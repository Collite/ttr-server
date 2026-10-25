// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.refresh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.slf4j.LoggerFactory
import org.tatrman.ttr.metadata.refresh.MetadataRefresher
import java.time.Duration

/**
 * DF-M06 / Phase 07 B3 — periodic re-load of model sources, delegating to [MetadataRefresher].
 *
 * Uses the same atomic-swap path as the on-demand `Refresh` RPC, so a scheduled refresh is just
 * as safe as a manual one (concurrent reads keep observing the prior snapshot until the swap
 * commits). Failures are logged but don't kill the scheduler — a flaky source can recover on the
 * next tick.
 *
 * Lifecycle: [start] returns the launched [Job] so callers can cancel on shutdown.
 */
class RefreshScheduler(
    private val refresher: MetadataRefresher,
    private val interval: Duration,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val log = LoggerFactory.getLogger(RefreshScheduler::class.java)

    fun start(): Job =
        scope.launch {
            log.info("RefreshScheduler started — interval={}s", interval.seconds)
            while (isActive) {
                delay(interval.toMillis())
                if (!isActive) return@launch
                try {
                    val results = refresher.refresh(sourceId = "", force = false)
                    val swapped = results.any { it.snapshotSwapped }
                    val failed = results.count { !it.success }
                    if (swapped || failed > 0) {
                        log.info(
                            "Scheduled refresh: swapped={} failed={} results={}",
                            swapped,
                            failed,
                            results.size,
                        )
                    } else {
                        log.debug("Scheduled refresh: no changes ({} sources unchanged)", results.size)
                    }
                } catch (t: Throwable) {
                    // The refresher already handles per-source failures; this catch covers the
                    // unexpected (Mutex contention, OOM, etc.) so a bad tick can't kill the loop.
                    log.warn("Scheduled refresh threw an exception (continuing): {}", t.message)
                }
            }
        }
}
