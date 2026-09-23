// SPDX-License-Identifier: Apache-2.0
package org.tatrman.dispatch.grpc

import com.google.protobuf.kotlin.toByteString
import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeIn
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.tatrman.dispatch.client.WorkerClient
import org.tatrman.dispatch.registry.WorkerEntry
import org.tatrman.dispatch.registry.WorkerRegistry
import org.tatrman.dispatch.sticky.StickyRegistry
import org.tatrman.dispatch.v1.DispatchRequest
import org.tatrman.dispatch.v1.WorkerHealthStatus
import org.tatrman.dispatch.world.WorldConfig
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.worker.v1.ExecuteRequest
import org.tatrman.worker.v1.ExecutionReceipt
import org.tatrman.worker.v1.GetCapabilitiesResponse
import org.tatrman.worker.v1.ResultBatch
import org.tatrman.worker.v1.StatementKind

/**
 * **ES-P0·S0.4 — `dispatch_target`, contracts §1.3 option (b).**
 *
 * Which worker actually ran the query is a fact only the dispatcher holds: the query service asks for a
 * connection, the registry picks an endpoint, and today that choice survives only as a
 * `routing_decision` warning string nobody parses. The routed worker's endpoint goes on the
 * receipt, on the first batch — the batch the query service already reads for the plan half.
 *
 * Option (b) and not (a): `ExecutionReceipt` is on dispatch's classpath (`:shared:proto`), so
 * there is no module-layering reason to settle for an INFO warning that is the wrong vehicle for
 * a structured fact.
 */
class ExecutionReceiptDispatchSpec :
    StringSpec({

        "the first batch names the routed worker's endpoint — receipt created if the worker sent none" {
            val out = runBlocking { dispatchThrough(batches = listOf(bare(isFirst = true, isLast = true))) }

            val only = out.single()
            only.hasReceipt() shouldBe true
            only.receipt.dispatchTarget shouldBe "worker-a:9000"
        }

        "a worker that sent a statement half keeps every field of it" {
            val workerHalf =
                ExecutionReceipt
                    .newBuilder()
                    .setStatementKind(StatementKind.SQL_EXECUTED)
                    .setStatement("SELECT 1")
                    .setRowsTotal(7)
                    .setEngineLabel("worker-mssql@df-fin")
                    .build()
            val out =
                runBlocking {
                    dispatchThrough(
                        batches =
                            listOf(
                                bare(isFirst = true, receipt = workerHalf),
                                bare(isLast = true, index = 1, receipt = workerHalf),
                            ),
                    )
                }

            val first = out.first()
            first.receipt.dispatchTarget shouldBe "worker-a:9000"
            first.receipt.statement shouldBe "SELECT 1"
            first.receipt.rowsTotal shouldBe 7L
            first.receipt.engineLabel shouldBe "worker-mssql@df-fin"
        }

        "only the first batch is stamped — the rest pass through untouched" {
            val out =
                runBlocking {
                    dispatchThrough(
                        batches =
                            listOf(
                                bare(isFirst = true),
                                bare(index = 1),
                                bare(index = 2, isLast = true),
                            ),
                    )
                }

            out.size shouldBe 3
            out[0].receipt.dispatchTarget shouldBe "worker-a:9000"
            out[1].hasReceipt() shouldBe false
            out[2].hasReceipt() shouldBe false
        }

        "a sticky re-route reports the endpoint that actually ran it, not the one first pinned" {
            val world = worldConfig()
            val a = stub("worker-a:9000")
            val b = stub("worker-b:9000")
            val registry = WorkerRegistry()
            registry.seed(
                listOf(
                    entry("worker-a:9000", a, stateful = true),
                    entry("worker-b:9000", b, stateful = true),
                ),
            )
            val sticky = StickyRegistry()
            // The session is pinned to a worker that is no longer in the healthy candidate set,
            // so failover picks a live one — and the receipt must name THAT one.
            sticky.recordSticky("s-1", "worker-gone:9000")
            val svc =
                DispatchServiceImpl(
                    registry = registry,
                    sticky = sticky,
                    world = world,
                    allowStickyFailover = true,
                )

            val out =
                runBlocking {
                    svc
                        .dispatch(
                            DispatchRequest
                                .newBuilder()
                                .setPlan(scan())
                                .setContext(PipelineContext.newBuilder().setSessionId("s-1"))
                                .build(),
                        ).toList()
                }

            val target = out.first().receipt.dispatchTarget
            target shouldBeIn setOf("worker-a:9000", "worker-b:9000")
            // …and it is the worker the routing decision itself names — not the dead pin.
            out
                .first()
                .context.warningsList
                .first { it.code == "sticky_failover" }
                .message shouldContain "failing over to $target"
        }

        "an error batch (no worker was reached) carries no receipt" {
            val registry = WorkerRegistry() // empty — no worker advertises the connection
            val svc = DispatchServiceImpl(registry, StickyRegistry(), worldConfig())

            val out =
                runBlocking {
                    svc
                        .dispatch(
                            DispatchRequest
                                .newBuilder()
                                .setPlan(scan())
                                .setContext(PipelineContext.getDefaultInstance())
                                .build(),
                        ).toList()
                }

            val only = out.single()
            only.messagesList.single().code shouldBe "no_worker_for_connection"
            only.hasReceipt() shouldBe false
        }
    })

