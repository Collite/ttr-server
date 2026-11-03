// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/**
 * TS-6 (contracts §5) — the time phrases the resolver's and the Golem's regexes missed, read by chrono's own rule
 * table: one family per block. Reference 2026-11-01 (a Sunday, in the second half and the fourth quarter) unless
 * a case says otherwise; 2026-05-15 is in the first half and the second quarter.
 */
class TimePhrasesSpec :
    StringSpec({
        val rec = DateRecognizer()
        val ref = LocalDate.of(2026, 11, 1)
        val may = LocalDate.of(2026, 5, 15)

        fun day(
            y: Int,
            m: Int,
            d: Int,
        ) = LocalDate.of(y, m, d)

        fun read(
            span: String,
            reference: LocalDate = ref,
            context: List<String> = emptyList(),
        ): ChronoRecognition = withClue(span) { rec.recognize(span, reference, context = context).shouldNotBeNull() }

        // ---- adjective years ----

        "an adjective year is the whole calendar year, in any case form" {
            for ((span, year) in listOf(
                "letošní rok" to 2026,
                "letošního roku" to 2026,
                "loňské" to 2025,
                "v loňském roce" to 2025,
                "předloňský" to 2024,
            )) {
                val r = read(span)
                withClue(span) {
                    r.startInclusive shouldBe day(year, 1, 1)
                    r.endExclusive shouldBe day(year + 1, 1, 1)
                }
            }
        }

        // ---- a month with no year ----

        "a month with no year is that month of the reference year when it is not later than the reference's" {
            read("za červenec").periodCode shouldBe "202607"
            read("od června").periodCode shouldBe "202606"
            read("v září").periodCode shouldBe "202609"
            read("in July").periodCode shouldBe "202607"
            read("for September").periodCode shouldBe "202609"
        }

        "a month later than the reference's asks between this year and last" {
            val r = read("v prosinci")
            r.periodCode shouldBe "202612"
            r.alternatives.map { it.periodCode } shouldBe listOf("202512")
        }

        "a month with no year takes the year the conversation is about" {
            read("v září", context = listOf("A v září?", "Tržby z tržiště v roce 2025")).periodCode shouldBe "202509"
        }

        // ---- half-years ----

        "a half-year with its year is six months, coded yyyyHn" {
            for ((span, code) in listOf(
                "1. pololetí 2026" to "2026H1",
                "druhé pololetí 2025" to "2025H2",
                "H1 2026" to "2026H1",
                "first half of 2026" to "2026H1",
                "the second half-year of 2025" to "2025H2",
            )) {
                val r = read(span)
                withClue(span) {
                    r.kind shouldBe ChronoKind.PERIOD
                    r.periodCode shouldBe code
                    r.endExclusive shouldBe r.startInclusive.plusMonths(6)
                }
            }
            read("1. pololetí 2026").startInclusive shouldBe day(2026, 1, 1)
            read("H2 2026").startInclusive shouldBe day(2026, 7, 1)
        }

        "a yearless half-year reads as a yearless month: the reference year, or ask when it is later" {
            read("1. pololetí").periodCode shouldBe "2026H1"
            read("druhé pololetí").periodCode shouldBe "2026H2" // the reference's own half
            val later = read("2. pololetí", may)
            later.periodCode shouldBe "2026H2"
            later.alternatives.map { it.periodCode } shouldBe listOf("2025H2")
        }

        "a yearless half-year leans on the conversation's year" {
            read(
                "ve 2. pololetí",
                context = listOf("A ve 2. pololetí?", "Tržby z tržiště v 1. pololetí 2025"),
            ).periodCode shouldBe "2025H2"
        }

        "'pololetní' and an English ordinal half with no year are no half-year" {
            rec.recognize("pololetní tržby", ref).shouldBeNull()
            rec.recognize("the first half", ref).shouldBeNull()
        }

        // ---- quarters ----

        "a numbered quarter is that quarter, coded yyyyQn" {
            val q2 = read("2. čtvrtletí 2026")
            q2.startInclusive shouldBe day(2026, 4, 1)
            q2.endExclusive shouldBe day(2026, 7, 1)
            q2.periodCode shouldBe "2026Q2"
            read("Q2 2026").periodCode shouldBe "2026Q2"
            read("třetí kvartál 2025").periodCode shouldBe "2025Q3"
            read("ve 2. čtvrtletí").periodCode shouldBe "2026Q2"
        }

        "a yearless quarter later than the reference's asks between this year and last" {
            val q3 = read("Q3", may)
            q3.periodCode shouldBe "2026Q3"
            q3.alternatives.map { it.periodCode } shouldBe listOf("2025Q3")
        }

        "a yearless quarter takes the year the span says in words" {
            read("letošní 2. čtvrtletí").periodCode shouldBe "2026Q2"
            read("ve 2. čtvrtletí loňského roku").periodCode shouldBe "2025Q2"
        }

        "„kvartál“ is a quarter word like „čtvrtletí“; „kvartální“ is a grain" {
            read("tento kvartál").periodCode shouldBe "2026Q4"
            read("minulý kvartál").periodCode shouldBe "2026Q3"
            read("last quarter").periodCode shouldBe "2026Q3"
            rec.recognize("kvartální tržby", ref).shouldBeNull()
        }

        // ---- elapsed ----

        "„uplynulý“ and 'past' name the period that has just elapsed" {
            read("uplynulý měsíc").periodCode shouldBe "202610"
            read("uplynulý rok").startInclusive shouldBe day(2025, 1, 1)
            read("uplynulé čtvrtletí").periodCode shouldBe "2026Q3"
            read("uplynulý týden").startInclusive shouldBe day(2026, 10, 19)
            read("the past month").periodCode shouldBe "202610"
        }

        // ---- trailing: ask (TS-5) ----

        "„poslední měsíc“ asks: the last 30 days, or the calendar month before" {
            val r = read("poslední měsíc")
            r.optionId shouldBe ChronoRecognition.TRAILING
            r.startInclusive shouldBe day(2026, 10, 2) // as „posledních 30 dní“ reads, ending with the reference day
            r.endExclusive shouldBe day(2026, 11, 2)
            val calendar = r.alternatives.single()
            calendar.optionId shouldBe ChronoRecognition.CALENDAR
            calendar.periodCode shouldBe "202610"
        }

        "a trailing week and year ask the same way; English says 'the last month'" {
            val week = read("poslední týden")
            week.startInclusive shouldBe day(2026, 10, 25)
            week.alternatives.single().startInclusive shouldBe day(2026, 10, 19)
            val year = read("za poslední rok")
            year.startInclusive shouldBe day(2025, 11, 1)
            year.alternatives.single().startInclusive shouldBe day(2025, 1, 1)
            read("the last month").alternatives.single().periodCode shouldBe "202610"
        }

        "a count, a quarter or an adjective is no trailing ask" {
            read("posledních 30 dní").alternatives shouldBe emptyList()
            read("poslední 3 měsíce").alternatives shouldBe emptyList()
            read("poslední čtvrtletí").periodCode shouldBe "2026Q3"
            read("last month").alternatives shouldBe emptyList() // no article: the calendar month before
            rec.recognize("poslední celý měsíc", ref).shouldBeNull()
        }

        // ---- year to date ----

        "year to date runs from the first of January through the reference day" {
            for (span in listOf("od začátku roku", "letos dosud", "year to date", "YTD", "year-to-date")) {
                val r = read(span)
                withClue(span) {
                    r.startInclusive shouldBe day(2026, 1, 1)
                    r.endExclusive shouldBe day(2026, 11, 2)
                }
            }
        }
    })
