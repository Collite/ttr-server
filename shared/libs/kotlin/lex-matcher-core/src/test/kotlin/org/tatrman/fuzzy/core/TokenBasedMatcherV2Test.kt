// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** LP-P0·S2 — `fuzzy.match:v2` score, provenance, retrieval and selector (contracts §4.1–4.6). */
class TokenBasedMatcherV2Test :
    StringSpec({
        val corpus =
            listOf(
                Candidate.fromValues("c-valmy", "Valmy Oil, s.r.o."),
                Candidate.fromValues("c-valmy-sk", "Valmy Oil Slovakia, s.r.o."),
                Candidate.fromValues("c-oil", "Oil s.r.o."),
                Candidate.fromValues("c-agro", "Agrofert, a.s."),
                Candidate.fromValues("c-agro-petr", "Agrofert Petrochemie"),
                Candidate.fromValues("c-pelex", "Pelex Czech Republic a.s."),
                Candidate.fromValues("c-peleby", "Peleby s.r.o."),
                Candidate.fromValues("c-vex", "Vex Česká republika, s.r.o."),
                Candidate.fromValues("c-benzina", "Benzina s.r.o."),
            )
        val index = TokenIndex(corpus)
        val v2 = TokenBasedMatcherV2(index)

        // review-103 D3 — `off` is §4.3's S as the contract wrote it; the cases below that pin the
        // §4.3 arithmetic on a NON-exact row pin it here, and check the shipped `scale` against it.
        val v2Off = TokenBasedMatcherV2(index, normalization = V2Normalization.OFF)

        fun q(text: String) = Candidate.tokenize(text)

        fun scoreOf(
            query: String,
            id: String,
        ): Scored = v2.scoreCandidate(q(query), q(query), corpus.first { it.id == id })

        fun offScoreOf(
            query: String,
            id: String,
        ): Scored = v2Off.scoreCandidate(q(query), q(query), corpus.first { it.id == id })

        // `S_perfect(n)` at the default multiplier/cap: min(1.05^(n(n−1)/2), 1.5) — no ε term.
        fun perfect(n: Int) = Math.pow(1.05, n * (n - 1) / 2.0).coerceAtMost(1.5)

        "T2 — valmy: two candidates at equal P; coverage orders them, by less than ε" {
            val a = scoreOf("valmy", "c-valmy")
            val b = scoreOf("valmy", "c-valmy-sk")
            // P = 1.0 for both (one exact query token), so S − ε·C is the same number.
            (a.score - TokenBasedMatcherV2.EPSILON * a.coverage!!) shouldBe (1.0 plusOrMinus 1e-12)
            (b.score - TokenBasedMatcherV2.EPSILON * b.coverage!!) shouldBe (1.0 plusOrMinus 1e-12)
            a.score shouldBeGreaterThan b.score
            (a.score - b.score) shouldBeLessThan TokenBasedMatcherV2.EPSILON

            v2.score(q("valmy"), q("valmy"), corpus, 2).map { it.candidate.id } shouldBe
                listOf("c-valmy", "c-valmy-sk")
        }

        "T2 — oil: the short `Oil s.r.o.` outranks `Valmy Oil Slovakia` (candidate coverage)" {
            scoreOf("oil", "c-oil").score shouldBeGreaterThan scoreOf("oil", "c-valmy-sk").score
            v2
                .score(q("oil"), q("oil"), corpus, 1)
                .single()
                .candidate.id shouldBe "c-oil"
        }

        "T2 — pele: a single token earns no order bonus (S = P + ε·C exactly)" {
            val pelex = offScoreOf("pele", "c-pelex")
            // pele→pelex: prefix at the 0.86 floor beats the 1-typo 0.85 (✅LP-8)
            pelex.score shouldBe (0.86 + TokenBasedMatcherV2.EPSILON * pelex.coverage!! plusOrMinus 1e-12)
            pelex.tokenHits.single().kind shouldBe "prefix"

            val peleby = offScoreOf("pele", "c-peleby")
            peleby.tokenHits.single().kind shouldBe "prefix"
            peleby.score shouldBe (0.86 + TokenBasedMatcherV2.EPSILON * peleby.coverage!! plusOrMinus 1e-12)

            // D3 — shipped `scale`: S_perfect(1) = 1, so a one-token row keeps §4.3's S exactly.
            perfect(1) shouldBe 1.0
            scoreOf("pele", "c-pelex").score shouldBe pelex.score
            scoreOf("pele", "c-pelex").tokenHits shouldBe pelex.tokenHits
        }

        "T2 — two prefix hits in order earn the order bonus v1 never gave them" {
            val s = offScoreOf("agro petr", "c-agro-petr")
            s.tokenHits.map { it.kind } shouldBe listOf("prefix", "prefix")
            // P = 0.86 (both prefix hits at the floor: 4/8, 4/11), one in-order pair ⇒ ×1.05.
            s.score shouldBe (0.86 * 1.05 + TokenBasedMatcherV2.EPSILON * s.coverage!! plusOrMinus 1e-12)
            s.coverage!! shouldBe (1.0 plusOrMinus 1e-12)

            // …and reversed, the pair is out of order ⇒ no bonus.
            val r = offScoreOf("petr agro", "c-agro-petr")
            r.score shouldBe (0.86 + TokenBasedMatcherV2.EPSILON * r.coverage!! plusOrMinus 1e-12)

            // D3 — one factor per query length, so the in-order row still beats the reversed one.
            scoreOf("agro petr", "c-agro-petr").score shouldBe (s.score / perfect(2) plusOrMinus 1e-12)
            scoreOf("petr agro", "c-agro-petr").score shouldBe (r.score / perfect(2) plusOrMinus 1e-12)
        }

        "T2 — an unmatched query token weighs idf(t) (idfAbsent outside the corpus) and scores 0" {
            val s = offScoreOf("valmy zzzz", "c-valmy")
            val wValmy = index.idfV2("valmy")
            val wAbsent = index.idfV2("zzzz")
            val p = wValmy / (wValmy + wAbsent)
            s.score shouldBe (p + TokenBasedMatcherV2.EPSILON * s.coverage!! plusOrMinus 1e-12)
            s.tokenHits.map { it.queryToken } shouldBe listOf("valmy")
            // D3 — an UNMATCHED token is not an exact one: the row is normalized too.
            scoreOf("valmy zzzz", "c-valmy").score shouldBe (s.score / perfect(2) plusOrMinus 1e-12)
        }

        "T3 — provenance: one hit per matched query token, with kinds and both positions" {
            val s = scoreOf("valmy oil", "c-valmy-sk")
            s.tokenHits shouldBe
                listOf(
                    TokenHit("valmy", "valmy", "exact", 0, queryPos = 0, candidatePos = 0),
                    TokenHit("oil", "oil", "exact", 0, queryPos = 1, candidatePos = 1),
                )
            val tokens = Candidate.tokenize("Valmy Oil Slovakia, s.r.o.")
            val expectedC =
                (index.idfV2("valmy") + index.idfV2("oil")) / tokens.sumOf { index.idfV2(it) }
            s.coverage!! shouldBe (expectedC plusOrMinus 1e-12)
        }

        "T3 — two query tokens hitting ONE candidate token count it once in C" {
            val s = scoreOf("benzina benzin", "c-benzina")
            s.tokenHits.map { it.candidatePos } shouldBe listOf(0, 0)
            val tokens = Candidate.tokenize("Benzina s.r.o.")
            s.coverage!! shouldBe (index.idfV2("benzina") / tokens.sumOf { index.idfV2(it) } plusOrMinus 1e-12)
        }

        "✅LP-7 — `valmy oil` prefers `Valmy Oil, s.r.o.` (both tokens EXACT through the comma) by coverage" {
            val a = scoreOf("valmy oil", "c-valmy")
            a.tokenHits.map { it.kind to it.candidateToken } shouldBe listOf("exact" to "valmy", "exact" to "oil,")
            v2.score(q("valmy oil"), q("valmy oil"), corpus, 2).map { it.candidate.id } shouldBe
                listOf("c-valmy", "c-valmy-sk")
            // …and `agrofert a.s.` now answers the row that IS `Agrofert, a.s.`
            v2
                .score(q("agrofert a.s."), q("agrofert a.s."), corpus, 1)
                .single()
                .candidate.id shouldBe "c-agro"
        }

        "review — rows sharing an id (alias rows) each get their own coverage denominator" {
            val canonical = Candidate.fromValues("pk-1", "Agrofert Petrochemie Holding")
            val alias = Candidate.fromValues("pk-1", "AGF")
            val aliased = TokenIndex(listOf(canonical, alias, Candidate.fromValues("pk-2", "Benzina")))
            val m = TokenBasedMatcherV2(aliased)
            // Score the long row first: before the fix its denominator was memoised for the alias too.
            m.scoreCandidate(q("agrofert"), q("agrofert"), canonical).coverage!! shouldBeLessThan 1.0
            m.scoreCandidate(q("agf"), q("agf"), alias).coverage!! shouldBe (1.0 plusOrMinus 1e-12)
        }

        "review — v2 df counts rows like v1, not distinct ids" {
            val rows =
                listOf(
                    Candidate.fromValues("pk-1", "Oil One"),
                    Candidate.fromValues("pk-1", "Oil Two"),
                    Candidate.fromValues("pk-2", "Benzina"),
                )
            val idx = TokenIndex(rows)
            idx.idfV2("oil") shouldBe (idx.idf("oil") plusOrMinus 1e-12)
        }

        "review — an exact hit takes the earliest position, through the edge trim" {
            val c = Candidate.fromValues("c-x", "Oil, Oil")
            val m = TokenBasedMatcherV2(TokenIndex(listOf(c)))
            m
                .scoreCandidate(q("oil"), q("oil"), c)
                .tokenHits
                .single()
                .candidatePos shouldBe 0
        }

        "T1 — v1 through the TokenScorer seam is exactly rescore(), with no provenance" {
            val v1 = TokenBasedMatcher(corpus, index)
            val tokens = q("valmy oil")
            val seam = v1.score(tokens, tokens, corpus, 5)
            seam.map { it.candidate to it.score } shouldBe v1.rescore(tokens, tokens, corpus, 5)
            seam.all { it.tokenHits.isEmpty() && it.coverage == null } shouldBe true
        }

        "T4 — an all-prefix query retrieves under v2; v1 retrieval finds nothing" {
            val vocab = TokenVocabulary(corpus)
            val tokens = q("agro petr")
            IndexFirstRetriever(MatchVersion.V1) { vocab }.retrieve(tokens, tokens, null, 200).shouldBeEmpty()
            val v2Hits = IndexFirstRetriever(MatchVersion.V2) { vocab }.retrieve(tokens, tokens, null, 200)
            v2Hits.first().id shouldBe "c-agro-petr"
        }

        "score() = stable sort-then-take over the full scoring, for every limit (top-k selection)" {
            for (query in listOf("valmy", "oil", "s.r.o.", "pele", "agro petr", "a.s.")) {
                val tokens = q(query)
                val full =
                    corpus
                        .map { v2.scoreCandidate(tokens, tokens, it) }
                        .sortedByDescending { it.score }
                for (limit in listOf(1, 2, 3, 5, corpus.size, corpus.size + 3)) {
                    v2.score(tokens, tokens, corpus, limit) shouldBe full.take(limit)
                }
            }
        }

        "T5 — MatchVersion parses v1/v2, defaults blank to v1, and refuses anything else" {
            MatchVersion.fromString(null) shouldBe MatchVersion.V1
            MatchVersion.fromString(" ") shouldBe MatchVersion.V1
            MatchVersion.fromString("V2") shouldBe MatchVersion.V2
            shouldThrow<IllegalArgumentException> { MatchVersion.fromString("v3") }
        }

        "review-103 L1 — blank is UNSET and takes the caller's default (the service passes its shipped v2)" {
            MatchVersion.fromString(null, default = MatchVersion.V2) shouldBe MatchVersion.V2
            MatchVersion.fromString("", default = MatchVersion.V2) shouldBe MatchVersion.V2
            MatchVersion.fromString("v1", default = MatchVersion.V2) shouldBe MatchVersion.V1
            shouldThrow<IllegalArgumentException> { MatchVersion.fromString("v3", default = MatchVersion.V2) }
        }

        "T5 — v2 with legacy retrieval is refused with the contracts §4.1 message" {
            MatchVersion.V1.requireCompatible(RetrievalMode.LEGACY)
            MatchVersion.V2.requireCompatible(RetrievalMode.INDEX_FIRST)
            shouldThrow<IllegalArgumentException> { MatchVersion.V2.requireCompatible(RetrievalMode.LEGACY) }
                .message!! shouldContain "v2 requires index-first"
        }
    })
