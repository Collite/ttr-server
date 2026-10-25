// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.cn

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.putJsonArray
import org.tatrman.concept.candidate.Promotion
import org.tatrman.concept.candidate.PromotionPatch
import org.tatrman.concept.candidate.PromotionResult
import org.tatrman.concept.candidate.Proposal
import org.tatrman.concept.candidate.PullRequest
import org.tatrman.concept.candidate.Stereo
import org.slf4j.LoggerFactory
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.metadata.source.ModelStorage
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.relativeTo

/** The promotion source's model text: every model file by its path under the model root, and the [version] read. */
data class ModelText(
    val version: String,
    val files: Map<String, String>,
)

/** Where a promotion reads the model text it patches. */
fun interface PromotionFiles {
    fun read(): ModelText
}

/**
 * The promotion source's files through a [ModelStorage] of its own (A-CN-93): its own checkout, so reading never races
 * the refresher's pull of the served source. Every file the loader reads (`.ttr` and `.ttrm`), so the gate loads the
 * whole model; the patch itself edits `.ttrm` only (review-134 F4).
 *
 * With [localRoot] — that checkout's working tree at the model's sub-path — one fetch gives the version and the files
 * are read from the tree: nothing else moves a promotion checkout, and promotions run one at a time (F9: a git storage
 * fetches on every call, so version · listing · version cost three fetches). Without it, the version is read on both
 * sides of the listing, and a commit landing in between is read again, once. A source that cannot be read is
 * `503 CN_PROMOTION_SOURCE_UNAVAILABLE` (F3).
 */
class StoragePromotionFiles(
    private val storage: ModelStorage,
    private val localRoot: Path? = null,
) : PromotionFiles {
    override fun read(): ModelText =
        try {
            localRoot?.let { root ->
                ModelText(storage.fetchVersion(), files(LocalFsStorage(id = "promotion", rootPath = root)))
            }
                ?: readMoving()
        } catch (e: CnRefused) {
            throw e
        } catch (e: Exception) {
            throw CnRefused(
                HttpStatusCode.ServiceUnavailable,
                Promoter.CN_PROMOTION_SOURCE_UNAVAILABLE,
                "the promotion source could not be read: ${e.message}",
            )
        }

    private fun readMoving(): ModelText {
        repeat(2) {
            val version = storage.fetchVersion()
            val files = files(storage)
            if (storage.fetchVersion() == version) return ModelText(version, files)
        }
        throw CnRefused(
            HttpStatusCode.ServiceUnavailable,
            Promoter.CN_PROMOTION_SOURCE_MOVING,
            "the promotion source changed twice while it was read — try again",
        )
    }

    private fun files(from: ModelStorage): Map<String, String> =
        from.listFiles(MODEL_EXTENSIONS).associate { f ->
            val path = Path.of(f.path)
            val rel = f.rootPath?.let { path.relativeTo(it) } ?: path.fileName
            rel.invariantSeparatorsPathString to from.read(f)
        }

    companion object {
        /** The loader's model extensions (ttr-metadata reads both). */
        val MODEL_EXTENSIONS = listOf("ttr", "ttrm")
    }
}

/** A promotion ready to go out: the patch against [base], and the pull request's text. */
data class PendingPromotion(
    val candidateId: String,
    val patch: PromotionPatch,
    val base: String,
    val pullRequest: PullRequest,
    val at: Instant,
)

/** Where a delivery put it: a provider's pull request, or an inbox file. */
data class Delivered(
    val pullRequest: String? = null,
    val inbox: String? = null,
)

/** One way a promotion leaves the store; a failure throws [CnRefused] (nothing is recorded then). */
fun interface PromotionDelivery {
    fun deliver(p: PendingPromotion): Delivered
}

/**
 * Promotion = a model TEXT change (cn contracts §6, A-CN-84): the S5.2 patch against the promotion source's files, the
 * gate (A-CN-88), the pull request's text — a phase's er rendering in it, the gate's new warnings under it — then every
 * configured [deliveries] in order. With none, the store's record is the inbox: the API serves its diff (A-CN-93).
 *
 * [modelRoot] is the model's path in its repository (the source's sub-path). Every diff the store hands out — the
 * record's, so the API's, and the inbox file — carries it, so `git apply` takes it at the repository root, the paths
 * the pull request shows. A model-relative diff applied in the model's directory is SKIPPED by `git apply`, silently
 * (it reads patch paths from the repository root and ignores those outside the current directory): the S5.4 walk.
 */
