// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.observability

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.llmgateway.config.ConfigLoader
import org.tatrman.llmgateway.config.GatewayConfig
import org.tatrman.llmgateway.config.SeededKey
import org.tatrman.llmgateway.governance.KeyMint
import org.tatrman.llmgateway.module
import org.testcontainers.containers.PostgreSQLContainer
import java.net.URI
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.sql.DriverManager
import java.util.Base64
import java.util.Date

/**
 * LC-P0·S0.2 — the write half of the three threads, through the real completion routes against real
 * Postgres (LC contracts §2.1/§2.2): the caller's four attribution headers land on the row, and the
 * response names that row in `X-Prompt-Log-Id` — for a plain and a streamed completion alike. The last
 * case is the phase DoD end to end: the call is read back by its own subject and by nobody else.
 */
class PromptLogAttributionSpec :
    StringSpec({

        val pgc =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("llmgateway")
                .withUsername("tatrman")
                .withPassword("tatrman")
        val wm = WireMockServer(WireMockConfiguration.options().dynamicPort())
        val golemKey = KeyMint.generate()

        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val alg = Algorithm.RSA256(kp.public as RSAPublicKey, kp.private as RSAPrivateKey)
        val iss = "https://kc/realms/kantheon"
        val aud = "llm-gateway"
        lateinit var cfg: Config

        fun jwt(subject: String): String =
            JWT
                .create()
                .withSubject(subject)
                .withIssuer(iss)
                .withAudience(aud)
                .withExpiresAt(Date(System.currentTimeMillis() + 3_600_000))
                .withClaim("realm_access", mapOf<String, Any>("roles" to listOf("default-roles-kantheon")))
                .sign(alg)

        fun gateway(): GatewayConfig {
            val base = ConfigLoader.loadFromResources()
            return base.copy(
                providers =
                    base.providers.copy(
                        providers =
                            base.providers.providers.mapValues { (_, p) ->
                                p.copy(baseUrl = wm.baseUrl() + URI(p.baseUrl).path)
                            },
                    ),
                governance =
                    base.governance.copy(
                        keys = base.governance.keys + SeededKey("golem", "golem-k", KeyMint.hash(golemKey)),
                    ),
            )
        }

        /** The row with [id], polled — the writer is async (a request never waits on PG). */
        suspend fun row(id: Long): Map<String, String?>? {
            repeat(80) {
                val found =
                    DriverManager.getConnection(pgc.jdbcUrl, pgc.username, pgc.password).use { c ->
                        c
                            .prepareStatement(
                                "SELECT turn_ref, purpose, end_user_subject, agent_id FROM prompt_logs WHERE id = ?",
                            ).use { st ->
                                st.setLong(1, id)
                                st.executeQuery().use { rs ->
                                    if (rs.next()) {
                                        mapOf(
                                            "turn_ref" to rs.getString(1),
                                            "purpose" to rs.getString(2),
                                            "end_user_subject" to rs.getString(3),
                                            "agent_id" to rs.getString(4),
                                        )
                                    } else {
                                        null
                                    }
                                }
                            }
                    }
                if (found != null) return found
                delay(50)
            }
            return null
        }

        beforeSpec {
            pgc.start()
            wm.start()
            cfg =
                ConfigFactory
                    .parseString(
                        """
                        db { enabled = true, host = "${pgc.host}", port = "${pgc.firstMappedPort}", database = "${pgc.databaseName}", user = "${pgc.username}", password = "${pgc.password}" }
                        admin {
                            enabled = true
                            issuer = "$iss"
                            audience = "$aud"
                            realmPublicKey = "${Base64.getEncoder().encodeToString(kp.public.encoded)}"
                        }
                        """.trimIndent(),
                    ).withFallback(ConfigFactory.load())
                    .resolve()
        }
        afterSpec {
            pgc.stop()
            wm.stop()
        }
        beforeTest {
            wm.resetAll()
            wm.stubFor(
                post(urlPathEqualTo("/openai/v1/chat/completions")).willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                        """{"id":"c1","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"It is Q3."},"finish_reason":"stop"}],"usage":{"prompt_tokens":8,"completion_tokens":4,"total_tokens":12}}""",
                    ),
                ),
            )
        }

        val body = """{"model":"gpt-4o","messages":[{"role":"user","content":"which quarter?"}]}"""

        "a completion carrying the four headers lands as a row with them, under the id the response named" {
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(cfg, gateway()) }

                val res =
                    client.post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $golemKey")
                        header("X-Turn-Ref", "turn-lc-1")
                        header("X-Call-Purpose", "compose-plan")
                        header("X-End-User-Subject", "sub-dan")
                        header("X-Agent-Id", "golem-hartland")
                        setBody(body)
                    }
                res.status shouldBe HttpStatusCode.OK
                val id = res.headers["X-Prompt-Log-Id"].shouldNotBeNull().toLong()

                row(id) shouldBe
                    mapOf(
                        "turn_ref" to "turn-lc-1",
                        "purpose" to "compose-plan",
                        "end_user_subject" to "sub-dan",
                        "agent_id" to "golem-hartland",
                    )
            }
        }

        "a completion with none of the headers is still written (NULLs) and still named" {
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(cfg, gateway()) }

                val res =
                    client.post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $golemKey")
                        setBody(body)
                    }
                res.status shouldBe HttpStatusCode.OK
                val id = res.headers["X-Prompt-Log-Id"].shouldNotBeNull().toLong()
                row(id) shouldBe
                    mapOf("turn_ref" to null, "purpose" to null, "end_user_subject" to null, "agent_id" to null)
            }
        }

        "an over-long attribution header is stored truncated to 512 characters, never refused" {
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(cfg, gateway()) }

                val long = "x".repeat(4_000)
                val res =
                    client.post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $golemKey")
                        header("X-Call-Purpose", long)
                        header("X-Agent-Id", long)
                        setBody(body)
                    }
                res.status shouldBe HttpStatusCode.OK
                val stored = row(res.headers["X-Prompt-Log-Id"]!!.toLong()).shouldNotBeNull()
                stored["purpose"] shouldBe "x".repeat(512)
                stored["agent_id"] shouldBe "x".repeat(512)
            }
        }

        "a streamed completion names its row on the response headers; the frames stay untouched" {
            val chunks =
                listOf(
                    """{"id":"s","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":"It is Q3."},"finish_reason":null}]}""",
                    """{"id":"s","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
                    "[DONE]",
                ).joinToString("") { "data: $it\n\n" }
            wm.stubFor(
                post(urlPathEqualTo("/openai/v1/chat/completions")).willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "text/event-stream").withBody(chunks),
                ),
            )
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(cfg, gateway()) }

                val res =
                    client.post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $golemKey")
                        header("X-Turn-Ref", "turn-lc-stream")
                        header("X-Call-Purpose", "answer-format")
                        setBody(
                            """{"model":"gpt-4o","stream":true,"messages":[{"role":"user","content":"which quarter?"}]}""",
                        )
                    }
                val sse = res.bodyAsText()
                sse shouldContain "data: [DONE]"
                sse.contains("X-Prompt-Log-Id") shouldBe false // header, not an in-band frame
                val id = res.headers["X-Prompt-Log-Id"].shouldNotBeNull().toLong()
                row(id).shouldNotBeNull()["purpose"] shouldBe "answer-format"
            }
        }

        // The LC-P0 DoD, end to end: one call made with a context; its own subject reads it back by turn;
        // another subject's JWT gets 200 and an EMPTY list — not a 403, which would say the turn exists.
        "the call is read back by its own subject, and by no other subject" {
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(cfg, gateway()) }

                val made =
                    client.post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $golemKey")
                        header("X-Turn-Ref", "turn-lc-dod")
                        header("X-Call-Purpose", "compose-plan")
                        header("X-End-User-Subject", "sub-dan")
                        header("X-Agent-Id", "golem-hartland")
                        setBody(body)
                    }
                val id = made.headers["X-Prompt-Log-Id"]!!
                row(id.toLong()).shouldNotBeNull()

                fun items(json: String) = Json.parseToJsonElement(json).jsonObject["items"]!!.jsonArray

                val own =
                    client.get("/v1/prompt-logs?turn_ref=turn-lc-dod") {
                        header(HttpHeaders.Authorization, "Bearer ${jwt("sub-dan")}")
                    }
                own.status shouldBe HttpStatusCode.OK
                val mine = items(own.bodyAsText()).single().jsonObject
                mine["id"]!!.jsonPrimitive.content shouldBe id
                mine["purpose"]!!.jsonPrimitive.content shouldBe "compose-plan"
                mine["agentId"]!!.jsonPrimitive.content shouldBe "golem-hartland"
                mine.containsKey("endUserSubject") shouldBe false

                val theirs =
                    client.get("/v1/prompt-logs?turn_ref=turn-lc-dod") {
                        header(HttpHeaders.Authorization, "Bearer ${jwt("sub-marketa")}")
                    }
                theirs.status shouldBe HttpStatusCode.OK
                items(theirs.bodyAsText()).size shouldBe 0

                // The data-plane key is not a realm JWT: it never reads rows.
                client
                    .get("/v1/prompt-logs?turn_ref=turn-lc-dod") {
                        header(HttpHeaders.Authorization, "Bearer $golemKey")
                    }.status shouldBe HttpStatusCode.Unauthorized
            }
        }
    })
