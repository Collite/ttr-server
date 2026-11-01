// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.governance

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import org.tatrman.llmgateway.config.BudgetDef
import org.tatrman.llmgateway.config.ConfigLoader
import org.tatrman.llmgateway.config.GatewayConfig
import org.tatrman.llmgateway.config.RateLimitDef
import org.tatrman.llmgateway.config.SeededKey
import org.tatrman.llmgateway.config.Team
import org.tatrman.llmgateway.module
import org.tatrman.llmgateway.store.Pg
import org.tatrman.llmgateway.store.RedisConn
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.PostgreSQLContainer
import java.net.URI

/**
 * LG-P4·S2·T7 — the two-team admission smoke through the wire (P-1). Team A (rpm=2, soft budget) makes two
 * successful chat calls that settle, then its third is rate-limited 429; team B (hard budget already
 * exceeded) is blocked with `insufficient_quota` + `x-gateway-reason`. A budget refusal hands back the rate
 * slot it took (team C: rpm=2, hard budget exceeded — every retry is still a budget refusal, and its rate
 * window stays empty). Assertions are data-flow (budget_usage rows moved, the window's log), not call counts
 * (house rule). Real PG + Redis + a WireMock upstream.
 */
class AdmissionSmokeSpec :
    StringSpec({

        val pgc =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("llmgateway")
                .withUsername("tatrman")
                .withPassword("tatrman")
        val redisC = GenericContainer("redis:7-alpine").apply { withExposedPorts(6379) }
        val wm = WireMockServer(WireMockConfiguration.options().dynamicPort())

        val keyA = KeyMint.generate()
        val keyB = KeyMint.generate()
        val keyC = KeyMint.generate()
        lateinit var cfg: Config
        lateinit var budgets: BudgetUsageRepo
        lateinit var virtualKeys: VirtualKeyRepo
        lateinit var redis: RedisConn
        // matches the service's firstOfMonthUtc() (current UTC month) — the row key both sides settle against
        val month =
            java.time.LocalDate
                .now(java.time.ZoneOffset.UTC)
                .withDayOfMonth(1)

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
                        teams =
                            listOf(
                                Team("teamA", "teamA/", BudgetDef("a", 1_000.0, "soft"), RateLimitDef("arl", 2)),
                                Team("teamB", "teamB/", BudgetDef("b", 0.01, "hard"), RateLimitDef("brl", 100)),
                                Team("teamC", "teamC/", BudgetDef("c", 0.01, "hard"), RateLimitDef("crl", 2)),
                            ),
                        keys =
                            listOf(
                                SeededKey("teamA", "kA", KeyMint.hash(keyA)),
                                SeededKey("teamB", "kB", KeyMint.hash(keyB)),
                                SeededKey("teamC", "kC", KeyMint.hash(keyC)),
                            ),
                    ),
            )
        }

        beforeSpec {
            pgc.start()
            redisC.start()
            wm.start()
            wm.stubFor(
                post(urlPathEqualTo("/openai/v1/chat/completions")).willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(
                        """{"id":"c1","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""",
                    ),
                ),
            )
            cfg =
                ConfigFactory
                    .parseString(
                        """
                        db { enabled = true, host = "${pgc.host}", port = "${pgc.firstMappedPort}", database = "${pgc.databaseName}", user = "${pgc.username}", password = "${pgc.password}" }
                        redis { enabled = true, host = "${redisC.host}", port = ${redisC.getMappedPort(6379)} }
                        """.trimIndent(),
                    ).withFallback(ConfigFactory.load())
                    .resolve()
            val pg = Pg.fromConfig(cfg).also { it.migrate() }
            budgets = BudgetUsageRepo(pg.db)
            virtualKeys = VirtualKeyRepo(pg.db)
            redis = RedisConn.fromConfig(cfg)
            // module() upserts teams at boot, but that boot is lazy (first request) — ensure the teams exist
            // now so the pre-seed of team B's usage (before the first call) satisfies the FK.
            TeamRepo(pg.db).upsertAll(gateway().governance.teams)
        }
        afterSpec {
            redis.close()
            pgc.stop()
            redisC.stop()
            wm.stop()
        }

        fun body(model: String) = """{"model":"$model","messages":[{"role":"user","content":"hi"}]}"""

        "team A settles two successes then is rate-limited; team B is blocked by its hard budget" {
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(cfg, gateway()) }

                // team B's hard cap is already blown (seed usage over 0.01) → admission blocks before upstream
                budgets.addUsage("teamB", month, java.math.BigDecimal("1.0"), 0, 0)

                // team A: rpm=2 → two calls admitted + served (WireMock) + settled
                repeat(2) {
                    client
                        .post("/v1/chat/completions") {
                            header(HttpHeaders.Authorization, "Bearer $keyA")
                            setBody(body("gpt-4o"))
                        }.status shouldBe HttpStatusCode.OK
                }
                // A's spend was recorded (data-flow, not call count)
                budgets.usedUsd("teamA", month) shouldBeGreaterThan 0.0

                // A's third call within the window → rate-limited (no upstream work)
                val third =
                    client.post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $keyA")
                        setBody(body("gpt-4o"))
                    }
                third.status shouldBe HttpStatusCode.TooManyRequests
                third.bodyAsText() shouldContain "rate_limit_exceeded"

                // team B → hard budget exceeded → insufficient_quota + x-gateway-reason
                val b =
                    client.post("/v1/chat/completions") {
                        header(HttpHeaders.Authorization, "Bearer $keyB")
                        setBody(body("gpt-4o"))
                    }
                b.status shouldBe HttpStatusCode.TooManyRequests
                b.bodyAsText() shouldContain "insufficient_quota"
                b.headers["x-gateway-reason"] shouldBe "budget_exceeded"
            }
        }

        "a budget refusal hands back its rate slot: retrying over budget is never rate-limited" {
            testApplication {
                environment { config = MapApplicationConfig() }
                application { module(cfg, gateway()) }
                // boot now: the spec's repos run on Exposed's default database, the pool opened last — after the
                // previous test that is its app's pool, closed when that app stopped
                startApplication()

                budgets.addUsage("teamC", month, java.math.BigDecimal("1.0"), 0, 0) // hard cap blown

                // rpm=2: had the refusals kept their slots, the third would be refused by the rate limit
                repeat(3) {
                    val c =
                        client.post("/v1/chat/completions") {
                            header(HttpHeaders.Authorization, "Bearer $keyC")
                            setBody(body("gpt-4o"))
                        }
                    c.status shouldBe HttpStatusCode.TooManyRequests
                    c.headers["x-gateway-reason"] shouldBe "budget_exceeded"
                }
                // the key's window holds none of them
                val keyId = virtualKeys.findByHash(KeyMint.hash(keyC))!!.id
                redis.sync().zcard("llm-gateway:rlw:$keyId") shouldBe 0L
            }
        }
    })
