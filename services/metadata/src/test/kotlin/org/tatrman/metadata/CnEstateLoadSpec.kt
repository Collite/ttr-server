// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.meta.v1.GetStatusRequest
import org.tatrman.meta.v1.ListObjectsRequest
import org.tatrman.meta.v1.ObjectDescriptor
import org.tatrman.meta.v1.OverallStatus
import org.tatrman.meta.v1.PageRequest
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.CnSchema
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.Via
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.metadata.grpc.MetadataServiceImpl
import org.tatrman.metadata.grpc.servedIndex
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * CN-P4 S4.2 (cn contracts §5) — the metadata service's snapshot carries the cn tier. metadata loads the shared cn estate
 * (`shared/testdata/cn-estate`), swaps it into its registry, and the registry holds the tier the v6 lexicon archive was
 * compiled from: the same concepts with the same landings, and a reverse index equal to the archive's `liftsTo`. That
 * is one estate read by three consumers that agree (metadata here, lex-matcher S4.3, the resolver S4.4). The tier is
 * library-only, so the wire must survive it. Concepts page out as `SCHEMA_CODE_UNSPECIFIED`, the automation tier's
 * posture, and never fail an RPC.
 */
class CnEstateLoadSpec :
    StringSpec({
        val estate = Path.of(System.getProperty("cnEstate") ?: error("cnEstate not set"))
        val snapshot = FileBasedSource("fixture", 100, LocalFsStorage("fixture", estate.resolve("model"))).load()
        val result = ModelReconciler(ModelDescriptor(id = "shop", name = "shop")).reconcile(listOf(snapshot))
        val registry =
            MetadataRegistry().apply {
                swap(result.model, ModelGraph.build(result.model), result.warnings + result.errors)
            }

        fun cn(): CnSchema =
            registry
                .readOrError()
                .model.schemas
                .getValue("cn") as CnSchema

        /** The v6 archive's `targets`, compiled by ttr-core from this same estate (S4.1). */
        val targets: Map<String, JsonObject> =
            Json
                .parseToJsonElement(estate.resolve("expected/lexicon.json").readText())
                .jsonObject
                .getValue("targets")
                .jsonObject
                .mapValues { it.value.jsonObject }

        /** A JSON `null` is a primitive too: `content` would read it as the string "null". */
        fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

        fun wire(via: Via?): String? =
            when (via) {
                Via.DIRECT -> "direct"
                Via.MD_HOP -> "md-hop"
                null -> null
            }

        "the estate loads without an error" {
            // The estate is one package, `shop`, laid out by layer (`model/cn/shop.ttrm`): the directory names the layer,
            // not the package. That mismatch is the harmless one, and ttr-core's lexicon build tolerates it too.
            (snapshot.errors + result.errors)
                .filterNot { it.message.startsWith("ttr/package-declaration-mismatch") }
                .map { "${it.file}:${it.line} ${it.message}" }
                .shouldBeEmpty()
        }

        "the registry carries every concept the archive does, with the same landing and the same way there" {
            val archived =
                targets
                    .filterValues { it.str("objectKind") == "concept" }
                    .mapValues { (_, t) ->
                        listOf(t.str("stereotype"), t.str("erTarget"), t.str("via"), t.str("ofRef"))
                    }
            val served =
                cn().concepts.values.associate {
                    it.ref to listOf(it.stereotype, it.erTarget, wire(it.via), it.of?.let { of -> "cn.${of.name}" })
                }

            served.size shouldBe 12
            served shouldBe archived
        }

        "its reverse index is the archive's liftsTo — every lifted er and md element, no phase in it" {
            val archived =
                targets
                    .filterValues { it["liftsTo"]?.jsonArray?.isNotEmpty() == true }
                    .mapValues { (_, t) ->
                        t.getValue("liftsTo").jsonArray.map {
                            it.jsonObject.str("qname") to it.jsonObject.str("via")
                        }
                    }
            val served =
                cn().reverseIndex.mapValues { (_, lifts) -> lifts.map { it.ref to wire(it.via) }.sortedBy { it.first } }

            served shouldBe archived
            served.values
                .flatten()
                .map { it.first }
                .filter {
                    cn()
                        .concepts.values
                        .single { c -> c.ref == it }
                        .stereotype == "phase"
                }.shouldBeEmpty()
        }

        "the wire survives the cn tier: ListObjects pages through it, GetStatus reports the model loaded" {
            val svc = MetadataServiceImpl(registry, packageIndex = servedIndex(snapshot))
            val all = mutableListOf<ObjectDescriptor>()
            var token = ""
            do {
                val page =
                    svc.listObjects(
                        ListObjectsRequest
                            .newBuilder()
                            .setPage(PageRequest.newBuilder().setPageSize(500).setPageToken(token))
                            .build(),
                    )
                all += page.itemsList
                token = page.pageInfo.nextPageToken
            } while (token.isNotEmpty())

            val concepts = all.filter { it.kind == "concept" }
            concepts.map { it.localName }.toSet() shouldBe
                cn()
                    .concepts.keys
                    .map { it.name }
                    .toSet()
            concepts.all { it.schemaCode == SchemaCode.SCHEMA_CODE_UNSPECIFIED } shouldBe true

            val status = svc.getStatus(GetStatusRequest.getDefaultInstance())
            status.modelLoaded shouldBe true
            status.overallStatus shouldBe OverallStatus.OK
        }
    })
