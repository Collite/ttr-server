// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.search

import org.tatrman.meta.v1.PageRequest
import org.tatrman.meta.v1.SearchRequest
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.metadata.model.parseSchemaCode
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.veles.grpc.MetadataServiceImpl
import org.tatrman.veles.grpc.PackageIndex
import org.tatrman.ttr.metadata.model.LocalizedTextList
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.ModelVersion
import org.tatrman.ttr.metadata.model.Query
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
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import java.time.Instant

class SearchRpcSpec :
    StringSpec({

        "substring search returns hits with matched_field and algorithm set" {
            val (service, _) = wire(query("customersList"))
            val resp = service.search(req("customersList", algo = "substring"))
            resp.itemsList.shouldNotBeEmpty()
            resp.algorithmUsed shouldBe "substring"
            resp.itemsList[0].algorithm shouldBe "substring"
            resp.itemsList[0].matchedField shouldBe "name"
        }

        "keyword search uses the inverted index" {
            val (service, _) =
                wire(
                    query(
                        "Q",
                        search = SearchHints(keywords = LocalizedTextList(mapOf("cs" to listOf("zákazníci")))),
                    ),
                )
            val resp = service.search(req("zákazníci", algo = "keyword"))
            resp.itemsList.shouldNotBeEmpty()
            resp.itemsList[0].algorithm shouldBe "keyword"
            resp.itemsList[0].matchedField shouldBe "keyword"
            resp.itemsList[0].relevanceScore shouldBe 1.0f
        }

        "regex search extracts parameters when requested for query owners" {
            val (service, _) =
                wire(
                    query(
                        "ordersForCustomer",
                        search = SearchHints(patterns = listOf("objednávky zákazníka {customer_id}")),
                    ),
                )
            val resp =
                service.search(
                    SearchRequest
                        .newBuilder()
                        .setQuery("objednávky zákazníka 42")
                        .setLanguage("cs")
                        .setAlgorithm("regex")
                        .setIncludeExtractedParameters(true)
                        .build(),
                )
            resp.itemsList.shouldNotBeEmpty()
            resp.itemsList[0].extractedParametersMap["customer_id"] shouldBe "42"
            resp.itemsList[0].patternIndex shouldBe 0
        }

        "all algorithm merges across substring + keyword + regex" {
            val q =
                query(
                    "Q",
                    search =
                        SearchHints(
                            keywords = LocalizedTextList(mapOf("cs" to listOf("zákazníci"))),
                            patterns = listOf("seznam {filter}"),
                        ),
                )
            val (service, _) = wire(q)
            val resp = service.search(req("zákazníci", algo = "all"))
            resp.algorithmUsed shouldBe "all"
            resp.itemsList.shouldNotBeEmpty()
        }

        "unknown algorithm returns OK + empty items + algorithm_not_supported message" {
            val (service, _) = wire(query("customersList"))
            val resp = service.search(req("customersList", algo = "imaginary"))
            resp.itemsCount shouldBe 0
            resp.algorithmUsed shouldBe "imaginary"
            resp.messagesList.any { it.code == "algorithm_not_supported" } shouldBe true
        }

        "result_threshold filters out low-confidence hits" {
            // Two queries; keyword algorithm scores keyword=1.0, alias=0.5.
            val q1 = query("Q1", search = SearchHints(keywords = LocalizedTextList(mapOf("cs" to listOf("foo")))))
            val q2 = query("Q2", search = SearchHints(aliases = listOf("foo")))
            val (service, _) = wire(q1, q2)
            val resp =
                service.search(
                    SearchRequest
                        .newBuilder()
                        .setQuery("foo")
                        .setAlgorithm("keyword")
                        .setLanguage("cs")
                        .setResultThreshold(0.6f)
                        .build(),
                )
            resp.itemsCount shouldBe 1
            resp.itemsList[0].relevanceScore shouldBe 1.0f
        }

        "page.page_size caps the number of returned hits (regression: facade dropped page size)" {
            // Two owners both match the substring "customers".
            val (service, _) = wire(query("customersA"), query("customersB"))
            // No page → server-default window (100) → both hits returned.
            service.search(req("customers", algo = "substring")).itemsCount shouldBe 2
            // page_size = 1 → exactly one hit (the mapping the facade previously dropped).
            val capped =
                service.search(
                    SearchRequest
                        .newBuilder()
                        .setQuery("customers")
                        .setLanguage("cs")
                        .setAlgorithm("substring")
                        .setPage(PageRequest.newBuilder().setPageSize(1))
                        .build(),
                )
            capped.itemsCount shouldBe 1
        }
    })

private fun wire(vararg q: Query): Pair<MetadataServiceImpl, MetadataRegistry> {
    val registry = MetadataRegistry()
    val supportedLanguages = listOf("cs", "en")
    val stopWords = StopWords(supportedLanguages)
    val substring = SubstringAlgorithm()
    val keyword = KeywordAlgorithm(stopWords)
    val regex = RegexAlgorithm()
    val coreRegistry =
        SearchAlgorithmRegistry(mapOf(substring.name to substring, keyword.name to keyword, regex.name to regex))
    val holder = SearchIndexHolder(coreRegistry, supportedLanguages)
    val all = AllAlgorithm(coreRegistry, holder)
    val withAll =
        SearchAlgorithmRegistry(
            coreRegistry
                .all()
                .associateBy { it.name } + (all.name to all),
        )
    registry.addListener { snap -> holder.rebuild(snap) }
    val model =
        Model(
            descriptor = ModelDescriptor("test-id", "test"),
            version = ModelVersion("0", Instant.EPOCH),
            schemas = emptyMap(),
            mappings = emptyList(),
            queries = q.associateBy { it.qname },
        )
    registry.swap(model, ModelGraph.build(model))
    val service = MetadataServiceImpl(registry, withAll, holder, tracer = null, packageIndex = PackageIndex())
    return service to registry
}

private fun query(
    name: String,
    search: SearchHints = SearchHints(),
): Query =
    Query(
        internalId = "id-$name",
        qname = qn("query", "query", name),
        sourceLanguage = "SQL",
        sourceText = "select 1",
        search = search,
    )

private fun req(
    query: String,
    algo: String,
): SearchRequest =
    SearchRequest
        .newBuilder()
        .setQuery(query)
        .setLanguage("cs")
        .setAlgorithm(algo)
        .build()

private fun qn(
    schema: String,
    namespace: String,
    name: String,
): QualifiedName =
    QualifiedName(
        schemaCode = parseSchemaCode(schema) ?: SchemaCode.UNSPECIFIED,
        namespace = namespace,
        name = name,
    )
