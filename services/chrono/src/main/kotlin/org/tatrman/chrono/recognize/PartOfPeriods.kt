// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

/**
 * "the last month of the quarter", „poslední týden v měsíci“: a last week, month or year of a period that contains it
 * (✅R215-N). It is a part of that period — the quarter's last month — and no rule reads it yet, so it is no period
 * at all: not the trailing ask ([TrailingPeriods]), not the deictic "last month" ([PeriodWords]), not the
 * week rule. Over the folded text (`Normalization.fold`):
 * - "the last week / month / year of" anything — "of the year", "of Q3", "of the contract";
 * - "last week / month of / in" a period: (the / this / that / each) year, quarter, month, week, a month, a year in
 *   digits, `Qn`, `Hn` — "last month in Brno" is still last month. Not "last year": a year is part of no month or
 *   quarter, and "last year in March" is March of last year;
 * - „poslední týden / měsíc“ in any case, then a period noun in the genitive (roku, měsíce, týdne, čtvrtletí,
 *   kvartálu, pololetí, a month) or after v / ve in the locative (roce, měsíci, týdnu, čtvrtletí, a month), with an
 *   optional tohoto / letošního / minulého / každého between. Not „poslední rok“, for the reason above;
 * - a count window ([WindowPeriods.counts]) of such a whole, the same way: "the last 2 months of the year", "the last
 *   3 months of the quarter", "the last 2 days of the month", "the last 10 days in March", „poslední 2 měsíce roku“,
 *   „posledních 6 měsíců loňského roku“ (RR15). "last 30 days in Brno" and "the last 3 months of data" are counts.
 *
 * A last quarter, half or month of a year is its fourth quarter, its second half or December ([NumberedPeriods], ✅R215-P
 * and R): "the last quarter of 2025", "the last half of the previous year", „poslední měsíc roku“. Those are not in
 * the table. One of a year [NumberedPeriods] does not read (its year words are [YearPhrases]') is, so it is not read
 * as the quarter or month before either: "the last quarter of next year", "the last month of each year", "… of the year-over-year growth",
 * „poslední čtvrtletí příštího roku“, „poslední pololetí každého roku“.
 */
internal object PartOfPeriods {
    private const val EN_ADJ = PeriodWords.EN_ADJ
    private const val CS_ADJ = PeriodWords.CS_ADJ

    private val enAnyRe = Regex("""\bthe\s+last\s+$EN_ADJ(?:week|month|year)\s+of\b""")
    private val enRe =
        Regex(
            """\blast\s+$EN_ADJ(?:week|month)\s+(?:of|in)\s+""" +
                """(?:(?:the|this|that|each|every)\s+)?([a-z0-9]+)\b""",
        )
    private val csRe =
        Regex(
            """\bposledn\w*\s+$CS_ADJ(?:tyden|tydne|tydnu|tydnem|mesic|mesice|mesici|mesicem)""" +
                """\s+(?:ve?\s+)?""" +
                """(?:(?:tohoto|toho|tomto|tom|letosniho|letosnim|minuleho|minulem|kazdeho|kazdem)\s+)?([a-z]+)\b""",
        )

    /**
     * "last quarter / half / month of / in" up to two words, then "year"; „posledn… čtvrtletí / pololetí / měsíc“ up to
     * two words, then „roku“ or „roce“.
     */
    private val enOfYearRe =
        Regex("""\blast\s+$EN_ADJ(?:quarter|half|month)\s+(?:of|in)\s+(?:[a-z]+\s+){0,2}${EN_ADJ}year\b""")
    private val csOfYearRe =
        Regex(
            """\bposledn\w*\s+$CS_ADJ(?:(?:ctvrtlet|kvartal|pololet)(?!n)\w*|mesic(?:e|i|em)?)""" +
                """\s+(?:[a-z]+\s+){0,2}$CS_ADJ(?:roku|roce)\b""",
        )

    /**
     * After a count window: of / in, then (the) (this / last …) year, quarter, month or week, cs: a whole's noun. Not
     * a year-over-year measure: "the last 3 months of year-over-year growth" is a trailing count (RR20).
     */
    private val enCountOfRe =
        Regex(
            """^\s+(?:of|in)\s+(?:(?:the|that)\s+)?""" +
                """(?:(?:this|current|last|previous|prior|past|next|following|coming|each|every)\s+)?""" +
                """${EN_ADJ}(?:year\b(?!\s*-|\s+(?:over|on)\b)|quarter\b|month\b|week\b)""",
        )
    private val enCountOfWordRe = Regex("""^\s+(?:of|in)\s+(?:the\s+)?([a-z0-9]+)\b""")
    private val csCountOfRe =
        Regex(
            """^\s+(?:ve?\s+)?(?:(?:tohoto|toho|tomto|tom|letosniho|letosnim|minuleho|minulem|lonskeho|lonskem""" +
                """|predchoziho|predchozim|kazdeho|kazdem|pristiho|pristim)\s+)?$CS_ADJ([a-z]+)\b""",
        )

    private val enWholes = setOf("year", "quarter", "month", "week")
    private val csWholes = "roku roce mesice mesici tydne tydnu ctvrtleti kvartalu pololeti".split(' ').toSet()
    private val enCodeRe = Regex("""\d{4}|q[1-4]|h[12]""")

    /** How long the whole a count window is of runs in [rest], the text right after the window; null if none. */
    private fun countOf(rest: String): Int? {
        enCountOfRe.find(rest)?.let { return it.range.last + 1 }
        enCountOfWordRe.find(rest)?.let { m ->
            val w = m.groupValues[1]
            if (enCodeRe.matches(w) || Months.ofWord(w, withYear = true) != null) return m.range.last + 1
        }
        csCountOfRe.find(rest)?.let { m ->
            val w = m.groupValues[1]
            if (w in csWholes || Months.ofWord(w, withYear = false) != null) return m.range.last + 1
        }
        return null
    }

    /** Where the folded [n] names a part of a larger period, in the order they stand. */
    fun findAll(n: String): List<IntRange> = memo(n)

    private val memo = SpanMemo(compute = ::find)

    private fun find(n: String): List<IntRange> {
        // The last quarter, half or month of a year [NumberedPeriods] reads; not "Q3" in "last week of Q3 2025".
        val ofYear =
            (NumberedPeriods.quarters(n) + NumberedPeriods.halves(n) + NumberedPeriods.lastMonths(n))
                .map { it.range }
                .filter { n.startsWith("last", it.first) || n.startsWith("posledn", it.first) }
        return buildList {
            enAnyRe.findAll(n).forEach { add(it.range) }
            for (m in enRe.findAll(n)) {
                val w = m.groupValues[1]
                if (w in enWholes || enCodeRe.matches(w) || Months.ofWord(w, withYear = true) != null) add(m.range)
            }
            for (m in csRe.findAll(n)) {
                val w = m.groupValues[1]
                if (w in csWholes || Months.ofWord(w, withYear = false) != null) add(m.range)
            }
            (enOfYearRe.findAll(n) + csOfYearRe.findAll(n)).forEach { add(it.range) }
            for (count in WindowPeriods.counts(n)) {
                val at = count.range.last + 1
                countOf(n.substring(at))?.let { add(count.range.first until at + it) }
            }
        }.filterNot { r -> ofYear.any { it.overlaps(r) } }
            .sortedBy { it.first }
    }
}
