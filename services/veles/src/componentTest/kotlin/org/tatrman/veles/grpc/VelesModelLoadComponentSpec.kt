// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.grpc

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeEmpty
import org.tatrman.meta.v1.ListQueriesRequest
import org.tatrman.meta.v1.ResolveAreaRequest
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Path

/**
 * WS-C1 T4 — a whole model tree loaded from a real directory: where the unit
 * `ResolveAreaSpec`/`MetadataQuerySpec` exercise `ResolveArea`/`ListQueries` against small synthetic
 * fixtures, this reconciles whole fixture trees — `src/test/resources/model-tpcds/` (the TPC-DS
 * package and its area) and `model-seed/` (the Hartland `returns` slice and its area) — through the
 * live ttr-metadata reconciler, from disk the way a git checkout is read, and asserts the curated
 * query set and the areas resolve. Two trees, two models: both are TPC-DS, so their table names
 * overlap, and one model holds one definition per qualified name.
 *
 * Veles bundles no model since ttr-server#180 — the served model comes from the configured git
 * source — so this tree is a test fixture, not a shipped resource.
 *
 * No container — but it drives the real reconciler over a whole model tree rather than a mock, so
 * it belongs to the component tier, out of the mocked `test` gate.
 */
@Tags("component")
class VelesModelLoadComponentSpec :
    StringSpec({

        // Root each source at a fixture tree under `src/test/resources`, so the WHOLE tree — every
        // package + the `areas/*.ttrm` — reconciles exactly as the service loads a checkout at
        // runtime. The dir is injected by the build (`veles.modelRoot`) because `LocalFsStorage`
        // needs a real directory, not a classpath resource URI.
        val resourcesRoot: Path =
            Path.of(
                checkNotNull(System.getProperty("veles.modelRoot")) {
                    "veles.modelRoot not set — see services/veles/build.gradle.kts componentTest config"
                },
            )

        fun service(tree: String): MetadataServiceImpl {
            val source =
                FileBasedSource(
                    sourceId = tree,
                    priority = 100,
                    storage = LocalFsStorage(id = tree, rootPath = resourcesRoot.resolve(tree)),
                )
            val reconciler =
                ModelReconciler(ModelDescriptor(id = tree, name = tree, description = "$tree fixture tree"))
            val result = reconciler.reconcile(listOf(source.load()))
            val registry = MetadataRegistry()
            registry.swap(result.model, ModelGraph.build(result.model), result.warnings + result.errors)
            return MetadataServiceImpl(registry, packageIndex = PackageIndex())
        }

        "the model-tpcds tree reconciles and ListQueries surfaces the four TPC-DS curated queries" {
            val r = service("model-tpcds").listQueries(ListQueriesRequest.newBuilder().addTags("tpcds").build())
            r.itemsList
                .map { it.objectDescriptor.localName }
                .shouldContainAll(
                    "store_sales_by_month",
                    "top_items_by_revenue",
                    "customer_running_total",
                    "channel_revenue_cte",
                )
        }

        "ResolveArea(tpcds) resolves the TPC-DS package off the tree" {
            val r = service("model-tpcds").resolveArea(ResolveAreaRequest.newBuilder().setArea("tpcds").build())
            r.found shouldBe true
            r.packagesList shouldContainAll listOf("tpcds")
            r.description.shouldNotBeEmpty()
            r.tagsList shouldContainAll listOf("warehouse", "tpc-ds")
        }

        "ResolveArea(hartland) resolves the returns package off the tree" {
            val r = service("model-seed").resolveArea(ResolveAreaRequest.newBuilder().setArea("hartland").build())
            r.found shouldBe true
            r.packagesList shouldContainAll listOf("returns")
            r.description.shouldNotBeEmpty()
        }
    })
