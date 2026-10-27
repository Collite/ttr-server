// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.read

import kotlinx.serialization.json.Json
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilterGroup
import org.slf4j.LoggerFactory
import org.tatrman.ttr.metadata.source.ModelSource
import org.tatrman.ttr.metadata.source.SourceSnapshot
import java.nio.file.Path

/**
 * Adds read-only templates from the existing GitArchiveStorage checkout, at the load's SHA, never HEAD.
 * Per R171-B, a template read failure keeps this instance's previous successful catalog and an issue;
 * it never prevents the model from loading. A genuine inner model load failure still propagates.
 */
internal class GitTemplateSource(
    private val source: ModelSource,
    private val checkout: Path,
    modelDirectory: String,
) : ModelSource {
    private val modelRoot =
        runCatching {
            val normalized = Path.of(modelDirectory.ifBlank { "" }).normalize()
            require(normalized.none { it.toString() == ".." }) { "model subdirectory escapes the repository" }
            normalized.joinToString("/").trim('/')
        }

    private data class SuccessfulCatalog(
        val catalog: TemplateCatalog,
        val encoded: String,
    )

    private var previous: SuccessfulCatalog? = null

    @Synchronized
    override fun load(): SourceSnapshot {
        // Model failures deliberately retain their normal semantics.
        val snapshot = source.load()
        val encoded =
            try {
                previous?.takeIf { it.catalog.version == snapshot.version }?.encoded ?: run {
                    val files = filesAt(snapshot.version, modelRoot.getOrThrow())
                    val catalog =
                        templateCatalog(
                            snapshot.sourceId,
                            snapshot.priority,
                            files.content,
                            snapshot.version,
                            files.issues,
                        )
                    val successful = SuccessfulCatalog(catalog, Json.encodeToString(catalog))
                    previous = successful // Only successful complete reads are memoized; failed SHAs are retried.
                    successful.encoded
                }
            } catch (e: Exception) {
                val prior = previous?.catalog
                val issue =
                    "template source ${snapshot.sourceId}: could not read templates at ${snapshot.version.take(8)} " +
                        "(${e.javaClass.simpleName}: ${e.message.orEmpty().lineSequence().first()}); " +
                        (prior?.let { "serving templates of ${it.version.take(8)}" } ?: "no previous templates")
                log.warn(issue)
                Json.encodeToString(
                    (prior ?: TemplateCatalog(snapshot.sourceId, snapshot.priority)).let {
                        it.copy(
                            issues =
                                it.issues + issue,
                        )
                    },
                )
            }
        return snapshot.copy(
            exportSidecars =
                snapshot.exportSidecars + (TEMPLATE_SIDECAR_PREFIX + snapshot.sourceId to encoded),
        )
    }

    private data class TemplateFiles(
        val content: Map<String, ByteArray>,
        val issues: Map<String, String>,
    )

    private fun filesAt(
        version: String,
        root: String,
    ): TemplateFiles =
        Git.open(checkout.toFile()).use { git ->
            val repo = git.repository
            val prefix = if (root.isEmpty()) "" else "$root/"
            RevWalk(repo).use { revisions ->
                val commit = revisions.parseCommit(ObjectId.fromString(version))
                TreeWalk(repo).use { tree ->
                    tree.addTree(commit.tree)
                    tree.isRecursive = true
                    tree.filter = PathFilterGroup.createFromStrings(listOf("${prefix}reports", "${prefix}forms"))
                    val files = mutableMapOf<String, ByteArray>()
                    val issues = mutableMapOf<String, String>()
                    while (tree.next()) {
                        val path = tree.pathString
                        if (!path.startsWith(prefix)) continue
                        // A blank root is the repository root; never strip an extra model/ tree.
                        val publicPath = "model/" + path.removePrefix(prefix)
                        if (!REPORT_PATH.matches(publicPath) &&
                            !FORM_PATH.matches(publicPath) &&
                            !WORKBOOK_PATH.matches(publicPath)
                        ) {
                            continue
                        }
                        val mode = tree.getFileMode(0)
                        if (mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE) {
                            issues[publicPath] = "not a regular file (symlink/submodule)"
                            continue
                        }
                        // Any blob read exception aborts this whole catalog, never mixing old/new documents.
                        files[publicPath] = repo.open(tree.getObjectId(0)).bytes
                    }
                    TemplateFiles(files, issues)
                }
            }
        }

    private companion object {
        val WORKBOOK_PATH = Regex("model/reports/[^/]+/template\\.xlsx")
        val log = LoggerFactory.getLogger(GitTemplateSource::class.java)
    }
}
