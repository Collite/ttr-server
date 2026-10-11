// SPDX-License-Identifier: Apache-2.0
package org.tatrman.worker.mssql.connection

import com.typesafe.config.ConfigFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class ConnectionPoolManagerSpec :
    StringSpec({
        "fromConfig parses connections block" {
            val config =
                ConfigFactory.parseString(
                    """
                    connections {
                      mssql-finance {
                        host = "fin.example"
                        port = 1433
                        database = "ERP_FIN"
                        username = "u"
                        password = "p"
                        max-pool-size = 5
                      }
                      mssql-crm {
                        host = "crm.example"
                        database = "ERP_CRM"
                        username = "u2"
                        password = "p2"
                      }
                    }
                    """.trimIndent(),
                )
            val mgr = ConnectionPoolManager.fromConfig(config)
            mgr.supportedConnections shouldHaveSize 2
            mgr.supportedConnections shouldContain "mssql-finance"
            mgr.supportedConnections shouldContain "mssql-crm"
        }

        "fromConfig handles a missing connections block as empty" {
            val mgr = ConnectionPoolManager.fromConfig(ConfigFactory.empty())
            mgr.supportedConnections shouldBe emptySet()
        }

        "acquire on unknown connection_id throws UnknownConnectionException" {
            val mgr = ConnectionPoolManager(emptyMap())
            shouldThrow<ConnectionPoolManager.UnknownConnectionException> {
                mgr.acquire("nope")
            }.connectionId shouldBe "nope"
        }

        "ConnectionConfig.fromConfig builds a SQLServer JDBC URL by default" {
            val cfg =
                ConfigFactory.parseString(
                    """
                    host = "h.example"
                    port = 1500
                    database = "DB1"
                    username = "u"
                    password = "p"
                    """.trimIndent(),
                )
            val parsed = ConnectionConfig.fromConfig("id1", cfg)
            parsed.jdbcUrl shouldContain "jdbc:sqlserver://h.example:1500"
            parsed.jdbcUrl shouldContain "databaseName=DB1"
        }

        "ConnectionConfig.fromConfig captures database + defaultSchema for capability advertisement" {
            // Issue #57 Phase B — the worker advertises database/default-schema per connection
            // via GetCapabilities so the dispatcher can concretize logical TableScan qnames.
            val cfg =
                ConfigFactory.parseString(
                    """
                    host = "h.example"
                    port = 1433
                    database = "tatrman"
                    default-schema = "dbo"
                    username = "u"
                    password = "p"
                    """.trimIndent(),
                )
            val parsed = ConnectionConfig.fromConfig("mssql-default", cfg)
            parsed.database shouldBe "tatrman"
            parsed.defaultSchema shouldBe "dbo"
        }

        "ConnectionConfig.fromConfig defaults defaultSchema to 'dbo' when absent" {
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
            parsed.defaultSchema shouldBe "dbo"
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
            byId["a"]!!.defaultSchema shouldBe "dbo"
            byId["b"]!!.database shouldBe "B_DB"
            byId["b"]!!.defaultSchema shouldBe "alt"
        }

        "ConnectionConfig.fromConfig honours an explicit jdbc-url override" {
            val cfg =
                ConfigFactory.parseString(
                    """
                    jdbc-url = "jdbc:sqlserver://override.example:1433;databaseName=O"
                    host = "ignored"
                    database = "O"
                    username = "u"
                    password = "p"
                    """.trimIndent(),
                )
            val parsed = ConnectionConfig.fromConfig("id1", cfg)
            parsed.jdbcUrl shouldBe "jdbc:sqlserver://override.example:1433;databaseName=O"
            parsed.database shouldBe "O"
        }

        // Issue #57 Phase B — silent drift between `database` (advertised) and
        // `jdbc-url`'s databaseName= (actually connected) would cause the dispatcher's
        // qname rewrite to target a different physical DB than the worker serves.
        "ConnectionConfig.fromConfig rejects a jdbc-url whose databaseName= differs from the database field" {
            val cfg =
                ConfigFactory.parseString(
                    """
                    jdbc-url = "jdbc:sqlserver://h:1433;databaseName=ActualDb"
                    host = "ignored"
                    database = "MismatchedDb"
                    username = "u"
                    password = "p"
                    """.trimIndent(),
                )
            val ex = shouldThrow<IllegalArgumentException> { ConnectionConfig.fromConfig("id1", cfg) }
            ex.message!! shouldContain "MismatchedDb"
        }

        "ConnectionConfig.fromConfig allows a jdbc-url without databaseName= (driver default)" {
            val cfg =
                ConfigFactory.parseString(
                    """
                    jdbc-url = "jdbc:sqlserver://h:1433;encrypt=false"
                    host = "ignored"
                    database = "Any"
                    username = "u"
                    password = "p"
                    """.trimIndent(),
                )
            val parsed = ConnectionConfig.fromConfig("id1", cfg)
            parsed.database shouldBe "Any"
        }

        "probe of an unknown connection_id records connected=false with a clear error" {
            val mgr = ConnectionPoolManager(emptyMap())
            val r = mgr.probe("never-configured")
            r.connected shouldBe false
            r.lastError shouldContain "unknown"
            mgr.lastProbe("never-configured")?.connected shouldBe false
        }

        "probe against an unreachable host records connected=false and caches the error" {
            // No DB at this host:port — the JDBC driver will fail to connect inside the 5s
            // probe timeout. We only assert the connected=false + lastError-not-empty shape;
            // the exact error text varies across JDBC driver versions.
            val mgr =
                ConnectionPoolManager(
                    mapOf(
                        "unreachable" to
                            ConnectionConfig(
                                id = "unreachable",
                                jdbcUrl = "jdbc:sqlserver://127.0.0.1:1;databaseName=X;loginTimeout=2",
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
