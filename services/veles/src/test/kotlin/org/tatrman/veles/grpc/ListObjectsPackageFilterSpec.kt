// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.grpc

import org.tatrman.meta.v1.ListObjectsRequest
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

class ListObjectsPackageFilterSpec :
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

        "ListObjects(kind=entity, package=sales) returns entities scoped to sales" {
            val svc = service()
            val respFiltered =
                svc.listObjects(
                    ListObjectsRequest
                        .newBuilder()
                        .setKind("entity")
                        .setPackage("sales")
                        .build(),
                )
            // All returned items (if any) should have sourceFile containing /sales/
            // If the fixture has no sales entities, this list is empty
            respFiltered.itemsList.all { it.sourceFile.contains("/sales/") } shouldBe true
        }

        "ListObjects(kind=entity, package=returns) returns entities from the returns package" {
            val svc = service()
            val resp =
                svc.listObjects(
                    ListObjectsRequest
                        .newBuilder()
                        .setKind("entity")
                        .setPackage("returns")
                        .build(),
                )

            val entityCount = resp.itemsList.size
            (entityCount >= 7) shouldBe true
            resp.itemsList.all {
                it.qualifiedName.schemaCode == SchemaCode.ER &&
                    it.sourceFile.contains("/returns/")
            } shouldBe true
        }

        "ListObjects(kind=entity, package=) returns all entities (empty filter)" {
            val svc = service()
            val respAll =
                svc.listObjects(
                    ListObjectsRequest
                        .newBuilder()
                        .setKind("entity")
                        .build(),
                )
            val respFiltered =
                svc.listObjects(
                    ListObjectsRequest
                        .newBuilder()
                        .setKind("entity")
                        .setPackage("")
                        .build(),
                )
            respFiltered.itemsList.size shouldBe respAll.itemsList.size
        }

        "ListObjects(kind=table, package=returns) returns tables from returns" {
            val svc = service()
            val resp =
                svc.listObjects(
                    ListObjectsRequest
                        .newBuilder()
                        .setKind("table")
                        .setPackage("returns")
                        .build(),
                )

            resp.itemsList.all {
                it.qualifiedName.schemaCode == SchemaCode.DB &&
                    it.sourceFile.contains("/returns/")
            } shouldBe true
        }
    })
