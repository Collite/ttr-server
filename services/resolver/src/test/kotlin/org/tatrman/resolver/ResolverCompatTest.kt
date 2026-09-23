// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.FieldDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.Descriptors
import com.google.protobuf.DynamicMessage
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.tatrman.resolver.v1.Attribution
import org.tatrman.resolver.v1.Span
import org.tatrman.resolver.v1.ValueFinding
import org.tatrman.resolver.v1.ValueKind

/**
 * LP-P1·S2·T1 — the additive promise, checked against a reader that predates it.
 *
 * `ValueKind.VERBATIM` and `ValueFinding.predicate_ref`/`verbatim_text` are additive (J-v2), and
 * "additive" is a claim about a **peer that has not been rebuilt**: golem, kantheon and the door's
 * own clients all decode this message, and they are deployed one at a time. The old reader is
 * modelled here as a runtime descriptor holding fields 1–6 only — wire-compatible stand-ins, since
 * a proto3 enum is a varint and a nested message is length-delimited — because a test that parses
 * the new bytes with the NEW generated class proves nothing at all about the old one.
 *
 * What an old reader must do, and what this pins:
 *  1. parse without error;
 *  2. see `kind = 3`, a number it has no name for, rather than fail or silently read GROUNDED;
 *  3. keep fields 7 and 8 as unknown fields, so a service that relays a lattice it does not
 *     understand relays the literal too, instead of deleting the one thing the user typed.
 */
class ResolverCompatTest :
    StringSpec({

        /** `ValueFinding` as it was before LP: fields 1–6, in wire-compatible stand-in types. */
        val oldValueFinding: Descriptors.Descriptor =
            run {
                fun field(
                    name: String,
                    number: Int,
                    type: FieldDescriptorProto.Type,
                    label: FieldDescriptorProto.Label = FieldDescriptorProto.Label.LABEL_OPTIONAL,
                ) = FieldDescriptorProto
                    .newBuilder()
                    .setName(name)
                    .setNumber(number)
                    .setType(type)
                    .setLabel(label)
                    .build()

                val message =
                    DescriptorProto
                        .newBuilder()
                        .setName("ValueFindingV0")
                        .addField(field("id", 1, FieldDescriptorProto.Type.TYPE_STRING))
                        .addField(field("span", 2, FieldDescriptorProto.Type.TYPE_BYTES))
                        .addField(field("kind", 3, FieldDescriptorProto.Type.TYPE_INT32))
                        .addField(
                            field(
                                "attributions",
                                4,
                                FieldDescriptorProto.Type.TYPE_BYTES,
                                FieldDescriptorProto.Label.LABEL_REPEATED,
                            ),
                        ).addField(field("grounding", 5, FieldDescriptorProto.Type.TYPE_BYTES))
                        .addField(field("anchor_mention_id", 6, FieldDescriptorProto.Type.TYPE_STRING))
                        .build()

                val file =
                    FileDescriptorProto
                        .newBuilder()
                        .setName("lp/old_value_finding.proto")
                        .setSyntax("proto3")
                        .setPackage("lp.compat")
                        .addMessageType(message)
                        .build()

                Descriptors.FileDescriptor
                    .buildFrom(file, emptyArray())
                    .findMessageTypeByName("ValueFindingV0")
            }

        val verbatim =
            ValueFinding
                .newBuilder()
                .setId("v1")
                .setSpan(
                    Span
                        .newBuilder()
                        .setStart(32)
                        .setEnd(39)
                        .setText("\"Pelex\""),
                ).setKind(ValueKind.VALUE_KIND_VERBATIM)
                .addAttributions(Attribution.newBuilder().setAttributeRef("er.entity.store.name"))
                .setAnchorMentionId("m1")
                .setVerbatimText("Pelex")
                .build()

        "an old client parses a VERBATIM value, and sees a kind it has no name for" {
            val old = DynamicMessage.parseFrom(oldValueFinding, verbatim.toByteArray())

            old.getField(oldValueFinding.findFieldByNumber(1)) shouldBe "v1"
            old.getField(oldValueFinding.findFieldByNumber(3)) shouldBe 3
            old.getField(oldValueFinding.findFieldByNumber(6)) shouldBe "m1"
        }

        "the literal survives a relay through a service that does not know the field" {
            // The shape that matters in production: an intermediary decodes the lattice with an
            // older stub and passes it on. Unknown fields are kept by protobuf, so the literal
            // reaches the consumer that does understand it — and it is the ONE field a consumer
            // cannot reconstruct, because §1.4's text is not the span's substring.
            val relayed = DynamicMessage.parseFrom(oldValueFinding, verbatim.toByteArray())
            relayed.unknownFields.hasField(7).shouldBeFalse() // predicate_ref: absent, P2 sets it
            relayed.unknownFields.hasField(8).shouldBeTrue()

            val roundTripped = ValueFinding.parseFrom(relayed.toByteArray())

            roundTripped shouldBe verbatim
            roundTripped.verbatimText shouldBe "Pelex"
            roundTripped.hasPredicateRef().shouldBeFalse()
        }

        "an absent predicate_ref is distinguishable from an empty one" {
            // §2.2: absent ⇒ the default predicate applies (contains for a name). A blank string
            // is a different statement, and proto3 `optional` is what keeps the two apart.
            verbatim.hasPredicateRef().shouldBeFalse()

            val blank = verbatim.toBuilder().setPredicateRef("").build()
            blank.hasPredicateRef().shouldBeTrue()
            ValueFinding.parseFrom(blank.toByteArray()).hasPredicateRef().shouldBeTrue()
        }
    })
