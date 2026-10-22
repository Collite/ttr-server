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
                    QueryRun { request ->
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
                    QueryRun { request ->
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
            val door = QueryDoor(QueryRun { error("must not use default connection") }, emptyMap(), NO_TOKENS)
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
                    QueryRun {
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
                QueryRun {
                    flow { throw Status.PERMISSION_DENIED.asException() }
                },
                QueryRun { flowOf(ResultBatch.getDefaultInstance()) },
            )) {
                shouldThrow<AutomationException> { QueryDoor(query, ROUTES, NO_TOKENS).read(read(c)) }.code shouldBe
                    ErrorCode.DOOR_REFUSED
            }
        }
        "empty typed results succeed, while unbound placeholders and excess rows fail" {
            val c = runnerContext(realRecipe("release_order"))
            val columns = listOf(ManifestColumn("id", "int"))
            QueryDoor(
                QueryRun { flowOf(batch(Table(columns, emptyList()))) },
                ROUTES,
                NO_TOKENS,
            ).read(read(c)).table.rows shouldBe
                emptyList()
            shouldThrow<AutomationException> {
                QueryDoor(
                    QueryRun { error("never called") },
                    ROUTES,
                    NO_TOKENS,
                ).read(read(c).copy(sql = "SELECT :missing"))
            }
            shouldThrow<AutomationException> {
                QueryDoor(
                    QueryRun {
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
