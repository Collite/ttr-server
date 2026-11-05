// SPDX-License-Identifier: Apache-2.0
package org.tatrman.grounding.mcp.client

import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import org.tatrman.grounding.v1.ScanRequest
import org.tatrman.grounding.v1.ScanResponse
import java.io.Closeable

/**
 * Transport seam onto ONE deterministic grounding service (chrono / geo / money — all speak the same
 * `GroundingService` proto). The MCP wrapper holds three of these, one per service. Kept minimal:
 * the MCP does no grounding logic (service-vs-MCP rule), just request/response passthrough.
 */
interface GroundingClient : Closeable {
    /** The wrapped service's name ("chrono" | "geo" | "money"), for logs/telemetry. */
    val serviceName: String

    suspend fun ground(request: GroundRequest): GroundResponse

    /** The time phrases in a text (TS-P0 S0.3, contracts §3); chrono implements it, geo and money do not. */
    suspend fun scan(request: ScanRequest): ScanResponse
}
