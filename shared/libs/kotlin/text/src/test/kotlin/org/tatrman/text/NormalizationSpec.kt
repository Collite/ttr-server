// SPDX-License-Identifier: Apache-2.0
package org.tatrman.text

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Golden vectors for the S-2 fold (RG-P0.S3.T1). The fold spec is the cross-cutting
 * determinism contract (contracts §6 S-2): every matcher folds byte-identically.
 * These vectors ARE the fixture — change a vector only after recomputing by hand.
 */
class NormalizationSpec :
    FunSpec({

        // input -> expected folded output (lowercase -> NFD -> strip combining marks)
        val golden =
            listOf(
                // Czech diacritics from the hero sentence + design examples
                "Zákazník" to "zakaznik",
                "Octavie" to "octavie",
                "pražských" to "prazskych",
                "tržba" to "trzba",
                "pobočkách" to "pobockach",
                "účetní středisko" to "ucetni stredisko",
                "Náměstí Míru" to "namesti miru",
                // mixed case
                "PRAHA" to "praha",
                "QT ORLAK" to "qt orlak",
                "MaJeTeK" to "majetek",
                // full Czech diacritic set
                "ěščřžýáíéúůňťď" to "escrzyaieuuntd",
                "ĚŠČŘŽÝÁÍÉÚŮŇŤĎ" to "escrzyaieuuntd",
                // ascii passthrough + digits/punct untouched
                "faktura 12345" to "faktura 12345",
                "FAP-2026" to "fap-2026",
                // empty
                "" to "",
            )

        context("fold produces the expected canonical form") {
            golden.forEach { (input, expected) ->
                test("fold(${input.ifEmpty { "<empty>" }}) == $expected") {
                    Normalization.fold(input) shouldBe expected
                }
            }
        }

        context("fold is idempotent: fold(fold(x)) == fold(x)") {
            golden.map { it.first }.forEach { input ->
                test("idempotent on ${input.ifEmpty { "<empty>" }}") {
                    val once = Normalization.fold(input)
                    Normalization.fold(once) shouldBe once
                }
            }
        }

        test("a precomposed char and its decomposed form fold identically") {
            val precomposed = "é" // é
            val decomposed = "é" // e + combining acute
            Normalization.fold(precomposed) shouldBe Normalization.fold(decomposed)
            Normalization.fold(precomposed) shouldBe "e"
        }
    })
