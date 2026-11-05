// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.text.Normalization
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

        // review-215 RR8: an article may stand before the year word; a year chrono cannot name is no year to fall back
        // from, so the span is not read, and not as the quarter before either.
        "the last quarter of the previous, prior, past or current year is its fourth; of one chrono cannot name, none" {
            for ((span, code) in listOf(
                "the last quarter of the previous year" to "2025Q4",
                "the last quarter of the prior year" to "2025Q4",
                "the last quarter of the past year" to "2025Q4",
                "the last quarter of the current year" to "2026Q4",
                "the last quarter of the year 2025" to "2025Q4",
            )) {
                withClue(span) { read(span).periodCode shouldBe code }
            }
            for (span in listOf(
                "the last quarter of next year",
                "the last quarter of the following year",
                "poslední čtvrtletí příštího roku",
                "poslední čtvrtletí následujícího roku",
                "the last quarter of each year",
                "the last quarter of every year",
                "poslední čtvrtletí každého roku",
                "the last quarter of the year-over-year growth",
                "the last quarter of the last year",
            )) {
                withClue(span) { rec.read(span, ref).shouldBeNull() }
            }
            read("sales last quarter in Brno").periodCode shouldBe "2026Q3"
        }

        "after the last quarter of the previous year, the previous quarter is that year's third" {
            read(
                "the previous quarter",
                context = listOf("And the previous quarter?", "Revenue in the last quarter of the previous year"),
            ).periodCode shouldBe "2025Q3"
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

        // review-215 RR12 (✅R215-M): the commonest year-on-year comparison puts a noun before its year, and one with no
        // year word ("vs a year ago") still compares two periods.
        "a comparison whose other side names a time is no one period" {
            for (span in listOf(
                "Q2 vs the same quarter last year",
                "Q2 compared to the same period last year",
                "Q2 versus the same quarter of last year",
                "2. čtvrtletí oproti stejnému období loni",
                "2. čtvrtletí oproti stejnému čtvrtletí loňského roku",
                "2. čtvrtletí ve srovnání se stejným obdobím loňského roku",
                "H1 vs the same half last year",
                "Q3 vs the same period of the previous year",
                "the last month of the year vs the same month last year",
                "Q2 compared with the same quarter of 2025",
                "Q2 2025 compared to a year earlier",
                "Q2 vs a year ago",
                "Q2 vs prior period",
                "the same quarter last year vs Q2",
                "YTD vs the same period last year",
                "year to date compared to the same period last year",
                "od začátku roku ve srovnání se stejným obdobím loni",
            )) {
                withClue(span) { rec.read(span, ref).shouldBeNull() }
            }
        }

        "a comparison with no time on its other side, a year-over-year measure and a conjunction are unchanged" {
            for (span in listOf(
                "Q2 sales vs target",
                "2. čtvrtletí oproti plánu",
                "Q2 YoY",
                "Q2 year over year",
                "2. čtvrtletí meziročně",
            )) {
                withClue(span) { read(span).periodCode shouldBe "2026Q2" }
            }
            for (span in listOf("revenue and costs last year in Q2", "tržby a náklady loni ve 2. čtvrtletí")) {
                withClue(span) { read(span).periodCode shouldBe "2025Q2" }
            }
        }

        // review-215 RR13 (#213, A-TS-3): a year the numbered rule cannot read is no year to fall back from.
        "a quarter, half or last month in a year chrono cannot read is not read" {
            for (span in listOf(
                "Q2 next year",
                "the second quarter of next year",
                "2. čtvrtletí příštího roku",
                "druhé čtvrtletí příštího roku",
                "H1 next year",
                "první pololetí příštího roku",
                "H2 of the next year",
                "Q2 the following year",
                "Q3 of the coming year",
                "the second quarter of the year after",
                "Q2 of each year",
                "každé 2. čtvrtletí",
                "Q1 every year",
                "Q2 a year ago",
                "Q2 one year ago",
                "Q2 a year earlier",
                "2. čtvrtletí před rokem",
                "H1 a year ago",
                "Q2 in 2 years",
                "Q2 this year or next year",
                "Q2 2025 and next year",
                "Q2 for 2025",
                "Q2 sales 2025",
                "revenue 2025 in Q2",
                "tržby 2025 za 2. čtvrtletí",
                "Q2 fiscal 2025",
                "H1 of fiscal 2025",
                "H2 fiscal 2025",
                "the second half of the year for 2025",
                "the last half of the year for 2025",
                "the last month of the year for 2025",
                "YTD next year",
                "YTD a year ago",
            )) {
                withClue(span) { rec.read(span, ref).shouldBeNull() }
            }
            // The accepted cost: a number no pattern places may be an amount, and is read as a year chrono cannot place.
            rec.read("Q2 for 2000 customers", ref).shouldBeNull()
        }

        "a quarter of a year named by a word chrono does not read is not read" {
            for (span in listOf(
                "the second quarter of the contract year",
                "Q2 of the school year",
                "Q2 of the same year",
                "H1 of the model year",
            )) {
                withClue(span) { rec.read(span, ref).shouldBeNull() }
            }
            for (span in listOf(
                "Q2 for the year",
                "the second quarter of the fiscal year",
                "Q2 revenue year over year",
                "ve 2. čtvrtletí kalendářního roku",
                "2. čtvrtletí hospodářského roku",
            )) {
                withClue(span) { read(span).periodCode shouldBe "2026Q2" }
            }
        }

        // review-215 RR17 (S0.3 T8): a yearless period takes a fallback year only from a span that says nothing else
        // about a year — an allow-list (YearPhrases.speaksOfYear), not a list of the phrases that stop it.
        "a yearless period in a span that says anything else about a year is not read" {
            for (span in listOf(
                // a year noun or adverb, plural, a breakdown
                "Q2 in previous years",
                "Q2 in all years",
                "Q2 of past years",
                "Q2 in prior years",
                "Q2 over the years",
                "Q2 in recent years",
                "Q2 across years",
                "Q2 annually",
                "Q2 yearly",
                "Q2 of the year ahead",
                "Q2 sales by year",
                "Q2 by year",
                "Q2 sales per year",
                "H1 in previous years",
                "2. čtvrtletí o rok dříve",
                "2. čtvrtletí o rok později",
                "2. čtvrtletí o rok zpět",
                "2. čtvrtletí rok předtím",
                "2. čtvrtletí rok poté",
                "2. čtvrtletí za rok",
                "2. čtvrtletí za dva roky",
                "každoročně ve 2. čtvrtletí",
                "2. čtvrtletí každoročně",
                "2. čtvrtletí ročně",
                "2. čtvrtletí v minulých letech",
                "2. čtvrtletí posledních let",
                "2. čtvrtletí všech let",
                "2. čtvrtletí předchozích let",
                "2. čtvrtletí po letech",
                "2. čtvrtletí podle let",
                "1. pololetí o rok dříve",
                "1. pololetí každoročně",
                // a two-digit year
                "Q2 FY25",
                "FY25 Q2",
                "Q2 '25",
                "Q2'25",
                "Q2/25",
                "Q2-25",
                "Q2 25",
                "2. čtvrtletí 25",
                "H1 FY25",
                "H2 '25",
                "H1'25",
                // a relative word before the period
                "next Q2",
                "the coming Q1",
                "the upcoming H1",
                "next H1",
                "next Q4",
                "last Q4",
                "Q2 before last",
                "příští 2. čtvrtletí",
                // a plural noun
                "ve 2. čtvrtletích",
                "ve 2. kvartálech",
                "v 1. pololetích",
                // a year apart from a yearless last quarter
                "poslední čtvrtletí roku o rok dříve",
            )) {
                withClue(span) { rec.read(span, ref).shouldBeNull() }
            }
            for (span in listOf("last Q2", "previous Q2", "minulé 2. čtvrtletí")) {
                withClue(span) { rec.read(span, may).shouldBeNull() }
            }
            for (span in listOf(
                "YTD FY25",
                "YTD '25",
                "FY25 YTD",
                "YTD annually",
                "YTD in previous years",
                "od začátku roku o rok dříve",
            )) {
                withClue(span) { rec.read(span, ref).shouldBeNull() }
            }
        }

        "a yearless period beside a neutral year frame still takes the reference's year" {
            for (span in listOf(
                "Q2",
                "Q2 revenue",
                "Q2 sales in Brno",
                "this Q2",
                "Q2 of the year",
                "Q2 for the year",
                "Q2 of the fiscal year",
                "Q2 of the financial year",
                "Q2 of the calendar year",
                "Q2 fiscal year",
                "the second quarter of the year",
                "Q2 share of annual revenue",
                "2. čtvrtletí podíl na roční tržbě",
                "Q2 YoY",
                "Q2 year over year",
                "Q2 year-on-year",
                "2. čtvrtletí meziročně",
                "2. čtvrtletí roku",
                "2. čtvrtletí fiskálního roku",
                "2. čtvrtletí kalendářního roku",
                "2. čtvrtletí hospodářského roku",
                // "let" is an English verb, not the Czech „let“; a share is no year
                "let me see Q2 revenue",
                "Q2 25% growth",
            )) {
                withClue(span) { read(span).periodCode shouldBe "2026Q2" }
            }
            read("H2 of the year").periodCode shouldBe "2026H2"
            for (span in listOf("Q2 FY2025", "Q2 of FY2025", "Q2 2025/26")) {
                withClue(span) { read(span).periodCode shouldBe "2025Q2" }
            }
            read("Q2 of that year", context = listOf("Q2 of that year", "Revenue in 2025")).periodCode shouldBe "2025Q2"
            for (span in listOf("YTD", "YTD this year", "letos dosud", "YTD for the year")) {
                val r = read(span)
                withClue(span) {
                    r.startInclusive shouldBe day(2026, 1, 1)
                    r.endExclusive shouldBe day(2026, 11, 2)
                }
            }
        }

        // review-215 RR18 (S0.3 T9): a year after "since", "until", „od (roku)“, „do (roku)“ is a range's bound, not the
        // year the period is in.
        "a quarter or half whose year is only a range's bound is not read" {
            for (span in listOf(
                "Q2 since 2020",
                "Q2 revenue since 2020",
                "2. čtvrtletí od roku 2020",
                "2. čtvrtletí po roce 2020",
                "Q2 until 2025",
                "2. čtvrtletí do roku 2025",
                "H1 since 2020",
                "the second half of the year since 2020",
                "the last quarter of the year since 2020",
                "Q2 since last year",
                "2. čtvrtletí od loňska",
            )) {
                withClue(span) { rec.read(span, ref).shouldBeNull() }
            }
            for (span in listOf(
                "Q2 in 2025",
                "Q2 during 2025",
                "Q2 2025",
                "Q2 of 2025",
                "2. čtvrtletí v roce 2025",
                "ve 2. čtvrtletí roku 2025",
                "v roce 2025 ve 2. čtvrtletí",
            )) {
                withClue(span) { read(span).periodCode shouldBe "2025Q2" }
            }
            for (span in listOf("since 2020", "od roku 2020")) {
                val r = read(span)
                withClue(span) {
                    r.startInclusive shouldBe day(2020, 1, 1)
                    r.endExclusive shouldBe day(2021, 1, 1)
                }
            }
        }

        "a quarter in a year the span writes or says is unchanged" {
            for (span in listOf(
                "Q2 2025",
                "Q2 in 2025",
                "2025 Q2 revenue",
                "Q2 of the year 2025",
                "2. čtvrtletí, rok 2025",
                "v roce 2025 ve 2. čtvrtletí",
                "Q2 last year for 2000 customers",
                "more than 2000 units in Q2 2025",
            )) {
                withClue(span) { read(span).periodCode shouldBe "2025Q2" }
            }
            read("Q2").periodCode shouldBe "2026Q2"
            val q4 = read("Q4", LocalDate.of(2026, 2, 10))
            q4.periodCode shouldBe "2026Q4"
            q4.alternatives.map { it.periodCode } shouldBe listOf("2025Q4")
        }

        "a quarter an earlier question names in a year chrono cannot read is no quarter to count from" {
            rec
                .read(
                    "the previous quarter",
                    ref,
                    context = listOf("And the previous quarter?", "Revenue plan for Q2 next year"),
                ).shouldBeInstanceOf<ChronoReading.Unanchored>()
        }

        // review-215 RR14: a quarter or half of a fiscal year is that quarter or half, as "Q4 FY2025" is.
        "a quarter or half of a fiscal year is that quarter or half, not the whole fiscal year" {
            for ((span, code) in listOf(
                "Q4 of fiscal year 2025" to "2025Q4",
                "the last quarter of fiscal year 2025" to "2025Q4",
                "the second half of fiscal year 2025" to "2025H2",
                "the last half of fiscal year 2025" to "2025H2",
                "H1 fiscal year 2025" to "2025H1",
                "Q4 FY2025" to "2025Q4",
                "the last month of fiscal year 2026" to "202612",
            )) {
                withClue(span) { read(span).periodCode shouldBe code }
            }
            read("fiscal year 2025").kind shouldBe ChronoKind.FISCAL_YEAR
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

        // review-215 RR15 (✅R215-N, A-TS-3): the last N units of a larger period are a part of it, which no rule reads.
        "a count of a larger period is no trailing count; a count of anything else is" {
            for (span in listOf(
                "the last 2 months of the year",
                "the last 6 months of the year",
                "poslední 2 měsíce roku",
                "the last 3 months of the quarter",
                "the last 2 days of the month",
                "the last 2 months of last year",
                "posledních 6 měsíců loňského roku",
                "posledních 14 dní března",
                "the last 10 days in March",
            )) {
                withClue(span) { rec.read(span, ref).shouldBeNull() }
            }
            read("last 3 months").startInclusive shouldBe day(2026, 8, 2)
            read("the last 12 months").startInclusive shouldBe day(2025, 11, 2)
            read("posledních 30 dní").startInclusive shouldBe day(2026, 10, 3)
            read("last 30 days in Brno").startInclusive shouldBe day(2026, 10, 3)
            read("the last 3 months of data").startInclusive shouldBe day(2026, 8, 2)
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
                "poslední měsíc čtvrtletí",
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
        // trailing ask, not the deictic "last month", not the week rule. The last month of a year is December (RR10).
        "the last week, month or year of a larger period is no period" {
            for (span in listOf(
                "the last month of the quarter",
                "the last week of the month",
                "the last year of the contract",
                "the last week in October",
                "poslední měsíc čtvrtletí",
                "poslední týden v měsíci",
                "the last month in the quarter",
                "last week of the month",
                "poslední týden října",
                "the last week of the year",
                "the last month of Q3 2025",
                "poslední týden března",
                "last week of Q3 2025",
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

        // review-215 RR7: a year is part of no month or quarter; "last year in March" is the order English uses as often
        // as "March last year".
        "a last year before a month or a quarter is that month or quarter of last year" {
            read("sales last year in March").periodCode shouldBe "202503"
            read("last year in December").periodCode shouldBe "202512"
            read("revenue last year in Q3").periodCode shouldBe "2025Q3"
            for (span in listOf(
                "the last week in October",
                "last week in March",
                "the last month in the quarter",
                "the last month of Q3 2025",
                "the last year of the contract",
            )) {
                withClue(span) { rec.read(span, ref).shouldBeNull() }
            }
            // Czech: „poslední rok“ before a month or a quarter is no part of it either.
            for (span in listOf(
                "poslední rok v březnu",
                "za poslední rok v prosinci",
                "poslední rok ve 3. čtvrtletí",
            )) {
                withClue(span) { PartOfPeriods.findAll(Normalization.fold(span)).shouldBeEmpty() }
            }
        }

        // The earlier question names no month, so "the previous month" counts from the reference: October.
        "a part of a larger period in an earlier question names no period of its unit" {
            read(
                "the previous month",
                context = listOf("And the previous month?", "Revenue in the last month of the quarter"),
            ).periodCode shouldBe "202610"
        }

        // review-215 RR10 (✅R215-R, Bora 2026-11-04): the last month of a year is December and the last half H2, read
        // as any yearless December or H2 — the year from the year word, else the conversation's, else the reference's.
        "the last month of a year is its December, the last half its second half" {
            for ((span, code) in listOf(
                "poslední měsíc tohoto roku" to "202612",
                "the last month of last year" to "202512",
                "poslední měsíc loňského roku" to "202512",
                "the last month of 2025" to "202512",
                "poslední měsíc roku 2025" to "202512",
                "the last month of fiscal year 2026" to "202612",
                "the last half of the year" to "2026H2",
                "poslední pololetí roku" to "2026H2",
                "the last half of last year" to "2025H2",
                "poslední pololetí loňského roku" to "2025H2",
                "the last half of 2025" to "2025H2",
                "poslední pololetí 2025" to "2025H2",
                "poslední pololetí roku 2025" to "2025H2",
                "the first half of last year" to "2025H1",
                "the second half of last year" to "2025H2",
                "the second half of the year" to "2026H2",
            )) {
                val r = read(span)
                withClue(span) {
                    r.kind shouldBe ChronoKind.PERIOD
                    r.periodCode shouldBe code
                    r.alternatives shouldBe emptyList()
                }
            }
            for (span in listOf(
                "the last month of the year",
                "poslední měsíc roku",
                "v posledním měsíci roku",
                "poslední měsíc v roce",
                "the last month of the fiscal year",
            )) {
                val r = read(span)
                withClue(span) {
                    r.periodCode shouldBe "202612"
                    r.alternatives.map { it.periodCode } shouldBe listOf("202512")
                }
            }
            read("the last month of the year", may).alternatives.map { it.periodCode } shouldBe listOf("202512")
            val half = read("the last half of the year", may)
            half.periodCode shouldBe "2026H2"
            half.alternatives.map { it.periodCode } shouldBe listOf("2025H2")
        }

        "the last month or half of a year chrono cannot name is not read; trailing and relative words are unchanged" {
            for (span in listOf(
                "the last month of next year",
                "the last month of each year",
                "last month of next year",
                "poslední měsíc příštího roku",
                "the last half of next year",
                "poslední pololetí příštího roku",
                "Q2 and the last month of the year",
                "poslední měsíce roku",
            )) {
                withClue(span) { rec.read(span, ref).shouldBeNull() }
            }
            read("the last month").optionId shouldBe ChronoRecognition.TRAILING
            read("poslední měsíc").optionId shouldBe ChronoRecognition.TRAILING
            read("last month").periodCode shouldBe "202610"
            read("H2").periodCode shouldBe "2026H2"
            read("druhé pololetí").periodCode shouldBe "2026H2"
            read("first half of 2025").periodCode shouldBe "2025H1"
            rec.recognize("the first half", ref).shouldBeNull()
        }

        // review-215 RR16: the last quarter, half and month of a year read the same forms.
        "the last quarter, half and month of a year read 'of the last year' alike, and „v (loňském) roce“ alike" {
            for (span in listOf(
                "the last month of the last year",
                "the last quarter of the last year",
                "the last half of the last year",
            )) {
                withClue(span) { rec.read(span, ref).shouldBeNull() }
            }
            for ((span, code) in listOf(
                "poslední čtvrtletí v loňském roce" to "2025Q4",
                "v posledním čtvrtletí v roce 2025" to "2025Q4",
                "poslední pololetí v loňském roce" to "2025H2",
                "poslední pololetí v roce 2025" to "2025H2",
                "poslední měsíc v loňském roce" to "202512",
                "poslední měsíc v roce 2025" to "202512",
            )) {
                withClue(span) { read(span).periodCode shouldBe code }
            }
        }

        "after the last month of a year, the previous month counts from its December" {
            withClue("a December with no year, later than the reference's month, is a December nobody settled") {
                rec
                    .read(
                        "the previous month",
                        ref,
                        context = listOf("And the previous month?", "Revenue in the last month of the year"),
                    ).shouldBeInstanceOf<ChronoReading.Unanchored>()
            }
            read(
                "the previous month",
                context = listOf("And the previous month?", "Revenue in the last month of last year"),
            ).periodCode shouldBe "202511"
            read("v září", context = listOf("A v září?", "Tržby za poslední měsíc roku 2025")).periodCode shouldBe
                "202509"
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

        // review-215 RR9 (✅R215-O): „do“ and "to" name a place or a party as often as a time.
        "year to date with „do“ or 'to' before a word that names no time is year to date" {
            for (span in listOf(
                "vývoz od začátku roku do Německa",
                "export od začátku roku do zahraničí",
                "exports year to date to Germany",
                "year to date to our top customers",
            )) {
                val r = read(span)
                withClue(span) {
                    r.startInclusive shouldBe day(2026, 1, 1)
                    r.endExclusive shouldBe day(2026, 11, 2)
                }
            }
            for (span in listOf(
                "year to date as of 15 March",
                "YTD until the contract ends",
                "od začátku roku do Q2",
                "YTD to last month",
            )) {
                withClue(span) { rec.read(span, ref).shouldBeNull() }
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

        // review-215 RR11: a year word says which year year to date is in, as a year in digits does; a year other than
        // the reference's is the year rules' to read, as „od začátku roku 2025“ is.
        "year to date in a year other than the reference's is that year, as the year rules read it" {
            for ((span, year) in listOf(
                "YTD last year" to 2025,
                "year to date last year" to 2025,
                "last year YTD" to 2025,
                "YTD for last year" to 2025,
                "loni od začátku roku" to 2025,
                "od začátku roku loni" to 2025,
                "předloni od začátku roku" to 2024,
                "YTD the year before last" to 2024,
            )) {
                val r = read(span)
                withClue(span) {
                    r.startInclusive shouldBe day(year, 1, 1)
                    r.endExclusive shouldBe day(year + 1, 1, 1)
                    r.confidence shouldBe 0.9
                }
            }
            val previous = read("YTD previous year", context = listOf("YTD previous year", "Revenue in 2024"))
            previous.startInclusive shouldBe day(2023, 1, 1)
            previous.endExclusive shouldBe day(2024, 1, 1)
            previous.confidence shouldBe 0.85
            val march = read("YTD last year through March")
            march.startInclusive shouldBe day(2025, 1, 1)
            march.endExclusive shouldBe day(2025, 4, 1)
        }

        "year to date this year is year to date, and a year word joined to it is still another period" {
            for (span in listOf("letos dosud", "YTD this year", "YTD", "YTD by region")) {
                val r = read(span)
                withClue(span) {
                    r.startInclusive shouldBe day(2026, 1, 1)
                    r.endExclusive shouldBe day(2026, 11, 2)
                }
            }
            read("od začátku roku 2025 do dneška").startInclusive shouldBe day(2025, 1, 1)
            for (span in listOf("year to date vs last year", "YTD compared to last year's YTD")) {
                withClue(span) { rec.read(span, ref).shouldBeNull() }
            }
        }
    })
