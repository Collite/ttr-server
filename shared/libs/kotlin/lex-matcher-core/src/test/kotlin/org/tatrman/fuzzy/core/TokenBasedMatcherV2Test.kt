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
                Candidate.fromValues("c-marvy", "Marvy Oil, s.r.o."),
                Candidate.fromValues("c-marvy-sk", "Marvy Oil Slovakia, s.r.o."),
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

        "T2 — marvy: two candidates at equal P; coverage orders them, by less than ε" {
            val a = scoreOf("marvy", "c-marvy")
            val b = scoreOf("marvy", "c-marvy-sk")
            // P = 1.0 for both (one exact query token), so S − ε·C is the same number.
            (a.score - TokenBasedMatcherV2.EPSILON * a.coverage!!) shouldBe (1.0 plusOrMinus 1e-12)
            (b.score - TokenBasedMatcherV2.EPSILON * b.coverage!!) shouldBe (1.0 plusOrMinus 1e-12)
            a.score shouldBeGreaterThan b.score
            (a.score - b.score) shouldBeLessThan TokenBasedMatcherV2.EPSILON

            v2.score(q("marvy"), q("marvy"), corpus, 2).map { it.candidate.id } shouldBe
                listOf("c-marvy", "c-marvy-sk")
        }

        "T2 — oil: the short `Oil s.r.o.` outranks `Marvy Oil Slovakia` (candidate coverage)" {
            scoreOf("oil", "c-oil").score shouldBeGreaterThan scoreOf("oil", "c-marvy-sk").score
            v2
                .score(q("oil"), q("oil"), corpus, 1)
                .single()
                .candidate.id shouldBe "c-oil"
        }

        "T2 — shel: a single token earns no order bonus (S = P + ε·C exactly)" {
            val shell = scoreOf("shel", "c-shell")
            val p = 1.0 - 0.15 // shel→shell: 1-typo (0.85) beats the prefix (0.80), contracts §4.2 max q
            shell.score shouldBe (p + TokenBasedMatcherV2.EPSILON * shell.coverage!! plusOrMinus 1e-12)
            shell.tokenHits.single().kind shouldBe "typo"

            val shelby = scoreOf("shel", "c-shelby")
            shelby.tokenHits.single().kind shouldBe "prefix"
            shelby.score shouldBe (0.80 + TokenBasedMatcherV2.EPSILON * shelby.coverage!! plusOrMinus 1e-12)
        }

        "T2 — two prefix hits in order earn the order bonus v1 never gave them" {
            val s = scoreOf("agro petr", "c-agro-petr")
            s.tokenHits.map { it.kind } shouldBe listOf("prefix", "prefix")
            // P = 0.80 (both prefix hits at the floor: 4/8, 4/11), one in-order pair ⇒ ×1.05.
            s.score shouldBe (0.80 * 1.05 + TokenBasedMatcherV2.EPSILON * s.coverage!! plusOrMinus 1e-12)
            s.coverage!! shouldBe (1.0 plusOrMinus 1e-12)

            // …and reversed, the pair is out of order ⇒ no bonus.
            val r = scoreOf("petr agro", "c-agro-petr")
            r.score shouldBe (0.80 + TokenBasedMatcherV2.EPSILON * r.coverage!! plusOrMinus 1e-12)
        }

        "T2 — an unmatched query token weighs idf(t) (idfAbsent outside the corpus) and scores 0" {
            val s = scoreOf("marvy zzzz", "c-marvy")
            val wMarvy = index.idf("marvy")
            val wAbsent = index.idf("zzzz")
            val p = wMarvy / (wMarvy + wAbsent)
            s.score shouldBe (p + TokenBasedMatcherV2.EPSILON * s.coverage!! plusOrMinus 1e-12)
            s.tokenHits.map { it.queryToken } shouldBe listOf("marvy")
        }

        "T3 — provenance: one hit per matched query token, with kinds and both positions" {
            val s = scoreOf("marvy oil", "c-marvy-sk")
            s.tokenHits shouldBe
                listOf(
                    TokenHit("marvy", "marvy", "exact", 0, queryPos = 0, candidatePos = 0),
                    TokenHit("oil", "oil", "exact", 0, queryPos = 1, candidatePos = 1),
                )
            val tokens = Candidate.tokenize("Marvy Oil Slovakia, s.r.o.")
            val expectedC =
                (index.idf("marvy") + index.idf("oil")) / tokens.sumOf { index.idf(it) }
            s.coverage!! shouldBe (expectedC plusOrMinus 1e-12)
        }

        "T3 — two query tokens hitting ONE candidate token count it once in C" {
            val s = scoreOf("benzina benzin", "c-benzina")
            s.tokenHits.map { it.candidatePos } shouldBe listOf(0, 0)
            val tokens = Candidate.tokenize("Benzina s.r.o.")
            s.coverage!! shouldBe (index.idf("benzina") / tokens.sumOf { index.idf(it) } plusOrMinus 1e-12)
        }

        "T1 — v1 through the TokenScorer seam is exactly rescore(), with no provenance" {
            val v1 = TokenBasedMatcher(corpus, index)
            val tokens = q("marvy oil")
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
