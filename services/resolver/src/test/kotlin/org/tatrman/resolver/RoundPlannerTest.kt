// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.pipeline.LookupRoundConfig
import org.tatrman.resolver.pipeline.RoundPlanner
import org.tatrman.resolver.v1.Binding
import org.tatrman.resolver.v1.Disposition
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.GapRecord
import org.tatrman.resolver.v1.Mention
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.Span
import org.tatrman.resolver.v1.ValueFinding
import org.tatrman.resolver.v1.ValueKind
import org.tatrman.fuzzy.v1.TargetClass as FuzzyTargetClass

/**
 * RV-P2.3.T2 — the round planner, fed lattices and asked for query plans.
 *
 * Tested apart from execution because the interesting failures are decisions, not plumbing: a
 * planner that widens before it has narrowed spends the estate's precision on the first round, and
 * one that never widens leaves recall on the floor. Neither is visible through a fake matcher —
 * both are visible here, as the shape of the query it planned.
 */
class RoundPlannerTest :
    StringSpec({

        val config = LookupRoundConfig.DEFAULT
        val account =
            ResolverEntityType(
                "md.dimension.Account",
                listOf("md.dimension.Account", "md.dimension.Account.code", "md.dimension.Account.name"),
                listOf("účet"),
            )

        "a G4 narrows into the axis the user named — the anchor's categories, and only those" {
            val plan = RoundPlanner.plan(g4Lattice(), listOf(account), emptySet(), config)
            val query = plan.single()
            query.tier shouldBe RoundPlanner.Tier.ANCHORED_VALUE
            query.term shouldBe "501001"
            query.categories shouldContainExactlyInAnyOrder account.categories
            // No class filter: a member value carries no class, and filtering by one would exclude
            // the very rows this tier exists to reach (contracts §1 addendum, rule 4).
            query.targetClasses shouldBe emptyList()
        }

        "MV §5.3 — a G4 under an ENTITY re-asks inside the entity's member vocabularies too" {
            // The v5-archive shape: the entity is gated by its own ref only, and its values live
            // in an indexed attribute's vocabulary, registered under the attribute's ref. The
            // anchor's categories alone cannot hold `501001` — the same scope rule the governed
            // lookup reads (`valueCategoriesByRef`), so the round re-asks where the value can be.
            val entity = ResolverEntityType("md.dimension.Account", listOf("md.dimension.Account"), listOf("účet"))
            val code =
                ResolverEntityType(
                    "md.dimension.Account.code",
                    listOf("md.dimension.Account.code"),
                    emptyList(),
                    objectKind = "attribute",
                    ownerRef = "md.dimension.Account",
                    memberVocabulary = true,
                )

            RoundPlanner
                .plan(g4Lattice(), listOf(entity, code), emptySet(), config)
                .single()
                .categories shouldContainExactly listOf("md.dimension.Account", "md.dimension.Account.code")
        }

        "a G4 whose anchor bound NOTHING is not planned — an unbound anchor lends no scope" {
            val lattice =
                g4Lattice()
                    .toBuilder()
                    .apply {
                        setMentions(0, getMentions(0).toBuilder().clearBindings())
                    }.build()
            RoundPlanner.plan(lattice, listOf(account), emptySet(), config) shouldBe emptyList()
        }

        "a G1 is asked cross-category but CLASS-scoped: a mention is a name, not a data value" {
            val plan = RoundPlanner.plan(g1Lattice(), listOf(account), emptySet(), config)
            val query = plan.single()
            query.tier shouldBe RoundPlanner.Tier.UNBOUND_MENTION
            query.term shouldBe "čerpacích stanic"
            // EMPTY = every category (contracts §1 addendum) — the deliberate cross-category round
            // a rung runs before it knows the target.
            query.categories shouldBe emptyList()
            query.targetClasses shouldContainExactly
                listOf(
                    FuzzyTargetClass.TARGET_CLASS_MODEL_OBJECT,
                    FuzzyTargetClass.TARGET_CLASS_OPERATOR,
                )
        }

        "a G3 widens, last and bounded — `max_candidates` is the cap that keeps it honest" {
            val plan = RoundPlanner.plan(g3Lattice(), listOf(account), emptySet(), config)
            val query = plan.single()
            query.tier shouldBe RoundPlanner.Tier.BROAD
            query.term shouldBe "Praze"
            query.categories shouldBe emptyList()
            query.maxCandidates shouldBe config.broadMaxCandidates
        }

        "tiers are strict: with a G4 and a G3 both open, ONLY the G4 is planned this round" {
            val lattice =
                g4Lattice()
                    .toBuilder()
                    .addValues(value("v9", 40, 45, "Praze", ValueKind.VALUE_KIND_GROUNDED))
                    .addGaps(gap(GapKind.GAP_KIND_G3_UNATTRIBUTED, 40, 45, "Praze", valueId = "v9"))
                    .build()
            val plan = RoundPlanner.plan(lattice, listOf(account), emptySet(), config)
            plan.map { it.tier } shouldContainExactly listOf(RoundPlanner.Tier.ANCHORED_VALUE)
        }

        "…and the G3 becomes plannable once the G4 query has been asked — that is the fallback" {
            val lattice =
                g4Lattice()
                    .toBuilder()
                    .addValues(value("v9", 40, 45, "Praze", ValueKind.VALUE_KIND_GROUNDED))
                    .addGaps(gap(GapKind.GAP_KIND_G3_UNATTRIBUTED, 40, 45, "Praze", valueId = "v9"))
                    .build()
            val first = RoundPlanner.plan(lattice, listOf(account), emptySet(), config)
            val second = RoundPlanner.plan(lattice, listOf(account), first.map { it.key }.toSet(), config)
            second.map { it.tier } shouldContainExactly listOf(RoundPlanner.Tier.BROAD)
            // and then it is genuinely dry — this is what terminates the loop
            val asked = (first + second).map { it.key }.toSet()
            RoundPlanner.plan(lattice, listOf(account), asked, config) shouldBe emptyList()
        }

        "G2 and G5 are not this rung's business (contracts §3) — neither is ever planned" {
            val lattice =
                ResolutionState
                    .newBuilder()
                    .addGaps(gap(GapKind.GAP_KIND_G2_AMBIGUOUS, 0, 2, "DF", valueId = "v1"))
                    .addGaps(gap(GapKind.GAP_KIND_G5_NLP_DARK, 0, 40, "", valueId = ""))
                    .addValues(value("v1", 0, 2, "DF", ValueKind.VALUE_KIND_LITERAL))
                    .build()
            RoundPlanner.plan(lattice, listOf(account), emptySet(), config) shouldBe emptyList()
        }

        "a gap-free lattice plans nothing at all: a question that resolved costs zero rounds" {
            RoundPlanner.plan(
                ResolutionState.newBuilder().addMentions(boundMention()).build(),
                listOf(account),
                emptySet(),
                config,
            ) shouldBe emptyList()
        }

        "one round's fan-out is capped, and the remainder is simply next round's work" {
            val builder = ResolutionState.newBuilder()
            repeat(5) { i ->
                builder.addMentions(
                    Mention.newBuilder().setId("m$i").setSpan(span(i * 10, i * 10 + 4, "word$i")),
                )
                builder.addGaps(gap(GapKind.GAP_KIND_G1_UNBOUND, i * 10, i * 10 + 4, "word$i", mentionId = "m$i"))
            }
            val capped = config.copy(maxQueriesPerRound = 2)
            RoundPlanner.plan(builder.build(), listOf(account), emptySet(), capped).size shouldBe 2
        }
    }) {
    private companion object {
        private fun span(
            start: Int,
            end: Int,
            text: String,
        ): Span =
            Span
                .newBuilder()
                .setStart(start)
                .setEnd(end)
                .setText(text)
                .build()

        private fun gap(
            kind: GapKind,
            start: Int,
            end: Int,
            text: String,
            mentionId: String = "",
            valueId: String = "",
        ): GapRecord {
            val b =
                GapRecord
                    .newBuilder()
                    .setKind(kind)
                    .setSpan(span(start, end, text))
                    .setDisposition(Disposition.DISPOSITION_UNRESOLVED)
            if (mentionId.isNotBlank()) b.mentionId = mentionId
            if (valueId.isNotBlank()) b.valueId = valueId
            return b.build()
        }

        private fun value(
            id: String,
            start: Int,
            end: Int,
            text: String,
            kind: ValueKind,
            anchor: String = "",
        ): ValueFinding {
            val b =
                ValueFinding
                    .newBuilder()
                    .setId(id)
                    .setSpan(span(start, end, text))
                    .setKind(kind)
            if (anchor.isNotBlank()) b.anchorMentionId = anchor
            return b.build()
        }

        private fun boundMention(): Mention =
            Mention
                .newBuilder()
                .setId("m1")
                .setSpan(span(15, 19, "účtu"))
                .addBindings(Binding.newBuilder().setRef("md.dimension.Account"))
                .build()

        /** *"…účtu 501001…"* — the axis is bound, the code inside it missed. */
        private fun g4Lattice(): ResolutionState =
            ResolutionState
                .newBuilder()
                .addMentions(boundMention())
                .addValues(value("v1", 20, 26, "501001", ValueKind.VALUE_KIND_LITERAL, anchor = "m1"))
                .addGaps(gap(GapKind.GAP_KIND_G4_METHOD_MISS, 20, 26, "501001", valueId = "v1"))
                .build()

        /** issues.md §2 — the estate has no word for it. */
        private fun g1Lattice(): ResolutionState =
            ResolutionState
                .newBuilder()
                .addMentions(Mention.newBuilder().setId("m3").setSpan(span(19, 35, "čerpacích stanic")))
                .addGaps(gap(GapKind.GAP_KIND_G1_UNBOUND, 19, 35, "čerpacích stanic", mentionId = "m3"))
                .build()

        /** issues.md §2 — a place nobody attributed. */
        private fun g3Lattice(): ResolutionState =
            ResolutionState
                .newBuilder()
                .addValues(value("v2", 39, 44, "Praze", ValueKind.VALUE_KIND_GROUNDED))
                .addGaps(gap(GapKind.GAP_KIND_G3_UNATTRIBUTED, 39, 44, "Praze", valueId = "v2"))
                .build()
    }
}
