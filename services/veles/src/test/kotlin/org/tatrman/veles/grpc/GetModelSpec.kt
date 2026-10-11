// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.grpc

import org.tatrman.meta.v1.GetModelRequest
import org.tatrman.meta.v1.Language
import org.tatrman.meta.v1.ListQueriesRequest
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class GetModelSpec :
    StringSpec({

        val fixtureRoot: Path =
            Path
                .of(checkNotNull(this::class.java.classLoader.getResource("model-seed/returns")).toURI())
                .parent

        fun service(): MetadataServiceImpl {
            val source =
                FileBasedSource(
                    sourceId = "model-seed",
                    priority = 100,
                    storage = LocalFsStorage(id = "model-seed", rootPath = fixtureRoot),
                )
            val reconciler =
                ModelReconciler(
                    ModelDescriptor(id = "test", name = "test", description = "Hartland seed fixture"),
                )
            val snapshot = source.load()
            val result = reconciler.reconcile(listOf(snapshot))
            val registry = MetadataRegistry()
            registry.swap(result.model, ModelGraph.build(result.model), result.warnings + result.errors)
            return MetadataServiceImpl(registry, packageIndex = servedIndex(snapshot))
        }

        // The TPC-DS seed lives in its own tree: its tables share their names with the Hartland
        // slice above (both are TPC-DS), and one model holds one definition per qualified name.
        fun tpcdsService(): MetadataServiceImpl {
            val root: Path =
                Path.of(checkNotNull(this::class.java.classLoader.getResource("model-tpcds/tpcds")).toURI()).parent
            val source =
                FileBasedSource(
                    sourceId = "model-tpcds",
                    priority = 100,
                    storage = LocalFsStorage(id = "model-tpcds", rootPath = root),
                )
            val snapshot = source.load()
            val result = ModelReconciler(ModelDescriptor(id = "test", name = "test")).reconcile(listOf(snapshot))
            val registry = MetadataRegistry()
            registry.swap(result.model, ModelGraph.build(result.model), result.warnings + result.errors)
            return MetadataServiceImpl(registry, packageIndex = servedIndex(snapshot))
        }

        "GetModel(packages=[returns]) returns non-empty ModelBundle" {
            val svc = service()
            val resp =
                svc.getModel(
                    GetModelRequest
                        .newBuilder()
                        .addPackages("returns")
                        .setIncludeSearchHints(true)
                        .setIncludeRoles(true)
                        .setIncludeDrillMap(true)
                        .build(),
                )

            val entityCount = resp.model.entitiesList.size
            (entityCount >= 7) shouldBe true
            val relationCount = resp.model.relationsList.size
            (relationCount >= 8) shouldBe true
            val queryCount = resp.model.patternQueriesList.size
            (queryCount >= 5) shouldBe true
            resp.model.packageVersionsList.size shouldBe 1
            resp.model.packageVersionsList[0].packageName shouldBe "returns"
            resp.model.packageVersionsList[0]
                .contentHash
                .isEmpty() shouldBe false
            resp.model.packageVersionsList[0]
                .loadedAt
                .isEmpty() shouldBe false
        }

        // WS-T2 T1 — the TPC-DS seed package loads cleanly: exactly the seven star
        // tables resolve (the three sales facts + the four shared dimensions), all
        // sourced from the tpcds package. This is a db-only package (raw-SQL curated
        // queries over physical tables — no ER layer), so `relations` (ER-derived,
        // MetadataServiceImpl §260) is not the guard here; FK join behaviour is
        // exercised end-to-end by the Translate unparse + query smoke (T4/T7).
        "GetModel(packages=[tpcds]) loads the TPC-DS seven-table star" {
            val svc = tpcdsService()
            val resp =
                svc.getModel(
                    GetModelRequest
                        .newBuilder()
                        .addPackages("tpcds")
                        .build(),
                )

            val tableNames =
                resp.model.tablesList
                    .filter { it.objectDescriptor.qualifiedName.schemaCode == SchemaCode.DB }
                    .map { it.objectDescriptor.qualifiedName.name }
                    .toSet()
            tableNames shouldContainAll
                setOf("store_sales", "catalog_sales", "web_sales", "date_dim", "item", "customer", "store")
            tableNames.size shouldBe 7

            resp.model.packageVersionsList.map { it.packageName } shouldContain "tpcds"
            resp.model.tablesList.all { it.objectDescriptor.sourceFile.contains("/tpcds/") } shouldBe true
        }

        // WS-T2 T3 — the four curated queries are registered under the tpcds tag,
        // each SQL with its single `{year}` param. (Parse→RelNode is validated
        // separately by TpcdsQueryParseSpec, which runs the parse worker; the bare
        // reconcile here leaves queries PENDING.)
        "ListQueries(tags=[tpcds]) returns the four curated queries with their params" {
            val svc = tpcdsService()
            val r = svc.listQueries(ListQueriesRequest.newBuilder().addTags("tpcds").build())
            val byName = r.itemsList.associateBy { it.objectDescriptor.localName }

            byName.keys shouldContainAll
                setOf(
                    "store_sales_by_month",
                    "top_items_by_revenue",
                    "customer_running_total",
                    "channel_revenue_cte",
                )
            listOf(
                "store_sales_by_month",
                "top_items_by_revenue",
                "customer_running_total",
                "channel_revenue_cte",
            ).forEach { name ->
                byName.getValue(name).let {
                    it.sourceLanguage shouldBe Language.SQL
                    it.parameterCount shouldBe 1
                }
            }
        }

        "entities list contains an entity with the `distribution center` alias when include_search_hints=true" {
            val svc = service()
            val resp =
                svc.getModel(
                    GetModelRequest
                        .newBuilder()
                        .addPackages("returns")
                        .setIncludeSearchHints(true)
                        .build(),
                )

            val hasDistributionCenterAlias =
                resp.model.entitiesList.any { entity ->
                    entity.detail.aliasesList.contains("distribution center")
                }
            hasDistributionCenterAlias shouldBe true
        }

        "PF-1: tables includes ALL tables in the package, not just bound ones (incl. date_dim)" {
            // date_dim and item are declared in returns/db.ttr but their entities (calendar,
            // item) bind to *__filter sqlQueries instead of directly to these tables — so the
            // tables are unbound from the ER perspective. PF-1 requires them in the bundle anyway.
            val svc = service()
            val resp =
                svc.getModel(
                    GetModelRequest
                        .newBuilder()
                        .addPackages("returns")
                        .build(),
                )

            val tableLocalNames =
                resp.model.tablesList
                    .filter { it.objectDescriptor.qualifiedName.schemaCode == SchemaCode.DB }
                    .map { it.objectDescriptor.qualifiedName.name }
                    .toSet()

            tableLocalNames shouldContain "date_dim"
            tableLocalNames shouldContain "item"

            resp.model.tablesList.all { it.objectDescriptor.sourceFile.contains("/returns/") } shouldBe true
        }

        "A3: namedQueries holds synthetic *__filter queries; patternQueries always have search.patterns" {
            val svc = service()
            val resp =
                svc.getModel(
                    GetModelRequest
                        .newBuilder()
                        .addPackages("returns")
                        .setIncludeSearchHints(true)
                        .build(),
                )

            val namedLocal =
                resp.model.namedQueriesList
                    .map { it.objectDescriptor.qualifiedName.name }
                    .toSet()
            namedLocal shouldContain "item__filter"
            namedLocal shouldContain "calendar__filter"

            // Every pattern_query must have at least one search.patterns entry (that's the
            // discriminator) — even after locale/search-hint stripping, the partition
            // happened on the domain object first.
            resp.model.patternQueriesList.all {
                it.queryDescriptor.search.patternsList
                    .isNotEmpty()
            } shouldBe true
        }

        "A4: include_search_hints=false strips SearchHints + entity-level aliases" {
            val svc = service()
            val resp =
                svc.getModel(
                    GetModelRequest
                        .newBuilder()
                        .addPackages("returns")
                        .setIncludeSearchHints(false)
                        .setIncludeRoles(true)
                        .build(),
                )

            resp.model.entitiesList.all {
                !it.detail.hasSearch() && it.detail.aliasesList.isEmpty()
            } shouldBe true
            resp.model.rolesList.all { !it.hasSearch() } shouldBe true
            resp.model.patternQueriesList.all { !it.queryDescriptor.hasSearch() } shouldBe true
            resp.model.namedQueriesList.all { !it.queryDescriptor.hasSearch() } shouldBe true
        }

        "A4: include_roles=false omits roles entirely" {
            val svc = service()
            val resp =
                svc.getModel(
                    GetModelRequest
                        .newBuilder()
                        .addPackages("returns")
                        .setIncludeRoles(false)
                        .build(),
                )

            resp.model.rolesList.isEmpty() shouldBe true
        }

        "A4: locale=cs narrows LocalizedString fields to that one BCP-47 key" {
            val svc = service()
            val resp =
                svc.getModel(
                    GetModelRequest
                        .newBuilder()
                        .addPackages("returns")
                        .setLocale("cs")
                        .setIncludeRoles(true)
                        .build(),
                )

            // EntityDetail.display_label and RoleDetail.label: every populated map must
            // contain only "cs" (other keys dropped).
            resp.model.entitiesList
                .filter {
                    it.detail.hasDisplayLabel() &&
                        it.detail.displayLabel.byLanguageMap
                            .isNotEmpty()
                }.all { it.detail.displayLabel.byLanguageMap.keys == setOf("cs") } shouldBe true
            resp.model.rolesList
                .filter { it.hasLabel() && it.label.byLanguageMap.isNotEmpty() }
                .all { it.label.byLanguageMap.keys == setOf("cs") } shouldBe true
        }

        "A2: content_hash is stable across reloads of the same fixture, and a real sha256 hex" {
            val a = service().getModel(GetModelRequest.newBuilder().addPackages("returns").build())
            val b = service().getModel(GetModelRequest.newBuilder().addPackages("returns").build())

            a.model.packageVersionsList[0].contentHash shouldBe b.model.packageVersionsList[0].contentHash
            a.model.packageVersionsList[0]
                .contentHash.length shouldBe 64
            a.model.packageVersionsList[0]
                .contentHash
                .all { it in "0123456789abcdef" } shouldBe true
        }

        "Stage 04 — ModelBundleEntity carries qname via ObjectDescriptor" {
            val svc = service()
            val resp =
                svc.getModel(
                    GetModelRequest
                        .newBuilder()
                        .addPackages("returns")
                        .build(),
                )

            // Every bundle entity now has a populated ObjectDescriptor (qname etc.).
            resp.model.entitiesList.all {
                it.hasObjectDescriptor() &&
                    it.objectDescriptor.qualifiedName.name
                        .isNotEmpty()
            } shouldBe true
        }

        "Stage 04 — ModelBundleQuery carries source_text + parameters" {
            val svc = service()
            val resp =
                svc.getModel(
                    GetModelRequest
                        .newBuilder()
                        .addPackages("returns")
                        .build(),
                )

            // The detail pattern declares one parameter (order_number); make sure
            // the bundle wire actually carries it.
            val detail =
                resp.model.patternQueriesList.firstOrNull {
                    it.objectDescriptor.qualifiedName.name == "return_detail"
                }
            checkNotNull(detail) { "return_detail pattern should be in the bundle" }
            detail.parametersList.size shouldBe 1
            detail.parametersList[0].name shouldBe "order_number"
            detail.sourceText.isNotEmpty() shouldBe true
        }

        // Drill-map LOADING test removed at fork Stage 2.1 (2026-06-13): drill-map
        // definition & usage is being revisited from the product side before the loaded
        // shape is worth asserting. Deferred to kantheon-v1.1 §8 (Veles / model graph).
        // The proto (`DrillMapDetail`) and loader stay in tree; only the load assertion is
        // dropped. The flag-gating test below (include_drill_map=false) is kept.

        "Stage 03 — drill maps are omitted when include_drill_map=false" {
            val svc = service()
            val resp =
                svc.getModel(
                    GetModelRequest
                        .newBuilder()
                        .addPackages("returns")
                        .setIncludeDrillMap(false)
                        .build(),
                )

            resp.model.drillMapsList.size shouldBe 0
        }

        "empty packages list returns ERROR message" {
            val svc = service()
            val resp =
                svc.getModel(
                    GetModelRequest.newBuilder().build(),
                )

            val msgCount = resp.messagesList.size
            (msgCount >= 1) shouldBe true
            resp.messagesList[0].severity shouldBe Severity.ERROR
            resp.messagesList[0].code shouldBe "EMPTY_PACKAGES"
        }
    })
