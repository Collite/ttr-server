// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** LP-P0·S1 — `fuzzy.match:v2` per-token kinds + the ES-AUTO edit ladder (contracts §4.2). */
class VocabularyResolverV2Spec :
    StringSpec({
        val candidates =
            listOf(
                Candidate.fromValues("A", "Shell Czech Republic"),
                Candidate.fromValues("B", "Agrofert"),
                Candidate.fromValues("C", "Marvy Oil"),
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
            val r = resolver().resolveV2("shell")
            r.size shouldBe 1
            r[0].tokenId shouldBe vocab.idOf("shell")
            r[0].kind shouldBe MatchKind.EXACT
            r[0].quality shouldBe 1.0
        }

        "agro → agrofert is a PREFIX at the 0.80 floor (4/8 < 0.80)" {
            val hit = resolver().resolveV2("agro").hit("agrofert")!!
            hit.kind shouldBe MatchKind.PREFIX
            hit.quality shouldBe (0.80 plusOrMinus 1e-12)
        }

        "a long prefix earns len t / len c above the floor — shelte → shelter = 6/7" {
            val hit = resolver().resolveV2("shelte").hit("shelter")!!
            // Also ED 1 (q 0.85) — the prefix's 6/7 ≈ 0.857 is higher, so PREFIX wins.
            hit.kind shouldBe MatchKind.PREFIX
            hit.quality shouldBe (6.0 / 7.0 plusOrMinus 1e-12)
        }

        "shel → shell: 1-typo (0.85) and prefix (0.80) — contracts §4.2 max q ⇒ TYPO 0.85" {
            // The task list pinned PREFIX 0.8 here; contracts §4.2 ("a token matching by several
            // kinds takes the max q") wins, and 0.85 > 0.80.
            val hit = resolver().resolveV2("shel").hit("shell")!!
            hit.kind shouldBe MatchKind.TYPO
            hit.distance shouldBe 1
            hit.quality shouldBe (0.85 plusOrMinus 1e-12)
        }

        "marvi → marvy is a TYPO at 0.85" {
            val hit = resolver().resolveV2("marvi").hit("marvy")!!
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

        "marvyoil → marvy resolves to NOTHING (ED 3, and a query token longer than c is no prefix)" {
            resolver().resolveV2("marvyoil").hit("marvy") shouldBe null
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
            r.first().quality shouldBe (0.85 plusOrMinus 1e-12)
        }

        "T4 — idfOrMax: idf for a vocabulary token, idfAbsent otherwise" {
            vocab.idfOrMax("shell") shouldBe vocab.idf(vocab.idOf("shell"))
            vocab.idfOrMax("zzzz") shouldBe vocab.idfAbsent
            vocab.idfOrMax("zzzz") shouldNotBe vocab.idfOrMax("shell")
        }

        "T5 — the typo neighbourhood is bounded by the budget: len t ± budget(len t)" {
            val r = resolver()
            r.resolveV2("chzch") // 5 chars, budget 1
            r.lastTypoScan shouldBe 4..6
            r.resolveV2("repablik") // 8 chars, budget 2
            r.lastTypoScan shouldBe 6..10
            r.resolveV2("xy") // 2 chars, budget 0 ⇒ no typo scan at all
            r.lastTypoScan shouldBe IntRange.EMPTY
            r.resolveV2("shell") // exact short-circuits before any scan
            r.lastTypoScan shouldBe IntRange.EMPTY
        }
    })
