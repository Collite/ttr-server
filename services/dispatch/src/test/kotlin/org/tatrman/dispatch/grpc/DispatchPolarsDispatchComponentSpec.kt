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
import org.tatrman.plan.v1.WorkspaceRef
import org.tatrman.worker.v1.ExecuteRequest
import org.tatrman.worker.v1.GetCapabilitiesResponse
import org.tatrman.worker.v1.ResultBatch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fork Stage 3.4 T4 — Dispatch ⇄ Polars dispatch component test (in-process worker).
 *
 * Polars is the stateful Polars worker: it advertises `supports_stateful_sessions = true`
 * and no `supported_connections` (a DataFrame engine, not a connection-backed one), so
 * Dispatch routes workspace-rooted plans to it by session, sticky-pinning a `session_id` to
 * one pod. This exercises the stateful path the MSSQL worker (Mssql) doesn't have:
 *
 *   1. a WorkspaceRef plan + session routes to Polars; Arrow IPC streams back intact;
 *   2. the "stage a DF, then read it" flow — a first plan with `assign_to_workspace` and a
 *      second WorkspaceRef plan in the same session land on the SAME worker (sticky), with
 *      `assign_to_workspace` forwarded to the worker unchanged.
 *
 * True on-K3s dispatch integration is deferred to the separate integration-test suite.
 */
class DispatchPolarsDispatchComponentSpec :
    StringSpec({

        val frame = byteArrayOf(0x41, 0x52, 0x52, 0x4F, 0x57, 0x31, 0x2A, 0x2B) // "ARROW1" + payload
        val fingerprint = "b".repeat(64)

        // Polars plans are workspace-rooted, so World routing is degenerate here.
        val world =
            WorldConfig.fromConfig(
                ConfigFactory.parseString(
                    """world { default-connection = "pg-default" }""",
                ),
            )

        fun statefulPolars(endpoint: String): PolarsStub =
            PolarsStub(
                endpoint = endpoint,
                execute = {
                    flow {
                        emit(
                            ResultBatch
                                .newBuilder()
                                .setIsFirst(true)
                                .setIsLast(true)
                                .setBatchIndex(0)
                                .setBatchRowCount(3)
                                .setSchemaFingerprint(fingerprint)
                                .setArrowIpc(frame.toByteString())
                                .setContext(PipelineContext.getDefaultInstance())
                                .build(),
                        )
                    }
                },
            )

        fun svc(vararg workers: PolarsStub): DispatchServiceImpl {
            val registry = WorkerRegistry()
            registry.seed(
                workers.map {
                    WorkerEntry(
                        endpoint = it.endpoint,
                        roleHint = "polars",
                        client = it,
                        capabilities = it.capabilities,
                        health = WorkerHealthStatus.HEALTHY,
                        lastPolled = null,
                        consecutiveFailures = 0,
                    )
                },
            )
            return DispatchServiceImpl(registry, StickyRegistry(), world)
        }

        "workspace-rooted plan + session routes to Polars and Arrow IPC streams back intact" {
            val polars = statefulPolars("polars:7301")
            val req =
                DispatchRequest
                    .newBuilder()
                    .setPlan(workspaceRef("q1"))
                    .setContext(PipelineContext.newBuilder().setSessionId("s1"))
                    .build()

            val out = svc(polars).dispatch(req).toList()

            out.size shouldBe 1
            out[0].isLast shouldBe true
            out[0].schemaFingerprint shouldBe fingerprint
            out[0].arrowIpc.toByteArray() shouldBe frame
            out[0].context.warningsList.map { it.code } shouldContain "routing_decision"
            polars.received.size shouldBe 1
        }

        "stage-then-read: assign_to_workspace forwarded and the session sticks to one worker" {
            val a = statefulPolars("polars-a:7301")
            val b = statefulPolars("polars-b:7301")
            val svc = svc(a, b)

            // 1) Stage a DF into the session workspace.
            svc
                .dispatch(
                    DispatchRequest
                        .newBuilder()
                        .setPlan(workspaceRef("base"))
                        .setContext(PipelineContext.newBuilder().setSessionId("sess-42"))
                        .setOptions(
                            org.tatrman.worker.v1.ExecutionOptions
                                .getDefaultInstance(),
                        ).build()
                        .toBuilder()
                        .build(),
                ).toList()

            // 2) Read it back in the same session — must hit the same worker (sticky).
            svc
                .dispatch(
                    DispatchRequest
                        .newBuilder()
                        .setPlan(workspaceRef("derived"))
                        .setContext(PipelineContext.newBuilder().setSessionId("sess-42"))
                        .build(),
                ).toList()

            // Exactly one of the two workers served both dispatches (sticky pin), the other none.
            val servedCounts = listOf(a.received.size, b.received.size).sorted()
            servedCounts shouldBe listOf(0, 2)
        }
    })

private class PolarsStub(
    val endpoint: String,
    private val execute: (ExecuteRequest) -> Flow<ResultBatch>,
) : WorkerClient {
    val received = CopyOnWriteArrayList<ExecuteRequest>()

    val capabilities: GetCapabilitiesResponse =
        GetCapabilitiesResponse
            .newBuilder()
            .setEngineName("polars")
            .setSupportsStatefulSessions(true)
            .setMaxConcurrentSessions(1000)
            .build()

    override suspend fun getCapabilities(): GetCapabilitiesResponse = capabilities

    override fun execute(request: ExecuteRequest): Flow<ResultBatch> {
        received.add(request)
        return execute.invoke(request)
    }

    override fun close() = Unit
}

private fun workspaceRef(name: String): PlanNode =
    PlanNode
        .newBuilder()
        .setWorkspaceRef(WorkspaceRef.newBuilder().setWorkspaceName(name))
        .build()
