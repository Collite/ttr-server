// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.export

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.tatrman.ttr.metadata.cli.runMetadataCommand
import java.nio.file.Files

/** The CLI comes from the same published grammar bundle as the serving library (no mocked writer). */
class ExportParitySpec :
    StringSpec({
        "HTTP and in-process CLI emit identical graph, definitions and OWL bytes" {
            val registry = xmRegistry()
            testApplication {
                environment { config = MapApplicationConfig() }
                application { routing { metadataExportRoutes(registry) } }
                for ((format, model, profile, name) in listOf(
                    listOf("graph.json", "world", "", "graph.json"),
                    listOf("defs.json", "world", "", "defs.json"),
                    listOf("ttl", "cn", "owl", "cn.ttl"),
                )) {
                    val out = Files.createTempDirectory("xm-parity")
                    try {
                        val args =
                            listOf(
                                "export",
                                XM_ROOT.toString(),
                                "--model",
                                model,
                                "--format",
                                format,
                                "--out",
                                out.toString(),
                            ) +
                                if (profile.isEmpty()) emptyList() else listOf("--profile", profile)
                        val cli = runMetadataCommand(args)
                        cli.exitCode shouldBe 0
                        val response =
                            client.get(
                                "/model/export?model=$model&format=$format" +
                                    if (profile.isEmpty()) "" else "&profile=$profile",
                            )
                        response.status shouldBe HttpStatusCode.OK
                        response.body<ByteArray>().toList() shouldBe Files.readAllBytes(out.resolve(name)).toList()
                        Files.readString(out.resolve(".snapshot")) shouldBe
                            "${registry.readOrError().model.version.value}\n"
                        val golden =
                            when (format) {
                                "ttl" ->
                                    java.nio.file.Path
                                        .of("src/test/resources/xm-goldens/ttl/world-owl.ttl")
                                else ->
                                    java.nio.file.Path
                                        .of("src/test/resources/xm-goldens/$format/world.json")
                            }
                        Files.readString(out.resolve(name)) shouldBe
                            Files.readString(golden).replace(
                                "fixture-1",
                                registry
                                    .readOrError()
                                    .model.version.value,
                            )
                    } finally {
                        Files.walk(out).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
                    }
                }
            }
        }
    })
