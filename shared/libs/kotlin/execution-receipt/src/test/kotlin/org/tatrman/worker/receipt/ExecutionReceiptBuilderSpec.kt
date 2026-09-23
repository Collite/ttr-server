// SPDX-License-Identifier: Apache-2.0
package org.tatrman.worker.receipt

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.plan.v1.Value
import org.tatrman.validate.v1.SecurityRuleApplied
import org.tatrman.worker.v1.RlsOutcome
import org.tatrman.worker.v1.StatementKind

/**
 * **ES-P0·S0.2 — the shared builder.**
 *
 * Two promises are tested here and nowhere else, because every worker inherits them from this
 * one place:
 *
 *  - **bounded** — a statement over 64 KiB leaves the wire as a `statement_ref`, never as a
 *    truncated statement someone might paste into a console (ES architecture §2, the A-10 shape);
 *  - **best-effort** — a receipt must NEVER fail a run (ES architecture §7). Anything the caller's
 *    own value computation throws becomes an absent receipt and a WARN, and the stream completes.
 *
 * And one rule that is a security property, not a convenience: ⚑ES-2 — the worker emits parameter
 * NAMES and TYPES, never values. Whether a value is ever shown is the BFF's decision under the
 * protocol profile; a worker that emitted one would have put it on the wire before anyone could
 * decide, so the masking is here, at the source.
 */
