// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import org.tatrman.automation.bundle.ApprovalFixture
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.automation.bundle.ApprovalReader
import org.tatrman.automation.bundle.BundleFixture
import org.tatrman.automation.bundle.BundleSource
import org.tatrman.automation.bundle.Bundles
import org.tatrman.automation.identity.AUTOMATION_AUTH
import org.tatrman.automation.identity.AutomationBearerProvider
import org.tatrman.automation.identity.CallerVerifier
import org.tatrman.automation.identity.TestRealm
import org.tatrman.automation.identity.caller
import org.tatrman.mcp.identity.HttpJwksKeySource
import org.tatrman.mcp.identity.JwksBearerVerifier
import java.nio.file.Files
import java.nio.file.Path

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
            approvals: ApprovalReader = ApprovalReader { _, _, _ -> false },
        ): DescriptorSources {
            val bundles = BundleFixture.bundles(BundleSource { BundleFixture.checkout(root, branch = it) })
            runBlocking { bundles.refresh() }
            return BundleFixture.sources(bundles, approvals)
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
                body["deployments"]!!.jsonArray.size shouldBe 12
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
                service(served(root, ApprovalReader { _, _, _ -> true }))
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
    })
