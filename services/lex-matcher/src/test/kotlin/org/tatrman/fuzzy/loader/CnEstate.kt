// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import org.tatrman.ttr.lexicon.CompiledLexicon
import org.tatrman.ttr.lexicon.compile.LexiconBuild
import org.tatrman.ttr.lexicon.compile.LexiconBuildOutcome
import org.tatrman.ttr.metadata.LoadIssue
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.source.BuiltinStockSource
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/**
 * CN-P4 S4.3 — the conceptual tier's shared fixture estate (`shared/testdata/cn-estate`), compiled HERE by the real
 * producer at the pinned grammar: `LexiconBuild.run`, as `ttr-lexicon build` runs it, without the stdlib (the golden's
 * own setting). So the archive this service reads is the v6 archive ttr-core's golden holds, never a hand-written one.
 * metadata loads the same estate's model (S4.2), and the resolver reads the same archive (S4.4).
 */
internal object CnEstate {
    /** What the golden was compiled with: `expected/lexicon.json`'s header. */
    const val SNAPSHOT_ID = "sha256:abababababababababababababababababababababababababababababababab"
    const val BUILT_AT = "2026-10-22T00:00:00Z"

    val root: Path = Path.of(System.getProperty("cnEstate") ?: error("cnEstate not set"))

    /** The model as the CLI loads it: stock roles + the estate's `model/`, the package/directory mismatch tolerated. */
    private fun model(root: Path): Model {
        val result =
            MetadataLoader(
                listOf(
                    BuiltinStockSource(),
                    FileBasedSource("repo", 100, LocalFsStorage("repo", root.resolve("model"))),
                ),
            ).load()
        val fatal = result.errors.filter { it.category != LoadIssue.Category.PACKAGE_MISMATCH }
        check(fatal.isEmpty()) { fatal.joinToString("\n") { "${it.file}:${it.line} ${it.message}" } }
        return result.model!!
    }

    fun build(root: Path = this.root): LexiconBuildOutcome =
        LexiconBuild.run(root, model(root), SNAPSHOT_ID, BUILT_AT, "cn-estate-test", includeStdlib = false).also {
            check(it.ok) { "the estate's lexicon files do not parse: ${it.violations}" }
        }

    /** The v6 lexicon ttr-core's `CnArchiveV6Spec` holds byte-stable over this estate. */
    fun golden(): CompiledLexicon = CompiledLexicon.fromJson(root.resolve("expected/lexicon.json").readText())

    /** [outcome]'s archive, written to [path]. */
    fun pack(
        outcome: LexiconBuildOutcome,
        path: Path,
    ): Path = path.also { it.writeBytes(outcome.packed.bytes) }

    /** A writable copy of the estate, [edit]ed. */
    fun variant(edit: (Path) -> Unit): Path {
        val to = Files.createTempDirectory("cn-estate")
        Files.walk(root).use { paths ->
            for (p in paths.toList()) {
                val dest = to.resolve(root.relativize(p).toString())
                if (Files.isDirectory(p)) dest.createDirectories() else Files.copy(p, dest)
            }
        }
        return to.also(edit)
    }

    /** One textual replacement in an estate file, which must happen. */
    fun Path.replace(
        old: String,
        new: String,
    ) {
        val text = readText()
        check(old in text) { "'$old' is not in $this" }
        writeText(text.replace(old, new))
    }

    /**
     * The same estate without its conceptual tier: no `model cn`, no bindings, no concept terms. The er and md model
     * and every element and member term stay — a v5-shaped build of the same package. A concept term's line is
     * blanked, not removed: every other term keeps its provenance line, which the merge's precedence reads.
     */
    fun withoutCn(): Path =
        variant { dir ->
            dir.resolve("model/cn").toFile().deleteRecursively()
            dir.resolve("model/binding").toFile().deleteRecursively()
            for (lang in listOf("cs", "en")) {
                val terms = dir.resolve("model/lexicon/$lang/terms.ttrm")
                terms.writeText(
                    terms.readLines().joinToString("\n", postfix = "\n") {
                        if ("for: cn." in
                            it
                        ) {
                            ""
                        } else {
                            it
                        }
                    },
                )
            }
        }
}
