// SPDX-License-Identifier: Apache-2.0
package org.tatrman.proto

import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorSet
import com.google.protobuf.Descriptors
import com.google.protobuf.DynamicMessage
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.fuzzy.v1.Provenance
import org.tatrman.fuzzy.v1.TokenHit
import java.io.File

/**
 * LP-P0·S2 T6 — **the fuzzy old-client proof** (the [ResolverOldClientSpec] pattern). A client
 * generated from the pre-`fuzzy.match:v2` `org.tatrman.fuzzy.v1` — a frozen descriptor set,
 * `compat/fuzzy-v1-pre-lp.desc` — parses a v2 row, reads every field it knew, and re-emits the
 * v2 provenance intact.
 */
class FuzzyOldClientSpec :
    StringSpec({

        "a pre-LP client parses a v2 FuzzyMatch, reads its own fields, and loses nothing" {
            val v2Row =
                FuzzyMatch
                    .newBuilder()
                    .setCandidateId("c-valmy")
                    .setCandidate("Valmy Oil, s.r.o.")
                    .setScore(1.0071)
                    .setCategory("customer")
                    .setProvenance(
                        Provenance
                            .newBuilder()
                            .setProducer("fuzzy")
                            .setMethod("TATRMAN_V2")
                            .setRawScore(1.0071)
                            .addTokenHits(
                                TokenHit
                                    .newBuilder()
                                    .setQueryToken("valmy")
                                    .setCandidateToken("valmy")
                                    .setKind("exact")
                                    .setQueryPos(0)
                                    .setCandidatePos(0),
                            ).setCoverage(0.71),
                    ).build()

            val oldMatch = preLp("org/tatrman/fuzzy/v1/fuzzy.proto", "FuzzyMatch")
            val oldProvenance = oldMatch.findFieldByName("provenance").messageType
            // the baseline really is pre-change — otherwise this proves nothing
            oldProvenance.findFieldByName("token_hits") shouldBe null

            val asOld = DynamicMessage.parseFrom(oldMatch, v2Row.toByteArray())
            asOld.getField(oldMatch.findFieldByName("candidate_id")) shouldBe "c-valmy"
            val prov = asOld.getField(oldMatch.findFieldByName("provenance")) as DynamicMessage
            prov.getField(oldProvenance.findFieldByName("method")) shouldBe "TATRMAN_V2"
            prov.getField(oldProvenance.findFieldByName("raw_score")) shouldBe 1.0071
            prov.unknownFields.hasField(7).shouldBeTrue()
            prov.unknownFields.hasField(8).shouldBeTrue()

            val back = FuzzyMatch.parseFrom(asOld.toByteArray())
            back.provenance.tokenHitsList shouldBe v2Row.provenance.tokenHitsList
            back.provenance.coverage shouldBe 0.71
        }

        "a pre-LP client parses a status carrying engine_version" {
            val status =
                FuzzyStatusResponse
                    .newBuilder()
                    .setReady(true)
                    .setEngineVersion("v2")
                    .build()
            val oldStatus = preLp("org/tatrman/fuzzy/v1/fuzzy.proto", "FuzzyStatusResponse")
            oldStatus.findFieldByName("engine_version") shouldBe null
            val asOld = DynamicMessage.parseFrom(oldStatus, status.toByteArray())
            asOld.getField(oldStatus.findFieldByName("ready")) shouldBe true
            FuzzyStatusResponse.parseFrom(asOld.toByteArray()).engineVersion shouldBe "v2"
        }
    })

private fun preLp(
    file: String,
    message: String,
): Descriptors.Descriptor {
    val protos =
        FileDescriptorSet
            .parseFrom(File(System.getProperty("tatrman.proto.preLp")).readBytes())
            .fileList
            .associateBy { it.name }
    val built = HashMap<String, Descriptors.FileDescriptor>()

    fun build(name: String): Descriptors.FileDescriptor =
        built.getOrPut(name) {
            val proto: FileDescriptorProto = protos[name] ?: error("$name is not in the pre-LP baseline")
            Descriptors.FileDescriptor.buildFrom(proto, proto.dependencyList.map { build(it) }.toTypedArray())
        }

    return build(file).findMessageTypeByName(message) ?: error("$message is not in $file")
}