private fun worldConfig(): WorldConfig =
    WorldConfig.fromConfig(
        ConfigFactory.parseString(
            """
            world {
              default-connection = "df-fin"
              table-connections { "db.dbo.*" = "df-fin" }
            }
            """.trimIndent(),
        ),
    )

private fun scan(): PlanNode =
    PlanNode
        .newBuilder()
        .setTableScan(
            TableScanNode.newBuilder().setTable(
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(SchemaCode.DB)
                    .setNamespace("dbo")
                    .setName("QHDOK_FAKTURY"),
            ),
        ).build()

private fun bare(
    index: Int = 0,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    receipt: ExecutionReceipt? = null,
): ResultBatch =
    ResultBatch
        .newBuilder()
        .setBatchIndex(index)
        .setIsFirst(isFirst)
        .setIsLast(isLast)
        .setArrowIpc(ByteArray(0).toByteString())
        .setContext(PipelineContext.getDefaultInstance())
        .apply { if (receipt != null) setReceipt(receipt) }
        .build()

private fun stub(
    @Suppress("UNUSED_PARAMETER") endpoint: String,
    batches: List<ResultBatch> = listOf(bare(isFirst = true, isLast = true)),
): ReceiptStubClient = ReceiptStubClient(batches)

private fun entry(
    endpoint: String,
    client: ReceiptStubClient,
    stateful: Boolean = false,
): WorkerEntry =
    WorkerEntry(
        endpoint = endpoint,
        roleHint = "mssql",
        client = client,
        capabilities = client.capabilitiesFor(stateful),
        health = WorkerHealthStatus.HEALTHY,
        lastPolled = null,
        consecutiveFailures = 0,
    )

private suspend fun dispatchThrough(batches: List<ResultBatch>): List<ResultBatch> {
    val client = ReceiptStubClient(batches)
    val registry = WorkerRegistry()
    registry.seed(listOf(entry("worker-a:9000", client)))
    return DispatchServiceImpl(registry, StickyRegistry(), worldConfig())
        .dispatch(
            DispatchRequest
                .newBuilder()
                .setPlan(scan())
                .setContext(PipelineContext.getDefaultInstance())
                .build(),
        ).toList()
}

private class ReceiptStubClient(
    private val batches: List<ResultBatch>,
) : WorkerClient {
    fun capabilitiesFor(stateful: Boolean): GetCapabilitiesResponse =
        GetCapabilitiesResponse
            .newBuilder()
            .setEngineName("mssql")
            .addSupportedConnections("df-fin")
            .setSupportsStatefulSessions(stateful)
            .build()

    override suspend fun getCapabilities(): GetCapabilitiesResponse = capabilitiesFor(false)

    override fun execute(request: ExecuteRequest): Flow<ResultBatch> = flow { batches.forEach { emit(it) } }

    override fun close() = Unit
}
