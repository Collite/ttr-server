// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe

/**
 * GH #69 — IDF-weighted TATRMAN scoring.
 *
 * Mirrors the reported case: a category where the token "kancelar" appears in
 * every candidate (common) while "qt" is rare. Matching the common token alone
 * must NOT earn a high score; the full match and the rare-token match must win.
 */
class TokenBasedIdfScoringTest :
    StringSpec({
        // "kancelar" ∈ all 6 candidates (df=6, idf≈1.0); "qt" ∈ 2 (rare).
        val candidates =
            listOf(
                Candidate.fromValues("1", "QT KANCELAR"),
                Candidate.fromValues("2", "Kancelar QT"),
                Candidate.fromValues("3", "Kancelar CS"),
                Candidate.fromValues("4", "Kancelar SM"),
                Candidate.fromValues("5", "Kancelar NT"),
                Candidate.fromValues("6", "Kancelar XY"),
            )

        fun matcher(idfEnabled: Boolean): TokenBasedMatcher =
            TokenBasedMatcher(
                candidates = candidates,
                tokenIndex = TokenIndex(candidates),
                distanceCache = DistanceCache(),
                idfEnabled = idfEnabled,
            )

        fun scores(idfEnabled: Boolean): Map<String, Double> =
            matcher(idfEnabled).match("QT KANCELAR", 10).associate { it.first.value to it.second }

        "IDF on — matching only the common token scores far below a full match" {
            val s = scores(idfEnabled = true)
            val full = s["QT KANCELAR"] ?: 0.0
            val commonOnly = s["Kancelar CS"] ?: 0.0
            full shouldBeGreaterThan 0.99
            // "Kancelar CS" shares only the ubiquitous "kancelar" — must fall well
            // below the resolver's TATRMAN accept floor (0.91), not near it.
            commonOnly shouldBeLessThan 0.5
            full shouldBeGreaterThan commonOnly
        }

        "IDF on — the full match ranks first" {
            matcher(idfEnabled = true)
                .match("QT KANCELAR", 10)
                .first()
                .first.value shouldBe "QT KANCELAR"
        }

        "IDF off (legacy) — the common-only match scores high, demonstrating the bug it fixes" {
            val s = scores(idfEnabled = false)
            // Legacy char-overlap scoring rates "Kancelar CS" highly (the bug):
            // one of two tokens matched ⇒ ~0.9, which the old pipeline accepted.
            (s["Kancelar CS"] ?: 0.0) shouldBeGreaterThan 0.8
        }

        "TokenIndex.idf — rarer tokens weigh more; common tokens floor at ≥1" {
            val index = TokenIndex(candidates)
            index.idf("kancelar") shouldBeLessThan index.idf("qt")
            index.idf("kancelar") shouldBeGreaterThanOrEqual 1.0
            // An unseen token is treated as maximally rare (≥ any present token).
            index.idf("zzz") shouldBeGreaterThan index.idf("qt")
        }
    })
