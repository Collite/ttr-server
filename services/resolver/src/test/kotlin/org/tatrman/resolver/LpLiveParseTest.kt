// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.resolver.v1.EntityType
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.ValueKind

/**
 * LP — the first questions a REAL dependency parse put through the literal path.
 *
 * Until 2026-09-25 the demo estate analysed every question on the degrade floor (no lemma, no POS,
 * no dep tree), so §2.1's parse rules and span proposal's phrase rules had never met a quoted
 * literal. The three parses below are quoted from the live wire of that day, token for token —
 * offsets, lemmas, tags and heads as the analyser returned them, including the parses a linguist
 * would call wrong, because those are the ones the rules have to survive.
 */
class LpLiveParseTest :
    StringSpec({

        fun store(anchor: String): EntityType =
            EntityType
                .newBuilder()
                .setRef("er.entity.store")
                .addCategories("er.entity.store")
                .addAnchors(anchor)
                .setObjectKind("entity")
                .setNameAttributeRef("er.entity.store.store_name")
                .build()

        /** *Ukaž prodejny začínající na „abl“* — the participle is an `amod` of the head noun. */
        fun czechParse(): AnalyzeResponse =
            AnalyzeResponse
                .newBuilder()
                .setLanguage("cs")
                .setDetectedLanguage("cs")
                .addTokens(VerbatimHero.token("Ukaž", 0, 4, "ukázat", "VERB", 0, "root"))
                .addTokens(VerbatimHero.token("prodejny", 5, 13, "prodejna", "NOUN", 1, "obj"))
                .addTokens(VerbatimHero.token("začínající", 14, 24, "začínající", "ADJ", 2, "amod"))
                .addTokens(VerbatimHero.token("na", 25, 27, "na", "ADP", 6, "case"))
                .addTokens(VerbatimHero.token("„", 28, 29, "\"", "PUNCT", 6, "punct"))
                .addTokens(VerbatimHero.token("abl", 29, 32, "abl", "NOUN", 3, "obl:arg"))
                .addTokens(VerbatimHero.token("“", 32, 33, "\"", "PUNCT", 6, "punct"))
                .build()

        val czech = "Ukaž prodejny začínající na „abl“"

        "a participle that governs the literal is not folded into the head's phrase" {
            // Live: the anchored phrase came out as *prodejny začínající* — the head noun plus the
            // `amod` participle that carries the restriction. That phrase names nothing, so the
            // mention bound nothing (G1), and the literal lost its head (G3) with it.
            val fuzzy =
                VerbatimHero.FakeFuzzy(
                    mapOf(
                        "prodejny" to listOf(VerbatimHero.declared("er.entity.store")),
                        "začínající na" to listOf(VerbatimHero.predicate("pred:starts_with", "začínající na")),
                    ),
                )
            val (_, response) =
                VerbatimHero.resolve(VerbatimHero.registryOf(store("prodejna")), fuzzy, czechParse(), czech)
            val state = response.resolutionState

            state.mentionsList.map { it.span.text } shouldContain "prodejny"
            state.mentionsList.map { it.span.text } shouldNotContain "prodejny začínající"
            val verbatim = state.valuesList.single { it.kind == ValueKind.VALUE_KIND_VERBATIM }
            verbatim.attributionsList.map { it.attributeRef } shouldContainExactly listOf("er.entity.store.store_name")
            verbatim.predicateRef shouldBe "pred:starts_with"
            state.gapsList.map { it.kind } shouldNotContain GapKind.GAP_KIND_G1_UNBOUND
        }

        "…and a real adjective beside it still is: *kamenné prodejny* stays one phrase" {
            // Same day, same question with an adjective, and a different engine answered: every
            // content word tagged NOUN, the adjective and the participle BOTH `amod` of the head.
            // Only the one the literal hangs from is the literal's.
            val text = "Ukaž kamenné prodejny začínající na „abl“"
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .setLanguage("cs")
                    .setDetectedLanguage("cs")
                    .addTokens(VerbatimHero.token("Ukaž", 0, 4, "Ukaž", "NOUN", 0, "root"))
                    .addTokens(VerbatimHero.token("kamenné", 5, 12, "kamenné", "NOUN", 3, "amod"))
                    .addTokens(VerbatimHero.token("prodejny", 13, 21, "prodejna", "NOUN", 1, "obj"))
                    .addTokens(VerbatimHero.token("začínající", 22, 32, "začínající", "NOUN", 3, "amod"))
                    .addTokens(VerbatimHero.token("na", 33, 35, "na", "ADP", 7, "case"))
                    .addTokens(VerbatimHero.token("„", 36, 37, "„", "PUNCT", 7, "punct"))
                    .addTokens(VerbatimHero.token("abl", 37, 40, "abl", "X", 4, "obl:arg"))
                    .addTokens(VerbatimHero.token("“", 40, 41, "“", "PUNCT", 7, "punct"))
                    .build()
            val fuzzy =
                VerbatimHero.FakeFuzzy(
                    mapOf(
                        "kamenné prodejny" to listOf(VerbatimHero.declared("er.entity.store")),
                        "začínající na" to listOf(VerbatimHero.predicate("pred:starts_with", "začínající na")),
                    ),
                )
            val (_, response) = VerbatimHero.resolve(VerbatimHero.registryOf(store("prodejna")), fuzzy, parse, text)
            val state = response.resolutionState

            state.mentionsList.map { it.span.text } shouldContain "kamenné prodejny"
            // This analyser tags the participle NOUN, so once it is out of the phrase the mention
            // layer proposes it on its own — and it is a word of the form, not a thing to ask about.
            state.mentionsList.map { it.span.text } shouldNotContain "začínající"
            state.valuesList
                .single { it.kind == ValueKind.VALUE_KIND_VERBATIM }
                .attributionsList
                .map { it.attributeRef } shouldContainExactly listOf("er.entity.store.store_name")
        }

        "a leftover mention obeys the same rule: the participle is not part of what was talked about" {
            // An estate with no anchor for *prodejna*: the mention layer proposes the leftover
            // phrase, and it must be the noun, not the noun plus the literal's restriction.
            val other =
                EntityType
                    .newBuilder()
                    .setRef("er.entity.customer")
                    .addCategories("er.entity.customer")
                    .addAnchors("zákazník")
                    .setObjectKind("entity")
                    .build()
            val (_, response) =
                VerbatimHero.resolve(
                    VerbatimHero.registryOf(other),
                    VerbatimHero.FakeFuzzy(emptyMap()),
                    czechParse(),
                    czech,
                )

            val spans = response.resolutionState.mentionsList.map { it.span.text }
            spans shouldContain "prodejny"
            spans shouldNotContain "prodejny začínající"
        }

        "the trigger form travels with its literal: a negated form does not push the head out of reach" {
            // Live: the parser attached *starting* to *Show* (ccomp), so the literal's dep chain
            // never passes *stores*; and edge to edge from the delimiter *stores* sat FOUR tokens
            // away, one past §2.1's bound — the three words of *not starting with* between them.
            // The same question without *not* attributed. A form and its literal are one unit.
            val text = "Show stores not starting with «abl»"
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .setLanguage("en")
                    .setDetectedLanguage("en")
                    .addTokens(VerbatimHero.token("Show", 0, 4, "show", "VERB", 0, "root"))
                    .addTokens(VerbatimHero.token("stores", 5, 11, "store", "NOUN", 1, "obj"))
                    .addTokens(VerbatimHero.token("not", 12, 15, "not", "PART", 4, "advmod"))
                    .addTokens(VerbatimHero.token("starting", 16, 24, "start", "VERB", 1, "ccomp"))
                    .addTokens(VerbatimHero.token("with", 25, 29, "with", "ADP", 7, "case"))
                    .addTokens(VerbatimHero.token("«", 30, 31, "''", "PUNCT", 7, "punct"))
                    .addTokens(VerbatimHero.token("abl", 31, 34, "abl", "NOUN", 4, "obl"))
                    .addTokens(VerbatimHero.token("»", 34, 35, "''", "PUNCT", 7, "punct"))
                    .build()
            val fuzzy =
                VerbatimHero.FakeFuzzy(
                    mapOf(
                        "stores" to listOf(VerbatimHero.declared("er.entity.store")),
                        "not starting with" to
                            listOf(VerbatimHero.predicate("pred:not_starts_with", "not starting with")),
                    ),
                )
            val (_, response) = VerbatimHero.resolve(VerbatimHero.registryOf(store("store")), fuzzy, parse, text)
            val state = response.resolutionState

            val verbatim = state.valuesList.single { it.kind == ValueKind.VALUE_KIND_VERBATIM }
            verbatim.attributionsList.map { it.attributeRef } shouldContainExactly listOf("er.entity.store.store_name")
            verbatim.predicateRef shouldBe "pred:not_starts_with"
            state.gapsList.map { it.kind } shouldNotContain GapKind.GAP_KIND_G3_UNATTRIBUTED
        }
    })
