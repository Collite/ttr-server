// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.governance

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import org.tatrman.llmgateway.config.BudgetDef
import org.tatrman.llmgateway.config.Team
import org.tatrman.llmgateway.store.Pg
import org.testcontainers.containers.PostgreSQLContainer
import java.time.LocalDate

/**
 * LG-P4·S2·T2 — monthly money budgets over real Postgres (Testcontainers). Proves the settle is ONE atomic
 * upsert-add (50 concurrent settles sum exactly — the read-modify-write race the FI-6 note warns about),
 * cached responses never settle, the month is the row key (UTC boundary → new row), and the pre-check
 * enforces hard vs soft with min-wins between a per-key override and the team default.
 */
class BudgetComponentSpec :
    StringSpec({

        val pgc =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("llmgateway")
                .withUsername("tatrman")
                .withPassword("tatrman")

        lateinit var pg: Pg
        lateinit var repo: BudgetUsageRepo
        val july = LocalDate.of(2026, 7, 1)

        beforeSpec {
            pgc.start()
            pg =
                Pg
                    .fromConfig(
                        ConfigFactory.parseString(
                            """
                            db {
                                type = "POSTGRES"
                                enabled = true
                                host = "${pgc.host}"
                                port = "${pgc.firstMappedPort}"
                                database = "${pgc.databaseName}"
                                user = "${pgc.username}"
                                password = "${pgc.password}"
                            }
                            """.trimIndent(),
                        ),
                    ).also { it.migrate() }
            repo = BudgetUsageRepo(pg.db)
            // teams for FK integrity (budget_usage.team_id → teams.id)
            TeamRepo(pg.db).upsertAll(
                listOf(
                    Team("race", "race/"),
                    Team("hardt", "hardt/"),
                    Team("softt", "softt/"),
                    Team("mwt", "mwt/"),
                    Team("cacht", "cacht/"),
                    Team("montht", "montht/"),
                ),
            )
        }
        afterSpec {
            pg.close()
            pgc.stop()
        }

        fun budget(
            team: String,
            cost: Double,
            cached: Boolean = false,
        ) = Settle(
            keyId = "vk_x",
            teamId = team,
            costCenter = null,
            requestedModel = "m",
            servedProvider = "openai",
            servedModel = "m",
            fallbackFrom = null,
            strippedParams = emptyList(),
            usage = Usage(10, 5),
            costUsd = cost,
            estimated = false,
            cached = cached,
            ttfbMs = null,
            durationMs = 1,
            traceId = null,
        )

        "50 concurrent settles sum exactly (atomic upsert-add, no race)" {
            (1..50).toList().parallelStream().forEach {
                repo.addUsage("race", july, java.math.BigDecimal("0.100000"), 10, 5)
            }
            repo.usedUsd("race", july) shouldBeExactly 5.0 // 50 × 0.10
            // token counters tracked alongside
            pg.db.getDataSource().connection.use { c ->
                c.createStatement().use { st ->
                    st
                        .executeQuery("SELECT used_tokens_in, used_tokens_out FROM budget_usage WHERE team_id='race'")
                        .use { rs ->
                            rs.next()
                            rs.getLong(1) shouldBe 500L // 50 × 10
                            rs.getLong(2) shouldBe 250L // 50 × 5
                        }
                }
            }
        }

        "hard budget at 100% blocks; soft admits but the request passes" {
            val svc = BudgetService(repo, mapOf("hardt" to BudgetDef("b", 100.0, "hard")), nowMonth = { july })
            svc.precheck("hardt", null).allowed shouldBe true // nothing spent yet
            repo.addUsage("hardt", july, java.math.BigDecimal("100.0"), 0, 0)
            val blocked = svc.precheck("hardt", null)
            blocked.allowed shouldBe false
            blocked.reason shouldBe "monthly budget exceeded"

            val soft = BudgetService(repo, mapOf("softt" to BudgetDef("b", 100.0, "soft")), nowMonth = { july })
            repo.addUsage("softt", july, java.math.BigDecimal("150.0"), 0, 0) // 150% of cap
            soft.precheck("softt", null).allowed shouldBe true // soft never blocks (D-6)
        }

        "min-wins: a per-key budget override below the team cap is what binds" {
            val svc = BudgetService(repo, mapOf("mwt" to BudgetDef("b", 100.0, "hard")), nowMonth = { july })
            repo.addUsage("mwt", july, java.math.BigDecimal("60.0"), 0, 0) // 60 spent
            svc.precheck("mwt", keyBudgetOverride = null).allowed shouldBe true // under the 100 team cap
            svc.precheck("mwt", keyBudgetOverride = 50.0).allowed shouldBe false // over the 50 key cap
        }

        "a cached settle is a no-op (never charged)" {
            val svc = BudgetService(repo, mapOf("cacht" to BudgetDef("b", 100.0, "hard")), nowMonth = { july })
            svc.settle(budget("cacht", cost = 9.99, cached = true))
            repo.usedUsd("cacht", july) shouldBeExactly 0.0
            svc.settle(budget("cacht", cost = 9.99, cached = false))
            repo.usedUsd("cacht", july) shouldBeExactly 9.99
        }

        "the month is the row key — a new UTC month starts a fresh counter" {
            val august = LocalDate.of(2026, 8, 1)
            repo.addUsage("montht", july, java.math.BigDecimal("7.0"), 0, 0)
            repo.addUsage("montht", august, java.math.BigDecimal("3.0"), 0, 0)
            repo.usedUsd("montht", july) shouldBeExactly 7.0
            repo.usedUsd("montht", august) shouldBeExactly 3.0
        }
    })
