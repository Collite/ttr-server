// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * RV-P1.4 T4 — the authored match method is honoured, and TOKENS carries the RV-32 margin.
 *
 * Written before the dispatcher (red first). The rule under test is that the method is the
 * **author's precision statement**: EXACT means the word as written, TYPOS(n) means n edits and no
 * more, TOKENS means the existing token algorithm plus a statement of whether the hit was unique
 * enough to bind unattended.
 */
class MethodDispatcherTest :
    StringSpec({

        fun result(
            id: String,
            value: String,
            score: Double,
            method: String?,
            targetRef: String? = "md.$id",
            source: SourceTag = SourceTag.DECLARED,
        ) = FuzzyMatchResult(
            candidateId = id,
            candidate = value,
            score = score,
            category = "c",
            source = source,
            targetRef = targetRef,
            matchMethod = method,
        )

        val dispatcher = MethodDispatcher()

        // ---- EXACT ---------------------------------------------------------------------------

        "EXACT admits the authored word and rejects a near miss" {
            val hits =
                dispatcher.dispatch(
                    "zákazník",
                    listOf(
                        result("a", "zákazník", 1.0, "EXACT"),
                        result("b", "zákazníci", 0.9, "EXACT"),
                    ),
                )

            hits.map { it.candidateId } shouldBe listOf("a")
        }

        "EXACT ignores case and surrounding whitespace, because the authored form does" {
            // TermNormalizer stores `termNormalized`, so casing and spacing were already decided at
            // compile time; a query differing only there is the same authored word, not a typo.
            val hits = dispatcher.dispatch("  Čistý   Obrat ", listOf(result("a", "čistý obrat", 1.0, "EXACT")))

            hits.size shouldBe 1
        }

        "EXACT does NOT admit a diacritic-stripped query — that is a TYPOS decision the author declined" {
            // The engine's fold erases diacritics on purpose (so `zakaznik` finds `zákazník`), and
            // dispatching on the folded form would silently hand every EXACT term that tolerance.
            val hits = dispatcher.dispatch("vyroba", listOf(result("a", "výroba", 0.95, "EXACT")))

            hits.shouldBeEmpty()
        }

        // ---- TYPOS(n) ------------------------------------------------------------------------

        "TYPOS(1) admits a one-edit query and rejects a two-edit one" {
            val hits =
                dispatcher.dispatch(
                    "zákaznik",
                    listOf(
                        result("a", "zákazník", 0.95, "TYPOS(1)"),
                        result("b", "záhradník", 0.7, "TYPOS(1)"),
                    ),
                )

            hits.map { it.candidateId } shouldBe listOf("a")
        }

        "TYPOS(1) admits the diacritic-stripped query EXACT refused — one missing accent is one edit" {
            val hits = dispatcher.dispatch("vyroba", listOf(result("a", "výroba", 0.95, "TYPOS(1)")))

            hits.size shouldBe 1
        }

        "the cap is the authored n, not the engine's own tolerance" {
            val two = listOf(result("a", "zákazník", 0.9, "TYPOS(2)"))
            val one = listOf(result("a", "zákazník", 0.9, "TYPOS(1)"))

            dispatcher.dispatch("zakaznik", two).size shouldBe 1 // 2 accents dropped = 2 edits
            dispatcher.dispatch("zakaznik", one).shouldBeEmpty()
        }

        // ---- TOKENS + the RV-32 uniqueness margin ----------------------------------------------

        "a TOKENS hit with no competing target carries a margin and is auto-bindable" {
            val hits = dispatcher.dispatch("čistý obrat", listOf(result("a", "čistý obrat", 0.98, "TOKENS")))

            hits.single().uniquenessMargin!! shouldBeGreaterThan MethodDispatcher.DEFAULT_UNIQUENESS_FLOOR
            hits.single().autoBindable shouldBe true
        }

        "a TOKENS hit that clearly beats the runner-up is auto-bindable, and the margin is the gap" {
            val hits =
                dispatcher.dispatch(
                    "čistý obrat",
                    listOf(
                        result("a", "čistý obrat", 0.98, "TOKENS", targetRef = "md.net"),
                        result("b", "hrubý obrat", 0.60, "TOKENS", targetRef = "md.gross"),
                    ),
                )

            val winner = hits.single { it.candidateId == "a" }
            winner.uniquenessMargin!! shouldBe (0.98 - 0.60 plusOrMinus 1e-9)
            winner.autoBindable shouldBe true
        }

        "a non-discriminative TOKENS hit is flagged and never auto-bindable" {
            // The case RV-32 exists for: "obrat" is a token of two different measures, so the token
            // algorithm scores both alike. Returning them both is right; binding either is not.
            val hits =
                dispatcher.dispatch(
                    "obrat",
                    listOf(
                        result("a", "čistý obrat", 0.71, "TOKENS", targetRef = "md.net"),
                        result("b", "hrubý obrat", 0.70, "TOKENS", targetRef = "md.gross"),
                    ),
                )

            hits.map { it.candidateId } shouldContainExactlyInAnyOrder listOf("a", "b")
            hits.forEach { it.autoBindable shouldBe false }
            hits.single { it.candidateId == "a" }.uniquenessMargin!! shouldBeLessThan
                MethodDispatcher.DEFAULT_UNIQUENESS_FLOOR
        }

        "the losing target of an unambiguous pair is itself not auto-bindable" {
            val hits =
                dispatcher.dispatch(
                    "čistý obrat",
                    listOf(
                        result("a", "čistý obrat", 0.98, "TOKENS", targetRef = "md.net"),
                        result("b", "hrubý obrat", 0.60, "TOKENS", targetRef = "md.gross"),
                    ),
                )

            val loser = hits.single { it.candidateId == "b" }
            loser.autoBindable shouldBe false
            loser.uniquenessMargin!! shouldBeLessThan 0.0
        }

        "two aliases of the SAME target are not competition" {
            // Identity is the target ref, not the string: one measure spelled two ways is one
            // binding, and flagging it ambiguous would make every well-aliased term unbindable.
            val hits =
                dispatcher.dispatch(
                    "obrat",
                    listOf(
                        result("a", "obrat", 0.95, "TOKENS", targetRef = "md.net"),
                        result("b", "čistý obrat", 0.94, "TOKENS", targetRef = "md.net"),
                    ),
                )

            hits.forEach { it.autoBindable shouldBe true }
        }

        "the margin ignores candidates from other layers — the matcher does not arbitrate across them" {
            // A member value scoring alongside a declared term is the 0..n-bindings case (RV-2); the
            // resolver's evidence gate decides it. Letting it depress the margin would move that
            // decision into the matcher, which never picks a winner across layers (T2).
            val hits =
                dispatcher.dispatch(
                    "obrat",
                    listOf(
                        result("a", "obrat", 0.95, "TOKENS", targetRef = "md.net"),
                        result("m", "Obrat", 0.94, null, targetRef = null, source = SourceTag.MEMBER),
                    ),
                )

            hits.single { it.candidateId == "a" }.autoBindable shouldBe true
            hits.single { it.candidateId == "m" }.autoBindable.shouldBeNull()
        }

        // ---- member rows carry a method since MV (review-104 F4 / F9) ------------------------

        "a member TOKENS row takes no part in the uniqueness margin — two stores named alike stay offerable" {
            // A margin verdict reads auto_bindable = false, which the resolver classes WEAK: never
            // bound, never offered. Two near-identical data values are the resolver's tie band to
            // settle as a question, not the matcher's to veto.
            val hits =
                dispatcher.dispatch(
                    "nashville",
                    listOf(
                        result("Nashville Central", "Nashville Central", 0.91, "TOKENS", null, SourceTag.MEMBER),
                        result("Nashville East", "Nashville East", 0.90, "TOKENS", null, SourceTag.MEMBER),
                    ),
                )

            hits.map { it.autoBindable } shouldBe listOf(null, null)
            hits.map { it.uniquenessMargin } shouldBe listOf(null, null)
        }

        "...and a member row is no rival to a declared TOKENS term either" {
            val hits =
                dispatcher.dispatch(
                    "obrat",
                    listOf(
                        result("a", "obrat", 0.95, "TOKENS", targetRef = "md.net"),
                        result("m", "obrat", 0.95, "TOKENS", targetRef = null, source = SourceTag.MEMBER),
                    ),
                )

            hits.single { it.candidateId == "a" }.autoBindable shouldBe true
            hits.single { it.candidateId == "m" }.autoBindable.shouldBeNull()
        }

        "method_override never reaches a member row — an EXACT state code stays EXACT under a TOKENS rung" {
            val stateCode = result("TN", "TN", 0.9, "EXACT", targetRef = null, source = SourceTag.MEMBER)

            dispatcher.dispatch("tx", listOf(stateCode), override = MatchMethod.Tokens) shouldBe emptyList()
        }

        "...and a TYPOS(1) member keeps its slack under an EXACT rung" {
            val name = result("Taxas", "Taxas", 0.8, "TYPOS(1)", targetRef = null, source = SourceTag.MEMBER)

            dispatcher.dispatch("Texas", listOf(name), override = MatchMethod.Exact).map { it.candidateId } shouldBe
                listOf("Taxas")
        }

        "the override still replaces a DECLARED term's method" {
            val term = result("a", "obrat", 0.9, "EXACT")

            dispatcher.dispatch("obraty", listOf(term)) shouldBe emptyList()
            dispatcher.dispatch("obraty", listOf(term), override = MatchMethod.Tokens).map { it.candidateId } shouldBe
                listOf("a")
        }

        // ---- unauthored rows are untouched -------------------------------------------------

        "candidates with no authored method pass through unchanged" {
            // T7's guarantee: with no artifact loaded, dispatch is the identity function.
            val input =
                listOf(
                    result("m1", "Praha", 0.9, null, targetRef = null, source = SourceTag.MEMBER),
                    result("m2", "Brno", 0.4, null, targetRef = null, source = SourceTag.MEMBER),
                )

            dispatcher.dispatch("praha", input) shouldBe input
        }

        "an unrecognised method string is treated as unauthored, not as a rejection" {
            // An archive from a newer toolchain must not empty the response.
            val hits = dispatcher.dispatch("cokoliv", listOf(result("a", "obrat", 0.3, "SEMANTIC(0.8)")))

            hits.size shouldBe 1
            hits.single().uniquenessMargin.shouldBeNull()
            hits.single().autoBindable.shouldBeNull()
        }

        "dispatch never rewrites the engine's score" {
            val hits =
                dispatcher.dispatch(
                    "zákazník",
                    listOf(result("a", "zákazník", 0.83, "EXACT")),
                )

            hits.single().score shouldBe 0.83
        }

        // ---- the parser --------------------------------------------------------------------

        "the method parser accepts the three authored forms and refuses the rest" {
            MatchMethod.parse("EXACT") shouldBe MatchMethod.Exact
            MatchMethod.parse("TOKENS") shouldBe MatchMethod.Tokens
            MatchMethod.parse("TYPOS(2)") shouldBe MatchMethod.Typos(2)
            MatchMethod.parse("typos(2)") shouldBe MatchMethod.Typos(2)
            MatchMethod.parse(null).shouldBeNull()
            MatchMethod.parse("").shouldBeNull()
            MatchMethod.parse("TYPOS").shouldBeNull()
            MatchMethod.parse("TYPOS()").shouldBeNull()
        }
    })
