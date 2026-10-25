// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.read

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.Cardinality
import org.tatrman.ttr.metadata.model.DbColumn
import org.tatrman.ttr.metadata.model.DbForeignKey
import org.tatrman.ttr.metadata.model.DbSchema
import org.tatrman.ttr.metadata.model.DbTable
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttr.metadata.model.LocalizedText
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.ModelVersion
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.Relation
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.search.SearchAlgorithmRegistry
import org.tatrman.ttr.metadata.search.SearchIndexHolder
import org.tatrman.ttr.metadata.search.substring.SubstringAlgorithm
import org.tatrman.metadata.grpc.PackageIndex
import java.time.Instant

/**
 * ttr-server#185 — the catalog viewer labels the model from this JSON (Collite/ttr-core#170), so
 * the read routes carry the model's localized metadata and the relation / foreign-key endpoints.
 * Every field is additive and omitted when empty. The fixture is shaped like the Hartland demo
 * model's `item` / `store` / `store_sales` corner.
 */
class MetadataReadRoutesLabelsSpec :
    StringSpec({

        "an entity carries displayLabel in every language, labelPlural and its localized description" {
            withRoutes { get ->
                val item = node(get("/model/graph?schema=er"), "er.entity.item")
                item["label"]!!.jsonPrimitive.content shouldBe "item" // still the identifier
                item["displayLabel"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content } shouldBe
                    mapOf("cs" to "Produkt", "en" to "Product")
                item["labelPlural"]!!.jsonPrimitive.content shouldBe "Products"
                item["descriptionLocalized"]!!.jsonObject["en"]!!.jsonPrimitive.content shouldBe
                    "Products — category/brand drills."
                item["description"] shouldBe null // the plain form was not authored
            }
        }

        "an attribute carries its localized description; a plain description stays plain" {
            withRoutes { get ->
                val graph = get("/model/graph?schema=er")
                val category = node(graph, "er.entity.item.category")
                category["descriptionLocalized"]!!.jsonObject["cs"]!!.jsonPrimitive.content shouldBe
                    "Kategorie tohoto produktu"
                category["displayLabel"] shouldBe null // none authored → no key at all

                val store = node(graph, "er.entity.store")
                store["description"]!!.jsonPrimitive.content shouldBe "Physical stores."
                store["descriptionLocalized"] shouldBe null
            }
        }

        "an attribute carries its displayLabel when the model authors one" {
            withRoutes { get ->
                // Hartland labels no attribute; the grammar allows it (G5), so the fixture adds one.
                val name = node(get("/model/graph?schema=er"), "er.entity.item.product_name")
                name["displayLabel"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content } shouldBe
                    mapOf("cs" to "Název produktu", "en" to "Product name")
            }
        }

        "a relation carries its endpoints and its cardinality in the authored form" {
            withRoutes { get ->
                val rel = node(get("/model/graph?schema=er"), "er.entity.rel_store_sales_item")
                rel["from"]!!.jsonPrimitive.content shouldBe "er.entity.store_sales"
                rel["to"]!!.jsonPrimitive.content shouldBe "er.entity.item"
                rel["cardinality"]!!.jsonObject["from"]!!.jsonPrimitive.content shouldBe "0..*"
                rel["cardinality"]!!.jsonObject["to"]!!.jsonPrimitive.content shouldBe "1"
            }
        }

        "a foreign key carries its column endpoints" {
            withRoutes { get ->
                val fk = node(get("/model/graph?schema=db"), "db.dbo.fk_ss_item")
                fk["fromColumns"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe
                    listOf("db.dbo.store_sales.ss_item_sk")
                fk["toColumns"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe listOf("db.dbo.item.i_item_sk")
            }
        }

        "/model/object carries the same fields as the graph node" {
            withRoutes { get ->
                val body = get("/model/object?qname=er.entity.item")
                val obj = body["object"]!!.jsonObject
                obj["displayLabel"]!!.jsonObject["en"]!!.jsonPrimitive.content shouldBe "Product"
                obj["labelPlural"]!!.jsonPrimitive.content shouldBe "Products"
            }
        }

        "an object with nothing localized keeps exactly the original five keys" {
            withRoutes { get ->
                val salesItem = node(get("/model/graph?schema=er"), "er.entity.store_sales.item")
                salesItem.keys shouldBe setOf("qname", "kind", "label", "schema", "pkg")
            }
        }
    })

private fun node(
    graph: JsonObject,
    qname: String,
): JsonObject {
    val found =
        (graph["nodes"] as JsonArray)
            .map { it.jsonObject }
            .firstOrNull { it["qname"]!!.jsonPrimitive.content == qname }
    found shouldNotBe null
    return found!!
}

private suspend fun withRoutes(block: suspend (get: suspend (String) -> JsonObject) -> Unit) {
    testApplication {
        environment { config = MapApplicationConfig() }
        val registry = MetadataRegistry()
        registry.swap(hartlandCorner(), ModelGraph.build(hartlandCorner()))
        val substring = SubstringAlgorithm()
        val search = SearchAlgorithmRegistry(mapOf(substring.name to substring))
        application {
            routing { metadataReadRoutes(registry, search, SearchIndexHolder(search, listOf("en")), PackageIndex()) }
        }
        block { path ->
            val res = client.get(path)
            res.status shouldBe HttpStatusCode.OK
            Json.parseToJsonElement(res.bodyAsText()).jsonObject
        }
    }
}

private fun er(name: String) = QualifiedName(schemaCode = SchemaCode.ER, namespace = "entity", name = name)

private fun db(name: String) = QualifiedName(schemaCode = SchemaCode.DB, namespace = "dbo", name = name)

private fun loc(vararg pairs: Pair<String, String>) = LocalizedText(mapOf(*pairs))

/** The Hartland demo's `item` / `store` / `store_sales` corner, ER and DB. */
private fun hartlandCorner(): Model {
    val item =
        Entity(
            internalId = "e-item",
            qname = er("item"),
            descriptionLocalized =
                loc(
                    "en" to "Products — category/brand drills.",
                    "cs" to "Produkty — členění podle kategorie a značky.",
                ),
            labelPlural = "Products",
            displayLabel = loc("en" to "Product", "cs" to "Produkt"),
            attributes =
                listOf(
                    Attribute(
                        internalId = "a-item-product-name",
                        qname = er("item.product_name"),
                        displayLabel = loc("en" to "Product name", "cs" to "Název produktu"),
                        entity = er("item"),
                        type = "text",
                    ),
                    Attribute(
                        internalId = "a-item-category",
                        qname = er("item.category"),
                        descriptionLocalized =
                            loc("en" to "Product category of this item", "cs" to "Kategorie tohoto produktu"),
                        entity = er("item"),
                        type = "text",
                    ),
                ),
        )
    val store =
        Entity(
            internalId = "e-store",
            qname = er("store"),
            description = "Physical stores.",
            labelPlural = "Stores",
            displayLabel = loc("en" to "Store", "cs" to "Prodejna"),
        )
    val storeSales =
        Entity(
            internalId = "e-store-sales",
            qname = er("store_sales"),
            attributes =
                listOf(
                    Attribute(
                        internalId = "a-ss-item",
                        qname = er("store_sales.item"),
                        entity = er("store_sales"),
                        type = "int",
                    ),
                ),
        )
    val rel =
        Relation(
            internalId = "r-ss-item",
            qname = er("rel_store_sales_item"),
            fromEntity = er("store_sales"),
            toEntity = er("item"),
            cardinality = Cardinality(fromMin = 0, fromMax = -1, toMin = 1, toMax = 1),
        )
    val itemTable =
        DbTable(
            internalId = "t-item",
            qname = db("item"),
            columns =
                listOf(
                    DbColumn(
                        internalId = "c-item-sk",
                        qname = db("item.i_item_sk"),
                        table = db("item"),
                        dataType = "int",
                    ),
                ),
        )
    val salesTable =
        DbTable(
            internalId = "t-ss",
            qname = db("store_sales"),
            columns =
                listOf(
                    DbColumn(
                        internalId = "c-ss-item",
                        qname = db("store_sales.ss_item_sk"),
                        table = db("store_sales"),
                        dataType = "int",
                    ),
                ),
        )
    val fk =
        DbForeignKey(
            internalId = "fk-ss-item",
            qname = db("fk_ss_item"),
            fromColumns = listOf(db("store_sales.ss_item_sk")),
            toColumns = listOf(db("item.i_item_sk")),
        )
    return Model(
        descriptor = ModelDescriptor("hartland", "hartland"),
        version = ModelVersion("v1", Instant.EPOCH),
        schemas =
            mapOf(
                "er" to
                    ErSchema(
                        entities = listOf(item, store, storeSales).associateBy { it.qname },
                        relations = mapOf(rel.qname to rel),
                    ),
                "db" to
                    DbSchema(
                        tables = listOf(itemTable, salesTable).associateBy { it.qname },
                        foreignKeys = mapOf(fk.qname to fk),
                    ),
            ),
        mappings = emptyList(),
        queries = emptyMap(),
    )
}
