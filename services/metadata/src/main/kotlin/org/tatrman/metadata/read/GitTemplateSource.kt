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
 * Per R171-B, a template read failure keeps the published catalog and an issue;
 * it never prevents the model from loading. A genuine inner model load failure still propagates.
 */
internal class GitTemplateSource(
    private val source: ModelSource,
    private val checkout: Path,
    modelDirectory: String,
    sourceId: String,
    priority: Int,
    private val served: () -> String? = { null },
) : ModelSource {
    private val modelRoot =
        runCatching {
            val path = Path.of(modelDirectory.ifBlank { "" })
            require(!path.isAbsolute) { "absolute" }
            val normalized = path.normalize()
            require(normalized.none { it.toString() == ".." }) { "escapes the repository" }
            normalized.joinToString("/").trim('/')
        }
    private val invalidRootCatalog =
        modelRoot.exceptionOrNull()?.let { cause ->
            val issue =
                "template source $sourceId: invalid subdirectory '$modelDirectory' " +
                    "(${cause.message}); no templates served"
            log.warn(issue)
            Json.encodeToString(TemplateCatalog(sourceId, priority, issues = listOf(issue)))
        }

    private data class SuccessfulCatalog(
        val version: String,
        val encoded: String,
    )

    private data class FailureKey(
        val attemptedVersion: String,
        val priorVersion: String?,
        val cause: String,
    )

    private data class FailedCatalog(
        val key: FailureKey,
        val encoded: String,
    )

    private var previous: SuccessfulCatalog? = null
    private var lastFailure: FailedCatalog? = null

    @Synchronized
    override fun load(): SourceSnapshot {
        // Model failures deliberately retain their normal semantics, including Errors.
        val snapshot = source.load()
        val encoded =
            invalidRootCatalog ?: try {
                val encoded =
                    previous?.takeIf { it.version == snapshot.version }?.encoded ?: run {
                        val files = filesAt(snapshot.version, modelRoot.getOrThrow())
                        val catalog =
                            templateCatalog(
                                snapshot.sourceId,
                                snapshot.priority,
                                files.content,
                                snapshot.version,
                                files.issues,
                            )
                        val successful = SuccessfulCatalog(snapshot.version, Json.encodeToString(catalog))
                        previous = successful // Only successful complete reads are memoized; failed SHAs are retried.
                        successful.encoded
                    }
                lastFailure = null
                encoded
            } catch (e: Exception) {
                failedCatalog(snapshot, "${e.javaClass.simpleName}: ${e.message.orEmpty().lineSequence().first()}")
            } catch (e: StackOverflowError) {
                failedCatalog(snapshot, "StackOverflowError: nesting too deep")
            }
        return snapshot.copy(
            exportSidecars =
                snapshot.exportSidecars + (TEMPLATE_SIDECAR_PREFIX + snapshot.sourceId to encoded),
        )
    }

    private fun failedCatalog(
        snapshot: SourceSnapshot,
        cause: String,
    ): String {
        val prior = served()?.let { SuccessfulCatalog(Json.decodeFromString<TemplateCatalog>(it).version, it) }
        val key = FailureKey(snapshot.version, prior?.version, cause)
        lastFailure?.takeIf { it.key == key }?.let {
            log.debug("Unchanged template read failure: {}", key)
            return it.encoded
        }
        val issue =
            "template source ${snapshot.sourceId}: could not read templates at ${snapshot.version.take(8)} " +
                "($cause); " +
                (prior?.let { "serving templates of ${it.version.take(8)}" } ?: "no previous templates")
        log.warn(issue)
        val catalog =
            prior?.let { Json.decodeFromString<TemplateCatalog>(it.encoded) }
                ?: TemplateCatalog(snapshot.sourceId, snapshot.priority)
        val retainedIssues =
            catalog.issues.filterNot {
                it.startsWith("template source ${snapshot.sourceId}: could not read templates at ")
            }
        val encoded = Json.encodeToString(catalog.copy(issues = retainedIssues + issue))
        lastFailure = FailedCatalog(key, encoded)
        return encoded
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
                val issues = mutableMapOf<String, String>()
                val checked = mutableSetOf<String>()
                val roots =
                    listOf("${prefix}reports", "${prefix}forms").filter { target ->
                        val components = target.split('/')
                        var available = true
                        for (i in components.indices) {
                            val component = components.take(i + 1).joinToString("/")
                            if (!checked.add(component)) {
                                if (component in issues) {
                                    available = false
                                    break
                                }
                                continue
                            }
                            TreeWalk.forPath(repo, component, commit.tree).use { entry ->
                                if (entry == null) {
                                    available = false // Missing template trees are valid and stay silent.
                                } else if (entry.getFileMode(0) != FileMode.TREE) {
                                    issues[component] = "is a symlink/submodule; templates under it are not served"
                                    available = false
                                }
                            }
                            if (!available) break
                        }
                        available
                    }
                val files = mutableMapOf<String, ByteArray>()
                if (roots.isNotEmpty()) {
                    TreeWalk(repo).use { tree ->
                        tree.addTree(commit.tree)
                        tree.isRecursive = true
                        tree.filter = PathFilterGroup.createFromStrings(roots)
                        while (tree.next()) {
                            // The filter guarantees this prefix; ancestors were checked above without following links.
                            val publicPath = "model/" + tree.pathString.removePrefix(prefix)
                            val mode = tree.getFileMode(0)
                            if (mode != FileMode.REGULAR_FILE && mode != FileMode.EXECUTABLE_FILE) {
                                issues[publicPath] = "not a regular file (symlink/submodule)"
                                continue
                            }
                            if (!REPORT_PATH.matches(publicPath) &&
                                !FORM_PATH.matches(publicPath) &&
                                !WORKBOOK_PATH.matches(publicPath)
                            ) {
                                continue
                            }
                            // Any blob read exception aborts this whole catalog, never mixing old/new documents.
                            files[publicPath] = repo.open(tree.getObjectId(0)).bytes
                        }
                    }
                }
                TemplateFiles(files, issues)
            }
        }

    private companion object {
        val WORKBOOK_PATH = Regex("model/reports/[^/]+/template\\.xlsx")
        val log = LoggerFactory.getLogger(GitTemplateSource::class.java)
    }
}
