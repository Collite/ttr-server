// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.mcp.tools

import com.google.protobuf.TextFormat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.tatrman.worker.receipt.ExecutionReceiptBuilder
import org.tatrman.worker.v1.ExecutionReceipt
import org.tatrman.worker.v1.ResultBatch

/**
 * **ES-P0·S0.5 — the `execution` object (contracts §2).**
 *
 * The receipt crosses the wire in two halves on two batches (⚑ES-1: the plan half on the first,
 * the statement half on the last). This is where they stop being two: query-mcp merges them once
 * so that golem, Studio and every other MCP client see ONE object and never have to know the
 * shape of the stream that produced it.
 *
 * Two rules the JSON adds to the proto:
 *
 *  - the **plan crosses as text**, rendered by the same `TextFormat` printer `compile` already
 *    uses for `relPlanText` — one formatter, one rendering, and no proto bytes in a JSON payload;
 *  - the bound **value stays masked** (⚑ES-2). The worker masked it at the source; nothing here
 *    can unmask it, and the key is `value` because that is what the document's reader calls it.
 */
object ExecutionJson {
    /**
     * Merges every half the stream carried. Returns null when no batch carried a receipt at all —
     * an older server, or a run that never reached a worker — and the caller then emits no
     * `execution` key rather than an object full of empty strings.
     */
    fun mergeReceipts(batches: List<ResultBatch>): ExecutionReceipt? =
        batches
            .filter { it.hasReceipt() }
            .fold(null as ExecutionReceipt?) { acc, batch ->
                ExecutionReceiptBuilder.merged(acc, batch.receipt)
            }

    /** The `execution` object exactly as contracts §2 spells it. */
    fun toJson(receipt: ExecutionReceipt): JsonObject =
        buildJsonObject {
            put("statementKind", JsonPrimitive(receipt.statementKind.name))
            put("dialect", JsonPrimitive(receipt.dialect))
            put("statement", JsonPrimitive(receipt.statement))
            put("statementRef", JsonPrimitive(receipt.statementRef))
            put("statementTruncated", JsonPrimitive(receipt.statementTruncated))
            put(
                "parameters",
                buildJsonArray {
                    for (p in receipt.parametersList) {
                        add(
                            buildJsonObject {
                                put("name", JsonPrimitive(p.name))
                                put("type", JsonPrimitive(p.type))
                                put("value", JsonPrimitive(p.valueMasked))
                                put("masked", JsonPrimitive(p.masked))
                            },
                        )
                    }
                },
            )
            put("connectionId", JsonPrimitive(receipt.connectionId))
            put("engineLabel", JsonPrimitive(receipt.engineLabel))
            put("rowsTotal", JsonPrimitive(receipt.rowsTotal))
            put("durationMs", JsonPrimitive(receipt.durationMs))
            put("rls", JsonPrimitive(receipt.rls.name))
            put(
                "dispatchedPlanText",
                JsonPrimitive(
                    if (receipt.hasDispatchedPlan()) {
                        TextFormat.printer().printToString(receipt.dispatchedPlan)
                    } else {
                        ""
                    },
                ),
            )
            put("planOmittedReason", JsonPrimitive(receipt.planOmittedReason))
            put(
                "securityApplied",
                buildJsonArray {
                    for (rule in receipt.securityAppliedList) {
                        add(
                            buildJsonObject {
                                put("ruleId", JsonPrimitive(rule.ruleId))
                                put("predicateSummary", JsonPrimitive(rule.predicateSummary))
                            },
                        )
                    }
                },
            )
            put("dispatchTarget", JsonPrimitive(receipt.dispatchTarget))
            put("effectiveSchema", JsonPrimitive(receipt.effectiveSchema))
            put("cacheHit", JsonPrimitive(receipt.cacheHit))
            put("compileMs", JsonPrimitive(receipt.compileMs))
        }
}
