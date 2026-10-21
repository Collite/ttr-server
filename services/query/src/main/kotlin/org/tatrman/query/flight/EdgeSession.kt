// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

import org.apache.arrow.flight.FlightProducer
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.worker.v1.ExecutionReceipt

/**
 * BI (contracts C-2, C-3) — who is calling the edge, and for which world: the verified bearer's
 * `preferred_username` and `realm_access.roles`, the connection's `world`, and the client kind for
 * telemetry and receipts. The edge forwards [userId] and [roles] to `Run` exactly as query-mcp does
 * (ES §3); roles never come from anything but a verified token.
 */
data class EdgeSession(
    val userId: String,
    val roles: List<String>,
    val world: String = "",
    val client: String = CLIENT_OTHER,
) {
    companion object {
        const val CLIENT_SUPERSET = "superset"
        const val CLIENT_JDBC = "jdbc"
        const val CLIENT_ADBC = "adbc"
        const val CLIENT_OTHER = "other"
    }
}

/** The [EdgeSession] of one Flight call; null = the call is not authenticated and is refused. */
fun interface SessionResolver {
    fun sessionOf(context: FlightProducer.CallContext): EdgeSession?
}

/**
 * One statement's end, however it ended — streamed to completion, refused, failed mid-stream, cancelled by
 * the client, or started and never fetched. The edge reports every statement exactly once (C-6's receipt
 * sink and C-8's telemetry read it).
 */
data class StatementOutcome(
    val correlationId: String,
    val session: EdgeSession,
    val statement: String,
    val status: Status,
    /** The refusal's or failure's code (`translator_rejected`, `world_mismatch`, …); empty when [status] is OK. */
    val errorCode: String = "",
    val rowsStreamed: Long = 0,
    /** The last batch's execution receipt (ES): executed statement, connection, security applied. */
    val receipt: ExecutionReceipt? = null,
    /** Warnings the door and the worker raised on the statement, from every batch (`top_n_applied`, `blob_too_large`, …). */
    val notices: List<ResponseMessage> = emptyList(),
    val durationMs: Long = 0,
) {
    enum class Status(
        val label: String,
    ) {
        OK("ok"),
        REFUSED("refused"),
        FAILED("failed"),
        CANCELLED("cancelled"),
        EXPIRED("expired"),
    }

    val topNApplied: Boolean get() = notices.any { it.code == TOP_N_APPLIED }

    companion object {
        /** validate's `RuleEnforcer.TOP_N_APPLIED`. */
        const val TOP_N_APPLIED = "top_n_applied"
    }
}

/** Where statement outcomes go (C-6's receipt sink in S1.2; telemetry). Must not throw into the edge. */
fun interface StatementListener {
    fun onStatementDone(outcome: StatementOutcome)
}
