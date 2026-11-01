// SPDX-License-Identifier: Apache-2.0
package org.tatrman.proto

import com.google.protobuf.Descriptors
import com.google.protobuf.DynamicMessage
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.meta.v1.AttributeJoinPair
import org.tatrman.meta.v1.Cardinality
import org.tatrman.meta.v1.RelationDetail
import org.tatrman.plan.v1.QualifiedName

class RelationNameWireSpec :
    StringSpec({
        "a pre-name relation reader retains endpoints cardinality and join pairs and ignores field ten" {
            val current = RelationDetail.getDescriptor()
            current.findFieldByName("name").number shouldBe 10
            val oldMessage =
                current
                    .toProto()
                    .toBuilder()
                    .clearField()
                    .addAllField(
                        current.toProto().fieldList.filter {
                            it.number !=
                                10
                        },
                    ).build()
            val file =
                current.file
                    .toProto()
                    .toBuilder()
                    .clearMessageType()
                    .addAllMessageType(
                        current.file
                            .toProto()
                            .messageTypeList
                            .map { if (it.name == "RelationDetail") oldMessage else it },
                    ).build()
            val oldDescriptor =
                Descriptors.FileDescriptor
                    .buildFrom(
                        file,
                        current.file.dependencies.toTypedArray(),
                    ).findMessageTypeByName("RelationDetail")

            fun qname(name: String) = QualifiedName.newBuilder().setName(name).build()
            val detail =
                RelationDetail
                    .newBuilder()
                    .setName("authored_relation")
                    .setFromEntity(qname("sale"))
                    .setToEntity(qname("buyer"))
                    .setCardinality(Cardinality.newBuilder().setFromMax(-1).setToMax(1))
                    .addJoinPairs(
                        AttributeJoinPair.newBuilder().setFromAttr(qname("sale.buyer_id")).setToAttr(qname("buyer.id")),
                    ).build()
            val old = DynamicMessage.parseFrom(oldDescriptor, detail.toByteArray())
            old.allFields.keys
                .map { it.number }
                .toSet() shouldBe setOf(1, 2, 3, 4)
            old.unknownFields.hasField(10) shouldBe true
            // The old reader's recognized fields equal a new message with only name removed.
            val withoutName =
                DynamicMessage.parseFrom(
                    oldDescriptor,
                    detail
                        .toBuilder()
                        .clearName()
                        .build()
                        .toByteArray(),
                )
            old.allFields shouldBe withoutName.allFields
            RelationDetail.parseFrom(old.toByteArray()) shouldBe detail
        }
    })
