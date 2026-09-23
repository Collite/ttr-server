// SPDX-License-Identifier: Apache-2.0
package org.tatrman.worker.receipt

import org.slf4j.LoggerFactory
import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.worker.v1.BoundParameter
import org.tatrman.worker.v1.ExecutionReceipt
import org.tatrman.worker.v1.RlsOutcome
import org.tatrman.worker.v1.StatementKind

/**
 * The one place a `worker.v1.ExecutionReceipt` is built (ES-P0·S0.2).
 *
 * Three hops fill a receipt and none of them may get these rules subtly different, so they all
 * come through here: the two JVM workers fill the **statement half** ([statementHalf]), ttr-query
 * fills the **plan half** (ES-P0·S0.3), and worker-polars — being Python — carries a twin of this
 * file rather than this file.
 *
 * ### The three rules
 *
 * **Bounded.** A statement over [MAX_STATEMENT_CHARS] is *omitted* and replaced by a
 * `statement_ref`, never truncated: a half-statement is worse than none, because it reads like a
 * whole one. The full text goes to the worker log at DEBUG, where Loki carries it and the
 * protocol's service-logs section can quote it (ES architecture §2, the A-10 shape).
 *
 * **Masked.** ⚑ES-2 — names, types and the bound count always; the value never. Whether a value
 * is ever shown is the BFF's decision under the protocol profile, and a worker that emitted one
 * would have put it on the wire before anyone could decide.
 *
 * **Best-effort.** A receipt must never fail a run (ES architecture §7). Everything here is built
 * inside [guarded]: a throw becomes an absent receipt and one WARN, and the stream completes
 * exactly as it would have. Callers treat `null` as "no receipt on this batch" and emit anyway.
 */
object ExecutionReceiptBuilder {
    /** Statements longer than this leave as a [ExecutionReceipt.getStatementRef], not as text. */
    const val MAX_STATEMENT_CHARS: Int = 65_536

    /** Serialized `dispatched_plan` bytes above this are omitted with `plan_omitted_reason`. */
    const val MAX_PLAN_BYTES: Int = 262_144

    /** What stands in for every bound value on the wire (⚑ES-2). */
    const val MASKED: String = "<masked>"

    private val log = LoggerFactory.getLogger(ExecutionReceiptBuilder::class.java)

    /**
     * The worker's half: what was handed to the engine, and what came back.
     *
     * @param kind `SQL_EXECUTED` when [sql] exists, `PLAN_EXECUTED` for an engine that runs the
     *   plan itself, `NONE` when the run failed before unparse — the receipt still travels, and
     *   saying "there was no statement" is a fact the reader wants.
     * @param sql the exact text handed to the engine, or null when there is none.
     * @param correlationId only used to mint the `statement_ref` for an over-cap statement.
     * @return the half, or null when building it threw — never a partially-built receipt.
     */
    @Suppress("LongParameterList")
    fun statementHalf(
        kind: StatementKind,
        dialect: String,
        sql: String?,
        parameters: List<ParameterBinding>,
        connectionId: String,
        engineLabel: String,
        rowsTotal: Long,
        durationMs: Long,
        rls: RlsOutcome,
        correlationId: String,
    ): ExecutionReceipt? =
        guarded {
            val builder =
                ExecutionReceipt
                    .newBuilder()
                    .setStatementKind(kind)
                    .setDialect(dialect)
                    .setConnectionId(connectionId)
                    .setEngineLabel(engineLabel)
                    .setRowsTotal(rowsTotal)
                    .setDurationMs(durationMs)
                    .setRls(rls)

            when {
                sql == null -> Unit
                sql.length > MAX_STATEMENT_CHARS -> {
                    builder
                        .setStatement("")
                        .setStatementRef("worker:$correlationId")
                        .setStatementTruncated(true)
                    log.debug(
                        "Execution receipt: statement of {} chars exceeds the {} cap for " +
                            "correlation_id={} — omitted from the receipt. Statement: {}",
                        sql.length,
                        MAX_STATEMENT_CHARS,
                        correlationId,
                        sql,
                    )
                }
                else -> builder.setStatement(sql)
            }

            parameters.forEach { binding ->
                builder.addParameters(
                    BoundParameter
                        .newBuilder()
                        .setName(binding.name)
                        .setType(binding.type)
                        .setValueMasked(MASKED)
                        .setMasked(true),
                )
            }

            builder.build()
        }

    /**
     * Runs [build] and swallows anything it throws, WARN-logged, as an absent receipt.
     *
     * Every producer of a receipt — including a caller assembling one by hand — goes through
     * here. A run that streamed its rows correctly must not fail because the bookkeeping about
     * it did.
     */
    fun guarded(build: () -> ExecutionReceipt?): ExecutionReceipt? =
        try {
            build()
        } catch (t: Throwable) {
            if (t is InterruptedException) Thread.currentThread().interrupt()
            log.warn("Execution receipt could not be built — continuing without one: {}", t.message, t)
            null
        }
}
