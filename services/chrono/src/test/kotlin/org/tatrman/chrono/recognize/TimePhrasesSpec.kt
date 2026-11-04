// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
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

        // Bora 2026-11-03: "the last quarter of a year" is the fourth quarter, read as any yearless numbered quarter.
        "the last quarter of a year is its fourth quarter; the year from its year word, else as a yearless quarter" {
            for ((span, code) in listOf(
                "the last quarter of the year" to "2026Q4",
                "in the last quarter of the fiscal year" to "2026Q4",
                "the last quarter of this year" to "2026Q4",
                "the last quarter of last year" to "2025Q4",
                "poslední čtvrtletí roku" to "2026Q4",
                "v posledním čtvrtletí tohoto roku" to "2026Q4",
                "poslední kvartál loňského roku" to "2025Q4",
                "the last quarter of 2025" to "2025Q4",
            )) {
                val r = read(span)
                withClue(span) {
                    r.periodCode shouldBe code
                    r.alternatives shouldBe emptyList()
                }
            }
            val later = read("the last quarter of the year", may)
            later.periodCode shouldBe "2026Q4"
            later.alternatives.map { it.periodCode } shouldBe listOf("2025Q4")
            read("last quarter").periodCode shouldBe "2026Q3"
            read("poslední čtvrtletí").periodCode shouldBe "2026Q3"
        }

        "after the last quarter of the year, the previous quarter is the third" {
            read(
                "the previous quarter",
                context = listOf("And the previous quarter?", "Revenue in the last quarter of the year"),
            ).periodCode shouldBe "2026Q3"
        }

        "„kvartál“ is a quarter word like „čtvrtletí“; „kvartální“ is a grain" {
            read("tento kvartál").periodCode shouldBe "2026Q4"
            read("minulý kvartál").periodCode shouldBe "2026Q3"
            read("last quarter").periodCode shouldBe "2026Q3"
            rec.recognize("kvartální tržby", ref).shouldBeNull()
        }

        // review-215 R2: two periods of one unit are not one period, the rule `relative` already applies.
        "two numbered periods of one unit, or a numbered and a relative one, are not one period" {
            for (span in listOf(
                "1. a 2. čtvrtletí",
                "první a druhé čtvrtletí",
                "first and second quarter of 2025",
                "v 1. a 2. pololetí 2025",
                "last quarter and Q1",
                "Q1 a Q2 2025",
                "1.–2. čtvrtletí 2025",
            )) {
                withClue(span) { rec.read(span, ref).shouldBeNull() }
            }
            read("letošní 2. čtvrtletí").periodCode shouldBe "2026Q2"
            read("poslední čtvrtletí 2025").periodCode shouldBe "2025Q4"
            read("v 1. pololetí 2025").periodCode shouldBe "2025H1"
        }

        // review-215 RR1 (✅R215-M): a numbered period reads only from a span that names no other period, wherever
        // the other stands and whatever joins them.
        "a numbered period with any other period in its span is no one period" {
            for (span in listOf(
                "the first and the second quarter of 2025",
                "first through third quarter of 2025",
                "1./2. čtvrtletí 2025",
                "1. & 2. čtvrtletí",
                "Q3 and H1 2025",
                "2. čtvrtletí a 1. pololetí 2025",
                "1. pololetí a 2. čtvrtletí 2025",
                "2. čtvrtletí 2025 a 2026",
                "Q2 2025 vs 2024",
                "Q1 a březen 2025",
                "Q1 and last month",
                "Q2 and last week",
                "the second quarter and the third of 2025",
            )) {
                withClue(span) { rec.read(span, ref).shouldBeNull() }
            }
        }

        "a year word says which year a numbered period is in; it is no other period" {
            read("letošní 2. čtvrtletí").periodCode shouldBe "2026Q2"
            read("2. čtvrtletí minulého roku").periodCode shouldBe "2025Q2"
            read("the second quarter of last year").periodCode shouldBe "2025Q2"
            read("the last quarter of 2025").periodCode shouldBe "2025Q4"
            read("first half of 2025").periodCode shouldBe "2025H1"
            read("druhé pololetí").periodCode shouldBe "2026H2"
            read("Q2").periodCode shouldBe "2026Q2"
            read("Who sold more than 2000 units in Q2 2025?").periodCode shouldBe "2025Q2"
        }

        // review-215 RR6 (✅R215-M): a year after "vs" or „a“ names a second period, not this one's year; a count
        // window and year to date are periods too, and year to date asks the same test.
        "a period with a year joined to it, a count window or year to date beside it is no one period" {
            for (span in listOf(
                "Q2 this year vs last year",
                "H1 this year vs last year",
                "2. čtvrtletí letos a loni",
                "1. pololetí letos a loni",
                "letos ve 2. čtvrtletí oproti loňsku",
                "Q2 2026 vs last year",
                "Q2 2025 vs the prior year",
                "Q2 vs last year",
                "Q2 compared with last year",
                "Q2 vs 2024",
                "last year vs Q2",
                "letošní a loňské 2. čtvrtletí",
                "letos v Q2 2025",
                "Q2 this year and last",
                "Q1 and the last 3 months",
                "Q1 vs posledních 30 dní",
                "Q2 vs last 6 weeks",
                "Q2 2025 vs year to date",
                "Q2 vs YTD",
                "Q2 a od začátku roku",
                "year to date vs last year",
                "od začátku roku oproti loňsku",
            )) {
                withClue(span) { rec.read(span, ref).shouldBeNull() }
            }
        }

        "a year word with no joiner still says which year" {
            read("letošní 2. čtvrtletí").periodCode shouldBe "2026Q2"
            for (span in listOf(
                "Q2 last year",
                "loni ve 2. čtvrtletí",
                "the second quarter of last year",
                "Q2, last year",
                "Q2 last year and costs",
                "loni v Q2 2025",
            )) {
                withClue(span) { read(span).periodCode shouldBe "2025Q2" }
            }
            read("the last quarter of last year").periodCode shouldBe "2025Q4"
            read("od začátku roku 2025 do dneška").startInclusive shouldBe day(2025, 1, 1)
            for (span in listOf("letos dosud", "YTD as of today", "od začátku tohoto roku")) {
                withClue(span) { read(span).endExclusive shouldBe day(2026, 11, 2) }
            }
            read("YTD by end of March").endExclusive shouldBe day(2026, 4, 1)
        }

        // A count of weeks or years is no rule's yet; "last 6 weeks" read as last week before RR6.
        "a count of weeks or years is not read; a count of days or months is" {
            for (span in listOf("last 6 weeks", "posledních 6 týdnů", "v posledních 2 týdnech", "past 2 years")) {
                withClue(span) { rec.read(span, ref).shouldBeNull() }
            }
            read("last 3 months").startInclusive shouldBe day(2026, 8, 2)
            read("posledních 30 dní").startInclusive shouldBe day(2026, 10, 3)
        }

        // ---- elapsed ----

        "„uplynulý“ and 'past' name the period that has just elapsed" {
            read("uplynulý měsíc").periodCode shouldBe "202610"
            read("uplynulý rok").startInclusive shouldBe day(2025, 1, 1)
            read("uplynulé čtvrtletí").periodCode shouldBe "2026Q3"
            read("uplynulý týden").startInclusive shouldBe day(2026, 10, 19)
            read("the past month").periodCode shouldBe "202610"
        }

        // review-215 R4: "past due" is the accounting word for overdue, not a week's scope.
        "'past' and „uplynulý“ scope a week only right before the noun" {
            val due = read("invoices past due this week")
            due.startInclusive shouldBe day(2026, 10, 26)
            due.target shouldBe DateTarget.DUE
            read("the past week").startInclusive shouldBe day(2026, 10, 19)
            read("v uplynulém týdnu").startInclusive shouldBe day(2026, 10, 19)
            read("uplynulý týden").startInclusive shouldBe day(2026, 10, 19)
        }

        // ---- trailing: ask (TS-5) ----

        "„poslední měsíc“ asks: the last 30 days, or the calendar month before" {
            val r = read("poslední měsíc")
            r.optionId shouldBe ChronoRecognition.TRAILING
            r.startInclusive shouldBe day(2026, 10, 3) // 30 days ending with the reference day, as „posledních 30 dní“
            r.endExclusive shouldBe day(2026, 11, 2)
            val calendar = r.alternatives.single()
            calendar.optionId shouldBe ChronoRecognition.CALENDAR
            calendar.periodCode shouldBe "202610"
        }

        "a trailing week and year ask the same way; English says 'the last month'" {
            val week = read("poslední týden")
            week.startInclusive shouldBe day(2026, 10, 26)
            week.alternatives.single().startInclusive shouldBe day(2026, 10, 19)
            val year = read("za poslední rok")
            year.startInclusive shouldBe day(2025, 11, 2)
            year.alternatives.single().startInclusive shouldBe day(2025, 1, 1)
            read("the last month").alternatives.single().periodCode shouldBe "202610"
        }

        // review-215 R7: the adjective's case goes with the noun's; a part of a larger period is no trailing window.
        "the trailing ask takes the singular in every case, and nothing that names a part of a larger period" {
            for (span in listOf(
                "posledního měsíce",
                "v posledním měsíci",
                "za poslední rok",
                "posledním týdnem",
                "the last month",
            )) {
                withClue(span) { read(span).optionId shouldBe ChronoRecognition.TRAILING }
            }
            for (span in listOf(
                "poslední měsíce",
                "poslední měsíc roku",
                "the last week of the month",
                "poslední týdny",
            )) {
                withClue(span) {
                    val r = rec.recognize(span, ref)
                    r?.optionId shouldBe null
                    r?.alternatives.orEmpty().map { it.optionId } shouldBe emptyList()
                }
            }
        }

        // review-215 RR2 (✅R215-N): "the last X of a Y" is a part of a larger period, and it is not read — not the
        // trailing ask, not the deictic "last month", not the week rule.
        "the last week, month or year of a larger period is no period" {
            for (span in listOf(
                "the last month of the year",
                "the last month of the quarter",
                "the last week of the month",
                "the last year of the contract",
                "the last week in October",
                "poslední měsíc čtvrtletí",
                "poslední týden v měsíci",
                "poslední měsíc v roce",
                "poslední měsíc tohoto roku",
                "the last month in the quarter",
                "last week of the month",
                "poslední týden října",
            )) {
                withClue(span) { rec.read(span, ref).shouldBeNull() }
            }
            read("last month").periodCode shouldBe "202610"
            read("last month in Brno").periodCode shouldBe "202610"
            for (span in listOf("the last month", "poslední měsíc", "v posledním měsíci", "za poslední rok")) {
                withClue(span) { read(span).optionId shouldBe ChronoRecognition.TRAILING }
            }
            read("the past month").periodCode shouldBe "202610"
            read("the last quarter of 2025").periodCode shouldBe "2025Q4"
        }

        // The earlier question names no month, so "the previous month" counts from the reference: October.
        "a part of a larger period in an earlier question names no period of its unit" {
            read(
                "the previous month",
                context = listOf("And the previous month?", "Revenue in the last month of the year"),
            ).periodCode shouldBe "202610"
        }

        // review-215 R8: chrono asked, and the answer is not in the conversation — the #214 shape.
        "after a trailing ask, the next anaphoric period of its unit has nothing to count from" {
            for (context in listOf(
                listOf("A předchozí měsíc?", "Tržby za poslední měsíc"),
                listOf("And the previous month?", "Revenue for the last month"),
                listOf("A předchozí rok?", "Tržby za poslední rok"),
            )) {
                val span = if (context[0].startsWith("And")) "the previous month" else context[0].drop(2).dropLast(1)
                withClue(
                    context,
                ) { rec.read(span, ref, context = context).shouldBeInstanceOf<ChronoReading.Unanchored>() }
            }
            // "last month" with no article is the calendar month before, a settled October.
            read(
                "the previous month",
                context = listOf("And the previous month?", "Revenue last month"),
            ).periodCode shouldBe
                "202609"
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

        // review-215 R3: the ending is part of the phrase, not the whole of it.
        "year to date ending today is year to date; ending with a month, it runs to that month's end" {
            for ((span, end) in listOf(
                "od začátku roku do dneška" to day(2026, 11, 2),
                "od začátku roku dodnes" to day(2026, 11, 2),
                "year to date as of today" to day(2026, 11, 2),
                "od začátku roku do března" to day(2026, 4, 1),
                "od začátku roku do konce září" to day(2026, 10, 1),
                "YTD through September" to day(2026, 10, 1),
                "od začátku roku 2025 do března" to day(2025, 4, 1),
            )) {
                val r = read(span)
                withClue(span) {
                    r.startInclusive.dayOfYear shouldBe 1
                    r.endExclusive shouldBe end
                    r.alternatives shouldBe emptyList()
                }
            }
            read("od začátku roku 2025 do března").startInclusive shouldBe day(2025, 1, 1)
        }

        // review-215 RR3 (✅R215-O): an ending is a word that names a time; a breakdown is none.
        "year to date with a breakdown after it, or an ending that is now, is year to date" {
            for ((span, end) in listOf(
                "year to date by region" to day(2026, 11, 2),
                "YTD by month" to day(2026, 11, 2),
                "YTD by store and month" to day(2026, 11, 2),
                "od začátku roku do současnosti" to day(2026, 11, 2),
                "od začátku roku do současného dne" to day(2026, 11, 2),
                "YTD so far" to day(2026, 11, 2),
                "year to date until now" to day(2026, 11, 2),
                "YTD by today" to day(2026, 11, 2),
                "YTD by end of March" to day(2026, 4, 1),
                "YTD by the end of March" to day(2026, 4, 1),
                "year to date to end of September" to day(2026, 10, 1),
            )) {
                val r = read(span)
                withClue(span) {
                    r.startInclusive shouldBe day(2026, 1, 1)
                    r.endExclusive shouldBe end
                }
            }
        }

        "year to date with any other ending is no one period" {
            for (span in listOf(
                "od začátku roku do 15. března",
                "year to date through Q2",
                "od začátku roku do konce roku",
                "od začátku roku do konce 2. čtvrtletí",
                "od začátku roku do minulého měsíce",
                "YTD by 15 March",
            )) {
                withClue(span) { rec.read(span, ref).shouldBeNull() }
            }
        }
    })
