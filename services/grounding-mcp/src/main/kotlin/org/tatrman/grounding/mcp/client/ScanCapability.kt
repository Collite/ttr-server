// SPDX-License-Identifier: Apache-2.0
package org.tatrman.grounding.mcp.client

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.tatrman.grounding.v1.GetStatusResponse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * OL-C1/C4 — does the grounder implement `Scan`? The same read as the resolver's (OL-C2), kept as a copy because the
 * two services share no library. Read from its `GetStatus` (`capabilities["scan"] = "true"`) and kept for [ttl]. Any other value, a missing key, or a failed read means "no": the caller then does not call `Scan`.
 *
 * Silent per turn by contract: one INFO line when the known state changes (the first read included), nothing else.
 * One read at a time; a read that takes longer than [timeout] counts as failed, so a hung grounder costs a turn at
 * most [timeout] once per [ttl]. [nowNanos] is injectable so the TTL can be tested without a real clock.
 */
class ScanCapability(
    private val ttl: Duration,
    private val timeout: Duration = DEFAULT_TIMEOUT,
    private val nowNanos: () -> Long = System::nanoTime,
    private val readStatus: suspend () -> GetStatusResponse,
) {
    private val log = LoggerFactory.getLogger(ScanCapability::class.java)
    private val mutex = Mutex()

    @Volatile private var known: Boolean? = null

    @Volatile private var readAt: Long = 0L

    suspend fun available(): Boolean {
        fresh()?.let { return it }
        return mutex.withLock {
            fresh() ?: read()
        }
    }

    private fun fresh(): Boolean? = known?.takeIf { nowNanos() - readAt < ttl.inWholeNanoseconds }

    private suspend fun read(): Boolean {
        var cause: Exception? = null
        val scan =
            try {
                val status = withTimeoutOrNull(timeout) { readStatus() } ?: error("GetStatus timed out after $timeout")
                status.capabilitiesMap[KEY] == "true"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cause = e
                false
            }
        if (scan != known) {
            if (cause == null) {
                log.info("grounder scan capability: {} (GetStatus capabilities[\"{}\"])", label(scan), KEY)
            } else {
                log.info("grounder scan capability: {} (GetStatus failed: {})", label(scan), cause.toString())
            }
        }
        known = scan
        readAt = nowNanos()
        return scan
    }

    private fun label(scan: Boolean) = if (scan) "available" else "not available — Scan is not called"

    companion object {
        const val KEY = "scan"
        val DEFAULT_TIMEOUT: Duration = 2.seconds
    }
}
