// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.observability

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.llmgateway.module
import org.testcontainers.containers.PostgreSQLContainer
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.sql.DriverManager
import java.util.Base64
import java.util.Date

/**
 * The prompt-log inspect surface through the wire (PT contracts §5), against
 * real Postgres — the same shape and gate as `AdminApiSpec`, which is this
 * service's house pattern for a route that touches the database.
 *
 * Rows are inserted with raw SQL rather than through `PromptLogWriter`: the
 * writer is an async fire-and-forget channel and a test that waited on it would
 * be timing-dependent. What is under test here is the READ path.
 */
class PromptLogsRoutesSpec :
    StringSpec({

        val pgc =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("llmgateway")
                .withUsername("tatrman")
                .withPassword("tatrman")

        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val alg = Algorithm.RSA256(kp.public as RSAPublicKey, kp.private as RSAPrivateKey)
        val iss = "https://kc/realms/tatrman"
        val aud = "llm-gateway"

        fun token(
            roles: List<String>,
            subject: String = "inspector",
        ): String =
            JWT
                .create()
                .withSubject(subject)
                .withIssuer(iss)
                .withAudience(aud)
                .withExpiresAt(Date(System.currentTimeMillis() + 3_600_000))
                .withClaim("realm_access", mapOf<String, Any>("roles" to roles))
                .sign(alg)

        val adminJwt = token(listOf("llm-gateway-admin"))
        lateinit var cfg: Config

        fun seed(
            turnRef: String?,
            traceId: String?,
            prompt: String,
            subject: String? = null,
            purpose: String? = null,
            agentId: String? = null,
        ) {
            DriverManager
                .getConnection(pgc.jdbcUrl, pgc.username, pgc.password)
                .use { c ->
                    c
                        .prepareStatement(
                            """
                            INSERT INTO prompt_logs
                              (user_id, model_name, provider, prompt_text, response_text,
                               tokens_prompt, tokens_completion, duration_ms, status,
                               turn_ref, trace_id, requested_model, served_model, served_provider, cached,
                               end_user_subject, purpose, agent_id)
                            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                            """.trimIndent(),
                        ).use { st ->
                            st.setString(1, "vk_test")
                            st.setString(2, "claude-opus-5")
                            st.setString(3, "azure")
                            st.setString(4, prompt)
                            st.setString(5, "a completion")
                            st.setInt(6, 100)
                            st.setInt(7, 20)
                            st.setLong(8, 900)
                            st.setString(9, "SUCCESS")
                            st.setString(10, turnRef)
                            st.setString(11, traceId)
                            st.setString(12, "claude-opus-5")
                            st.setString(13, "claude-opus-5")
                            st.setString(14, "azure")
                            st.setBoolean(15, false)
                            st.setString(16, subject)
                            st.setString(17, purpose)
                            st.setString(18, agentId)
                            st.executeUpdate()
                        }
                }
        }

        beforeSpec {
            pgc.start()
            cfg =
                ConfigFactory
                    .parseString(
                        """
                        db { enabled = true, host = "${pgc.host}", port = "${pgc.firstMappedPort}", database = "${pgc.databaseName}", user = "${pgc.username}", password = "${pgc.password}" }
                        admin {
                            enabled = true
                            issuer = "$iss"
                            audience = "$aud"
                            role = "llm-gateway-admin"
                            realmPublicKey = "${Base64.getEncoder().encodeToString(kp.public.encoded)}"
                        }
                        """.trimIndent(),
                    ).withFallback(ConfigFactory.load())
                    .resolve()
        }
        afterSpec { pgc.stop() }

        fun items(body: String) = Json.parseToJsonElement(body).jsonObject["items"]!!.jsonArray

        fun access(body: String) =
            Json
                .parseToJsonElement(body)
                .jsonObject["access"]!!
                .jsonPrimitive.content

        "inspect surface: auth gates, filters by turn_ref and trace_id, enforces limit" {
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(cfg) } // Flyway runs V1..V4 at boot
                // `application {}` only REGISTERS the module — testApplication boots LAZILY, on the
                // first client call. Without this the seeds below hit an unmigrated database and
                // every test here dies on `relation "prompt_logs" does not exist`.
                startApplication()

                // Seeded after boot so the schema exists.
                seed(turnRef = "turn-A", traceId = "trace-A", prompt = "first for A")
                seed(turnRef = "turn-A", traceId = "trace-A", prompt = "second for A")
                seed(turnRef = "turn-B", traceId = "trace-B", prompt = "for B")
                seed(turnRef = null, traceId = null, prompt = "uncorrelated")

                // ── auth ──
                client.get("/v1/prompt-logs?turn_ref=turn-A").status shouldBe HttpStatusCode.Unauthorized
                // LC-1 replaced the 403 a role-less realm JWT used to get: it now reads its OWN rows, and
                // these seeds belong to nobody — so 200 and an empty list (never an existence oracle).
                val roleless =
                    client.get("/v1/prompt-logs?turn_ref=turn-A") {
                        header(HttpHeaders.Authorization, "Bearer ${token(listOf("plain-user"))}")
                    }
                roleless.status shouldBe HttpStatusCode.OK
                items(roleless.bodyAsText()).size shouldBe 0

                // ── neither key → 400, never an unfiltered dump of the whole table ──
                client
                    .get("/v1/prompt-logs") { header(HttpHeaders.Authorization, "Bearer $adminJwt") }
                    .status shouldBe HttpStatusCode.BadRequest

                // ── filters by turn_ref ──
                val byTurn =
                    client.get("/v1/prompt-logs?turn_ref=turn-A") {
                        header(HttpHeaders.Authorization, "Bearer $adminJwt")
                    }
                byTurn.status shouldBe HttpStatusCode.OK
                val turnItems = items(byTurn.bodyAsText())
                turnItems.size shouldBe 2
                turnItems.map { it.jsonObject["promptText"]!!.jsonPrimitive.content } shouldBe
                    listOf("first for A", "second for A") // oldest first
                turnItems.all { it.jsonObject["turnRef"]!!.jsonPrimitive.content == "turn-A" } shouldBe true

                // ── filters by trace_id ──
                val byTrace =
                    client.get("/v1/prompt-logs?trace_id=trace-B") {
                        header(HttpHeaders.Authorization, "Bearer $adminJwt")
                    }
                val traceItems = items(byTrace.bodyAsText())
                traceItems.size shouldBe 1
                traceItems
                    .single()
                    .jsonObject["promptText"]!!
                    .jsonPrimitive.content shouldBe "for B"

                // ── limit enforced ──
                val limited =
                    client.get("/v1/prompt-logs?turn_ref=turn-A&limit=1") {
                        header(HttpHeaders.Authorization, "Bearer $adminJwt")
                    }
                items(limited.bodyAsText()).size shouldBe 1

                // An absurd limit is clamped, not honoured.
                val absurd =
                    client.get("/v1/prompt-logs?turn_ref=turn-A&limit=100000") {
                        header(HttpHeaders.Authorization, "Bearer $adminJwt")
                    }
                absurd.status shouldBe HttpStatusCode.OK
                items(absurd.bodyAsText()).size shouldBe 2

                // ── an unknown key is an empty page, not a 404: "no calls recorded"
                //     is a legitimate answer, and the async writer means it can happen
                //     even for a turn that really did call a model.
                val unknown =
                    client.get("/v1/prompt-logs?turn_ref=no-such-turn") {
                        header(HttpHeaders.Authorization, "Bearer $adminJwt")
                    }
                unknown.status shouldBe HttpStatusCode.OK
                items(unknown.bodyAsText()).size shouldBe 0
            }
        }

        // review-079 R8. The two keys used to be ORed, so naming one turn returned
        // every row sharing its trace — sibling turns' prompt and completion bodies
        // included. turn_ref now wins when both are supplied.
        "a turn_ref never widens to its whole trace, even when both keys are given" {
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(cfg) }
                startApplication() // boot before seeding — see the note above

                // Two turns, ONE trace — what a session-level trace looks like.
                seed(turnRef = "turn-mine", traceId = "trace-shared", prompt = "mine")
                seed(turnRef = "turn-theirs", traceId = "trace-shared", prompt = "theirs")

                val both =
                    client.get("/v1/prompt-logs?turn_ref=turn-mine&trace_id=trace-shared") {
                        header(HttpHeaders.Authorization, "Bearer $adminJwt")
                    }
                val rows = items(both.bodyAsText())
                rows.size shouldBe 1
                rows
                    .single()
                    .jsonObject["promptText"]!!
                    .jsonPrimitive.content shouldBe "mine"

                // The trace remains a first-class key on its own — a caller holding
                // only a trace still gets everything under it.
                items(
                    client
                        .get("/v1/prompt-logs?trace_id=trace-shared") {
                            header(HttpHeaders.Authorization, "Bearer $adminJwt")
                        }.bodyAsText(),
                ).size shouldBe 2
            }
        }

        // ── LC-1 (LC contracts §2.3): the auth matrix, row by row ──────────────────────────────────
        "per-row authz: admin and inspect read every row; a subject reads its own; a key or nothing is 401" {
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(cfg) }
                startApplication() // boot before seeding — see the note above

                seed(
                    "turn-M",
                    "trace-M",
                    "dan's call",
                    subject = "sub-dan",
                    purpose = "compose-plan",
                    agentId = "golem-hartland",
                )
                seed(
                    "turn-M",
                    "trace-M",
                    "marketa's call",
                    subject = "sub-marketa",
                    purpose = "answer-format",
                    agentId = "golem-investment",
                )
                seed("turn-M", "trace-M", "pre-LC call") // NULL subject: role-only

                suspend fun read(bearer: String?) =
                    client.get("/v1/prompt-logs?turn_ref=turn-M") {
                        if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer")
                    }

                // 1. admin → all rows, bodies included, and a role-holder is told whose they are
                val adminBody = read(adminJwt).bodyAsText()
                access(adminBody) shouldBe "all"
                val asAdmin = items(adminBody)
                asAdmin.map { it.jsonObject["promptText"]!!.jsonPrimitive.content } shouldBe
                    listOf("dan's call", "marketa's call", "pre-LC call")
                asAdmin.map { it.jsonObject["endUserSubject"]!!.jsonPrimitive.contentOrNull } shouldBe
                    listOf("sub-dan", "sub-marketa", null)

                // 2. inspect (the new, narrow role) → all rows, bodies included
                val asInspect = read(token(listOf("llm-gateway-inspect"), subject = "sub-ops"))
                asInspect.status shouldBe HttpStatusCode.OK
                val inspectBody = asInspect.bodyAsText()
                access(inspectBody) shouldBe "all"
                items(inspectBody).map { it.jsonObject["responseText"]!!.jsonPrimitive.contentOrNull } shouldBe
                    listOf("a completion", "a completion", "a completion")

                // 3. neither role, sub = dan → dan's row ONLY; marketa's and the NULL-subject row omitted; 200.
                //    A reader-scoped answer says so ("own"), and carries NO bodies (review-100 F4, ruled
                //    2026-09-24): the raw prompt holds golem's whole system prompt, and the only path to it
                //    is the role-gated, floor-redacted `full` profile in the assembler.
                val asDan = read(token(listOf("default-roles-kantheon"), subject = "sub-dan"))
                asDan.status shouldBe HttpStatusCode.OK
                val danBody = asDan.bodyAsText()
                access(danBody) shouldBe "own"
                val dan = items(danBody).single().jsonObject
                dan["promptText"] shouldBe JsonNull
                dan["responseText"] shouldBe JsonNull
                dan["purpose"]!!.jsonPrimitive.content shouldBe "compose-plan"
                dan["agentId"]!!.jsonPrimitive.content shouldBe "golem-hartland"
                dan.containsKey("endUserSubject") shouldBe false // a subject is not told who they are

                // ... and the same by trace_id: the subject filter is not a turn_ref-only path
                val danByTrace =
                    client.get("/v1/prompt-logs?trace_id=trace-M") {
                        header(HttpHeaders.Authorization, "Bearer ${token(emptyList(), subject = "sub-dan")}")
                    }
                items(danByTrace.bodyAsText())
                    .single()
                    .jsonObject["purpose"]!!
                    .jsonPrimitive.content shouldBe
                    "compose-plan"

                // 4. a gateway API key is not a realm JWT → 401;  5. no bearer → 401
                read("ttrk-0123456789abcdef0123456789abcdef").status shouldBe HttpStatusCode.Unauthorized
                read(null).status shouldBe HttpStatusCode.Unauthorized
            }
        }

        // The page limit is spent AFTER the subject filter (it is in SQL): a subject whose one row sits
        // behind 5 of someone else's still gets it at limit=1 — an in-memory filter would return nothing.
        "the subject filter runs in SQL, before the limit" {
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(cfg) }
                startApplication()

                repeat(5) { seed("turn-L", null, "other $it", subject = "sub-other", purpose = "other") }
                seed("turn-L", null, "mine", subject = "sub-me", purpose = "mine")

                val res =
                    client.get("/v1/prompt-logs?turn_ref=turn-L&limit=1") {
                        header(HttpHeaders.Authorization, "Bearer ${token(emptyList(), subject = "sub-me")}")
                    }
                // (a subject's read carries no bodies — the row is told apart by its purpose)
                items(res.bodyAsText())
                    .single()
                    .jsonObject["purpose"]!!
                    .jsonPrimitive.content shouldBe "mine"
            }
        }

        // review-079 R12. `created_at` has a DEFAULT, not a NOT NULL; a row carrying
        // an explicit NULL used to throw in the row mapper and 500 the whole page.
        "a row with a null created_at serializes instead of failing the page" {
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(cfg) }
                startApplication() // boot before seeding — see the note above

                seed(turnRef = "turn-null-ts", traceId = null, prompt = "no timestamp")
                DriverManager
                    .getConnection(pgc.jdbcUrl, pgc.username, pgc.password)
                    .use { c ->
                        c.prepareStatement("UPDATE prompt_logs SET created_at = NULL WHERE turn_ref = ?").use { st ->
                            st.setString(1, "turn-null-ts")
                            st.executeUpdate()
                        }
                    }

                val res =
                    client.get("/v1/prompt-logs?turn_ref=turn-null-ts") {
                        header(HttpHeaders.Authorization, "Bearer $adminJwt")
                    }
                res.status shouldBe HttpStatusCode.OK
                val row = items(res.bodyAsText()).single().jsonObject
                row["promptText"]!!.jsonPrimitive.content shouldBe "no timestamp"
                row["createdAt"]!!.jsonPrimitive.contentOrNull shouldBe null
            }
        }
    })
