// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * review-103 F3 · ruling 2 · decision D3 — `fuzzy.match:v2` keeps every row that is not an all-exact
 * match under 1.0, so ≥ 1.0 keeps meaning "ordered exact" (design §5.5) for the resolver's member
 * EXACT class (0.9999). The probe rows are the review's own table, run through a REAL [FuzzyMatcher]
 * on an index-first member corpus — the path the resolver reads.
 */
class V2NormalizationSpec :
    StringSpec({
        val corpus =
            listOf(
                Candidate.fromValues("m-kovo-brno", "Kovostav Brno Stavby s.r.o."),
                Candidate.fromValues("m-kovo-praha", "Kovostav Praha s.r.o."),
                Candidate.fromValues("m-valmy-trade", "Valmy Oil Trade s.r.o."),
                Candidate.fromValues("m-valmy", "Valmy Oil, s.r.o."),
                Candidate.fromValues("m-oil-trade", "Oil Trade a.s."),
                Candidate.fromValues("m-stavby", "Stavby Novák s.r.o."),
                Candidate.fromValues("m-benzina", "Benzina s.r.o."),
                Candidate.fromValues("m-agro", "Agrofert, a.s."),
            )
        val index = TokenIndex(corpus)

        /** One member category, index-first, the way `StringRepository` serves it. */
        val repo =
            object : MatchRepository {
                override fun getCandidates(category: String?) = corpus

                override fun getTokenIndex(category: String?) = index

                override fun getDistanceCache(category: String?) = DistanceCache()

                override fun getVocabulary(category: String?) = TokenVocabulary(corpus)

                override fun vocabularyVersion() = "d3"
            }

        fun matcher(normalization: V2Normalization? = null) =
            if (normalization == null) {
                // No argument at all: what an embedder gets (the library default).
                FuzzyMatcher(repo, retrievalMode = RetrievalMode.INDEX_FIRST, matchVersion = MatchVersion.V2)
            } else {
                FuzzyMatcher(
                    repo,
                    retrievalMode = RetrievalMode.INDEX_FIRST,
                    matchVersion = MatchVersion.V2,
                    v2Normalization = normalization,
                )
            }

        suspend fun top(
            m: FuzzyMatcher,
            query: String,
        ): FuzzyMatchResult = m.match(query, "customer", AlgorithmType.TATRMAN, 5).first()

        // `S_perfect(n)` — the all-exact in-order order bonus, WITHOUT §4.3's ε term.
        fun perfect(n: Int) = Math.pow(1.05, n * (n - 1) / 2.0).coerceAtMost(1.5)

        /** Every one of the query's [n] tokens hit, and every hit is EXACT. */
        fun Scored.allExact(n: Int) = tokenHits.size == n && tokenHits.all { it.kind == "exact" }

        // The review's probe table: query → (the row it answers, its kinds in query order).
        val probes =
            listOf(
                Triple("kovo brno stav", "m-kovo-brno", listOf("prefix", "exact", "prefix")),
                Triple("valmi oil trade", "m-valmy-trade", listOf("typo", "exact", "exact")),
                Triple("valmy oil trad", "m-valmy-trade", listOf("exact", "exact", "prefix")),
            )

        "F3 probe table — `off` reproduces the defect: a partial/typo member match scores ≥ 1.0" {
            val off = matcher(V2Normalization.OFF)
            for ((query, id, kinds) in probes) {
                val hit = top(off, query)
                hit.candidateId shouldBe id
                hit.provenance.tokenHits.map { it.kind } shouldBe kinds
                // What the resolver's member-EXACT rule (score ≥ 0.9999) read as EXACT.
                hit.score shouldBeGreaterThanOrEqual 1.0
            }
        }

        "F3 probe table — the shipped default (scale 0.99) puts the same rows under 1.0" {
            val off = matcher(V2Normalization.OFF)
            val shipped = matcher()
            shipped.normalization shouldBe V2Normalization.DEFAULT
            for ((query, id, kinds) in probes) {
                val hit = top(shipped, query)
                hit.candidateId shouldBe id
                hit.provenance.tokenHits.map { it.kind } shouldBe kinds
                hit.score shouldBeLessThan 1.0
                hit.score shouldBeLessThan V2Normalization.DEFAULT_CEILING + 1e-12
                // …by ONE factor per query length, S / S_perfect(3); coverage (C) is reported unscaled.
                hit.score shouldBe (top(off, query).score / perfect(3) plusOrMinus 1e-12)
                hit.provenance.coverage shouldBe top(off, query).provenance.coverage
                println("D3 probe '$query': off %.4f → scale %.4f".format(top(off, query).score, hit.score))
            }
        }

        "S_perfect(1) = 1: a one-token non-exact row keeps §4.3's S byte for byte (typo, prefix, ED-2)" {
            val off = matcher(V2Normalization.OFF)
            val shipped = matcher()
            for ((query, kind) in listOf("benzin" to "prefix", "valmi" to "typo", "agrofret" to "typo")) {
                val before = top(off, query)
                val now = top(shipped, query)
                now.provenance.tokenHits
                    .single()
                    .kind shouldBe kind
                now.candidateId shouldBe before.candidateId
                now.score shouldBe before.score
            }
            // The case the ε cost: an ED-2 typo (q 0.70) stays at 0.70 + ε·C, on the resolver's
            // LIVE_STRONG floor (0.70) rather than under it.
            top(shipped, "agrofret").score shouldBeGreaterThanOrEqual 0.70
        }

        "without ε a near-perfect multi-token partial scales PAST 1.0 — the ceiling is what keeps it under" {
            // A rare exact token plus a 24/25 prefix of a token every row shares: P ≈ 0.992, so
            // S / S_perfect(2) = (P·1.05 + ε) / 1.05 ≥ 1.0.
            val common = "abcdefghijklmnopqrstuvwxy"
            val rows =
                listOf(Candidate.fromValues("target", "Zebrax $common")) +
                    (1..29).map { Candidate.fromValues("f$it", "Filler$it $common") }
            val index = TokenIndex(rows)
            val tokens = Candidate.tokenize("zebrax ${common.dropLast(1)}")
            val raw =
                TokenBasedMatcherV2(
                    index,
                    normalization = V2Normalization.OFF,
                ).scoreCandidate(tokens, tokens, rows[0])
            raw.tokenHits.map { it.kind } shouldBe listOf("exact", "prefix")
            raw.score shouldBeGreaterThanOrEqual 1.0 // §4.3 as written: read as ordered exact
            (raw.score / perfect(2)) shouldBeGreaterThanOrEqual 1.0 // the scale alone does not bound it
            TokenBasedMatcherV2(index).scoreCandidate(tokens, tokens, rows[0]).score shouldBe
                (V2Normalization.DEFAULT_CEILING plusOrMinus 1e-12)
        }

        "an all-exact, in-order query keeps §4.3's S (≥ 1.0) in every mode" {
            val modes =
                listOf(
                    V2Normalization.OFF,
                    V2Normalization.DEFAULT,
                    V2Normalization(V2Normalization.Mode.CAP, 0.99),
                    V2Normalization(V2Normalization.Mode.SCALE, 0.5),
                )
            val scores =
                modes.map { mode ->
                    val hit = top(matcher(mode), "valmy oil trade")
                    hit.candidateId shouldBe "m-valmy-trade"
                    hit.provenance.tokenHits.map { it.kind } shouldBe listOf("exact", "exact", "exact")
                    hit.score
                }
            scores.forEach { it shouldBe scores.first() }
            // Three in-order exact hits: 1.05³ + ε·C.
            scores.first() shouldBeGreaterThanOrEqual 1.157625
        }

        "an all-exact row outranks every non-exact one: `valmy oil trade` over `valmi oil trade`" {
            // The same row answers both; the exact query must read as exact and the typo one must not.
            val shipped = matcher()
            top(shipped, "valmy oil trade").score shouldBeGreaterThanOrEqual 1.0
            top(shipped, "valmi oil trade").score shouldBeLessThan 1.0
        }

        "scale leaves the ORDER among non-exact rows unchanged (one linear factor per query)" {
            val off = TokenBasedMatcherV2(index, normalization = V2Normalization.OFF)
            val scaled = TokenBasedMatcherV2(index)
            var compared = 0
            for (query in listOf(
                "kovo brno stav",
                "valmi oil trade",
                "valmy oil trad",
                "kovo",
                "oil trad",
                "valmi oyl",
                "stav nov",
                "agro",
                "benzin",
            )) {
                val tokens = Candidate.tokenize(query)
                val rows =
                    corpus
                        .map { it to off.scoreCandidate(tokens, tokens, it) }
                        .filter { (_, s) -> s.score > 0.0 && !s.allExact(tokens.size) }
                if (rows.size >= 2) compared++
                val offOrder = rows.sortedByDescending { (_, s) -> s.score }.map { (c, _) -> c.id }
                val scaledRows = rows.map { (c, _) -> c to scaled.scoreCandidate(tokens, tokens, c) }
                scaledRows.sortedByDescending { (_, s) -> s.score }.map { (c, _) -> c.id } shouldBe offOrder
                for ((pair, scaledPair) in rows.zip(scaledRows)) {
                    scaledPair.second.score shouldBe (
                        minOf(0.99, pair.second.score / perfect(tokens.size)) plusOrMinus 1e-12
                    )
                    scaledPair.second.score shouldBeLessThan 1.0
                }
            }
            compared shouldBeGreaterThanOrEqual 3 // not vacuous: several queries rank ≥ 2 non-exact rows
        }

        "cap clips the raw S at the ceiling; a sub-ceiling row is untouched" {
            val cap = matcher(V2Normalization(V2Normalization.Mode.CAP, 0.99))
            val off = matcher(V2Normalization.OFF)
            top(cap, "kovo brno stav").score shouldBe (0.99 plusOrMinus 1e-12)
            top(cap, "benzin").score shouldBe top(off, "benzin").score // one prefix hit, S < 0.99
        }

        "scale honours a configured ceiling as the guard" {
            top(matcher(V2Normalization(V2Normalization.Mode.SCALE, 0.5)), "valmy oil trad").score shouldBe
                (0.5 plusOrMinus 1e-12)
        }

        "v1 never runs the normalization: its scores ignore the configured mode" {
            val v1Off =
                FuzzyMatcher(
                    repo,
                    retrievalMode = RetrievalMode.INDEX_FIRST,
                    v2Normalization = V2Normalization.OFF,
                )
            val v1Default = FuzzyMatcher(repo, retrievalMode = RetrievalMode.INDEX_FIRST)
            for (query in listOf("kovo brno stav", "valmi oil trade", "kovostaw brno stavby")) {
                v1Off.match(query, "customer", AlgorithmType.TATRMAN, 5).map { it.candidateId to it.score } shouldBe
                    v1Default.match(query, "customer", AlgorithmType.TATRMAN, 5).map { it.candidateId to it.score }
            }
        }

        "config — the ceiling must be > 0 and < 1.0, in every mode" {
            for (bad in listOf(1.0, 1.5, 0.0, -0.1, Double.NaN)) {
                for (mode in V2Normalization.Mode.entries) {
                    shouldThrow<IllegalArgumentException> { V2Normalization(mode, bad) }.message!! shouldContain
                        "fuzzy.match.v2.normalize.ceiling"
                }
            }
            V2Normalization(V2Normalization.Mode.SCALE, 0.99).ceiling shouldBe 0.99
            V2Normalization(V2Normalization.Mode.CAP, 0.5).ceiling shouldBe 0.5
            V2Normalization.DEFAULT shouldBe V2Normalization(V2Normalization.Mode.SCALE, 0.99)
        }

        "config — mode parses scale/cap/off, blank is the default, anything else is an error" {
            V2Normalization.Mode.fromString("scale") shouldBe V2Normalization.Mode.SCALE
            V2Normalization.Mode.fromString(" CAP ") shouldBe V2Normalization.Mode.CAP
            V2Normalization.Mode.fromString("off") shouldBe V2Normalization.Mode.OFF
            V2Normalization.Mode.fromString(null) shouldBe V2Normalization.Mode.SCALE
            V2Normalization.Mode.fromString("") shouldBe V2Normalization.Mode.SCALE
            V2Normalization.Mode.fromString(" ", default = V2Normalization.Mode.OFF) shouldBe V2Normalization.Mode.OFF
            shouldThrow<IllegalArgumentException> { V2Normalization.Mode.fromString("clip") }.message!! shouldContain
                "fuzzy.match.v2.normalize.mode"
        }
    })
