// SPDX-License-Identifier: Apache-2.0
package org.tatrman.worker.receipt

import org.slf4j.LoggerFactory
import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.plan.v1.PlanNode
import org.tatrman.validate.v1.SecurityRuleApplied
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

    /** `plan_omitted_reason` when the dispatched plan is over [MAX_PLAN_BYTES] (contracts §1.1). */
    const val PLAN_OVER_CAP: String = "over_cap"

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
     * ttr-query's half: what was dispatched, and what the validator did to it before it went.
     *
     * The plan is the one handed to `DispatchRequest.plan` — post-validate, and physical when the
     * effective schema is DB, i.e. the very tree the worker unparsed (⚑ES-3). Over
     * [MAX_PLAN_BYTES] it is dropped rather than cut: a truncated proto does not parse, and a
     * plan that does not parse is worse than a `plan_omitted_reason` that says why it is missing.
     *
     * @param dispatchTarget the worker endpoint, when the caller already knows it — ttr-query
     *   learns it from the first batch dispatch stamped (contracts §1.3) and re-attaches it to
     *   the last batch, so the last batch carries the whole receipt on its own (⚑ES-1).
     * @return the half, or null when building it threw.
     */
    fun planHalf(
        dispatchedPlan: PlanNode,
        securityApplied: List<SecurityRuleApplied>,
        effectiveSchema: String,
        cacheHit: Boolean,
        compileMs: Long,
        dispatchTarget: String = "",
    ): ExecutionReceipt? =
        guarded {
            val builder =
                ExecutionReceipt
                    .newBuilder()
                    .setEffectiveSchema(effectiveSchema)
                    .setCacheHit(cacheHit)
                    .setCompileMs(compileMs)
                    .setDispatchTarget(dispatchTarget)
                    .addAllSecurityApplied(securityApplied)

            val planBytes = dispatchedPlan.serializedSize
            if (planBytes > MAX_PLAN_BYTES) {
                builder.setPlanOmittedReason(PLAN_OVER_CAP)
                log.debug(
                    "Execution receipt: dispatched plan of {} bytes exceeds the {} cap — omitted.",
                    planBytes,
                    MAX_PLAN_BYTES,
                )
            } else {
                builder.setDispatchedPlan(dispatchedPlan)
            }

            builder.build()
        }

    /**
     * Folds a [half] into whatever is already held — the one place the two halves of ⚑ES-1 meet.
     *
     * The halves occupy disjoint field numbers (1–11 worker, 20–26 ttr-query), so for the scalars
     * proto's own merge is exactly the right rule: each hop's fields survive and no hop overwrites
     * a fact it does not hold.
     *
     * The two **repeated** fields need a rule of their own, because proto merge CONCATENATES them.
     * ⚑ES-1 has ttr-query re-attach its half to the last batch so that batch stands alone, which
     * means a consumer merging first + last meets the same `security_applied` set twice — and a
     * concatenating merge would report every rule twice, and every bound parameter twice. Each
     * repeated field belongs to exactly one half (`parameters` to the worker, `security_applied`
     * to ttr-query), so the honest rule is to take it whole from whichever side has it, never to
     * append. That also makes merging idempotent, which is what a re-attached half requires.
     *
     * Returns [existing] unchanged when there is nothing to add, and null only when there is
     * nothing at all.
     */
    fun merged(
        existing: ExecutionReceipt?,
        half: ExecutionReceipt?,
    ): ExecutionReceipt? =
        guarded {
            when {
                half == null -> existing
                existing == null -> half
                else -> {
                    val parameters =
                        if (existing.parametersCount > 0) existing.parametersList else half.parametersList
                    val security =
                        if (existing.securityAppliedCount > 0) {
                            existing.securityAppliedList
                        } else {
                            half.securityAppliedList
                        }
                    existing
                        .toBuilder()
                        .mergeFrom(half)
                        .clearParameters()
                        .addAllParameters(parameters)
                        .clearSecurityApplied()
                        .addAllSecurityApplied(security)
                        .build()
                }
            }
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
