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
                Candidate.fromValues("c-shell", "Shell Czech Republic a.s."),
                Candidate.fromValues("c-shelby", "Shelby s.r.o."),
                Candidate.fromValues("c-omv", "OMV Česká republika, s.r.o."),
                Candidate.fromValues("c-benzina", "Benzina s.r.o."),
            )
        val index = TokenIndex(corpus)
        val v2 = TokenBasedMatcherV2(index)

        fun q(text: String) = Candidate.tokenize(text)

        fun scoreOf(
            query: String,
            id: String,
        ): Scored = v2.scoreCandidate(q(query), q(query), corpus.first { it.id == id })

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

        "T2 — shel: a single token earns no order bonus (S = P + ε·C exactly)" {
            val shell = scoreOf("shel", "c-shell")
            // shel→shell: prefix at the 0.86 floor beats the 1-typo 0.85 (✅LP-8)
            shell.score shouldBe (0.86 + TokenBasedMatcherV2.EPSILON * shell.coverage!! plusOrMinus 1e-12)
            shell.tokenHits.single().kind shouldBe "prefix"

            val shelby = scoreOf("shel", "c-shelby")
            shelby.tokenHits.single().kind shouldBe "prefix"
            shelby.score shouldBe (0.86 + TokenBasedMatcherV2.EPSILON * shelby.coverage!! plusOrMinus 1e-12)
        }

        "T2 — two prefix hits in order earn the order bonus v1 never gave them" {
            val s = scoreOf("agro petr", "c-agro-petr")
            s.tokenHits.map { it.kind } shouldBe listOf("prefix", "prefix")
            // P = 0.86 (both prefix hits at the floor: 4/8, 4/11), one in-order pair ⇒ ×1.05.
            s.score shouldBe (0.86 * 1.05 + TokenBasedMatcherV2.EPSILON * s.coverage!! plusOrMinus 1e-12)
            s.coverage!! shouldBe (1.0 plusOrMinus 1e-12)

            // …and reversed, the pair is out of order ⇒ no bonus.
            val r = scoreOf("petr agro", "c-agro-petr")
            r.score shouldBe (0.86 + TokenBasedMatcherV2.EPSILON * r.coverage!! plusOrMinus 1e-12)
        }

        "T2 — an unmatched query token weighs idf(t) (idfAbsent outside the corpus) and scores 0" {
            val s = scoreOf("valmy zzzz", "c-valmy")
            val wValmy = index.idfV2("valmy")
            val wAbsent = index.idfV2("zzzz")
            val p = wValmy / (wValmy + wAbsent)
            s.score shouldBe (p + TokenBasedMatcherV2.EPSILON * s.coverage!! plusOrMinus 1e-12)
            s.tokenHits.map { it.queryToken } shouldBe listOf("valmy")
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
            for (query in listOf("valmy", "oil", "s.r.o.", "shel", "agro petr", "a.s.")) {
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

        "T5 — v2 with legacy retrieval is refused with the contracts §4.1 message" {
            MatchVersion.V1.requireCompatible(RetrievalMode.LEGACY)
            MatchVersion.V2.requireCompatible(RetrievalMode.INDEX_FIRST)
            shouldThrow<IllegalArgumentException> { MatchVersion.V2.requireCompatible(RetrievalMode.LEGACY) }
                .message!! shouldContain "v2 requires index-first"
        }
    })
