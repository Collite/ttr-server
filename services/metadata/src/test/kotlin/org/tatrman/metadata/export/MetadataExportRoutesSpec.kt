// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.export

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

internal val XM_ROOT: Path = Path.of("src/test/resources/xm-world").toAbsolutePath()

internal fun xmRegistry(): MetadataRegistry {
    val result =
        MetadataLoader(
            listOf(BuiltinStockSource(), FileBasedSource("xm", 100, LocalFsStorage("xm", XM_ROOT.resolve("model")))),
        ).load()
    result.errors shouldBe emptyList()
    val model = requireNotNull(result.model)
    return MetadataRegistry().apply {
        swap(model, ModelGraph.build(model), files = result.files, exportSidecars = result.exportSidecars)
    }
}

class MetadataExportRoutesSpec :
    StringSpec({
        "no params retains TTR tar, ETag, validation and legacy DOT route" {
            val registry = xmRegistry()
            testApplication {
                environment { config = MapApplicationConfig() }
                application { routing { metadataExportRoutes(registry) } }
                val response = client.get("/model/export")
                response.status shouldBe HttpStatusCode.OK
                response.contentType()?.withoutParameters().toString() shouldBe "application/x-tar+gzip"
                response.headers[HttpHeaders.ETag] shouldBe
                    registry
                        .readOrError()
                        .model.version.value
                val entries = mutableListOf<String>()
                TarArchiveInputStream(GZIPInputStream(response.body<ByteArray>().inputStream())).use { archive ->
                    while (true) {
                        val entry = archive.nextTarEntry ?: break
                        entries += entry.name
                    }
                }
                entries.any { it.endsWith(".ttr") } shouldBe true
                client.get("/model/export?validate=false").status shouldBe HttpStatusCode.OK
                client.get("/model/export/dot").status shouldBe HttpStatusCode.OK
            }
        }
        "graph bytes equal core golden, conditional GET recognizes HTTP entity tags" {
            val registry = xmRegistry()
            val version =
                registry
                    .readOrError()
                    .model.version.value
            testApplication {
                environment { config = MapApplicationConfig() }
                application { routing { metadataExportRoutes(registry) } }
                val response = client.get("/model/export?model=world&format=graph.json")
                response.status shouldBe HttpStatusCode.OK
                response.contentType()?.withoutParameters() shouldBe ContentType.Application.Json
                response.bodyAsText() shouldBe
                    Files
                        .readString(
                            Path.of("src/test/resources/xm-goldens/graph.json/world.json"),
                        ).replace("fixture-1", version)
                for (tag in listOf(version, "\"$version\"", "W/\"$version\"", "\"different\", \"$version\"", "*")) {
                    val cached = client.get("/model/export?format=graph.json") { header(HttpHeaders.IfNoneMatch, tag) }
                    cached.status shouldBe HttpStatusCode.NotModified
                    cached.headers[HttpHeaders.ETag] shouldBe version
                    cached.bodyAsText() shouldBe ""
                }
            }
        }
        "route reports cannot-carry, reserved formats, unknown since, bad inputs and empty current delta" {
            val registry = xmRegistry()
            val version =
                registry
                    .readOrError()
                    .model.version.value
            testApplication {
                environment { config = MapApplicationConfig() }
                application { routing { metadataExportRoutes(registry) } }
                val carry = client.get("/model/export?model=md&format=ttl") { header(HttpHeaders.IfNoneMatch, version) }
                carry.status shouldBe HttpStatusCode.UnprocessableEntity
                Json.parseToJsonElement(carry.bodyAsText()) shouldBe
                    Json.parseToJsonElement(
                        """{"error":"xm/format-cannot-carry","model":"md","format":"ttl","lossiness":"L-TTL-1"}""",
                    )
                val reserved = client.get("/model/export?format=ossie") { header(HttpHeaders.IfNoneMatch, version) }
                reserved.status shouldBe HttpStatusCode.NotImplemented
                reserved.bodyAsText() shouldContain "xm/format-not-built"
                client.get("/model/export?since=unknown").let {
                    it.status shouldBe HttpStatusCode.Conflict
                    it.bodyAsText() shouldContain
                        "xm/since-unknown"
                }
                val current = client.get("/model/export?format=graph.json&since=$version")
                val delta =
                    Json
                        .parseToJsonElement(current.bodyAsText())
                        .jsonObject
                        .getValue("delta")
                        .jsonObject
                delta.getValue("renamed").jsonArray shouldBe emptyList()
                delta.getValue("removed").jsonArray shouldBe emptyList()
                for (query in listOf(
                    "format=nope",
                    "model=nope",
                    "format=ttl&profile=nope",
                    "format=ttl&format=xmi",
                    "model=",
                    "unexpected=1",
                )) {
                    val bad = client.get("/model/export?$query") { header(HttpHeaders.IfNoneMatch, version) }
                    bad.status shouldBe HttpStatusCode.BadRequest
                    bad.bodyAsText() shouldContain "xm/bad-request"
                }
                client.get("/model/export?format=xmi").contentType()?.withoutParameters() shouldBe
                    ContentType.Application.Xml
            }
        }
        "unloaded model answers 503" {
            testApplication {
                environment { config = MapApplicationConfig() }
                application { routing { metadataExportRoutes(MetadataRegistry()) } }
                client.get("/model/export?format=graph.json").status shouldBe HttpStatusCode.ServiceUnavailable
            }
        }
    })
