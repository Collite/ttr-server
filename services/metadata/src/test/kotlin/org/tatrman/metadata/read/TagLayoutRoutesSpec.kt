// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.read

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.metadata.source.YamlFileSource
import org.tatrman.ttr.yaml.CanonicalYaml
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText

/** MW S4.3b: real YAML source/reconciler + ResolveTags closure + HTTP layout projection. Invented ids only. */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class TagLayoutRoutesSpec :
    StringSpec({
        fun loadRegistry(
            pkg: String = "demo.widgets",
            target: MetadataRegistry = MetadataRegistry(),
        ): MetadataRegistry {
            val root = Files.createTempDirectory("mw-tag-layout")
            try {
                listOf(
                    """
                    model er
                    def entity widget { attributes: [def attribute code { type: int }] }
                    def entity crate { attributes: [def attribute code { type: int }] }
                    def entity neighbour { }
                    def relation packed {
                        tags: ["er-only", "both"], from: er.entity.widget, to: er.entity.crate,
                        cardinality: { from: "0..*", to: "1" }
                    }
                    def relation adjacent {
                        from: er.entity.widget, to: er.entity.neighbour,
                        cardinality: { from: "0..*", to: "1" }
                    }
                    """.trimIndent(),
                    """
                    model db schema dbo
                    def table widget_row {
                        tags: ["db-only", "both"], columns: [def column code { type: int }]
                    }
                    def view widget_view {
                        tags: ["db-only", "both"], columns: [def column code { type: int }]
                    }
                    def table spare { columns: [def column code { type: int }] }
                    """.trimIndent(),
                ).forEachIndexed { i, ttr ->
                    root
                        .resolve(pkg.replace('.', '/'))
                        .createDirectories()
                        .resolve(
                            "model$i.yaml",
                        ).writeText(CanonicalYaml.write(CanonicalYaml.fromTtr("package $pkg\n$ttr\n")))
                }
                val source = YamlFileSource("fixture", 100, LocalFsStorage("fixture", root)).load()
                source.errors shouldBe emptyList()
                val loaded = ModelReconciler(ModelDescriptor("tag-layout", "tag-layout")).reconcile(listOf(source))
                loaded.errors shouldBe emptyList()
                return target.apply {
                    swap(loaded.model, ModelGraph.build(loaded.model), files = loaded.files)
                }
            } finally {
                root.deleteRecursively()
            }
        }
        val er = listOf("demo.widgets.er.entity.crate", "demo.widgets.er.entity.widget")
        val db = listOf("demo.widgets.db.dbo.widget_row", "demo.widgets.db.dbo.widget_view")

        fun objects(body: String) =
            Json
                .parseToJsonElement(body)
                .jsonObject
                .getValue("objects")
                .jsonArray
                .map { it.jsonPrimitive.content }

        "ER-only tags default to ER, including untagged relation endpoints and no neighbours" {
            val registry = loadRegistry()
            testApplication {
                application { routing { layoutRoutes(registry) } }
                val response = client.get("/model/layout?tags=er-only")
                response.status shouldBe HttpStatusCode.OK
                objects(response.bodyAsText()) shouldBe er
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                body.getValue("schema").jsonPrimitive.content shouldBe "er"
                body.getValue("source").jsonPrimitive.content shouldBe "computed:tags"
                body.getValue("positions").jsonObject.keys shouldBe er.toSet()
                body.getValue("modelVersion").jsonPrimitive.content shouldBe
                    registry
                        .readOrError()
                        .model.version.value
            }
        }
        "DB-only tags select tables and views, without columns or unrelated tables" {
            val registry = loadRegistry()
            testApplication {
                application { routing { layoutRoutes(registry) } }
                val response = client.get("/model/layout?tags=db-only&schema=db")
                response.status shouldBe HttpStatusCode.OK
                objects(response.bodyAsText()) shouldBe db
                val empty = client.get("/model/layout?tags=db-only")
                empty.status shouldBe HttpStatusCode.OK
                objects(empty.bodyAsText()) shouldBe emptyList()
            }
        }
        "mixed-schema tags give the exact ER and DB projections in two separate calls" {
            val registry = loadRegistry()
            testApplication {
                application { routing { layoutRoutes(registry) } }
                val erResponse = client.get("/model/layout?tags=both&schema=er")
                val dbResponse = client.get("/model/layout?tags=both&schema=db")
                erResponse.status shouldBe HttpStatusCode.OK
                dbResponse.status shouldBe HttpStatusCode.OK
                objects(erResponse.bodyAsText()) shouldBe er
                objects(dbResponse.bodyAsText()) shouldBe db
                erResponse.headers[HttpHeaders.ETag] shouldNotBe dbResponse.headers[HttpHeaders.ETag]
            }
        }
        "name plus tags, invalid schemas and blank tag members are 400" {
            val registry = loadRegistry()
            testApplication {
                application { routing { layoutRoutes(registry) } }
                listOf(
                    "name=authored&tags=both",
                    "name=&tags=both",
                    "tags=both&schema=md",
                    "tags=both&schema=",
                    "tags=both&schema=ER",
                    "tags=",
                    "tags=both,",
                    "tags=both&tags=",
                ).forEach { query ->
                    client.get("/model/layout?$query").status shouldBe HttpStatusCode.BadRequest
                }
            }
        }
        "normalized tag sets share the cached representation and ETag; unknown tags return an empty layout" {
            val registry = loadRegistry()
            testApplication {
                application { routing { layoutRoutes(registry) } }
                val first = client.get("/model/layout?tags=er-only,db-only")
                first.status shouldBe HttpStatusCode.OK
                val etag = first.headers[HttpHeaders.ETag]
                etag shouldNotBe null
                val second = client.get("/model/layout?tags=db-only&tags=%20er-only%20,db-only&schema=er")
                second.status shouldBe HttpStatusCode.OK
                second.bodyAsText() shouldBe first.bodyAsText()
                second.headers[HttpHeaders.ETag] shouldBe etag
                val unknown = client.get("/model/layout?tags=absent")
                unknown.status shouldBe HttpStatusCode.OK
                objects(unknown.bodyAsText()) shouldBe emptyList()
            }
        }
        "snapshot swap invalidates cached tag layouts and their ETags" {
            val registry = loadRegistry()
            testApplication {
                application { routing { layoutRoutes(registry) } }
                val first = client.get("/model/layout?tags=both")
                first.status shouldBe HttpStatusCode.OK
                loadRegistry("demo.replaced", registry)
                val second = client.get("/model/layout?tags=both")
                second.status shouldBe HttpStatusCode.OK
                objects(second.bodyAsText()) shouldBe
                    listOf("demo.replaced.er.entity.crate", "demo.replaced.er.entity.widget")
                second.headers[HttpHeaders.ETag] shouldNotBe first.headers[HttpHeaders.ETag]
            }
        }
        "tag layouts return 503 before the initial model load" {
            testApplication {
                application { routing { layoutRoutes(MetadataRegistry()) } }
                client.get("/model/layout?tags=both").status shouldBe HttpStatusCode.ServiceUnavailable
            }
        }
    })
