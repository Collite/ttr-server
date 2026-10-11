// SPDX-License-Identifier: Apache-2.0
package org.tatrman.dispatch.grpc

import com.google.protobuf.kotlin.toByteString
import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import org.tatrman.dispatch.client.WorkerClient
import org.tatrman.dispatch.registry.WorkerEntry
import org.tatrman.dispatch.registry.WorkerRegistry
import org.tatrman.dispatch.sticky.StickyRegistry
import org.tatrman.dispatch.world.WorldConfig
import org.tatrman.dispatch.v1.DispatchRequest
import org.tatrman.dispatch.v1.WorkerHealthStatus
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.worker.v1.ExecuteRequest
import org.tatrman.worker.v1.GetCapabilitiesResponse
import org.tatrman.worker.v1.ResultBatch

/**
 * Fork Stage 3.3 T6 — Dispatch ⇄ Mssql dispatch component test (in-process worker).
 *
 * Dispatch receives a validated fixture plan, matches it to Mssql by capability
 * (engine "mssql" serving connection "hartland-default"), and streams the worker's Arrow IPC
 * batches back through itself. Dispatch's contract is pass-through fidelity: it must
 * not wrap, reorder, or mutate the worker's `arrow_ipc` frames (the Arrow encode/
 * decode round-trip itself is pinned worker-side by ArrowIpcFormatterContractSpec).
 *
 * The second case proves an unroutable plan fails closed with a typed Rule-6 message
 * rather than hanging: the stream terminates (`toList` returns) carrying the error.
 *
 * True on-K3s dispatch integration is deferred to the separate integration-test suite.
 */
class DispatchMssqlDispatchComponentSpec :
    StringSpec({

        // Two distinct Arrow IPC frames, as Mssql would emit them: a first batch
        // (schema + rows, carrying the schema_fingerprint) and a tail batch.
        val firstFrame = byteArrayOf(0x41, 0x52, 0x52, 0x4F, 0x57, 0x31, 0x00, 0x11) // "ARROW1" + payload
        val tailFrame = byteArrayOf(0x41, 0x52, 0x52, 0x4F, 0x57, 0x31, 0x7F, 0x7E)
        val fingerprint = "a".repeat(64)

        val world =
            WorldConfig.fromConfig(
                ConfigFactory.parseString(
                    """
                    world {
                      default-connection = "pg-unserved"
                      table-connections {
                        "db.dbo.ORDERS*" = "hartland-default"
                      }
                    }
                    """.trimIndent(),
                ),
            )

        // Mssql: an in-process MSSQL worker serving hartland-default, streaming two real frames.
        val mssql =
            MssqlStub(
                connections = listOf("hartland-default"),
                execute = {
                    flow {
                        emit(
                            ResultBatch
                                .newBuilder()
                                .setIsFirst(true)
                                .setIsLast(false)
                                .setBatchIndex(0)
                                .setBatchRowCount(2)
                                .setSchemaFingerprint(fingerprint)
                                .setArrowIpc(firstFrame.toByteString())
                                .setContext(PipelineContext.getDefaultInstance())
                                .build(),
                        )
                        emit(
                            ResultBatch
                                .newBuilder()
                                .setIsFirst(false)
                                .setIsLast(true)
                                .setBatchIndex(1)
                                .setBatchRowCount(0)
                                .setArrowIpc(tailFrame.toByteString())
                                .setContext(PipelineContext.getDefaultInstance())
                                .build(),
                        )
                    }
                },
            )

        fun svc(): DispatchServiceImpl {
            val registry = WorkerRegistry()
            registry.seed(
                listOf(
                    WorkerEntry(
                        endpoint = "mssql:7296",
                        roleHint = "mssql",
                        client = mssql,
                        capabilities = mssql.capabilities,
                        health = WorkerHealthStatus.HEALTHY,
                        lastPolled = null,
                        consecutiveFailures = 0,
                    ),
                ),
            )
            return DispatchServiceImpl(registry, StickyRegistry(), world)
        }

        "validated plan routes to Mssql and Arrow IPC streams back through Dispatch intact" {
            val req =
                DispatchRequest
                    .newBuilder()
                    .setPlan(scan(qname("db", "dbo", "ORDERS")))
                    .setContext(PipelineContext.getDefaultInstance())
                    .build()

            val out = svc().dispatch(req).toList()

            // Both worker batches arrive, in order, with their Arrow frames byte-identical.
            out.size shouldBe 2
            out[0].isFirst shouldBe true
            out[0].schemaFingerprint shouldBe fingerprint
            out[0].arrowIpc.toByteArray() shouldBe firstFrame
            out[1].isLast shouldBe true
            out[1].arrowIpc.toByteArray() shouldBe tailFrame
            // Dispatch annotated the routing decision on the first batch (its only addition).
            out[0].context.warningsList.map { it.code } shouldContain "routing_decision"
        }

        "unroutable plan fails closed with a typed Rule-6 message, not a hang" {
            // db.dbo.CUSTOMERS maps to nothing → default-connection pg-unserved, which no
            // worker serves. The stream must terminate carrying the error, not block.
            val req =
                DispatchRequest
                    .newBuilder()
                    .setPlan(scan(qname("db", "dbo", "CUSTOMERS")))
                    .setContext(PipelineContext.getDefaultInstance())
                    .build()

            val out = svc().dispatch(req).toList()

            out.size shouldBe 1
            out[0].isLast shouldBe true
            out[0].messagesList[0].code shouldBe "no_worker_for_connection"
        }
    })

private class MssqlStub(
    connections: List<String>,
    private val execute: (ExecuteRequest) -> Flow<ResultBatch>,
) : WorkerClient {
    val capabilities: GetCapabilitiesResponse =
        GetCapabilitiesResponse
            .newBuilder()
            .setEngineName("mssql")
            .addAllSupportedConnections(connections)
            .setSupportsStatefulSessions(false)
            .build()

    override suspend fun getCapabilities(): GetCapabilitiesResponse = capabilities

    override fun execute(request: ExecuteRequest): Flow<ResultBatch> = execute.invoke(request)

    override fun close() = Unit
}

private fun qname(
    schema: String,
    ns: String,
    name: String,
): QualifiedName =
    QualifiedName
        .newBuilder()
        .setSchemaCode(SchemaCode.valueOf(schema.uppercase()))
        .setNamespace(ns)
        .setName(name)
        .build()

private fun scan(table: QualifiedName): PlanNode =
    PlanNode
        .newBuilder()
        .setTableScan(TableScanNode.newBuilder().setTable(table))
        .build()
