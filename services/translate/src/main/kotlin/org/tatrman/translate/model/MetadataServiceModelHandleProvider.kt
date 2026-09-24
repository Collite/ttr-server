// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translate.model

import org.tatrman.meta.v1.GetSnapshotRequest
import org.tatrman.meta.v1.GetSnapshotResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.tatrman.translate.snapshot.SnapshotModelHandle
import org.tatrman.translator.framework.ModelHandle
import java.util.concurrent.atomic.AtomicReference

/**
 * [ModelHandleProvider] backed by the metadata service's `GetSnapshot`, with ETag-based
 * skipping and periodic refresh.
 *
 * Until the first successful fetch, [current] returns [fallback] (the boot fixture) so the
 * translator can start before the metadata service is reachable; the background refresh loop
 * swaps in the real model as soon as a snapshot is available. Each gRPC RPC of the translator
 * captures `current()` once at entry, satisfying the per-request snapshot-capture rule.
 *
 * [getSnapshot] is injected so tests can supply a canned response without a real channel; in
 * production it's a thin wrapper over `VelesServiceCoroutineStub.getSnapshot(...)`.
 */
class MetadataServiceModelHandleProvider(
    private val getSnapshot: suspend (GetSnapshotRequest) -> GetSnapshotResponse,
    private val refreshIntervalMs: Long = 60_000,
    // Retry cadence while the FIRST load is still pending. Until a snapshot has
    // ever loaded the translator serves the boot fixture (real objects fail to
    // resolve), so we retry far more often than the steady-state poll to recover
    // from a cold-start blip (slow channel / deadline) within seconds, not one
    // full poll interval.
    private val initialRetryIntervalMs: Long = 5_000,
    private val endpoint: String = "metadata service",
    fallback: ModelHandle = BootFixtureModel.handle(),
) : ModelHandleProvider {
    private val logger = LoggerFactory.getLogger(MetadataServiceModelHandleProvider::class.java)
    private val handleRef = AtomicReference(fallback)
    private val etagRef = AtomicReference<String?>(null)
    private val lastSuccessfulRefreshRef = AtomicReference<Long?>(null)

    @Volatile
    private var refreshJob: Job? = null

    override fun current(): ModelHandle = handleRef.get()

    /** Unix timestamp millis of the last successful metadata refresh, or null if never refreshed. */
    fun lastSuccessfulRefreshTimestamp(): Long? = lastSuccessfulRefreshRef.get()

    /** Fetch once; swap in a fresh handle if the model changed since the last ETag. */
    suspend fun refreshOnce() {
        val request = GetSnapshotRequest.newBuilder().setIfNoneMatch(etagRef.get().orEmpty()).build()
        val resp = getSnapshot(request)
        when {
            resp.notModified -> Unit // already up to date
            resp.hasSnapshot() -> {
                val firstLoad = lastSuccessfulRefreshRef.get() == null
                handleRef.set(SnapshotModelHandle.from(resp.snapshot))
                etagRef.set(resp.etag)
                lastSuccessfulRefreshRef.set(System.currentTimeMillis())
                logger.info(
                    "Metadata model {} from {} (version={})",
                    if (firstLoad) "loaded" else "refreshed",
                    endpoint,
                    resp.snapshot.model.version,
                )
            }
            else ->
                logger.warn(
                    "Metadata GetSnapshot from {} returned no snapshot (messages={}); keeping current model",
                    endpoint,
                    resp.messagesList.map { it.code },
                )
        }
    }

    /** Start the background refresh loop on [scope]. Idempotent; the first iteration runs immediately. */
    fun start(scope: CoroutineScope) {
        if (refreshJob != null) return
        logger.info(
            "Starting metadata refresh loop against {} (initial load now, then every {} ms)",
            endpoint,
            refreshIntervalMs,
        )
        refreshJob =
            scope.launch {
                while (isActive) {
                    val outcome = runCatching { refreshOnce() }
                    // Recompute AFTER the attempt: a successful first load flips this to
                    // the steady-state poll; a still-pending first load keeps fast retry.
                    val firstLoadPending = lastSuccessfulRefreshRef.get() == null
                    val nextDelayMs = if (firstLoadPending) initialRetryIntervalMs else refreshIntervalMs
                    outcome.onFailure { ex ->
                        if (firstLoadPending) {
                            logger.error(
                                "Initial metadata load from {} failed; translator is serving the BOOT FIXTURE model, " +
                                    "so real schema objects will NOT resolve (expect schema_object_unknown). " +
                                    "Retrying in {} ms.",
                                endpoint,
                                nextDelayMs,
                                ex,
                            )
                        } else {
                            logger.warn(
                                "Metadata refresh from {} failed; keeping last successfully loaded model. " +
                                    "Retrying in {} ms.",
                                endpoint,
                                nextDelayMs,
                                ex,
                            )
                        }
                    }
                    delay(nextDelayMs)
                }
            }
    }

    fun stop() {
        refreshJob?.cancel()
        refreshJob = null
    }
}
