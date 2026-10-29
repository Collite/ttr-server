// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files

/** Exercises Application's actual startup, source board, HTTP refresh and strict-default wiring. */
class StrictSwapApplicationSpec :
    StringSpec({
        "HTTP refresh refuses partial content, preserves readiness, and exposes repair through production wiring" {
            val root = Files.createTempDirectory("strict-application")
            try {
                Files.writeString(root.resolve("db.ttrm"), "model db\ndef table orders {}")
                val cfg =
                    ConfigFactory
                        .parseMap(
                            mapOf(
                                "metadata.sources.github-model.remote-uri" to "",
                                "metadata.sources.world.type" to "filesystem",
                                "metadata.sources.world.path" to root.toString(),
                                "metadata.refresh-scheduler.interval-seconds" to 0,
                                "grpc.port" to 0,
                                "telemetry.enabled" to false,
                            ),
                        ).withFallback(ConfigFactory.load())
                        .resolve()
                testApplication {
                    environment { config = MapApplicationConfig() }
                    application { module(cfg) }
                    // This waits on the same lock as the initial async load.
                    client.post("/refresh").status shouldBe HttpStatusCode.OK
                    client.get("/ready").status shouldBe HttpStatusCode.OK
                    val before = Json.parseToJsonElement(client.get("/status").bodyAsText()).jsonObject
                    before["strict_swap"]!!.jsonPrimitive.content shouldBe "true"
                    val version = before["model_version"]!!.jsonPrimitive.content
                    Files.writeString(root.resolve("bad.ttrm"), "model db\ndef table broken {")
                    client.post("/refresh").bodyAsText() shouldContain "strict_swap_rejected"
                    client.get("/ready").status shouldBe HttpStatusCode.OK
                    val failed = Json.parseToJsonElement(client.get("/status").bodyAsText()).jsonObject
                    failed["model_version"]!!.jsonPrimitive.content shouldBe version
                    failed.toString() shouldContain "bad.ttrm"
                    failed.toString() shouldContain "\"consecutive_failures\":1"
                    Files.writeString(root.resolve("bad.ttrm"), "model db\ndef table repaired {}")
                    Json
                        .parseToJsonElement(client.post("/refresh").bodyAsText())
                        .jsonObject["status"]!!
                        .jsonPrimitive.content shouldBe "ok"
                    val repaired = client.get("/status").bodyAsText()
                    repaired shouldContain "\"issues\":[]"
                    repaired shouldContain "\"consecutive_failures\":0"
                    client.get("/model/graph").bodyAsText() shouldContain "db.dbo.repaired"
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    })
