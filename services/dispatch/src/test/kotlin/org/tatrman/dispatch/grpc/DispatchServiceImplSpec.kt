// SPDX-License-Identifier: Apache-2.0
package org.tatrman.dispatch.grpc

import com.google.protobuf.kotlin.toByteString
import com.typesafe.config.ConfigFactory
import org.tatrman.dispatch.v1.DispatchRequest
import org.tatrman.dispatch.v1.WorkerHealthStatus
import org.tatrman.plan.v1.JoinNode
import org.tatrman.plan.v1.JoinType
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.worker.v1.ExecuteRequest
import org.tatrman.worker.v1.GetCapabilitiesResponse
import org.tatrman.worker.v1.ResultBatch
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain as shouldContainStr
import io.kotest.matchers.string.shouldNotMatch
import org.tatrman.worker.v1.ConnectionInfo
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import org.tatrman.dispatch.client.WorkerClient
import org.tatrman.dispatch.registry.WorkerEntry
import org.tatrman.dispatch.registry.WorkerRegistry
import org.tatrman.dispatch.sticky.StickyRegistry
import org.tatrman.dispatch.world.WorldConfig

class DispatchServiceImplSpec :
    StringSpec({
        val storeSales = qname("db", "dbo", "store_sales")
        val customerAddress = qname("db", "dbo", "customer_address")
        val world =
            WorldConfig.fromConfig(
                ConfigFactory.parseString(
                    """
                    world {
                      default-connection = "pg-default"
                      table-connections {
                        "db.dbo.store_*" = "pg-store"
                        "db.dbo.customer*" = "pg-customer"
                      }
                    }
                    """.trimIndent(),
                ),
            )

        fun fixture(
            stateful: Boolean = false,
            connections: List<String> = listOf("pg-store"),
            execute: (ExecuteRequest) -> Flow<ResultBatch> = { defaultStream() },
        ): Pair<DispatchServiceImpl, StickyRegistry> {
            val client = StubClient(connections = connections, supportsStateful = stateful, execute = execute)
            val registry = WorkerRegistry()
            registry.seed(
                listOf(
                    WorkerEntry(
                        endpoint = "worker-a:9000",
                        roleHint = "mssql",
                        client = client,
                        capabilities = client.capabilities,
                        health = WorkerHealthStatus.HEALTHY,
                        lastPolled = null,
                        consecutiveFailures = 0,
                    ),
                ),
            )
            val sticky = StickyRegistry()
            return DispatchServiceImpl(registry, sticky, world) to sticky
        }

        fun fixtureWithFailover(): Pair<DispatchServiceImpl, StickyRegistry> {
            val client =
                StubClient(
                    connections = listOf("pg-store"),
                    supportsStateful = true,
                    execute = { defaultStream() },
                )
            val registry = WorkerRegistry()
            registry.seed(
                listOf(
                    WorkerEntry(
                        endpoint = "worker-a:9000",
                        roleHint = "mssql",
                        client = client,
                        capabilities = client.capabilities,
                        health = WorkerHealthStatus.HEALTHY,
                        lastPolled = null,
                        consecutiveFailures = 0,
                    ),
                ),
            )
            val sticky = StickyRegistry()
            return DispatchServiceImpl(
                registry = registry,
                sticky = sticky,
                world = world,
                allowStickyFailover = true,
            ) to sticky
        }

        "single-connection plan routes through Worker" {
            val (svc, _) = fixture()
            val req =
                DispatchRequest
                    .newBuilder()
                    .setPlan(scan(storeSales))
                    .setContext(PipelineContext.getDefaultInstance())
                    .build()
            val out = svc.dispatch(req).toList()
            out.size shouldBe 1
            out[0].isLast shouldBe true
            out[0].context.warningsList.map { it.code } shouldContain "routing_decision"
        }

        "cross-database plan rejected with cross_database_not_supported" {
            val (svc, _) = fixture(connections = listOf("pg-store", "pg-customer"))
            val req =
                DispatchRequest
                    .newBuilder()
                    .setPlan(
                        PlanNode
                            .newBuilder()
                            .setJoin(
                                JoinNode
                                    .newBuilder()
                                    .setLeft(scan(storeSales))
                                    .setRight(scan(customerAddress))
                                    .setJoinType(JoinType.INNER),
                            ).build(),
                    ).setContext(PipelineContext.getDefaultInstance())
                    .build()
            val out = svc.dispatch(req).toList()
            out.size shouldBe 1
            out[0].messagesList.size shouldBe 1
            out[0].messagesList[0].code shouldBe "cross_database_not_supported"
        }

        "explicit connection_id overrides derived with warning" {
            val (svc, _) = fixture(connections = listOf("pg-store", "pg-other"))
            val req =
                DispatchRequest
                    .newBuilder()
                    .setPlan(scan(storeSales))
                    .setContext(PipelineContext.getDefaultInstance())
                    .setConnectionId("pg-other")
                    .build()
            val out = svc.dispatch(req).toList()
            out[0].context.warningsList.map { it.code } shouldContain "connection_id_overrides_derived"
        }

        "no Worker for connection emits no_worker_for_connection" {
            val (svc, _) = fixture(connections = listOf("pg-other"))
            val req =
                DispatchRequest
                    .newBuilder()
                    .setPlan(scan(storeSales))
                    .setContext(PipelineContext.getDefaultInstance())
                    .build()
            val out = svc.dispatch(req).toList()
            out[0].messagesList[0].code shouldBe "no_worker_for_connection"
        }

        "Values-only plan routes to default-connection" {
            val (svc, _) = fixture(connections = listOf("pg-default"))
            val req =
                DispatchRequest
                    .newBuilder()
                    .setPlan(
                        PlanNode
                            .newBuilder()
                            .setValues(
                                org.tatrman.plan.v1.ValuesNode
                                    .newBuilder(),
                            ).build(),
                    ).setContext(PipelineContext.getDefaultInstance())
                    .build()
            val out = svc.dispatch(req).toList()
            out.size shouldBe 1
            out[0].isLast shouldBe true
        }

        "stateful Worker with session_id records sticky entry" {
            val (svc, sticky) = fixture(stateful = true)
            val req =
                DispatchRequest
                    .newBuilder()
                    .setPlan(scan(storeSales))
                    .setContext(PipelineContext.newBuilder().setSessionId("s-1"))
                    .build()
            svc.dispatch(req).toList()
            sticky.findSticky("s-1") shouldBe "worker-a:9000"
        }

        "stateless Worker with session_id does not record sticky entry" {
            val (svc, sticky) = fixture(stateful = false)
            val req =
                DispatchRequest
                    .newBuilder()
                    .setPlan(scan(storeSales))
                    .setContext(PipelineContext.newBuilder().setSessionId("s-1"))
                    .build()
            svc.dispatch(req).toList()
            sticky.findSticky("s-1") shouldBe null
        }

        "Worker stream failure surfaces worker_stream_failed" {
            val (svc, _) =
                fixture(
                    execute = { _ ->
                        flow {
                            emit(
                                ResultBatch
                                    .newBuilder()
                                    .setIsFirst(true)
                                    .setArrowIpc(ByteArray(0).toByteString())
                                    .build(),
                            )
                            throw RuntimeException("connection reset")
                        }
                    },
                )
            val req =
                DispatchRequest
                    .newBuilder()
                    .setPlan(scan(storeSales))
                    .setContext(PipelineContext.getDefaultInstance())
                    .build()
            val out = svc.dispatch(req).toList()
            out.last().messagesList[0].code shouldBe "worker_stream_failed"
        }

        "session_lost when sticky worker is missing from candidates" {
            // Pre-pin a session to a worker that is NOT in the registry.
            val (svc, sticky) = fixture()
            sticky.recordSticky("s-2", "ghost-worker:9000")
            val req =
                DispatchRequest
                    .newBuilder()
                    .setPlan(scan(storeSales))
                    .setContext(PipelineContext.newBuilder().setSessionId("s-2"))
                    .build()
            val out = svc.dispatch(req).toList()
            out[0].messagesList[0].code shouldBe "session_lost"
        }

        "DF-D01 — non-sticky traffic with multiple workers routes to the least-loaded one" {
            val a = StubClient(connections = listOf("pg-store"), supportsStateful = false) { defaultStream() }
            val b = StubClient(connections = listOf("pg-store"), supportsStateful = false) { defaultStream() }
            val reg = WorkerRegistry()
            reg.seed(
                listOf(
                    WorkerEntry("w-a:9000", "mssql", a, a.capabilities, WorkerHealthStatus.HEALTHY, null, 0),
                    WorkerEntry("w-b:9000", "mssql", b, b.capabilities, WorkerHealthStatus.HEALTHY, null, 0),
                ),
            )
            // Pre-seed: w-a is busy with 5 in-flight dispatches, w-b is idle.
            val loadTracker =
                org.tatrman.dispatch.routing
                    .LoadTracker(mapOf("w-a:9000" to 5))
            val svc = DispatchServiceImpl(reg, StickyRegistry(), world, loadTracker = loadTracker)

            val req =
                DispatchRequest
                    .newBuilder()
                    .setPlan(scan(storeSales))
                    .setContext(PipelineContext.getDefaultInstance())
                    .build()
            val out = svc.dispatch(req).toList()
            val warnings = out.first().context.warningsList
            val decision = warnings.firstOrNull { it.code == "routing_decision" }
            decision shouldNotBe null
            decision!!.message shouldContainStr "w-b:9000"
            decision.message shouldContainStr "least-loaded"
        }

        "DF-D01 — ties broken deterministically by endpoint string" {
            val a = StubClient(connections = listOf("pg-store"), supportsStateful = false) { defaultStream() }
            val b = StubClient(connections = listOf("pg-store"), supportsStateful = false) { defaultStream() }
            val reg = WorkerRegistry()
            reg.seed(
                listOf(
                    WorkerEntry("w-b:9000", "mssql", b, b.capabilities, WorkerHealthStatus.HEALTHY, null, 0),
                    WorkerEntry("w-a:9000", "mssql", a, a.capabilities, WorkerHealthStatus.HEALTHY, null, 0),
                ),
            )
            // Both idle (load 0). Endpoint order in the registry puts w-b first, but tie-break
            // by endpoint string should pick w-a:9000.
            val svc = DispatchServiceImpl(reg, StickyRegistry(), world)
            val req =
                DispatchRequest
                    .newBuilder()
                    .setPlan(scan(storeSales))
                    .setContext(PipelineContext.getDefaultInstance())
                    .build()
            val out = svc.dispatch(req).toList()
            val decision =
                out
                    .first()
                    .context.warningsList
                    .first { it.code == "routing_decision" }
            decision.message shouldContainStr "w-a:9000"
        }

        "DF-D03 — new session_id with multi-pod candidate set picks deterministically via consistent-hash" {
            // Two workers serving the same connection; a fresh dispatcher with no prior pin must
            // pick the same pod for the same session across cold starts (i.e. across DispatcherImpl
            // instances). We verify by constructing the dispatcher twice and confirming the chosen
            // pod (recorded into sticky on first dispatch) matches.
            fun twoWorkerFixture(): Pair<DispatchServiceImpl, StickyRegistry> {
                val a = StubClient(connections = listOf("pg-store"), supportsStateful = true) { defaultStream() }
                val b = StubClient(connections = listOf("pg-store"), supportsStateful = true) { defaultStream() }
                val reg = WorkerRegistry()
                reg.seed(
                    listOf(
                        WorkerEntry("w-a:9000", "mssql", a, a.capabilities, WorkerHealthStatus.HEALTHY, null, 0),
                        WorkerEntry("w-b:9000", "mssql", b, b.capabilities, WorkerHealthStatus.HEALTHY, null, 0),
                    ),
                )
                val s = StickyRegistry()
                return DispatchServiceImpl(reg, s, world) to s
            }

            val (svc1, sticky1) = twoWorkerFixture()
            val (svc2, sticky2) = twoWorkerFixture()
            val req =
                DispatchRequest
                    .newBuilder()
                    .setPlan(scan(storeSales))
                    .setContext(PipelineContext.newBuilder().setSessionId("stable-session-1"))
                    .build()
            svc1.dispatch(req).toList()
            svc2.dispatch(req).toList()
            // Both cold-start dispatchers pin the same session to the same worker.
            sticky1.findSticky("stable-session-1") shouldBe sticky2.findSticky("stable-session-1")
            // And the warning trail shows the consistent-hash decision was used.
            val warnings =
                svc1
                    .dispatch(req)
                    .toList()
                    .first()
                    .context.warningsList
                    .map { it.code }
            // First call before this final dispatch already pinned the session; the second call
            // hits the sticky_session_match branch.
            warnings shouldContain "sticky_session_match"
        }

        "DF-D02 / G7 — sticky_failover when allowStickyFailover = true and pinned worker is gone" {
            val (svc, sticky) = fixtureWithFailover()
            sticky.recordSticky("s-3", "ghost-worker:9000")
            val req =
                DispatchRequest
                    .newBuilder()
                    .setPlan(scan(storeSales))
                    .setContext(PipelineContext.newBuilder().setSessionId("s-3"))
                    .build()
            val out = svc.dispatch(req).toList()
            // No session_lost error — request succeeded against the surviving worker.
            out.none { it.messagesList.any { m -> m.code == "session_lost" } } shouldBe true
            // The first batch's context carries the failover warning.
            val warnings =
                out
                    .first()
                    .context.warningsList
                    .map { it.code }
            warnings shouldContain "sticky_failover"
            // The stale entry is evicted on failover; the subsequent recordSticky on the
            // fallback worker re-pins the session there for future dispatches.
            sticky.findSticky("s-3") shouldBe "worker-a:9000"
        }

        "ListWorkers includes capability + health view" {
            val (svc, _) = fixture()
            val resp =
                svc.listWorkers(
                    org.tatrman.dispatch.v1.ListWorkersRequest
                        .getDefaultInstance(),
                )
            resp.workersList.size shouldBe 1
            resp.workersList[0].health shouldBe WorkerHealthStatus.HEALTHY
            resp.workersList[0].capabilities.supportedConnectionsList shouldContain "pg-store"
        }

        "GetStatus aggregates correctly" {
            val (svc, _) = fixture()
            val resp =
                svc.getStatus(
                    org.tatrman.dispatch.v1.GetStatusRequest
                        .getDefaultInstance(),
                )
            resp.ready shouldBe true
            resp.knownWorkers shouldBe 1
            resp.healthyWorkers shouldBe 1
            resp.defaultConnection shouldBe "pg-default"
        }

        // ----- Phase 2.4 — workspace-rooted routing -----

        "workspace-rooted plan with empty session_id rejected as workspace_requires_session" {
            val (svc, _) = fixture(stateful = true)
            val req =
                DispatchRequest
                    .newBuilder()
                    .setPlan(workspaceRef("q1"))
                    .setContext(PipelineContext.getDefaultInstance())
                    .build()
            val out = svc.dispatch(req).toList()
            out.size shouldBe 1
            out[0].messagesList[0].code shouldBe "workspace_requires_session"
        }

        "workspace-rooted plan rejected when no stateful worker is available" {
            // Default fixture worker is stateless.
            val (svc, _) = fixture(stateful = false)
            val req =
                DispatchRequest
                    .newBuilder()
                    .setPlan(workspaceRef("q1"))
                    .setContext(PipelineContext.newBuilder().setSessionId("s1"))
                    .build()
            val out = svc.dispatch(req).toList()
            out[0].messagesList[0].code shouldBe "no_stateful_worker_available"
        }

        "workspace-rooted plan with session_id routes to a stateful worker" {
            val (svc, _) = fixture(stateful = true, connections = emptyList())
            val req =
                DispatchRequest
                    .newBuilder()
                    .setPlan(workspaceRef("q1"))
                    .setContext(PipelineContext.newBuilder().setSessionId("s1"))
                    .build()
            val out = svc.dispatch(req).toList()
            out.size shouldBe 1
            out[0].isLast shouldBe true
            out[0].context.warningsList.map { it.code } shouldContain "routing_decision"
        }

        "routing_decision warning includes database + schema when worker advertises ConnectionInfo (#57 Phase C)" {
            val client =
                StubClient(
                    connections = listOf("pg-store"),
                    supportsStateful = false,
                    execute = { defaultStream() },
                    connectionInfos =
                        listOf(
                            ConnectionInfo
                                .newBuilder()
                                .setConnectionId("pg-store")
                                .setDatabase("tatrman")
                                .setDefaultSchema("dbo")
                                .build(),
                        ),
                )
            val registry = WorkerRegistry()
            registry.seed(
                listOf(
                    WorkerEntry(
                        "worker-a:9000",
                        "mssql",
                        client,
                        client.capabilities,
                        WorkerHealthStatus.HEALTHY,
                        null,
                        0,
                    ),
                ),
            )
            val svc = DispatchServiceImpl(registry, StickyRegistry(), world)

            val out =
                svc
                    .dispatch(
                        DispatchRequest
                            .newBuilder()
                            .setPlan(scan(storeSales))
                            .setContext(PipelineContext.getDefaultInstance())
                            .build(),
                    ).toList()
            val warning =
                out[0]
                    .context.warningsList
                    .first { it.code == "routing_decision" }
            warning.message shouldContainStr "database=tatrman"
            warning.message shouldContainStr "schema=dbo"
        }

        "routing_decision warning omits database tag when worker hasn't advertised ConnectionInfo" {
            // Phase C must degrade gracefully against legacy (pre-Phase-B) workers.
            val (svc, _) = fixture() // StubClient default: no connectionInfos
            val out =
                svc
                    .dispatch(
                        DispatchRequest
                            .newBuilder()
                            .setPlan(scan(storeSales))
                            .setContext(PipelineContext.getDefaultInstance())
                            .build(),
                    ).toList()
            val warning =
                out[0]
                    .context.warningsList
                    .first { it.code == "routing_decision" }
            warning.message shouldNotMatch Regex("""\bdatabase=\S+""")
            warning.message shouldNotMatch Regex("""\bschema=\S+""")
        }

        // Issue #57 Phase C — when the worker advertises default_schema, the dispatcher
        // fills in empty plan namespaces before forwarding the ExecuteRequest.
        "dispatcher fills in TableScan namespace from worker's default_schema when plan namespace is empty" {
            val captured = AtomicReference<ExecuteRequest>()
            // Empty plan namespace makes the qname key `db.store_sales`, which matches no
            // world pattern → routing falls back to default-connection = pg-default. Worker
            // advertises that connection so a candidate exists.
            val client =
                StubClient(
                    connections = listOf("pg-default"),
                    supportsStateful = false,
                    execute = { req ->
                        captured.set(req)
                        defaultStream()
                    },
                    connectionInfos =
                        listOf(
                            ConnectionInfo
                                .newBuilder()
                                .setConnectionId("pg-default")
                                .setDatabase("tatrman")
                                .setDefaultSchema("dbo")
                                .build(),
                        ),
                )
            val registry = WorkerRegistry()
            registry.seed(
                listOf(
                    WorkerEntry(
                        "worker-a:9000",
                        "mssql",
                        client,
                        client.capabilities,
                        WorkerHealthStatus.HEALTHY,
                        null,
                        0,
                    ),
                ),
            )
            val svc = DispatchServiceImpl(registry, StickyRegistry(), world)

            val emptyNs = qname("db", "", "store_sales")
            svc
                .dispatch(
                    DispatchRequest
                        .newBuilder()
                        .setPlan(scan(emptyNs))
                        .setContext(PipelineContext.getDefaultInstance())
                        .build(),
                ).toList()

            captured
                .get()
                .plan.tableScan.table.namespace shouldBe "dbo"
            captured
                .get()
                .plan.tableScan.table.name shouldBe "store_sales"
        }

        "dispatcher warns schema_mismatch when plan namespace differs from worker's default_schema" {
            val captured = AtomicReference<ExecuteRequest>()
            val client =
                StubClient(
                    connections = listOf("pg-store"),
                    supportsStateful = false,
                    execute = { req ->
                        captured.set(req)
                        defaultStream()
                    },
                    connectionInfos =
                        listOf(
                            ConnectionInfo
                                .newBuilder()
                                .setConnectionId("pg-store")
                                .setDatabase("tatrman")
                                .setDefaultSchema("fin")
                                .build(),
                        ),
                )
            val registry = WorkerRegistry()
            registry.seed(
                listOf(
                    WorkerEntry(
                        "worker-a:9000",
                        "mssql",
                        client,
                        client.capabilities,
                        WorkerHealthStatus.HEALTHY,
                        null,
                        0,
                    ),
                ),
            )
            val svc = DispatchServiceImpl(registry, StickyRegistry(), world)

            val out =
                svc
                    .dispatch(
                        DispatchRequest
                            .newBuilder()
                            .setPlan(scan(storeSales)) // namespace="dbo"
                            .setContext(PipelineContext.getDefaultInstance())
                            .build(),
                    ).toList()

            captured
                .get()
                .plan.tableScan.table.namespace shouldBe "dbo"
            val codes = out[0].context.warningsList.map { it.code }
            codes shouldContain "schema_mismatch"
        }

        "dispatcher leaves the plan unchanged when worker has not advertised default_schema" {
            val captured = AtomicReference<ExecuteRequest>()
            val client =
                StubClient(
                    connections = listOf("pg-default"),
                    supportsStateful = false,
                    execute = { req ->
                        captured.set(req)
                        defaultStream()
                    },
                ) // no connectionInfos → legacy worker
            val registry = WorkerRegistry()
            registry.seed(
                listOf(
                    WorkerEntry(
                        "worker-a:9000",
                        "mssql",
                        client,
                        client.capabilities,
                        WorkerHealthStatus.HEALTHY,
                        null,
                        0,
                    ),
                ),
            )
            val svc = DispatchServiceImpl(registry, StickyRegistry(), world)

            val emptyNs = qname("db", "", "store_sales")
            val out =
                svc
                    .dispatch(
                        DispatchRequest
                            .newBuilder()
                            .setPlan(scan(emptyNs))
                            .setContext(PipelineContext.getDefaultInstance())
                            .build(),
                    ).toList()

            captured
                .get()
                .plan.tableScan.table.namespace shouldBe "" // untouched
            out[0].context.warningsList.map { it.code } shouldNotContain "schema_mismatch"
        }
    })

