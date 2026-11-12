// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.tatrman.automation.bundle.ApprovalBook
import org.tatrman.automation.bundle.Approver
import org.tatrman.automation.bundle.BundleFixture
import org.tatrman.automation.identity.AUTOMATION_AUTH
import org.tatrman.automation.identity.AutomationBearerProvider
import org.tatrman.automation.identity.CallerVerifier
import org.tatrman.mcp.identity.HttpJwksKeySource
import org.tatrman.mcp.identity.JwksBearerVerifier
import org.tatrman.automation.identity.TestRealm
import org.tatrman.automation.ledger.HeldGroup
import org.tatrman.automation.ledger.Ledger
import org.tatrman.automation.ledger.RunNames
import org.tatrman.automation.ledger.RunStatus
import org.tatrman.automation.runner.RunFingerprints
import org.tatrman.automation.writer.Apply
import org.tatrman.automation.writer.DeferredContexts
import org.tatrman.automation.writer.Held
import java.net.URLEncoder
import java.time.Duration
import java.util.UUID

/**
 * S2.3 T3/T4 — the dev face: 404 unless AUTOMATION_DEV_ROUTES=true; 403 identity without role.ops (D-138, the world's);
 * D-184's approvals seeding through the same approve() (201 `{id}`, 409 golden-required).
 */
class DevRoutesSpec :
    StringSpec({
        val realm = TestRealm()
        afterSpec { realm.close() }
        val bundle = BundleFixture.load()
        val id = UUID.randomUUID()
        val ledger = mockk<Ledger>()
        every { ledger.heldGroup(any(), any()) } answers {
            HeldGroup(
                id,
                UUID.randomUUID(),
                RunStatus.WAITING,
                "held",
                RunNames("release_order", "release_on_placement", "release_native_event", bundle.id, "maya"),
                RunFingerprints(null, null, null, null, null),
                emptyList(),
                emptyList(),
                emptyList(),
                Duration.ofMinutes(30),
            )
        }
        val held =
            Held(
                ledger,
                Apply(mockk(), BundleFixture.clock),
                DeferredContexts { _, _, _ -> error("no apply before the role check") },
                { bundle },
                BundleFixture.clock,
            )
        val group = URLEncoder.encode("""{"order_number":"4711"}""", Charsets.UTF_8)
        val written = mutableListOf<org.tatrman.automation.bundle.ApprovalRecord>()
        val approver = Approver({ bundle }, ApprovalBook { it.also(written::add) }, { BundleFixture.clock.instant() })
        val recipe = "release_order"
        val fps =
            bundle.recipes
                .getValue(recipe)
                .recipe.fingerprints

        fun approval(kind: String) =
            """{"objectKind":"recipe","object":"$recipe","kind":"$kind",""" +
                """"fps":{"text":"${fps.text}","values":"${fps.values}"},"note":"seed"}"""

        suspend fun post(
            enabled: Boolean,
            token: String,
            path: String = "/automation/dev/runs/$id/held/$group/commit",
            body: String? = null,
        ): Pair<HttpStatusCode, String> {
            var answer: Pair<HttpStatusCode, String>? = null
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    install(StatusPages) { installAutomationErrors() }
                    install(Authentication) {
                        AutomationBearerProvider.register(
                            this,
                            CallerVerifier(
                                JwksBearerVerifier(
                                    HttpJwksKeySource(realm.jwksUrl),
                                    TestRealm.ISSUER,
                                    TestRealm.AUDIENCE,
                                ),
                            ),
                        )
                    }
                    routing { authenticate(AUTOMATION_AUTH) { devRoutes(enabled, held, approver, { bundle }) } }
                }
                val response =
                    client.post(path) {
                        bearerAuth(token)
                        if (body != null) {
                            contentType(ContentType.Application.Json)
                            setBody(body)
                        }
                    }
                answer = response.status to response.bodyAsText()
            }
            return answer!!
        }

        "the dev routes answer 404 unless AUTOMATION_DEV_ROUTES=true" {
            post(enabled = false, realm.person()).first shouldBe HttpStatusCode.NotFound
        }

        "a caller outside the world's role.ops is 403 identity, before any apply" {
            val (status, body) = post(enabled = true, realm.person(roles = listOf("hartland-sales-managers")))
            status shouldBe HttpStatusCode.Forbidden
            Json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject["code"] shouldBe JsonPrimitive("identity")
        }

        "the approvals seed is 404 unless AUTOMATION_DEV_ROUTES=true, and 403 without role.ops; nothing is written" {
            written.clear()
            post(false, realm.person(roles = listOf("hartland-ops")), "/automation/dev/approvals", approval("values"))
                .first shouldBe HttpStatusCode.NotFound
            val (status, body) =
                post(
                    true,
                    realm.person(roles = listOf("hartland-sales-managers")),
                    "/automation/dev/approvals",
                    approval("values"),
                )
            status shouldBe HttpStatusCode.Forbidden
            Json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject["code"] shouldBe JsonPrimitive("identity")
            written shouldBe emptyList()
        }

        "role.ops seeds an approval through approve(): 201 {id}, approver = the caller, the served toolchain recorded" {
            written.clear()
            val (status, body) =
                post(
                    true,
                    realm.person(username = "olga", roles = listOf("hartland-ops")),
                    "/automation/dev/approvals",
                    approval("values"),
                )
            status shouldBe HttpStatusCode.Created
            val record = written.single()
            Json.parseToJsonElement(body).jsonObject["id"] shouldBe JsonPrimitive(record.id)
            record.kind shouldBe "values"
            record.approver shouldBe "olga"
            record.note shouldBe "seed"
            record.toolchain shouldBe
                bundle.recipes
                    .getValue(recipe)
                    .manifest.toolchain
                    .ifBlank { null }
        }

        "a recipe approval of a recipe without a golden case is 409 golden-required (D-89, the same approve())" {
            written.clear()
            val noGolden = bundle.recipes.values.first { it.recipe.tests.isEmpty() }
            val f = noGolden.recipe.fingerprints
            val (status, body) =
                post(
                    true,
                    realm.person(roles = listOf("hartland-ops")),
                    "/automation/dev/approvals",
                    """{"objectKind":"recipe","object":"${noGolden.name}","kind":"recipe",""" +
                        """"fps":{"text":"${f.text}","values":"${f.values}"}}""",
                )
            status shouldBe HttpStatusCode.Conflict
            Json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject["code"] shouldBe
                JsonPrimitive("golden-required")
            written shouldBe emptyList()
        }
    })
