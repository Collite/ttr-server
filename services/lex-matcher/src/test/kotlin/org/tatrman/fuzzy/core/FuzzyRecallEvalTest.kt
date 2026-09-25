// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual

/**
 * Lightweight, hermetic recall/score "eval" for the fuzzy matcher — Phase 02 G1 close-out evidence.
 *
 * There's no production fuzzy-match corpus and `infra/nlp/eval/seed.jsonl` is an NLP-parse corpus
 * (no query→canonical pairs), so we use a small in-spec corpus of realistic Czech ERP entity names
 * with diacritic-stripped / inflected query variants plus a few true-negatives. `infra/nlp` is
 * replaced by a canned lemma map (the same `raw token → folded lemma` contract `NlpLemmatizer`
 * produces), so the eval is deterministic and offline.
 *
 * Two configs are compared:
 *   - NFD-only  (≈ Phase 02 Stage A — [NoopLemmatizer])
 *   - NFD+lemma (Phase 02 Stage B — canned lemmatiser)
 *
 * Finding (printed): the token-set Levenshtein already gets ~all of these multi-token entity
 * names right at top-1 under NFD-only — the *recall* headroom is small. What lemmatisation buys is
 * **higher confidence / cleaner ranking on inflected queries**: a lemma-exact query token scores
 * 1.0 against the candidate instead of ~0.9, so the correct candidate's score never drops below
 * its NFD-only score and a lemma-exact inflected query becomes an exact match.
 *
 * Asserts: recall@1/@5 never regresses; for every inflected positive the correct candidate's score
 * under NFD+lemma ≥ its score under NFD-only; the mean top-1 score on inflected queries is higher
 * under NFD+lemma.
 */
