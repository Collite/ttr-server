// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/**
 * RV-P3.0 T4 (RV-44) — the dispatcher scores rows that carry a **declared matching profile**.
 *
 * Table-driven off the contracts §2 addendum, one case per clause it states. The clause each case
 * enforces is named in the test name, because the addendum is the contract and this file is only
 * its enforcement — a case that cannot cite a clause does not belong here.
 *
 * The hero the whole workstream exists for is *"zakaznik finds zákazník"*: today that works only by
 * the accident that a dropped accent costs exactly one edit, and refuses outright under `EXACT`.
 * With `{ norm: folded, exact: 0.90 }` it is an authored fact with the author's own number on it.
 */
class ProfileScoringTest :
    StringSpec({

        /** A declared row exactly as `LexiconArchiveSource` builds one, via the real Candidate. */
        fun row(
            value: String,
            profile: MatchProfile?,
            method: String? = null,
            engineScore: Double = 0.42,
            id: String = "lex:$value",
            targetRef: String = "er.Customer",
        ): FuzzyMatchResult {
            val candidate =
                Candidate.vocabulary(
                    id = id,
                    value = value,
                    targetRef = targetRef,
                    source = SourceTag.DECLARED,
                    matchMethod = method,
                    matchProfile = profile,
                )
            return FuzzyMatchResult(
                candidateId = candidate.id,
                candidate = candidate.value,
                score = engineScore,
                category = targetRef,
                source = candidate.source,
                targetRef = candidate.targetRef,
                matchMethod = candidate.matchMethod,
                authoredMethod = candidate.authoredMethod,
                canonicalCandidate = candidate.canonicalValue,
                matchProfile = candidate.matchProfile,
                lemmaCandidate = candidate.lemmaValue,
            )
        }

        val canonicalExact = NormRule(Norm.CANONICAL, exact = 1.00)
        val canonicalTypos = NormRule(Norm.CANONICAL, exact = 1.00, typos = TyposRule(1, 0.05))
        val folded = NormRule(Norm.FOLDED, exact = 0.90)
        val lemma = NormRule(Norm.LEMMA, exact = 0.80)

        val dispatcher = MethodDispatcher()

        fun dispatch(
            query: String,
            vararg rows: FuzzyMatchResult,
        ) = dispatcher.dispatch(query, rows.toList())

        // ---- (a) canonical `exact` fires on the AUTHORED form ----------------------------------

        "canonical/exact fires at its declared score, on the authored form" {
            val hit =
                dispatch("Zákazník", row("zákazník", MatchProfile(listOf(canonicalExact, folded))))
                    .single()

            hit.score shouldBe 1.00
            hit.provenance.norm shouldBe "canonical"
            hit.provenance.algorithm shouldBe "exact"
            hit.provenance.distance.shouldBeNull()
        }

        "`vyroba` is NOT a canonical hit for `výroba` — the P1.4 T4 ruling holds per norm" {
            // Diacritics are preserved on the canonical stratum. With canonical alone declared,
            // the row simply does not match, and no rule quietly folds it in.
            dispatch("vyroba", row("výroba", MatchProfile(listOf(canonicalExact)))).shouldBeEmpty()
        }

        "the engine's own score is left behind, and stays reachable as rawScore" {
            val hit =
                dispatch("zákazník", row("zákazník", MatchProfile(listOf(canonicalExact)), engineScore = 0.42))
                    .single()

            hit.score shouldBe 1.00
            hit.provenance.rawScore shouldBe 0.42
        }

        // ---- (b) `typos` fires at `exact − d·penalty`, capped at `distance` --------------------

        "typos fires at exact − d·penalty" {
            val hit = dispatch("zákazníx", row("zákazník", MatchProfile(listOf(canonicalTypos)))).single()

            hit.score shouldBe (0.95 plusOrMinus 1e-9)
            hit.provenance.algorithm shouldBe "typos"
            hit.provenance.distance shouldBe 1
        }

        "typos is capped at the declared distance — two edits under TYPOS(1) is not a match" {
            dispatch("zákazníxy", row("zákazník", MatchProfile(listOf(canonicalTypos)))).shouldBeEmpty()
        }

        "a wider budget costs proportionally more score" {
            val wide = NormRule(Norm.CANONICAL, exact = 1.00, typos = TyposRule(3, 0.05))
            dispatch("zákaznxxx", row("zákazník", MatchProfile(listOf(wide)))).single().score shouldBe
                (0.85 plusOrMinus 1e-9)
        }

        "a budget that outruns its anchor does not fire — a score at or below zero is a broken rule" {
            // `RG-LEX-017` rejects this pair at authoring time; the engine still has to hold the
            // line for an artifact built before that check existed. Reporting -0.10 would sort the
            // row below candidates that matched nothing at all, and say nothing about why.
            val exhausted = NormRule(Norm.CANONICAL, exact = 0.20, typos = TyposRule(4, 0.10))
            dispatch("zákaznxxx", row("zákazník", MatchProfile(listOf(exhausted)))).shouldBeEmpty()
        }

        "…and the edits it CAN still afford fire normally" {
            // The guard drops firings, never the rule: one edit off 0.20 at 0.10 is still 0.10.
            val exhausted = NormRule(Norm.CANONICAL, exact = 0.20, typos = TyposRule(4, 0.10))
            dispatch("zákazníx", row("zákazník", MatchProfile(listOf(exhausted)))).single().score shouldBe
                (0.10 plusOrMinus 1e-9)
        }

        // ---- (c) `folded` equality fires at ITS declared score ---------------------------------

        "the hero: `zakaznik` finds `zákazník` as an AUTHORED folded fact, at the folded score" {
            val hit = dispatch("zakaznik", row("zákazník", MatchProfile(listOf(canonicalExact, folded)))).single()

            hit.score shouldBe 0.90
            hit.provenance.norm shouldBe "folded"
            hit.provenance.algorithm shouldBe "exact"
        }

        "without a folded rule the same query does not match — the stratum is opt-in" {
            dispatch("zakaznik", row("zákazník", MatchProfile(listOf(canonicalExact)))).shouldBeEmpty()
        }

        // ---- (d) `lemma` via the existing lemmatiser path ---------------------------------------

        "the lemma norm compares the lemmatiser's output, which with none installed is the fold" {
            // `lemmaCandidate` is `Candidate.lemmaValue` — the repository's lemmas, folded. With no
            // lemmatiser it collapses onto the folded surface, exactly as the token path does, so
            // a lemma rule then behaves as a folded one at the lemma's own (lower) score.
            val hit = dispatch("zakaznik", row("zákazník", MatchProfile(listOf(lemma)))).single()

            hit.score shouldBe 0.80
            hit.provenance.norm shouldBe "lemma"
        }

        // ---- (e) combination = max; order is irrelevant ------------------------------------------

        "combination across a profile is MAX over firings" {
            // `zákaznik` (one edit) fires canonical/typos at 0.95 AND folded/exact at 0.90.
            // Max, not first-match-wins, not a sum.
            val hit =
                dispatch("zákaznik", row("zákazník", MatchProfile(listOf(canonicalTypos, folded, lemma)))).single()

            hit.score shouldBe (0.95 plusOrMinus 1e-9)
            hit.provenance.algorithm shouldBe "typos"
        }

        "a mis-ordered `match:` list gives identical results — which is WHY the rule is max" {
            val ordered = MatchProfile(listOf(canonicalTypos, folded, lemma))
            val shuffled = MatchProfile(listOf(lemma, folded, canonicalTypos))

            val a = dispatch("zákaznik", row("zákazník", ordered)).single()
            val b = dispatch("zákaznik", row("zákazník", shuffled)).single()

            a.score shouldBe b.score
            a.provenance.norm shouldBe b.provenance.norm
            a.provenance.algorithm shouldBe b.provenance.algorithm
        }

        // ---- (f) ⚑M-4 the short-term guard --------------------------------------------------

        "⚑M-4 — a ≤3-char authored term never typos-fires, whatever distance it declared" {
            val wide = NormRule(Norm.CANONICAL, exact = 1.00, typos = TyposRule(3, 0.05))

            dispatch("dx", row("DC", MatchProfile(listOf(wide)))).shouldBeEmpty()
            // …and it still matches itself. The guard suppresses the fuzz, not the term.
            dispatch("dc", row("DC", MatchProfile(listOf(wide)))).single().score shouldBe 1.00
        }

        "⚑M-4 — the guard reaches `method:` sugar rows from a PRE-profile archive too" {
            // One guard, decided in one place, whatever built the artifact: a row with a method and
            // no profile goes down the dispatcher's own gate, and that gate applies it as well.
            dispatch("dx", row("DC", profile = null, method = "TYPOS(1)")).shouldBeEmpty()
            dispatch("dc", row("DC", profile = null, method = "TYPOS(1)")).single().candidateId shouldBe "lex:DC"
        }

        "a four-character term is not short — the boundary is where the addendum puts it" {
            dispatch("rocx", row("roce", MatchProfile(listOf(canonicalTypos)))).single().score shouldBe
                (0.95 plusOrMinus 1e-9)
        }

        // ---- (g) ⚑M-2 a row WITHOUT a profile is untouched ---------------------------------------

        "⚑M-2 — a member row keeps the engine score, byte-identical, profiles or not" {
            val member =
                FuzzyMatchResult(
                    candidateId = "pk-1",
                    candidate = "Kaufland",
                    score = 0.667,
                    category = "er.Customer",
                    source = SourceTag.MEMBER,
                )
            val declared = row("zákazník", MatchProfile(listOf(canonicalExact)))

            val hits = dispatch("zákazník", declared, member)

            // The member row survives untouched — same score, no provenance triple, same instance
            // shape as the pre-RV-44 service produced.
            hits.single { it.candidateId == "pk-1" } shouldBe member
        }

        "a declared row with NEITHER a profile nor a method is untouched as well" {
            val untouched = row("zákazník", profile = null, method = null, engineScore = 0.51)
            dispatch("zákazník", untouched).single() shouldBe untouched
        }

        // ---- (h) ⚑M-5 the floor knob -------------------------------------------------------------

        "⚑M-5 — the floor drops below-floor candidates when set" {
            val floored = MethodDispatcher(profileScorer = ProfileScorer(minInClassScore = 0.85))

            floored
                .dispatch("zakaznik", listOf(row("zákazník", MatchProfile(listOf(canonicalExact, folded)))))
                .single()
                .score shouldBe 0.90
            floored.dispatch("zakaznik", listOf(row("zákazník", MatchProfile(listOf(lemma))))).shouldBeEmpty()
        }

        "⚑M-5 — default 0 means off: the same row survives" {
            dispatch("zakaznik", row("zákazník", MatchProfile(listOf(lemma)))).single().score shouldBe 0.80
        }

        // ---- tokens: RV-32 untouched --------------------------------------------------------------

        "a `tokens` rule contributes the ENGINE's score, and keeps the RV-32 margin machinery" {
            val tokens = MatchProfile(listOf(NormRule(Norm.CANONICAL, tokens = true)))
            val hits =
                dispatch(
                    "celkem",
                    row("celkem za zákazníky", tokens, method = "TOKENS", engineScore = 0.7, targetRef = "md.a"),
                    row("celkem za pobočky", tokens, method = "TOKENS", engineScore = 0.6, targetRef = "md.b"),
                )

            hits.map { it.score } shouldBe listOf(0.7, 0.6)
            hits.forEach { it.provenance.algorithm shouldBe "tokens" }
            // The margin is still computed, and still keyed on the target ref.
            hits.first().uniquenessMargin shouldBe (0.1 plusOrMinus 1e-9)
            hits.first().autoBindable shouldBe true
        }

        // ---- review-103 F3: a `tokens` firing competes on min(S, 1.0) ------------------------------

        // The `obrat` probe. The engine score is what a REAL v2 index-first match of the one-token
        // query `obrat` gives the row `obrat`: EXACT, P = 1.0, C = 1.0 ⇒ S = 1.0 + ε = 1.01 (an
        // all-exact row, so D3 leaves it alone). Raw, that `tokens` firing beat the exact rule.
        fun v2EngineScore(
            query: String,
            value: String,
        ): Double {
            val candidate = Candidate.fromValues("x", value)
            val tokens = Candidate.tokenize(query)
            return TokenBasedMatcherV2(TokenIndex(listOf(candidate, Candidate.fromValues("y", "tržby"))))
                .scoreCandidate(tokens, tokens, candidate)
                .score
        }

        "F3 — `[{canonical, exact 1.0}, {folded, tokens}]` keeps its EXACT firing when tokens reads 1.01" {
            val engine = v2EngineScore("obrat", "obrat")
            engine shouldBe (1.01 plusOrMinus 1e-12)
            val profile = MatchProfile(listOf(canonicalExact, NormRule(Norm.FOLDED, tokens = true)))

            val hit = dispatch("obrat", row("obrat", profile, engineScore = engine)).single()

            hit.provenance.norm shouldBe "canonical"
            hit.provenance.algorithm shouldBe "exact"
            hit.score shouldBe 1.00
            hit.provenance.rawScore shouldBe engine // the engine's number stays reachable
        }

        "F3 — …and in the mis-ordered list too: a tie at 1.0 goes to the declared firing" {
            val engine = v2EngineScore("obrat", "obrat")
            val reversed = MatchProfile(listOf(NormRule(Norm.FOLDED, tokens = true), canonicalExact))

            val hit = dispatch("obrat", row("obrat", reversed, engineScore = engine)).single()

            hit.provenance.algorithm shouldBe "exact"
            hit.provenance.norm shouldBe "canonical"
            hit.score shouldBe 1.00
        }

        "F3 — a `tokens` firing still beats a LOWER declared score, and then carries the engine's own" {
            // folded exact 0.90 vs tokens at 1.01 (capped to 1.0 for the comparison only).
            val profile = MatchProfile(listOf(folded, NormRule(Norm.CANONICAL, tokens = true)))

            val hit = dispatch("obrat", row("obrat", profile, engineScore = 1.01)).single()

            hit.provenance.algorithm shouldBe "tokens"
            hit.score shouldBe 1.01
        }

        "F3 — an ordered multi-token exact query (v1's 1.05 bonus) no longer outranks the exact rule" {
            val profile = MatchProfile(listOf(canonicalExact, NormRule(Norm.CANONICAL, tokens = true)))

            val hit = dispatch("čistý obrat", row("čistý obrat", profile, engineScore = 1.05)).single()

            hit.provenance.algorithm shouldBe "exact"
            hit.score shouldBe 1.00
        }

        // ---- the override ---------------------------------------------------------------------------

        "`method_override` REPLACES a row's profile with the sugar profile it means" {
            // The rung's strict-first pass: an override of EXACT must not leave the estate's
            // folded stratum quietly firing underneath it.
            val profile = MatchProfile(listOf(canonicalExact, folded))

            dispatcher.dispatch("zakaznik", listOf(row("zákazník", profile)), MatchMethod.Exact).shouldBeEmpty()
            dispatcher
                .dispatch("zákazník", listOf(row("zákazník", profile)), MatchMethod.Exact)
                .single()
                .score shouldBe 1.00
        }

        "`method_override` never GRANTS a profile to a row that has none" {
            // Same rule the authored method has had since P1.4 T4: a caller widening its own
            // declared layer must not start scoring the member index by somebody's declared numbers.
            val member =
                FuzzyMatchResult(
                    candidateId = "pk-1",
                    candidate = "Kaufland",
                    score = 0.667,
                    category = "er.Customer",
                    source = SourceTag.MEMBER,
                )
            val declared = row("zákazník", MatchProfile(listOf(canonicalExact)))

            dispatcher
                .dispatch("zákazník", listOf(declared, member), MatchMethod.Exact)
                .single { it.candidateId == "pk-1" } shouldBe member
        }
    })
