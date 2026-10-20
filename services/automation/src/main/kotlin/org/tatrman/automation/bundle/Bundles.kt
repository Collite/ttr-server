// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.bundle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.config.PreviewsConfig
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** The `bundles` rows of the ledger (contracts §2): one per loaded bundle; a preview's `last_used_at` moves on use. */
interface BundleBook {
    fun record(bundle: Bundle)

    fun touch(
        id: String,
        at: Instant,
    )
}

/** A preview bundle (D-111) and when it was last used — eviction (P4) reads [lastUsedAt]. */
data class Preview(
    val bundle: Bundle,
    val lastUsedAt: Instant,
)

/** What a refresh did: [changed] = a new commit was loaded and swapped in; false = the commit was already served. */
data class RefreshOutcome(
    val bundle: Bundle,
    val changed: Boolean,
)

/**
 * The bundle registry (D-47, D-74, D-111): the **served** bundle every deployment runs, and **previews** — other
 * branches loaded beside it. A refresh is all-or-nothing: the commit is checked out and loaded in full before it
 * replaces anything, so a failing checkout (`502 checkout-failed`) or an invalid commit (`422 bundle-invalid`)
 * leaves the served bundle untouched. One refresh at a time — a second one meanwhile is `409 refresh-in-progress`,
 * never queued (the CI call, the poll and a person may race; the loser retries). Refreshing a commit already
 * loaded is a no-op. A preview branch must match `previews.allowedBranches` (an exact name, or a prefix ending in
 * `*`) — else `403 branch-not-allowed`. Any other failure (the ledger's `bundles` write, say) fails the refresh as
 * well: the served bundle stays, health's `refreshFailed` turns true, and the exception goes on to the caller.
 * [refreshed] counts every outcome (`automation_refresh_total`, §10): `loaded`, `unchanged`, or the error code.
 */
class Bundles(
    private val servedBranch: String,
    private val previewsConfig: PreviewsConfig,
    private val source: BundleSource,
    private val loader: BundleLoader,
    private val book: BundleBook,
    private val clock: Clock = Clock.systemUTC(),
    private val refreshed: (outcome: String) -> Unit = {},
) {
    @Volatile
    var served: Bundle? = null
        private set

    /** The latest commit the served branch's last refresh saw — also when it did not load. */
    @Volatile
    var latestCommit: String? = null
        private set

    /** The last refresh of the served branch failed (health's `refreshFailed`). */
    @Volatile
    var refreshFailed: Boolean = false
        private set

    private val previews = ConcurrentHashMap<String, Preview>()
    private val lock = Mutex()

    fun previews(): List<Preview> = previews.values.sortedBy { it.bundle.branch }

    fun preview(branch: String): Preview? = previews[branch]

    /**
     * Marks a preview used (a run on it — P4); the ledger row follows. Atomic per branch: a refresh swapping the
     * preview's bundle meanwhile is never undone by a stale copy.
     */
    fun touch(branch: String) {
        val now = clock.instant()
        val touched = previews.computeIfPresent(branch) { _, p -> p.copy(lastUsedAt = now) } ?: return
        book.touch(touched.bundle.id, now)
    }

    suspend fun refresh(branch: String? = null): RefreshOutcome {
        val target = branch?.takeIf { it.isNotBlank() } ?: servedBranch
        val kind = if (target == servedBranch) BundleKind.SERVED else BundleKind.PREVIEW
        return try {
            refreshLocked(target, kind).also { refreshed(if (it.changed) LOADED else UNCHANGED) }
        } catch (e: AutomationException) {
            refreshed(e.code.code)
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (kind == BundleKind.SERVED) refreshFailed = true
            log.error("automation: refresh of {} failed", target, e)
            refreshed(ErrorCode.INTERNAL.code)
            throw e
        }
    }

    private suspend fun refreshLocked(
        target: String,
        kind: BundleKind,
    ): RefreshOutcome {
        if (kind == BundleKind.PREVIEW && !allowed(target)) {
            throw AutomationException(ErrorCode.BRANCH_NOT_ALLOWED, "branch $target is not an allowed preview branch")
        }
        if (!lock.tryLock()) throw AutomationException(ErrorCode.REFRESH_IN_PROGRESS, "a refresh is in progress; retry")
        try {
            return withContext(Dispatchers.IO) { swap(target, kind) }
        } finally {
            lock.unlock()
        }
    }

    private fun swap(
        branch: String,
        kind: BundleKind,
    ): RefreshOutcome {
        val current = if (kind == BundleKind.SERVED) served else previews[branch]?.bundle
        val checkout =
            try {
                source.checkout(branch)
            } catch (e: CheckoutFailedException) {
                if (kind == BundleKind.SERVED) refreshFailed = true
                log.warn("automation: refresh of {} failed: {}", branch, e.message)
                throw AutomationException(ErrorCode.CHECKOUT_FAILED, e.message ?: "checkout failed")
            }
        if (kind == BundleKind.SERVED) latestCommit = checkout.commit
        if (current != null && current.commit == checkout.commit) {
            if (kind == BundleKind.SERVED) refreshFailed = false
            return RefreshOutcome(current, changed = false)
        }
        val bundle =
            try {
                loader.load(checkout, kind)
            } catch (e: BundleInvalidException) {
                if (kind == BundleKind.SERVED) refreshFailed = true
                log.warn("automation: {} at {} refused: {}", branch, checkout.commit.take(12), e.diagnostics)
                throw AutomationException(
                    ErrorCode.BUNDLE_INVALID,
                    "the bundle at ${checkout.commit.take(12)} is invalid (${e.diagnostics.size} finding(s))",
                    buildJsonObject { put("diagnostics", JsonArray(e.diagnostics.map { JsonPrimitive(it) })) },
                )
            }
        book.record(bundle)
        when (kind) {
            BundleKind.SERVED -> {
                served = bundle
                refreshFailed = false
            }
            BundleKind.PREVIEW -> previews[branch] = Preview(bundle, bundle.loadedAt)
        }
        log.info("automation: {} now serves {}", kind.wire, bundle.id)
        return RefreshOutcome(bundle, changed = true)
    }

    private fun allowed(branch: String): Boolean =
        previewsConfig.allowedBranches.any { p ->
            if (p.endsWith("*")) branch.startsWith(p.dropLast(1)) else branch == p
        }

    companion object {
        /** The `outcome` of `automation_refresh_total` besides an error code. */
        const val LOADED = "loaded"
        const val UNCHANGED = "unchanged"

        private val log = LoggerFactory.getLogger(Bundles::class.java)
    }
}
