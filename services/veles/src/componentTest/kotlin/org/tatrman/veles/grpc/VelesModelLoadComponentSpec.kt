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
 * WS-C1 T4 — the **real bundled model** load: where the unit `ResolveAreaSpec`/`MetadataQuerySpec`
 * exercise `ResolveArea`/`ListQueries` against small synthetic fixtures, this reconciles the actual
 * shipped `src/main/resources/model-ttr/` tree (TPC-DS + the ucetnictvi/obchodni_doklady accounting
 * packages + their area definitions) through the live ttr-metadata reconciler and asserts the
 * curated query set and the areas resolve off the packaged model. It guards the WS-T2 model
 * authoring against silent regressions in the bundled resources (a broken `.ttr`/`.ttrm` that unit
 * fixtures wouldn't catch).
 *
 * No container — but it drives the real reconciler over the packaged model rather than a mock, so
 * it belongs to the component tier, out of the mocked `test` gate.
 *
 * **Scope note (deviation, tracked in the C1 session handoff):** the deploy-test C1 T4 wording lists
 * an `investment` model, but no investment TTR model is authored in the repo yet (that lands with the
 * Midas arc). This spec covers the two model families that actually ship today — TPC-DS and
 * accounting — and adds investment when its model does.
 */
@Tags("component")
class VelesModelLoadComponentSpec :
    StringSpec({

        // Root the source at the authored model tree (`src/main/resources/model-ttr`), so the
        // WHOLE shipped model — every package + the `areas/*.ttrm` — reconciles exactly as the
        // service loads it at runtime. The dir is injected by the build (`veles.modelRoot`)
        // rather than resolved from a classpath resource: on CI the project rides the suite
        // classpath as a JAR, so `getResource("model-ttr/..").toURI()` is a `jar:` URI with no
        // open filesystem (FileSystemNotFoundException) — and `LocalFsStorage` needs a real dir.
        val modelRoot: Path =
            Path.of(
                checkNotNull(System.getProperty("veles.modelRoot")) {
                    "veles.modelRoot not set — see services/veles/build.gradle.kts componentTest config"
                },
            )

        fun service(): MetadataServiceImpl {
            val source =
                FileBasedSource(
                    sourceId = "model-ttr",
                    priority = 100,
                    storage = LocalFsStorage(id = "model-ttr", rootPath = modelRoot),
                )
            val reconciler =
                ModelReconciler(ModelDescriptor(id = "bundled", name = "bundled", description = "packaged model-ttr"))
            val result = reconciler.reconcile(listOf(source.load()))
            val registry = MetadataRegistry()
            registry.swap(result.model, ModelGraph.build(result.model), result.warnings + result.errors)
            return MetadataServiceImpl(registry, packageIndex = PackageIndex())
        }

        "the bundled model-ttr reconciles and ListQueries surfaces the four TPC-DS curated queries" {
            val r = service().listQueries(ListQueriesRequest.newBuilder().addTags("tpcds").build())
            r.itemsList
                .map { it.objectDescriptor.localName }
                .shouldContainAll(
                    "store_sales_by_month",
                    "top_items_by_revenue",
                    "customer_running_total",
                    "channel_revenue_cte",
                )
        }

        "ResolveArea(tpcds) resolves the TPC-DS package off the bundled model" {
            val r = service().resolveArea(ResolveAreaRequest.newBuilder().setArea("tpcds").build())
            r.found shouldBe true
            r.packagesList shouldContainAll listOf("tpcds")
            r.description.shouldNotBeEmpty()
            r.tagsList shouldContainAll listOf("warehouse", "tpc-ds")
        }

        "ResolveArea(accounting) resolves the ucetnictvi package off the bundled model" {
            val r = service().resolveArea(ResolveAreaRequest.newBuilder().setArea("accounting").build())
            r.found shouldBe true
            r.packagesList shouldContainAll listOf("ucetnictvi")
            r.description.shouldNotBeEmpty()
        }
    })
