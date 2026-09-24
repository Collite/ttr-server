// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.tatrman.nlp.v1.AnalyzeResponse
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

        "the windows are the three tokens before the literal, as singles and as pairs" {
            val text = "ukaž dodací místa začínající na \"Pelex\""
            val parse = VerbatimHero.parse()
            val literals = Literals.of(VerbatimHero.TEXT, parse)

            PredicateTriggers.windowsOf(literals, parse).map { it.text } shouldContainExactlyInAnyOrder
                listOf("místa", "začínající", "na", "místa začínající", "začínající na")
            // Six slots would be the whole question; five are the window. The bound is
            // MAX_ANCHOR_DISTANCE singles plus the pairs inside it, and nothing else.
            text.split(" ").size shouldBe 6
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
            val text = "\"Shell\" \"Pelex\""
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .setLanguage("cs")
                    .addTokens(VerbatimHero.token("\"", 0, 1, "\"", "PUNCT", 0, "punct"))
                    .addTokens(VerbatimHero.token("Shell", 1, 6, "Shell", "PROPN", 0, "root"))
                    .addTokens(VerbatimHero.token("\"", 6, 7, "\"", "PUNCT", 0, "punct"))
                    .addTokens(VerbatimHero.token("\"", 8, 9, "\"", "PUNCT", 0, "punct"))
                    .addTokens(VerbatimHero.token("Pelex", 9, 14, "Pelex", "PROPN", 0, "root"))
                    .addTokens(VerbatimHero.token("\"", 14, 15, "\"", "PUNCT", 0, "punct"))
                    .build()

            // Every token in the window before `"Pelex"` belongs to `"Shell"`, delimiters included.
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

            // One `obsahující` query, not two: the answer would be the same, and a slot costs the
            // matcher a scored pass over its index.
            windows.count { it.text == "obsahující" } shouldBe 1
        }

        "the queries are class-scoped to the producer's own closed `pred:` set" {
            val windows =
                PredicateTriggers.windowsOf(
                    Literals.of(VerbatimHero.TEXT, VerbatimHero.parse()),
                    VerbatimHero.parse(),
                )

            PredicateTriggers.queries(windows, 10).forEach { q ->
                q.categoriesList shouldContainExactlyInAnyOrder
                    LexiconValidator.PREDICATE_KINDS.map { "pred:$it" }
                q.limit shouldBe 10
            }
            // Not a copy of the list: a sixth predicate becomes askable the moment the artifact
            // that defines it is on the classpath.
            PredicateTriggers.CATEGORIES.size shouldBe LexiconValidator.PREDICATE_KINDS.size
        }
    })
