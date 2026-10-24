// SPDX-License-Identifier: Apache-2.0
package org.tatrman.health.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.health.content.Adapters
import org.tatrman.health.service.HealthCheckService
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

class ConfigLoaderTest :
    StringSpec({

        "the bundled roster is the reference estate's: agents, the spine, the platform tiers" {
            val config = ConfigLoader.load()

            config.technologies.size shouldBeGreaterThan 50
            config.technologies.keys shouldContainAll
                setOf(
                    // agents and front doors
                    "golem-hartland",
                    "pythia",
                    "iris",
                    "iris-bff",
                    // the model & query path
                    "veles",
                    "translate",
                    "validate",
                    "query",
                    "dispatch",
                    "worker-postgres",
                    // understanding
                    "resolver",
                    "nlp",
                    "fuzzy",
                    "chrono",
                    // platform tiers
                    "keycloak",
                    "identity",
                    "kantheon-postgres",
                    "prometheus",
                    "external-secrets",
                    "cert-manager",
                )
        }

        "no target of a service this estate does not run" {
            val keys = ConfigLoader.load().technologies.keys
            keys.none { it.startsWith("sql-") } shouldBe true
            keys shouldNotContainAnyOf
                setOf("themis", "opensearch", "qdrant", "neo4j", "nats", "whois", "backstage")
        }

        "Postgres checks are TCP on the CNPG primary and Keycloak hits its management port" {
            val config = ConfigLoader.load()

            config.technologies.getValue("kantheon-postgres").type shouldBe "tcp"
            config.technologies.getValue("kantheon-postgres").host shouldBe "postgres-rw.data"
            config.technologies.getValue("kantheon-postgres").port shouldBe 5432
            config.technologies.getValue("keycloak").type shouldBe "native"
            config.technologies.getValue("keycloak").url shouldBe "http://auth-keycloak-keycloakx-http.auth:9000"
        }

        "every technology builds a checker without throwing" {
            // Exercises HealthCheckService.createChecker for every entry — catches a
            // missing host/port (tcp) or url (native) in the config before deploy.
            val service = HealthCheckService(ConfigLoader.load())
            service.getSupportedTechnologies().size shouldBeGreaterThan 50
        }

        "every status block names an adapter that exists" {
            val statuses =
                ConfigLoader.load().technologies.mapNotNull { (k, t) ->
                    t.status?.let {
                        k to
                            it
                    }
                }

            statuses.size shouldBeGreaterThan 10
            statuses.filterNot { (_, status) -> status.adapter in Adapters.byName } shouldBe emptyList()
        }

        "a roster block replaces the bundled technologies; everything else stays" {
            // What the chart's config fragment hands the service: the fragment over the bundled conf.
            val config =
                ConfigLoader.load(
                    withBundled(
                        """
                        health-check-service.roster {
                          technologies {
                            only-one {
                              type = tcp
                              host = "db.data"
                              port = 5432
                              port = ${'$'}{?ROSTER_TEST_UNSET_PORT}
                            }
                          }
                        }
                        """.trimIndent(),
                    ),
                )

            config.technologies.keys shouldBe setOf("only-one")
            config.technologies.getValue("only-one").port shouldBe 5432
            // The bundled `prometheus` block is not part of the roster and survives it.
            config.prometheus.url shouldBe "http://mon-prometheus-server.monitoring"
        }

        "a roster without a technologies block is refused rather than serving the bundled estate's" {
            val fragment =
                "health-check-service.roster { prometheus { url = \"http://p\", timeout = 1, queryInterval = 1 } }"

            shouldThrow<IllegalArgumentException> { ConfigLoader.load(withBundled(fragment)) }.message!! shouldContain
                "no `technologies` block"
        }
    })

/** A config fragment over the bundled `application.conf`, resolved — as `-Dconfig.file` + its include do. */
private fun withBundled(fragment: String): Config =
    ConfigFactory.parseString(fragment).withFallback(ConfigFactory.parseResources("application.conf")).resolve()
