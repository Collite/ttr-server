// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * A8.3 recognizer unit tests, one block per rule family. Reference is pinned to 2026-05-15
 * (a Friday) so relative expressions are deterministic — the recognizer never reads a clock.
 */
class DateRecognizerSpec :
    StringSpec({
        val rec = DateRecognizer()
        val ref = LocalDate.of(2026, 5, 15)

        fun day(
            y: Int,
            m: Int,
            d: Int,
        ) = LocalDate.of(y, m, d)

        // ---- absolute: ISO ----
        "ISO date → single-day interval" {
            val r = rec.recognize("2026-03-15", ref).shouldNotBeNull()
            r.startInclusive shouldBe day(2026, 3, 15)
            r.endExclusive shouldBe day(2026, 3, 16)
            r.kind shouldBe ChronoKind.ABSOLUTE
        }

        // ---- absolute: numeric cs/en ----
        "numeric D.M.YYYY (cs)" {
            val r = rec.recognize("15.3.2026", ref).shouldNotBeNull()
            r.startInclusive shouldBe day(2026, 3, 15)
            r.endExclusive shouldBe day(2026, 3, 16)
        }
        "numeric with spaces '15. 3. 2026'" {
            rec.recognize("15. 3. 2026", ref).shouldNotBeNull().startInclusive shouldBe day(2026, 3, 15)
        }
        "numeric D.M. infers the reference year" {
            rec.recognize("15.3.", ref).shouldNotBeNull().startInclusive shouldBe day(2026, 3, 15)
        }

        // ---- absolute: named month ----
        "en 'March 15 2026' → that day" {
            rec.recognize("March 15 2026", ref).shouldNotBeNull().startInclusive shouldBe day(2026, 3, 15)
        }
        "cs genitive '15. května 2026' → that day" {
            rec.recognize("15. května 2026", ref).shouldNotBeNull().startInclusive shouldBe day(2026, 5, 15)
        }
        "cs červenec vs červen disambiguation" {
            rec.recognize("15. července 2026", ref).shouldNotBeNull().startInclusive shouldBe day(2026, 7, 15)
            rec.recognize("15. června 2026", ref).shouldNotBeNull().startInclusive shouldBe day(2026, 6, 15)
        }
        "day equal to year%100 is still a day, not a whole month ('May 26 2026' → the 26th)" {
            val r = rec.recognize("May 26 2026", ref).shouldNotBeNull()
            r.kind shouldBe ChronoKind.ABSOLUTE
            r.startInclusive shouldBe day(2026, 5, 26)
            r.endExclusive shouldBe day(2026, 5, 27)
        }
        "cs instrumental month 'v srpnem 2026' → August period" {
            rec.recognize("v srpnem 2026", ref).shouldNotBeNull().periodCode shouldBe "202608"
        }

        // ---- period: month granularity ----
        "'May 2026' → PERIOD month with code 202605" {
            val r = rec.recognize("May 2026", ref).shouldNotBeNull()
            r.kind shouldBe ChronoKind.PERIOD
            r.startInclusive shouldBe day(2026, 5, 1)
            r.endExclusive shouldBe day(2026, 6, 1)
            r.periodCode shouldBe "202605"
        }
        "'May period' infers the reference year (lower confidence)" {
            val r = rec.recognize("May period", ref).shouldNotBeNull()
            r.kind shouldBe ChronoKind.PERIOD
            r.periodCode shouldBe "202605"
            (r.confidence < 0.9) shouldBe true
        }
        "cs adjective 'květnové období' → May period" {
            rec.recognize("květnové období", ref).shouldNotBeNull().periodCode shouldBe "202605"
        }

        // ---- period: explicit code ----
        "bare code '202605' → PERIOD" {
            val r = rec.recognize("202605", ref).shouldNotBeNull()
            r.kind shouldBe ChronoKind.PERIOD
            r.periodCode shouldBe "202605"
            r.startInclusive shouldBe day(2026, 5, 1)
        }
        "'období 202605' is higher-confidence than the bare code" {
            val explicit = rec.recognize("období 202605", ref).shouldNotBeNull()
            val bare = rec.recognize("202605", ref).shouldNotBeNull()
            (explicit.confidence > bare.confidence) shouldBe true
        }
        "invalid month in code (202699) is not a period" {
            rec.recognize("202699", ref).shouldBeNull()
        }
        "an embedded 6-digit id ('doklad 200312') is NOT grounded as a period" {
            // No 'period'/'období' keyword and the code isn't standalone → must not become Dec 2003.
            rec.recognize("doklad 200312", ref).shouldBeNull()
        }

        // ---- fiscal year ----
        "'fiscal year 2026' → whole-year interval" {
            val r = rec.recognize("fiscal year 2026", ref).shouldNotBeNull()
            r.kind shouldBe ChronoKind.FISCAL_YEAR
            r.startInclusive shouldBe day(2026, 1, 1)
            r.endExclusive shouldBe day(2027, 1, 1)
        }
        "'fiskální rok 2026' (cs)" {
            rec.recognize("fiskální rok 2026", ref).shouldNotBeNull().startInclusive shouldBe day(2026, 1, 1)
        }

        // ---- bare calendar year ----
        // The live regression: hartland asked "…revenues for 2025 by month?", stanza tagged the
        // span DATE, and chrono answered UNGROUNDABLE — so the value reached golem with no
        // interval, no anchor column, and the door refused the question.
        "bare '2025' → whole-year interval" {
            val r = rec.recognize("2025", ref).shouldNotBeNull()
            r.kind shouldBe ChronoKind.CALENDAR_YEAR
            r.startInclusive shouldBe day(2025, 1, 1)
            r.endExclusive shouldBe day(2026, 1, 1)
        }
        "a bare year carries no period code — it must not route through the period table" {
            rec
                .recognize("2025", ref)
                .shouldNotBeNull()
                .periodCode
                .shouldBeNull()
        }
        "a bare year is not read as a fiscal one" {
            rec.recognize("2025", ref).shouldNotBeNull().kind shouldBe ChronoKind.CALENDAR_YEAR
            rec.recognize("fiscal year 2025", ref).shouldNotBeNull().kind shouldBe ChronoKind.FISCAL_YEAR
        }
        // Placement: every more specific rule must still win, or adding this one silently
        // reinterpreted spans that already worked.
        "the more specific rules still win over the bare year" {
            rec.recognize("2026-03-15", ref).shouldNotBeNull().kind shouldBe ChronoKind.ABSOLUTE
            rec.recognize("March 2026", ref).shouldNotBeNull().endExclusive shouldBe day(2026, 4, 1)
            rec.recognize("202605", ref).shouldNotBeNull().kind shouldBe ChronoKind.PERIOD
            rec.recognize("15.3.2026", ref).shouldNotBeNull().endExclusive shouldBe day(2026, 3, 16)
        }
        "a year outside the plausible range is not a year" {
            rec.recognize("1899", ref).shouldBeNull()
            rec.recognize("3000", ref).shouldBeNull()
        }
        "a span with a second number is not claimed as a year" {
            rec.recognize("2025 4", ref).shouldBeNull()
        }
        "a run that is not four digits is not a year" {
            rec.recognize("205", ref).shouldBeNull()
            rec.recognize("20255", ref).shouldBeNull()
        }

        // ---- relative ----
        "today / dnes → reference day" {
            rec.recognize("today", ref).shouldNotBeNull().startInclusive shouldBe ref
            rec.recognize("dnes", ref).shouldNotBeNull().startInclusive shouldBe ref
        }
        "yesterday / včera" {
            rec.recognize("yesterday", ref).shouldNotBeNull().startInclusive shouldBe day(2026, 5, 14)
            rec.recognize("včera", ref).shouldNotBeNull().startInclusive shouldBe day(2026, 5, 14)
        }
        "this month / tento měsíc → reference month" {
            val r = rec.recognize("this month", ref).shouldNotBeNull()
            r.startInclusive shouldBe day(2026, 5, 1)
            r.endExclusive shouldBe day(2026, 6, 1)
            rec.recognize("tento měsíc", ref).shouldNotBeNull().startInclusive shouldBe day(2026, 5, 1)
        }
        "last month / minulý měsíc → April 2026" {
            rec.recognize("last month", ref).shouldNotBeNull().startInclusive shouldBe day(2026, 4, 1)
            rec.recognize("minulý měsíc", ref).shouldNotBeNull().startInclusive shouldBe day(2026, 4, 1)
        }
        "this year / letos and last year / loni" {
            rec.recognize("letos", ref).shouldNotBeNull().startInclusive shouldBe day(2026, 1, 1)
            rec.recognize("loni", ref).shouldNotBeNull().startInclusive shouldBe day(2025, 1, 1)
        }
        "last week / minulý týden → the prior Monday..Monday week" {
            val r = rec.recognize("last week", ref).shouldNotBeNull()
            r.startInclusive.dayOfWeek shouldBe DayOfWeek.MONDAY
            r.startInclusive shouldBe day(2026, 5, 4) // Monday before the 2026-05-15 week (Mon 05-11)
            r.endExclusive shouldBe day(2026, 5, 11)
            rec.recognize("minulý týden", ref).shouldNotBeNull().startInclusive shouldBe day(2026, 5, 4)
        }
        // ---- cs relative periods in the forms a question puts them in (LR, the Czech demo's reserve
        // „Tržby z tržiště za minulý měsíc pro Brno DC“). The demonstrative and the adjective decline
        // with the noun, and each form must land on the same period as its nominative.
        val csRelative =
            listOf(
                // last month = April 2026
                "minulý měsíc" to (day(2026, 4, 1) to day(2026, 5, 1)),
                "za minulý měsíc" to (day(2026, 4, 1) to day(2026, 5, 1)),
                "minulého měsíce" to (day(2026, 4, 1) to day(2026, 5, 1)),
                "v minulém měsíci" to (day(2026, 4, 1) to day(2026, 5, 1)),
                "předchozí měsíc" to (day(2026, 4, 1) to day(2026, 5, 1)),
                "minuly mesic" to (day(2026, 4, 1) to day(2026, 5, 1)),
                // the month before last = March 2026
                "předminulý měsíc" to (day(2026, 3, 1) to day(2026, 4, 1)),
                // this month = May 2026
                "tento měsíc" to (day(2026, 5, 1) to day(2026, 6, 1)),
                "tenhle měsíc" to (day(2026, 5, 1) to day(2026, 6, 1)),
                "tohoto měsíce" to (day(2026, 5, 1) to day(2026, 6, 1)),
                "v tomto měsíci" to (day(2026, 5, 1) to day(2026, 6, 1)),
                "aktuální měsíc" to (day(2026, 5, 1) to day(2026, 6, 1)),
                // this year = 2026
                "letos" to (day(2026, 1, 1) to day(2027, 1, 1)),
                "letošní" to (day(2026, 1, 1) to day(2027, 1, 1)),
                "letošní rok" to (day(2026, 1, 1) to day(2027, 1, 1)),
                "tento rok" to (day(2026, 1, 1) to day(2027, 1, 1)),
                "v tomto roce" to (day(2026, 1, 1) to day(2027, 1, 1)),
                // last year = 2025
                "loni" to (day(2025, 1, 1) to day(2026, 1, 1)),
                "vloni" to (day(2025, 1, 1) to day(2026, 1, 1)),
                "loňský rok" to (day(2025, 1, 1) to day(2026, 1, 1)),
                "loňské" to (day(2025, 1, 1) to day(2026, 1, 1)),
                "minulý rok" to (day(2025, 1, 1) to day(2026, 1, 1)),
                "minulého roku" to (day(2025, 1, 1) to day(2026, 1, 1)),
                "v minulém roce" to (day(2025, 1, 1) to day(2026, 1, 1)),
                // two years back = 2024 — "předloni" also contains "loni"
                "předloni" to (day(2024, 1, 1) to day(2025, 1, 1)),
                "předloňský rok" to (day(2024, 1, 1) to day(2025, 1, 1)),
                // weeks: the reference is Friday 2026-05-15, its week starts Monday 05-11
                "tento týden" to (day(2026, 5, 11) to day(2026, 5, 18)),
                "minulého týdne" to (day(2026, 5, 4) to day(2026, 5, 11)),
                "v minulém týdnu" to (day(2026, 5, 4) to day(2026, 5, 11)),
            )
        for ((span, interval) in csRelative) {
            "cs relative '$span' → ${interval.first}/${interval.second}" {
                val r = rec.recognize(span, ref).shouldNotBeNull()
                r.startInclusive shouldBe interval.first
                r.endExclusive shouldBe interval.second
                r.confidence shouldBe 0.9
            }
        }
        "last month carries its period code, so a period-keyed fact can bind it" {
            rec.recognize("minulého měsíce", ref).shouldNotBeNull().periodCode shouldBe "202604"
            rec.recognize("předminulý měsíc", ref).shouldNotBeNull().periodCode shouldBe "202603"
        }
        "'poslední měsíc' is not read as the calendar month before — it is as often the trailing month" {
            rec.recognize("poslední měsíc", ref).shouldBeNull()
        }
        "a word that only contains 'roce' is not the locative of rok" {
            rec.recognize("minulé procento", ref).shouldBeNull()
        }
        "last N months / poslední 3 měsíce → rolling window ending today" {
            val r = rec.recognize("poslední 3 měsíce", ref).shouldNotBeNull()
            r.startInclusive shouldBe day(2026, 2, 15)
            r.endExclusive shouldBe day(2026, 5, 16)
            rec.recognize("last 30 days", ref).shouldNotBeNull().startInclusive shouldBe day(2026, 4, 15)
        }

        // ---- date-role targeting ----
        "'due in May' targets DUE and grounds the May period" {
            val r = rec.recognize("due in May", ref).shouldNotBeNull()
            r.target shouldBe DateTarget.DUE
            r.periodCode shouldBe "202605"
        }
        "'posted last month' targets POSTING" {
            rec.recognize("posted last month", ref).shouldNotBeNull().target shouldBe DateTarget.POSTING
        }

        // ---- ambiguity (A8.6) ----
        "a bare FUTURE month is ambiguous: this year primary + last year alternative" {
            // ref month = May (5); December (12) > 5 → could be this year (upcoming) or last (past)
            val r = rec.recognize("December", ref).shouldNotBeNull()
            r.periodCode shouldBe "202612"
            r.alternatives.map { it.periodCode } shouldBe listOf("202512")
        }
        "a bare past/current month is unambiguous (this year, no alternatives)" {
            val r = rec.recognize("March period", ref).shouldNotBeNull() // March (3) <= 5
            r.periodCode shouldBe "202603"
            r.alternatives.shouldBeEmpty()
        }
        "an explicit-year month is never ambiguous" {
            rec
                .recognize("December 2026", ref)
                .shouldNotBeNull()
                .alternatives
                .shouldBeEmpty()
        }

        // ---- cs month names in every case form a question uses, with and without a year ----
        // nominative · genitive · locative, with the prepositions a question puts before them. The
        // resolver hands chrono the span NER typed — "říjnu 2025" once the parts are joined — so each
        // form must carry its year; without one it stays the bare, reference-relative month.
        val csMonths =
            listOf(
                Triple("leden", "ledna", "lednu"),
                Triple("únor", "února", "únoru"),
                Triple("březen", "března", "březnu"),
                Triple("duben", "dubna", "dubnu"),
                Triple("květen", "května", "květnu"),
                Triple("červen", "června", "červnu"),
                Triple("červenec", "července", "červenci"),
                Triple("srpen", "srpna", "srpnu"),
                Triple("září", "září", "září"),
                Triple("říjen", "října", "říjnu"),
                Triple("listopad", "listopadu", "listopadu"),
                Triple("prosinec", "prosince", "prosinci"),
            )
        csMonths.forEachIndexed { i, (nominative, genitive, locative) ->
            val month = i + 1
            val code = "2025%02d".format(month)
            for (span in listOf(
                "$nominative 2025",
                "$genitive 2025",
                "$locative 2025",
                "za $nominative 2025",
                "během $genitive 2025",
                "v $locative 2025",
            )) {
                "cs '$span' → that month of 2025" {
                    val r = rec.recognize(span, ref).shouldNotBeNull()
                    r.kind shouldBe ChronoKind.PERIOD
                    r.startInclusive shouldBe day(2025, month, 1)
                    r.endExclusive shouldBe day(2025, month, 1).plusMonths(1)
                    r.periodCode shouldBe code
                    r.alternatives.shouldBeEmpty()
                }
            }
            "cs bare '$locative' (no year) stays the reference-relative month" {
                val r = rec.recognize(locative, ref).shouldNotBeNull()
                r.periodCode shouldBe "2026%02d".format(month)
                if (month > ref.monthValue) {
                    r.alternatives.map { it.periodCode } shouldBe listOf("2025%02d".format(month))
                } else {
                    r.alternatives.shouldBeEmpty()
                }
            }
        }
        "cs without diacritics 'v rijnu 2025' → October 2025" {
            rec.recognize("v rijnu 2025", ref).shouldNotBeNull().periodCode shouldBe "202510"
        }

        // ---- unrecognized ----
        "gibberish → null (caller emits UNGROUNDABLE / LLM fallback)" {
            rec.recognize("qwerty nonsense", ref).shouldBeNull()
        }
    })
