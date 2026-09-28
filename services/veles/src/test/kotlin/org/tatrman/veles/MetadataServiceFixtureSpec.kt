// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles

import org.tatrman.meta.v1.GetModelRequest
import org.tatrman.meta.v1.GetObjectRequest
import org.tatrman.meta.v1.GetSnapshotRequest
import org.tatrman.meta.v1.GetStatusRequest
import org.tatrman.meta.v1.ListObjectsRequest
import org.tatrman.meta.v1.OverallStatus
import org.tatrman.meta.v1.ValidateModelRequest
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.veles.grpc.MetadataServiceImpl
import org.tatrman.veles.grpc.servedIndex
import org.tatrman.veles.grpc.PackageIndex
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import java.nio.file.Path

/**
 * End-to-end-against-the-MVP-surface integration test:
 *  TTR fixture files
 *    -> FileBasedSource (LocalFsStorage + DslParser via ttr-parser lib)
 *    -> ModelReconciler
 *    -> MetadataRegistry (AtomicReference<RegistrySnapshot>)
 *    -> MetadataServiceImpl (gRPC RPCs)
 */
class MetadataServiceFixtureSpec :
    StringSpec({

        val fixtureRoot: Path =
            Path.of(checkNotNull(this::class.java.classLoader.getResource("fixture-model")).toURI())

        fun service(): MetadataServiceImpl {
            val source =
                FileBasedSource(
                    sourceId = "fixture",
                    priority = 100,
                    storage = LocalFsStorage(id = "fixture", rootPath = fixtureRoot),
                )
            val reconciler = ModelReconciler(ModelDescriptor(id = "test", name = "test", description = "fixture"))
            val snapshot = source.load()
            val result = reconciler.reconcile(listOf(snapshot))
            val registry = MetadataRegistry()
            registry.swap(result.model, ModelGraph.build(result.model), result.warnings + result.errors)
            return MetadataServiceImpl(registry, packageIndex = servedIndex(snapshot))
        }

        "GetModel(packages=[shop]) returns non-empty ModelBundle from the fixture" {
            val r = service().getModel(GetModelRequest.newBuilder().addPackages("shop").build())
            r.model.packageVersionsList.isEmpty() shouldBe false
            r.model.entitiesList.size shouldBeGreaterThan 0
            // M3: lock down the PackageVersion shape — name echoed, hash is a real sha256 hex.
            r.model.packageVersionsList[0].packageName shouldBe "shop"
            r.model.packageVersionsList[0]
                .contentHash.length shouldBe 64
            r.model.packageVersionsList[0]
                .contentHash
                .all { it in "0123456789abcdef" } shouldBe true
        }

        "GetModel classifies query with search.patterns into pattern_queries, one without into named_queries" {
            // The Hartland demo path: a query's `search { patterns }` must reach the
            // ModelBundle so Golem/Themis see it as a pattern (not free-SQL) query.
            val r =
                service().getModel(
                    GetModelRequest
                        .newBuilder()
                        .addPackages("shop")
                        .setIncludeSearchHints(true)
                        .build(),
                )
            val patternNames = r.model.patternQueriesList.map { it.objectDescriptor.localName }
            val namedNames = r.model.namedQueriesList.map { it.objectDescriptor.localName }

            patternNames shouldContain "revenue_by_channel"
            patternNames shouldNotContain "all_customers"
            namedNames shouldContain "all_customers"
            namedNames shouldNotContain "revenue_by_channel"

            // The patterns themselves must survive into the served bundle query.
            val served = r.model.patternQueriesList.first { it.objectDescriptor.localName == "revenue_by_channel" }
            served.queryDescriptor.search.patternsList shouldContainAll
                listOf("revenue (per|by) channel", "sales by channel")
        }

        "GetModel rejects empty packages list with EMPTY_PACKAGES error" {
            val r = service().getModel(GetModelRequest.newBuilder().build())
            r.messagesList.map { it.code } shouldContainAll listOf("EMPTY_PACKAGES")
        }

        "ListObjects returns the customers + orders tables and their columns" {
            val r = service().listObjects(ListObjectsRequest.getDefaultInstance())
            r.itemsList shouldHaveAtLeastSize 1
            // The fixture has 2 tables + 6 columns + 1 entity + 3 attributes = 12 objects.
            r.pageInfo.totalCount shouldBeGreaterThan 5
        }

        "ListObjects filters by kind" {
            val r =
                service().listObjects(
                    ListObjectsRequest.newBuilder().setKind("table").build(),
                )
            // Two tables in the fixture (customers, orders).
            r.itemsList.size shouldBe 2
            r.itemsList.all { it.kind == "table" } shouldBe true
        }

        "ListObjects filters by tag" {
            val r =
                service().listObjects(
                    ListObjectsRequest.newBuilder().addTags("core").build(),
                )
            r.itemsList shouldHaveAtLeastSize 2
            r.itemsList.all { it.tagsList.contains("core") } shouldBe true
        }

        "GetObject returns descriptor by qname" {
            val qn =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                    .setNamespace("dbo")
                    .setName("customers")
                    .build()
            val r = service().getObject(GetObjectRequest.newBuilder().setQualifiedName(qn).build())
            r.objectDescriptor.localName shouldBe "customers"
            r.objectDescriptor.kind shouldBe "table"
            r.messagesList.size shouldBe 0
        }

        "GetObject on a db table returns DbTableDetail with columns + primary key (DF-M07)" {
            val qn =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                    .setNamespace("dbo")
                    .setName("customers")
                    .build()
            val r = service().getObject(GetObjectRequest.newBuilder().setQualifiedName(qn).build())
            r.hasTable() shouldBe true
            r.table.columnsList.map { it.name } shouldContainAll listOf("id", "name", "tenant_id")
            r.table.primaryKeyList shouldBe listOf("id")
            r.table.columnsList
                .first { it.name == "id" }
                .dataType
                .shouldNotBeEmpty()
        }

        "GetObject on a db column returns DbColumnDetail (DF-M07)" {
            val svc = service()
            val anyColumn =
                svc.listObjects(ListObjectsRequest.newBuilder().setKind("column").build()).itemsList.first()
            val r = svc.getObject(GetObjectRequest.newBuilder().setQualifiedName(anyColumn.qualifiedName).build())
            r.hasColumn() shouldBe true
            r.column.dataType.shouldNotBeEmpty()
        }

        "GetSnapshot entries carry per-kind detail for db tables (DF-M07)" {
            val snap = service().getSnapshot(GetSnapshotRequest.getDefaultInstance())
            val customers = snap.snapshot.objectsList.first { it.objectDescriptor.localName == "customers" }
            customers.hasTable() shouldBe true
            customers.table.columnsList.size shouldBe 3
        }

        "GetObject reports object_not_found via messages, NOT a gRPC error" {
            val qn =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                    .setNamespace("dbo")
                    .setName("does_not_exist")
                    .build()
            val r = service().getObject(GetObjectRequest.newBuilder().setQualifiedName(qn).build())
            r.messagesList.size shouldBe 1
            r.messagesList[0].code shouldBe "object_not_found"
        }

        "GetSnapshot returns 304-equivalent (notModified) for matching ETag" {
            val svc = service()
            val first = svc.getSnapshot(GetSnapshotRequest.getDefaultInstance())
            first.notModified shouldBe false
            first.etag.shouldNotBeEmpty()

            val second =
                svc.getSnapshot(GetSnapshotRequest.newBuilder().setIfNoneMatch(first.etag).build())
            second.notModified shouldBe true
            second.etag shouldBe first.etag
        }

        "GetStatus reports overall OK once the snapshot is loaded" {
            val r = service().getStatus(GetStatusRequest.getDefaultInstance())
            r.modelLoaded shouldBe true
            r.overallStatus shouldBe OverallStatus.OK
            r.modelVersion.shouldNotBeEmpty()
        }

        "GetStatus reports DEGRADED when no snapshot has been loaded" {
            val empty = MetadataServiceImpl(MetadataRegistry(), packageIndex = PackageIndex())
            val r = empty.getStatus(GetStatusRequest.getDefaultInstance())
            r.modelLoaded shouldBe false
            r.overallStatus shouldBe OverallStatus.DEGRADED
        }

        // MS (ttr-semantics v3, catalog 0.13.2): the fixture is no longer warning-FREE, and
        // that is the designed behaviour rather than fixture rot. `er-customer.ttr` declares
        // the legacy `nameAttribute:`, which v3 deprecates in favour of `semantics { name: }`
        // (MS contracts §1.2) — a WARNING, deliberately not an error, so an estate still
        // writing the legacy property keeps loading. The fixture KEEPS the legacy spelling on
        // purpose: it is this repo's only coverage of that path, and pinning the exact issue
        // is worth more than pinning a zero. Errors must still be zero.
        "ValidateModel returns errors_count = 0, and the only warning is the v3 deprecation" {
            val r = service().validateModel(ValidateModelRequest.getDefaultInstance())
            r.errorsCount shouldBe 0
            r.warningsCount shouldBe 1
            val issue = r.issuesList.single()
            issue.humanMessage shouldContain "TTR-SEM-218"
            issue.sourceFile shouldContain "er-customer"
        }

        "ListObjects pagination — small page size advances via page tokens" {
            val svc = service()
            val first =
                svc.listObjects(
                    ListObjectsRequest
                        .newBuilder()
                        .setPage(
                            org.tatrman.meta.v1.PageRequest
                                .newBuilder()
                                .setPageSize(2),
                        ).build(),
                )
            first.itemsList.size shouldBe 2
            first.pageInfo.nextPageToken.shouldNotBeEmpty()

            val second =
                svc.listObjects(
                    ListObjectsRequest
                        .newBuilder()
                        .setPage(
                            org.tatrman.meta.v1.PageRequest
                                .newBuilder()
                                .setPageSize(2)
                                .setPageToken(first.pageInfo.nextPageToken),
                        ).build(),
                )
            second shouldNotBe null
            second.itemsList.size shouldBeGreaterThan 0
        }

        // ---- Phase 07 B1 — TraverseEdges over the fixture model ----

        "TraverseEdges OUTGOING from a column reaches its table via DEFINES" {
            val svc = service()
            // Column qnames in the fixture are table-qualified: db.dbo.customers.id
            val columnQname =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                    .setNamespace("dbo")
                    .setName("customers.id")
                    .build()
            val r =
                svc.traverseEdges(
                    org.tatrman.meta.v1.TraverseEdgesRequest
                        .newBuilder()
                        .setFromQualifiedName(columnQname)
                        .setMaxDepth(1)
                        .addEdgeTypes(org.tatrman.meta.v1.EdgeType.DEFINES)
                        .build(),
                )
            r.edgesList.size shouldBe 1
            val edge = r.edgesList.first()
            edge.type shouldBe org.tatrman.meta.v1.EdgeType.DEFINES
            edge.depth shouldBe 1
            edge.source.qualifiedName.name shouldBe "customers.id"
            edge.target.qualifiedName.name shouldBe "customers"
            edge.target.kind shouldBe "table"
        }

        "TraverseEdges INCOMING from a table reaches all its columns" {
            val svc = service()
            val tableQname =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                    .setNamespace("dbo")
                    .setName("customers")
                    .build()
            val r =
                svc.traverseEdges(
                    org.tatrman.meta.v1.TraverseEdgesRequest
                        .newBuilder()
                        .setFromQualifiedName(tableQname)
                        .setDirection(org.tatrman.meta.v1.Direction.INCOMING)
                        .setMaxDepth(1)
                        .build(),
                )
            // customers has 3 columns: id, name, tenant_id.
            r.edgesList.size shouldBe 3
            r.edgesList.all { it.type == org.tatrman.meta.v1.EdgeType.DEFINES } shouldBe true
            r.edgesList.map { it.target.qualifiedName.name }.toSet() shouldBe setOf("customers")
            r.edgesList.map { it.source.qualifiedName.name }.toSet() shouldBe
                setOf("customers.id", "customers.name", "customers.tenant_id")
        }

        "TraverseEdges from an unknown qname returns no edges + object_not_found warning" {
            val svc = service()
            val r =
                svc.traverseEdges(
                    org.tatrman.meta.v1.TraverseEdgesRequest
                        .newBuilder()
                        .setFromQualifiedName(
                            QualifiedName
                                .newBuilder()
                                .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                                .setNamespace("dbo")
                                .setName("ghost")
                                .build(),
                        ).setMaxDepth(1)
                        .build(),
                )
            r.edgesList.size shouldBe 0
            r.messagesList.map { it.code } shouldContainAll listOf("object_not_found")
        }

        "TraverseEdges max_depth bound is respected" {
            val svc = service()
            val tableQname =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                    .setNamespace("dbo")
                    .setName("customers")
                    .build()
            // depth=0 → request normalises to 1 internally; the test confirms the cap path works
            // by walking depth=10 (capped at MAX_DEPTH_CAP=10) and finding only direct neighbours
            // since the fixture has no chained edges.
            val r =
                svc.traverseEdges(
                    org.tatrman.meta.v1.TraverseEdgesRequest
                        .newBuilder()
                        .setFromQualifiedName(tableQname)
                        .setDirection(org.tatrman.meta.v1.Direction.INCOMING)
                        .setMaxDepth(10)
                        .build(),
                )
            // Each column edge is at depth 1; columns don't have further inbound edges in this fixture.
            r.edgesList.all { it.depth == 1 } shouldBe true
        }
    })
