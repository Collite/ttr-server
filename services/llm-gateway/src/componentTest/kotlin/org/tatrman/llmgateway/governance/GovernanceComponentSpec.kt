// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.governance

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.tatrman.llmgateway.auth.PgKeyValidator
import org.tatrman.llmgateway.config.Team
import org.tatrman.llmgateway.store.Pg
import org.testcontainers.containers.PostgreSQLContainer

/**
 * LG-P4·S1 — the PG-backed governance domain end to end (Testcontainers). Covers T2 (V2 applies additively
 * over V1), T1 (seeded-import idempotency; validation of unknown/valid/revoked with the ≤30 s cache driven
 * by an injected clock; throttled last_used_at) and T5 (issuance returns plaintext once + persists the row).
 * One container serves the whole spec; a `golem` team is upserted first for FK integrity.
 */
class GovernanceComponentSpec :
    StringSpec({

        val pgc =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("llmgateway")
                .withUsername("tatrman")
                .withPassword("tatrman")

        lateinit var pg: Pg
        lateinit var teams: TeamRepo
        lateinit var keys: VirtualKeyRepo
        lateinit var service: KeyService

        beforeSpec {
            pgc.start()
            val cfg =
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
                )
            pg = Pg.fromConfig(cfg).also { it.migrate() }
            teams = TeamRepo(pg.db)
            keys = VirtualKeyRepo(pg.db)
            service = KeyService(keys, teams)
            teams.upsertAll(listOf(Team(id = "golem", costCenterPrefix = "golem/")))
        }
        afterSpec {
            pg.close()
            pgc.stop()
        }

        // ── T2: V2 applies additively over V1 ───────────────────────────────────────────────────────
        "V2 creates virtual_keys with key_hash and leaves the 1.x prompt_logs table untouched" {
            pg.db.getDataSource().connection.use { c ->
                c.createStatement().use { st ->
                    st
                        .executeQuery(
                            "SELECT to_regclass('public.virtual_keys') IS NOT NULL," +
                                " to_regclass('public.teams') IS NOT NULL," +
                                " to_regclass('public.budget_usage') IS NOT NULL," +
                                " to_regclass('public.prompt_logs') IS NOT NULL",
                        ).use { rs ->
                            rs.next()
                            rs.getBoolean(1) shouldBe true
                            rs.getBoolean(2) shouldBe true
                            rs.getBoolean(3) shouldBe true
                            rs.getBoolean(4) shouldBe true // 1.x table survived the additive migration
                        }
                    st
                        .executeQuery(
                            "SELECT count(*) FROM information_schema.columns" +
                                " WHERE table_name='virtual_keys' AND column_name='key_hash'",
                        ).use { rs ->
                            rs.next()
                            rs.getInt(1) shouldBe 1
                        }
                }
            }
        }

        // ── T1: seeded import idempotency (G-3) ─────────────────────────────────────────────────────
        "seeded import is idempotent, marks seeded=true, and resolves to its team" {
            val hash = KeyMint.hash(KeyMint.generate())
            val id1 = keys.upsertSeeded("golem", "seed-alpha", hash)
            val id2 = keys.upsertSeeded("golem", "seed-alpha", hash) // re-boot: no-op
            id2 shouldBe id1
            val row = keys.findByHash(hash).shouldNotBeNull()
            row.seeded shouldBe true
            row.teamId shouldBe "golem"
            keys.listByTeam("golem").count { it.keyHash == hash } shouldBe 1
        }

        // ── T1/T4: validation — unknown, valid-now, revoked-after-TTL; throttled touch ──────────────
        "unknown hash is invalid; an issued key validates immediately; revocation takes effect within the TTL" {
            var clock = 1_000_000L
            val validator =
                PgKeyValidator(keys, setOf("golem"), ttlMs = 30_000, touchThrottleMs = 60_000, nowMs = { clock })

            validator.validate("ttrk-does-not-exist-000000000000000000000000").shouldBeNull()

            val issued = service.issueKey("golem", "live-key")
            issued.plaintext shouldMatch KeyMint.FORMAT
            // negative results are NOT cached → a just-issued key works on first presentation
            val p = validator.validate(issued.plaintext).shouldNotBeNull()
            p.team shouldBe "golem"
            p.keyId shouldBe issued.row.id

            service.revoke(issued.row.id) shouldBe true
            // still cached (TTL not elapsed) → stale-valid, bounded by the TTL
            validator.validate(issued.plaintext).shouldNotBeNull()
            clock += 30_001 // TTL elapses → re-read from PG → revoked → invalid
            validator.validate(issued.plaintext).shouldBeNull()
        }

        "last_used_at is written once then throttled within the window" {
            var clock = 5_000_000L
            val validator =
                PgKeyValidator(keys, setOf("golem"), ttlMs = 30_000, touchThrottleMs = 60_000, nowMs = { clock })
            val issued = service.issueKey("golem", "touch-key")

            validator.validate(issued.plaintext).shouldNotBeNull()
            val firstTouch = keys.findByHash(KeyMint.hash(issued.plaintext))!!.lastUsedAt.shouldNotBeNull()

            clock += 1_000 // within throttle window → no second write
            validator.validate(issued.plaintext).shouldNotBeNull()
            keys.findByHash(KeyMint.hash(issued.plaintext))!!.lastUsedAt shouldBe firstTouch
        }

        // ── T5: issuance persists a row and returns the plaintext exactly once ──────────────────────
        "issueKey persists a virtual_keys row and lists it under its team" {
            val before = service.list("golem").size
            val issued = service.issueKey("golem", "issued-key")
            issued.row.seeded shouldBe false
            keys.findByHash(KeyMint.hash(issued.plaintext)).shouldNotBeNull()
            service.list("golem").size shouldBe before + 1
        }
    })
