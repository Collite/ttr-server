// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.grpc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.meta.v1.GetModelRequest
import org.tatrman.meta.v1.GetObjectRequest
import org.tatrman.meta.v1.GetSnapshotRequest
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Path

class RelationNameSpec :
    StringSpec({
        "GetModel GetObject and GetSnapshot retain authored relation names and identical join details" {
            val root = Path.of(checkNotNull(javaClass.classLoader.getResource("fixture-lq/hartland")).toURI()).parent
            val source = FileBasedSource("relations", 100, LocalFsStorage("relations", root)).load()
            val result = ModelReconciler(ModelDescriptor("relations", "relations")).reconcile(listOf(source))
            // This shared fixture intentionally includes refused query shapes; relation loading is unaffected.
            val registry = MetadataRegistry().also { it.swap(result.model, ModelGraph.build(result.model)) }
            val packages =
                PackageIndex().apply {
                    record(source)
                    commit()
                }
            val service = MetadataServiceImpl(registry, packageIndex = packages)
            val relations =
                result.model
                    .objectByQname()
                    .values
                    .filter { it.kind == "relation" }
            relations.size shouldBe 3
            val bundle =
                service
                    .getModel(
                        GetModelRequest
                            .newBuilder()
                            .addAllPackages(
                                relations
                                    .map {
                                        checkNotNull(packages.packageOf(it.sourceFile))
                                    }.distinct(),
                            ).build(),
                    ).model.relationsList
                    .associateBy { it.name }
            bundle.keys shouldBe relations.map { it.qname.name }.toSet()
            val snapshot = service.getSnapshot(GetSnapshotRequest.getDefaultInstance()).snapshot
            for (relation in relations) {
                val detail =
                    service
                        .getObject(
                            GetObjectRequest.newBuilder().setQualifiedName(relation.qname.toProto()).build(),
                        ).relation
                detail.name shouldBe relation.qname.name
                detail shouldBe bundle.getValue(relation.qname.name)
                snapshot.objectsList
                    .single {
                        it.objectDescriptor.qualifiedName == relation.qname.toProto()
                    }.relation shouldBe
                    detail
                val typed = relation as org.tatrman.ttr.metadata.model.Relation
                detail.fromEntity shouldBe typed.fromEntity.toProto()
                detail.toEntity shouldBe typed.toEntity.toProto()
                detail.cardinality.fromMin shouldBe typed.cardinality.fromMin
                detail.cardinality.fromMax shouldBe typed.cardinality.fromMax
                detail.cardinality.toMin shouldBe typed.cardinality.toMin
                detail.cardinality.toMax shouldBe typed.cardinality.toMax
                detail.joinPairsList.map { it.fromAttr to it.toAttr } shouldBe
                    typed.joinPairs.map { it.fromAttr.toProto() to it.toAttr.toProto() }
            }
        }
    })
