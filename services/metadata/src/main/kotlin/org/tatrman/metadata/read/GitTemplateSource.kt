// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.read

import kotlinx.serialization.json.Json
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.tatrman.ttr.metadata.source.ModelSource
import org.tatrman.ttr.metadata.source.SourceSnapshot
import java.nio.file.Path

/**
 * Adds read-only templates from the SAME GitArchiveStorage checkout, at the load's SHA, never HEAD.
 * The registry/refresher already transports raw sidecars with each source contribution. Keeping the
 * catalog there avoids a second mutable index or a listener racing with the atomic model swap.
 */
internal class GitTemplateSource(
    private val source: ModelSource,
    private val checkout: Path,
    private val modelDirectory: String,
) : ModelSource {
    override fun load(): SourceSnapshot {
        val snapshot = source.load()
        val catalog = templateCatalog(snapshot.sourceId, snapshot.priority, filesAt(snapshot.version))
        return snapshot.copy(
            exportSidecars =
                snapshot.exportSidecars +
                    (TEMPLATE_SIDECAR_PREFIX + snapshot.sourceId to Json.encodeToString(catalog)),
        )
    }

    private fun filesAt(version: String): Map<String, ByteArray> =
        Git.open(checkout.toFile()).use { git ->
            val repo = git.repository
            RevWalk(repo).use { revisions ->
                val commit = revisions.parseCommit(ObjectId.fromString(version))
                TreeWalk(repo).use { tree ->
                    tree.addTree(commit.tree)
                    tree.isRecursive = true
                    buildMap {
                        while (tree.next()) {
                            val path = tree.pathString
                            // Configured subdirectory is the model root (normally "model"). Public
                            // source paths use the contract's model/ prefix, even for a nested world.
                            val relative =
                                if (modelDirectory.isBlank()) {
                                    path.removePrefix("model/")
                                } else {
                                    if (!path.startsWith("$modelDirectory/")) continue
                                    path.removePrefix("$modelDirectory/")
                                }
                            val publicPath = "model/$relative"
                            if (!REPORT_PATH.matches(publicPath) &&
                                !FORM_PATH.matches(publicPath) &&
                                !WORKBOOK_PATH.matches(publicPath)
                            ) {
                                continue
                            }
                            val mode = tree.getFileMode(0)
                            // Never follow symlinks to mutable or out-of-tree content.
                            if (mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE) continue
                            put(publicPath, repo.open(tree.getObjectId(0)).bytes)
                        }
                    }
                }
            }
        }

    private companion object {
        val WORKBOOK_PATH = Regex("model/reports/[^/]+/template\\.xlsx")
    }
}
