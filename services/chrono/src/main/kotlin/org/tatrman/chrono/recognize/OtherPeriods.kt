// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

import java.time.LocalDate

/**
 * Does a span name a period besides the one a rule read (✅R215-M)? Such a span is no one period: "Q2 2025 vs 2024",
 * „2. čtvrtletí a 1. pololetí 2025“ and "Q1 and last month" each name two, and reading one of them as the whole would
 * drop the other. Over the folded text (`Normalization.fold`), outside the range the rule matched:
 * - another quarter or half-year by number ([NumberedPeriods]), and a number or ordinal joined to one — the elided
 *   noun of „1./2. čtvrtletí“, "first through third quarter", "the second quarter and the third";
 * - a month name, the last month of a year ([NumberedPeriods]), a relative month or quarter word ([PeriodWords]), a
 *   week with a scope word, a day word or a date;
 * - a trailing phrase ([TrailingPeriods]), a count window or year to date ([WindowPeriods]);
 * - a year joined to the span by a comparison or a conjunction: "Q2 this year vs last year", „2. čtvrtletí letos a
 *   loni“, "Q2 vs 2024", "last year vs Q2", "Q2 this year and last";
 * - a comparison with a time on its other side, whatever stands between: "Q2 vs the same quarter last year",
 *   „2. čtvrtletí oproti stejnému období loni“, "Q2 vs a year ago", "Q2 vs prior period"; a scope word on a noun that
 *   is no time is none ("Q2 2025 vs current forecast");
 * - two different years: two year words („letošní a loňské“), a year word and a written year that differ („letos
 *   v Q2 2025“), or two years in digits ([NamedYear.bareYears], so not an amount: "more than 2000 units").
 *
 * A year word with no joiner says which year the period is in, and is none: „letošní 2. čtvrtletí“, "Q2 last year",
 * „loni ve 2. čtvrtletí“, "the second quarter of last year". [DateRecognizer] asks it of a numbered quarter or half
 * and of year to date; S0.3 asks it of the other rules (P5).
 */
internal object OtherPeriods {
    /** What joins two periods, or a number to the period whose noun it shares: „1. a 2.“, "first through", „1./2.“. */
    private const val JOIN =
        """(?:[,/&+–-]|\b(?:a|i|and|or|nebo|az|to|through|thru|till|until|vs|versus|oproti|proti|the)\b)"""

    private const val EN_ORDINAL = """\b(?:first|second|third|fourth|1st|2nd|3rd|4th)\b"""

    /** A Czech ordinal (`ctvrt` stops short of *čtvrtletí*), a bare digit, `Qn` or `Hn`. */
    private const val NUMBER = """(?:$EN_ORDINAL|\b(?:prvn|druh|tret|ctvrt(?!let))\w*|\b[1-4]\b|\bq[1-4]\b|\bh[12]\b)"""

    /**
     * A number right before a numbered period, joined to it: a Czech ordinal digit („1. 2. čtvrtletí“, „1.–2.“) needs
     * no joiner, any other number at least one.
     */
    private val numberBeforeRe = Regex("""(?:\b[1-4]\.(?:\s*$JOIN)*|$NUMBER(?:\s*$JOIN)+)\s*$""")

    /**
     * An ordinal right after a numbered period, joined to it: "the second quarter and the third". Not a Czech one:
     * Czech elides the noun of the earlier ordinals, and *druhy* after a period is as often "kinds".
     */
    private val numberAfterRe = Regex("""^(?:\s*$JOIN)+\s*($EN_ORDINAL|\b[1-4]\.)""")

    /** A week with a scope word, cs and en: "last week", „tento týden“, „v minulém týdnu“. */
    private val scopedWeekRe =
        Regex(
            """\b(?:this|current|last|past|previous|prior|next)\s+${PeriodWords.EN_ADJ}week\b""" +
                """|\b(?:tento|tohoto|tomto|tenhle|minul|predchoz|predesl|predminul|posledn|uplynul|pristi)\w*""" +
                """\s+${PeriodWords.CS_ADJ}(?:tyden|tydn\w*)""",
        )

    /** A day: today, yesterday, tomorrow, cs and en; a date in digits. */
    private val dayRe =
        Regex(
            """\b(?:today|yesterday|tomorrow|dnes\w*|dodnes|vcer\w*|zitr\w*)\b""" +
                """|\b\d{1,2}\s*[./]\s*\d{1,2}\s*[./]|\b\d{4}-\d{2}-\d{2}\b""",
        )

    private val wordRe = Regex("""\b[a-z]+\b""")
    private val yearAfterRe = Regex("""^\s+\d{4}\b""")
    private val prepositionBeforeRe = Regex("""\b(?:in|for|of|during|since|until|from|through|by|to)\s+$""")

    /** "may" and "march" are verbs too: a month only after a preposition or before a year. */
    private val verbMonths = setOf("may", "march")

    /**
     * What joins a second year to a period: a comparison or a conjunction, cs and en. Not a comma: "Q2, last year" is
     * Q2 of last year.
     */
    private const val COMPARISON =
        """\b(?:vs|versus|oproti|proti|compared\s+(?:to|with)|against|(?:ve\s+)?srovnani\s+se?|(?:v\s+)?porovnani\s+se?)\b\.?"""
    private const val YEAR_JOIN = """(?:[/&+–-]|$COMPARISON|\b(?:than|nez|and|a|i|or|nebo)\b\.?)"""
    private val yearJoinBeforeRe = Regex("""$YEAR_JOIN\s*(?:the\s+)?$""")
    private val yearJoinAfterRe = Regex("""^\s*$YEAR_JOIN""")

    /** "Q2 this year and last": a year whose noun the conjunction elides. */
    private val elidedLastRe = Regex("""\b(?:and|or|vs|versus|than)\.?\s+last\b(?!\s*[a-z0-9])""")

