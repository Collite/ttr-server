// SPDX-License-Identifier: Apache-2.0
package org.tatrman.money.client

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
 * RG-P3.S0.T5 — the MetaV1 money adapter component test (the seam's real path). Stands up an
 * in-process **Veles** ([MetadataServiceImpl]) over the grammar semantics goldens, then drives
 * [MetaV1MoneyDiscovery] over an in-process channel: proves the adapter maps the real `meta.v1`
 * projection (RS-33 `semantics_kind` descriptors + `AttributeSemantics`) to money's discovery
 * domain types — identical to what [org.tatrman.money.FakeMetadataClient] returns for the seam specs.
 */
class MetaV1MoneyDiscoveryComponentSpec :
    StringSpec({

        "MetaV1 adapter maps the real meta.v1 semantics projection to money discovery domain types" {
            val root = Path.of(checkNotNull(this::class.java.classLoader.getResource("fixture-semantics")).toURI())
            val source = FileBasedSource(sourceId = "fx", priority = 100, storage = LocalFsStorage("fx", root))
            val result =
                ModelReconciler(
                    ModelDescriptor(id = "t", name = "t", description = "t"),
                ).reconcile(listOf(source.load()))
            val registry = MetadataRegistry()
            registry.swap(result.model, ModelGraph.build(result.model), result.warnings + result.errors)
            val service = MetadataServiceImpl(registry, packageIndex = PackageIndex())

            val serverName = "money-meta-${System.identityHashCode(service)}"
            val server =
                InProcessServerBuilder
                    .forName(serverName)
                    .directExecutor()
                    .addService(service)
                    .build()
                    .start()
            val channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
            val discovery = MetaV1MoneyDiscovery.forChannel(channel)

            try {
                runBlocking {
                    // 59-semantics.ttrm is an unpackaged `model er` → its objects live at package "".
                    val amounts = discovery.amountColumns("")
                    amounts.domestic?.columnName shouldBe "amount_dom"
                    amounts.amount.map { it.columnName } shouldBe listOf("amount")
                    amounts.currencyCode?.columnName shouldBe "currency_code"

                    val fx = discovery.fxTable("").shouldNotBeNull()
                    fx.entityName shouldBe "FxRate"
                    fx.rate.columnName shouldBe "rate"
                    fx.fromCurrency.columnName shouldBe "from_ccy"
                    fx.toCurrency.columnName shouldBe "to_ccy"
                    fx.validFrom?.columnName shouldBe "valid_from"
                    fx.validTo?.columnName shouldBe "valid_to"

                    discovery.eventDateColumn("")?.columnName shouldBe "txn_date"
                    discovery.probeReady() shouldBe true
                }
            } finally {
                channel.shutdownNow()
                server.shutdownNow()
            }
        }
    })
