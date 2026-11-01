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
) : ModelSource {
    var fallback: ManagedSource? = null
    private var checkpoint: BranchStorage.Checkpoint? = null
    private var preview = false
    private var preparationFailure: Exception? = null
    var attempted: SourceSnapshot? = null
        private set
    var origin: SourceOrigin = SourceOrigin(kind)
        private set

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
        origin = SourceOrigin(kind, git?.branch.orEmpty())
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
                SourceOrigin(kind, git?.branch.orEmpty(), if (git != null) snapshot.version else "")
            }
        return snapshot
    }

    fun attemptedOrigin(): SourceOrigin =
        if (attempted != null) {
            origin
        } else {
            SourceOrigin(kind, git?.branch.orEmpty(), git?.attemptedCommit.orEmpty())
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
)