class Promoter(
    private val files: PromotionFiles,
    private val deliveries: List<PromotionDelivery> = emptyList(),
    private val gate: PromotionGate = PromotionGate(),
    private val modelRoot: String = "",
) {
    fun promote(
        s: StoredCandidate,
        by: String,
        at: Instant,
        promoted: (String) -> String?,
    ): PromotionRecord {
        val text = files.read()
        val patch =
            when (val r = Promotion.patch(s.candidate, text.files, promoted)) {
                is PromotionResult.Refused ->
                    throw CnRefused(HttpStatusCode.UnprocessableEntity, CN_PROMOTION_REFUSED, r.reason)
                is PromotionResult.Patched -> r.patch
            }
        val proposal = s.candidate.proposal
        val phase = proposal is Proposal.Concept && proposal.stereo == Stereo.PHASE && proposal.definitionText != null
        val gated = gate.check(text.files, text.files + patch.files, patch.qname, renderEr = phase)
        if (gated.newErrors.isNotEmpty()) {
            throw CnRefused(
                HttpStatusCode.UnprocessableEntity,
                CN_PROMOTION_GATE,
                "the patched model has ${gated.newErrors.size} new error(s): ${gated.newErrors.joinToString("; ")}",
                buildJsonObject { putJsonArray("findings") { gated.newErrors.forEach { add(JsonPrimitive(it)) } } },
            )
        }
        val pr = Promotion.pullRequest(s.candidate, patch, gated.erRendering).withFindings(gated.newWarnings)
        val rooted = patch.copy(diff = rootedDiff(patch.diff, modelRoot))
        val pending = PendingPromotion(s.id, rooted, text.version, pr, at)
        var delivered = Delivered()
        val undelivered = mutableListOf<String>()
        for (d in deliveries) {
            val r =
                try {
                    d.deliver(pending)
                } catch (e: Exception) {
                    val refused =
                        e as? CnRefused
                            ?: CnRefused(
                                HttpStatusCode.BadGateway,
                                CN_PROMOTION_DELIVERY,
                                "a delivery failed: ${e.message}",
                            )
                    // Nothing has gone out: refuse, and a retry starts clean. Something has (a pull request is open):
                    // the promotion stands with the failure beside it — refusing now would open a second pull request
                    // on the retry and orphan this one (review-134 F2).
                    if (delivered.pullRequest == null && delivered.inbox == null) throw refused
                    log.warn("cn: promotion of {} went out, but a later delivery failed: {}", s.id, refused.message)
                    undelivered += "${refused.code}: ${refused.message}"
                    continue
                }
            delivered = Delivered(delivered.pullRequest ?: r.pullRequest, delivered.inbox ?: r.inbox)
        }
        return PromotionRecord(
            qname = patch.qname,
            diff = rooted.diff,
            title = pr.title,
            body = pr.body,
            base = text.version,
            by = by,
            at = at,
            pullRequest = delivered.pullRequest,
            inbox = delivered.inbox,
            findings = gated.newWarnings,
            undelivered = undelivered,
        )
    }

    /** The gate's non-error findings under the body, each an inline code span (the model's own words, inert). */
    private fun PullRequest.withFindings(findings: List<String>): PullRequest {
        if (findings.isEmpty()) return this
        val list = findings.joinToString("\n") { "- `${it.replace("`", "'")}`" }
        return copy(body = "$body\n### New findings (not errors)\n\n$list\n")
    }

    companion object {
        const val CN_PROMOTION_REFUSED = "CN_PROMOTION_REFUSED"
        const val CN_PROMOTION_GATE = "CN_PROMOTION_GATE"
        const val CN_PROMOTION_SOURCE_MOVING = "CN_PROMOTION_SOURCE_MOVING"
        const val CN_PROMOTION_SOURCE_UNAVAILABLE = "CN_PROMOTION_SOURCE_UNAVAILABLE"
        const val CN_PROMOTION_DELIVERY = "CN_PROMOTION_DELIVERY"

        private val log = LoggerFactory.getLogger(Promoter::class.java)

        private val GIT_HEADER = Regex("^diff --git a/(\\S+) b/(\\S+)$")

        /**
         * [diff] with [root] before every path — in the file headers only (`diff --git`, `---`, `+++`, up to a file's
         * first hunk), never in a hunk line that happens to start the same way. A blank root leaves it as it is.
         */
        internal fun rootedDiff(
            diff: String,
            root: String,
        ): String {
            val prefix = root.trim('/').ifEmpty { return diff } + "/"
            var header = false
            return diff.lines().joinToString("\n") { line ->
                when {
                    line.startsWith("diff --git ") -> {
                        header = true
                        val (from, to) = GIT_HEADER.matchEntire(line)?.destructured ?: return@joinToString line
                        "diff --git a/$prefix$from b/$prefix$to"
                    }
                    line.startsWith("@@") -> {
                        header = false
                        line
                    }
                    header && (line.startsWith("--- a/") || line.startsWith("+++ b/")) ->
                        line.substring(0, 6) + prefix + line.substring(6)
                    else -> line
                }
            }
        }
    }
}