class ExecutionReceiptBuilderSpec :
    StringSpec({

        "a statement at the cap travels inline, with no ref and no truncation flag" {
            val sql = "SELECT " + "x".repeat(ExecutionReceiptBuilder.MAX_STATEMENT_CHARS - 7)
            sql.length shouldBe ExecutionReceiptBuilder.MAX_STATEMENT_CHARS

            val receipt = statementHalf(sql = sql).shouldNotBeNull()

            receipt.statement shouldBe sql
            receipt.statementRef shouldBe ""
            receipt.statementTruncated shouldBe false
            receipt.statementKind shouldBe StatementKind.SQL_EXECUTED
        }

        "a statement over the cap is OMITTED and replaced by a worker ref — never truncated" {
            val sql = "SELECT " + "x".repeat(ExecutionReceiptBuilder.MAX_STATEMENT_CHARS)

            val receipt =
                statementHalf(sql = sql, correlationId = "09568c3e-1111-4000-8000-000000000000")
                    .shouldNotBeNull()

            receipt.statement shouldBe ""
            receipt.statementRef shouldBe "worker:09568c3e-1111-4000-8000-000000000000"
            receipt.statementTruncated shouldBe true
        }

        "parameters carry name and type from the binding, and the value is masked at the source (⚑ES-2)" {
            val bindings =
                listOf(
                    binding("year_from", "int", Value.newBuilder().setIntValue(2025).build()),
                    binding("customer", "text", Value.newBuilder().setStringValue("Marvy Oil, s.r.o.").build()),
                )

            val receipt = statementHalf(parameters = bindings).shouldNotBeNull()

            receipt.parametersCount shouldBe 2
            receipt.getParameters(0).name shouldBe "year_from"
            receipt.getParameters(0).type shouldBe "int"
            receipt.getParameters(0).valueMasked shouldBe ExecutionReceiptBuilder.MASKED
            receipt.getParameters(0).masked shouldBe true
            receipt.getParameters(1).name shouldBe "customer"
            receipt.getParameters(1).type shouldBe "text"
            receipt.getParameters(1).valueMasked shouldBe ExecutionReceiptBuilder.MASKED

            // The proof that matters: no bound value reaches the wire in any field.
            val onTheWire = receipt.toByteArray().toString(Charsets.ISO_8859_1)
            onTheWire.contains("Marvy Oil") shouldBe false
            onTheWire.contains("2025") shouldBe false
        }

        "a failure before unparse names itself NONE and carries no statement" {
            val receipt =
                statementHalf(kind = StatementKind.NONE, sql = null, dialect = "").shouldNotBeNull()

            receipt.statementKind shouldBe StatementKind.NONE
            receipt.statement shouldBe ""
            receipt.statementRef shouldBe ""
            receipt.statementTruncated shouldBe false
        }

        "the worker names itself and the connection it used" {
            val receipt =
                statementHalf(
                    connectionId = "pg-hartland-cz",
                    engineLabel = "worker-postgres@pg-hartland-cz",
                    rowsTotal = 12,
                    durationMs = 184,
                    rls = RlsOutcome.RLS_APPLIED,
                ).shouldNotBeNull()

            receipt.connectionId shouldBe "pg-hartland-cz"
            receipt.engineLabel shouldBe "worker-postgres@pg-hartland-cz"
            receipt.rowsTotal shouldBe 12L
            receipt.durationMs shouldBe 184L
            receipt.rls shouldBe RlsOutcome.RLS_APPLIED
            receipt.dialect shouldBe "POSTGRESQL"
        }

        "a throwing supplier yields an ABSENT receipt, not a failed run" {
            shouldNotThrowAny {
                ExecutionReceiptBuilder.guarded { error("the receipt blew up") }.shouldBeNull()
            }
        }

        "…and the same holds when the throw comes from inside statementHalf's own inputs" {
            // A parameter list that cannot be read — the shape a real failure takes when a
            // binding's value is computed lazily by a caller that is already unwinding.
            val hostile =
                object : AbstractList<ParameterBinding>() {
                    override val size: Int get() = 1

                    override fun get(index: Int): ParameterBinding = error("binding unreadable")
                }

            shouldNotThrowAny {
                ExecutionReceiptBuilder
                    .statementHalf(
                        kind = StatementKind.SQL_EXECUTED,
                        dialect = "POSTGRESQL",
                        sql = "SELECT 1",
                        parameters = hostile,
                        connectionId = "pg",
                        engineLabel = "worker-postgres@pg",
                        rowsTotal = 0,
                        durationMs = 0,
                        rls = RlsOutcome.RLS_NOT_REQUIRED,
                        correlationId = "c",
                    ).shouldBeNull()
            }
        }
        // ── ES-P0·S0.3 — the plan half, and the meeting of the two ─────────────────────────────

        "the plan half carries the dispatched plan and the validator's security set" {
            val plan = scan("positions")
            val rule =
                SecurityRuleApplied
                    .newBuilder()
                    .setRuleId("rls.tenant")
                    .setPredicateSummary("WHERE tenant_id = (your tenant)")
                    .build()

            val receipt =
                ExecutionReceiptBuilder
                    .planHalf(
                        dispatchedPlan = plan,
                        securityApplied = listOf(rule),
                        effectiveSchema = "DB",
                        cacheHit = true,
                        compileMs = 31,
                    ).shouldNotBeNull()

            receipt.dispatchedPlan shouldBe plan
            receipt.planOmittedReason shouldBe ""
            receipt.securityAppliedList shouldBe listOf(rule)
            receipt.effectiveSchema shouldBe "DB"
            receipt.cacheHit shouldBe true
            receipt.compileMs shouldBe 31L

            // The plan half says nothing about the statement — that is the worker's fact.
            receipt.statementKind shouldBe StatementKind.STATEMENT_KIND_UNSPECIFIED
            receipt.statement shouldBe ""
        }

        "a plan over the cap is OMITTED with a reason — a cut proto would not parse at all" {
            // One oversized qualified name is enough to blow the 256 KiB cap.
            val huge = scan("p".repeat(ExecutionReceiptBuilder.MAX_PLAN_BYTES + 1))
            huge.serializedSize shouldBeGreaterThan ExecutionReceiptBuilder.MAX_PLAN_BYTES

            val receipt =
                ExecutionReceiptBuilder
                    .planHalf(
                        dispatchedPlan = huge,
                        securityApplied = emptyList(),
                        effectiveSchema = "DB",
                        cacheHit = false,
                        compileMs = 12,
                    ).shouldNotBeNull()

            receipt.hasDispatchedPlan() shouldBe false
            receipt.planOmittedReason shouldBe ExecutionReceiptBuilder.PLAN_OVER_CAP
            // Everything else the half knows still travels.
            receipt.effectiveSchema shouldBe "DB"
            receipt.compileMs shouldBe 12L
        }

        "merging the halves keeps BOTH — neither hop overwrites a fact it does not hold" {
            val worker = statementHalf(sql = "SELECT 1", rowsTotal = 7, rls = RlsOutcome.RLS_APPLIED)
            val query =
                ExecutionReceiptBuilder.planHalf(
                    dispatchedPlan = scan("positions"),
                    securityApplied = emptyList(),
                    effectiveSchema = "ER",
                    cacheHit = false,
                    compileMs = 9,
                    dispatchTarget = "worker-postgres:7401",
                )

            val merged = ExecutionReceiptBuilder.merged(worker, query).shouldNotBeNull()

            merged.statement shouldBe "SELECT 1"
            merged.rowsTotal shouldBe 7L
            merged.rls shouldBe RlsOutcome.RLS_APPLIED
            merged.dispatchedPlan shouldBe scan("positions")
            merged.effectiveSchema shouldBe "ER"
            merged.dispatchTarget shouldBe "worker-postgres:7401"
        }

        "merging is IDEMPOTENT — a re-attached half does not double the rules or the roster" {
            // ⚑ES-1 re-attaches the plan half to the last batch, so a consumer that merges the
            // first batch and the last meets the same set twice. Proto's own merge would append.
            val rule =
                SecurityRuleApplied
                    .newBuilder()
                    .setRuleId("rls.tenant")
                    .setPredicateSummary("WHERE tenant_id = (your tenant)")
                    .build()
            val plan =
                ExecutionReceiptBuilder.planHalf(
                    dispatchedPlan = scan("positions"),
                    securityApplied = listOf(rule),
                    effectiveSchema = "DB",
                    cacheHit = false,
                    compileMs = 9,
                )
            val worker =
                statementHalf(
                    sql = "SELECT 1",
                    parameters = listOf(binding("year_from", "int", Value.newBuilder().setIntValue(2025).build())),
                )

            val last = ExecutionReceiptBuilder.merged(worker, plan)
            val both = ExecutionReceiptBuilder.merged(plan, last).shouldNotBeNull()

            both.securityAppliedList shouldBe listOf(rule)
            both.parametersCount shouldBe 1
            both.parametersList.single().name shouldBe "year_from"
        }

        "merging tolerates an absent half on either side, and two absences" {
            val worker = statementHalf(sql = "SELECT 1")

            ExecutionReceiptBuilder.merged(worker, null) shouldBe worker
            ExecutionReceiptBuilder.merged(null, worker) shouldBe worker
            ExecutionReceiptBuilder.merged(null, null).shouldBeNull()
        }
    })

