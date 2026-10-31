// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** MW-P1: distinguish missing source configuration from a configured source awaiting content. */
class ContentSourceStartupSpec :
    StringSpec({
        "production main exits non-zero without a content source, even with built-in stock roles" {
            // Cover both an omitted git URI and explicitly disabled sources. The built-in stock
            // source is present in both cases; strict=false must not turn it into a content model.
            for (sourceOverride in listOf("remote-uri=", "enabled=false")) {
                val output = Files.createTempFile("metadata-no-source", ".txt")
                val process =
                    ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Dmetadata.sources.github-model.$sourceOverride",
                        "-Dmetadata.sources.second-model.enabled=false",
                        "-Dmetadata.sources.local-model.enabled=false",
                        "-Dmetadata.strict-swap=false",
                        "-Dtelemetry.enabled=false",
                        "-Dktor.deployment.port=0",
                        "-Dgrpc.port=0",
                        "-cp",
                        System.getProperty("java.class.path"),
                        "org.tatrman.metadata.ApplicationKt",
                    ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
                try {
                    val exited = process.waitFor(20, TimeUnit.SECONDS)
                    withClue(Files.readString(output)) {
                        exited shouldBe true
                        process.exitValue() shouldNotBe 0
                        val diagnostic = Files.readString(output)
                        diagnostic shouldContain "no_model_source_configured"
                        diagnostic shouldContain "METADATA_GIT_REMOTE_URI"
                        diagnostic shouldContain "METADATA_GIT_BRANCH"
                        diagnostic shouldContain "METADATA_GIT_SUBDIR"
                        diagnostic shouldContain "METADATA_TTR_ROOT"
                        diagnostic shouldNotContain "[OTEL-CONFIG]"
                        diagnostic shouldNotContain "cn candidates:"
                        diagnostic shouldNotContain "Responding at"
                    }
                } finally {
                    if (process.isAlive) {
                        process.destroyForcibly()
                        process.waitFor(5, TimeUnit.SECONDS)
                    }
                    Files.deleteIfExists(output)
                }
            }
        }

        "a configured empty filesystem starts unready and bootstrap retry publishes repaired content" {
            val root = Files.createTempDirectory("metadata-empty-source")
            try {
                val cfg =
                    startupConfig(
                        mapOf(
                            "metadata.sources.local-model.enabled" to true,
                            "metadata.sources.local-model.path" to root.toString(),
                        ),
                    )
                testApplication {
                    environment { config = MapApplicationConfig() }
                    application { module(cfg) }
                    // Serialize with the initial load; empty content is a load failure, not a
                    // configuration error, and must not prevent the server from starting.
                    client.post("/refresh").status shouldBe HttpStatusCode.OK
                    client.get("/ready").status shouldBe HttpStatusCode.ServiceUnavailable
                    client.get("/status").bodyAsText() shouldContain "\"model_loaded\":false"
                    Files.writeString(root.resolve("db.ttrm"), "model db\ndef table recovered {}")
                    // No explicit refresh after repair: this exercises the real bootstrap retry
                    // even with the periodic refresh scheduler disabled.
                    eventually(10.seconds) {
                        client.get("/ready").status shouldBe HttpStatusCode.OK
                    }
                    client.get("/model/graph").bodyAsText() shouldContain "db.dbo.recovered"
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

        "an unreachable configured git source starts and exposes its load failure" {
            val root = Files.createTempDirectory("metadata-unreachable-source")
            try {
                val cfg =
                    startupConfig(
                        mapOf(
                            "metadata.sources.github-model.remote-uri" to root.resolve("absent.git").toUri().toString(),
                            "metadata.sources.github-model.local-checkout-dir" to root.resolve("checkout").toString(),
                        ),
                    )
                testApplication {
                    environment { config = MapApplicationConfig() }
                    application { module(cfg) }
                    client.post("/refresh").status shouldBe HttpStatusCode.OK
                    client.get("/ready").status shouldBe HttpStatusCode.ServiceUnavailable
                    val status = client.get("/status").bodyAsText()
                    status shouldContain "\"model_loaded\":false"
                    status shouldContain "github-model"
                    status shouldContain "absent.git"
                    status shouldContain "\"loaded\":false"
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    })

private fun startupConfig(overrides: Map<String, Any>): Config =
    ConfigFactory
        .parseMap(overrides)
        .withFallback(
            ConfigFactory.parseMap(
                mapOf(
                    "metadata.sources.github-model.remote-uri" to "",
                    "metadata.sources.second-model.enabled" to false,
                    "metadata.refresh-scheduler.interval-seconds" to 0,
                    "grpc.port" to 0,
                    "telemetry.enabled" to false,
                ),
            ),
        ).withFallback(ConfigFactory.load())
        .resolve()
