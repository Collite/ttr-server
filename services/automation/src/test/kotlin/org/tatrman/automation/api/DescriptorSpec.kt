// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.kotest.core.spec.style.StringSpec
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

        fun served(approvals: ApprovalReader = ApprovalReader { _, _, _ -> false }): DescriptorSources {
            val bundles = BundleFixture.bundles(BundleSource { BundleFixture.checkout(branch = it) })
            runBlocking { bundles.refresh() }
            return BundleFixture.sources(bundles, approvals)
        }

        "the descriptor of the hartland fixture, for Maya (sales_manager), equals the golden" {
            val text =
                json.encodeToString(
                    Descriptor.serializer(),
                    describe(served(), caller("maya", setOf("sales_manager"))),
                ) +
                    "\n"
            if (System.getenv("UPDATE_GOLDEN") != null) {
                Files.createDirectories(golden.parent)
                Files.writeString(golden, text)
            }
            text shouldBe Files.readString(golden)
        }

        "granted is per caller: ops may run every deployment as the decider; Maya the release ones only" {
            val ops = describe(served(), caller("olga", setOf("ops"))).deployments
            ops.filter { it.runAs == "identity.automation_decider" }.all { it.granted } shouldBe true
            val maya = describe(served(), caller("maya", setOf("sales_manager"))).deployments.filter { it.granted }
            maya.map { it.name }.toSet() shouldBe
                setOf("release_native_event", "release_n8n", "release_py", "release_bpmn")
            val sam = describe(served(), caller("sam", setOf("sales"))).deployments.filter { it.granted }
            sam.shouldBeEmpty()
        }

        "stale = some fingerprint without an approval; every one approved = not stale" {
            describe(served(), caller("olga", setOf("ops"))).deployments.all { it.stale } shouldBe true
            describe(served { _, _, _ -> true }, caller("olga", setOf("ops"))).deployments.none { it.stale } shouldBe
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
            ) { header(HttpHeaders.Authorization, "Bearer ${realm.person("maya", listOf("sales_manager"))}") }

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
    })
