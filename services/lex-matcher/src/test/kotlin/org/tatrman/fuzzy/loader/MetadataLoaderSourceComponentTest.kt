// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import io.grpc.BindableService
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.tatrman.fuzzy.core.FuzzyMatcher
import org.tatrman.fuzzy.core.LookupQuery
import org.tatrman.fuzzy.core.MatchVersion
import org.tatrman.fuzzy.core.RetrievalMode
import org.tatrman.fuzzy.core.SourceTag
import kotlin.time.Duration.Companion.seconds
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.Status
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.StreamObserver
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.config.LoaderSourceConfig
import org.tatrman.fuzzy.config.MetadataConfig
import org.tatrman.fuzzy.config.MssqlConfig
import org.tatrman.fuzzy.config.PostgresConfig
import org.tatrman.fuzzy.core.LoaderWarningInfo
import org.tatrman.fuzzy.core.StringRepository
import org.tatrman.meta.v1.ListMemberVocabulariesRequest
import org.tatrman.meta.v1.ListMemberVocabulariesResponse
import org.tatrman.meta.v1.MemberVocabulary
import org.tatrman.meta.v1.PageInfo
import org.tatrman.meta.v1.VelesServiceGrpc
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * MV-T2 (member-vocabulary contracts §4, §6, §8, §9.1, §9.8) — the member loader over
 * `ListMemberVocabularies`, end to end through the real [MetadataServiceClient] on `io.grpc.inprocess`
 * against a hand-rolled Veles. Asserts on data flow: the categories, the SQL that ran, the rows and
 * the method they carry, the warnings — not call counts alone.
 */
