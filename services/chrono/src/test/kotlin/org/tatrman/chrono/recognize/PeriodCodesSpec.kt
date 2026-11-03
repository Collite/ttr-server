// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** review-215 RR5 — the grain a period table's `code_format` names, and which codes fit it. */
class PeriodCodesSpec :
    StringSpec({
        "a format names a month, a quarter or a half-year — or no grain chrono reads" {
            for ((format, grain) in listOf(
                "yyyyMM" to PeriodCodes.Grain.MONTH,
                "yyyy-MM" to PeriodCodes.Grain.MONTH,
                "yyyy/MM" to PeriodCodes.Grain.MONTH,
                "yyyy.MM" to PeriodCodes.Grain.MONTH,
                "" to PeriodCodes.Grain.MONTH,
                "yyyyQn" to PeriodCodes.Grain.QUARTER,
                "yyyy-Qn" to PeriodCodes.Grain.QUARTER,
                "yyyyHn" to PeriodCodes.Grain.HALF,
                "yyyy" to null,
                "yyyyMMdd" to null,
                "yyyy-ww" to null,
            )) {
                withClue(format) { PeriodCodes.grainOfFormat(format) shouldBe grain }
            }
        }

        "a code fits only a format of its own grain" {
            PeriodCodes.fits("202605", "yyyy-MM") shouldBe true
            PeriodCodes.fits("2026Q2", "yyyyQn") shouldBe true
            PeriodCodes.fits("2026Q2", "yyyyMM") shouldBe false
            PeriodCodes.fits("202605", "yyyy") shouldBe false
            PeriodCodes.fits("202605", "yyyyMMdd") shouldBe false
        }
    })
