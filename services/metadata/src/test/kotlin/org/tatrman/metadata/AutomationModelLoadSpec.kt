// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.tatrman.meta.v1.GetModelRequest
import org.tatrman.meta.v1.GetStatusRequest
import org.tatrman.meta.v1.ListObjectsRequest
import org.tatrman.meta.v1.OverallStatus
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.AutomationSchema
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.WorldSchema
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.metadata.grpc.MetadataServiceImpl
import org.tatrman.metadata.grpc.servedIndex
import java.nio.file.Path

/**
 * Grammar 0.16 (ttr-core#207) — a model carrying the automation half: `states:`/`transitions:` on attributes, a
 * world with `writable:` storages and its `def identity` / `def grant` / `def deployment` / `def process` members,
 * and `model automation` files (the hartland demo world of AU, design `17` §1–§2). Metadata must load it clean and
 * serve it: the world members go out as world objects (kind `deployment`, …), the automation tier — which the
 * frozen proto `SchemaCode` has no member for — as `SCHEMA_CODE_UNSPECIFIED`, never a failed RPC.
 */
class AutomationModelLoadSpec :
    StringSpec({
        val root: Path = Path.of(checkNotNull(this::class.java.classLoader.getResource("automation-model")).toURI())

        val snapshot = FileBasedSource("fixture", 100, LocalFsStorage("fixture", root)).load()
        val result = ModelReconciler(ModelDescriptor(id = "au", name = "au")).reconcile(listOf(snapshot))

        fun service(): MetadataServiceImpl {
            val registry = MetadataRegistry()
            registry.swap(result.model, ModelGraph.build(result.model), result.warnings + result.errors)
            return MetadataServiceImpl(registry, packageIndex = servedIndex(snapshot))
        }

        "the automation model loads without an error" {
            (snapshot.errors + result.errors).map { "${it.file}:${it.line} ${it.message}" }.shouldBeEmpty()
            val world = (result.model.schemas["world"] as WorldSchema).worlds.values.single()
            world.deployments.size shouldBe 12
            world.grants.size shouldBe 4
            world.identities.single().credential shouldBe "TTR_CONN_AUTOMATION_DECIDER"
            (result.model.schemas["automation"] as AutomationSchema).programs.size shouldBe 8
            val order = (result.model.schemas["er"] as ErSchema).entities.values.single { it.qname.name == "order" }
            order.attributes.single { it.qname.name.endsWith("state") }.states shouldContain "held"
        }

        "GetModel serves the package's entities" {
            val r = service().getModel(GetModelRequest.newBuilder().addPackages("hartland").build())
            r.messagesList.filter { it.severity.name == "ERROR" }.shouldBeEmpty()
            r.model.entitiesList.map { it.objectDescriptor.localName } shouldContain "order"
        }

        "ListObjects pages through every object, the world members and the automation tier included" {
            val svc = service()
            val all = mutableListOf<org.tatrman.meta.v1.ObjectDescriptor>()
            var token = ""
            do {
                val page =
                    svc.listObjects(
                        ListObjectsRequest
                            .newBuilder()
                            .setPage(
                                org.tatrman.meta.v1.PageRequest
                                    .newBuilder()
                                    .setPageSize(500)
                                    .setPageToken(token),
                            ).build(),
                    )
                all += page.itemsList
                token = page.pageInfo.nextPageToken
            } while (token.isNotEmpty())
            all.count { it.kind == "deployment" } shouldBe 12
            all.filter { it.kind == "program" }.size shouldBe 8
            all.filter { it.kind == "program" }.all { it.schemaCode == SchemaCode.SCHEMA_CODE_UNSPECIFIED } shouldBe
                true
            all.size shouldBeGreaterThan 40
        }

        "GetStatus reports the model loaded" {
            val r = service().getStatus(GetStatusRequest.getDefaultInstance())
            r.modelLoaded shouldBe true
            r.overallStatus shouldBe OverallStatus.OK
        }
    })
