// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.cn

import org.tatrman.concept.candidate.CnCandidate
import org.tatrman.concept.candidate.CnCandidates
import org.tatrman.concept.candidate.Proposal
import org.tatrman.ttr.metadata.model.VocabularyVersion
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Where a candidate is in the steward's loop. The `cn-candidate.v1` document knows three statuses; the store adds
 * [PENDING_MERGE] — a promotion opened, its pull request not merged yet — and keeps the document `proposed` meanwhile,
 * so every candidate the API returns is still a valid `cn-candidate.v1` (A-CN-90).
 */
enum class CandidateState(
    val word: String,
) {
    PROPOSED("proposed"),
    PENDING_MERGE("pending_merge"),
    REJECTED("rejected"),
    PROMOTED("promoted"),
    ;

    companion object {
        fun of(word: String): CandidateState? = entries.firstOrNull { it.word == word }
    }
}

/**
 * An opened promotion (cn contracts §6): the def it adds ([qname]), the patch and the pull request's text, the
 * promotion source's version the patch is against ([base]), who opened it, where it went — a provider's pull request,
 * an inbox file, or only this record (the API serves the diff either way) — the gate's new non-error findings, and any
 * delivery that failed after another had gone out ([undelivered], review-134 F2).
 */
data class PromotionRecord(
    val qname: String,
    val diff: String,
    val title: String,
    val body: String,
    val base: String,
    val by: String,
    val at: Instant,
    val pullRequest: String? = null,
    val inbox: String? = null,
    val findings: List<String> = emptyList(),
    val undelivered: List<String> = emptyList(),
)

/** A candidate as the store keeps it: the document, the store's [state], and what the store knows about it. */
data class StoredCandidate(
    val candidate: CnCandidate,
    val state: CandidateState,
    val contentHash: String,
    val submittedBy: String,
    val submittedAt: Instant,
    val updatedAt: Instant,
    val promotion: PromotionRecord? = null,
) {
    val id: String get() = candidate.id

    /** What `stereotype=` filters on: a concept's stereotype, else `term` or `relation`. */
    val kind: String get() = kindOf(candidate.proposal)

    val questionCount: Int? get() = candidate.provenance.evidence?.questionCount

    val documentCount: Int? get() = candidate.provenance.evidence?.documentCount

    companion object {
        fun kindOf(p: Proposal): String =
            when (p) {
                is Proposal.Concept -> p.stereo.word
                is Proposal.Term -> "term"
                is Proposal.Relation -> "relation"
            }

        /**
         * The dedupe key (A-CN-92): SHA-256 of the proposal's canonical JSON — the schema's key order, label locales
         * sorted (`CnCandidates.toJson`). The proposal only: the same proposal from another source is the same proposal.
         */
        fun contentHash(c: CnCandidate): String {
            val canonical = CnCandidates.toJson(c).getValue("proposal").toString()
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            return "sha256:" + digest.joinToString("") { "%02x".format(it) }
        }
    }
}

/** One audit row: what happened to a candidate, who did it, when. */
data class CandidateEvent(
    val candidateId: String,
    val action: String,
    val actor: String,
    val at: Instant,
    val detail: String = "",
)

enum class CandidateSort { NEWEST, EVIDENCE }

/** `GET /v1/cn/candidates`' question. [kind] is a stereotype word, `term` or `relation`. */
data class CandidateQuery(
    val state: CandidateState? = null,
    val producer: String? = null,
    val kind: String? = null,
    val sort: CandidateSort = CandidateSort.NEWEST,
    val limit: Int = DEFAULT_LIMIT,
) {
    companion object {
        const val DEFAULT_LIMIT = 100
        const val MAX_LIMIT = 500
    }
}

sealed interface Inserted {
    data class Created(
        val candidate: StoredCandidate,
    ) : Inserted

    /** The proposal is already stored (A-CN-92): [first] is the candidate that carries it, whatever its state. */
    data class Duplicate(
        val first: StoredCandidate,
    ) : Inserted

    /** The id names another proposal. */
    data class IdTaken(
        val other: StoredCandidate,
    ) : Inserted
}

/** The candidate store (cn contracts §6, A-CN-90): candidates, their audit rows, the registered vocabulary versions. */
interface CandidateStore : AutoCloseable {
    fun insert(c: StoredCandidate): Inserted

    fun get(id: String): StoredCandidate?

    fun find(q: CandidateQuery): List<StoredCandidate>

    /** Replaces the candidate [c] names when it is still in [expected]; false when another write got there first. */
    fun replace(
        c: StoredCandidate,
        expected: CandidateState,
    ): Boolean

    fun record(e: CandidateEvent)

    /** [id]'s audit rows, oldest first. */
    fun events(id: String): List<CandidateEvent>

    /** Registers [v] unless its (vocabulary, version) is registered already; true when this call registered it. */
    fun register(
        v: VocabularyVersion,
        by: String,
    ): Boolean

    /** Every registered version, in registration order. */
    fun vocabularyVersions(): List<VocabularyVersion>

    override fun close() = Unit
}

/** Ordering shared by both stores: newest first, or the evidence counts first (T6, the K queue), oldest first within. */
internal fun CandidateSort.comparator(): Comparator<StoredCandidate> =
    when (this) {
        CandidateSort.NEWEST -> compareByDescending<StoredCandidate> { it.submittedAt }.thenBy { it.id }
        CandidateSort.EVIDENCE ->
            compareBy<StoredCandidate, Int?>(nullsLast(reverseOrder())) { it.questionCount }
                .thenBy(nullsLast(reverseOrder())) { it.documentCount }
                .thenBy { it.submittedAt }
                .thenBy { it.id }
    }

/** The store the tests run on, and a reference for the Postgres one: same answers, nothing kept across restarts. */
class InMemoryCandidateStore : CandidateStore {
    private val candidates = ConcurrentHashMap<String, StoredCandidate>()
    private val events = mutableListOf<CandidateEvent>()
    private val versions = mutableListOf<VocabularyVersion>()

    @Synchronized
    override fun insert(c: StoredCandidate): Inserted {
        candidates.values.firstOrNull { it.contentHash == c.contentHash }?.let { return Inserted.Duplicate(it) }
        candidates[c.id]?.let { return Inserted.IdTaken(it) }
        candidates[c.id] = c
        return Inserted.Created(c)
    }

    override fun get(id: String): StoredCandidate? = candidates[id]

    override fun find(q: CandidateQuery): List<StoredCandidate> =
        candidates.values
            .asSequence()
            .filter { q.state == null || it.state == q.state }
            .filter { q.producer == null || it.candidate.provenance.producer == q.producer }
            .filter { q.kind == null || it.kind == q.kind }
            .sortedWith(q.sort.comparator())
            .take(q.limit)
            .toList()

    @Synchronized
    override fun replace(
        c: StoredCandidate,
        expected: CandidateState,
    ): Boolean {
        if (candidates[c.id]?.state != expected) return false
        candidates[c.id] = c
        return true
    }

    @Synchronized
    override fun record(e: CandidateEvent) {
        events += e
    }

    @Synchronized
    override fun events(id: String): List<CandidateEvent> = events.filter { it.candidateId == id }

    @Synchronized
    override fun register(
        v: VocabularyVersion,
        by: String,
    ): Boolean {
        if (versions.any { it.key == v.key }) return false
        versions += v
        return true
    }

    @Synchronized
    override fun vocabularyVersions(): List<VocabularyVersion> = versions.toList()
}
