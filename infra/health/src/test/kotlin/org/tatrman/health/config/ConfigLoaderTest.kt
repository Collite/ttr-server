// SPDX-License-Identifier: Apache-2.0
package org.tatrman.health.config

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.tatrman.health.service.HealthCheckService

class ConfigLoaderTest :
    StringSpec({

        "bundled config parses and exposes the kantheon + fabric dashboard technologies" {
            val config = ConfigLoader.load()

            config.technologies.size shouldBeGreaterThan 30
            // A representative slice across the shared-cluster estate and the kantheon
            // constellation (agents, platform services, workers, MCP wrappers, technical
            // wave), plus the two host-based checker types (native HTTP, TCP).
            config.technologies.keys shouldContainAll
                setOf(
                    // shared cluster estate
                    "seaweed",
                    "kantheon-postgres",
                    "keycloak-postgres",
                    "neo4j",
                    "keycloak",
                    "grafana",
                    "loki",
                    "prometheus",
                    "tempo",
                    "nats",
                    // kantheon constellation
                    "iris-bff",
                    "themis",
                    "veles",
                    "query",
                    "fuzzy",
                    "nlp",
                    "validate",
                    "dispatch",
                    "mssql",
                    "capabilities-mcp",
                    "query-mcp",
                    // technical wave (infra/)
                    "whois",
                    "backstage",
                )
        }

        "no legacy predecessor-platform / erp-sql check targets survive the re-point" {
            val keys = ConfigLoader.load().technologies.keys
            // Stage 5.2 T3: targets re-pointed to the kantheon estate; the legacy line is gone.
            keys.none { it.startsWith("sql-") } shouldBe true
            // SV-P0 S4: `fuzzy-mcp` and `llm-gateway` were removed from this legacy guard —
            // the J-v2 service renames make these functional names our OWN
            // spine services' health-check targets, colliding with the predecessor platform's names.
            keys shouldNotContainAnyOf
                setOf("metadata", "fuzzy-matcher", "meta-mcp", "erp-data-mcp", "erp-sql")
        }

        "Postgres checks are TCP and Keycloak hits its management port" {
            val config = ConfigLoader.load()

            config.technologies.getValue("kantheon-postgres").type shouldBe "tcp"
            config.technologies.getValue("kantheon-postgres").port shouldBe 5432
            config.technologies.getValue("keycloak").type shouldBe "native"
            config.technologies.getValue("keycloak").url shouldBe "http://auth-keycloak-keycloakx-http.auth:9000"
        }

        "every technology builds a checker without throwing" {
            // Exercises HealthCheckService.createChecker for every entry — catches a
            // missing host/port (tcp) or url (native) in the config before deploy.
            val service = HealthCheckService(ConfigLoader.load())
            service.getSupportedTechnologies().size shouldBeGreaterThan 30
        }

        "env/system-property overrides win over the bundled defaults" {
            // `${?HEALTH_*}` substitutions resolve against the full config stack, which
            // includes system properties — proves the config is retunable without a rebuild.
            // (A string-valued port must still parse to Int via the config transformer.)
            System.setProperty("HEALTH_REDIS_HOST", "redis-override.data")
            System.setProperty("HEALTH_REDIS_PORT", "6380")
            ConfigFactory.invalidateCaches()
            try {
                val redis = ConfigLoader.load().technologies.getValue("redis")
                redis.host shouldBe "redis-override.data"
                redis.port shouldBe 6380
            } finally {
                System.clearProperty("HEALTH_REDIS_HOST")
                System.clearProperty("HEALTH_REDIS_PORT")
                ConfigFactory.invalidateCaches()
            }
        }
    })
