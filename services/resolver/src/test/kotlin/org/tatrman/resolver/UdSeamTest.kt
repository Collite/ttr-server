// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.nlp.v1.NerEntity
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.pipeline.Binder
import org.tatrman.resolver.pipeline.DomainSpanCandidate
import org.tatrman.resolver.pipeline.GatedSpan
import org.tatrman.resolver.pipeline.SpanProposal
import org.tatrman.resolver.pipeline.UniversalBinding
import org.tatrman.resolver.pipeline.UniversalSeam
import org.tatrman.resolver.v1.EvidenceClass
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.resolver.v1.UniversalEntityType
import org.tatrman.resolver.v1.ValueKind

/**
 * UD-P1 (ttr-server#118, option 1) — the seam between a universal NER reading and the member
 * reading proposed over the same characters (UD contracts §4, A-UD-2).
 *
 * The rule: a dual-reading span that found a contender SUPERSEDES the universal under it (the
 * member wins the span); a dual-reading span that found nothing is WITHDRAWN (the universal stands
 * alone, exactly as before UD). Either way the lattice carries one finding per span.
 */
class UdSeamTest :
    StringSpec({

        // ── the pure rule ─────────────────────────────────────────────────────────────────────

        val tn = UniversalBinding(10, 12, "TN", UniversalEntityType.LOCATION, "TN", "", "stanza")

        fun gated(
            start: Int = 10,
            end: Int = 12,
            dual: Boolean = true,
            contenders: Int = 1,
            origin: DomainSpanCandidate.Origin = DomainSpanCandidate.Origin.GOVERNED_VALUE,
        ) = GatedSpan(
            DomainSpanCandidate(
                text = "TN",
                start = start,
                end = end,
                gatedEntityRefs = emptyList(),
                categories = emptyList(),
                anchored = origin == DomainSpanCandidate.Origin.GOVERNED_VALUE,
                origin = origin,
                dualReading = dual,
            ),
            List(contenders) { i ->
                Binder.ClassedMatch(
                    FuzzyMatch
                        .newBuilder()
                        .setCandidateId("TN")
                        .setCategory("c$i")
                        .build(),
                    EvidenceClass.EVIDENCE_CLASS_DECLARED_ALIAS,
                )
            },
            ambiguous = contenders > 1,
        )

        "a dual reading with a contender over the universal supersedes it" {
            val g = gated()
            val seam = UniversalSeam.supersede(listOf(tn), listOf(g))

            seam.universals.shouldBeEmpty()
            seam.superseded shouldContainExactly listOf(tn to g)
            seam.gated shouldContainExactly listOf(g)
        }

        "an ambiguous dual reading still supersedes — an ask among members is a member reading (⚑UD-2)" {
            val g = gated(contenders = 3)
            UniversalSeam.supersede(listOf(tn), listOf(g)).universals.shouldBeEmpty()
        }

        "a dual reading that found nothing is withdrawn and the universal stands (A-UD-2)" {
            val seam = UniversalSeam.supersede(listOf(tn), listOf(gated(contenders = 0)))

            seam.universals shouldContainExactly listOf(tn)
            seam.superseded.shouldBeEmpty()
            // One span, one finding: kept, it would reach the lattice as an unattributed LITERAL
            // beside the grounded place, with a second G3 over the same characters.
            seam.gated.shouldBeEmpty()
        }

        "an accidental overlap never supersedes: the candidate must be a dual reading" {
            val g = gated(dual = false)
            val seam = UniversalSeam.supersede(listOf(tn), listOf(g))

            seam.universals shouldContainExactly listOf(tn)
            seam.gated shouldContainExactly listOf(g)
        }

        "a dual reading that does not cover the universal does not supersede it" {
            val narrower = gated(start = 10, end = 11)
            UniversalSeam.supersede(listOf(tn), listOf(narrower)).universals shouldContainExactly listOf(tn)
        }

        "only what survived the open-sibling collapse counts (§4.5): the open sibling alone supersedes, once" {
            // E13's shape after `resolveOpenSiblings`: the governed half found nothing and is gone,
            // the open half found the members. The gate already chose; the seam counts what is left.
            val open = gated(contenders = 3, origin = DomainSpanCandidate.Origin.OPEN_VALUE)
            val seam = UniversalSeam.supersede(listOf(tn), listOf(open))

            seam.superseded
                .single()
                .second.candidate.origin shouldBe DomainSpanCandidate.Origin.OPEN_VALUE
            seam.superseded
                .single()
                .second.contenders.size shouldBe 3
        }

        "no dual reading anywhere ⇒ the inputs come back untouched (I-2)" {
            val other = gated(start = 0, end = 6, dual = false, origin = DomainSpanCandidate.Origin.ANCHOR_PHRASE)
            val seam = UniversalSeam.supersede(listOf(tn), listOf(other))

            seam.universals shouldBe listOf(tn)
            seam.gated shouldBe listOf(other)
            seam.superseded.shouldBeEmpty()
        }

        // ── through the pipeline (MhMembers harness: §8.5 registry, member vocabularies flagged) ──

        fun ResolveResponse.findingsOn(text: String) = resolutionState.valuesList.filter { it.span.text == text }

        fun ResolveResponse.gapsOn(text: String) =
            resolutionState.gapsList.filter { it.span.text == text }.map { it.kind }

        fun place(
            text: String,
            start: Int,
            label: String = "GPE",
        ): List<NerEntity> = listOf(MhMembers.ner(text, start, start + text.length, label))

        "I-3 `Stores in TN` + GPE — the member wins the span: one LITERAL finding, attributed, no place" {
            val r = MhMembers.resolve("Stores in TN", MhMembers.e11En(), entities = place("TN", 10))

            val tnFinding = r.findingsOn("TN").single()
            tnFinding.kind shouldBe ValueKind.VALUE_KIND_LITERAL
            tnFinding.hasGrounding() shouldBe false
            tnFinding.attributionsList.map { it.binding.ref } shouldContainExactly listOf("${MhMembers.STORE_STATE}#TN")
            r.gapsOn("TN").shouldBeEmpty()
        }

        "I-3 — and the door agrees: no universal binding, the member is a domain one" {
            // The COUNT form, because `Stores` alone is the store/store_sales object homonym and asks.
            val r =
                MhMembers.resolve("How many stores in TN", MhMembers.e11Count(), entities = place("TN", 19))

            r.hasAwaiting() shouldBe false
            r.resolution.bindingsList.count { it.hasUniversal() } shouldBe 0
            r.resolution.bindingsList
                .single { it.domain.rawText == "TN" }
                .domain.memberOf shouldBe MhMembers.STORE_STATE
            r.resolution.rationale shouldContain "0 universal"
        }

        "I-4 `Stores in Paris` — no member anywhere: the place stands, alone, with its G3" {
            val tokens =
                arrayOf(
                    MhMembers.tok("Stores", 0, 6, "store", "NOUN", 0, "root"),
                    MhMembers.tok("in", 7, 9, "in", "ADP", 3, "case"),
                    MhMembers.tok("Paris", 10, 15, "Paris", "PROPN", 1, "nmod"),
                )
            val r = MhMembers.resolve("Stores in Paris", tokens, entities = place("Paris", 10))

            val paris = r.findingsOn("Paris").single()
            paris.kind shouldBe ValueKind.VALUE_KIND_GROUNDED
            paris.grounding.kind shouldBe UniversalEntityType.LOCATION.name
            paris.attributionsList.shouldBeEmpty()
            r.gapsOn("Paris") shouldContainExactly listOf(GapKind.GAP_KIND_G3_UNATTRIBUTED)
            // …and the question WAS asked: proposal is unconditional, only the outcome is not
            val parse =
                MhMembers
                    .parse(
                        "Stores in Paris",
                        tokens,
                    ).toBuilder()
                    .addAllEntities(place("Paris", 10))
                    .build()
            SpanProposal
                .proposeDomainSpans(parse, MhMembers.entityTypes())
                .filter { it.text == "Paris" }
                .map { it.origin to it.dualReading } shouldContainExactly
                listOf(DomainSpanCandidate.Origin.GOVERNED_VALUE to true, DomainSpanCandidate.Origin.OPEN_VALUE to true)
        }

        "I-5 `Customers in TN` — the open sibling + M3 bind the customer's address state; the place is superseded" {
            val r = MhMembers.resolve("Customers in TN", MhMembers.e13En(), entities = place("TN", 13))

            val tnFinding = r.findingsOn("TN").single()
            tnFinding.kind shouldBe ValueKind.VALUE_KIND_LITERAL
            tnFinding.attributionsList.map { it.binding.ref } shouldContainExactly listOf("${MhMembers.CA_STATE}#TN")
            r.resolution.bindingsList.count { it.hasUniversal() } shouldBe 0
        }

        "I-6 `Sales in TN` — a fact governs no member: the resolver asks by owner, and the place is gone" {
            val r = MhMembers.resolve("Sales in TN", MhMembers.e12En(), entities = place("TN", 9))

            r.hasAwaiting() shouldBe true
            r.awaiting.optionsList.map { it.memberOf } shouldContainExactlyInAnyOrder
                listOf(MhMembers.STORE_STATE, MhMembers.CA_STATE, MhMembers.WAREHOUSE_STATE)
            r.findingsOn("TN").single().kind shouldBe ValueKind.VALUE_KIND_LITERAL
        }

        "I-7 bare `TN` and `Nashville stores` — nothing governs the place, so it stays a place" {
            val bare = MhMembers.resolve("TN", MhMembers.e12Bare(), entities = place("TN", 0))
            bare.findingsOn("TN").single().kind shouldBe ValueKind.VALUE_KIND_GROUNDED
            bare.gapsOn("TN") shouldContainExactly listOf(GapKind.GAP_KIND_G3_UNATTRIBUTED)

            val preModifier: Array<Token> =
                arrayOf(
                    MhMembers.tok("Nashville", 0, 9, "Nashville", "PROPN", 2, "compound"),
                    MhMembers.tok("stores", 10, 16, "store", "NOUN", 0, "root"),
                )
            val r = MhMembers.resolve("Nashville stores", preModifier, entities = place("Nashville", 0))
            r.findingsOn("Nashville").single().kind shouldBe ValueKind.VALUE_KIND_GROUNDED
        }
    })
