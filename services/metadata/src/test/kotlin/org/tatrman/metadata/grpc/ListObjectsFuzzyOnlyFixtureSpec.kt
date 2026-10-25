// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.grpc

import org.tatrman.meta.v1.GetObjectRequest
import org.tatrman.meta.v1.ListObjectsRequest
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

/**
 * Phase 1, task 1-7 — end-to-end load-path verification of the `fuzzy: true` flag.
 *
 * Replaces the manual `grpcurl` step with an automated, CI-run check that exercises
 * the real chain a deployed metadata service uses:
 *
 *   TTR fixture (`fixture-fuzzy/db.ttr`, one column tagged `search { fuzzy: true }`)
 *     -> FileBasedSource (DslParser via ttr-parser lib)
 *     -> ModelReconciler
 *     -> MetadataRegistry
 *     -> MetadataServiceImpl
 *
 * Proves the flag survives parse → model (`DbColumn.search`) → proto (`DbColumnDetail.search`)
 * → both the `ListObjects(fuzzy_only=true)` filter and the `GetObject` mapper. The two
 * sibling specs in this package (`GetObjectColumnSearchHintsSpec`, `ListObjectsFuzzyOnlyFilterSpec`)
 * cover the wiring against hand-built in-memory models; this one closes the loop against a file.
 */
class ListObjectsFuzzyOnlyFixtureSpec :
    StringSpec({

        val fixtureRoot: Path =
            Path.of(checkNotNull(this::class.java.classLoader.getResource("fixture-fuzzy")).toURI())

        fun service(): MetadataServiceImpl {
            val source =
                FileBasedSource(
                    sourceId = "fixture-fuzzy",
                    priority = 100,
                    storage = LocalFsStorage(id = "fixture-fuzzy", rootPath = fixtureRoot),
                )
            val reconciler = ModelReconciler(ModelDescriptor(id = "test", name = "test", description = "fuzzy fixture"))
            val result = reconciler.reconcile(listOf(source.load()))
            val registry = MetadataRegistry()
            registry.swap(result.model, ModelGraph.build(result.model), result.warnings + result.errors)
            return MetadataServiceImpl(registry, packageIndex = PackageIndex())
        }

        fun columnQn(name: String): QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.DB)
                .setNamespace("dbo")
                .setName(name)
                .build()

        "ListObjects(kind=column, fuzzy_only=true) returns only the TTR-tagged fuzzy column" {
            val resp =
                service().listObjects(
                    ListObjectsRequest
                        .newBuilder()
                        .setKind("column")
                        .setFuzzyOnly(true)
                        .build(),
                )

            resp.itemsList.map { it.qualifiedName.name } shouldBe listOf("products.name")
        }

        "ListObjects(kind=column, fuzzy_only=false) returns all three columns" {
            val resp =
                service().listObjects(
                    ListObjectsRequest
                        .newBuilder()
                        .setKind("column")
                        .setFuzzyOnly(false)
                        .build(),
                )

            resp.itemsList.map { it.qualifiedName.name }.toSet() shouldBe
                setOf("products.id", "products.name", "products.sku")
        }

        "GetObject on the fuzzy column surfaces search.fuzzy=true through the load path" {
            val resp =
                service().getObject(
                    GetObjectRequest.newBuilder().setQualifiedName(columnQn("products.name")).build(),
                )

            resp.hasColumn() shouldBe true
            resp.column.hasSearch() shouldBe true
            resp.column.search.fuzzy shouldBe true
            resp.column.search.searchable shouldBe true
        }

        "GetObject on a non-fuzzy column has no search field" {
            val resp =
                service().getObject(
                    GetObjectRequest.newBuilder().setQualifiedName(columnQn("products.sku")).build(),
                )

            resp.hasColumn() shouldBe true
            resp.column.hasSearch() shouldBe false
        }
    })
