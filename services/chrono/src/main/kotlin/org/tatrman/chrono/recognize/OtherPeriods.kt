// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

/**
 * Does a span name a period besides the one a rule read (✅R215-M)? Such a span is no one period: "Q2 2025 vs 2024",
 * „2. čtvrtletí a 1. pololetí 2025“ and "Q1 and last month" each name two, and reading one of them as the whole would
 * drop the other. Over the folded text (`Normalization.fold`), outside the range the rule matched:
 * - another quarter or half-year by number ([NumberedPeriods]), and a number or ordinal joined to one — the elided
 *   noun of „1./2. čtvrtletí“, "first through third quarter", "the second quarter and the third";
 * - a second year in digits ([NamedYear.bareYears], so not an amount: "more than 2000 units");
 * - a month name, a relative month or quarter word ([PeriodWords]), a week with a scope word, a day word or a date;
 * - a trailing phrase ([TrailingPeriods]).
 *
 * Year words are none: they say which year the period is in („letošní 2. čtvrtletí“, "the second quarter of last
 * year"). [DateRecognizer] asks it of a numbered quarter or half; S0.3 asks it of the other rules (P5).
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

    /** Does the folded [n] name a period outside [own], the range of the one a rule read? */
    fun outside(
        n: String,
        own: IntRange,
    ): Boolean = mentions(n).any { !it.overlaps(own) } || NamedYear.bareYears(n).size > 1

    /** Where the folded [n] names a period, other than by a year: the ranges [outside] weighs. */
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
            PeriodWords.findAll(n, PeriodUnit.MONTH).forEach { add(it.range) }
            PeriodWords.findAll(n, PeriodUnit.QUARTER).forEach { add(it.range) }
            TrailingPeriods.findAll(n).forEach { add(it.range) }
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
