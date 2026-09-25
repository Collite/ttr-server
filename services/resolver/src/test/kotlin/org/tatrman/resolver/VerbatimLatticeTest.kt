// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.NerEntity
import org.tatrman.resolver.pipeline.Literals
import org.tatrman.resolver.pipeline.QuoteScanner
import org.tatrman.resolver.pipeline.SpanProposal
import org.tatrman.resolver.v1.EntityType
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ValueKind

/**
 * LP-P1·S2 — *"Ukaž dodací místa začínající na "Pelex""*, the question this effort started from.
 *
 * GI-2: the resolver kept proposing store MEMBERS for the quoted word, so the user's one way of
 * saying *take this string as typed* produced a clarification about which shop they meant. The
 * fix is structural rather than a threshold: a quoted span is not proposed at all, by any of the
 * six sources, and reaches the lattice as its own kind of value.
 *
 * Driven through the whole pipeline, not through `LatticeAssembler` alone, because the claim has
 * two halves and they live in different objects — *one VERBATIM value* is the assembler's, and
 * *zero candidates for Pelex* is span proposal's. A unit test of either half would pass while the
 * other regressed.
 */
class VerbatimLatticeTest :
    StringSpec({

        val text = VerbatimHero.TEXT
        val store = VerbatimHero.STORE

        fun registryOf(vararg types: EntityType) = VerbatimHero.registryOf(*types)

        fun resolve(
            registry: Registry,
            fuzzy: VerbatimHero.FakeFuzzy =
                VerbatimHero.FakeFuzzy(mapOf("dodací místa" to listOf(VerbatimHero.declared("er.entity.store")))),
            parse: AnalyzeResponse = VerbatimHero.parse(),
            questionText: String = text,
        ) = VerbatimHero.resolve(registry, fuzzy, parse, questionText)

        "the hero: ONE verbatim value on the store's name, and nothing was looked up for Pelex" {
            val (fuzzy, response) = resolve(registryOf(store))

            val verbatim = response.resolutionState.valuesList.single()
            verbatim.kind shouldBe ValueKind.VALUE_KIND_VERBATIM
            verbatim.verbatimText shouldBe "Pelex"
            // The SPAN is what was typed — quotes included. The TEXT is §1.4's content. Both, and
            // deliberately not one: the span is what an echo underlines and what a re-gate refuses
            // hypotheses on, the text is what travels as a bound parameter.
            verbatim.span.start shouldBe 32
            verbatim.span.end shouldBe 39
            verbatim.span.text shouldBe "\"Pelex\""
            verbatim.attributionsList.map { it.attributeRef } shouldContainExactly listOf("er.entity.store.name")
            // §2: no binding rides with it. There is no member to bind to and no layer that
            // produced it — the user quoted it.
            verbatim.attributionsList
                .single()
                .hasBinding()
                .shouldBeFalse()
            // §2.2: the question names no predicate, so the default for a name — `contains` — is
            // written out by the producer, which is the one place that knows the literal landed on
            // the NAME facet (review-103 D5, closing ⚑LPQ-7).
            verbatim.predicateRef shouldBe "pred:contains"

            // The other half, and the reason this runs through the pipeline: the matcher was never
            // asked about Pelex. Not "asked and refused" — never asked.
            //
            // One BatchMatch, several slots: the gate's, the grounding trigger annotation's
            // (RV-P1.6.T6) and — since LP-P2b — the `pred:` windows before the literal. What
            // matters here is what is NOT in it, and that the literal's own text is in no slot.
            val queries = fuzzy.lastRequest!!.spansList.map { it.query }
            queries.none { it.contains("Pelex") } shouldBe true
            // Every slot is either the proposed mention or a window of the three tokens before the
            // literal — nothing enumerates the question at large.
            queries.distinct() shouldContainExactlyInAnyOrder
                listOf(
                    "dodací místa",
                    "místa",
                    "začínající",
                    "na",
                    "místa začínající",
                    "začínající na",
                    // review-103 F1: the widest form is three words (*s názvem přesně*), so the
                    // window is too.
                    "místa začínající na",
                )
        }

        "LP-P2b — the hero now carries `pred:starts_with`, retrieved off the literal" {
            // The half P1 could not deliver: *začínající na* is a participle and a preposition, so
            // `SpanProposal` proposes no span over it (the hero case above asserts the only
            // proposed span is `dodací místa`). `PredicateTriggers` asks about the three tokens
            // before the literal instead, class-scoped, on the SAME BatchMatch.
            val fuzzy =
                VerbatimHero.FakeFuzzy(
                    mapOf(
                        "dodací místa" to listOf(VerbatimHero.declared("er.entity.store")),
                        "začínající na" to listOf(VerbatimHero.predicate("pred:starts_with", "začínající na")),
                    ),
                )
            val (asked, response) = resolve(registryOf(store), fuzzy)

            val verbatim = response.resolutionState.valuesList.single()
            verbatim.predicateRef shouldBe "pred:starts_with"
            // The attribution is unchanged — the two questions are answered independently.
            verbatim.attributionsList.map { it.attributeRef } shouldContainExactly listOf("er.entity.store.name")
            // Still ONE BatchMatch. The predicate windows are trailing slots on the pass the core
            // already makes, never a second round trip (B-T1).
            asked.lookups.shouldBeEmpty()
            val queries = asked.lastRequest!!.spansList.map { it.query }
            queries shouldContain "začínající na"
            // …and still nothing was asked about the literal itself.
            queries.none { it.contains("Pelex") } shouldBe true
        }

        "a question with no trigger gets §2.2's default for its facet, written out (review-103 D5)" {
            // The hero fixture answers no `pred:` row, so the windows come back empty — which is
            // also every estate whose archive predates the slice. Absent used to mean "default",
            // and left the consumer to guess the facet from the literal's shape; the producer now
            // says it, because only the producer knows which facet the literal landed on.
            val (_, response) = resolve(registryOf(store))

            response.resolutionState.valuesList
                .single()
                .predicateRef shouldBe "pred:contains"
        }

        "a headless literal keeps predicate_ref ABSENT — there is no facet to default from" {
            val (_, response) = resolve(registryOf(store.toBuilder().clearNameAttributeRef().build()))

            val verbatim = response.resolutionState.valuesList.single()
            verbatim.attributionsList.shouldBeEmpty()
            verbatim.hasPredicateRef().shouldBeFalse()
        }

        "LP-P2b — a predicate BELOW the bind floor is not evidence of anything" {
            // The same floor the gate applies to a model candidate. A weak hit on a participle is
            // how a filter nobody asked for would get into a plan.
            val weak =
                VerbatimHero.FakeFuzzy(
                    mapOf(
                        "dodací místa" to listOf(VerbatimHero.declared("er.entity.store")),
                        "začínající na" to
                            listOf(
                                VerbatimHero
                                    .predicate("pred:starts_with", "začínající na")
                                    .toBuilder()
                                    .setScore(0.1)
                                    .build(),
                            ),
                    ),
                )
            val (_, response) = resolve(registryOf(store), weak)

            // Not `starts_with`: the weak row was no evidence, so the default for a name stands.
            response.resolutionState.valuesList
                .single()
                .predicateRef shouldBe "pred:contains"
        }

        "review-103 F1 — a row that is a WIDER form than the window is a fragment, not a trigger" {
            // The live F1 shape: the one-word window `na` answered by the TOKENS form
            // *začínající na*. v2 scores the window's own tokens, so this came back at 1.0 with no
            // rival, and bound. The resolver now asks for the whole form.
            val fragment =
                VerbatimHero.FakeFuzzy(
                    mapOf(
                        "dodací místa" to listOf(VerbatimHero.declared("er.entity.store")),
                        "na" to listOf(VerbatimHero.predicate("pred:starts_with", "začínající na", method = "TOKENS")),
                    ),
                )
            val (_, response) = resolve(registryOf(store), fragment)

            response.resolutionState.valuesList
                .single()
                .predicateRef shouldBe "pred:contains"
        }

        "no gap is opened for a literal the question said which column belongs to" {
            val (_, response) = resolve(registryOf(store))

            // Attributed ⇒ nothing to ask about. G1/G2 in particular would be a bug of a specific
            // kind: they are the ask-the-user gaps, and asking which shop `"Pelex"` means is
            // exactly what the quoting existed to prevent.
            response.resolutionState.gapsList
                .filter { it.span.start == 32 }
                .shouldBeEmpty()
            response.resolutionState.valuesList
                .single()
                .anchorMentionId
                .shouldBe(
                    response.resolutionState.mentionsList
                        .first { it.span.text.contains("místa") }
                        .id,
                )
        }

        "an estate that declares no name attribute gets G3, not a guess" {
            // Not a channel gap any more (⚑LPQ-5 closed at P2b) — this is the estate that really
            // declares nothing: the head is found, but the model has not said which of its columns
            // carries the name. Emitting the value with zero attributions is
            // the honest answer — and `Gaps` types it G3, "nothing scoped it", which is the record
            // the ladder acts on. The alternative, picking a column, is the failure mode this
            // whole effort exists to retire.
            val silent = store.toBuilder().clearNameAttributeRef().build()

            val (_, response) = resolve(registryOf(silent))

            val verbatim = response.resolutionState.valuesList.single()
            verbatim.kind shouldBe ValueKind.VALUE_KIND_VERBATIM
            verbatim.verbatimText shouldBe "Pelex"
            verbatim.attributionsList.shouldBeEmpty()
            response.resolutionState.gapsList
                .single { it.valueId == verbatim.id }
                .kind shouldBe GapKind.GAP_KIND_G3_UNATTRIBUTED
        }

        "a quoted date is a string, not a date: the universal layer skips a literal" {
            // §2.4. `"12.5.2024"` in quotes asks for rows whose name contains those characters.
            // Grounded as a DATE it would become a half-open interval on a period column — a
            // different question, answered confidently, which is the worst of the two failures.
            val quoted = "zákazník \"12.5.2024\""
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .setLanguage("cs")
                    .setDetectedLanguage("cs")
                    .addTokens(VerbatimHero.token("zákazník", 0, 8, "zákazník", "NOUN", 0, "root"))
                    .addTokens(VerbatimHero.token("\"", 9, 10, "\"", "PUNCT", 1, "punct"))
                    .addTokens(VerbatimHero.token("12.5.2024", 10, 19, "12.5.2024", "NUM", 1, "nmod"))
                    .addTokens(VerbatimHero.token("\"", 19, 20, "\"", "PUNCT", 1, "punct"))
                    .addEntities(
                        NerEntity
                            .newBuilder()
                            .setText("12.5.2024")
                            .setLabel("DATE")
                            .setCharStart(10)
                            .setCharEnd(19)
                            .setNormalizedValue("2024-05-12")
                            .setSourceEngine("nametag3"),
                    ).build()
            val customer =
                EntityType
                    .newBuilder()
                    .setRef("er.entity.customer")
                    .addCategories("er.entity.customer")
                    .addAnchors("zákazník")
                    .setObjectKind("entity")
                    .setNameAttributeRef("er.entity.customer.name")
                    .build()

            val (_, response) =
                resolve(
                    registryOf(customer),
                    VerbatimHero.FakeFuzzy(mapOf("zákazník" to listOf(VerbatimHero.declared("er.entity.customer")))),
                    parse,
                    quoted,
                )

            val value = response.resolutionState.valuesList.single()
            value.kind shouldBe ValueKind.VALUE_KIND_VERBATIM
            value.verbatimText shouldBe "12.5.2024"
            value.hasGrounding().shouldBeFalse()
            response.resolution.bindingsList.none { it.hasUniversal() } shouldBe true
        }

        "span proposal proposes nothing inside a literal, whatever the source would have been" {
            // The invariant behind the hero, asserted directly and over every source at once:
            // the anchor index (a declared anchor word INSIDE quotes), proper nouns (b), NER (c)
            // and the n-gram floor (d) all have their own reason to reach for these tokens.
            val quoted = "zákazník \"dodací místo Pelex\""
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .setLanguage("cs")
                    .addTokens(VerbatimHero.token("zákazník", 0, 8, "zákazník", "NOUN", 0, "root"))
                    .addTokens(VerbatimHero.token("\"", 9, 10, "\"", "PUNCT", 1, "punct"))
                    .addTokens(VerbatimHero.token("dodací", 10, 16, "dodací", "ADJ", 4, "amod"))
                    .addTokens(VerbatimHero.token("místo", 17, 22, "místo", "NOUN", 1, "nmod"))
                    .addTokens(VerbatimHero.token("Pelex", 23, 28, "Pelex", "PROPN", 4, "flat"))
                    .addTokens(VerbatimHero.token("\"", 28, 29, "\"", "PUNCT", 1, "punct"))
                    .addEntities(
                        NerEntity
                            .newBuilder()
                            .setText("Pelex")
                            .setLabel("if")
                            .setCharStart(23)
                            .setCharEnd(28)
                            .setSourceEngine("nametag3"),
                    ).build()
            val types =
                listOf(
                    org.tatrman.resolver.model.ResolverEntityType(
                        ref = "er.entity.store",
                        categories = listOf("er.entity.store"),
                        anchors = listOf("dodací místo"),
                    ),
                    org.tatrman.resolver.model.ResolverEntityType(
                        ref = "er.entity.customer",
                        categories = listOf("er.entity.customer"),
                        anchors = listOf("zákazník"),
                    ),
                )
            val literals = Literals.of(quoted, parse)

            val proposed = SpanProposal.proposeDomainSpans(parse, types, literals)

            proposed.none { literals.overlaps(it.start, it.end) } shouldBe true
            // And the unquoted half is untouched — this is an exclusion, not a switch that turns
            // proposal off for the whole question.
            proposed.map { it.text } shouldContainExactly listOf("zákazník")
        }

        "with no parse at all, the literal is still found and still excluded" {
            // The degraded floor (§SpanProposal (d)): no dep parse, so every content n-gram is a
            // candidate — the loosest source there is, and the one a quoted string most needs to
            // be kept out of.
            val degraded = "zákazník \"Valmy Oil\" a Pelex"
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .setLanguage("de")
                    // The whitespace split IS the tokenisation on this path — the same one
                    // `QuoteScanner.tokens` falls back to, so the fixture cannot drift from it.
                    .addAllTokens(QuoteScanner.tokens(degraded, AnalyzeResponse.getDefaultInstance()))
                    .build()
            val types =
                listOf(
                    org.tatrman.resolver.model.ResolverEntityType(
                        ref = "er.entity.customer",
                        categories = listOf("er.entity.customer"),
                        anchors = listOf("zákazník"),
                    ),
                )
            val literals = Literals.of(degraded, parse)

            val proposed = SpanProposal.proposeDomainSpans(parse, types, literals)

            literals.spans
                .single()
                .literal.text shouldBe "Valmy Oil"
            proposed.none { literals.overlaps(it.start, it.end) } shouldBe true
            // `Pelex` outside the quotes is still a candidate: the floor still runs.
            proposed.any { it.text == "Pelex" } shouldBe true
        }
    })
