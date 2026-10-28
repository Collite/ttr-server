// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.read

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.*
import org.tatrman.metadata.grpc.PackageIndex
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.search.SearchAlgorithmRegistry
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Files
import java.nio.file.Path

/** Real parser, reconciler, registry and HTTP reads; no hand-built md objects or graph. */
class MetadataReadRoutesMdSpec :
    StringSpec({
        "index and graph expose md objects with matching counts and packages" {
            withMdRoutes { get, _, _ ->
                val graph = get("/model/graph")
                val nodes = graph.nodes()
                val index = get("/model/index")
                index["schemas"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("md") shouldBe true
                index["packages"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("md") shouldBe true
                index["counts"]!!.jsonObject["objects"]!!.jsonPrimitive.int shouldBe nodes.size
                nodes.getValue("md.dimension.Buyer")["kind"]!!.jsonPrimitive.content shouldBe "dimension"
                nodes.getValue("md.cubelet.purchases")["kind"]!!.jsonPrimitive.content shouldBe "cubelet"
                nodes.getValue("md.dimension.Buyer.id")["kind"]!!.jsonPrimitive.content shouldBe "attribute"
                nodes
                    .getValue(
                        "md.dimension.Buyer.id",
                    )["displayLabel"]!!
                    .jsonObject["cs"]!!
                    .jsonPrimitive.content shouldBe
                    "Kód kupujícího"
                nodes.getValue("md.map.buyer_amounts")["kind"]!!.jsonPrimitive.content shouldBe "map"
                nodes.getValue("md.domain.Units")["pkg"]!!.jsonPrimitive.content shouldBe "md"
                nodes
                    .getValue("md.dimension.Buyer")["descriptionLocalized"]!!
                    .jsonObject["cs"]!!
                    .jsonPrimitive.content shouldBe "Dimenze kupujícího"
            }
        }

        "md graph carries dimension members, grain, measures and hierarchy levels" {
            withMdRoutes { get, _, _ ->
                val graph = get("/model/graph?schema=md")
                val edges = graph.edges()
                edges.contains(Triple("md.dimension.Buyer.id", "md.dimension.Buyer", "DEFINES")) shouldBe true
                edges.contains(Triple("md.cubelet.purchases", "md.dimension.Buyer.id", "REFERENCES")) shouldBe true
                edges.contains(Triple("md.cubelet.purchases", "md.dimension.Calendar.day", "REFERENCES")) shouldBe true
                edges.contains(Triple("md.cubelet.purchases", "md.measure.revenue", "REFERENCES")) shouldBe true
                edges.contains(
                    Triple("md.hierarchy.CalendarRollup", "md.dimension.Calendar.month", "REFERENCES"),
                ) shouldBe
                    true
                graph.nodes().values.all { it["schema"]!!.jsonPrimitive.content == "md" } shouldBe true
            }
        }

        "whole graph exposes er2db and all md2db binding families as logical-to-physical MAPS_TO" {
            withMdRoutes { get, _, _ ->
                val graph = get("/model/graph")
                val edges = graph.edges()
                edges.contains(Triple("er.entity.buyer", "db.retail.buyers", "MAPS_TO")) shouldBe true
                edges.contains(Triple("er.entity.buyer.id", "db.retail.buyers.id", "MAPS_TO")) shouldBe true
                edges.contains(Triple("er.entity.product", "db.retail.products", "MAPS_TO")) shouldBe true
                edges.contains(Triple("er.entity.product.id", "db.retail.products.id", "MAPS_TO")) shouldBe true
                edges.contains(Triple("er.entity.sale_buyer", "db.retail.sale_buyer", "MAPS_TO")) shouldBe true
                edges.contains(Triple("md.cubelet.purchases", "db.retail.sales", "MAPS_TO")) shouldBe true
                edges.contains(Triple("md.domain.Money", "db.retail.sales.amount", "MAPS_TO")) shouldBe true
                edges.contains(Triple("md.map.buyer_amounts", "db.retail.sales", "MAPS_TO")) shouldBe true
                edges.size shouldBe edges.distinct().size
                val ids = graph.nodes().keys
                edges.all { it.first in ids && it.second in ids } shouldBe true
            }
        }

        "export binding identities adapt to the existing catalog namespace without duplicating nodes" {
            withMdRoutes { get, _, _ ->
                val graph = get("/model/graph")
                graph.nodes().containsKey("er.storage.warehouse") shouldBe true
                graph.nodes().containsKey("er.entity.warehouse") shouldBe false
                graph.edges().contains(Triple("er.storage.warehouse", "db.retail.buyers", "MAPS_TO")) shouldBe true
                val detail = get("/model/object?qname=er.storage.warehouse")
                detail["references"]!!.jsonArray.map { it.jsonPrimitive.content }.contains("db.retail.buyers") shouldBe
                    true
            }
        }

        "schema and package scopes exclude cross-scope edges without dangling endpoints" {
            withMdRoutes { get, _, _ ->
                for (path in listOf(
                    "/model/graph?schema=md",
                    "/model/graph?package=md",
                    "/model/graph?schema=db&package=db.retail",
                )) {
                    val graph = get(path)
                    val ids = graph.nodes().keys
                    graph.edges().all { it.first in ids && it.second in ids } shouldBe true
                    graph.edges().none { it.third == "MAPS_TO" } shouldBe true
                }
                get("/model/graph?schema=md&package=er").nodes() shouldBe emptyMap()
                get("/model/graph?package=not-a-package").edges() shouldBe emptyList()
            }
        }

        "md object detail round trips graph fields, source and outgoing references" {
            withMdRoutes { get, root, _ ->
                val graph = get("/model/graph")
                for (id in listOf(
                    "md.dimension.Buyer",
                    "md.dimension.Buyer.id",
                    "md.cubelet.purchases",
                    "md.domain.Money",
                )) {
                    val detail = get("/model/object?qname=$id")
                    detail["object"]!!.jsonObject shouldBe graph.nodes().getValue(id)
                    val location = detail["sourceLocation"]!!.jsonPrimitive.content
                    Path.of(location).isAbsolute shouldBe true
                    location shouldBe root.resolve("md/analytic.ttrm").toString()
                    detail["references"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe
                        graph
                            .edges()
                            .filter { it.first == id }
                            .map { it.second }
                            .distinct()
                            .sorted()
                }
            }
        }

        "typed and md detail source paths use the same absolute form" {
            withMdRoutes { get, root, _ ->
                val location = get("/model/object?qname=er.entity.buyer")["sourceLocation"]!!.jsonPrimitive.content
                Path.of(location).isAbsolute shouldBe true
                location shouldBe root.resolve("er/logical.ttrm").toString()
            }
        }

        "next snapshot removes md objects and edges, while disk-only edits do not change served reads" {
            withMdRoutes { get, root, reload ->
                val before = get("/model/graph")
                val version = get("/model/index")["modelVersion"]
                val md = root.resolve("md/analytic.ttrm")
                Files.writeString(md, Files.readString(md).replace(Regex("def cubelet purchases[^\\n]*"), ""))
                get("/model/graph") shouldBe before
                get("/model/index")["modelVersion"] shouldBe version
                reload()
                val after = get("/model/graph")
                after.nodes().containsKey("md.cubelet.purchases") shouldBe false
                after.edges().none {
                    it.first == "md.cubelet.purchases" || it.second == "md.cubelet.purchases"
                } shouldBe
                    true
                after.nodes().containsKey("md.dimension.Buyer") shouldBe true
            }
        }

        "md reads retain not-loaded, unknown-object and read-only conventions" {
            testApplication {
                environment { config = MapApplicationConfig() }
                val registry = MetadataRegistry()
                application {
                    routing {
                        metadataReadRoutes(
                            registry,
                            SearchAlgorithmRegistry(emptyMap()),
                            null,
                            PackageIndex(),
                            emptySet(),
                        )
                    }
                }
                for (path in listOf(
                    "/model/index",
                    "/model/graph?schema=md",
                    "/model/object?qname=md.dimension.Buyer",
                )) {
                    client.get(path).status shouldBe HttpStatusCode.ServiceUnavailable
                    client.post(path).status shouldBe HttpStatusCode.MethodNotAllowed
                }
            }
            withMdRoutes { get, _, _ ->
                get(
                    "/model/object?qname=md.dimension.absent",
                    HttpStatusCode.NotFound,
                )["error"]!!.jsonPrimitive.content shouldBe
                    "not found"
            }
        }
    })

private fun JsonObject.nodes(): Map<String, JsonObject> =
    getValue("nodes").jsonArray.associate {
        it.jsonObject
            .getValue("qname")
            .jsonPrimitive.content to it.jsonObject
    }

private fun JsonObject.edges(): List<Triple<String, String, String>> =
    getValue("edges").jsonArray.map {
        val edge = it.jsonObject
        Triple(
            edge.getValue("from").jsonPrimitive.content,
            edge.getValue("to").jsonPrimitive.content,
            edge.getValue("type").jsonPrimitive.content,
        )
    }

private class MdGet(
    private val get: suspend (String) -> Pair<HttpStatusCode, JsonObject>,
) {
    suspend operator fun invoke(
        path: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): JsonObject {
        val (actual, body) = get(path)
        actual shouldBe status
        return body
    }
}

private suspend fun withMdRoutes(block: suspend (MdGet, Path, suspend () -> Unit) -> Unit) {
    val root = Files.createTempDirectory("metadata-md-read")
    try {
        val fixture = Path.of("src/test/resources/fixture-md-read")
        Files.walk(fixture).use { files ->
            files.forEach { path ->
                val destination = root.resolve(fixture.relativize(path))
                if (Files.isDirectory(path)) Files.createDirectories(destination) else Files.copy(path, destination)
            }
        }
        testApplication {
            environment { config = MapApplicationConfig() }
            val registry = MetadataRegistry()
            val packages = PackageIndex().commitOnSwap(registry)

            fun reload() {
                val source = FileBasedSource("md-read", 100, LocalFsStorage("md-read", root))
                val loaded = MetadataLoader(listOf(packages.recording("md-read", source))).load()
                loaded.errors shouldBe emptyList()
                val model = requireNotNull(loaded.model)
                registry.swap(model, ModelGraph.build(model), files = loaded.files)
            }
            reload()
            application {
                routing {
                    metadataReadRoutes(
                        registry,
                        SearchAlgorithmRegistry(emptyMap()),
                        null,
                        packages,
                        emptySet(),
                    )
                }
            }
            block(
                MdGet { path ->
                    val response = client.get(path)
                    response.status to Json.parseToJsonElement(response.bodyAsText()).jsonObject
                },
                root,
                { reload() },
            )
        }
    } finally {
        Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}
