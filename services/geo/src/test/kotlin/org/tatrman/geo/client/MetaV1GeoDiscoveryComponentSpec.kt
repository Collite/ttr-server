// SPDX-License-Identifier: Apache-2.0
package org.tatrman.geo.client

import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.veles.grpc.MetadataServiceImpl
import org.tatrman.veles.grpc.PackageIndex
import java.nio.file.Path

/**
 * RG-P3.S0.T5 — the MetaV1 geo adapter component test (the seam's real path). Stands up an in-process
 * **Veles** ([MetadataServiceImpl]) over a POI semantics fixture, then drives [MetaV1GeoDiscovery]
 * over an in-process channel: proves the adapter maps the real `meta.v1` projection (RS-33
 * `semantics_kind` descriptors + `AttributeSemantics` roles + `EntityDetail.name_attribute`) to geo's
 * discovery domain types — identical to what [org.tatrman.geo.FakeMetadataClient] returns for the seam
 * specs.
 */
class MetaV1GeoDiscoveryComponentSpec :
    StringSpec({

        "MetaV1 adapter maps the real meta.v1 POI projection to geo discovery domain types" {
            val root = Path.of(checkNotNull(this::class.java.classLoader.getResource("fixture-semantics-geo")).toURI())
            val source = FileBasedSource(sourceId = "poi", priority = 100, storage = LocalFsStorage("poi", root))
            val result =
                ModelReconciler(
                    ModelDescriptor(id = "t", name = "t", description = "t"),
                ).reconcile(listOf(source.load()))
            val registry = MetadataRegistry()
            registry.swap(result.model, ModelGraph.build(result.model), result.warnings + result.errors)
            val service = MetadataServiceImpl(registry, packageIndex = PackageIndex())

            val serverName = "geo-meta-${System.identityHashCode(service)}"
            val server =
                InProcessServerBuilder
                    .forName(serverName)
                    .directExecutor()
                    .addService(service)
                    .build()
                    .start()
            val channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
            val discovery = MetaV1GeoDiscovery.forChannel(channel)

            try {
                runBlocking {
                    // poi.ttrm is an unpackaged `model er` → its objects live at package "".
                    val geo = discovery.geoColumns("").shouldNotBeNull()
                    geo.lat.columnName shouldBe "lat"
                    geo.lon.columnName shouldBe "lon"

                    val poi = discovery.poiEntity("").shouldNotBeNull()
                    poi.entityName shouldBe "Store"
                    poi.nameColumn shouldBe "store_name"
                    poi.latColumn shouldBe "lat"
                    poi.lonColumn shouldBe "lon"

                    discovery.probeReady() shouldBe true
                }
            } finally {
                channel.shutdownNow()
                server.shutdownNow()
            }
        }
    })
