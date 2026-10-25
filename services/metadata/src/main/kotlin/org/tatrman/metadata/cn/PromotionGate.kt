// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.cn

import io.ktor.http.HttpStatusCode
import org.tatrman.concept.CnConceptService
import org.tatrman.concept.Layer
import org.tatrman.concept.RenderOptions
import org.tatrman.concept.ResolvedCnModel
import org.tatrman.concept.Severity
import org.tatrman.concept.v1.ConceptRef
import org.tatrman.ttr.metadata.LoadIssue
import org.tatrman.ttr.metadata.LoadOptions
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttrp.resolve.MdRepo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** What the gate found: the findings the patch adds ([newErrors] refuse it) and, for a phase, its er rendering. */
data class GateResult(
    val newErrors: List<String>,
    val newWarnings: List<String>,
    val erRendering: String?,
)

/**
 * The S5.3 gate (cn contracts §6, A-CN-88): before a promotion goes anywhere, load the model text before and after the
 * patch through the **strict** load report (`duplicate-qname` and friends, which the plain snapshot cannot see) plus the
 * cn definition diagnostics, and compare. A finding the patch adds is new — an error refuses the promotion, anything
 * else rides in the pull request. A phase is rendered to er from the patched model (the `definition.render` door's own
 * function, in process) for the reviewer to read.
 *
 * Findings compare as text without line numbers: a relation inserted mid-file moves every finding below it.
 */
class PromotionGate {
    fun check(
        before: Map<String, String>,
        after: Map<String, String>,
        qname: String,
        renderEr: Boolean,
    ): GateResult {
        val temps = mutableListOf<Path>()
        try {
            val original = load(materialize(before, temps), patched = false)
            val patched = load(materialize(after, temps), patched = true)
            val fresh = patched.findings.keys - original.findings.keys
            val (errors, others) = fresh.sorted().partition { patched.findings.getValue(it) }
            val er = if (renderEr) render(patched.cn, qname) else null
            return GateResult(errors, others + er?.second.orEmpty(), er?.first)
        } finally {
            temps.forEach { it.toFile().deleteRecursively() }
        }
    }

    private class Loaded(
        /** Each finding, and whether it is an error. */
        val findings: Map<String, Boolean>,
        val cn: ResolvedCnModel,
    )

    /**
     * A model that does not load at all is a refusal, never an exception (review-134 F3): the source's own model is the
     * source's problem (`503 CN_PROMOTION_SOURCE_UNAVAILABLE`), the patched model the patch's (`422 CN_PROMOTION_GATE`).
     */
    private fun load(
        dir: Path,
        patched: Boolean,
    ): Loaded {
        val result =
            MetadataLoader(FileBasedSource("promotion", 100, LocalFsStorage(id = "promotion", rootPath = dir)))
                .load(LoadOptions(strict = true))
        val model =
            result.model ?: throw if (patched) {
                CnRefused(
                    HttpStatusCode.UnprocessableEntity,
                    Promoter.CN_PROMOTION_GATE,
                    "the patched model does not load: ${result.errors.firstOrNull()?.message}",
                )
            } else {
                CnRefused(
                    HttpStatusCode.ServiceUnavailable,
                    Promoter.CN_PROMOTION_SOURCE_UNAVAILABLE,
                    "the promotion source's model does not load: ${result.errors.firstOrNull()?.message}",
                )
            }
        val cn = ResolvedCnModel.of(model, snapshotHash = "promotion", md = MdRepo.loadFrom(dir))

        fun clean(text: String) = text.replace("$dir/", "").replace("${dir.fileName}/", "")
        val report =
            result.report
                ?.issues
                .orEmpty()
                .associate { i ->
                    "${i.code} ${i.severity.name.lowercase()} ${clean(i.file.orEmpty())} ${clean(i.message)}".trim() to
                        (i.severity == LoadIssue.Severity.ERROR)
                }
        val definitions =
            cn.definitionDiagnostics().associate { d ->
                "${d.slug} ${d.severity.name.lowercase()} ${d.subject}".trim() to (d.severity == Severity.ERROR)
            }
        return Loaded(report + definitions, cn)
    }

    /** The rendering, or null and why not (as findings) when the renderer answers no text. */
    private fun render(
        cn: ResolvedCnModel,
        qname: String,
    ): Pair<String?, List<String>> {
        val r =
            CnConceptService(cn).definitionRender(
                ConceptRef.newBuilder().setQname(qname).build(),
                Layer.ER,
                "",
                RenderOptions(),
            )
        if (r.text.isNotBlank()) return r.text to emptyList()
        return null to r.diagnostics.map { "er rendering: ${it.slug} ${it.message}".trim() }
    }

    private fun materialize(
        files: Map<String, String>,
        temps: MutableList<Path>,
    ): Path {
        val dir = Files.createTempDirectory("cn-promotion").also { temps.add(it) }
        for ((rel, text) in files) {
            dir.resolve(rel).also { it.parent.createDirectories() }.writeText(text)
        }
        return dir
    }
}
