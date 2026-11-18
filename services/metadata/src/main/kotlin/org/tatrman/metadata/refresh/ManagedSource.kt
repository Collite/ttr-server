// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.refresh

import org.tatrman.ttr.metadata.source.FallbackSource
import org.tatrman.ttr.metadata.source.ModelSource
import org.tatrman.ttr.metadata.source.SourceSnapshot

/** Configuration provenance and rollback state; used only under GuardedRefresher's lock. */
internal class ManagedSource(
    val id: String,
    val kind: String,
    private val primary: ModelSource,
    val git: BranchStorage? = null,
    /** MW S4.2 — the parser kind (`ttr` / `yaml`) and, for `yaml`, the profile the last load read the tree through. */
    val parser: String = "ttr",
    private val profile: () -> String = { "" },
) : ModelSource {
    var fallback: ManagedSource? = null
    private var checkpoint: BranchStorage.Checkpoint? = null
    private var preview = false
    private var preparationFailure: Exception? = null
    var attempted: SourceSnapshot? = null
        private set
    var origin: SourceOrigin = origin()
        private set

    private fun origin(
        branch: String = "",
        commit: String = "",
    ) = SourceOrigin(kind, branch, commit, kind = parser, profile = profile())

    fun begin(
        branch: String = "",
        initial: Boolean = false,
    ) {
        attempted = null
        preparationFailure = null
        checkpoint =
            git?.let { storage ->
                storage.clearAttempt()
                try {
                    storage.checkpoint().let { point ->
                        if (initial) point.copy(branch = storage.defaultBranch, head = null, ref = null) else point
                    }
                } catch (e: Exception) {
                    preparationFailure = e
                    BranchStorage.Checkpoint(if (initial) storage.defaultBranch else storage.branch, null, null)
                }
            }
        preview = branch.isNotEmpty()
        if (preview) git?.branch = branch
        origin = origin(git?.branch.orEmpty())
        fallback?.begin(initial = initial)
    }

    override fun load(): SourceSnapshot {
        val prepared = preparationFailure?.let { cause -> ModelSource { throw cause } } ?: primary
        val fb = fallback
        val snapshot = if (fb != null && !preview) FallbackSource(id, prepared, fb).load() else prepared.load()
        attempted = snapshot
        val isFallback = snapshot.warnings.any { it.file == "<fallback>" }
        origin =
            if (isFallback && fb != null) {
                fb.origin.copy(fallback = true)
            } else {
                origin(git?.branch.orEmpty(), if (git != null) snapshot.version else "")
            }
        return snapshot
    }

    fun attemptedOrigin(): SourceOrigin =
        if (attempted != null) {
            origin
        } else {
            origin(git?.branch.orEmpty(), git?.attemptedCommit.orEmpty())
        }

    fun finish(accepted: Boolean): String? {
        val failures = mutableListOf<String>()
        if (!accepted) {
            checkpoint?.let { point ->
                runCatching { git?.restore(point) }.exceptionOrNull()?.let {
                    failures +=
                        "rollback_failed: ${it.message}"
                }
            }
        }
        fallback?.finish(accepted)?.let { failures += it }
        checkpoint = null
        preview = false
        return failures.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }
}

data class SourceOrigin(
    val source: String,
    val branch: String = "",
    val commit: String = "",
    val fallback: Boolean = false,
    /** MW S4.2 (contracts §1) — the parser kind, `ttr` or `yaml`; [source] stays the storage type. */
    val kind: String = "ttr",
    /** The profile a `yaml` source read its tree through (`<name>/<major>` or the configured path); empty for `ttr`. */
    val profile: String = "",
)
