// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import kotlin.math.ln

/**
 * FZ-P1 Tier-1 pinning tests — written BEFORE the fixes (TDD). They encode behaviour the
 * mechanical fixes must preserve byte-for-byte:
 *  - (a) the exact-token short-circuit (T2) must reproduce the IDF-weighted score exactly — on an
 *    exact hit the matched token IS the query token, so `idf(matched) == idf(queryToken)` and the
 *    per-token quality is 1.0 (hand-computed here);
 *  - (b) the repaired [DistanceCache] (T3) must retain entries past the legacy 15k wholesale-wipe
 *    threshold — **fails until T3 lands** (failing-first; no `@Ignore`);
 *  - (c) the seed + lazy-extras path (T4) must return the identical ordered result set.
 */
class Tier1FixesSpec :
    StringSpec({
        // idf(t) = ln((N+1)/(df+1)) + 1, N = document count.
        fun idf(
            df: Int,
            n: Int,
        ): Double = ln((n + 1.0) / (df + 1.0)) + 1.0

        "(a) exact-token hit reproduces the IDF-weighted score (full exact match ⇒ 1.05 order bonus)" {
            // "kancelar" ∈ all 3 (df=3), "qt" ∈ 1 (df=1). N=3.
            val candidates =
                listOf(
                    Candidate.fromValues("1", "qt kancelar"),
                    Candidate.fromValues("2", "kancelar cs"),
                    Candidate.fromValues("3", "kancelar sm"),
                )
            val matcher =
                TokenBasedMatcher(
                    candidates = candidates,
                    tokenIndex = TokenIndex(candidates),
                    distanceCache = DistanceCache(),
                    idfEnabled = true,
                )
            val scores = matcher.match("qt kancelar", 10).associate { it.first.id to it.second }

            // Candidate 1: both query tokens are exact hits (quality 1.0 each), ordered ⇒ base 1.0 × 1.05.
            abs((scores["1"] ?: 0.0) - 1.05) shouldBeLessThan 1e-9

            // Candidate 2: "kancelar" exact (quality 1.0, weight idf=1.0); "qt" nearest "cs" at dist 2
            // (quality 0, weight idf(cs)); no ordered pair ⇒ bonus 1.0. base = 1.0 / (idf(cs) + idf(kancelar)).
            val expected2 = 1.0 / (idf(1, 3) + idf(3, 3))
            abs((scores["2"] ?: 0.0) - expected2) shouldBeLessThan 1e-9
        }

        "(b) DistanceCache retains entries past the legacy 15k wipe threshold" {
            val cache = DistanceCache()
            val inserts = 20_000
            for (i in 0 until inserts) {
                cache.getOrCompute("q$i", "c$i") { 1.0 }
            }
            // Pre-T3 the cache wiped wholesale past 15k (size would collapse); post-T3 it keeps them all.
            cache.size() shouldBe inserts
        }

        "(c) seed + lazy extras return the identical ordered results" {
            // Query "alpha" seeds {1,2} (share the exact token); candidate 3 has no overlap and is
            // reached only through the extras path. The full ordered id list must be stable.
            val candidates =
                listOf(
                    Candidate.fromValues("1", "alpha beta"),
                    Candidate.fromValues("2", "alpha gamma"),
                    Candidate.fromValues("3", "zeta"),
                )
            val matcher =
                TokenBasedMatcher(
                    candidates = candidates,
                    tokenIndex = TokenIndex(candidates),
                    distanceCache = DistanceCache(),
                    idfEnabled = true,
                )
            val ids = matcher.match("alpha", 10).map { it.first.id }
            // The two exact-token seeds outrank the fuzzy-only extra; ids 1 & 2 come first (tie, seed
            // order), 3 last.
            ids.take(2).toSet() shouldBe setOf("1", "2")
            ids.last() shouldBe "3"
            ids.size shouldBe 3
        }
    })
