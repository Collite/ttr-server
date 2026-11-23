// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import org.tatrman.automation.bundle.ApprovalFixture
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.automation.bundle.ApprovalReader
import org.tatrman.automation.bundle.BundleFixture
import org.tatrman.automation.bundle.BundleSource
import org.tatrman.automation.bundle.Bundles
import org.tatrman.automation.engine.DeploymentDefinitions
import org.tatrman.automation.bundle.BundleFixture.edit
import org.tatrman.automation.identity.AUTOMATION_AUTH
import org.tatrman.automation.identity.AutomationBearerProvider
import org.tatrman.automation.identity.CallerVerifier
import org.tatrman.automation.identity.TestRealm
import org.tatrman.automation.identity.caller
import org.tatrman.mcp.identity.HttpJwksKeySource
import org.tatrman.mcp.identity.JwksBearerVerifier
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * AU-server S0.2 T5 — `GET /automation/` over the hartland fixture equals the golden (contracts §1.1; the shape
 * the Studio reads). Nothing is approved yet, so every deployment is stale; `granted` is per caller. The golden is
 * `src/test/resources/descriptor/hartland.json`; `UPDATE_GOLDEN=1` rewrites it (review the diff).
 */
class DescriptorSpec :
    StringSpec({
        val json =
            Json {
                prettyPrint = true
                encodeDefaults = true // as Ktor's json() writes the wire
            }
        val golden: Path = Path.of("src/test/resources/descriptor/hartland.json")

        fun served(
            root: Path = BundleFixture.root,
            deploymentDefinitions: DeploymentDefinitions? = null,
            approvals: ApprovalReader = ApprovalReader { _, _, _ -> false },
        ): DescriptorSources {
            val bundles = BundleFixture.bundles(BundleSource { BundleFixture.checkout(root, branch = it) })
            runBlocking { bundles.refresh() }
            return BundleFixture.sources(bundles, approvals, deploymentDefinitions = deploymentDefinitions)
        }

        "the descriptor of the hartland fixture, for Maya (sales_manager), equals the golden" {
            val text =
                json.encodeToString(
                    Descriptor.serializer(),
                    describe(served(), caller("maya", setOf("hartland-sales-managers"))),
                ) +
                    "\n"
            if (System.getenv("UPDATE_GOLDEN") != null) {
                Files.createDirectories(golden.parent)
                Files.writeString(golden, text)
            }
            text shouldBe Files.readString(golden)
        }

        "granted is per caller: ops may run every deployment as the decider; Maya the release ones only" {
            val ops = describe(served(), caller("olga", setOf("hartland-ops"))).deployments
            ops.filter { it.runAs == "identity.automation_decider" }.all { it.granted } shouldBe true
            val maya =
                describe(
                    served(),
                    caller("maya", setOf("hartland-sales-managers")),
                ).deployments.filter { it.granted }
            maya.map { it.name }.toSet() shouldBe
                setOf("release_native_event", "release_n8n", "release_py", "release_bpmn")
            val sam = describe(served(), caller("sam", setOf("sales"))).deployments.filter { it.granted }
            sam.shouldBeEmpty()
        }

        "stale = some fingerprint without an approval; every one approved = not stale" {
            describe(served(), caller("olga", setOf("hartland-ops"))).deployments.all { it.stale } shouldBe true
            describe(
                served {
                    _,
                    _,
                    _,
                    ->
                    true
                },
                caller("olga", setOf("hartland-ops")),
            ).deployments.none { it.stale } shouldBe
                true
        }

        "nothing served: every list is empty, served and latest are null" {
            val empty = BundleFixture.sources(BundleFixture.bundles(BundleSource { error("never") }))
            val d = describe(empty, caller("olga"))
            d.served shouldBe null
            d.latest shouldBe null
            d.recipes.shouldBeEmpty()
            d.deployments.shouldBeEmpty()
            d.registry.shouldBeEmpty()
            d.version shouldBe "v1"
        }

        // ---- the routes, behind the real bearer check ----

        val realm = TestRealm()
        afterSpec { realm.close() }

        fun ApplicationTestBuilder.service(sources: DescriptorSources) {
            application {
                install(ContentNegotiation) { json() }
                install(StatusPages) { installAutomationErrors() }
                val callers =
                    CallerVerifier(
                        JwksBearerVerifier(HttpJwksKeySource(realm.jwksUrl), TestRealm.ISSUER, TestRealm.AUDIENCE),
                    )
                install(Authentication) { AutomationBearerProvider.register(this, callers) }
                routing { authenticate(AUTOMATION_AUTH) { descriptorRoutes(sources) } }
            }
        }

        suspend fun ApplicationTestBuilder.get(path: String): HttpResponse =
            client.get(
                path,
            ) { header(HttpHeaders.Authorization, "Bearer ${realm.person("maya", listOf("hartland-sales-managers"))}") }

        fun HttpResponse.errorCode(text: String) =
            Json
                .parseToJsonElement(text)
                .jsonObject["error"]!!
                .jsonObject["code"]!!
                .jsonPrimitive.content

        "GET /automation/ answers the descriptor for the token's caller" {
            testApplication {
                service(served())
                val r = get("/automation/")
                r.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
                body["served"]!!.jsonObject["bundle"] shouldBe JsonPrimitive("served:master@0123456789ab")
                body["deployments"]!!.jsonArray.size shouldBe 11
            }
        }

        "GET /automation/recipes/{r}: the manifest's recipe; an unknown one is 404 recipe" {
            testApplication {
                service(served())
                val r = get("/automation/recipes/release_order")
                r.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
                body["recipe"]!!.jsonObject["key"]!!.jsonArray.single() shouldBe JsonPrimitive("order.order_number")
                body["verdictSchema"]!!.jsonObject["entity"] shouldBe JsonPrimitive("release_order_verdict")
                val missing = get("/automation/recipes/nope")
                missing.status shouldBe HttpStatusCode.NotFound
                missing.errorCode(missing.bodyAsText()) shouldBe "recipe"
            }
        }

        "published golden paths and labels survive refresh and authenticated recipe and descriptor reads" {
            val root = BundleFixture.copy()
            val path = root.resolve("automation/release_order/release_order.bundle/manifest.json")
            val manifest = Json.parseToJsonElement(Files.readString(path)).jsonObject
            val recipe = manifest.getValue("recipe").jsonObject
            val fingerprints =
                JsonObject(
                    recipe.getValue("fingerprints").jsonObject + ("labels" to JsonPrimitive("sha256:labels")),
                )
            val tests = JsonArray(listOf(JsonPrimitive("tests/release.yaml")))
            val updated = JsonObject(recipe + mapOf("tests" to tests, "fingerprints" to fingerprints))
            Files.writeString(path, JsonObject(manifest + ("recipe" to updated)).toString())
            testApplication {
                service(served(root))
                val detail = get("/automation/recipes/release_order")
                detail.status shouldBe HttpStatusCode.OK
                val published =
                    Json
                        .parseToJsonElement(detail.bodyAsText())
                        .jsonObject
                        .getValue("recipe")
                        .jsonObject
                published["tests"] shouldBe tests
                published["fingerprints"] shouldBe fingerprints
                val descriptor = get("/automation/")
                descriptor.status shouldBe HttpStatusCode.OK
                val described =
                    Json
                        .parseToJsonElement(descriptor.bodyAsText())
                        .jsonObject
                        .getValue("recipes")
                        .jsonArray
                        .single { it.jsonObject["name"] == JsonPrimitive("release_order") }
                        .jsonObject
                described["fingerprints"] shouldBe fingerprints
                // The fixture has no approvals, but only text/values/program/deployment may be unapproved.
                val deployment = get("/automation/deployments/release_native_event")
                Json
                    .parseToJsonElement(deployment.bodyAsText())
                    .jsonObject
                    .getValue("unapproved")
                    .jsonArray
                    .map { it.jsonPrimitive.content } shouldBe
                    listOf(
                        "recipe release_order",
                        "values release_order",
                        "program release_on_placement",
                        "deployment release_native_event",
                    )
            }
        }

        "deployment coverage failures are distinct from missing approvals on the wire" {
            val root = ApprovalFixture.copyTo(tempdir(prefix = "automation-bundles-").toPath().resolve("branch"))
            val path = root.resolve("automation/release_order/release_order.bundle/manifest.json")
            val original = Json.parseToJsonElement(Files.readString(path)).jsonObject
            val recipe = original.getValue("recipe").jsonObject
            val broken =
                JsonObject(
                    recipe + ("approvalDependencies" to JsonObject(mapOf("version" to JsonPrimitive(2)))),
                )
            Files.writeString(path, JsonObject(original + ("recipe" to broken)).toString())
            testApplication {
                service(served(root) { _, _, _ -> true })
                val response = get("/automation/deployments/release_native_event")
                response.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body.getValue("unapproved").jsonArray shouldBe JsonArray(emptyList())
                body.getValue("coverage").jsonObject shouldBe
                    JsonObject(mapOf("release_order" to JsonPrimitive("unsupported")))
                body.getValue("deployment").jsonObject.getValue("stale") shouldBe JsonPrimitive(true)
                body.getValue("registry").jsonObject.getValue("status") shouldBe JsonPrimitive("refused")
            }
        }

        "GET /automation/deployments/{d}: the deployment, its cell and which fingerprints lack an approval" {
            testApplication {
                service(served())
                val r = get("/automation/deployments/release_n8n")
                r.status shouldBe HttpStatusCode.OK
                val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
                body["deployment"]!!.jsonObject["granted"] shouldBe JsonPrimitive(true)
                body["registry"]!!.jsonObject["status"] shouldBe JsonPrimitive("notes")
                body["processDefinitionId"] shouldBe JsonNull
                body["unapproved"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe
                    listOf(
                        "recipe release_order",
                        "values release_order",
                        "program release_on_placement",
                        "deployment release_n8n",
                    )
                val missing = get("/automation/deployments/nope")
                missing.status shouldBe HttpStatusCode.NotFound
                missing.errorCode(missing.bodyAsText()) shouldBe "deployment"
            }
        }

        "deployment detail finds the exact authored definition among versions of the same key" {
            val token = realm.person("dan", listOf("studio"))
            DefinitionEngine(token).use { engine ->
                testApplication {
                    service(served(engine.bundle(), engine.client()))
                    val detail =
                        client.get("/automation/deployments/release_bpmn") {
                            header(HttpHeaders.Authorization, "Bearer $token")
                        }
                    detail.status shouldBe HttpStatusCode.OK
                    val body = Json.parseToJsonElement(detail.bodyAsText()).jsonObject
                    body["processDefinitionId"] shouldBe JsonPrimitive(DefinitionEngine.EXACT_ID)
                    body.getValue("deployment").jsonObject["granted"] shouldBe JsonPrimitive(false)
                    engine.requests.map(URI::getRawPath) shouldBe
                        listOf(
                            "/engine-rest/process-definition",
                            "/engine-rest/deployment",
                            "/engine-rest/process-definition/release_order%3A6%3Aother/xml",
                            "/engine-rest/process-definition/release_order%3A5%3Aexact/xml",
                        )
                }
            }
        }

        "deployment detail is readable without a unique verified definition" {
            val token = realm.person("dan", listOf("studio"))
            for (case in listOf("missing", "ambiguous", "forbidden", "ambiguous-artifact", "older")) {
                DefinitionEngine(token, case).use { engine ->
                    testApplication {
                        val root = if (case == "older") BundleFixture.root else engine.bundle()
                        service(served(root, engine.client()))
                        val detail =
                            client.get("/automation/deployments/release_bpmn") {
                                header(HttpHeaders.Authorization, "Bearer $token")
                            }
                        detail.status shouldBe HttpStatusCode.OK
                        Json.parseToJsonElement(detail.bodyAsText()).jsonObject["processDefinitionId"] shouldBe JsonNull
                        if (case == "older" || case == "ambiguous-artifact") engine.requests.shouldBeEmpty()
                    }
                }
            }
        }

        "an unauthenticated deployment detail never asks the engine" {
            DefinitionEngine(realm.person()).use { engine ->
                testApplication {
                    service(served(engine.bundle(), engine.client()))
                    client.get("/automation/deployments/release_bpmn").status shouldBe HttpStatusCode.Unauthorized
                    engine.requests.shouldBeEmpty()
                }
            }
        }

        "POST /automation/refresh answers the bundle; again, a no-op" {
            testApplication {
                val bundles: Bundles = BundleFixture.bundles(BundleSource { BundleFixture.checkout(branch = it) })
                service(BundleFixture.sources(bundles))
                val token = realm.person("olga", listOf("ops"))
                val first = client.post("/automation/refresh") { header(HttpHeaders.Authorization, "Bearer $token") }
                first.status shouldBe HttpStatusCode.OK
                Json.parseToJsonElement(first.bodyAsText()).jsonObject["changed"] shouldBe JsonPrimitive(true)
                val again = client.post("/automation/refresh") { header(HttpHeaders.Authorization, "Bearer $token") }
                Json.parseToJsonElement(again.bodyAsText()).jsonObject["changed"] shouldBe JsonPrimitive(false)
            }
        }

        "POST /automation/refresh needs a refresh role: anyone else is 403 refresh-forbidden, and nothing loads" {
            testApplication {
                val bundles: Bundles = BundleFixture.bundles(BundleSource { BundleFixture.checkout(branch = it) })
                service(BundleFixture.sources(bundles))
                val maya = realm.person("maya", listOf("hartland-sales-managers"))
                val r = client.post("/automation/refresh") { header(HttpHeaders.Authorization, "Bearer $maya") }
                r.status shouldBe HttpStatusCode.Forbidden
                r.errorCode(r.bodyAsText()) shouldBe "refresh-forbidden"
                bundles.served shouldBe null
                val ci = realm.service("svc-hartland-ci", roles = listOf("ops"))
                client.post("/automation/refresh") { header(HttpHeaders.Authorization, "Bearer $ci") }.status shouldBe
                    HttpStatusCode.OK
            }
        }

        "POST /automation/refresh refuses a pre-cut bundle with the Core cut diagnostic and retains the served bundle" {
            testApplication {
                val root = BundleFixture.copy()
                var commit = BundleFixture.COMMIT
                val bundles =
                    BundleFixture.bundles(
                        BundleSource { BundleFixture.checkout(root, commit = commit, branch = it) },
                    )
                service(BundleFixture.sources(bundles))
                val token = realm.service("svc-hartland-ci", roles = listOf("ops"))
                client
                    .post(
                        "/automation/refresh",
                    ) { header(HttpHeaders.Authorization, "Bearer $token") }
                    .status shouldBe
                    HttpStatusCode.OK
                val served = bundles.served
                Files.copy(
                    Path.of("src/test/resources/pre-cut/world.ttrm"),
                    root.resolve("model/worlds/demo.ttrm"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
                commit = "ffffffffffffffffffffffffffffffffffffffff"
                val response = client.post("/automation/refresh") { header(HttpHeaders.Authorization, "Bearer $token") }
                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.errorCode(response.bodyAsText()) shouldBe "bundle-invalid"
                response.bodyAsText() shouldContain "TTRP-CUT-001"
                response.bodyAsText() shouldContain "TTRP-CUT-002"
                response.bodyAsText() shouldNotContain "(CUT-"
                response.bodyAsText() shouldNotContain "is not an executor"
                bundles.served shouldBe served
                bundles.refreshFailed shouldBe true
            }
        }
    })

private class DefinitionEngine(
    private val bearer: String,
    private val case: String = "positive",
) : AutoCloseable {
    val requests = CopyOnWriteArrayList<URI>()
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/engine-rest/") { exchange ->
                val uri = exchange.requestURI
                requests.add(uri)
                val response =
                    if (exchange.requestHeaders.getFirst("Authorization") == "Bearer $bearer" && case != "forbidden") {
                        response(uri)
                    } else {
                        null
                    }
                if (response == null) {
                    exchange.sendResponseHeaders(403, -1)
                    exchange.close()
                } else {
                    val bytes = response.toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
            }
            start()
        }
    private val url = "http://127.0.0.1:${server.address.port}/engine-rest"

    fun client() = DeploymentDefinitions(url, null, Duration.ofSeconds(3))

    fun bundle(): Path {
        val root = BundleFixture.copy()
        root.edit("model/worlds/demo.ttrm") {
            it.replace("extends: camunda_8", "extends: process_engine, url: \"$url\"")
        }
        val dir = Files.createDirectories(root.resolve("processes/cz"))
        Files.writeString(dir.resolve("release_order.bpmn"), XML)
        if (case == "ambiguous-artifact") Files.writeString(dir.resolve("duplicate.bpmn"), XML)
        Files.writeString(
            dir.parent.resolve("sets.json"),
            """{"cz":{"deployments":{"release_event":"release_bpmn"}}}""",
        )
        return root
    }

    private fun response(uri: URI): String? =
        when (uri.path) {
            "/engine-rest/process-definition" ->
                if (uri.rawQuery ==
                    "key=release_order&resourceName=release_order.bpmn&withoutTenantId=true&maxResults=1001"
                ) {
                    "[${definition("release_order:7:foreign", "foreign")}," +
                        "${definition(OTHER_ID, "other")},${definition(EXACT_ID, "exact")}]"
                } else {
                    null
                }
            "/engine-rest/deployment" ->
                if (uri.rawQuery ==
                    "name=hartland-processes-cz&source=hartland%3Aprocesses%2Fcz&withoutTenantId=true&maxResults=1001"
                ) {
                    "[${deployment("other")},${deployment("exact")}]"
                } else {
                    null
                }
            "/engine-rest/process-definition/$OTHER_ID/xml" -> xml(OTHER_ID, case == "ambiguous")
            "/engine-rest/process-definition/$EXACT_ID/xml" -> xml(EXACT_ID, case != "missing")
            else -> null
        }

    private fun definition(
        id: String,
        deploymentId: String,
    ) =
        """{"id":"$id","key":"release_order","resource":"release_order.bpmn","deploymentId":"$deploymentId","tenantId":null}"""

    private fun deployment(id: String) =
        """{"id":"$id","name":"hartland-processes-cz","source":"hartland:processes/cz","tenantId":null}"""

    private fun xml(
        id: String,
        match: Boolean,
    ): String =
        JsonObject(
            mapOf("id" to JsonPrimitive(id), "bpmn20Xml" to JsonPrimitive(if (match) XML else "$XML\n")),
        ).toString()

    override fun close() = server.stop(0)

    companion object {
        const val EXACT_ID = "release_order:5:exact"
        private const val OTHER_ID = "release_order:6:other"
        private val XML =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                              xmlns:ttr="https://tatrman.org/schema/automation-export/1">
              <bpmn:process id="release_order" isExecutable="true" name="Uvolnění objednávky">
                <bpmn:extensionElements>
                  <ttr:contract>{"target":"process_engine","deployment":"release_bpmn"}</ttr:contract>
                </bpmn:extensionElements>
              </bpmn:process>
            </bpmn:definitions>
            """.trimIndent()
    }
}
