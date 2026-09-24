// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.grpc

import org.tatrman.meta.v1.ListObjectsRequest
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.metadata.model.parseSchemaCode
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.DbColumn
import org.tatrman.ttr.metadata.model.DbSchema
import org.tatrman.ttr.metadata.model.DbTable
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.ModelVersion
import org.tatrman.ttr.metadata.model.SearchHints
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.search.SearchAlgorithmRegistry
import org.tatrman.ttr.metadata.search.SearchIndexHolder
import org.tatrman.ttr.metadata.search.all.AllAlgorithm
import org.tatrman.ttr.metadata.search.keyword.KeywordAlgorithm
import org.tatrman.ttr.metadata.search.keyword.StopWords
import org.tatrman.ttr.metadata.search.regex.RegexAlgorithm
import org.tatrman.ttr.metadata.search.substring.SubstringAlgorithm
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

class ListObjectsFuzzyOnlyFilterSpec :
    StringSpec({

        fun qn(
            schema: String,
            namespace: String,
            name: String,
        ): QualifiedName =
            QualifiedName(
                schemaCode = parseSchemaCode(schema) ?: SchemaCode.UNSPECIFIED,
                namespace = namespace,
                name = name,
            )

        "ListObjects(kind=column, fuzzy_only=false) returns all columns (entity excluded by kind)" {
            val table =
                DbTable(
                    internalId = "t1",
                    qname = qn("db", "dbo", "customers"),
                    columns =
                        listOf(
                            column(qn("db", "dbo", "customers.id"), "int", indexed = false, searchable = false),
                            column(qn("db", "dbo", "customers.name"), "text", indexed = true, searchable = true),
                            column(qn("db", "dbo", "customers.email"), "varchar", indexed = false, searchable = false),
                        ),
                    primaryKey = listOf("id"),
                )
            val fuzzyEntity =
                Entity(
                    internalId = "e1",
                    qname = qn("er", "entity", "Customer"),
                    search = SearchHints(searchable = true, indexed = true, matchMethod = "TYPOS(1)"),
                )
            val model =
                Model(
                    descriptor = ModelDescriptor("test-id", "test"),
                    version = ModelVersion("0", java.time.Instant.EPOCH),
                    schemas =
                        mapOf(
                            "db" to DbSchema(tables = mapOf(table.qname to table)),
                            "er" to
                                org.tatrman.ttr.metadata.model.ErSchema(
                                    entities =
                                        mapOf(
                                            fuzzyEntity.qname to fuzzyEntity,
                                        ),
                                ),
                        ),
                    mappings = emptyList(),
                    queries = emptyMap(),
                )
            val (service, _) = wire(model)

            val resp =
                service.listObjects(
                    ListObjectsRequest
                        .newBuilder()
                        .setKind("column")
                        .setFuzzyOnly(false)
                        .build(),
                )

            resp.itemsCount shouldBe 3
            resp.itemsList.map { it.qualifiedName.name }.toSet() shouldBe
                setOf("customers.id", "customers.name", "customers.email")
            resp.itemsList.map { it.qualifiedName.name } shouldNotContain "Customer"
        }

        "ListObjects(kind=column, fuzzy_only=true) returns only fuzzy columns" {
            val table =
                DbTable(
                    internalId = "t1",
                    qname = qn("db", "dbo", "customers"),
                    columns =
                        listOf(
                            column(qn("db", "dbo", "customers.id"), "int", indexed = false, searchable = false),
                            column(qn("db", "dbo", "customers.name"), "text", indexed = true, searchable = true),
                            column(qn("db", "dbo", "customers.email"), "varchar", indexed = true, searchable = true),
                        ),
                    primaryKey = listOf("id"),
                )
            val fuzzyEntity =
                Entity(
                    internalId = "e1",
                    qname = qn("er", "entity", "Customer"),
                    search = SearchHints(searchable = true, indexed = true, matchMethod = "TYPOS(1)"),
                )
            val model =
                Model(
                    descriptor = ModelDescriptor("test-id", "test"),
                    version = ModelVersion("0", java.time.Instant.EPOCH),
                    schemas =
                        mapOf(
                            "db" to DbSchema(tables = mapOf(table.qname to table)),
                            "er" to
                                org.tatrman.ttr.metadata.model.ErSchema(
                                    entities =
                                        mapOf(
                                            fuzzyEntity.qname to fuzzyEntity,
                                        ),
                                ),
                        ),
                    mappings = emptyList(),
                    queries = emptyMap(),
                )
            val (service, _) = wire(model)

            val resp =
                service.listObjects(
                    ListObjectsRequest
                        .newBuilder()
                        .setKind("column")
                        .setFuzzyOnly(true)
                        .build(),
                )

            resp.itemsCount shouldBe 2
            resp.itemsList.map { it.qualifiedName.name }.toSet() shouldBe
                setOf("customers.name", "customers.email")
            resp.itemsList.map { it.qualifiedName.name } shouldNotContain "Customer"
        }

        "ListObjects(kind=entity, fuzzy_only=true) returns only fuzzy entities (cross-kind)" {
            val entity =
                Entity(
                    internalId = "e1",
                    qname = qn("er", "entity", "Customer"),
                    search = SearchHints(searchable = true, indexed = true, matchMethod = "TYPOS(1)"),
                )
            val nonFuzzyEntity =
                Entity(
                    internalId = "e2",
                    qname = qn("er", "entity", "Order"),
                    search = SearchHints.EMPTY,
                )
            val model =
                Model(
                    descriptor = ModelDescriptor("test-id", "test"),
                    version = ModelVersion("0", java.time.Instant.EPOCH),
                    schemas =
                        mapOf(
                            "er" to
                                org.tatrman.ttr.metadata.model.ErSchema(
                                    entities = mapOf(entity.qname to entity, nonFuzzyEntity.qname to nonFuzzyEntity),
                                ),
                        ),
                    mappings = emptyList(),
                    queries = emptyMap(),
                )
            val (service, _) = wire(model)

            val resp =
                service.listObjects(
                    ListObjectsRequest
                        .newBuilder()
                        .setKind("entity")
                        .setFuzzyOnly(true)
                        .build(),
                )

            resp.itemsCount shouldBe 1
            resp.itemsList[0].qualifiedName.name shouldBe "Customer"
        }

        "ListObjects(kind=column, fuzzy_only=true) includes columns backing a fuzzy attribute" {
            // The `fuzzy: true` flag now lives on ER attributes; the column itself
            // is NOT marked fuzzy. The effective-fuzzy resolution must follow the
            // er2db mapping and surface the backing column anyway.
            val table =
                DbTable(
                    internalId = "t1",
                    qname = qn("db", "dbo", "QSTRED_DF"),
                    columns =
                        listOf(
                            column(qn("db", "dbo", "QSTRED_DF.IDSTRED"), "int", indexed = false, searchable = false),
                            column(qn("db", "dbo", "QSTRED_DF.KOD_STR"), "text", indexed = false, searchable = false),
                            column(qn("db", "dbo", "QSTRED_DF.NAZEV_STR"), "text", indexed = false, searchable = false),
                        ),
                    primaryKey = listOf("IDSTRED"),
                )
            val kodAttr =
                org.tatrman.ttr.metadata.model.Attribute(
                    internalId = "a-kod",
                    qname = qn("er", "entity", "účetní_středisko.kód_střediska"),
                    entity = qn("er", "entity", "účetní_středisko"),
                    type = "text",
                    search = SearchHints(searchable = true, indexed = true, matchMethod = "TYPOS(1)"),
                )
            val nazevAttr =
                org.tatrman.ttr.metadata.model.Attribute(
                    internalId = "a-nazev",
                    qname = qn("er", "entity", "účetní_středisko.název_střediska"),
                    entity = qn("er", "entity", "účetní_středisko"),
                    type = "text",
                    search = SearchHints(searchable = true, indexed = true, matchMethod = "TYPOS(1)"),
                )
            val entity =
                Entity(
                    internalId = "e1",
                    qname = qn("er", "entity", "účetní_středisko"),
                    aliases = listOf("středisko", "nákladové středisko"),
                    attributes = listOf(kodAttr, nazevAttr),
                )
            val mappings =
                listOf(
                    org.tatrman.ttr.metadata.model.Er2DbAttributeMapping(
                        internalId = "m-kod",
                        qname = qn("er", "map", "kod"),
                        attribute = kodAttr.qname,
                        target =
                            org.tatrman.ttr.metadata.model.AttributeMappingTarget.Column(
                                qn("db", "dbo", "QSTRED_DF.KOD_STR"),
                            ),
                    ),
                    org.tatrman.ttr.metadata.model.Er2DbAttributeMapping(
                        internalId = "m-nazev",
                        qname = qn("er", "map", "nazev"),
                        attribute = nazevAttr.qname,
                        target =
                            org.tatrman.ttr.metadata.model.AttributeMappingTarget.Column(
                                qn("db", "dbo", "QSTRED_DF.NAZEV_STR"),
                            ),
                    ),
                )
            val model =
                Model(
                    descriptor = ModelDescriptor("test-id", "test"),
                    version = ModelVersion("0", java.time.Instant.EPOCH),
                    schemas =
                        mapOf(
                            "db" to DbSchema(tables = mapOf(table.qname to table)),
                            "er" to
                                org.tatrman.ttr.metadata.model
                                    .ErSchema(entities = mapOf(entity.qname to entity)),
                        ),
                    mappings = mappings,
                    queries = emptyMap(),
                )
            val (service, _) = wire(model)

            val resp =
                service.listObjects(
                    ListObjectsRequest
                        .newBuilder()
                        .setKind("column")
                        .setFuzzyOnly(true)
                        .build(),
                )

            // KOD_STR + NAZEV_STR surface via their fuzzy attributes; IDSTRED does not.
            resp.itemsList.map { it.qualifiedName.name }.toSet() shouldBe
                setOf("QSTRED_DF.KOD_STR", "QSTRED_DF.NAZEV_STR")
        }

        "ListObjects(kind=column, fuzzy_only=true) returns empty when no fuzzy columns exist" {
            val table =
                DbTable(
                    internalId = "t1",
                    qname = qn("db", "dbo", "products"),
                    columns =
                        listOf(
                            column(qn("db", "dbo", "products.id"), "int", indexed = false, searchable = false),
                            column(qn("db", "dbo", "products.name"), "text", indexed = false, searchable = false),
                        ),
                    primaryKey = listOf("id"),
                )
            val model = modelOf(table)
            val (service, _) = wire(model)

            val resp =
                service.listObjects(
                    ListObjectsRequest
                        .newBuilder()
                        .setKind("column")
                        .setFuzzyOnly(true)
                        .build(),
                )

            resp.itemsCount shouldBe 0
        }

        // MV-T1 T3 (contracts §2.2) — `indexed_only`, and `fuzzy_only` as its alias.
        "ListObjects(indexed_only) lists an EXACT-method column — it was not indexed before MV" {
            val table =
                DbTable(
                    internalId = "t1",
                    qname = qn("db", "dbo", "stores"),
                    columns =
                        listOf(
                            column(qn("db", "dbo", "stores.id"), "int"),
                            DbColumn(
                                internalId = "c-state",
                                qname = qn("db", "dbo", "stores.state"),
                                table = qn("db", "dbo", "stores"),
                                dataType = "char",
                                search = SearchHints(searchable = true, indexed = true, matchMethod = "EXACT"),
                            ),
                            DbColumn(
                                internalId = "c-label",
                                qname = qn("db", "dbo", "stores.label"),
                                table = qn("db", "dbo", "stores"),
                                dataType = "text",
                                // a bare `searchable` is a hint, not a vocabulary
                                search = SearchHints(searchable = true),
                            ),
                        ),
                    primaryKey = listOf("id"),
                )
            val (service, _) = wire(modelOf(table))
            val resp =
                service.listObjects(
                    ListObjectsRequest
                        .newBuilder()
                        .setKind("column")
                        .setIndexedOnly(true)
                        .build(),
                )
            resp.itemsList.map { it.qualifiedName.name } shouldBe listOf("stores.state")
        }

        "fuzzy_only is an alias of indexed_only — the same page" {
            val table =
                DbTable(
                    internalId = "t1",
                    qname = qn("db", "dbo", "customers"),
                    columns =
                        listOf(
                            column(qn("db", "dbo", "customers.id"), "int"),
                            column(qn("db", "dbo", "customers.name"), "text", indexed = true, searchable = true),
                            column(qn("db", "dbo", "customers.email"), "varchar", indexed = true, searchable = true),
                        ),
                    primaryKey = listOf("id"),
                )
            val (service, _) = wire(modelOf(table))

            suspend fun page(build: ListObjectsRequest.Builder.() -> Unit) =
                service
                    .listObjects(
                        ListObjectsRequest
                            .newBuilder()
                            .setKind("column")
                            .apply(build)
                            .build(),
                    ).itemsList
                    .map { it.qualifiedName.name }
            val viaAlias = page { fuzzyOnly = true }
            viaAlias shouldBe page { indexedOnly = true }
            viaAlias shouldBe listOf("customers.email", "customers.name")
        }
    })

