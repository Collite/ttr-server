// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.runBlocking
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.resolver.pipeline.ReGate
import org.tatrman.resolver.v1.EntityType
import org.tatrman.resolver.v1.GateRequest
import org.tatrman.resolver.v1.Hypothesis
import org.tatrman.resolver.v1.Span
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.ValueKind

/**
 * ✅LP-13 in tatrman (review-103 ruling 5) — **the delimiter is also the explicit lookup request**.
 *
 * A quoted literal with NO `pred:` trigger, attributed to a name attribute that has a member
 * vocabulary, is looked up among that attribute's members, with its §1.4 text, by the lookup
 * rung's first tier. One match ⇒ it BINDS, and the literal is a member value from then on; several
 * ⇒ G2, and the door asks; none, no vocabulary or no answer ⇒ the VERBATIM `contains` stays.
 * A literal WITH a trigger is a filter pattern and is never looked up.
 *
 * *Ukaž dodací místa "Pelex"* — the hero without its trigger.
 */
class QuotedLookupTest :
    StringSpec({

        "one member matches ⇒ the literal BINDS it and is a member value, still marked quoted" {
            val (fuzzy, response) = resolve(lookup = listOf(member("Pelex Oil, a.s.", 0.93)))
            val state = response.resolutionState

            fuzzy.lookups shouldContainExactly listOf("Pelex")
            val value = state.valuesList.single()
            value.kind shouldBe ValueKind.VALUE_KIND_LITERAL
            // The span is still what the user typed, and `verbatim_text` still says it was quoted —
            // the re-gate refuses hypotheses on it whatever it bound.
            value.span.text shouldBe "\"Pelex\""
            value.verbatimText shouldBe "Pelex"
            value.attributionsList.single().attributeRef shouldBe STORE_NAME
            value.attributionsList
                .single()
                .binding.ref shouldBe "$STORE_NAME#Pelex Oil, a.s."
            value.anchorMentionId shouldBe state.mentionsList.single { it.span.text == "dodací místa" }.id
            // No VERBATIM reading beside it, and nothing left open.
            state.valuesList.none { it.kind == ValueKind.VALUE_KIND_VERBATIM }.shouldBeTrue()
            state.gapsList.shouldBeEmpty()
            response.hasResolution().shouldBeTrue()
            state.rungLogList.any { it.action == "lookup" && value.id in it.valueIdsList }.shouldBeTrue()
        }

        "several members match ⇒ G2 on the value, and the door asks" {
            val (_, response) =
                resolve(lookup = listOf(member("Pelex Oil, a.s.", 0.93), member("Pelex Trade s.r.o.", 0.93)))
            val state = response.resolutionState

            val value = state.valuesList.single()
            value.kind shouldBe ValueKind.VALUE_KIND_LITERAL
            state.gapsList.single { it.valueId == value.id }.kind shouldBe GapKind.GAP_KIND_G2_AMBIGUOUS
            response.hasAwaiting().shouldBeTrue()
        }

        "an ambiguous quoted value stays closed to the rungs: the user picks, no LLM does" {
            // G2 is a gap, and gaps invite rungs. The value is a LITERAL now, but its span was
            // quoted — `verbatim_text` says so — and the re-gate's refusal follows the quotes.
            val fuzzy =
                VerbatimHero.FakeFuzzy(
                    mapOf(
                        "dodací místa" to listOf(VerbatimHero.declared(STORE)),
                        "Pelex" to listOf(member("Pelex Oil, a.s.", 0.93), member("Pelex Trade s.r.o.", 0.93)),
                    ),
                )
            val (_, response) =
                VerbatimHero.resolve(VerbatimHero.registryOf(VerbatimHero.STORE, NAME_ATTRIBUTE), fuzzy, parse(), TEXT)
            val lattice = response.resolutionState
            val lookupsBefore = fuzzy.lookups.size

            val gated =
                runBlocking {
                    VerbatimHero.pipeline(fuzzy, parse()).gate(
                        GateRequest
                            .newBuilder()
                            .setLattice(lattice)
                            .addHypotheses(
                                Hypothesis
                                    .newBuilder()
                                    .setSpan(
                                        Span
                                            .newBuilder()
                                            .setStart(19)
                                            .setEnd(24)
                                            .setText("Pelex"),
                                    ).setRef("$STORE_NAME#Pelex Oil, a.s.")
                                    .setProposingRung("local"),
                            ).build(),
                    )
                }

            gated.outcomesList.single().reason shouldBe ReGate.Reason.VERBATIM_SPAN
            gated.gatedBindingsList.shouldBeEmpty()
            fuzzy.lookups.size shouldBe lookupsBefore
        }

        "no member matches ⇒ the VERBATIM contains stays, exactly as before" {
            val (fuzzy, response) = resolve(lookup = emptyList())

            fuzzy.lookups shouldContainExactly listOf("Pelex")
            val value = response.resolutionState.valuesList.single()
            value.kind shouldBe ValueKind.VALUE_KIND_VERBATIM
            value.predicateRef shouldBe "pred:contains"
            value.predicateImplied.shouldBeTrue()
        }

        "an attribute with no member vocabulary is not looked up — nothing to look it up in" {
            val (fuzzy, response) =
                resolve(
                    lookup = listOf(member("Pelex Oil, a.s.", 0.93)),
                    nameAttribute = NAME_ATTRIBUTE.toBuilder().setMemberVocabulary(false).build(),
                )

            fuzzy.lookups.shouldBeEmpty()
            response.resolutionState.valuesList
                .single()
                .kind shouldBe ValueKind.VALUE_KIND_VERBATIM
        }

        "a literal WITH a trigger is a filter pattern and is never looked up" {
            val fuzzy =
                VerbatimHero.FakeFuzzy(
                    mapOf(
                        "dodací místa" to listOf(VerbatimHero.declared(STORE)),
                        "začínající na" to listOf(VerbatimHero.predicate("pred:starts_with", "začínající na")),
                        "Pelex" to listOf(member("Pelex Oil, a.s.", 0.93)),
                    ),
                )
            val (_, response) =
                VerbatimHero.resolve(
                    VerbatimHero.registryOf(VerbatimHero.STORE, NAME_ATTRIBUTE),
                    fuzzy,
                )

            fuzzy.lookups.shouldBeEmpty()
            val value = response.resolutionState.valuesList.single()
            value.kind shouldBe ValueKind.VALUE_KIND_VERBATIM
            value.predicateRef shouldBe "pred:starts_with"
            value.predicateImplied.shouldBeFalse()
        }
    }) {
    companion object {
        private const val STORE = "er.entity.store"
        private const val STORE_NAME = "er.entity.store.name"

        /** The TEXT of the question: the hero, without its trigger. */
        private const val TEXT = "Ukaž dodací místa \"Pelex\""

        /** The store's name attribute as a v5 archive projects it: gated by its own ref, owned, flagged. */
        private val NAME_ATTRIBUTE: EntityType =
            EntityType
                .newBuilder()
                .setRef(STORE_NAME)
                .addCategories(STORE_NAME)
                .setObjectKind("attribute")
                .setOwnerRef(STORE)
                .setMemberVocabulary(true)
                .build()

        /** `Pelex` hangs off `místa` (nmod), one hop — the parse finds the head. */
        private fun parse(): AnalyzeResponse =
            AnalyzeResponse
                .newBuilder()
                .setLanguage("cs")
                .setDetectedLanguage("cs")
                .addTokens(VerbatimHero.token("Ukaž", 0, 4, "ukázat", "VERB", 0, "root"))
                .addTokens(VerbatimHero.token("dodací", 5, 11, "dodací", "ADJ", 3, "amod"))
                .addTokens(VerbatimHero.token("místa", 12, 17, "místo", "NOUN", 1, "obj"))
                .addTokens(VerbatimHero.token("\"", 18, 19, "\"", "PUNCT", 5, "punct"))
                .addTokens(VerbatimHero.token("Pelex", 19, 24, "Pelex", "PROPN", 3, "nmod"))
                .addTokens(VerbatimHero.token("\"", 24, 25, "\"", "PUNCT", 5, "punct"))
                .build()

        /** A member row of the store's name vocabulary, as the lookup returns one. */
        private fun member(
            value: String,
            score: Double,
        ): FuzzyMatch =
            VerbatimHero
                .member(value, STORE_NAME)
                .toBuilder()
                .setScore(score)
                .build()

        private fun resolve(
            lookup: List<FuzzyMatch>,
            nameAttribute: EntityType = NAME_ATTRIBUTE,
        ) = VerbatimHero.resolve(
            VerbatimHero.registryOf(VerbatimHero.STORE, nameAttribute),
            VerbatimHero.FakeFuzzy(
                mapOf(
                    "dodací místa" to listOf(VerbatimHero.declared(STORE)),
                    "Pelex" to lookup,
                ),
            ),
            parse(),
            TEXT,
        )
    }
}
