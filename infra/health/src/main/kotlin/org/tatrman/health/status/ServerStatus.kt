// SPDX-License-Identifier: Apache-2.0
package org.tatrman.health.status

import kotlinx.serialization.Serializable
import org.tatrman.health.service.AllHealthResponse
import org.tatrman.health.service.HealthSummary
import org.tatrman.health.service.TechnologyStatus

/**
 * FO-P5.S2.T1 (FO-28) — the open Server's status snapshot: the single release-train version, the model
 * fingerprint (metadata `model_version`), and the per-service health rollup. Read-only; the machine twin of
 * the human page.
 */
@Serializable
data class ServerStatus(
    val serverVersion: String,
    /** The model fingerprint (metadata model_version); null when metadata is unreachable/undeployed. */
    val modelFingerprint: String?,
    val summary: HealthSummary,
    val services: List<TechnologyStatus>,
)

/**
 * Assembles a [ServerStatus] from its three independently-sourced parts. Each is a suspend seam so the
 * production wiring (build resource + metadata probe + the health rollup) and the tests (canned values)
 * share one path. The fingerprint fails soft — an unreachable metadata yields a null fingerprint, never an
 * error, so the status page always renders.
 */
class StatusService(
    private val serverVersion: String,
    private val modelFingerprint: suspend () -> String?,
    private val rollup: suspend () -> AllHealthResponse,
) {
    suspend fun current(): ServerStatus {
        val all = rollup()
        val fingerprint = runCatching { modelFingerprint() }.getOrNull()
        return ServerStatus(
            serverVersion = serverVersion,
            modelFingerprint = fingerprint,
            summary = all.summary,
            services = all.technologies,
        )
    }
}
