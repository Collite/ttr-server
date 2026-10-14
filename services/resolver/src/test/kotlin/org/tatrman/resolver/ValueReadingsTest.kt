// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.Provenance
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.fuzzy.v1.TargetClass
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.Binder
import org.tatrman.resolver.pipeline.Bound
import org.tatrman.resolver.pipeline.DomainSpanCandidate
import org.tatrman.resolver.pipeline.EvidenceClasses
import org.tatrman.resolver.pipeline.GateSpans
import org.tatrman.resolver.pipeline.GatedSpan
import org.tatrman.resolver.pipeline.ValueReadings
import org.tatrman.resolver.v1.Binding

/**
 * ttr-server#227 — [ValueReadings.settle], the one step every gated span goes through before the lattice,
 * the round planner or the door reads it: a value never carries a model object.
 *
 * The door is asserted alongside the span on purpose. The first cut of #227 enforced the rule where the
 * lattice writes attributions, and the door (`GateSpans.outcomeOf`, which binds a span's first contender)
 * went on binding the column the lattice had refused.
 */
class ValueReadingsTest :
    StringSpec({

        "a value-shaped word a trigger claims is the kernel's mention when it read as a model object" {
            val settled = settle(gated(governed("roce", 29), declared(YEAR)), chronoOn(29, 33))

            settled.candidate.origin shouldBe DomainSpanCandidate.Origin.ANCHOR_PHRASE
            settled.candidate.anchored shouldBe false
            settled.contenders.shouldBeEmpty()
            door(settled).shouldBeEmpty()
        }

        "… and when it found nothing at all: the trigger, not a row sharing its lemma, makes it the kernel's" {
            // An estate that declares no `za rok`: before, `roce` stayed an unattributed value and the year
            // beside it lost its narrowing — the claim hung on an object reading the word did not need.
            val settled = settle(gated(governed("roce", 29)), chronoOn(29, 33))

            settled.candidate.origin shouldBe DomainSpanCandidate.Origin.ANCHOR_PHRASE
            settled.contenders.shouldBeEmpty()
        }

        "a value-shaped word a member reads stays a value, trigger or not" {
            val span = gated(governed("Brno DC", 40), member("Brno DC", WAREHOUSE))

            settle(span, chronoOn(40, 47)) shouldBeSameInstanceAs span
        }

        "a value-shaped word that reads as a model object and no trigger claims is a mention bound to it" {
            val settled = settle(gated(properNoun("revenu", 12), declared(REVENUE)), emptyMap())

            settled.candidate.origin shouldBe DomainSpanCandidate.Origin.ANCHOR_PHRASE
            settled.contenders.map { it.match.targetRef } shouldContainExactly listOf(REVENUE)
            // the door binds what the lattice's mention binds
            door(settled).map { it.targetRef } shouldContainExactly listOf(REVENUE)
        }

        "a code that reads only as a model object keeps its span and loses the reading — on the door too" {
            val settled = settle(gated(literal("501001", 60), declared(ACCOUNT_CODE)), emptyMap())

            settled.candidate.origin shouldBe DomainSpanCandidate.Origin.LITERAL
            settled.contenders.shouldBeEmpty()
            door(settled).shouldBeEmpty()
        }

        "a dual reading is never a mention: it keeps its member, and a tie only an object made is no tie" {
            val tn = governed("TN", 60).copy(dualReadingOf = 60 to 62)
            val settled =
                settle(gated(tn, declared(YEAR), member("TN", STATE), ambiguous = true), chronoOn(60, 62))

            settled.candidate.origin shouldBe DomainSpanCandidate.Origin.GOVERNED_VALUE
            settled.contenders.map { it.match.candidateId } shouldContainExactly listOf("TN")
            settled.ambiguous shouldBe false
            door(settled).map { it.resolvedId } shouldContainExactly listOf("TN")
        }

        "a tie between two members is still one" {
            val settled =
                settle(
                    gated(
                        literal("Brno", 70),
                        declared(YEAR),
                        member("Brno", WAREHOUSE),
                        member("Brno", STATE),
                        ambiguous = true,
                    ),
                    emptyMap(),
                )

            settled.contenders.map { it.match.category } shouldContainExactly listOf(WAREHOUSE, STATE)
            settled.ambiguous shouldBe true
        }

        "a word with no head is not one a mention can be built on" {
            val headless = governed("Octavie", 80).copy(headToken = -1)
            val settled = settle(gated(headless, declared(YEAR)), chronoOn(80, 87))

            settled.candidate.origin shouldBe DomainSpanCandidate.Origin.GOVERNED_VALUE
            settled.contenders.shouldBeEmpty()
        }

        "a mention is left as it is: the rule is about values" {
            val mention = gated(anchorPhrase("roce", 29), declared(YEAR))

            settle(mention, chronoOn(29, 33)) shouldBeSameInstanceAs mention
        }

        "settling twice is settling once — a round re-settles what the broad pass settled" {
            val spans =
                listOf(
                    gated(governed("roce", 29), declared(YEAR)),
                    gated(governed("rok", 35)),
                    gated(properNoun("revenu", 12), declared(REVENUE)),
                    gated(literal("501001", 60), declared(ACCOUNT_CODE), member("501001", ACCOUNT_CODE)),
                    gated(governed("Brno DC", 40), member("Brno DC", WAREHOUSE)),
                )
            val triggers = chronoOn(29, 33) + chronoOn(35, 38)
            val once = ValueReadings.settle(spans, triggers)

            ValueReadings.settle(once, triggers) shouldBe once
        }
    }) {
    private companion object {
        const val YEAR = "er.entity.date_dim.year"
        const val REVENUE = "er.entity.channel_sales.ext_sales_price"
        const val ACCOUNT_CODE = "md.dimension.Account.code"
        const val WAREHOUSE = "er.entity.warehouse.warehouse_name"
        const val STATE = "er.entity.store.state"

        /** A `ground:chrono` trigger the batch answered for [start, end). */
        fun chronoOn(
            start: Int,
            end: Int,
        ): Map<Pair<Int, Int>, List<Binding>> =
            mapOf((start to end) to listOf(Binding.newBuilder().setRef("ground:chrono").build()))

        fun settle(
            span: GatedSpan,
            triggers: Map<Pair<Int, Int>, List<Binding>>,
        ): GatedSpan = ValueReadings.settle(listOf(span), triggers).single()

        /** What `Resolution.bindings` carries for [span] — the door. */
        fun door(span: GatedSpan) =
            GateSpans
                .outcomeOf(listOf(span), emptyList(), ResolverThresholds.LIVE, emptyMap(), "snap-1")
                .shouldBeInstanceOf<Bound>()
                .bindings

        fun gated(
            candidate: DomainSpanCandidate,
            vararg matches: FuzzyMatch,
            ambiguous: Boolean = false,
        ): GatedSpan =
            GatedSpan(
                candidate,
                matches.map {
                    Binder.ClassedMatch(it, EvidenceClasses.of(it, candidate.anchored, ResolverThresholds.LIVE))
                },
                ambiguous,
            )

        fun candidate(
            text: String,
            start: Int,
            origin: DomainSpanCandidate.Origin,
            anchored: Boolean,
            head: Int,
        ) = DomainSpanCandidate(
            text,
            start,
            start + text.length,
            listOf("er.entity.web_sales"),
            listOf("er.entity.web_sales"),
            anchored = anchored,
            origin = origin,
            headToken = head,
            lemma = text,
        )

        fun governed(
            text: String,
            start: Int,
        ) = candidate(text, start, DomainSpanCandidate.Origin.GOVERNED_VALUE, anchored = true, head = 8)

        fun properNoun(
            text: String,
            start: Int,
        ) = candidate(text, start, DomainSpanCandidate.Origin.PROPER_NOUN, anchored = false, head = 1)

        fun anchorPhrase(
            text: String,
            start: Int,
        ) = candidate(text, start, DomainSpanCandidate.Origin.ANCHOR_PHRASE, anchored = false, head = 8)

        fun literal(
            text: String,
            start: Int,
        ) = candidate(text, start, DomainSpanCandidate.Origin.LITERAL, anchored = true, head = -1)

        fun declared(targetRef: String): FuzzyMatch =
            FuzzyMatch
                .newBuilder()
                .setCandidateId("lex:$targetRef")
                .setCandidate(targetRef)
                .setScore(1.0)
                .setCategory(targetRef)
                .setSource(SourceTag.DECLARED)
                .setTargetRef(targetRef)
                .setTargetClass(TargetClass.TARGET_CLASS_MODEL_OBJECT)
                .setMatchMethod("EXACT")
                .setProvenance(Provenance.newBuilder().setProducer("lex-matcher").setMethod("TATRMAN"))
                .build()

        fun member(
            id: String,
            category: String,
        ): FuzzyMatch =
            FuzzyMatch
                .newBuilder()
                .setCandidateId(id)
                .setCandidate(id)
                .setScore(1.0)
                .setCategory(category)
                .setSource(SourceTag.MEMBER)
                .build()
    }
}