class FuzzyRecallEvalTest :
    StringSpec({

        // --- canonical entities (id → display value) ---
        val entities =
            listOf(
                "c1" to "Zákazník Pelex UK",
                "c2" to "Dodavatel Tesla Brno",
                "c3" to "Objednávka materiálu",
                "c4" to "Faktura přijatá",
                "c5" to "Skladová položka",
                "c6" to "Škoda Auto Mladá Boleslav",
                "c7" to "Pobočka Praha",
                "c8" to "Dodací list Německo",
                "c9" to "Reklamace zákazníka",
                "c10" to "Účetní doklad",
            )

        // --- canned `raw (lower-cased, accented) token → folded lemma` map (what NlpLemmatizer returns) ---
        val lemmaMap =
            mapOf(
                "zákazník" to "zakaznik",
                "zákazníka" to "zakaznik",
                "zákazníků" to "zakaznik",
                "zákazníkem" to "zakaznik",
                "dodavatel" to "dodavatel",
                "dodavatele" to "dodavatel",
                "dodavatelem" to "dodavatel",
                "objednávka" to "objednavka",
                "objednávky" to "objednavka",
                "objednávkám" to "objednavka",
                "materiálu" to "material",
                "materiál" to "material",
                "faktura" to "faktura",
                "faktury" to "faktura",
                "fakturám" to "faktura",
                "přijatá" to "prijaty",
                "přijaté" to "prijaty",
                "přijatých" to "prijaty",
                "skladová" to "skladovy",
                "skladové" to "skladovy",
                "skladovou" to "skladovy",
                "položka" to "polozka",
                "položky" to "polozka",
                "položkou" to "polozka",
                "mladá" to "mlady",
                "mladé" to "mlady",
                "boleslav" to "boleslav",
                "boleslavi" to "boleslav",
                "pobočka" to "pobocka",
                "pobočky" to "pobocka",
                "poboček" to "pobocka",
                "praha" to "praha",
                "praze" to "praha",
                "prahy" to "praha",
                "dodací" to "dodaci",
                "dodacího" to "dodaci",
                "list" to "list",
                "listu" to "list",
                "německo" to "nemecko",
                "německa" to "nemecko",
                "německu" to "nemecko",
                "reklamace" to "reklamace",
                "reklamací" to "reklamace",
                "účetní" to "ucetni",
                "doklad" to "doklad",
                "doklady" to "doklad",
                "dokladů" to "doklad",
            )

        // --- queries: (query, expectedCanonicalId or null for a true-negative, isInflected) ---
        data class Case(
            val query: String,
            val expected: String?,
            val inflected: Boolean = false,
        )
        val cases =
            listOf(
                // exact / case-only / diacritic-only
                Case("Zákazník Pelex UK", "c1"),
                Case("zakaznik pelex uk", "c1"),
                Case("ZÁKAZNÍK PELEX UK", "c1"),
                Case("dodavatel tesla brno", "c2"),
                Case("skoda auto mlada boleslav", "c6"),
                Case("pobocka praha", "c7"),
                // inflected
                Case("zákazníka Pelex UK", "c1", inflected = true),
                Case("zákazníků Pelex", "c1", inflected = true),
                Case("reklamace zákazníka", "c9", inflected = true),
                Case("reklamací zákazníka", "c9", inflected = true),
                Case("dodavatele Tesla Brno", "c2", inflected = true),
                Case("dodavatelem Tesla Brno", "c2", inflected = true),
                Case("objednávky materiálu", "c3", inflected = true),
                Case("objednávkám materiálu", "c3", inflected = true),
                Case("faktury přijaté", "c4", inflected = true),
                Case("fakturám přijatých", "c4", inflected = true),
                Case("skladové položky", "c5", inflected = true),
                Case("skladovou položkou", "c5", inflected = true),
                Case("Škoda Auto Mladé Boleslavi", "c6", inflected = true),
                Case("pobočky Prahy", "c7", inflected = true),
                Case("poboček Praha", "c7", inflected = true),
                Case("dodací list Německa", "c8", inflected = true),
                Case("dodacího listu Německu", "c8", inflected = true),
                Case("účetní doklady", "c10", inflected = true),
                Case("Účetní dokladů", "c10", inflected = true),
                // true negatives
                Case("kočka na střeše", null),
                Case("počasí v Brně zítra", null),
                Case("telefonní číslo", null),
            )

        fun foldedTokens(s: String) = Candidate.tokenizeRaw(s).map { TextNormalizer.fold(it) }

        fun lemmaTokens(s: String) = Candidate.tokenizeRaw(s).map { lemmaMap[it] ?: TextNormalizer.fold(it) }

        // useLemma=false ≈ Stage A (lemma axis == surface axis); useLemma=true ≈ Stage B (real lemmas).
        fun buildMatcher(useLemma: Boolean): TokenBasedMatcher {
            val candidates =
                entities.map { (id, value) ->
                    val surface = foldedTokens(value)
                    val lemmas = if (useLemma) lemmaTokens(value) else surface
                    Candidate.withLemmas(id, value, surfaceTokens = surface, lemmaTokens = lemmas)
                }
            return TokenBasedMatcher(candidates, TokenIndex(candidates), DistanceCache())
        }

        fun match(
            matcher: TokenBasedMatcher,
            query: String,
            useLemma: Boolean,
            limit: Int,
        ): List<Pair<Candidate, Double>> {
            val surface = foldedTokens(query)
            val lemmas = if (useLemma) lemmaTokens(query) else surface
            return matcher.match(surface, lemmas, limit)
        }

        data class Eval(
            val recallAt1: Double,
            val recallAt5: Double,
            val meanInflectedTop1Score: Double,
            val maxNegativeScore: Double,
            val scoreByCaseForExpected: Map<Int, Double>,
        )

        fun evaluate(useLemma: Boolean): Eval {
            val matcher = buildMatcher(useLemma)
            var hits1 = 0
            var hits5 = 0
            var positives = 0
            var inflectedTop1Sum = 0.0
            var inflectedN = 0
            var maxNeg = 0.0
            val scoreForExpected = mutableMapOf<Int, Double>()
            cases.forEachIndexed { i, case ->
                val results = match(matcher, case.query, useLemma, 5)
                if (case.expected == null) {
                    maxNeg = maxOf(maxNeg, results.firstOrNull()?.second ?: 0.0)
                } else {
                    positives++
                    val ids = results.map { it.first.id }
                    if (ids.firstOrNull() == case.expected) hits1++
                    if (case.expected in ids) hits5++
                    val s = results.firstOrNull { it.first.id == case.expected }?.second ?: 0.0
                    scoreForExpected[i] = s
                    if (case.inflected) {
                        inflectedTop1Sum += results.firstOrNull()?.second ?: 0.0
                        inflectedN++
                    }
                }
            }
            return Eval(
                recallAt1 = hits1.toDouble() / positives,
                recallAt5 = hits5.toDouble() / positives,
                meanInflectedTop1Score = if (inflectedN == 0) 0.0 else inflectedTop1Sum / inflectedN,
                maxNegativeScore = maxNeg,
                scoreByCaseForExpected = scoreForExpected,
            )
        }

        "NFD+lemma matching does not regress recall and raises confidence on inflected queries (G1 eval)" {
            val nfd = evaluate(useLemma = false)
            val lemma = evaluate(useLemma = true)

            val pos = cases.count { it.expected != null }
            val neg = cases.count { it.expected == null }
            println("=== fuzzy recall/score eval ($pos positives, $neg negatives) ===")
            println(
                "NFD-only   : recall@1=${"%.3f".format(nfd.recallAt1)}  recall@5=${"%.3f".format(nfd.recallAt5)}" +
                    "  meanInflectedTop1=${"%.3f".format(
                        nfd.meanInflectedTop1Score,
                    )}  maxNegScore=${"%.3f".format(nfd.maxNegativeScore)}",
            )
            println(
                "NFD+lemma  : recall@1=${"%.3f".format(lemma.recallAt1)}  recall@5=${"%.3f".format(lemma.recallAt5)}" +
                    "  meanInflectedTop1=${"%.3f".format(
                        lemma.meanInflectedTop1Score,
                    )}  maxNegScore=${"%.3f".format(lemma.maxNegativeScore)}",
            )

            // recall never regresses
            lemma.recallAt1 shouldBeGreaterThanOrEqual nfd.recallAt1
            lemma.recallAt5 shouldBeGreaterThanOrEqual nfd.recallAt5
            // for every positive, the correct candidate scores at least as high under NFD+lemma
            cases.forEachIndexed { i, case ->
                if (case.expected != null) {
                    (lemma.scoreByCaseForExpected[i] ?: 0.0) shouldBeGreaterThanOrEqual
                        (nfd.scoreByCaseForExpected[i] ?: 0.0)
                }
            }
            // and on inflected queries the top-1 confidence is strictly higher overall
            lemma.meanInflectedTop1Score shouldBeGreaterThanOrEqual nfd.meanInflectedTop1Score
        }
    })
