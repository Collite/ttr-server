// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.cn

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory
import org.tatrman.concept.candidate.CandidateLoad
import org.tatrman.concept.candidate.CnCandidates
import org.tatrman.concept.candidate.Promotion
import org.tatrman.concept.candidate.Proposal
import org.tatrman.concept.candidate.Resolution
import org.tatrman.concept.candidate.Status
import org.tatrman.ttr.metadata.model.CnSchema
import org.tatrman.ttr.metadata.model.VocabularyVersion
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A submission's answer: the stored candidate, and whether this call created it (false: a duplicate, A-CN-92). */
data class Submitted(
    val candidate: StoredCandidate,
    val created: Boolean,
)

/**
 * The candidate store's API (cn contracts §6, CN-P5 S5.3): `submit` from registered producers, the steward's queue,
 * `reject`, `promote` (a model-text pull request; the candidate waits in [CandidateState.PENDING_MERGE] until a model
 * load shows the def — [observe] — and is then `promoted` with its `canon_ref`), and the importers' vocabulary versions.
 * Every change is an audit row ([CandidateStore.record]) and a notification.
 */
class CandidateService(
    private val store: CandidateStore,
    private val policy: CnPolicy,
    private val promoter: Promoter? = null,
    private val notifier: CandidateNotifier = CandidateNotifier.NONE,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** One promotion at a time: a patch is read, gated and delivered against one version of the source. */
    private val promoting = ReentrantLock()

    val promotionEnabled: Boolean get() = promoter != null

    fun submit(
        caller: CnCaller,
        body: String,
    ): Submitted {
        val c =
            when (val load = CnCandidates.parse(body)) {
                is CandidateLoad.Ok -> load.candidate
                is CandidateLoad.Rejected ->
                    throw CnRefused(
                        HttpStatusCode.BadRequest,
                        CN_CANDIDATE_INVALID,
                        "not a cn-candidate.v1: ${load.violations.size} violation(s)",
                        buildJsonObject {
                            putJsonArray("violations") {
                                for (v in load.violations) {
                                    add(
                                        buildJsonObject {
                                            put("path", v.path)
                                            put("rule", v.rule)
                                            put("message", v.message)
                                        },
                                    )
                                }
                            }
                        },
                    )
            }
        if (c.status != Status.PROPOSED) {
            throw CnRefused(
                HttpStatusCode.BadRequest,
                CN_CANDIDATE_NOT_PROPOSED,
                "a candidate is submitted proposed — ${c.status.word} is the steward's to set",
            )
        }
        policy.admit(caller, c.provenance.producer, c.proposal)?.let { throw it }
        (c.proposal as? Proposal.Term)?.proposedConceptRef?.let { ref ->
            val queued = store.get(ref)
            if (queued == null || queued.candidate.proposal !is Proposal.Concept) {
                throw CnRefused(
                    HttpStatusCode.UnprocessableEntity,
                    CN_CANDIDATE_UNKNOWN_REF,
                    "proposed_concept_ref $ref names no concept candidate in the store",
                )
            }
            // A term for a rejected concept could never be promoted: refuse it now, not at every promote (F10).
            if (queued.state == CandidateState.REJECTED) {
                throw CnRefused(
                    HttpStatusCode.UnprocessableEntity,
                    CN_CANDIDATE_UNKNOWN_REF,
                    "proposed_concept_ref $ref names a rejected concept candidate",
                )
            }
        }
        val now = now()
        val stored =
            StoredCandidate(c, CandidateState.PROPOSED, StoredCandidate.contentHash(c), caller.principal, now, now)
        return when (val r = store.insert(stored)) {
            is Inserted.Created -> {
                event(r.candidate, "submitted", caller.principal, c.provenance.producer)
                Submitted(r.candidate, created = true)
            }
            is Inserted.Duplicate -> {
                event(r.first, "duplicate", caller.principal, "${c.id} from ${c.provenance.producer}")
                Submitted(r.first, created = false)
            }
            is Inserted.IdTaken ->
                throw CnRefused(HttpStatusCode.Conflict, CN_CANDIDATE_ID_TAKEN, "id ${c.id} names another candidate")
        }
    }

    fun list(
        caller: CnCaller,
        q: CandidateQuery,
    ): List<StoredCandidate> {
        requireReader(caller)
        return store.find(q)
    }

    fun get(
        caller: CnCaller,
        id: String,
    ): Pair<StoredCandidate, List<CandidateEvent>> {
        requireReader(caller)
        val s = store.get(id) ?: throw notFound(id)
        return s to store.events(id)
    }

    fun reject(
        caller: CnCaller,
        id: String,
        note: String?,
    ): StoredCandidate {
        policy.requireSteward(caller, "reject")
        val s = store.get(id) ?: throw notFound(id)
        if (s.state != CandidateState.PROPOSED && s.state != CandidateState.PENDING_MERGE) throw resolved(s)
        val now = now()
        val rejected =
            s.copy(
                candidate =
                    s.candidate.copy(
                        status = Status.REJECTED,
                        resolution = Resolution(by = caller.principal, at = now.toString(), note = note),
                    ),
                state = CandidateState.REJECTED,
                updatedAt = now,
            )
        if (!store.replace(rejected, s.state)) throw changed(id)
        event(rejected, "rejected", caller.principal, note.orEmpty())
        return rejected
    }

    fun promote(
        caller: CnCaller,
        id: String,
    ): StoredCandidate {
        policy.requireSteward(caller, "promote")
        val p =
            promoter ?: throw CnRefused(
                HttpStatusCode.ServiceUnavailable,
                CN_PROMOTION_OFF,
                "promotion is off: no promotion source is configured (metadata.cn.promotion.source)",
            )
        return promoting.withLock {
            val s = store.get(id) ?: throw notFound(id)
            when (s.state) {
                CandidateState.PROPOSED -> Unit
                CandidateState.PENDING_MERGE ->
                    throw CnRefused(
                        HttpStatusCode.Conflict,
                        CN_PROMOTION_PENDING,
                        "candidate $id is promoted already, waiting for its merge" +
                            (s.promotion?.pullRequest?.let { ": $it" } ?: ""),
                    )
                else -> throw resolved(s)
            }
            val now = now()
            val record =
                try {
                    p.promote(s, caller.principal, now) { ref ->
                        store
                            .get(ref)
                            ?.takeIf {
                                it.state ==
                                    CandidateState.PROMOTED
                            }?.promotion
                            ?.qname
                    }
                } catch (e: CnRefused) {
                    event(s, "promotion_refused", caller.principal, "${e.code}: ${e.message}")
                    throw e
                }
            val pending = s.copy(state = CandidateState.PENDING_MERGE, updatedAt = now, promotion = record)
            if (!store.replace(pending, CandidateState.PROPOSED)) throw changed(id)
            val opened = record.pullRequest ?: record.inbox ?: record.qname
            val undelivered =
                record.undelivered.takeIf { it.isNotEmpty() }?.joinToString(
                    "; ",
                    " (not delivered: ",
                    ")",
                )
            event(pending, "promotion_opened", caller.principal, opened + undelivered.orEmpty())
            pending
        }
    }

    /**
     * Every pending promotion whose def [schema] now carries is `promoted` (the merge is what made it canon): the
     * resolution names the steward who promoted it, and `canon_ref` = [version] — the promotion source's commit this
     * load read — `:` the def's qname. Called on every model swap, the boot load included.
     *
     * A candidate rejected while its pull request was open is watched too: rejecting does not close the pull request,
     * and if it is merged anyway the model carries the def, so the store says `promoted` and the history keeps the
     * rejection (review-134 F7).
     */
    fun observe(
        schema: CnSchema,
        version: String,
    ): List<StoredCandidate> {
        if (version.isBlank()) return emptyList()
        val watched =
            store.find(CandidateQuery(state = CandidateState.PENDING_MERGE, limit = Int.MAX_VALUE)) +
                store
                    .find(CandidateQuery(state = CandidateState.REJECTED, limit = Int.MAX_VALUE))
                    .filter { it.promotion != null }
        return watched.mapNotNull { s ->
            val record = s.promotion ?: return@mapNotNull null
            if (!landed(s.candidate.proposal, record.qname, schema)) return@mapNotNull null
            val canonRef = Promotion.canonRef(version, record.qname)
            val now = now()
            val promoted =
                s.copy(
                    candidate =
                        s.candidate.copy(
                            status = Status.PROMOTED,
                            resolution = Resolution(by = record.by, at = now.toString(), canonRef = canonRef),
                        ),
                    state = CandidateState.PROMOTED,
                    updatedAt = now,
                )
            if (!store.replace(promoted, s.state)) return@mapNotNull null
            val after =
                if (s.state ==
                    CandidateState.REJECTED
                ) {
                    " (its pull request was merged after the rejection)"
                } else {
                    ""
                }
            event(promoted, "promoted", MODEL_LOAD, canonRef + after)
            promoted
        }
    }

    fun registerVocabulary(
        caller: CnCaller,
        vocabulary: String,
        version: String,
        bundle: String?,
    ): Pair<VocabularyVersion, Boolean> {
        if (!policy.isImporter(caller)) {
            throw CnRefused(
                HttpStatusCode.Forbidden,
                CnPolicy.CN_FORBIDDEN,
                "registering a vocabulary version needs the ${policy.importerRole} role",
            )
        }
        if (!VOCABULARY.matches(vocabulary) || version.isBlank() || version.length > MAX_VERSION) {
            throw CnRefused(
                HttpStatusCode.BadRequest,
                CN_VOCABULARY_INVALID,
                "a vocabulary id is a word ([A-Za-z0-9._-]); a version is 1–$MAX_VERSION characters",
            )
        }
        val v = VocabularyVersion(vocabulary, version.trim(), bundle?.trim()?.ifEmpty { null }, now())
        val registered = store.register(v, caller.principal)
        if (registered) log.info("cn vocabularies: {} {} registered by {}", vocabulary, v.version, caller.principal)
        return (if (registered) v else store.vocabularyVersions().first { it.key == v.key }) to registered
    }

    fun vocabularyVersions(vocabulary: String?): List<VocabularyVersion> =
        store.vocabularyVersions().filter { vocabulary == null || it.vocabulary == vocabulary }

    private fun requireReader(caller: CnCaller) {
        if (!policy.mayRead(caller)) {
            throw CnRefused(
                HttpStatusCode.Forbidden,
                CnPolicy.CN_FORBIDDEN,
                "the candidate queue is read by stewards (${policy.stewardRole}) and registered producers",
            )
        }
    }

    private fun event(
        c: StoredCandidate,
        action: String,
        actor: String,
        detail: String,
    ) {
        val e = CandidateEvent(c.id, action, actor, now(), detail)
        store.record(e)
        log.info("cn candidate {}: {} by {}{}", c.id, action, actor, if (detail.isEmpty()) "" else " — $detail")
        runCatching { notifier.notify(e, c) }.onFailure { log.warn("cn notify failed: {}", it.message) }
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.SECONDS)

    private fun notFound(id: String) = CnRefused(HttpStatusCode.NotFound, CN_CANDIDATE_NOT_FOUND, "no candidate $id")

    private fun resolved(s: StoredCandidate) =
        CnRefused(HttpStatusCode.Conflict, CN_CANDIDATE_RESOLVED, "candidate ${s.id} is ${s.state.word} already")

    private fun changed(id: String) =
        CnRefused(HttpStatusCode.Conflict, CN_CANDIDATE_CHANGED, "candidate $id changed meanwhile — read it again")

    companion object {
        const val CN_CANDIDATE_INVALID = "CN_CANDIDATE_INVALID"
        const val CN_CANDIDATE_NOT_PROPOSED = "CN_CANDIDATE_NOT_PROPOSED"
        const val CN_CANDIDATE_UNKNOWN_REF = "CN_CANDIDATE_UNKNOWN_REF"
        const val CN_CANDIDATE_ID_TAKEN = "CN_CANDIDATE_ID_TAKEN"
        const val CN_CANDIDATE_NOT_FOUND = "CN_CANDIDATE_NOT_FOUND"
        const val CN_CANDIDATE_RESOLVED = "CN_CANDIDATE_RESOLVED"
        const val CN_CANDIDATE_CHANGED = "CN_CANDIDATE_CHANGED"
        const val CN_PROMOTION_OFF = "CN_PROMOTION_OFF"
        const val CN_PROMOTION_PENDING = "CN_PROMOTION_PENDING"
        const val CN_VOCABULARY_INVALID = "CN_VOCABULARY_INVALID"

        /** The actor of what a model load decides (a merge seen). */
        const val MODEL_LOAD = "model-load"

        private const val MAX_VERSION = 200
        private val VOCABULARY = Regex("^[A-Za-z0-9._-]{1,100}$")
        private val log = LoggerFactory.getLogger(CandidateService::class.java)

        /** Whether [schema] carries what [proposal] proposed — by the proposal, not only the qname. */
        internal fun landed(
            proposal: Proposal,
            qname: String,
            schema: CnSchema,
        ): Boolean =
            when (proposal) {
                is Proposal.Concept -> schema.concepts.values.any { it.qname.name == proposal.name }
                is Proposal.Term -> {
                    val id = qname.removePrefix("lexicon.")
                    schema.concepts.values.any { c -> c.lexiconLinks.any { it.entryKind == "term" && it.name == id } }
                }
                is Proposal.Relation -> {
                    val from = proposal.from.removePrefix("cn.")
                    val to = proposal.to.removePrefix("cn.")
                    schema.concepts.values.any { c ->
                        c.qname.name == from &&
                            c.relations.any { it.typeName == proposal.typeName && it.to?.name == to }
                    }
                }
            }
    }
}
