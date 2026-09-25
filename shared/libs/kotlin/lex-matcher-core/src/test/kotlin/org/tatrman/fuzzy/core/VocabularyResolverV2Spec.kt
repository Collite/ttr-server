// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/** LP-P0·S1 — `fuzzy.match:v2` per-token kinds + the ES-AUTO edit ladder (contracts §4.2). */
class VocabularyResolverV2Spec :
    StringSpec({
        val candidates =
            listOf(
                Candidate.fromValues("A", "Pelex Czech Republic"),
                Candidate.fromValues("B", "Agrofert"),
                Candidate.fromValues("C", "Valmy Oil"),
                Candidate.fromValues("D", "abc"),
                Candidate.fromValues("E", "Agra Invest"),
                Candidate.fromValues("F", "shelter"),
            )
        val vocab = TokenVocabulary(candidates)

        fun resolver() = VocabularyResolver(vocab)

        fun List<ResolvedToken>.hit(token: String): ResolvedToken? = firstOrNull { it.tokenId == vocab.idOf(token) }

        "T1 — the edit budget ladder at its boundaries 2 · 3 · 5 · 6" {
            mapOf(0 to 0, 1 to 0, 2 to 0, 3 to 1, 5 to 1, 6 to 2, 12 to 2).forEach { (len, budget) ->
                EditBudget.of(len) shouldBe budget
            }
        }

        "T2 — v1 ResolvedToken construction derives its kind from the distance" {
            ResolvedToken(0, distance = 0, quality = 1.0, weight = 1.0).kind shouldBe MatchKind.EXACT
            ResolvedToken(0, distance = 2, quality = 0.7, weight = 1.0).kind shouldBe MatchKind.TYPO
        }

        "T2 — the v1 path never emits a PREFIX kind (byte-pinned: no prefix hits at all)" {
            resolver().resolve("agro").hit("agrofert") shouldBe null
        }

        "exact ⇒ one entry, EXACT, q 1.0" {
            val r = resolver().resolveV2("pelex")
            r.size shouldBe 1
            r[0].tokenId shouldBe vocab.idOf("pelex")
            r[0].kind shouldBe MatchKind.EXACT
            r[0].quality shouldBe 1.0
        }

        "agro → agrofert is a PREFIX at the 0.86 floor (4/8 < 0.86, ✅LP-8)" {
            val hit = resolver().resolveV2("agro").hit("agrofert")!!
            hit.kind shouldBe MatchKind.PREFIX
            hit.quality shouldBe (0.86 plusOrMinus 1e-12)
        }

        "a long prefix earns len t / len c above the floor — republi → republic = 7/8" {
            val hit = resolver().resolveV2("republi").hit("republic")!!
            // Also ED 1 (q 0.85) — the prefix's 0.875 is higher, so PREFIX wins.
            hit.kind shouldBe MatchKind.PREFIX
            hit.quality shouldBe (7.0 / 8.0 plusOrMinus 1e-12)
        }

        "shelte → shelter: 6/7 is under the floor, so the floor (0.86) — still above the 1-typo 0.85" {
            val hit = resolver().resolveV2("shelte").hit("shelter")!!
            hit.kind shouldBe MatchKind.PREFIX
            hit.quality shouldBe (0.86 plusOrMinus 1e-12)
        }

        "pele → pelex: 1-typo (0.85) and prefix (0.86) ⇒ PREFIX — a prefix beats a single typo (✅LP-8)" {
            val hit = resolver().resolveV2("pele").hit("pelex")!!
            hit.kind shouldBe MatchKind.PREFIX
            hit.distance shouldBe 1
            hit.quality shouldBe (0.86 plusOrMinus 1e-12)
        }

        "valmi → valmy is a TYPO at 0.85" {
            val hit = resolver().resolveV2("valmi").hit("valmy")!!
            hit.kind shouldBe MatchKind.TYPO
            hit.distance shouldBe 1
            hit.quality shouldBe (0.85 plusOrMinus 1e-12)
        }

        "an ED-2 typo on a ≥ 6-char token scores 0.70; the same ED on a 5-char token is out of budget" {
            resolver().resolveV2("repablik").hit("republic")!!.quality shouldBe (0.70 plusOrMinus 1e-12)
            resolver().resolveV2("chzch").hit("czech") shouldBe null
        }

        "ab → abc resolves to NOTHING (budget 0, prefix needs ≥ 3 chars)" {
            resolver().resolveV2("ab").shouldBeEmpty()
        }

        "valmyoil → valmy resolves to NOTHING (ED 3, and a query token longer than c is no prefix)" {
            resolver().resolveV2("valmyoil").hit("valmy") shouldBe null
        }

        "one query token, two vocabulary tokens — a typo of one, a prefix of another ⇒ two entries, correct kinds" {
            // "agro" is ED 1 from "agra" (substitution) and a prefix of "agrofert".
            val r = resolver().resolveV2("agro")
            r.hit("agra")!!.kind shouldBe MatchKind.TYPO
            r.hit("agrofert")!!.kind shouldBe MatchKind.PREFIX
            r.map { it.tokenId }.toSet().size shouldBe r.size
        }

        "results are sorted by (−q, tokenId)" {
            val r = resolver().resolveV2("agro")
            r shouldBe r.sortedWith(compareBy({ -it.quality }, { it.tokenId }))
            r.first().quality shouldBe (0.86 plusOrMinus 1e-12)
        }

        "✅LP-7 — edge punctuation: `oil` is EXACT to both `oil` and `oil,`; a comma'd query finds the bare token" {
            val vocab7 =
                TokenVocabulary(
                    listOf(
                        Candidate.fromValues("A", "Valmy Oil, s.r.o."),
                        Candidate.fromValues("B", "Oil Trade s.r.o."),
                        Candidate.fromValues("C", "Tom & Jerry"),
                    ),
                )
            val r = VocabularyResolver(vocab7)
            r
                .resolveV2("oil")
                .filter { it.kind == MatchKind.EXACT }
                .map { vocab7.tokens[it.tokenId] }
                .toSet() shouldBe
                setOf("oil", "oil,")
            r
                .resolveV2("oil,")
                .filter { it.kind == MatchKind.EXACT }
                .map { vocab7.tokens[it.tokenId] }
                .toSet() shouldBe
                setOf("oil", "oil,")
            // legal-form dots trim at the edges only — `s.r.o` still is not `sro`
            r.resolveV2("s.r.o").single { it.kind == MatchKind.EXACT }.let { vocab7.tokens[it.tokenId] } shouldBe
                "s.r.o."
            // a token that trims to nothing is kept as typed
            EdgeTrim.of("&") shouldBe "&"
            r.resolveV2("&").single().let { vocab7.tokens[it.tokenId] } shouldBe "&"
        }

        "✅LP-9 — an exact hit skips the typo scan but still retrieves its prefixes" {
            val vocab9 =
                TokenVocabulary(
                    listOf(
                        Candidate.fromValues("A", "Vysočina Agro a.s."),
                        Candidate.fromValues("B", "Agrofert, a.s."),
                        Candidate.fromValues("C", "Agra Invest"),
                    ),
                )
            val r = VocabularyResolver(vocab9)
            val hits = r.resolveV2("agro")
            r.lastTypoScan shouldBe IntRange.EMPTY // `agra` (1 typo) is NOT offered past an exact hit
            hits.map { vocab9.tokens[it.tokenId] to it.kind } shouldBe
                listOf("agro" to MatchKind.EXACT, "agrofert," to MatchKind.PREFIX)
        }

        "T5 — the typo neighbourhood is bounded by the budget: len t ± budget(len t)" {
            val r = resolver()
            r.resolveV2("chzch") // 5 chars, budget 1
            r.lastTypoScan shouldBe 4..6
            r.resolveV2("repablik") // 8 chars, budget 2
            r.lastTypoScan shouldBe 6..10
            r.resolveV2("xy") // 2 chars, budget 0 ⇒ no typo scan at all
            r.lastTypoScan shouldBe IntRange.EMPTY
            r.resolveV2("pelex") // exact short-circuits before any scan
            r.lastTypoScan shouldBe IntRange.EMPTY
        }
    })
