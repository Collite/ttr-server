// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.grpc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.tatrman.meta.v1.ListMemberVocabulariesRequest
import org.tatrman.meta.v1.MemberVocabulary
import org.tatrman.meta.v1.PageRequest
import org.tatrman.ttr.metadata.LoadIssue
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.BuiltinStockSource
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Files
import java.nio.file.Path

/**
 * MV-T1 T4–T6 (member-vocabulary contracts §3, §9) — Veles publishes member vocabularies.
 *
 * One item per carrier with a member vocabulary, keyed by the ATTRIBUTE, with a read plan the
 * translator rendered from the entity — so two entities over one table are two vocabularies, a
 * view- or query-backed entity reads its own population, and the loader never composes SQL.
 */
class ListMemberVocabulariesSpec :
    StringSpec({

        val fixtureRoot: Path =
            Path.of(checkNotNull(this::class.java.classLoader.getResource("fixture-member-vocab/mv")).toURI()).parent

        fun service(): MetadataServiceImpl {
            val source =
                FileBasedSource(
                    sourceId = "mv",
                    priority = 100,
                    storage = LocalFsStorage(id = "mv", rootPath = fixtureRoot),
                )
            val result = ModelReconciler(ModelDescriptor(id = "mv", name = "mv")).reconcile(listOf(source.load()))
            check(result.errors.isEmpty()) { "fixture failed to load: ${result.errors}" }
            val registry = MetadataRegistry()
            registry.swap(result.model, ModelGraph.build(result.model), result.warnings)
            return MetadataServiceImpl(registry)
        }

        /**
         * An estate's `model/` tree, loaded the way `ttr-lexicon build` loads it (stock roles + the
         * files; a flat-package estate's package/directory mismatch tolerated, nothing else).
         */
        fun estateService(modelRoot: Path): MetadataServiceImpl {
            val result =
                MetadataLoader(
                    listOf(
                        BuiltinStockSource(),
                        FileBasedSource(
                            sourceId = "repo",
                            priority = 100,
                            storage = LocalFsStorage(id = "repo", rootPath = modelRoot),
                        ),
                    ),
                ).load()
            val fatal = result.errors.filter { it.category != LoadIssue.Category.PACKAGE_MISMATCH }
            val model = checkNotNull(result.model) { "estate model failed to load: $fatal" }
            check(fatal.isEmpty()) { "estate model failed to load: $fatal" }
            val registry = MetadataRegistry()
            registry.swap(model, ModelGraph.build(model), emptyList())
            return MetadataServiceImpl(registry)
        }

        suspend fun MetadataServiceImpl.all(dialect: String = "POSTGRESQL"): List<MemberVocabulary> =
            listMemberVocabularies(ListMemberVocabulariesRequest.newBuilder().setDialect(dialect).build()).itemsList

        // MV_MODEL_ROOT=<repo>/model points the dump at a real estate's model tree instead (the T7
        // hartland smoke); only this test reads it.
        "the listing, dumped (MV_DUMP=<file>, optional MV_MODEL_ROOT=<dir>) — the record T7 pastes" {
            val items = (System.getenv("MV_MODEL_ROOT")?.let { estateService(Path.of(it)) } ?: service()).all()
            System.getenv("MV_DUMP")?.let { out ->
                Files.writeString(
                    Path.of(out),
                    items.joinToString("\n\n") {
                        "${it.category}  key=${it.keyAttribute}  method=${it.matchMethod}\n" +
                            "  read_sql: ${it.readSql}\n" +
                            "  diagnostics: ${it.diagnosticsList.map { d -> d.code + ": " + d.humanMessage }}"
                    },
                )
            }
            items.size shouldNotBe 0
        }

        "T4 — one item per carrier, sorted by category; a bare `searchable` is not one; EXACT is" {
            service().all().map { it.category } shouldContainExactly
                listOf(
                    "db.dbo.ledger.account",
                    "er.entity.brand_rival.rival_name",
                    "er.entity.open_store.name",
                    "er.entity.order_line.sku_label",
                    "er.entity.store.state",
                    "er.entity.store.store_name",
                    "er.entity.store_card.display",
                    "er.entity.supplier_rival.rival_name",
                )
        }

        "the item carries its attribute, its owner, its key and its method" {
            val state = service().all().single { it.category == "er.entity.store.state" }
            state.entity.name shouldBe "store"
            state.attribute.name shouldBe "store.state"
            state.keyAttribute shouldBe "er.entity.store.store_id"
            state.matchMethod shouldBe "EXACT"
            state.dialect shouldBe "POSTGRESQL"
        }

        "§9.1 — two entities over one table are TWO vocabularies, each reading its own population" {
            val items = service().all().associateBy { it.category }
            val supplier = items.getValue("er.entity.supplier_rival.rival_name")
            val brand = items.getValue("er.entity.brand_rival.rival_name")
            supplier.readSql shouldContain "supplier_rivals"
            brand.readSql shouldContain "brand_rivals"
            supplier.version shouldNotBe brand.version
        }

        "T5 — a table-backed entity renders to PHYSICAL names: key first, one row per (key, value), by key" {
            // Pinned from the translator's own output (MV_DUMP). Calcite spells DISTINCT as a GROUP BY
            // over the two projected columns — the same rows — and aliases the physical columns back to
            // the attribute names (`id AS store_id`, `name AS store_name`).
            service().all().single { it.category == "er.entity.store.store_name" }.readSql shouldBe
                "SELECT \"id\" AS \"store_id\", \"name\" AS \"store_name\"\nFROM \"stores\"\n" +
                "GROUP BY \"id\", \"name\"\nORDER BY \"id\""
        }

        "T5 — the dialect is the caller's: MSSQL brackets, POSTGRESQL double quotes" {
            val svc = service()
            svc.all("MSSQL").single { it.category == "er.entity.store.state" }.readSql shouldContain "[stores]"
            svc.all("POSTGRESQL").single { it.category == "er.entity.store.state" }.readSql shouldContain "\"stores\""
        }

        "RG-FUZ-001 — a composite key: listed, no key, no read plan" {
            val line = service().all().single { it.category == "er.entity.order_line.sku_label" }
            line.keyAttribute shouldBe ""
            line.readSql shouldBe ""
            line.diagnosticsList.map { it.code } shouldBe listOf("RG-FUZ-001")
        }

        "RG-FUZ-003 — an expression-mapped attribute: listed, no read plan, the reason named" {
            val display = service().all().single { it.category == "er.entity.store_card.display" }
            display.readSql shouldBe ""
            display.diagnosticsList.map { it.code } shouldBe listOf("RG-FUZ-003")
            display.diagnosticsList.single().humanMessage shouldContain "expression"
        }

        "§9.2 — a query-backed entity: listed, RG-FUZ-003 — the query path cannot render it either (FINDING)" {
            // GetSnapshot leaves `QueryDetail.canonical_form` unset, so the translator's adapter holds an
            // EMPTY plan for every saved query and any ER query over a query-backed entity fails the
            // same way at query time. Rendering it here by another route would be exactly the drift
            // this RPC exists to prevent; the diagnostic names the translator's own error instead.
            val open = service().all().single { it.category == "er.entity.open_store.name" }
            open.readSql shouldBe ""
            open.diagnosticsList.map { it.code } shouldBe listOf("RG-FUZ-003")
            open.diagnosticsList.single().humanMessage shouldContain "NODE_NOT_SET"
        }

        "the db-only estate: an indexed column no attribute backs reads its table, keyed by its PK" {
            val account = service().all().single { it.category == "db.dbo.ledger.account" }
            account.keyAttribute shouldBe "db.dbo.ledger.id"
            account.matchMethod shouldBe "TYPOS(2)"
            account.readSql shouldBe
                "SELECT \"id\", \"account\"\nFROM \"ledger\"\nGROUP BY \"id\", \"account\"\nORDER BY \"id\""
        }

        "version = SHA-256 over (model version, read_sql, key, method): stable across calls, moves with the read plan" {
            val svc = service()
            val pg = svc.all("POSTGRESQL").single { it.category == "er.entity.store.state" }
            svc.all("POSTGRESQL").single { it.category == "er.entity.store.state" }.version shouldBe pg.version
            svc.all("MSSQL").single { it.category == "er.entity.store.state" }.version shouldNotBe pg.version
            pg.version shouldStartWith "sha256:"
        }

        "T6 — paging across pages is exhaustive, stable and in category order" {
            val svc = service()
            val expected = svc.all().map { it.category }
            val seen = mutableListOf<String>()
            var token = ""
            var pages = 0
            do {
                val resp =
                    svc.listMemberVocabularies(
                        ListMemberVocabulariesRequest
                            .newBuilder()
                            .setDialect("POSTGRESQL")
                            .setPage(PageRequest.newBuilder().setPageSize(3).setPageToken(token))
                            .build(),
                    )
                seen += resp.itemsList.map { it.category }
                resp.pageInfo.totalCount shouldBe expected.size
                token = resp.pageInfo.nextPageToken
                pages++
            } while (token.isNotEmpty())
            pages shouldBe 3
            seen shouldBe expected
        }

        "T6 — not ready ⇒ the standard not-ready message, no items" {
            val resp =
                MetadataServiceImpl(
                    MetadataRegistry(),
                ).listMemberVocabularies(ListMemberVocabulariesRequest.getDefaultInstance())
            resp.itemsCount shouldBe 0
            resp.messagesList.map { it.code } shouldBe listOf("metadata_not_ready")
        }

        "an unknown dialect is refused by name; \"\" is the translator default" {
            val svc = service()
            val refused =
                svc.listMemberVocabularies(
                    ListMemberVocabulariesRequest.newBuilder().setDialect("ORACLE").build(),
                )
            refused.itemsCount shouldBe 0
            refused.messagesList.map { it.code } shouldBe listOf("unknown_dialect")
            svc.all("").first().dialect shouldBe "MSSQL"
        }
    })