    private val comparisonRe = Regex(COMPARISON)

    /**
     * Besides [WindowPeriods.timeWordRe], the words a comparison's other side names a time with: "the same quarter",
     * „stejné období“, "prior period", "a year ago", "a year earlier", „před rokem“.
     */
    private val comparedTimeWordRe = Regex("""same|stejn\w*|period|obdobi|ago|earlier|before|pred|predtim""")

    /**
     * Does the folded [n] name a period outside [own], the range of the one a rule read? A year word is read against
     * [reference], the way it reads with no conversation, to tell whether it differs from a year in digits.
     */
    fun outside(
        n: String,
        own: IntRange,
        reference: LocalDate,
    ): Boolean = mentions(n).any { !it.overlaps(own) } || otherYear(n, own, reference) || comparedToTime(n, own)

    /**
     * Does a comparison in the folded [n] set [own] against a time: "Q2 vs the same quarter last year", „2. čtvrtletí
     * oproti stejnému období loni“, "Q2 vs a year ago", "the same quarter last year vs Q2"? Its other side is the rest
     * of the span when [own] stands before the comparison word, and what stands before the word when [own] is after
     * it. "Q2 sales vs target" compares no period. "and", „a“ and "than" join too much else to count without a year
     * right after them ([otherYear]): "revenue and costs last year in Q2" is Q2 of last year.
     */
    private fun comparedToTime(
        n: String,
        own: IntRange,
    ): Boolean =
        comparisonRe.findAll(n).filterNot { it.range.overlaps(own) }.any { m ->
            namesTime(if (m.range.first > own.last) n.substring(m.range.last + 1) else n.substring(0, m.range.first))
        }

    /**
     * The scope words of [WindowPeriods.timeWordRe]. One names a time on a comparison's other side only with a period
     * noun after it, as [PeriodWords] reads it ("vs the previous quarter", „oproti minulému měsíci“), or at the end of
     * that side ("Q2 vs last"). Before any other noun it scopes that noun: "vs current forecast", „oproti minulému
     * plánu“, "vs this budget" (RR20).
     */
    private val scopeWordRe =
        Regex(
            """this|that|last|previous|prior|next|current""" +
                """|minul\w*|predchoz\w*|predesl\w*|tohoto|tomto|toho|pristi\w*|soucasn\w*""",
        )

    /** Does [side] name a time: a relative period word, a year in digits, a month or a word of a time? */
    private fun namesTime(side: String): Boolean {
        val words = wordRe.findAll(side).map { it.value }.toList()
        return PeriodWords.findAll(side).isNotEmpty() ||
            NamedYear.bareYears(side).isNotEmpty() ||
            words.withIndex().any { (i, w) ->
                if (scopeWordRe.matches(w)) {
                    i == words.lastIndex
                } else {
                    WindowPeriods.timeWordRe.matches(w) ||
                        comparedTimeWordRe.matches(w) ||
                        Months.ofWord(w, withYear = false) != null
                }
            }
    }

    /** Does the folded [n] name a year besides the one [own] is in: one joined to the span, or two different ones? */
    private fun otherYear(
        n: String,
        own: IntRange,
        reference: LocalDate,
    ): Boolean {
        val words = PeriodWords.findAll(n, PeriodUnit.YEAR)
        val digits = NamedYear.bareYearsAt(n)
        if (digits.map { it.first }.toSet().size > 1) return true
        if (words.distinctBy { it.kind to it.delta }.size > 1) return true
        val written = NamedYear.writtenYears(n)
        if (words.any { w -> written.any { it != reference.year + w.delta } }) return true
        val years = words.map { it.range } + digits.map { it.second }
        val joined =
            years.any { r ->
                val before = yearJoinBeforeRe.containsMatchIn(n.substring(0, r.first))
                val after = r.last < own.first && yearJoinAfterRe.containsMatchIn(n.substring(r.last + 1))
                !r.overlaps(own) && (before || after)
            }
        return joined || elidedLastRe.findAll(n).any { !it.range.overlaps(own) }
    }

    /** Where the folded [n] names a period, other than by a year: the ranges [outside] weighs before the years. */
    private fun mentions(n: String): List<IntRange> =
        buildList {
            val numbered = NumberedPeriods.quarters(n) + NumberedPeriods.halves(n)
            for (p in numbered) {
                add(p.range)
                numberBeforeRe.find(n.substring(0, p.range.first))?.let { add(it.range) }
                numberAfterRe.find(n.substring(p.range.last + 1))?.let { m ->
                    val at = p.range.last + 1
                    add(at + m.range.first..at + m.range.last)
                }
            }
            NumberedPeriods.lastMonths(n).forEach { add(it.range) }
            PeriodWords.findAll(n, PeriodUnit.MONTH).forEach { add(it.range) }
            PeriodWords.findAll(n, PeriodUnit.QUARTER).forEach { add(it.range) }
            TrailingPeriods.findAll(n).forEach { add(it.range) }
            WindowPeriods.counts(n).forEach { add(it.range) }
            WindowPeriods.yearToDateRe.findAll(n).forEach { add(it.range) }
            scopedWeekRe.findAll(n).forEach { add(it.range) }
            dayRe.findAll(n).forEach { add(it.range) }
            for (m in wordRe.findAll(n)) {
                val yearAfter = yearAfterRe.containsMatchIn(n.substring(m.range.last + 1))
                Months.ofWord(m.value, withYear = yearAfter) ?: continue
                val verb = m.value in verbMonths && !yearAfter
                if (verb && !prepositionBeforeRe.containsMatchIn(n.substring(0, m.range.first))) continue
                add(m.range)
            }
        }
}
