// SPDX-License-Identifier: Apache-2.0
package org.tatrman.health.content

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.tatrman.health.config.ConfigLoader
import org.tatrman.health.config.StatusConfig
import java.time.Instant

/**
 * Each fixture (`src/test/resources/status`, the reference estate's real answers) read through the
 * `status` block the bundled configuration gives that technology — so the test fails when a service
 * changes its status shape, or the configuration stops matching it, before the landing shows a row
 * with nothing in it.
 */
class StatusFixturesTest :
    StringSpec({

        val statuses = ConfigLoader.load().technologies.mapValues { it.value.status }

        fun fixture(name: String): JsonElement =
            Json.parseToJsonElement(
                requireNotNull(javaClass.getResource("/status/$name.json")) { "no fixture $name" }.readText(),
            )

        fun read(
            technology: String,
            fixture: String = technology,
        ): Content {
            val config: StatusConfig = requireNotNull(statuses[technology]) { "$technology has no status block" }
            val adapter = requireNotNull(Adapters.byName[config.adapter]) { "no adapter ${config.adapter}" }
            return adapter.read(fixture(fixture), config)
        }

        fun Content.figures() = summary.associate { it.k to it.v }

        val commit = "3d54d5e8327902f38e996d9e895507fb6d8622f5"

        "metadata: the model source's commit and load time, objects and queries, one row per source" {
            val content = read("metadata")

            content.ok shouldBe true
            content.commit shouldBe commit
            content.at shouldBe "2026-10-23T06:40:55.951601037Z"
            content.figures() shouldBe mapOf("objects" to "1101", "queries" to "20")
            content.sources!!.map { it.name to it.ok } shouldContainExactly listOf("github-model" to true)
            content.sources!!.single().note!! shouldContain "Collite/ttr-demo.git"
        }

        "metadata with two sources: a row each; one that failed its refresh makes the content not ok" {
            val body =
                Json.parseToJsonElement(
                    """
                    {"ready":true,"model_loaded":true,"object_count":1500,"sources":[
                      {"id":"github-model","source":"git https://github.com/Collite/ttr-demo.git (branch master, sub-path model)",
                       "loaded":true,"version":"$commit","object_count":630,"last_success_at":"2026-10-23T06:40:55Z","consecutive_failures":0},
                      {"id":"invest-model","source":"git https://example.invalid/invest.git (branch master)",
                       "loaded":true,"serving_fallback":true,"version":"0ac1e2f","object_count":400,
                       "last_success_at":"2026-10-22T06:00:00Z","consecutive_failures":3,"last_error":"auth failed for https://…"}
                    ]}
                    """.trimIndent(),
                )

            val content = MetadataAdapter.read(body, statuses.getValue("metadata")!!)

            content.ok shouldBe false
            content.commit shouldBe commit
            content.at shouldBe "2026-10-23T06:40:55Z"
            content.sources!!.map { it.name to it.ok } shouldContainExactly
                listOf("github-model" to true, "invest-model" to false)
            // The git client's own error (hosts, credentials) never reaches the page.
            content.sources!![1].note shouldBe "the last refresh failed: see the metadata log"
            content.note shouldBe "invest-model: the last load failed · invest-model: serving the fallback model"
        }

        "translate and validate: the commit out of the version string, the same one metadata serves" {
            read("translate").commit shouldBe commit
            read("validate").commit shouldBe commit
            ContentService
                .summarize(
                    mapOf(
                        "metadata" to read("metadata"),
                        "translate" to read("translate"),
                        "validate" to read("validate"),
                    ),
                ).agree shouldBe true
        }

        "translate: when it refreshed and what the model holds" {
            val content = read("translate")

            content.ok shouldBe true
            content.at shouldBe Instant.ofEpochMilli(1792736489838).toString()
            content.figures() shouldBe mapOf("entities" to "38", "relations" to "81", "queries" to "20")
        }

        "translate on its boot fixture is not ok and names no commit" {
            val content =
                GenericJson.read(
                    Json.parseToJsonElement("""{"model_version":"boot-fixture-v0","er_entity_count":0}"""),
                    statuses.getValue("translate")!!,
                )

            content.ok shouldBe false
            content.commit.shouldBeNull()
            content.note shouldBe "boot fixture served: no model snapshot loaded yet"
        }

        "validate: ready, one sub-row per dependency" {
            val content = read("validate")

            content.ok shouldBe true
            content.children shouldBe listOf(ContentChild("metadata", null, "ready"))
            content.figures() shouldBe mapOf("maxRows" to "200")
        }

        "query: the worlds it serves, runs and cached plans, the Flight SQL statements" {
            read("query").figures() shouldBe
                mapOf(
                    "worlds" to "hartland_cz · hartland_us",
                    "activeRuns" to "0",
                    "cachedPlans" to "2",
                    "flightStatements" to "10",
                )
        }

        "dispatch and the workers: workers, connections, sessions" {
            read("dispatch").figures() shouldBe
                mapOf("workers" to "3", "knownWorkers" to "3", "connection" to "pg-hartland-cz")
            read("worker-postgres", "postgres").figures() shouldBe
                mapOf("connections" to "pg-hartland-cz · pg-hartland-us · pg-tpcds", "activeQueries" to "0")
            // No connection configured: the empty list is no figure, not an empty label.
            read("worker-mssql", "mssql").figures() shouldBe mapOf("activeQueries" to "0")
            read("worker-polars", "polars").figures() shouldBe mapOf("sessions" to "0", "dataframes" to "0")
        }

        "query-mcp, charon, chrono, resolver: what each reports" {
            read("query-mcp").figures() shouldBe mapOf("tools" to "query · compile", "activeRequests" to "0")
            read("charon").figures()["connections"] shouldBe "0"
            read("chrono").figures() shouldBe mapOf("lexiconTerms" to "59", "llm" to "false")
            read("resolver").figures() shouldBe mapOf("llm" to "false")
        }

        "nlp: languages, engines and capabilities from its /version" {
            val content = read("nlp", "nlp-version")

            content.ok shouldBe true
            content.figures() shouldBe
                mapOf(
                    "languages" to "cs · en",
                    "engines" to "morphodita · stanza · nametag3 · langid",
                    "capabilities" to "14",
                )
        }

        "identity: the directory's user count from /ready" {
            read("identity", "identity-ready").figures() shouldBe mapOf("users" to "5")
        }

        "prometheus: targets up of all" {
            val content = read("prometheus", "prometheus-targets")

            content.ok shouldBe true
            content.figures() shouldBe mapOf("targetsUp" to "10", "targets" to "10")
        }

        "external secrets: all synced, the latest refresh" {
            val content = read("external-secrets", "k8s-externalsecrets")

            content.ok shouldBe true
            content.figures()["synced"] shouldBe content.figures()["secrets"]
        }

        "validate on its boot fixture names no commit: not ok, and the summary does not agree" {
            val boot =
                GenericJson.read(
                    Json.parseToJsonElement(
                        """{"ready":true,"metadata_version":"boot-fixture-v0","dependencies":{"metadata":true}}""",
                    ),
                    statuses.getValue("validate")!!,
                )

            boot.ok shouldBe false
            boot.note shouldBe GenericJson.NO_COMMIT
            ContentService
                .summarize(
                    mapOf("metadata" to read("metadata"), "validate" to boot),
                    setOf("metadata", "validate"),
                ).agree shouldBe
                false
        }

        "prometheus: a target not scraped yet is pending, not down" {
            val body =
                Json.parseToJsonElement(
                    """{"data":{"activeTargets":[{"labels":{"job":"a"},"health":"up"},{"labels":{"job":"b"},"health":"unknown"}]}}""",
                )

            val content = PrometheusTargetsAdapter.read(body, statuses.getValue("prometheus")!!)
            content.ok shouldBe true
            content.figures() shouldBe mapOf("targetsUp" to "1", "targets" to "2", "pending" to "1")

            val down =
                Json.parseToJsonElement("""{"data":{"activeTargets":[{"labels":{"job":"b"},"health":"down"}]}}""")
            PrometheusTargetsAdapter.read(down, statuses.getValue("prometheus")!!).note shouldBe "down: b"
        }

        "a named adapter keeps the block's own configured figures and note" {
            val config =
                org.tatrman.health.config.StatusConfig(
                    url = "k8s:/apis/external-secrets.io/v1/externalsecrets",
                    adapter = "k8s-externalsecrets",
                    note = "kind",
                    summary =
                        listOf(
                            org.tatrman.health.config
                                .FieldConfig("kind", "kind", "text"),
                        ),
                )

            val content = ExternalSecretsAdapter.read(fixture("k8s-externalsecrets"), config)
            content.figures()["kind"] shouldBe "ExternalSecretList"
            content.note shouldBe "ExternalSecretList"
        }

        "certificates: one already past its expiry says expired, not 'expires within'" {
            val late = CertificatesAdapter.read(fixture("k8s-certificates"), Instant.parse("2026-12-20T08:00:00Z"))

            late.ok shouldBe false
            late.note shouldBe "expired: auth/hartland-root-ca, gateway/gateway-wildcard"
        }

        "certificates: ready, the nearest expiry and which one; within two weeks of it is not ok" {
            val body = fixture("k8s-certificates")

            val fine = CertificatesAdapter.read(body, Instant.parse("2026-10-23T08:00:00Z"))
            fine.ok shouldBe true
            fine.figures() shouldBe
                mapOf(
                    "ready" to "3",
                    "certificates" to "3",
                    "nearestExpiry" to "2026-12-16T09:09:31Z",
                    "expiresFirst" to "auth/hartland-root-ca",
                )

            val late = CertificatesAdapter.read(body, Instant.parse("2026-12-10T08:00:00Z"))
            late.ok shouldBe false
            late.note!! shouldContain "auth/hartland-root-ca expires within 14 days"
        }
    })
