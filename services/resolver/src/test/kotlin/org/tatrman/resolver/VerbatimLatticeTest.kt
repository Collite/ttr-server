// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
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
            // §2.2: absent, not blank. The default predicate (contains, for a name) applies, and
            // `pred:` arrives in LP-P2.
            verbatim.hasPredicateRef().shouldBeFalse()

            // The other half, and the reason this runs through the pipeline: the matcher was never
            // asked about Pelex. Not "asked and refused" — never asked.
            // One BatchMatch, two slots for the same phrase — the gate's and the grounding
            // trigger annotation's (RV-P1.6.T6). What matters here is what is NOT in it.
            val queries = fuzzy.lastRequest!!.spansList.map { it.query }
            queries.none { it.contains("Pelex") } shouldBe true
            queries.distinct() shouldContainExactly listOf("dodací místa")
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
            // The archive-fed case today (⚑LPQ-5): the head is found, but the model has not said
            // which of its columns carries the name. Emitting the value with zero attributions is
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
