// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.query

import com.google.protobuf.ByteString
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.automation.LedgerTestDb
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.api.installAutomationErrors
import org.tatrman.automation.api.queryRoutes
import org.tatrman.automation.bundle.Bundle
import org.tatrman.automation.bundle.BundleKind
import org.tatrman.automation.bundle.BundleLoader
import org.tatrman.automation.bundle.Checkout
import org.tatrman.automation.bundle.ManifestColumn
import org.tatrman.automation.identity.AUTOMATION_AUTH
import org.tatrman.automation.identity.AuthorMap
import org.tatrman.automation.identity.AutomationBearerProvider
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.identity.CallerVerifier
import org.tatrman.automation.identity.ServiceTokens
import org.tatrman.automation.identity.TestRealm
import org.tatrman.automation.identity.TokenKind
import org.tatrman.automation.ledger.Ledger
import org.tatrman.automation.ledger.RunStatus
import org.tatrman.automation.runner.ArrowTables
import org.tatrman.automation.runner.QueryDoor
import org.tatrman.automation.runner.QueryRun
import org.tatrman.automation.runner.Table
import org.tatrman.mcp.identity.HttpJwksKeySource
import org.tatrman.mcp.identity.JwksBearerVerifier
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.query.v1.CompileResponse
import org.tatrman.query.v1.RunRequest
import org.tatrman.ttr.metadata.model.QueryParameterDef
import org.tatrman.worker.v1.ResultBatch
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Duration
import java.util.Collections
import java.util.UUID

