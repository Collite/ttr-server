// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.read

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.TreeWalk
import org.slf4j.LoggerFactory
import org.tatrman.ttr.metadata.registry.RegistrySnapshot
import org.tatrman.ttr.metadata.source.ModelSource
import org.tatrman.ttr.metadata.source.SourceSnapshot
import java.nio.file.Path
import java.util.Base64

// Same temporary snapshot transport as templates; never a public export sidecar (ttr-core#314).
internal const val LEXICON_SIDECAR_PREFIX = "ttr-server:lexicon:"

@Serializable
internal data class ServedLexicon(
    val sourceId: String,
    val priority: Int,
    val version: String,
    val archive: String? = null,
    val issue: String? = null,
    val blobId: String? = null,
)

/** Reads an opaque compiled archive from the immutable commit, never a moving checkout file. */
internal class GitLexiconSource(
    private val source: ModelSource,
    private val checkout: Path,
    archivePath: String,
) : ModelSource {
    private val path =
        runCatching {
            if (archivePath.isBlank()) return@runCatching null
            val parsed = Path.of(archivePath)
            require(!parsed.isAbsolute) { "must be a repository-relative path" }
            val normalized = parsed.normalize()
            require(normalized.none { it.toString() == ".." } && normalized.toString().isNotEmpty()) {
                "must name a file within the repository"
            }
            normalized.joinToString("/")
        }
    private val configurationIssue =
        path
            .exceptionOrNull()
            ?.let {
                "invalid lexicon path '$archivePath': ${it.message}"
            }?.also { log.warn(it) }
    private var cached: Pair<String, String>? = null
    private var lastFailure: String? = null

    @Synchronized
    override fun load(): SourceSnapshot {
        val snapshot = source.load() // Genuine model failures retain their normal semantics.
        val encoded =
            configurationIssue?.let {
                Json.encodeToString(
                    ServedLexicon(
                        snapshot.sourceId,
                        snapshot.priority,
                        snapshot.version,
                        issue = "lexicon source ${snapshot.sourceId}: $it",
                    ),
                )
            } ?: cached?.takeIf { it.first == snapshot.version }?.second ?: run {
                try {
                    val archive = path.getOrThrow()?.let { readAt(snapshot.version, it) }
                    Json
                        .encodeToString(
                            ServedLexicon(
                                snapshot.sourceId,
                                snapshot.priority,
                                snapshot.version,
                                archive?.second,
                                blobId = archive?.first,
                            ),
                        ).also {
                            cached = snapshot.version to it
                            lastFailure = null
                        }
                } catch (e: Exception) {
                    val issue =
                        "lexicon source ${snapshot.sourceId} at ${snapshot.version.take(8)}: " +
                            "${e.javaClass.simpleName}: ${e.message.orEmpty().lineSequence().first()}"
                    if (lastFailure != issue) log.warn(issue)
                    lastFailure = issue
                    // Never label an old archive with the new model SHA. Failures are retried each tick; a same-SHA recovery is published with the next swap.
                    Json.encodeToString(
                        ServedLexicon(snapshot.sourceId, snapshot.priority, snapshot.version, issue = issue),
                    )
                }
            }
        return snapshot.copy(
            exportSidecars =
                snapshot.exportSidecars + (LEXICON_SIDECAR_PREFIX + snapshot.sourceId to encoded),
        )
    }

    private fun readAt(
        version: String,
        path: String,
    ): Pair<String, String>? =
        Git.open(checkout.toFile()).use { git ->
            val repo = git.repository
            RevWalk(repo).use { revisions ->
                val commit = revisions.parseCommit(ObjectId.fromString(version))
                val components = path.split('/')
                for (i in components.indices) {
                    val current = components.take(i + 1).joinToString("/")
                    TreeWalk.forPath(repo, current, commit.tree).use { entry ->
                        if (entry == null) return null // Estates without a compiled archive are supported.
                        val mode = entry.getFileMode(0)
                        if (i < components.lastIndex) {
                            require(mode == FileMode.TREE) { "$current is not a directory (symlink/submodule)" }
                        } else {
                            require(mode == FileMode.REGULAR_FILE || mode == FileMode.EXECUTABLE_FILE) {
                                "$current is not a regular file (symlink/submodule)"
                            }
                            // Bound the in-memory sidecar and reject oversized blobs with a normal exception.
                            return entry.getObjectId(0).name to
                                Base64.getEncoder().encodeToString(
                                    repo.open(entry.getObjectId(0)).getBytes(
                                        50 * 1024 * 1024,
                                    ),
                                )
                        }
                    }
                }
                null
            }
        }

    private companion object {
        val log = LoggerFactory.getLogger(GitLexiconSource::class.java)
    }
}

private data class CachedLexicons(
    val sidecars: Map<String, String>,
    val catalogs: List<ServedLexicon>,
)

@Volatile
private var lastLexicons: CachedLexicons? = null

internal fun RegistrySnapshot.servedLexicons(): List<ServedLexicon> {
    lastLexicons?.takeIf { it.sidecars === exportSidecars }?.let { return it.catalogs }
    val catalogs =
        exportSidecars
            .filterKeys { it.startsWith(LEXICON_SIDECAR_PREFIX) }
            .values
            .map { Json.decodeFromString<ServedLexicon>(it) }
            .sortedWith(compareBy<ServedLexicon> { it.priority }.thenBy { it.sourceId })

    lastLexicons = CachedLexicons(exportSidecars, catalogs)
    return catalogs
}