class MetadataLoaderSourceComponentTest :
    StringSpec({

        fun attribute(ref: String): QualifiedName {
            val (_, ns, name) = ref.split('.', limit = 3)
            return QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.ER)
                .setNamespace(ns)
                .setName(name)
                .build()
        }

        /** An item as Veles lists it: the category is the attribute's dotted ref. */
        fun vocabulary(
            category: String,
            readSql: String,
            method: String = "EXACT",
            version: String = "sha256:$category",
            vararg diagnostics: String,
        ): MemberVocabulary =
            MemberVocabulary
                .newBuilder()
                .setCategory(category)
                .setAttribute(attribute(category))
                .setKeyAttribute(if (readSql.isEmpty()) "" else category.substringBeforeLast('.') + ".id")
                .setReadSql(readSql)
                .setMatchMethod(method)
                .setVersion(version)
                .addAllDiagnostics(
                    diagnostics.map {
                        ResponseMessage
                            .newBuilder()
                            .setSeverity(Severity.WARNING)
                            .setCode(it)
                            .setHumanMessage("$it for $category")
                            .build()
                    },
                ).build()

        fun page(
            items: List<MemberVocabulary>,
            next: String = "",
        ): ListMemberVocabulariesResponse =
            ListMemberVocabulariesResponse
                .newBuilder()
                .addAllItems(items)
                .setPageInfo(PageInfo.newBuilder().setNextPageToken(next).setTotalCount(items.size))
                .build()

        /** A Veles that serves ListMemberVocabularies from [pages] (by page token), or fails with [error]. */
        class StubVeles : VelesServiceGrpc.VelesServiceImplBase() {
            var pages: Map<String, ListMemberVocabulariesResponse> = mapOf("" to page(emptyList()))
            var error: Status? = null
            var delayMs: Long = 0
            val requests = ConcurrentLinkedQueue<ListMemberVocabulariesRequest>()

            override fun listMemberVocabularies(
                request: ListMemberVocabulariesRequest,
                responseObserver: StreamObserver<ListMemberVocabulariesResponse>,
            ) {
                requests.add(request)
                if (delayMs > 0) Thread.sleep(delayMs)
                error?.let {
                    responseObserver.onError(it.asRuntimeException())
                    return
                }
                responseObserver.onNext(pages.getValue(request.page.pageToken))
                responseObserver.onCompleted()
            }
        }

        class Harness(
            val server: Server,
            val channel: ManagedChannel,
            val client: MetadataServiceClient,
        ) : AutoCloseable {
            override fun close() {
                channel.shutdownNow()
                server.shutdownNow()
            }
        }

        fun harness(
            veles: BindableService,
            timeoutMs: Long = 2_000,
        ): Harness {
            val name = "member-loader-${UUID.randomUUID()}"
            val server =
                InProcessServerBuilder
                    .forName(name)
                    .addService(veles)
                    .build()
                    .start()
            val channel = InProcessChannelBuilder.forName(name).usePlaintext().build()
            return Harness(server, channel, MetadataServiceClient(channel, timeoutMs = timeoutMs))
        }

        val postgres = PostgresConfig("h", 1, "db", "u", "p")

        fun loader(
            h: Harness,
            fetched: MutableList<String> = mutableListOf(),
            sourceNamespace: String = "",
            dialect: org.tatrman.fuzzy.config.DatabaseConfig = postgres,
            rows: (String) -> List<ReadRow> = { listOf(ReadRow("1", "Acme")) },
            aliasTables: () -> List<AliasTableDecl> = { emptyList() },
        ) = MetadataLoaderSource(
            client = h.client,
            dialect = dialect,
            sourceNamespace = sourceNamespace,
            fetchRows = { sql ->
                fetched += sql
                rows(sql)
            },
            aliasTables = aliasTables,
        )

        fun cfg(refreshIntervalSeconds: Long = 0) =
            AppConfig(
                serverPort = 7121,
                grpcPort = 7221,
                grpcReflectionEnabled = false,
                refreshIntervalSeconds = refreshIntervalSeconds,
                tokenBasedConfig =
                    org.tatrman.fuzzy.config
                        .TokenBasedConfig(),
                nlp =
                    org.tatrman.fuzzy.config
                        .NlpConfig(),
                loaderSource = LoaderSourceConfig(source = "metadata"),
                metadata = MetadataConfig(),
            )

        val stateSql =
            "SELECT \"s_store_sk\" AS \"sk\", \"s_state\" AS \"state\" FROM \"store\" GROUP BY 1, 2 ORDER BY 1"
        val nameSql = "SELECT \"s_store_sk\", \"s_store_name\" FROM \"store\" GROUP BY 1, 2 ORDER BY 1"

        "the category is the vocabulary's own, its read plan runs verbatim, every row carries its method" {
            val veles =
                StubVeles().apply {
                    pages =
                        mapOf(
                            "" to
                                page(
                                    listOf(
                                        vocabulary("er.entity.store.state", stateSql, "EXACT"),
                                        vocabulary("er.entity.store.store_name", nameSql, "TYPOS(1)"),
                                    ),
                                ),
                        )
                }
            harness(veles).use { h ->
                val fetched = mutableListOf<String>()
                val result = runBlocking { loader(h, fetched).loadNextCache() }!!

                result.keys shouldContainExactly setOf("er.entity.store.state", "er.entity.store.store_name")
                fetched shouldContainExactly listOf(stateSql, nameSql)
                result.getValue("er.entity.store.state").map { it.matchMethod } shouldBe listOf("EXACT")
                result.getValue("er.entity.store.store_name").map { it.matchMethod } shouldBe listOf("TYPOS(1)")
            }
        }

        "the loader always names its warehouse's dialect — POSTGRESQL, MSSQL; never \"\" (A-MV-7)" {
            val veles = StubVeles()
            harness(veles).use { h ->
                runBlocking {
                    loader(h, dialect = postgres).loadNextCache()
                    loader(h, dialect = MssqlConfig("h", 1, "db", "u", "p")).loadNextCache()
                }
                veles.requests.map { it.dialect } shouldContainExactly listOf("POSTGRESQL", "MSSQL")
            }
        }

        "every page is read, in order, with the token Veles handed back" {
            val veles =
                StubVeles().apply {
                    pages =
                        mapOf(
                            "" to page(listOf(vocabulary("er.entity.a.x", "SELECT 1")), next = "t2"),
                            "t2" to page(listOf(vocabulary("er.entity.b.y", "SELECT 2"))),
                        )
                }
            harness(veles).use { h ->
                val result = runBlocking { loader(h).loadNextCache() }!!
                result.keys shouldContainExactly setOf("er.entity.a.x", "er.entity.b.y")
                veles.requests.map { it.page.pageToken } shouldContainExactly listOf("", "t2")
            }
        }

        "§9.1 disjoint filters — two entities over one table are two categories with two row sets" {
            val supplierSql = "SELECT \"id\", \"name\" FROM \"rivals\" WHERE \"kind\" = 1 GROUP BY 1, 2 ORDER BY 1"
            val brandSql = "SELECT \"id\", \"name\" FROM \"rivals\" WHERE \"kind\" = 0 GROUP BY 1, 2 ORDER BY 1"
            val veles =
                StubVeles().apply {
                    pages =
                        mapOf(
                            "" to
                                page(
                                    listOf(
                                        vocabulary("er.entity.brand_rival.rival_name", brandSql, "TOKENS"),
                                        vocabulary("er.entity.supplier_rival.rival_name", supplierSql, "TOKENS"),
                                    ),
                                ),
                        )
                }
            val rows =
                mapOf(
                    supplierSql to listOf(ReadRow("1", "Nordwind"), ReadRow("2", "Acme")),
                    brandSql to listOf(ReadRow("3", "Acme")),
                )
            harness(veles).use { h ->
                val result = runBlocking { loader(h, rows = rows::getValue).loadNextCache() }!!
                // A-MV-15: a member's id is its value.
                result.getValue("er.entity.supplier_rival.rival_name").map { it.id } shouldContainExactly
                    listOf("Nordwind", "Acme")
                result.getValue("er.entity.brand_rival.rival_name").map { it.id } shouldContainExactly listOf("Acme")
            }
        }

        "a vocabulary Veles could not plan is not loaded; its diagnostics become the loader's warnings" {
            val veles =
                StubVeles().apply {
                    pages =
                        mapOf(
                            "" to
                                page(
                                    listOf(
                                        vocabulary("er.entity.line.sku", "", diagnostics = arrayOf("RG-FUZ-001")),
                                        vocabulary("er.entity.card.display", "", diagnostics = arrayOf("RG-FUZ-003")),
                                        vocabulary("er.entity.store.state", stateSql),
                                    ),
                                ),
                        )
                }
            harness(veles).use { h ->
                val fetched = mutableListOf<String>()
                val l = loader(h, fetched)
                val result = runBlocking { l.loadNextCache() }!!

                result.keys shouldContainExactly setOf("er.entity.store.state")
                fetched shouldContainExactly listOf(stateSql)
                l.warnings() shouldContainExactly
                    listOf(
                        LoaderWarningInfo("RG-FUZ-001", "er.entity.line.sku", "RG-FUZ-001 for er.entity.line.sku"),
                        LoaderWarningInfo(
                            "RG-FUZ-003",
                            "er.entity.card.display",
                            "RG-FUZ-003 for er.entity.card.display",
                        ),
                    )
            }
        }

        "the sourceNamespace guard reads the ATTRIBUTE's namespace" {
            val veles =
                StubVeles().apply {
                    pages =
                        mapOf(
                            "" to
                                page(
                                    listOf(
                                        vocabulary("er.entity.store.state", stateSql),
                                        vocabulary("er.other.store.state", "SELECT elsewhere"),
                                    ),
                                ),
                        )
                }
            harness(veles).use { h ->
                val fetched = mutableListOf<String>()
                val result = runBlocking { loader(h, fetched, sourceNamespace = "entity").loadNextCache() }!!
                result.keys shouldContainExactly setOf("er.entity.store.state")
                fetched shouldContainExactly listOf(stateSql)
            }
        }

        "one failing read plan loses that vocabulary only" {
            val veles =
                StubVeles().apply {
                    pages =
                        mapOf(
                            "" to
                                page(
                                    listOf(
                                        vocabulary("er.entity.store.state", stateSql),
                                        vocabulary("er.entity.store.store_name", nameSql),
                                    ),
                                ),
                        )
                }
            harness(veles).use { h ->
                val result =
                    runBlocking {
                        loader(h, rows = { sql ->
                            if (sql == nameSql) error("relation does not exist")
                            listOf(ReadRow("1", "TN"))
                        }).loadNextCache()
                    }!!
                result.keys shouldContainExactly setOf("er.entity.store.state")
            }
        }

        "a method this matcher does not know is matched EXACT — never left unauthored (§8)" {
            val veles =
                StubVeles().apply {
                    pages = mapOf("" to page(listOf(vocabulary("er.entity.store.state", stateSql, "SEMANTIC(0.8)"))))
                }
            harness(veles).use { h ->
                val result = runBlocking { loader(h).loadNextCache() }!!
                result.getValue("er.entity.store.state").single().matchMethod shouldBe "EXACT"
            }
        }

        "shared read plans are loaded under both categories, not merged (⚑MV-4)" {
            val veles =
                StubVeles().apply {
                    pages =
                        mapOf(
                            "" to
                                page(
                                    listOf(
                                        vocabulary("er.entity.a.x", stateSql, version = "v1"),
                                        vocabulary("er.entity.b.x", stateSql, version = "v2"),
                                    ),
                                ),
                        )
                }
            harness(veles).use { h ->
                runBlocking { loader(h).loadNextCache() }!!.keys shouldContainExactly
                    setOf("er.entity.a.x", "er.entity.b.x")
            }
        }

        "§9.8 — a Veles that predates member vocabularies (UNIMPLEMENTED): null, RG-FUZ-004" {
            // The bare base class IS what a pre-MV Veles answers: UNIMPLEMENTED for an unknown method.
            harness(object : VelesServiceGrpc.VelesServiceImplBase() {}).use { h ->
                val l = loader(h)
                runBlocking { l.loadNextCache() }.shouldBeNull()
                val warning = l.warnings().single()
                warning.code shouldBe "RG-FUZ-004"
                warning.category shouldBe ""
                warning.message shouldContain "unimplemented"
            }
        }

        "not ready — a message and no items is no listing, not an empty estate: null, RG-FUZ-004" {
            val veles =
                StubVeles().apply {
                    pages =
                        mapOf(
                            "" to
                                ListMemberVocabulariesResponse
                                    .newBuilder()
                                    .addMessages(
                                        ResponseMessage
                                            .newBuilder()
                                            .setSeverity(Severity.WARNING)
                                            .setCode("metadata_not_ready")
                                            .setHumanMessage("No model loaded yet"),
                                    ).build(),
                        )
                }
            harness(veles).use { h ->
                val l = loader(h)
                runBlocking { l.loadNextCache() }.shouldBeNull()
                l.warnings().single().message shouldContain "metadata_not_ready"
            }
        }

        "a transport failure is the same: null, RG-FUZ-004" {
            val veles = StubVeles().apply { error = Status.UNAVAILABLE.withDescription("veles is down") }
            harness(veles).use { h ->
                val l = loader(h)
                runBlocking { l.loadNextCache() }.shouldBeNull()
                l.warnings().single().code shouldBe "RG-FUZ-004"
            }
        }

        "a slow Veles hits the gRPC deadline; the loader signals null within budget" {
            val veles = StubVeles().apply { delayMs = 2_000 }
            harness(veles, timeoutMs = 200).use { h ->
                val start = System.currentTimeMillis()
                runBlocking { loader(h).loadNextCache() }.shouldBeNull()
                (System.currentTimeMillis() - start) shouldBeLessThan 1_500L
            }
        }

        "§9.8 through the repository — the previous load keeps serving, and GetStatus says why" {
            val veles =
                StubVeles().apply {
                    pages =
                        mapOf(
                            "" to
                                page(
                                    listOf(
                                        vocabulary("er.entity.line.sku", "", diagnostics = arrayOf("RG-FUZ-001")),
                                        vocabulary("er.entity.store.state", stateSql),
                                    ),
                                ),
                        )
                }
            harness(veles).use { h ->
                val repo = StringRepository(cfg(), loader(h, rows = { listOf(ReadRow("47", "TN")) }))
                try {
                    runBlocking { repo.forceRefresh() }
                    repo.getCandidates("er.entity.store.state").map { it.value } shouldBe listOf("TN")

                    veles.error = Status.UNIMPLEMENTED
                    runBlocking { repo.forceRefresh() }

                    repo.getCandidates("er.entity.store.state").map { it.value } shouldBe listOf("TN")
                    // The previous load's own warning still describes the cache being served.
                    repo.loaderWarnings().map { it.code } shouldContainExactly listOf("RG-FUZ-001", "RG-FUZ-004")
                } finally {
                    repo.close()
                }
            }
        }

        "a category Veles stops listing is gone after the next load (atomic swap)" {
            val veles =
                StubVeles().apply {
                    pages =
                        mapOf(
                            "" to
                                page(
                                    listOf(
                                        vocabulary("er.entity.a.x", "SELECT 1"),
                                        vocabulary("er.entity.b.y", "SELECT 2"),
                                    ),
                                ),
                        )
                }
            harness(veles).use { h ->
                val repo = StringRepository(cfg(), loader(h))
                try {
                    runBlocking { repo.forceRefresh() }
                    repo.knownCategories() shouldBe setOf("er.entity.a.x", "er.entity.b.y")

                    veles.pages = mapOf("" to page(listOf(vocabulary("er.entity.a.x", "SELECT 1"))))
                    runBlocking { repo.forceRefresh() }
                    repo.knownCategories() shouldBe setOf("er.entity.a.x")
                    repo.getCandidates("er.entity.b.y").shouldBeEmpty()
                } finally {
                    repo.close()
                }
            }
        }

        "T3 — the member version moves with the read plan even when the rows do not, and with the rows" {
            val veles =
                StubVeles().apply {
                    pages =
                        mapOf("" to page(listOf(vocabulary("er.entity.store.state", stateSql, version = "plan-1"))))
                }
            var rows = listOf(ReadRow("47", "TN"))
            harness(veles).use { h ->
                val repo = StringRepository(cfg(), loader(h, rows = { rows }))
                try {
                    fun version() = repo.layerVersions().memberIndexVersions.getValue("er.entity.store.state")
                    runBlocking { repo.forceRefresh() }
                    val first = version()

                    runBlocking { repo.forceRefresh() }
                    version() shouldBe first // nothing changed

                    veles.pages =
                        mapOf(
                            "" to
                                page(
                                    listOf(vocabulary("er.entity.store.state", "$stateSql -- v2", version = "plan-2")),
                                ),
                        )
                    runBlocking { repo.forceRefresh() }
                    val replanned = version()
                    replanned shouldNotBe first // same rows, another plan

                    rows = rows + ReadRow("48", "TX")
                    runBlocking { repo.forceRefresh() }
                    version() shouldNotBe replanned // same plan, other rows
                } finally {
                    repo.close()
                }
            }
        }

        "T6 — GetStatus lists each member category with its method" {
            val veles =
                StubVeles().apply {
                    pages =
                        mapOf(
                            "" to
                                page(
                                    listOf(
                                        vocabulary("er.entity.store.state", stateSql, "EXACT"),
                                        vocabulary("er.entity.store.store_name", nameSql, "TYPOS(1)"),
                                    ),
                                ),
                        )
                }
            harness(veles).use { h ->
                val repo = StringRepository(cfg(), loader(h))
                try {
                    runBlocking { repo.forceRefresh() }
                    repo.categoryStatuses().map {
                        "${it.category} (${it.matchMethod}): ${it.size}"
                    } shouldContainExactly
                        listOf("er.entity.store.state (EXACT): 1", "er.entity.store.store_name (TYPOS(1)): 1")
                    repo.layerVersions().memberIndexVersions shouldNotContainKey "db.dbo.store.s_state"
                } finally {
                    repo.close()
                }
            }
        }
        // ---- review-104 ---------------------------------------------------------------------

        "A-MV-15 (F2) — a member is a VALUE: same-label rows are one candidate, its id the value" {
            val veles =
                StubVeles().apply {
                    pages = mapOf("" to page(listOf(vocabulary("er.entity.store.state", stateSql))))
                }
            harness(veles).use { h ->
                // One row per STORE, as the plan reads them: three stores in Tennessee.
                val storeRows =
                    listOf(ReadRow("1", "TN"), ReadRow("2", "TN"), ReadRow("3", "TX"), ReadRow("4", "TN"))
                val members =
                    runBlocking { loader(h, rows = { storeRows }).loadNextCache() }!!
                        .getValue("er.entity.store.state")

                members.map { it.id to it.value } shouldContainExactly listOf("TN" to "TN", "TX" to "TX")
                // ...each built with its vocabulary's method and category (F12, F13).
                members.map { it.matchMethod to it.category }.distinct() shouldContainExactly
                    listOf("EXACT" to "er.entity.store.state")
            }
        }

        "F5 — a vocabulary Veles lists without a read plan keeps its previous load, and says so" {
            val card = "SELECT \"id\", \"display\" FROM (SELECT …) AS \"src\" GROUP BY 1, 2 ORDER BY 1"
            val veles =
                StubVeles().apply {
                    pages =
                        mapOf(
                            "" to
                                page(
                                    listOf(
                                        vocabulary("er.entity.card.display", card),
                                        vocabulary("er.entity.store.state", stateSql),
                                    ),
                                ),
                        )
                }
            harness(veles).use { h ->
                val l = loader(h, rows = { sql -> listOf(ReadRow("1", if (sql == card) "Gold" else "TN")) })
                val first = runBlocking { l.load() }!!

                // Veles restarts: the query-backed entity is in its parse window, so no read plan.
                veles.pages =
                    mapOf(
                        "" to
                            page(
                                listOf(
                                    vocabulary("er.entity.card.display", "", diagnostics = arrayOf("RG-FUZ-003")),
                                    vocabulary("er.entity.store.state", stateSql),
                                ),
                            ),
                    )
                val second = runBlocking { l.load() }!!

                second.categories.getValue("er.entity.card.display").map { it.value } shouldBe listOf("Gold")
                second.planVersions.getValue("er.entity.card.display") shouldBe
                    first.planVersions.getValue("er.entity.card.display")
                l.warnings().single().message shouldContain "previous load is still served"

                // Veles stops LISTING it: then, and only then, it leaves.
                veles.pages = mapOf("" to page(listOf(vocabulary("er.entity.store.state", stateSql))))
                runBlocking { l.load() }!!.categories.keys shouldContainExactly setOf("er.entity.store.state")
            }
        }

        "F5 — a read plan the warehouse refuses keeps that vocabulary's previous rows" {
            val veles =
                StubVeles().apply {
                    pages =
                        mapOf(
                            "" to
                                page(
                                    listOf(
                                        vocabulary("er.entity.store.state", stateSql),
                                        vocabulary("er.entity.store.store_name", nameSql, "TYPOS(1)"),
                                    ),
                                ),
                        )
                }
            var warehouseDown = false
            harness(veles).use { h ->
                val l =
                    loader(h, rows = { sql ->
                        if (warehouseDown) error("connection refused")
                        listOf(ReadRow("7", if (sql == nameSql) "ought" else "TN"))
                    })
                runBlocking { l.load() }
                warehouseDown = true
                val during = runBlocking { l.load() }!!

                during.categories.mapValues { (_, rows) -> rows.map { it.value } } shouldBe
                    mapOf("er.entity.store.state" to listOf("TN"), "er.entity.store.store_name" to listOf("ought"))
                during.categories
                    .getValue("er.entity.store.store_name")
                    .single()
                    .matchMethod shouldBe "TYPOS(1)"
            }
        }

        "F8 — a model edit that leaves the read plan alone does not move the member version" {
            val veles =
                StubVeles().apply {
                    pages =
                        mapOf("" to page(listOf(vocabulary("er.entity.store.state", stateSql, version = "model-1"))))
                }
            harness(veles).use { h ->
                val repo = StringRepository(cfg(), loader(h, rows = { listOf(ReadRow("47", "TN")) }))
                try {
                    fun version() = repo.layerVersions().memberIndexVersions.getValue("er.entity.store.state")
                    runBlocking { repo.forceRefresh() }
                    val before = version()

                    // Veles' item version hashes the whole model version: a new measure moves it.
                    veles.pages =
                        mapOf("" to page(listOf(vocabulary("er.entity.store.state", stateSql, version = "model-2"))))
                    runBlocking { repo.forceRefresh() }
                    version() shouldBe before

                    // ...while a changed method is a changed plan.
                    veles.pages =
                        mapOf(
                            "" to
                                page(
                                    listOf(
                                        vocabulary("er.entity.store.state", stateSql, "TYPOS(1)", version = "model-2"),
                                    ),
                                ),
                        )
                    runBlocking { repo.forceRefresh() }
                    version() shouldNotBe before
                } finally {
                    repo.close()
                }
            }
        }

        "F12 — a cross-category lookup reports each member's own vocabulary, one row per vocabulary" {
            val categories =
                listOf("er.entity.store.state", "er.entity.customer_address.state", "er.entity.warehouse.state")
            val veles =
                StubVeles().apply {
                    pages = mapOf("" to page(categories.map { vocabulary(it, "SELECT '$it'") }))
                }
            harness(veles).use { h ->
                val repo = StringRepository(cfg(), loader(h, rows = { listOf(ReadRow("1", "TN")) }))
                try {
                    runBlocking { repo.forceRefresh() }
                    // The G3 BROAD round's shape — no categories named — on the engine the service runs
                    // (application.conf: index-first retrieval, v2). The legacy retrieval path seeds by
                    // candidate id, and v2 refuses to start on it.
                    val matcher =
                        FuzzyMatcher(repo, retrievalMode = RetrievalMode.INDEX_FIRST, matchVersion = MatchVersion.V2)
                    val hits = runBlocking { matcher.lookup(LookupQuery("TN")) }.candidates

                    hits.map { it.category } shouldContainExactlyInAnyOrder categories
                    hits.map { it.candidateId }.distinct() shouldContainExactly listOf("TN")
                } finally {
                    repo.close()
                }
            }
        }

        "F14 — alias rows merge under the member category's own spelling, not its lower-cased one" {
            val category = "er.df.Zakaznik.Nazev"
            val veles = StubVeles().apply { pages = mapOf("" to page(listOf(vocabulary(category, nameSql, "TOKENS")))) }
            val decl =
                AliasTableDecl(
                    ownerCategory = category,
                    tableQname =
                        QualifiedName
                            .newBuilder()
                            .setNamespace("dbo")
                            .setName("ZAKAZNIK_ALIAS")
                            .build(),
                    pkColumn = "ID",
                    aliasColumn = "ALIAS",
                )
            harness(veles).use { h ->
                val loaded =
                    runBlocking {
                        loader(
                            h,
                            rows = { sql ->
                                listOf(
                                    if (sql ==
                                        nameSql
                                    ) {
                                        ReadRow("1", "Shell")
                                    } else {
                                        ReadRow("1", "Shelly")
                                    },
                                )
                            },
                            aliasTables = { listOf(decl) },
                        ).loadNextCache()
                    }!!

                loaded.keys shouldContainExactly setOf(category)
                loaded.getValue(category).map { it.value } shouldContainExactly listOf("Shell", "Shelly")
                loaded.getValue(category).map { it.matchMethod }.distinct() shouldContainExactly listOf("TOKENS")
            }
        }

        "F1 — a first boot that races Veles comes up: ready, its declared layer served, RG-FUZ-004 honest" {
            val notReady =
                ListMemberVocabulariesResponse
                    .newBuilder()
                    .addMessages(
                        ResponseMessage
                            .newBuilder()
                            .setSeverity(Severity.WARNING)
                            .setCode("metadata_not_ready")
                            .setHumanMessage("No model loaded yet"),
                    ).build()
            val veles = StubVeles().apply { pages = mapOf("" to notReady) }
            val declared =
                object : SnapshotVocabularySource {
                    override suspend fun fetch() =
                        DeclaredVocabulary(
                            listOf(
                                DeclaredVocabularyEntry(
                                    "md.measure.net",
                                    "md.measure.net",
                                    listOf(DeclaredValue("net", "obrat", SourceTag.DECLARED, "TOKENS")),
                                ),
                            ),
                        )

                    override fun hash() = "sha256:" + "cd".repeat(32)
                }
            harness(veles).use { h ->
                val repo = StringRepository(cfg(), loader(h), snapshotSource = declared)
                try {
                    runBlocking { repo.forceRefresh() }

                    repo.isCatalogReady() shouldBe true
                    repo.getCandidates("md.measure.net").map { it.value } shouldBe listOf("obrat")
                    repo.loaderWarnings().single().message shouldContain "no member vocabulary has been loaded yet"

                    veles.pages = mapOf("" to page(listOf(vocabulary("er.entity.store.state", stateSql))))
                    runBlocking { repo.forceRefresh() }

                    repo.getCandidates("er.entity.store.state").map { it.value } shouldBe listOf("Acme")
                    repo.getCandidates("md.measure.net").map { it.value } shouldBe listOf("obrat")
                    repo.loaderWarnings().shouldBeEmpty()
                } finally {
                    repo.close()
                }
            }
        }

        "F1 — until a member layer first loads, the refresh retries within seconds, not a whole interval" {
            // A Veles that predates ListMemberVocabularies until the test "releases" it.
            val veles = StubVeles().apply { error = Status.UNIMPLEMENTED }
            harness(veles).use { h ->
                // The interval is an hour: only the first-boot backoff can reload inside the test.
                val repo = StringRepository(cfg(refreshIntervalSeconds = 3_600), loader(h), firstMemberRetryMs = 50)
                try {
                    eventually(5.seconds) { repo.isCatalogReady() shouldBe true }
                    repo.knownCategories().shouldBeEmpty()

                    veles.error = null
                    veles.pages = mapOf("" to page(listOf(vocabulary("er.entity.store.state", stateSql))))
                    eventually(5.seconds) {
                        repo.getCandidates("er.entity.store.state").map { it.value } shouldBe listOf("Acme")
                    }
                } finally {
                    repo.close()
                }
            }
        }
    })
