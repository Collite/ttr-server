// SPDX-License-Identifier: Apache-2.0
package org.tatrman.worker.postgres.connection

import com.typesafe.config.ConfigFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class ConnectionPoolManagerSpec :
    StringSpec({
        "AU writer connections are inert without host env and activate read-write with their declared schemas" {
            val names = listOf("cz-writer", "us-writer", "automation-cz", "automation-us").map { "pg-hartland-$it" }
            val raw = ConfigFactory.parseResources("application.conf")
            val absent = raw.resolve()
            ConnectionPoolManager.fromConfig(absent).use { mgr ->
                names.forEach { (it in mgr.supportedConnections) shouldBe false }
            }
            val activated =
                ConfigFactory
                    .parseString(
                        """
                        POSTGRES_PG_HARTLAND_CZ_WRITER_HOST = "writer.test"
                        POSTGRES_PG_HARTLAND_US_WRITER_HOST = "writer.test"
                        POSTGRES_PG_HARTLAND_AUTOMATION_CZ_HOST = "ledger.test"
                        POSTGRES_PG_HARTLAND_AUTOMATION_US_HOST = "ledger.test"
                        """.trimIndent(),
                    ).withFallback(raw)
                    .resolve()
            ConnectionPoolManager.fromConfig(activated).use { mgr ->
                names.forEach {
                    (it in mgr.supportedConnections) shouldBe true
                    mgr.isReadOnly(it) shouldBe false
                }
            }
            val cz = ConnectionConfig.fromConfig("cz", activated.getConfig("connections.pg-hartland-automation-cz"))
            val us = ConnectionConfig.fromConfig("us", activated.getConfig("connections.pg-hartland-automation-us"))
            cz.defaultSchema shouldBe "cz"
            us.defaultSchema shouldBe "us"
            cz.database shouldBe "automation"
            us.database shouldBe "automation"
        }

        "fromConfig parses connections block" {
            val config =
                ConfigFactory.parseString(
                    """
                    connections {
                      pg-midas {
                        host = "midas.example"
                        port = 5432
                        database = "midas"
                        username = "u"
                        password = "p"
                        max-pool-size = 5
                      }
                      pg-other {
                        host = "other.example"
                        database = "other"
                        username = "u2"
                        password = "p2"
                      }
                    }
                    """.trimIndent(),
                )
            val mgr = ConnectionPoolManager.fromConfig(config)
            mgr.supportedConnections shouldHaveSize 2
            mgr.supportedConnections shouldContain "pg-midas"
            mgr.supportedConnections shouldContain "pg-other"
        }

        "fromConfig handles a missing connections block as empty" {
            val mgr = ConnectionPoolManager.fromConfig(ConfigFactory.empty())
            mgr.supportedConnections shouldBe emptySet()
        }

        // Pure-env connections (`host = ${?POSTGRES_PG_<NAME>_HOST}` with no default) are declared in
        // the base conf but activated per deployment — an entry whose host is absent (env unset) or
        // blank is skipped rather than crashing boot in ConnectionConfig.fromConfig's getString("host").
        "fromConfig skips a connection whose host is absent or blank" {
            val config =
                ConfigFactory.parseString(
                    """
                    connections {
                      active { host = "real.example", database = "A", username = "u", password = "p" }
                      unset  { database = "B", username = "u", password = "p" }
                      blank  { host = "", database = "C", username = "u", password = "p" }
                    }
                    """.trimIndent(),
                )
            val mgr = ConnectionPoolManager.fromConfig(config)
            mgr.supportedConnections shouldHaveSize 1
            mgr.supportedConnections shouldContain "active"
        }

        "acquire on unknown connection_id throws UnknownConnectionException" {
            val mgr = ConnectionPoolManager(emptyMap())
            shouldThrow<ConnectionPoolManager.UnknownConnectionException> {
                mgr.acquire("nope")
            }.connectionId shouldBe "nope"
        }

        "ConnectionConfig.fromConfig builds a PostgreSQL JDBC URL by default" {
            val cfg =
                ConfigFactory.parseString(
                    """
                    host = "h.example"
                    port = 5444
                    database = "midas"
                    username = "u"
                    password = "p"
                    """.trimIndent(),
                )
            val parsed = ConnectionConfig.fromConfig("id1", cfg)
            parsed.jdbcUrl shouldBe "jdbc:postgresql://h.example:5444/midas"
        }

        "ConnectionConfig.fromConfig defaults the port to 5432 when absent" {
            val cfg =
                ConfigFactory.parseString(
                    """
                    host = "h.example"
                    database = "midas"
                    username = "u"
                    password = "p"
                    """.trimIndent(),
                )
            val parsed = ConnectionConfig.fromConfig("id1", cfg)
            parsed.jdbcUrl shouldBe "jdbc:postgresql://h.example:5432/midas"
        }

        "ConnectionConfig.fromConfig captures database + defaultSchema for capability advertisement" {
            val cfg =
                ConfigFactory.parseString(
                    """
                    host = "h.example"
                    port = 5432
                    database = "midas"
                    default-schema = "public"
                    username = "u"
                    password = "p"
                    """.trimIndent(),
                )
            val parsed = ConnectionConfig.fromConfig("pg-midas", cfg)
            parsed.database shouldBe "midas"
            parsed.defaultSchema shouldBe "public"
        }

        "ConnectionConfig.fromConfig defaults defaultSchema to 'public' when absent" {
            val cfg =
                ConfigFactory.parseString(
                    """
                    host = "h.example"
                    database = "X"
                    username = "u"
                    password = "p"
                    """.trimIndent(),
                )
            val parsed = ConnectionConfig.fromConfig("id1", cfg)
            parsed.defaultSchema shouldBe "public"
        }

        "ConnectionConfig.fromConfig parses requires-tenant-id (the RLS gate) defaulting to false" {
            val withFlag =
                ConfigFactory.parseString(
                    """
                    host = "h"
                    database = "midas"
                    username = "u"
                    password = "p"
                    requires-tenant-id = true
                    """.trimIndent(),
                )
            ConnectionConfig.fromConfig("pg-midas", withFlag).requiresTenantId shouldBe true

            val withoutFlag =
                ConfigFactory.parseString(
                    """
                    host = "h"
                    database = "midas"
                    username = "u"
                    password = "p"
                    """.trimIndent(),
                )
            ConnectionConfig.fromConfig("pg-midas", withoutFlag).requiresTenantId shouldBe false
        }

        // WS-T2 T5 — the pg-tpcds warehouse profile: read-only, NOT multi-tenant (no RLS envelope).
        "ConnectionConfig.fromConfig parses the pg-tpcds profile: read-only, no tenant gate" {
            val cfg =
                ConfigFactory.parseString(
                    """
                    host = "test-pg.data.svc.cluster.local"
                    port = 5432
                    database = "tpc-ds-1g"
                    username = "tpcds_readonly"
                    password = "secret"
                    read-only = true
                    requires-tenant-id = false
                    """.trimIndent(),
                )
            val parsed = ConnectionConfig.fromConfig("pg-tpcds", cfg)
            parsed.readOnly shouldBe true
            parsed.requiresTenantId shouldBe false
            parsed.database shouldBe "tpc-ds-1g"
            parsed.jdbcUrl shouldBe "jdbc:postgresql://test-pg.data.svc.cluster.local:5432/tpc-ds-1g"
        }

        "ConnectionConfig.fromConfig defaults read-only to true" {
            val cfg =
                ConfigFactory.parseString(
                    """
                    host = "h"
                    database = "midas"
                    username = "u"
                    password = "p"
                    """.trimIndent(),
                )
            ConnectionConfig.fromConfig("pg-midas", cfg).readOnly shouldBe true
        }

        "ConnectionPoolManager.connectionDetails exposes ConnectionConfigs for advertisement" {
            val config =
                ConfigFactory.parseString(
                    """
                    connections {
                      a {
                        host = "h1"
                        database = "A_DB"
                        username = "u"
                        password = "p"
                      }
                      b {
                        host = "h2"
                        database = "B_DB"
                        default-schema = "alt"
                        username = "u"
                        password = "p"
                      }
                    }
                    """.trimIndent(),
                )
            val mgr = ConnectionPoolManager.fromConfig(config)
            val byId = mgr.connectionDetails().associateBy { it.id }
            byId["a"]!!.database shouldBe "A_DB"
            byId["a"]!!.defaultSchema shouldBe "public"
            byId["b"]!!.database shouldBe "B_DB"
            byId["b"]!!.defaultSchema shouldBe "alt"
        }

        "ConnectionConfig.fromConfig honours an explicit jdbc-url override" {
            val cfg =
                ConfigFactory.parseString(
                    """
                    jdbc-url = "jdbc:postgresql://override.example:5432/midas"
                    host = "ignored"
                    database = "midas"
                    username = "u"
                    password = "p"
                    """.trimIndent(),
                )
            val parsed = ConnectionConfig.fromConfig("id1", cfg)
            parsed.jdbcUrl shouldBe "jdbc:postgresql://override.example:5432/midas"
            parsed.database shouldBe "midas"
        }

        // Silent drift between `database` (advertised) and `jdbc-url`'s path database (actually
        // connected) would cause the dispatcher's qname rewrite to target a different physical DB.
        "ConnectionConfig.fromConfig rejects a jdbc-url whose database differs from the database field" {
            val cfg =
                ConfigFactory.parseString(
                    """
                    jdbc-url = "jdbc:postgresql://h:5432/actual_db"
                    host = "ignored"
                    database = "mismatched_db"
                    username = "u"
                    password = "p"
                    """.trimIndent(),
                )
            val ex = shouldThrow<IllegalArgumentException> { ConnectionConfig.fromConfig("id1", cfg) }
            ex.message!! shouldContain "mismatched_db"
        }

        "probe of an unknown connection_id records connected=false with a clear error" {
            val mgr = ConnectionPoolManager(emptyMap())
            val r = mgr.probe("never-configured")
            r.connected shouldBe false
            r.lastError shouldContain "unknown"
            mgr.lastProbe("never-configured")?.connected shouldBe false
        }

        "probe against an unreachable host records connected=false and caches the error" {
            // No DB at this host:port — the JDBC driver will fail to connect inside the probe
            // timeout. We only assert the connected=false + lastError-not-empty shape; the exact
            // error text varies across JDBC driver versions.
            val mgr =
                ConnectionPoolManager(
                    mapOf(
                        "unreachable" to
                            ConnectionConfig(
                                id = "unreachable",
                                jdbcUrl = "jdbc:postgresql://127.0.0.1:1/X?connectTimeout=2",
                                username = "u",
                                password = "p",
                                database = "X",
                            ),
                    ),
                )
            val r = mgr.probe("unreachable")
            r.connected shouldBe false
            r.lastError.isNotEmpty() shouldBe true
            mgr.lastProbe("unreachable")?.connected shouldBe false
        }

        "recordProbeResult stores an injected result without running a real probe" {
            val mgr = ConnectionPoolManager(emptyMap())
            val now = java.time.Instant.now()
            mgr.recordProbeResult(
                ConnectionPoolManager.ProbeResult("id1", connected = true, lastError = "", lastProbed = now),
            )
            mgr.lastProbe("id1")?.connected shouldBe true
            mgr.lastProbe("id1")?.lastProbed shouldBe now
        }
    })
