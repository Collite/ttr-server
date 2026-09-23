// SPDX-License-Identifier: Apache-2.0
package org.tatrman.worker.v1

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.validate.v1.SecurityRuleApplied

/**
 * **ES-P0·S0.1 — the receipt on the wire.**
 *
 * `ExecutionReceipt` is filled in two halves by two hops that never meet (ES architecture §2):
 * the worker owns fields 1–11 (what it handed the engine, and what came back), the query service owns
 * 20–26 (what it dispatched, and what the validator applied). The gap 12–19 and the tail 27–39
 * are `reserved` so neither half can grow into the other's numbering by accident.
 *
 * The field numbers ARE the contract — ES contracts §1.1/§1.2 — so they are asserted from the
 * descriptor, not merely used. `ResultBatch.receipt = 8` in particular: 8 was the first free tag
 * after the existing 1–7, and a consumer that merges the first batch's plan half with the last
 * batch's statement half (⚑ES-1) reads it by number off both.
 */
class ExecutionReceiptProtoSpec :
    StringSpec({

        "a full receipt round-trips through proto with both halves intact" {
            val receipt =
                ExecutionReceipt
                    .newBuilder()
                    // ── statement half — the worker ──
                    .setStatementKind(StatementKind.SQL_EXECUTED)
                    .setDialect("POSTGRESQL")
                    .setStatement("SELECT \"month\", SUM(\"revenue\") FROM \"sales\" GROUP BY \"month\"")
                    .setStatementRef("")
                    .setStatementTruncated(false)
                    .addParameters(
                        BoundParameter
                            .newBuilder()
                            .setName("year_from")
                            .setType("int")
                            .setValueMasked("<masked>")
                            .setMasked(true),
                    ).setConnectionId("pg-hartland-cz")
                    .setEngineLabel("worker-postgres@pg-hartland-cz")
                    .setRowsTotal(12L)
                    .setDurationMs(184L)
                    .setRls(RlsOutcome.RLS_APPLIED)
                    // ── plan half — the query service ──
                    .setDispatchedPlan(tableScan("store_sales"))
                    .setPlanOmittedReason("")
                    .addSecurityApplied(
                        SecurityRuleApplied
                            .newBuilder()
                            .setRuleId("requires-tenant-id")
                            .setPredicateSummary("WHERE tenant_id = (your tenant)"),
                    ).setDispatchTarget("worker-postgres:7401")
                    .setEffectiveSchema("DB")
                    .setCacheHit(false)
                    .setCompileMs(31L)
                    .build()

            val parsed = ExecutionReceipt.parseFrom(receipt.toByteArray())

            parsed shouldBe receipt
            parsed.statementKind shouldBe StatementKind.SQL_EXECUTED
            parsed.statement shouldBe receipt.statement
            parsed.parametersList.single().name shouldBe "year_from"
            parsed.parametersList.single().valueMasked shouldBe "<masked>"
            parsed.parametersList.single().masked shouldBe true
            parsed.rowsTotal shouldBe 12L
            parsed.durationMs shouldBe 184L
            parsed.rls shouldBe RlsOutcome.RLS_APPLIED
            parsed.dispatchedPlan.tableScan.table.name shouldBe "store_sales"
            parsed.securityAppliedList.single().ruleId shouldBe "requires-tenant-id"
            parsed.dispatchTarget shouldBe "worker-postgres:7401"
            parsed.effectiveSchema shouldBe "DB"
            parsed.compileMs shouldBe 31L
        }

        "a receipt carrying only the plan half round-trips as such (the first batch, ⚑ES-1)" {
            val planHalf =
                ExecutionReceipt
                    .newBuilder()
                    .setDispatchedPlan(tableScan("web_sales"))
                    .setEffectiveSchema("ER")
                    .setCacheHit(true)
                    .setCompileMs(4L)
                    .build()

            val parsed = ExecutionReceipt.parseFrom(planHalf.toByteArray())

            parsed shouldBe planHalf
            // The statement half is UNSET, not empty-by-accident: a reader tells the two apart
            // by `statement_kind`, which is UNSPECIFIED until a worker fills it.
            parsed.statementKind shouldBe StatementKind.STATEMENT_KIND_UNSPECIFIED
            parsed.statement shouldBe ""
            parsed.rowsTotal shouldBe 0L
        }

        "an over-cap statement carries a ref instead of the text (A-10 shape)" {
            val overCap =
                ExecutionReceipt
                    .newBuilder()
                    .setStatementKind(StatementKind.SQL_EXECUTED)
                    .setStatement("")
                    .setStatementRef("worker:09568c3e-0000-4000-8000-000000000000")
                    .setStatementTruncated(true)
                    .build()

            val parsed = ExecutionReceipt.parseFrom(overCap.toByteArray())

            parsed.statement shouldBe ""
            parsed.statementRef shouldBe "worker:09568c3e-0000-4000-8000-000000000000"
            parsed.statementTruncated shouldBe true
        }

        "ResultBatch.receipt is field 8 — the contract's number, not a convenience" {
            val receiptField = ResultBatch.getDescriptor().findFieldByName("receipt")
            receiptField.number shouldBe 8
            receiptField.messageType.fullName shouldBe "org.tatrman.worker.v1.ExecutionReceipt"

            // The batch a consumer actually reads: a tail marker with the statement half on it.
            val tail =
                ResultBatch
                    .newBuilder()
                    .setIsLast(true)
                    .setReceipt(
                        ExecutionReceipt
                            .newBuilder()
                            .setStatementKind(StatementKind.PLAN_EXECUTED)
                            .setEngineLabel("worker-polars@polars-local"),
                    ).build()

            val parsed = ResultBatch.parseFrom(tail.toByteArray())
            parsed.hasReceipt() shouldBe true
            parsed.receipt.statementKind shouldBe StatementKind.PLAN_EXECUTED
            parsed.receipt.engineLabel shouldBe "worker-polars@polars-local"
        }

        "StatementKind and RlsOutcome carry exactly the four values each the contract names" {
            StatementKind.entries.filter { it != StatementKind.UNRECOGNIZED }.map { it.name } shouldContainExactly
                listOf("STATEMENT_KIND_UNSPECIFIED", "SQL_EXECUTED", "PLAN_EXECUTED", "NONE")
            StatementKind.STATEMENT_KIND_UNSPECIFIED.number shouldBe 0
            StatementKind.SQL_EXECUTED.number shouldBe 1
            StatementKind.PLAN_EXECUTED.number shouldBe 2
            StatementKind.NONE.number shouldBe 3

            RlsOutcome.entries.filter { it != RlsOutcome.UNRECOGNIZED }.map { it.name } shouldContainExactly
                listOf("RLS_OUTCOME_UNSPECIFIED", "RLS_NOT_REQUIRED", "RLS_APPLIED", "RLS_FAILED")
            RlsOutcome.RLS_OUTCOME_UNSPECIFIED.number shouldBe 0
            RlsOutcome.RLS_NOT_REQUIRED.number shouldBe 1
            RlsOutcome.RLS_APPLIED.number shouldBe 2
            RlsOutcome.RLS_FAILED.number shouldBe 3
        }

        "the two halves are fenced apart by reserved 12 to 19 and 27 to 39" {
            // Proto reserved ranges are half-open: `reserved 12 to 19` is [12, 20).
            val ranges =
                ExecutionReceipt
                    .getDescriptor()
                    .toProto()
                    .reservedRangeList
                    .map { it.start to it.end }

            ranges shouldContainExactly listOf(12 to 20, 27 to 40)
        }

        "every ExecutionReceipt field sits on the number contracts §1.1 gave it" {
            val numbers =
                ExecutionReceipt
                    .getDescriptor()
                    .fields
                    .associate { it.name to it.number }

            numbers shouldBe
                mapOf(
                    "statement_kind" to 1,
                    "dialect" to 2,
                    "statement" to 3,
                    "statement_ref" to 4,
                    "statement_truncated" to 5,
                    "parameters" to 6,
                    "connection_id" to 7,
                    "engine_label" to 8,
                    "rows_total" to 9,
                    "duration_ms" to 10,
                    "rls" to 11,
                    "dispatched_plan" to 20,
                    "plan_omitted_reason" to 21,
                    "security_applied" to 22,
                    "dispatch_target" to 23,
                    "effective_schema" to 24,
                    "cache_hit" to 25,
                    "compile_ms" to 26,
                )

            BoundParameter
                .getDescriptor()
                .fields
                .associate { it.name to it.number } shouldBe
                mapOf("name" to 1, "type" to 2, "value_masked" to 3, "masked" to 4)
        }
    })

/** The smallest plan that is a real plan: what a dispatched `PlanNode` looks like on the wire. */
private fun tableScan(table: String): PlanNode =
    PlanNode
        .newBuilder()
        .setTableScan(
            TableScanNode.newBuilder().setTable(
                QualifiedName.newBuilder().setNamespace("tpcds").setName(table),
            ),
        ).build()
