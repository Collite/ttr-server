// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import com.google.protobuf.ByteString
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.grpc.Status
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.ManifestColumn
import org.tatrman.automation.identity.ServiceTokens
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.Warning
import org.tatrman.worker.v1.ResultBatch
import java.math.BigDecimal

private fun scopedQuery(run: (org.tatrman.query.v1.RunRequest) -> kotlinx.coroutines.flow.Flow<ResultBatch>): QueryRun =
    object : QueryRun {
        override fun run(request: org.tatrman.query.v1.RunRequest) = run.invoke(request)

        override suspend fun compile(request: org.tatrman.query.v1.RunRequest) =
            org.tatrman.query.v1.CompileResponse
                .newBuilder()
                .setPlan(
                    org.tatrman.plan.v1.PlanNode.newBuilder().setValues(
                        org.tatrman.plan.v1.ValuesNode
                            .getDefaultInstance(),
                    ),
                ).setErReads(
                    org.tatrman.query.v1.ErReadScope
                        .getDefaultInstance(),
                ).build()
    }

class DoorSpec :
    StringSpec({
        "door request pins ER SQL, run identity, roles, model, storage alias, typed bindings and ceiling" {
            val context = runnerContext(realRecipe("release_order"))
            val read =
                DoorRead(
                    context.id,
                    "shortfall",
                    "demo",
                    "erp_db",
                    "SELECT :tolerance, :as_of, :order_number",
                    mapOf(
                        "tolerance" to BoundValue("decimal", BigDecimal("0.02")),
                        "as_of" to BoundValue("datetime", java.time.Instant.parse("2026-11-02T08:00:00Z")),
                        "order_number" to BoundValue("int", 12L),
                    ),
                    context.identity,
                    context.modelFp,
                    500,
                    listOf(ManifestColumn("id", "int")),
                    context.timeout,
                )
            val door =
                QueryDoor(
                    scopedQuery { request ->
                        request.source shouldBe "SELECT {tolerance}, {as_of}, {order_number}"
                        request.world shouldBe "us_erp"
                        request.sourceSchema.name shouldBe "ER"
                        request.sourceLanguage.name shouldBe "SQL"
                        request.context.userId shouldBe "operator"
                        request.context.authRolesList shouldBe listOf("ops")
                        request.context.modelVersion shouldBe "pinned-model"
                        request.context.correlationId shouldBe "${context.id}/shortfall"
                        request.rowWindow.limit shouldBe 500L
                        request.context.parametersList.associate { it.name to it.type } shouldBe
                            mapOf("tolerance" to "float", "as_of" to "datetime", "order_number" to "int")
                        flowOf(
                            batch(Table(read.columns, listOf(listOf(12L))))
                                .toBuilder()
                                .setContext(
                                    request.context.toBuilder().addWarnings(
                                        Warning.newBuilder().setCode("security_predicate_applied"),
                                    ),
                                ).build(),
                        )
                    },
                    mapOf("demo/erp_db" to "us_erp"),
                    NO_TOKENS,
                )
            val result = door.read(read)
            result.table.rows shouldBe listOf(listOf(12L))
            result.warnings shouldBe setOf("security_predicate_applied")
        }
        "warnings and late cap notices accumulate across the entire Query stream" {
            val c = runnerContext(realRecipe("release_order"))
            val door =
                QueryDoor(
                    scopedQuery {
                        flowOf(
                            batch(Table(listOf(ManifestColumn("id", "int")), listOf(listOf(1L))))
                                .toBuilder()
                                .setContext(
                                    org.tatrman.plan.v1.PipelineContext
                                        .newBuilder()
                                        .addWarnings(Warning.newBuilder().setCode("security_predicate_applied")),
                                ).build(),
                            ResultBatch
                                .newBuilder()
                                .addNotices(
                                    org.tatrman.worker.v1.Notice
                                        .newBuilder()
                                        .setCode("top_n_applied")
                                        .setRequested(500)
                                        .setApplied(1),
                                ).build(),
                        )
                    },
                    ROUTES,
                    NO_TOKENS,
                )
            val result = door.read(read(c))
            result.warnings shouldBe setOf("security_predicate_applied", "top_n_applied")
            result.table.rows shouldBe listOf(listOf(1L))
        }

        "scope preflight pins the identical request and refuses undeclared reads before Run" {
            val c = runnerContext(realRecipe("release_order"))
            val r = read(c).copy(objectsRead = setOf("er.entity.order"))
            var preflight: org.tatrman.query.v1.RunRequest? = null
            var runs = 0

            fun query(objects: List<String>) =
                object : QueryRun {
                    override suspend fun compile(
                        request: org.tatrman.query.v1.RunRequest,
                    ): org.tatrman.query.v1.CompileResponse {
                        preflight = request
                        request.allowedErReads.objectsList shouldBe listOf("er.entity.order")
                        return org.tatrman.query.v1.CompileResponse
                            .newBuilder()
                            .setPlan(
                                org.tatrman.plan.v1.PlanNode.newBuilder().setValues(
                                    org.tatrman.plan.v1.ValuesNode
                                        .getDefaultInstance(),
                                ),
                            ).setErReads(
                                org.tatrman.query.v1.ErReadScope
                                    .newBuilder()
                                    .addAllObjects(objects),
                            ).build()
                    }

                    override fun run(
                        request: org.tatrman.query.v1.RunRequest,
                    ): kotlinx.coroutines.flow.Flow<ResultBatch> {
                        request shouldBe preflight
                        runs++
                        return flowOf(batch(Table(r.columns, emptyList())))
                    }
                }
            QueryDoor(query(listOf("er.entity.order")), ROUTES, NO_TOKENS).read(r)
            runs shouldBe 1
            val e =
                shouldThrow<AutomationException> {
                    QueryDoor(query(listOf("er.entity.order", "er.entity.secret")), ROUTES, NO_TOKENS).read(r)
                }
            e.code shouldBe ErrorCode.OUT_OF_SCOPE_READ
            e.detail.toString() shouldBe "{\"island\":\"i\"}"
            runs shouldBe 1
        }
        "absent ER evidence, compile errors and transport failures never invoke Run" {
            val c = runnerContext(realRecipe("release_order"))
            for (mode in listOf("old-query", "no-plan", "error", "transport", "scope")) {
                var runs = 0
                val query =
                    object : QueryRun {
                        override fun run(
                            request: org.tatrman.query.v1.RunRequest,
                        ): kotlinx.coroutines.flow.Flow<ResultBatch> = error("must not execute: ${++runs}")

                        override suspend fun compile(
                            request: org.tatrman.query.v1.RunRequest,
                        ): org.tatrman.query.v1.CompileResponse {
                            if (mode == "transport") throw Status.UNAVAILABLE.asException()
                            return org.tatrman.query.v1.CompileResponse
                                .newBuilder()
                                .apply {
                                    if (mode !=
                                        "no-plan"
                                    ) {
                                        setPlan(
                                            org.tatrman.plan.v1.PlanNode.newBuilder().setValues(
                                                org.tatrman.plan.v1.ValuesNode
                                                    .getDefaultInstance(),
                                            ),
                                        )
                                    }
                                    if (mode != "old-query") {
                                        setErReads(
                                            org.tatrman.query.v1.ErReadScope
                                                .getDefaultInstance(),
                                        )
                                    }
                                    if (mode == "error" || mode == "scope") {
                                        addMessages(
                                            ResponseMessage
                                                .newBuilder()
                                                .setCode(
                                                    if (mode ==
                                                        "scope"
                                                    ) {
                                                        "out-of-scope-read"
                                                    } else {
                                                        "denied"
                                                    },
                                                ).setSeverity(Severity.ERROR),
                                        )
                                    }
                                }.build()
                        }
                    }
                shouldThrow<AutomationException> { QueryDoor(query, ROUTES, NO_TOKENS).read(read(c)) }.code shouldBe
                    if (mode == "scope") ErrorCode.OUT_OF_SCOPE_READ else ErrorCode.DOOR_REFUSED
                runs shouldBe 0
            }
        }
        "Run's scope recheck refusal discards already streamed data" {
            val c = runnerContext(realRecipe("release_order"))
            val door =
                QueryDoor(
                    scopedQuery {
                        flowOf(
                            batch(Table(listOf(ManifestColumn("id", "int")), listOf(listOf(1L)))),
                            ResultBatch
                                .newBuilder()
                                .addMessages(
                                    ResponseMessage
                                        .newBuilder()
                                        .setSeverity(Severity.ERROR)
                                        .setCode("out-of-scope-read"),
                                ).build(),
                        )
                    },
                    ROUTES,
                    NO_TOKENS,
                )
            shouldThrow<AutomationException> { door.read(read(c)) }.code shouldBe ErrorCode.OUT_OF_SCOPE_READ
        }

        "a named identity uses its own IdP roles rather than the caller roles" {
            val c = runnerContext(realRecipe("release_order"))
            val token =
                com.auth0.jwt.JWT
                    .create()
                    .withSubject("sa")
                    .withClaim("client_id", "svc")
                    .withClaim("realm_access", mapOf("roles" to listOf("decider")))
                    .sign(
                        com.auth0.jwt.algorithms.Algorithm
                            .none(),
                    )
            val tokens = ServiceTokens(mapOf("DECIDER" to "client:secret"), { token })
            val identity = c.identity.copy(principal = "svc", credential = "DECIDER")
            val door =
                QueryDoor(
                    scopedQuery { request ->
                        request.context.userId shouldBe "svc"
                        request.context.authRolesList shouldBe listOf("decider")
                        flowOf(batch(Table(listOf(ManifestColumn("id", "int")), emptyList())))
                    },
                    ROUTES,
                    tokens,
                )
            door.read(read(c).copy(identity = identity)).table.rows shouldBe emptyList()
        }

        "a missing storage route fails closed before invoking Query" {
            val c = runnerContext(realRecipe("release_order"))
            val door = QueryDoor(scopedQuery { error("must not use default connection") }, emptyMap(), NO_TOKENS)
            shouldThrow<AutomationException> { door.read(read(c)) }.code shouldBe ErrorCode.DOOR_REFUSED
        }
        "an error batch after data discards the entire result" {
            val c = runnerContext(realRecipe("release_order"))
            val failure =
                ResultBatch
                    .newBuilder()
                    .addMessages(
                        ResponseMessage
                            .newBuilder()
                            .setCode(
                                "validator_refused",
                            ).setSeverity(Severity.ERROR)
                            .setHumanMessage("refused"),
                    ).build()
            val door =
                QueryDoor(
                    scopedQuery {
                        flowOf(batch(Table(listOf(ManifestColumn("id", "int")), listOf(listOf(1L)))), failure)
                    },
                    ROUTES,
                    NO_TOKENS,
                )
            val e = shouldThrow<AutomationException> { door.read(read(c)) }
            e.code shouldBe ErrorCode.DOOR_REFUSED
            e.detail.toString().contains("validator_refused") shouldBe true
        }
        "transport failure and missing Arrow schema never become empty successes" {
            val c = runnerContext(realRecipe("release_order"))
            for (query in listOf(
                scopedQuery {
                    flow { throw Status.PERMISSION_DENIED.asException() }
                },
                scopedQuery { flowOf(ResultBatch.getDefaultInstance()) },
            )) {
                shouldThrow<AutomationException> { QueryDoor(query, ROUTES, NO_TOKENS).read(read(c)) }.code shouldBe
                    ErrorCode.DOOR_REFUSED
            }
        }
        "empty typed results succeed, while unbound placeholders and excess rows fail" {
            val c = runnerContext(realRecipe("release_order"))
            val columns = listOf(ManifestColumn("id", "int"))
            QueryDoor(
                scopedQuery { flowOf(batch(Table(columns, emptyList()))) },
                ROUTES,
                NO_TOKENS,
            ).read(read(c)).table.rows shouldBe
                emptyList()
            shouldThrow<AutomationException> {
                QueryDoor(
                    scopedQuery { error("never called") },
                    ROUTES,
                    NO_TOKENS,
                ).read(read(c).copy(sql = "SELECT :missing"))
            }
            shouldThrow<AutomationException> {
                QueryDoor(
                    scopedQuery {
                        flowOf(batch(Table(columns, listOf(listOf(1L), listOf(2L)))))
                    },
                    ROUTES,
                    NO_TOKENS,
                ).read(read(c).copy(readCeiling = 1))
            }
        }
    })

private val ROUTES = mapOf("demo/erp_db" to "us_erp")
private val NO_TOKENS = ServiceTokens(emptyMap(), { error("no exchange") })

private fun batch(table: Table) =
    ResultBatch
        .newBuilder()
        .setArrowIpc(
            ByteString.copyFrom(ArrowTables.encode(table)),
        ).setBatchRowCount(table.rows.size.toLong())
        .build()

private fun read(c: RunContext) =
    DoorRead(
        c.id,
        "i",
        "demo",
        "erp_db",
        "SELECT 1",
        emptyMap(),
        c.identity,
        c.modelFp,
        500,
        listOf(ManifestColumn("id", "int")),
        c.timeout,
    )
