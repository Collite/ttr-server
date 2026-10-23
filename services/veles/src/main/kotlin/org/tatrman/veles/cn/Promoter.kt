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
import org.tatrman.ttr.metadata.source.ModelStorage
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.relativeTo

/** The promotion source's model text: every `.ttrm` file by its path under the model root, and the [version] read. */
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
 * the refresher's pull of the served source. The version is read on both sides of the listing; a commit landing in
 * between is read again, once.
 */
class StoragePromotionFiles(
    private val storage: ModelStorage,
) : PromotionFiles {
    override fun read(): ModelText {
        repeat(2) {
            val version = storage.fetchVersion()
            val files =
                storage.listFiles(listOf("ttrm")).associate { f ->
                    val path = Path.of(f.path)
                    val rel = f.rootPath?.let { path.relativeTo(it) } ?: path.fileName
                    rel.invariantSeparatorsPathString to storage.read(f)
                }
            if (storage.fetchVersion() == version) return ModelText(version, files)
        }
        throw CnRefused(
            HttpStatusCode.ServiceUnavailable,
            Promoter.CN_PROMOTION_SOURCE_MOVING,
            "the promotion source changed twice while it was read — try again",
        )
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
 */
class Promoter(
    private val files: PromotionFiles,
    private val deliveries: List<PromotionDelivery> = emptyList(),
    private val gate: PromotionGate = PromotionGate(),
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
        val pending = PendingPromotion(s.id, patch, text.version, pr, at)
        var delivered = Delivered()
        for (d in deliveries) {
            val r = d.deliver(pending)
            delivered = Delivered(delivered.pullRequest ?: r.pullRequest, delivered.inbox ?: r.inbox)
        }
        return PromotionRecord(
            qname = patch.qname,
            diff = patch.diff,
            title = pr.title,
            body = pr.body,
            base = text.version,
            by = by,
            at = at,
            pullRequest = delivered.pullRequest,
            inbox = delivered.inbox,
            findings = gated.newWarnings,
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
    }
}
