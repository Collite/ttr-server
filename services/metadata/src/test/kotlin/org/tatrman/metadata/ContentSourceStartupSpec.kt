// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata

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
        val refusals =
            listOf(
                StartupRefusal("blank URI", listOf("remote-uri="), "github-model (no remote-uri)"),
                StartupRefusal("whitespace URI", listOf("remote-uri=   "), "github-model (no remote-uri)"),
                StartupRefusal("disabled sources", listOf("enabled=false")),
                StartupRefusal("blank branch", listOf("branch="), "github-model (blank branch)"),
                StartupRefusal("whitespace branch", listOf("branch=   "), "github-model (blank branch)"),
                StartupRefusal(
                    "unknown kind with credentials",
                    listOf("kind=yaml", "remote-uri=https://review-user:review-password@example.invalid/model.git"),
                    "github-model (unknown kind 'yaml')",
                ),
                StartupRefusal("unknown type", listOf("type=typo"), "github-model (unknown type 'typo')"),
                StartupRefusal(
                    "missing filesystem path",
                    listOf("type=filesystem", "path="),
                    "github-model (filesystem source has no path)",
                ),
                StartupRefusal(
                    "unbuildable fallback",
                    listOf("remote-uri=", "fallback=local-model"),
                    "github-model (unbuildable fallback 'local-model')",
                    listOf("-Dmetadata.sources.local-model.path="),
                ),
                StartupRefusal(
                    "inherited offline demo",
                    listOf("remote-uri="),
                    "github-model (no remote-uri)",
                    contaminatedEnvironment = true,
                ),
            )
        for (refusal in refusals) {
            "production main refuses ${refusal.name} before startup resources" {
                val diagnostic = refusedStartup(refusal)
                diagnostic shouldContain "METADATA_GIT_REMOTE_URI"
                diagnostic shouldContain "METADATA_GIT_BRANCH"
                diagnostic shouldContain "METADATA_GIT_SUBDIR"
                diagnostic shouldContain "METADATA_TTR_ROOT"
                diagnostic shouldContain "METADATA_GIT_FALLBACK"
                diagnostic shouldContain "local-model.enabled"
                if (refusal.reason == null) {
                    diagnostic shouldNotContain "skipped:"
                } else {
                    diagnostic shouldContain "skipped:"
                    diagnostic shouldContain refusal.reason
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
                    eventually(20.seconds) {
                        client.get("/ready").status shouldBe HttpStatusCode.OK
                    }
                    client.get("/model/graph").bodyAsText() shouldContain "db.dbo.recovered"
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

        "a blank git branch uses an explicitly configured local fallback" {
            val root = Files.createTempDirectory("metadata-branch-fallback")
            try {
                Files.writeString(root.resolve("db.ttrm"), "model db\ndef table fallback_content {}")
                val cfg =
                    startupConfig(
                        mapOf(
                            "metadata.sources.github-model.remote-uri" to "file:///unused-metadata-repo",
                            "metadata.sources.github-model.branch" to "   ",
                            "metadata.sources.github-model.fallback" to "local-model",
                            "metadata.sources.local-model.path" to root.toString(),
                        ),
                    )
                promotionTarget(cfg, "github-model") shouldBe null
                testApplication {
                    environment { config = MapApplicationConfig() }
                    application { module(cfg) }
                    client.post("/refresh").status shouldBe HttpStatusCode.OK
                    client.get("/ready").status shouldBe HttpStatusCode.OK
                    client.get("/model/graph").bodyAsText() shouldContain "db.dbo.fallback_content"
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

private data class StartupRefusal(
    val name: String,
    val overrides: List<String>,
    val reason: String? = null,
    val extraOptions: List<String> = emptyList(),
    val contaminatedEnvironment: Boolean = false,
)

/** Run the actual production main; the launcher's environment is deliberately dirty in the R7 case. */
private fun refusedStartup(refusal: StartupRefusal): String {
    val root = Files.createTempDirectory("metadata-refused-startup")
    val output = root.resolve("output.txt")
    val builder =
        ProcessBuilder(
            listOf(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dmetadata.sources.github-model.remote-uri=file:///unused-metadata-repo",
                "-Dmetadata.sources.second-model.enabled=false",
                "-Dmetadata.sources.local-model.enabled=false",
                // Built-in stock roles are still present; strict=false must not make them content.
                "-Dmetadata.strict-swap=false",
                "-Dtelemetry.enabled=true",
                "-Dktor.deployment.port=0",
                "-Dgrpc.port=0",
            ) + refusal.overrides.map { "-Dmetadata.sources.github-model.$it" } + refusal.extraOptions +
                listOf("-cp", System.getProperty("java.class.path"), "org.tatrman.metadata.ApplicationKt"),
        ).redirectErrorStream(true).redirectOutput(output.toFile())
    if (refusal.contaminatedEnvironment) {
        val model = Files.createDirectories(root.resolve("model"))
        Files.writeString(model.resolve("db.ttrm"), "model db\ndef table offline {}")
        builder.environment().putAll(
            mapOf(
                "METADATA_GIT_FALLBACK" to "local-model",
                "METADATA_TTR_ROOT" to model.toString(),
                "JAVA_TOOL_OPTIONS" to "-Dmetadata.sources.github-model.fallback=local-model",
            ),
        )
    }
    builder.environment().keys.removeIf { it.startsWith("METADATA_") || it == "JAVA_TOOL_OPTIONS" }
    val process = builder.start()
    try {
        val exited = process.waitFor(20, TimeUnit.SECONDS)
        val text = Files.readString(output)
        return withClue(text) {
            exited shouldBe true
            process.exitValue() shouldNotBe 0
            text shouldNotContain "review-password"
            text shouldNotContain "Exception in thread"
            // Telemetry and CN messages prove ordering (review-188 mutation M2). Listener
            // assertions also guard against starting either serving surface before refusal.
            text shouldNotContain "[OTEL-CONFIG]"
            text shouldNotContain "cn candidates:"
            text shouldNotContain "Responding at"
            text shouldNotContain "Metadata gRPC server started"
            text.lineSequence().single { it.contains("no_model_source_configured") }.also {
                it shouldContain "ERROR"
                it shouldContain "org.tatrman.metadata.Application"
            }
        }
    } finally {
        if (process.isAlive) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        root.toFile().deleteRecursively()
    }
}
