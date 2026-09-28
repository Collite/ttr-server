// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.grpc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import org.tatrman.meta.v1.ResolveAreaRequest
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Path

/**
 * Golem Phase 4 Stage 4.2 T1/T2 — ResolveArea.
 *
 * Loads a FileBasedSource over a fixture root containing `areas/accounting.ttrm`
 * (a directive-less `.ttrm` file holding `def area accounting { ... }`), runs it
 * through the reconciler into a registry snapshot, and exercises the
 * MetadataServiceImpl.resolveArea RPC.
 */
class ResolveAreaSpec :
    StringSpec({

        // A dedicated resource root (NOT under `model-ttr`) so it doesn't shadow the
        // bundled `model-ttr` tree on the classpath (getResource returns the first match).
        val fixtureRoot: Path =
            Path
                .of(checkNotNull(this::class.java.classLoader.getResource("model-areas/areas")).toURI())
                .parent

        fun service(): MetadataServiceImpl {
            val source =
                FileBasedSource(
                    sourceId = "areas",
                    priority = 100,
                    storage = LocalFsStorage(id = "areas", rootPath = fixtureRoot),
                )
            val reconciler =
                ModelReconciler(ModelDescriptor(id = "test", name = "test", description = "areas fixture"))
            val result = reconciler.reconcile(listOf(source.load()))
            val registry = MetadataRegistry()
            registry.swap(result.model, ModelGraph.build(result.model), result.warnings + result.errors)
            return MetadataServiceImpl(registry, packageIndex = PackageIndex())
        }

        "resolveArea(accounting) returns the area's packages, description, tags, found=true" {
            val r = service().resolveArea(ResolveAreaRequest.newBuilder().setArea("accounting").build())
            r.found shouldBe true
            r.packagesList shouldContainExactly listOf("obchodni_doklady", "ucetnictvi")
            r.description.shouldNotBeEmpty()
            r.tagsList shouldContainExactly listOf("finance")
            r.messagesList.size shouldBe 0
        }

        // WS-T2 T2 — the TPC-DS area resolves to its self-contained seed package.
        "resolveArea(tpcds) returns the tpcds package, description, warehouse tags, found=true" {
            val r = service().resolveArea(ResolveAreaRequest.newBuilder().setArea("tpcds").build())
            r.found shouldBe true
            r.packagesList shouldContainExactly listOf("tpcds")
            r.description.shouldNotBeEmpty()
            r.tagsList shouldContainExactly listOf("warehouse", "tpc-ds")
            r.messagesList.size shouldBe 0
        }

        "resolveArea(nonexistent) returns found=false, empty packages, and a Rule-6 message" {
            val r = service().resolveArea(ResolveAreaRequest.newBuilder().setArea("nonexistent").build())
            r.found shouldBe false
            r.packagesList.isEmpty() shouldBe true
            r.messagesList.size shouldBe 1
            r.messagesList[0].code shouldBe "area_not_found"
        }

        "resolveArea on an unready registry surfaces metadata_not_ready" {
            val empty = MetadataServiceImpl(MetadataRegistry(), packageIndex = PackageIndex())
            val r = empty.resolveArea(ResolveAreaRequest.newBuilder().setArea("accounting").build())
            r.found shouldBe false
            r.messagesList.map { it.code } shouldContainExactly listOf("metadata_not_ready")
        }
    })