/** S3.1 T1: `POST /automation/query` + `variables`, the queries and runs lists, the templates route — real ledger. */
class QueryStepComponentSpec :
    StringSpec({
        LedgerTestDb.ready()
        beforeEach { LedgerTestDb.reset() }

        val loaded =
            BundleLoader(AuthorMap(emptyMap())).load(
                Checkout(
                    Path.of("src/test/resources/bundles/hartland"),
                    "master",
                    "0123456789abcdef",
                    "po",
                    "po@example.org",
                ),
                BundleKind.SERVED,
            )
        val revenue =
            loaded.model.queries.values
                .single { it.qname.name == "revenue_per_customer" }
        // A parameterised `def query` beside the fixture's own (the fixture world declares none with params).
        val ordersOf =
            revenue.copy(
                internalId = "orders_of_customer",
                qname = revenue.qname.copy(name = "orders_of_customer"),
                sourceText =
                    "SELECT order.order_number, order.amount FROM order " +
                        "WHERE order.customer = {customer} AND order.amount > {min}",
                parameters =
                    listOf(
                        QueryParameterDef("customer", "text"),
                        QueryParameterDef("min", "decimal", defaultValue = "0"),
                    ),
            )
        val bundle: Bundle =
            loaded.copy(model = loaded.model.copy(queries = loaded.model.queries + (ordersOf.qname to ordersOf)))
        val caller = Caller("sub-maya", "maya", setOf("hartland-sales-managers"), TokenKind.PERSON, "maya-bearer")

        class Fixture(
            var answer: (RunRequest) -> Table = {
                Table(
                    listOf(ManifestColumn("order_number", "int"), ManifestColumn("amount", "decimal")),
                    listOf(listOf(4711L, BigDecimal("10.50")), listOf(4712L, BigDecimal("2.25"))),
                )
            },
            var refuse: (RunRequest) -> ResponseMessage? = { null },
            var golemAnswer: GolemAnswer = GolemAnswer(QueryVerdict.ANSWERED),
            val served: Bundle = bundle,
        ) {
            /** review-273 F7: the sweep abandons the run while the Golem is still answering. */
            var abandonDuringAsk = false
            val requests: MutableList<RunRequest> = Collections.synchronizedList(mutableListOf())
            val asked: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
            val ledger = Ledger(Duration.ofMinutes(30))
            private val query =
                object : QueryRun {
                    override suspend fun compile(request: RunRequest): CompileResponse =
                        CompileResponse
                            .newBuilder()
                            .apply { refuse(request)?.let { addMessages(it) } }
                            .build()

                    override fun run(request: RunRequest): Flow<ResultBatch> =
                        flow {
                            requests += request
                            val table = answer(request)
                            emit(
                                ResultBatch
                                    .newBuilder()
                                    .setBatchRowCount(table.rows.size.toLong())
                                    .setArrowIpc(ByteString.copyFrom(ArrowTables.encode(table)))
                                    .build(),
                            )
                        }
                }
            val golem =
                Golem { bearer, agent, question, originRef, _ ->
                    asked += listOf(bearer, agent, question, originRef)
                    if (abandonDuringAsk) ledger.markStatus(UUID.fromString(originRef), RunStatus.ABANDONED)
                    golemAnswer
                }
            val step =
                QueryStep(
                    { served },
                    QueryDoor(
                        query,
                        mapOf(
                            "demo/erp_db" to "us-erp",
                            "demo/automation_db" to "us-automation",
                        ),
                        ServiceTokens(emptyMap(), {
                            error("unused")
                        }),
                    ),
                    golem,
                    ledger,
                    ServiceTokens(emptyMap(), { error("unused") }),
                    setOf("demo/erp_db", "demo/automation_db"),
                    { false },
                    QueryLimits(defaultLimit = 1000, maxLimit = 10_000, maxTableRows = 500),
                )

            suspend fun run(json: String) = step.run(Json.decodeFromString(QueryRequest.serializer(), json), caller)
        }

        fun code(block: suspend () -> Unit) =
            shouldThrow<AutomationException> {
                kotlinx.coroutines.runBlocking { block() }
            }.code

        "a named def query with params answers one frame and its variables beside it, with §1's types" {
            val f = Fixture()
            val out =
                f.run(
                    """{"query":"orders_of_customer","parameters":{"customer":"C-1"},
                       "variables":{"n":"row_count","any":"exists","total":{"kind":"aggregate","column":"amount","agg":"sum"},
                                    "all":"table","v":"verdict"}}""",
                )
            out.status shouldBe 200
            val r = out.response
            r.rowCount shouldBe 2
            r.truncated shouldBe false
            r.verdict shouldBe "answered"
            r.rows.first().toString() shouldBe """{"order_number":4711,"amount":10.5}"""
            r.variables.toString() shouldBe
                """{"n":2,"any":true,"total":12.75,"all":[{"order_number":4711,"amount":10.5},""" +
                """{"order_number":4712,"amount":2.25}],"v":"answered"}"""
            // The def query's body went verbatim, its params bound by name (the declared default fills `min`).
            val sent = f.requests.single()
            sent.source shouldBe ordersOf.sourceText
            sent.world shouldBe "us-erp"
            sent.context.parametersList.associate { it.name to it.type } shouldBe
                mapOf("customer" to "text", "min" to "float")
            // The run is a closed `query` run with a sealed one-shot protocol record.
            val run = UUID.fromString(r.run.id)
            f.ledger.findRun(run)!!.status shouldBe RunStatus.CLOSED
            val islands =
                f.ledger
                    .readProtocol(run, caller.sub)
                    .sections
                    .getValue("islands")
                    .single()
                    .payload.jsonObject
            islands["statement"]!!.jsonPrimitive.content shouldBe ordersOf.sourceText
            islands["query"]!!.jsonPrimitive.content shouldBe ordersOf.qname.dotted()
            islands["rowCount"].toString() shouldBe "2"
            islands["variables"]!!.jsonObject["total"].toString() shouldBe "\"12.75\"" // exact decimal string
            f.ledger
                .readProtocol(run, caller.sub)
                .sections
                .getValue("automation")
                .last()
                .payload
                .toString() shouldBe """{"sealed":true}"""
        }

        "row over more than one row is 422 row-not-single; table over max_table_rows is 422 variable-too-large" {
            val f = Fixture()
            code {
                f.run("""{"query":"orders_of_customer","parameters":{"customer":"C-1"},"variables":{"first":"row"}}""")
            } shouldBe
                ErrorCode.ROW_NOT_SINGLE
            f.answer = { Table(listOf(ManifestColumn("x", "int")), (1..501).map { listOf(it.toLong()) }) }
            code {
                f.run(
                    """{"statement":"SELECT customer.sk AS x FROM customer","variables":{"all":"table"}}""",
                )
            } shouldBe
                ErrorCode.VARIABLE_TOO_LARGE
            // Both failures are failed query runs, their reason recorded; a failed run frees nothing else.
            f.ledger.listRuns(caller.sub, status = RunStatus.FAILED).map { it.error } shouldBe
                listOf("variable-too-large", "row-not-single")
        }

        "an inline statement goes through validate under the caller's own identity; a refusal is 400 statement" {
            val f =
                Fixture(refuse = { r ->
                    if ("line_id" in r.source) {
                        ResponseMessage
                            .newBuilder()
                            .setSeverity(Severity.ERROR)
                            .setCode("validation_failed")
                            .setHumanMessage("unknown attribute line_id at 1:8")
                            .build()
                    } else {
                        null
                    }
                })
            val e =
                shouldThrow<AutomationException> {
                    f.run("""{"statement":"SELECT line_id FROM obligation"}""")
                }
            e.code shouldBe ErrorCode.STATEMENT
            e.message shouldBe "unknown attribute line_id at 1:8"
            f.requests.shouldBeEmpty() // refused at validate: nothing ran
            val out =
                f.run(
                    """{"statement":"SELECT o.order_number FROM order o WHERE o.customer = {c}","parameters":{"c":"C-1"},"limit":1}""",
                )
            out.response.truncated shouldBe true // two rows for limit 1
            out.response.rowCount shouldBe 1
            val sent = f.requests.single()
            sent.context.userId shouldBe "maya" // the caller, not a service identity (no deployment named)
            // The caller's roles → the caller's RLS.
            sent.context.authRolesList shouldBe listOf("hartland-sales-managers")
            sent.hasAllowedErReads() shouldBe false // ad-hoc reads are bounded by RLS only
            sent.rowWindow.limit shouldBe 2L // one past the limit tells a cut answer
            code { f.run("""{"statement":"SELECT 1","query":"orders_of_customer"}""") } shouldBe ErrorCode.STATEMENT
            code { f.run("""{"query":"nope"}""") } shouldBe ErrorCode.QUERY
            code { f.run("""{"query":"orders_of_customer"}""") } shouldBe ErrorCode.STATEMENT // customer is required
            code { f.run("""{"query":"orders_of_customer","parameters":{"customer":"C","x":1}}""") } shouldBe
                ErrorCode.STATEMENT
            code { f.run("""{"statement":"SELECT 1","asOf":"2026-11-02T05:00:00Z"}""") } shouldBe ErrorCode.AS_OF
        }

        "a Golem question runs the headless turn as the caller; TABLE → frame, refusal/clarify → verdict, others null" {
            val f =
                Fixture(
                    golemAnswer =
                        GolemAnswer(
                            QueryVerdict.ANSWERED,
                            Table(listOf(ManifestColumn("revenue", "float")), listOf(listOf(120.5))),
                            turnId = "turn-1",
                        ),
                )
            val spec = """"variables":{"r":{"kind":"scalar","column":"revenue"},"v":"verdict"}"""
            val out =
                f.run(
                    """{"golem":{"agent":"sales","question":"revenue of {customer}?"},"parameters":{"customer":"C-1"},$spec}""",
                )
            out.response.variables.toString() shouldBe """{"r":120.5,"v":"answered"}"""
            val (bearer, agent, question, originRef) = f.asked.single()
            bearer shouldBe "maya-bearer" // ✅AU-P3-2: the caller's bearer, no service identity
            agent shouldBe "sales"
            question shouldBe "revenue of C-1?"
            originRef shouldBe out.response.run.id
            f.requests.shouldBeEmpty()
            val turns =
                f.ledger
                    .readProtocol(UUID.fromString(out.response.run.id), caller.sub)
                    .sections
                    .getValue("turns")
                    .single()
                    .payload.jsonObject
            turns["turnId"]!!.jsonPrimitive.content shouldBe "turn-1"
            turns["origin"]!!.jsonPrimitive.content shouldBe "automation"
            for (verdict in listOf(QueryVerdict.REFUSED, QueryVerdict.CLARIFY)) {
                f.golemAnswer = GolemAnswer(verdict, code = "REFUSE")
                val other = f.run("""{"golem":{"agent":"sales","question":"why?"},$spec}""")
                other.response.verdict shouldBe verdict.wire
                other.response.variables.toString() shouldBe """{"r":null,"v":"${verdict.wire}"}"""
                other.response.rows.shouldBeEmpty()
            }
        }

        "a caller's idempotency key replays the closed run's answer without re-reading" {
            val f = Fixture()
            val body =
                """{"query":"orders_of_customer","parameters":{"customer":"C-1"},""" +
                    """"variables":{"n":"row_count"},"idempotencyKey":"engine:42"}"""
            val first = f.run(body)
            val again = f.run(body)
            again.status shouldBe 200
            again.response.replayed shouldBe true
            again.response.run shouldBe first.response.run
            again.response.variables shouldBe first.response.variables
            again.response.rowCount shouldBe 2
            f.requests.size shouldBe 1
        }

        "review-273 F6: one caller key under two deployments is two runs, never the other deployment's answer" {
            val names =
                bundle.deployments.keys
                    .filter {
                        bundle
                            .worldOf(
                                bundle.deployments.getValue(it),
                            )?.name == "demo"
                    }.take(2)
            names.size shouldBe 2
            val asCaller =
                bundle.copy(
                    deployments =
                        bundle.deployments +
                            names.associateWith {
                                bundle.deployments
                                    .getValue(
                                        it,
                                    ).copy(runAs = org.tatrman.ttr.metadata.model.RunAsRef.Caller)
                            },
                )
            val f = Fixture(served = asCaller)
            val body = { d: String -> """{"statement":"SELECT 1 AS x","deployment":"$d","idempotencyKey":"daily"}""" }
            val first = f.run(body(names[0]))
            val second = f.run(body(names[1]))
            second.response.replayed shouldBe false
            (second.response.run.id == first.response.run.id) shouldBe false
            f.run(body(names[0])).response.let {
                it.replayed shouldBe true
                it.run shouldBe first.response.run
            }
        }

        "review-273 F7: a query whose run the sweep abandoned meanwhile answers budget, not 500" {
            val f =
                Fixture(
                    golemAnswer =
                        GolemAnswer(
                            QueryVerdict.ANSWERED,
                            Table(listOf(ManifestColumn("x", "int")), listOf(listOf(1L))),
                        ),
                )
            f.abandonDuringAsk = true
            code { f.run("""{"golem":{"agent":"sales","question":"q"}}""") } shouldBe ErrorCode.BUDGET
        }

        "review-273 F1: the runs list pages at its documented maximum (500); 501 is 400" {
            val realm = TestRealm()
            val f = Fixture()
            try {
                val maya = realm.person("maya", listOf("hartland-sales-managers"))
                repeat(501) {
                    f.ledger.claimRun(
                        org.tatrman.automation.ledger
                            .RunOpen("statement", callerSubject = "sub-maya", kind = "query"),
                    )
                }
                testApplication {
                    application {
                        install(ContentNegotiation) { json() }
                        install(StatusPages) { installAutomationErrors() }
                        val verifier =
                            CallerVerifier(
                                JwksBearerVerifier(
                                    HttpJwksKeySource(realm.jwksUrl),
                                    TestRealm.ISSUER,
                                    TestRealm.AUDIENCE,
                                ),
                            )
                        install(Authentication) { AutomationBearerProvider.register(this, verifier) }
                        routing { authenticate(AUTOMATION_AUTH) { queryRoutes(f.step, { bundle }, f.ledger) } }
                    }
                    val page = client.get("/automation/runs?status=any&limit=500") { bearerAuth(maya) }
                    page.status shouldBe HttpStatusCode.OK
                    val body = Json.parseToJsonElement(page.bodyAsText()).jsonObject
                    body.getValue("runs").jsonArray.size shouldBe 500
                    (body["next"].toString() == "null") shouldBe false
                    client.get("/automation/runs?status=any&limit=501") { bearerAuth(maya) }.status shouldBe
                        HttpStatusCode.BadRequest
                }
            } finally {
                realm.close()
            }
        }

        "GET /automation/queries, /automation/runs and /automation/templates/{target} over HTTP" {
            val realm = TestRealm()
            val f = Fixture()
            try {
                testApplication {
                    application {
                        install(ContentNegotiation) { json() }
                        install(StatusPages) { installAutomationErrors() }
                        val verifier =
                            CallerVerifier(
                                JwksBearerVerifier(
                                    HttpJwksKeySource(realm.jwksUrl),
                                    TestRealm.ISSUER,
                                    TestRealm.AUDIENCE,
                                ),
                            )
                        install(Authentication) { AutomationBearerProvider.register(this, verifier) }
                        routing { authenticate(AUTOMATION_AUTH) { queryRoutes(f.step, { bundle }, f.ledger) } }
                    }
                    val maya = realm.person("maya", listOf("hartland-sales-managers"))
                    val other = realm.person("sam", listOf("hartland-sales-managers"))
                    val queries =
                        Json
                            .parseToJsonElement(
                                client.get("/automation/queries") { bearerAuth(maya) }.bodyAsText(),
                            ).jsonObject
                    val named = queries.getValue("queries").jsonArray.map { it.jsonObject }
                    named.map { it["name"]!!.jsonPrimitive.content } shouldBe
                        listOf("orders_of_customer", "revenue_per_customer")
                    named.first()["params"].toString() shouldBe
                        """[{"name":"customer","type":"text","default":null},{"name":"min","type":"decimal","default":"0"}]"""

                    val ids =
                        (1..3).map {
                            val res =
                                client.post("/automation/query") {
                                    bearerAuth(maya)
                                    contentType(ContentType.Application.Json)
                                    setBody("""{"statement":"SELECT 1 AS x","origin":"n8n-query"}""")
                                }
                            res.status shouldBe HttpStatusCode.OK
                            Json
                                .parseToJsonElement(
                                    res.bodyAsText(),
                                ).jsonObject["run"]!!
                                .jsonObject["id"]!!
                                .jsonPrimitive.content
                        }
                    val page1 =
                        Json
                            .parseToJsonElement(
                                client
                                    .get("/automation/runs?limit=2&kind=query") {
                                        bearerAuth(maya)
                                    }.bodyAsText(),
                            ).jsonObject
                    val runs1 = page1.getValue("runs").jsonArray.map { it.jsonObject }
                    runs1.map { it["runId"]!!.jsonPrimitive.content } shouldBe ids.reversed().take(2) // newest first
                    runs1.first()["kind"]!!.jsonPrimitive.content shouldBe "query"
                    runs1.first()["origin"]!!.jsonPrimitive.content shouldBe "n8n-query"
                    val cursor = page1["next"]!!.jsonPrimitive.content
                    val page2 =
                        Json
                            .parseToJsonElement(
                                client
                                    .get("/automation/runs?limit=2&cursor=$cursor") {
                                        bearerAuth(maya)
                                    }.bodyAsText(),
                            ).jsonObject
                    page2.getValue("runs").jsonArray.map { it.jsonObject["runId"]!!.jsonPrimitive.content } shouldBe
                        listOf(ids.first())
                    page2["next"].toString() shouldBe "null"
                    // Another caller sees none of maya's runs; a malformed filter is 400.
                    (
                        Json
                            .parseToJsonElement(
                                client
                                    .get("/automation/runs") {
                                        bearerAuth(other)
                                    }.bodyAsText(),
                            ).jsonObject["runs"] as JsonArray
                    ).size shouldBe
                        0
                    client.get("/automation/runs?status=sleeping") { bearerAuth(maya) }.status shouldBe
                        HttpStatusCode.BadRequest
                    client.get("/automation/runs?since=yesterday") { bearerAuth(maya) }.status shouldBe
                        HttpStatusCode.BadRequest

                    // T5 as re-cut (⚑AU-S31-1): every known target answers 404 templates-not-served until S3.1b.
                    val c7 = client.get("/automation/templates/camunda-7") { bearerAuth(maya) }
                    c7.status shouldBe HttpStatusCode.NotFound
                    c7.bodyAsText() shouldContain "\"reason\":\"templates-not-served\""
                    val unknown = client.get("/automation/templates/bpmn-9") { bearerAuth(maya) }
                    unknown.status shouldBe HttpStatusCode.NotFound
                    unknown.bodyAsText() shouldContain "\"reason\":\"unknown-target\""
                    client.get("/automation/templates/camunda-7").status shouldBe HttpStatusCode.Unauthorized
                }
            } finally {
                realm.close()
            }
        }
    })
