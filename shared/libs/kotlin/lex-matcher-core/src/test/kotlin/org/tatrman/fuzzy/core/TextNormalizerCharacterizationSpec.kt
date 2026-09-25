// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.text.Normalizer

/**
 * RG-P0.S3.T3 — proves the ≥5-copies fold consolidation is behavior-preserving:
 * after `TextNormalizer.fold` was repointed at the shared `org.tatrman.text`
 * lib, it must produce output IDENTICAL to the old inline implementation on the
 * S1/S2 corpora sample. `legacyFold` below is a verbatim copy of the pre-S3
 * implementation (lowercase → NFD → strip \p{Mn}); if the shared lib ever drifts,
 * this fails.
 */
class TextNormalizerCharacterizationSpec :
    FunSpec({

        // verbatim pre-S3 implementation
        val combiningMarks = Regex("\\p{Mn}+")

        fun legacyFold(input: String): String =
            Normalizer
                .normalize(input.lowercase(), Normalizer.Form.NFD)
                .replace(combiningMarks, "")

        // sample drawn from the S1 (seed.jsonl) + S2 (ucetnictvi / hero) Czech corpora
        val sample =
            listOf(
                "Kolik jsme utržili za Octavie v pražských pobočkách za poslední fiskální čtvrtletí?",
                "Zákazník",
                "zákazníka Pelex UK",
                "Seznam všech dodavatelů z Německa",
                "Zákazníci z Prahy a Brna",
                "Souhrn prodejů za leden 2024",
                "Vypiš účetní záznamy pro středisko QT ORLAK za období 2026.03",
                "ukazatel QT KANCELAR",
                "Kancelář QT",
                "Náklady",
                "Mzdy",
                "tržba",
                "obrat",
                "Moravskoslezském kraji",
                "iPhone 15",
                "Bosch",
                "Siemens",
                "ěščřžýáíé úůňťď",
                "PŘÍLIŠ ŽLUŤOUČKÝ KŮŇ",
                "faktura 12345",
                "",
            )

        context("fold matches the legacy inline implementation exactly") {
            sample.forEach { s ->
                test("characterize: ${s.take(32).ifEmpty { "<empty>" }}") {
                    TextNormalizer.fold(s) shouldBe legacyFold(s)
                }
            }
        }
    })