private fun binding(
    name: String,
    type: String,
    value: Value,
): ParameterBinding =
    ParameterBinding
        .newBuilder()
        .setName(name)
        .setType(type)
        .setValue(value)
        .build()

private fun statementHalf(
    kind: StatementKind = StatementKind.SQL_EXECUTED,
    dialect: String = "POSTGRESQL",
    sql: String? = "SELECT 1",
    parameters: List<ParameterBinding> = emptyList(),
    connectionId: String = "pg-hartland-cz",
    engineLabel: String = "worker-postgres@pg-hartland-cz",
    rowsTotal: Long = 0,
    durationMs: Long = 1,
    rls: RlsOutcome = RlsOutcome.RLS_NOT_REQUIRED,
    correlationId: String = "corr-1",
) = ExecutionReceiptBuilder.statementHalf(
    kind = kind,
    dialect = dialect,
    sql = sql,
    parameters = parameters,
    connectionId = connectionId,
    engineLabel = engineLabel,
    rowsTotal = rowsTotal,
    durationMs = durationMs,
    rls = rls,
    correlationId = correlationId,
)

private fun scan(table: String): PlanNode =
    PlanNode
        .newBuilder()
        .setTableScan(
            TableScanNode.newBuilder().setTable(
                QualifiedName.newBuilder().setNamespace("tpcds").setName(table),
            ),
        ).build()
