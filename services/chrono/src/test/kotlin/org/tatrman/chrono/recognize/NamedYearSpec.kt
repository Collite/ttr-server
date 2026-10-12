// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/**
 * Which years a text names — the evidence a yearless date leans on. Reference pinned to
 * 2026-05-15, as in [DateRecognizerSpec].
 */
class NamedYearSpec :
    StringSpec({
        val ref = LocalDate.of(2026, 5, 15)

        fun years(text: String) = NamedYear.yearsIn(text, ref)

        // ---- a written year, where the words around it make it one ----
        "after a month, in any case form and with or without a day" {
            years("Které položky byly v říjnu 2025 vyprodané?") shouldBe setOf(2025)
            years("Tržby od 15. října 2025") shouldBe setOf(2025)
            years("Sales in October 2025") shouldBe setOf(2025)
            years("Sales since October 15, 2025") shouldBe setOf(2025)
            years("Sales since Oct. 2025") shouldBe setOf(2025)
        }
        "after a year word" {
            years("Tržby podle prodejny v roce 2025") shouldBe setOf(2025)
            years("Tržby za rok 2024") shouldBe setOf(2024)
            years("Revenue for fiscal year 2025") shouldBe setOf(2025)
            years("Revenue FY2025") shouldBe setOf(2025)
        }
        "after a preposition only a time takes" {
            years("Store sales by city in 2025") shouldBe setOf(2025)
            years("Returns during 2024 by store") shouldBe setOf(2024)
            years("Tržby během 2025") shouldBe setOf(2025)
        }
        "not after one an amount takes" {
            years("Items for 2000 dollars").shouldBeEmpty()
            years("Položky do 2000 Kč").shouldBeEmpty()
        }
        "inside a full date" {
            years("Objednávky od 1.10.2025") shouldBe setOf(2025)
            years("Orders since 2025-10-01") shouldBe setOf(2025)
            years("Orders in 2025-10") shouldBe setOf(2025)
        }

        // ---- what is not a year ----
        "a bare four-digit number is not read — it looks exactly like an amount" {
            years("Položky nad 2000 Kč").shouldBeEmpty()
            years("Products priced over 2025 dollars").shouldBeEmpty()
            years("Tržby 2025").shouldBeEmpty()
        }
        "a word that is not a month does not make the number after it a year" {
            years("Prodejna 2001 v Praze").shouldBeEmpty()
        }
        "a run outside the plausible range is no year" {
            years("v roce 1066").shouldBeEmpty()
        }
        "a six-digit code is not a year" {
            years("doklad 202510").shouldBeEmpty()
        }

        // ---- a year said in words, relative to the reference ----
        "this year, last year, two back — cs and en" {
            years("Tržby letos") shouldBe setOf(2026)
            years("letošní tržby") shouldBe setOf(2026)
            years("v tomto roce") shouldBe setOf(2026)
            years("this year") shouldBe setOf(2026)
            years("Kolik vratek bylo loni?") shouldBe setOf(2025)
            years("vloni v zimě") shouldBe setOf(2025)
            years("loňské tržby") shouldBe setOf(2025)
            years("v minulém roce") shouldBe setOf(2025)
            years("last year") shouldBe setOf(2025)
            years("předloni") shouldBe setOf(2024)
            years("předloňské tržby") shouldBe setOf(2024)
        }
        "a year said as an adjective, in the case the sentence puts it" {
            years("letošní prodeje") shouldBe setOf(2026)
            years("Porovnej s letošními prodeji") shouldBe setOf(2026)
            years("tohoroční obrat") shouldBe setOf(2026)
            years("tohoročních objednávek") shouldBe setOf(2026)
            years("loňské výpadky zásob") shouldBe setOf(2025)
            years("v loňském říjnu") shouldBe setOf(2025)
            years("loňských tržeb") shouldBe setOf(2025)
            years("vloňské vratky") shouldBe setOf(2025)
            years("minuloroční prodeje") shouldBe setOf(2025)
            years("minuloročního obratu") shouldBe setOf(2025)
            years("předloňské výpadky") shouldBe setOf(2024)
            years("předminuloroční tržby") shouldBe setOf(2024)
            years("last year's sales") shouldBe setOf(2025)
        }
        "an adjective with „rok“ in any case" {
            years("tržby minulého roku") shouldBe setOf(2025)
            years("v předešlém roce") shouldBe setOf(2025)
            years("předešlého roku") shouldBe setOf(2025)
            years("v předchozím roce") shouldBe setOf(2025)
            years("předminulého roku") shouldBe setOf(2024)
        }
        "„roční“ alone is annual, not a year" {
            years("roční tržby").shouldBeEmpty()
        }
        "„předminuloroční“ is two back, not also one back" {
            years("předminuloroční tržby") shouldBe setOf(2024)
        }
        "„předloni“ is two back, not also one back" {
            years("Tržby předloni") shouldBe setOf(2024)
        }
        "„minulý měsíc“ names no year" {
            years("Tržby za minulý měsíc").shouldBeEmpty()
        }

        // ---- one, several, none ----
        "several years are several, not the first of them" {
            years("Porovnej říjen 2024 s říjnem 2025") shouldContainExactlyInAnyOrder listOf(2024, 2025)
            NamedYear.single("Porovnej říjen 2024 s říjnem 2025", ref).shouldBeNull()
        }
        "a year written and a year said that agree are one year" {
            NamedYear.single("loni, tedy v roce 2025", ref) shouldBe 2025
        }

        // ---- the nearest text that names a year decides ----
        "the first text naming a year wins; texts naming none are passed over" {
            NamedYear.firstOf(listOf("a v září?", "Tržby v roce 2025", "Tržby v roce 2024"), ref) shouldBe 2025
        }
        "a text naming several stops the search — an older one does not settle it" {
            NamedYear.firstOf(listOf("Porovnej říjen 2024 s říjnem 2025", "Tržby v roce 2023"), ref).shouldBeNull()
        }
        "no text naming a year — no year" {
            NamedYear.firstOf(listOf("a v září?", "Kolik máme prodejen?"), ref).shouldBeNull()
            NamedYear.firstOf(emptyList(), ref).shouldBeNull()
        }
    })