private fun qname(
    schema: String,
    ns: String,
    name: String,
): QualifiedName =
    QualifiedName
        .newBuilder()
        .setSchemaCode(
            if (schema ==
                "obj"
            ) {
                org.tatrman.plan.v1.SchemaCode.SCHEMA_CODE_UNSPECIFIED
            } else {
                org.tatrman.plan.v1.SchemaCode
                    .valueOf(schema.uppercase())
            },
        ).setNamespace(ns)
        .setName(name)
        .build()

private fun scan(table: QualifiedName): PlanNode =
    PlanNode
        .newBuilder()
        .setTableScan(TableScanNode.newBuilder().setTable(table))
        .build()

private fun workspaceRef(name: String): PlanNode =
    PlanNode
        .newBuilder()
        .setWorkspaceRef(
            org.tatrman.plan.v1.WorkspaceRef
                .newBuilder()
                .setWorkspaceName(name),
        ).build()

private fun defaultStream(): Flow<ResultBatch> =
    flow {
        emit(
            ResultBatch
                .newBuilder()
                .setIsFirst(true)
                .setIsLast(true)
                .setArrowIpc(ByteArray(0).toByteString())
                .setContext(PipelineContext.getDefaultInstance())
                .build(),
        )
    }

private class StubClient(
    connections: List<String>,
    supportsStateful: Boolean,
    connectionInfos: List<ConnectionInfo> = emptyList(),
    private val execute: (ExecuteRequest) -> Flow<ResultBatch>,
) : WorkerClient {
    val capabilities: GetCapabilitiesResponse =
        GetCapabilitiesResponse
            .newBuilder()
            .setEngineName("mssql")
            .addAllSupportedConnections(connections)
            .addAllConnections(connectionInfos)
            .setSupportsStatefulSessions(supportsStateful)
            .build()

    override suspend fun getCapabilities(): GetCapabilitiesResponse = capabilities

    override fun execute(request: ExecuteRequest): Flow<ResultBatch> = execute.invoke(request)

    override fun close() = Unit
}
