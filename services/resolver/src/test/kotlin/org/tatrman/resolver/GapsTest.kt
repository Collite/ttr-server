// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.pipeline.Gaps
import org.tatrman.resolver.v1.Attribution
import org.tatrman.resolver.v1.Binding
import org.tatrman.resolver.v1.Disposition
import org.tatrman.resolver.v1.FrameRole
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.GapRecord
import org.tatrman.resolver.v1.Grounding
import org.tatrman.resolver.v1.Mention
import org.tatrman.resolver.v1.Span
import org.tatrman.resolver.v1.TargetClass
import org.tatrman.resolver.v1.ValueFinding
import org.tatrman.resolver.v1.ValueKind

/**
 * RV-P2.1.T6 — every gap kind, constructed from a crafted input. The kind is not decoration:
 * contracts §3 gives each one its own rung list and its own ask rule, so getting the kind wrong
 * sends the ladder down the wrong path.
 */
class GapsTest :
    StringSpec({

        "G1_UNBOUND — a mention nothing bound, carrying the role that makes it worth asking about" {
            val gaps =
                Gaps.assess(
                    mentions = listOf(mention("m1", 18, 34, "čerpacích stanic", FrameRole.FRAME_ROLE_SUBJECT)),
                    values = emptyList(),
                    ambiguousSpans = emptySet(),
                    parse = AnalyzeResponse.getDefaultInstance(),
                    degraded = false,
                )
            val gap = gaps.single()
            gap.kind shouldBe GapKind.GAP_KIND_G1_UNBOUND
            gap.mentionId shouldBe "m1"
            gap.frameRolesList shouldContainExactly listOf(FrameRole.FRAME_ROLE_SUBJECT)
            gap.disposition shouldBe Disposition.DISPOSITION_UNRESOLVED
        }

        "G2_AMBIGUOUS — several candidates, none dominant; the lattice keeps them all" {
            val ambiguous =
                mention("m1", 0, 9, "středisko")
                    .toBuilder()
                    .addBindings(binding("er.qstred_df"))
                    .addBindings(binding("er.qxxukazmu"))
                    .build()
            val gaps =
                Gaps.assess(
                    mentions = listOf(ambiguous),
                    values = emptyList(),
                    ambiguousSpans = setOf(0 to 9),
                    parse = AnalyzeResponse.getDefaultInstance(),
                    degraded = false,
                )
            gaps.single().kind shouldBe GapKind.GAP_KIND_G2_AMBIGUOUS
            // the bindings are NOT dropped — refusing to choose is not refusing to report
            ambiguous.bindingsCount shouldBe 2
        }

        "G3_UNATTRIBUTED — a hint nothing scoped and nothing claimed (issues.md's `Praze`)" {
            val gaps =
                Gaps.assess(
                    mentions = emptyList(),
                    values = listOf(grounded("v1", 37, 42, "Praze", "LOCATION")),
                    ambiguousSpans = emptySet(),
                    parse = AnalyzeResponse.getDefaultInstance(),
                    degraded = false,
                )
            val gap = gaps.single()
            gap.kind shouldBe GapKind.GAP_KIND_G3_UNATTRIBUTED
            gap.valueId shouldBe "v1"
            gap.mentionId shouldBe ""
        }

        "G4_METHOD_MISS — the SAME value, once a mention scoped it: a known axis that missed" {
            val anchored =
                literal("v1", 20, 26, "5010O1")
                    .toBuilder()
                    .setAnchorMentionId("m1")
                    .build()
            val gaps =
                Gaps.assess(
                    mentions = listOf(mention("m1", 15, 19, "účtu", FrameRole.FRAME_ROLE_FILTER, bound = true)),
                    values = listOf(anchored),
                    ambiguousSpans = emptySet(),
                    parse = AnalyzeResponse.getDefaultInstance(),
                    degraded = false,
                )
            val gap = gaps.single()
            gap.kind shouldBe GapKind.GAP_KIND_G4_METHOD_MISS
            gap.valueId shouldBe "v1"
            // a value gap inherits its anchor's roles: that is what tells the ask policy whether
            // the missed code sits on the load-bearing axis
            gap.frameRolesList shouldContainExactly listOf(FrameRole.FRAME_ROLE_FILTER)
        }

        "G5_NLP_DARK — the capability floor is a gap about the whole question, and it DEGRADES" {
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .addTokens(
                        Token
                            .newBuilder()
                            .setText("Zobraz")
                            .setCharStart(0)
                            .setCharEnd(6),
                    ).addTokens(
                        Token
                            .newBuilder()
                            .setText("tržby")
                            .setCharStart(7)
                            .setCharEnd(12),
                    ).build()
            val gaps =
                Gaps.assess(
                    mentions = emptyList(),
                    values = emptyList(),
                    ambiguousSpans = emptySet(),
                    parse = parse,
                    degraded = true,
                )
            val gap = gaps.single()
            gap.kind shouldBe GapKind.GAP_KIND_G5_NLP_DARK
            gap.span.start shouldBe 0
            gap.span.end shouldBe 12
            // the answer still goes out — with the banner. That is a different promise from
            // UNRESOLVED, and the disposition is where the difference lives.
            gap.disposition shouldBe Disposition.DISPOSITION_DEGRADED
        }

        "G6_INCOHERENT — expressible, and deliberately never produced by a deterministic core" {
            val record =
                GapRecord
                    .newBuilder()
                    .setKind(GapKind.GAP_KIND_G6_INCOHERENT)
                    .setSpan(Span.newBuilder().setStart(0).setEnd(12))
                    .setDisposition(Disposition.DISPOSITION_UNRESOLVED)
                    .build()
            GapRecord.parseFrom(record.toByteArray()).kind shouldBe GapKind.GAP_KIND_G6_INCOHERENT

            // "the question does not cohere" is a judgement about meaning; contracts §3 routes it
            // to the `capable` rung. A core that claimed to detect it would be guessing.
            val everything =
                Gaps.assess(
                    mentions = listOf(mention("m1", 0, 6, "Zobraz"), mention("m2", 7, 12, "tržby", bound = true)),
                    values = listOf(literal("v1", 13, 15, "10"), grounded("v2", 16, 21, "Praze", "LOCATION")),
                    ambiguousSpans = setOf(0 to 6),
                    parse = AnalyzeResponse.getDefaultInstance(),
                    degraded = true,
                )
            everything.none { it.kind == GapKind.GAP_KIND_G6_INCOHERENT } shouldBe true
        }

        "G2_AMBIGUOUS on a VALUE — the door is asking about it, so the lattice may not call it settled" {
            // One code, two accounts. It is not UNattributed — it is OVER-attributed, so every
            // "not a gap" rule below would have dropped it and the ladder would have seen a
            // clarification on the wire with nothing in the lattice to re-enter (p2-1 review).
            val overAttributed =
                literal("v1", 20, 26, "501001")
                    .toBuilder()
                    .setAnchorMentionId("m1")
                    .addAttributions(attribution("md.dimension.Account.code#501001-a"))
                    .addAttributions(attribution("md.dimension.Account.code#501001-b"))
                    .build()
            val gaps =
                Gaps.assess(
                    mentions = listOf(mention("m1", 15, 19, "účtu", FrameRole.FRAME_ROLE_FILTER, bound = true)),
                    values = listOf(overAttributed),
                    ambiguousSpans = setOf(20 to 26),
                    parse = AnalyzeResponse.getDefaultInstance(),
                    degraded = false,
                )
            val gap = gaps.single()
            gap.kind shouldBe GapKind.GAP_KIND_G2_AMBIGUOUS
            gap.valueId shouldBe "v1"
            gap.mentionId shouldBe ""
            // and it is NOT reported as a method miss just because a mention scoped it: the
            // lookup did not miss, it found two.
            gap.frameRolesList shouldContainExactly listOf(FrameRole.FRAME_ROLE_FILTER)
            // the candidates survive — refusing to choose is not refusing to report
            overAttributed.attributionsCount shouldBe 2
        }

        "an unambiguous value with an attribution is still not a gap — the control" {
            val bound =
                literal("v1", 20, 26, "501001")
                    .toBuilder()
                    .setAnchorMentionId("m1")
                    .addAttributions(attribution("md.dimension.Account.code#501001"))
                    .build()
            Gaps.assess(
                mentions = listOf(mention("m1", 15, 19, "účtu", bound = true)),
                values = listOf(bound),
                ambiguousSpans = emptySet(),
                parse = AnalyzeResponse.getDefaultInstance(),
                degraded = false,
            ) shouldBe emptyList()
        }

        "a literal an OPERATOR scoped is that operator's argument, not an unattributed value" {
            val operator =
                mention("m1", 7, 14, "prvních")
                    .toBuilder()
                    .addBindings(binding("op:top-n", TargetClass.TARGET_CLASS_OPERATOR))
                    .build()
            val gaps =
                Gaps.assess(
                    mentions = listOf(operator),
                    values = listOf(literal("v1", 15, 17, "10").toBuilder().setAnchorMentionId("m1").build()),
                    ambiguousSpans = emptySet(),
                    parse = AnalyzeResponse.getDefaultInstance(),
                    degraded = false,
                )
            gaps shouldBe emptyList()
        }

        "a date grounds itself; a place does not" {
            val gaps =
                Gaps.assess(
                    mentions = emptyList(),
                    values =
                        listOf(
                            grounded("v1", 34, 38, "2025", "DATE"),
                            grounded("v2", 40, 45, "Praze", "LOCATION"),
                        ),
                    ambiguousSpans = emptySet(),
                    parse = AnalyzeResponse.getDefaultInstance(),
                    degraded = false,
                )
            // a date IS the value the planner will use; a place is a hint until something
            // attributes it to an attribute of the model
            gaps.map { it.valueId } shouldContainExactly listOf("v2")
        }

        // ── #139 — a number that names something is not self-grounding ───────────────────────────

        fun gapsFor(
            value: ValueFinding,
            vararg tokens: Token,
        ) = Gaps.assess(
            mentions = emptyList(),
            values = listOf(value),
            ambiguousSpans = emptySet(),
            parse = AnalyzeResponse.newBuilder().addAllTokens(tokens.toList()).build(),
            degraded = false,
        )

        "#139 — an account code the NER typed a number is a G3, so the turn can ask" {
            // the real parses: cs Stanza hangs `501001` off `účet` as `dep`, en Stanza off `account` as `flat`
            val cs =
                gapsFor(
                    grounded("v1", 5, 11, "501001", "MISC"),
                    tok("účet", 0, 4, "NOUN", 0, "root"),
                    tok("501001", 5, 11, "NUM", 1, "dep"),
                )
            cs.map { it.kind to it.valueId } shouldContainExactly listOf(GapKind.GAP_KIND_G3_UNATTRIBUTED to "v1")

            val en =
                gapsFor(
                    grounded("v1", 8, 14, "501001", "MISC"),
                    tok("account", 0, 7, "NOUN", 0, "root"),
                    tok("501001", 8, 14, "NUM", 1, "flat"),
                )
            en.map { it.kind } shouldContainExactly listOf(GapKind.GAP_KIND_G3_UNATTRIBUTED)
        }

        "#139 — the relation does not decide, the order does: a code after its noun as `nummod` is a G3" {
            // hartland, live (0.11.6): in the fuller sentence cs Stanza hangs the code off `účet` as
            // `nummod`, where the bare phrase had `dep` — `Náklady na účet 501001 v roce 2025`
            val gaps =
                gapsFor(
                    grounded("v1", 16, 22, "501001", "MISC"),
                    tok("Náklady", 0, 7, "NOUN", 0, "root"),
                    tok("na", 8, 10, "ADP", 3, "case"),
                    tok("účet", 11, 15, "NOUN", 1, "nmod"),
                    tok("501001", 16, 22, "NUM", 3, "nummod"),
                )
            gaps.map { it.kind } shouldContainExactly listOf(GapKind.GAP_KIND_G3_UNATTRIBUTED)
        }

        "#139 — a count, a threshold, a bare number and a parse-less value stay self-grounding" {
            // `top 10 products`: the number counts its noun
            gapsFor(
                grounded("v1", 4, 6, "10", "MISC"),
                tok("top", 0, 3, "ADJ", 3, "amod"),
                tok("10", 4, 6, "NUM", 3, "nummod"),
                tok("products", 7, 15, "NOUN", 0, "root"),
            ).shouldBeEmpty()
            // `sales above 1000`: a preposition makes it a comparison, not a name
            gapsFor(
                grounded("v1", 12, 16, "1000", "MISC"),
                tok("sales", 0, 5, "NOUN", 0, "root"),
                tok("above", 6, 11, "ADP", 3, "case"),
                tok("1000", 12, 16, "NUM", 1, "nmod"),
            ).shouldBeEmpty()
            // a bare number names nothing
            gapsFor(grounded("v1", 0, 6, "501001", "MISC"), tok("501001", 0, 6, "NUM", 0, "root")).shouldBeEmpty()
            // no parse (a re-gate of a parse-less lattice): unchanged
            gapsFor(grounded("v1", 5, 11, "501001", "MISC")).shouldBeEmpty()
            // `earned 5000`: an amount a VERB takes names nothing
            gapsFor(
                grounded("v1", 7, 11, "5000", "MISC"),
                tok("earned", 0, 6, "VERB", 0, "root"),
                tok("5000", 7, 11, "NUM", 1, "obj"),
            ).shouldBeEmpty()
            // cs `Top 10` (live): `10` follows the NOUN `Top`, but a rank word is followed by a count
            gapsFor(
                grounded("v1", 4, 6, "10", "MISC"),
                tok("Top", 0, 3, "NOUN", 0, "root"),
                tok("10", 4, 6, "NUM", 1, "nummod"),
            ).shouldBeEmpty()
            // before its noun it counts, even when a human reads a code (`item 1001 sales`, live)
            gapsFor(
                grounded("v1", 5, 9, "1001", "MISC"),
                tok("item", 0, 4, "NOUN", 3, "compound"),
                tok("1001", 5, 9, "NUM", 3, "nummod"),
                tok("sales", 10, 15, "NOUN", 0, "root"),
            ).shouldBeEmpty()
            // a spelled-out numeral is not a code, whatever relation it hangs by
            gapsFor(
                grounded("v1", 5, 10, "deset", "MISC"),
                tok("účet", 0, 4, "NOUN", 0, "root"),
                tok("deset", 5, 10, "NUM", 1, "dep"),
            ).shouldBeEmpty()
        }

        "#139 — through the pipeline: NameTag's `cnec:n_` account code keeps its grounding AND gets its G3" {
            // hartland, live: `501001` = MISC · cnec:n_ (universal), so no path proposes it for a lookup
            val r =
                MhMembers.resolve(
                    "účet 501001",
                    arrayOf(
                        MhMembers.tok("účet", 0, 4, "účet", "NOUN", 0, "root"),
                        MhMembers.tok("501001", 5, 11, "501001", "NUM", 1, "dep"),
                    ),
                    lang = "cs",
                    entities = listOf(MhMembers.ner("501001", 5, 11, "MISC", "cnec:n_")),
                )

            val value = r.resolutionState.valuesList.single { it.span.text == "501001" }
            value.kind shouldBe ValueKind.VALUE_KIND_GROUNDED
            value.grounding.kind shouldBe "MISC"
            r.resolutionState.gapsList
                .filter { it.valueId == value.id }
                .map { it.kind } shouldContainExactly listOf(GapKind.GAP_KIND_G3_UNATTRIBUTED)
        }

        "#139 — the exception is for numbers only: a date under a noun is still the planner's value" {
            gapsFor(
                grounded("v1", 7, 11, "2025", "DATE"),
                tok("výnosy", 0, 6, "NOUN", 0, "root"),
                tok("2025", 7, 11, "NUM", 1, "nmod"),
            ).shouldBeEmpty()
        }

        "gaps come out in span order, whatever order they were found in" {
            val gaps =
                Gaps.assess(
                    mentions = listOf(mention("m1", 50, 60, "položky")),
                    values = listOf(grounded("v1", 10, 15, "Praze", "LOCATION")),
                    ambiguousSpans = emptySet(),
                    parse = AnalyzeResponse.getDefaultInstance(),
                    degraded = false,
                )
            gaps.map { it.span.start } shouldContainExactly listOf(10, 50)
        }
    }) {
    companion object {
        /** A parsed token; [head] is 1-based, 0 = root, as the NLP service sends it. */
        private fun tok(
            text: String,
            start: Int,
            end: Int,
            upos: String,
            head: Int,
            relation: String,
        ): Token =
            Token
                .newBuilder()
                .setText(text)
                .setCharStart(start)
                .setCharEnd(end)
                .setUpos(upos)
                .setDepHead(head)
                .setDepRelation(relation)
                .build()

        private fun mention(
            id: String,
            start: Int,
            end: Int,
            text: String,
            vararg roles: FrameRole,
            bound: Boolean = false,
        ): Mention {
            val builder =
                Mention
                    .newBuilder()
                    .setId(id)
                    .setSpan(
                        Span
                            .newBuilder()
                            .setStart(start)
                            .setEnd(end)
                            .setText(text),
                    ).addAllFrameRoles(roles.toList())
            if (bound) builder.addBindings(binding("md.x"))
            return builder.build()
        }

        private fun binding(
            ref: String,
            targetClass: TargetClass = TargetClass.TARGET_CLASS_MODEL_OBJECT,
        ): Binding =
            Binding
                .newBuilder()
                .setRef(ref)
                .setTargetClass(targetClass)
                .build()

        private fun attribution(ref: String): Attribution =
            Attribution
                .newBuilder()
                .setAttributeRef(ref.substringBefore('#'))
                .setBinding(binding(ref, TargetClass.TARGET_CLASS_MEMBER))
                .build()

        private fun literal(
            id: String,
            start: Int,
            end: Int,
            text: String,
        ): ValueFinding =
            ValueFinding
                .newBuilder()
                .setId(id)
                .setSpan(
                    Span
                        .newBuilder()
                        .setStart(start)
                        .setEnd(end)
                        .setText(text),
                ).setKind(ValueKind.VALUE_KIND_LITERAL)
                .build()

        private fun grounded(
            id: String,
            start: Int,
            end: Int,
            text: String,
            kind: String,
        ): ValueFinding =
            ValueFinding
                .newBuilder()
                .setId(id)
                .setSpan(
                    Span
                        .newBuilder()
                        .setStart(start)
                        .setEnd(end)
                        .setText(text),
                ).setKind(ValueKind.VALUE_KIND_GROUNDED)
                .setGrounding(Grounding.newBuilder().setKernel("nametag3").setKind(kind))
                .build()
    }
}
