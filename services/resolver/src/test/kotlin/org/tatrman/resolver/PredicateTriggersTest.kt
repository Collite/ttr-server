// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.Provenance
import org.tatrman.fuzzy.v1.TokenHit
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.Literals
import org.tatrman.resolver.pipeline.PredicateTriggers
import org.tatrman.ttr.lexicon.LexiconValidator

/**
 * LP-P2b (contracts §3) — the retrieval window, on its own.
 *
 * `VerbatimLatticeTest` proves the hero carries `pred:starts_with` end to end. These pin the thing
 * that makes that affordable: the windows are derived from the LITERAL and are bounded by it, so a
 * question with no quotes costs nothing and a question with quotes costs a handful of slots on a
 * batch the core was making anyway.
 */
class PredicateTriggersTest :
    StringSpec({

        fun parseOf(vararg texts: String): AnalyzeResponse {
            val b = AnalyzeResponse.newBuilder().setLanguage("cs")
            var at = 0
            for (t in texts) {
                b.addTokens(VerbatimHero.token(t, at, at + t.length, t, "X", 0, "dep"))
                at += t.length + 1
            }
            return b.build()
        }

        "a question with no literal asks nothing at all" {
            val parse = parseOf("ukaž", "dodací", "místa")

            PredicateTriggers.windowsOf(Literals.NONE, parse).shouldBeEmpty()
            PredicateTriggers.windowsOf(Literals.of("ukaž dodací místa", parse), parse).shouldBeEmpty()
        }

        "the windows are the three tokens before the literal, as singles, pairs and the triple" {
            val text = "ukaž dodací místa začínající na \"Pelex\""
            val parse = VerbatimHero.parse()
            val literals = Literals.of(VerbatimHero.TEXT, parse)

            PredicateTriggers.windowsOf(literals, parse).map { it.text } shouldContainExactlyInAnyOrder
                listOf("místa", "začínající", "na", "místa začínající", "začínající na", "místa začínající na")
            // The bound is MAX_ANCHOR_DISTANCE tokens, and every contiguous stretch inside it up to
            // the widest form (three words since review-103 F1 — *s názvem přesně*), nothing else.
            text.split(" ").size shouldBe 6
            PredicateTriggers.MAX_FORM_TOKENS shouldBe 3
        }

        "nothing to the RIGHT of a literal is a window" {
            // Both languages put the predicate before the string it applies to. A form after one
            // is qualifying something else, and asking about it would invent a filter.
            val text = "\"Pelex\" obsahující"
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .setLanguage("cs")
                    .addTokens(VerbatimHero.token("\"", 0, 1, "\"", "PUNCT", 0, "punct"))
                    .addTokens(VerbatimHero.token("Pelex", 1, 6, "Pelex", "PROPN", 0, "root"))
                    .addTokens(VerbatimHero.token("\"", 6, 7, "\"", "PUNCT", 0, "punct"))
                    .addTokens(VerbatimHero.token("obsahující", 8, 18, "obsahující", "ADJ", 2, "amod"))
                    .build()

            PredicateTriggers.windowsOf(Literals.of(text, parse), parse).shouldBeEmpty()
        }

        "a window never reaches into another literal — that text is a string somebody quoted" {
            val text = "\"Valmy\" \"Pelex\""
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .setLanguage("cs")
                    .addTokens(VerbatimHero.token("\"", 0, 1, "\"", "PUNCT", 0, "punct"))
                    .addTokens(VerbatimHero.token("Valmy", 1, 6, "Valmy", "PROPN", 0, "root"))
                    .addTokens(VerbatimHero.token("\"", 6, 7, "\"", "PUNCT", 0, "punct"))
                    .addTokens(VerbatimHero.token("\"", 8, 9, "\"", "PUNCT", 0, "punct"))
                    .addTokens(VerbatimHero.token("Pelex", 9, 14, "Pelex", "PROPN", 0, "root"))
                    .addTokens(VerbatimHero.token("\"", 14, 15, "\"", "PUNCT", 0, "punct"))
                    .build()

            // Every token in the window before `"Pelex"` belongs to `"Valmy"`, delimiters included.
            PredicateTriggers.windowsOf(Literals.of(text, parse), parse).shouldBeEmpty()
        }

        "two literals sharing the word before them ask once" {
            val text = "obsahující \"A\" obsahující \"B\""
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .setLanguage("cs")
                    .addTokens(VerbatimHero.token("obsahující", 0, 10, "obsahující", "ADJ", 0, "root"))
                    .addTokens(VerbatimHero.token("\"", 11, 12, "\"", "PUNCT", 0, "punct"))
                    .addTokens(VerbatimHero.token("A", 12, 13, "A", "PROPN", 0, "obl"))
                    .addTokens(VerbatimHero.token("\"", 13, 14, "\"", "PUNCT", 0, "punct"))
                    .addTokens(VerbatimHero.token("obsahující", 15, 25, "obsahující", "ADJ", 0, "root"))
                    .addTokens(VerbatimHero.token("\"", 26, 27, "\"", "PUNCT", 0, "punct"))
                    .addTokens(VerbatimHero.token("B", 27, 28, "B", "PROPN", 0, "obl"))
                    .addTokens(VerbatimHero.token("\"", 28, 29, "\"", "PUNCT", 0, "punct"))
                    .build()

            val windows = PredicateTriggers.windowsOf(Literals.of(text, parse), parse)

            // One `obsahující` QUERY, not two: the answer would be the same, and a slot costs the
            // matcher a scored pass over its index…
            PredicateTriggers.queries(windows, 10).count { it.query == "obsahující" } shouldBe 1
            // …but two WINDOWS, one per literal, because each literal's tokens must receive the
            // answer (review-103 F13 — deduplicating the windows lost the second one's trigger).
            windows.filter { it.text == "obsahující" }.map { it.tokens } shouldContainExactlyInAnyOrder
                listOf(listOf(0), listOf(4))
            PredicateTriggers.slotCount(windows) shouldBe PredicateTriggers.queries(windows, 10).size
        }

        "F13 — the one answer reaches BOTH literals' windows" {
            val text = "obsahující \"A\" obsahující \"B\""
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .setLanguage("cs")
                    .addTokens(VerbatimHero.token("obsahující", 0, 10, "obsahující", "ADJ", 0, "root"))
                    .addTokens(VerbatimHero.token("\"", 11, 12, "\"", "PUNCT", 0, "punct"))
                    .addTokens(VerbatimHero.token("A", 12, 13, "A", "PROPN", 0, "obl"))
                    .addTokens(VerbatimHero.token("\"", 13, 14, "\"", "PUNCT", 0, "punct"))
                    .addTokens(VerbatimHero.token("obsahující", 15, 25, "obsahující", "ADJ", 0, "root"))
                    .addTokens(VerbatimHero.token("\"", 26, 27, "\"", "PUNCT", 0, "punct"))
                    .addTokens(VerbatimHero.token("B", 27, 28, "B", "PROPN", 0, "obl"))
                    .addTokens(VerbatimHero.token("\"", 28, 29, "\"", "PUNCT", 0, "punct"))
                    .build()
            val windows = PredicateTriggers.windowsOf(Literals.of(text, parse), parse)

            val triggers =
                PredicateTriggers.collect(
                    windows,
                    answer(windows, mapOf("obsahující" to VerbatimHero.predicate("pred:contains", "obsahující"))),
                    offset = 0,
                    thresholds = ResolverThresholds.LIVE,
                    parse = parse,
                )

            triggers.map { it.headToken to it.ref } shouldContainExactlyInAnyOrder
                listOf(0 to "pred:contains", 4 to "pred:contains")
        }

        "F1 — a TOKENS row wider than its window is a fragment; the same row as a whole form counts" {
            val parse = parseOf("s", "názvem", "přesně", "\"", "Valmy", "\"")
            val literals = Literals.of("s názvem přesně \"Valmy\"", parse)
            val windows = PredicateTriggers.windowsOf(literals, parse)
            val form = VerbatimHero.predicate("pred:equals", "s názvem přesně", method = "TOKENS")

            // The pre-fix slice answered the pair `s názvem` with the triple — and it bound.
            PredicateTriggers.coversWholeForm(windows.single { it.text == "s názvem" }, form) shouldBe false
            PredicateTriggers.coversWholeForm(windows.single { it.text == "názvem" }, form) shouldBe false
            PredicateTriggers.coversWholeForm(windows.single { it.text == "s názvem přesně" }, form) shouldBe true
        }

        "F1 — with v2 provenance, a same-width row must hit every word of the form" {
            val window = PredicateTriggers.Window(listOf(0, 1), "v textu")
            val half =
                VerbatimHero
                    .predicate("pred:contains", "v názvu", method = "TOKENS")
                    .toBuilder()
                    .setProvenance(
                        Provenance
                            .newBuilder()
                            .setMethod("TATRMAN_V2")
                            .addTokenHits(
                                TokenHit
                                    .newBuilder()
                                    .setQueryToken("v")
                                    .setCandidateToken("v")
                                    .setKind("exact"),
                            ),
                    ).build()

            PredicateTriggers.coversWholeForm(window, half) shouldBe false
        }

        "F1 — an EXACT row needs no further proof: the matcher compared the whole text" {
            val window = PredicateTriggers.Window(listOf(0), "obsahující")

            PredicateTriggers.coversWholeForm(window, VerbatimHero.predicate("pred:contains", "obsahující")) shouldBe
                true
        }

        "D2 — a negator right before the form turns it into its opposite, both ways" {
            val parse = parseOf("stores", "do", "not", "start", "with", "\"", "abl", "\"")
            val windows = PredicateTriggers.windowsOf(Literals.of("stores do not start with \"abl\"", parse), parse)

            val triggers =
                PredicateTriggers.collect(
                    windows,
                    answer(windows, mapOf("start with" to VerbatimHero.predicate("pred:starts_with", "start with"))),
                    offset = 0,
                    thresholds = ResolverThresholds.LIVE,
                    parse = parse,
                )

            triggers.map { it.ref }.distinct() shouldBe listOf("pred:not_starts_with")
            PredicateTriggers.negate("pred:not_contains") shouldBe "pred:contains"
            PredicateTriggers.negate("pred:equals") shouldBe "pred:not_equals"
        }

        "D2 — the negator belongs to the form it negates, so it carries the trigger too" {
            // §2.1 measures a head's distance past the literal's form (`VerbatimAttribution`), and
            // a free negator is part of that form: *stores never starting with "abl"* must not
            // leave *never* standing between the head and the unit the literal makes.
            val parse = parseOf("stores", "never", "starting", "with", "\"", "abl", "\"")
            val windows = PredicateTriggers.windowsOf(Literals.of("stores never starting with \"abl\"", parse), parse)
            val form = VerbatimHero.predicate("pred:starts_with", "starting with")

            val triggers =
                PredicateTriggers.collect(
                    windows,
                    answer(windows, mapOf("starting with" to form)),
                    offset = 0,
                    thresholds = ResolverThresholds.LIVE,
                    parse = parse,
                )

            triggers.map { it.headToken to it.ref } shouldContainExactlyInAnyOrder
                listOf(1 to "pred:not_starts_with", 2 to "pred:not_starts_with", 3 to "pred:not_starts_with")
        }

        "D2 — `doesn't` split by the tokenizer (`doesn` `'` `t`) negates too" {
            val parse = parseOf("name", "doesn", "'", "t", "contain", "\"", "abl", "\"")
            val windows = PredicateTriggers.windowsOf(Literals.of("name doesn ' t contain \"abl\"", parse), parse)

            val triggers =
                PredicateTriggers.collect(
                    windows,
                    answer(windows, mapOf("contain" to VerbatimHero.predicate("pred:contains", "contain"))),
                    offset = 0,
                    thresholds = ResolverThresholds.LIVE,
                    parse = parse,
                )

            triggers.map { it.ref }.distinct() shouldBe listOf("pred:not_contains")
        }

        "the queries are class-scoped to the producer's own closed `pred:` set" {
            val windows =
                PredicateTriggers.windowsOf(
                    Literals.of(VerbatimHero.TEXT, VerbatimHero.parse()),
                    VerbatimHero.parse(),
                )

            // The producer's closed set, plus the three negations this service composes (D1) —
            // named here too so a slice that lists them is askable before the pin moves.
            val expected =
                (LexiconValidator.PREDICATE_KINDS + setOf("not_starts_with", "not_ends_with", "not_equals"))
                    .map { "pred:$it" }
            PredicateTriggers.queries(windows, 10).forEach { q ->
                q.categoriesList shouldContainExactlyInAnyOrder expected
                q.limit shouldBe 10
            }
            // Not a copy of the list: a new predicate becomes askable the moment the artifact that
            // defines it is on the classpath.
            PredicateTriggers.CATEGORIES.toSet() shouldBe expected.toSet()
        }
    }) {
    companion object {
        /** A batch answering each window TEXT with its row, in [PredicateTriggers.queries]' order. */
        private fun answer(
            windows: List<PredicateTriggers.Window>,
            rows: Map<String, FuzzyMatch>,
        ): BatchMatchResponse {
            val builder = BatchMatchResponse.newBuilder()
            for (q in PredicateTriggers.queries(windows, 10)) {
                val result = FuzzyMatchResponse.newBuilder()
                rows[q.query]?.let { result.addMatches(it) }
                builder.addResults(result)
            }
            return builder.build()
        }
    }
}
