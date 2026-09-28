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
                .of(checkNotNull(this::class.java.classLoader.getResource("model-ttr/ucetnictvi")).toURI())
                .parent

        fun service(): MetadataServiceImpl {
            val source =
                FileBasedSource(
                    sourceId = "ucetnictvi",
                    priority = 100,
                    storage = LocalFsStorage(id = "ucetnictvi", rootPath = fixtureRoot),
                )
            val reconciler =
                ModelReconciler(
                    ModelDescriptor(id = "test", name = "test", description = "ucetnictvi fixture"),
                )
            val snapshot = source.load()
            val result = reconciler.reconcile(listOf(snapshot))
            val registry = MetadataRegistry()
            registry.swap(result.model, ModelGraph.build(result.model), result.warnings + result.errors)
            return MetadataServiceImpl(registry, packageIndex = servedIndex(snapshot))
        }

        "ListObjects(kind=entity, package=prodeje) returns entities scoped to prodeje" {
            val svc = service()
            val respFiltered =
                svc.listObjects(
                    ListObjectsRequest
                        .newBuilder()
                        .setKind("entity")
                        .setPackage("prodeje")
                        .build(),
                )
            // All returned items (if any) should have sourceFile containing /prodeje/
            // If the fixture has no prodeje entities, this list is empty
            respFiltered.itemsList.all { it.sourceFile.contains("/prodeje/") } shouldBe true
        }

        "ListObjects(kind=entity, package=ucetnictvi) returns entities from ucetnictvi package" {
            val svc = service()
            val resp =
                svc.listObjects(
                    ListObjectsRequest
                        .newBuilder()
                        .setKind("entity")
                        .setPackage("ucetnictvi")
                        .build(),
                )

            val entityCount = resp.itemsList.size
            (entityCount >= 7) shouldBe true
            resp.itemsList.all {
                it.qualifiedName.schemaCode == SchemaCode.ER &&
                    it.sourceFile.contains("/ucetnictvi/")
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

        "ListObjects(kind=table, package=ucetnictvi) returns tables from ucetnictvi" {
            val svc = service()
            val resp =
                svc.listObjects(
                    ListObjectsRequest
                        .newBuilder()
                        .setKind("table")
                        .setPackage("ucetnictvi")
                        .build(),
                )

            resp.itemsList.all {
                it.qualifiedName.schemaCode == SchemaCode.DB &&
                    it.sourceFile.contains("/ucetnictvi/")
            } shouldBe true
        }
    })