private fun column(
    qname: QualifiedName,
    dataType: String,
    indexed: Boolean = false,
    searchable: Boolean = false,
): DbColumn =
    DbColumn(
        internalId = "c-${qname.name}",
        qname = qname,
        table = qname,
        dataType = dataType,
        search =
            SearchHints(
                searchable = searchable,
                indexed = indexed,
                matchMethod = if (indexed) "TYPOS(1)" else null,
            ),
    )

private fun modelOf(vararg tables: DbTable): Model =
    Model(
        descriptor = ModelDescriptor("test-id", "test"),
        version = ModelVersion("0", java.time.Instant.EPOCH),
        schemas = mapOf("db" to DbSchema(tables = tables.associateBy { it.qname })),
        mappings = emptyList(),
        queries = emptyMap(),
    )

private fun wire(model: Model): Pair<MetadataServiceImpl, MetadataRegistry> {
    val registry = MetadataRegistry()
    val stopWords = StopWords(listOf("cs", "en"))
    val substring = SubstringAlgorithm()
    val keyword = KeywordAlgorithm(stopWords)
    val regex = RegexAlgorithm()
    val coreRegistry =
        SearchAlgorithmRegistry(mapOf(substring.name to substring, keyword.name to keyword, regex.name to regex))
    val holder = SearchIndexHolder(coreRegistry, listOf("cs", "en"))
    val all = AllAlgorithm(coreRegistry, holder)
    val withAll =
        SearchAlgorithmRegistry(
            coreRegistry.all().associateBy { it.name } + (all.name to all),
        )
    registry.addListener { snap -> holder.rebuild(snap) }
    registry.swap(model, ModelGraph.build(model))
    val service = MetadataServiceImpl(registry, withAll, holder, tracer = null)
    return service to registry
}
