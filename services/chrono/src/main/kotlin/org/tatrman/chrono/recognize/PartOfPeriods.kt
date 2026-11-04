// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

/**
 * "the last month of the year", „poslední týden v měsíci“: a last week, month or year of a period that contains it
 * (✅R215-N). It is a part of that period — December, the quarter's last month — and no rule reads it yet, so it is
 * no period at all: not the trailing ask ([TrailingPeriods]), not the deictic "last month" ([PeriodWords]), not the
 * week rule. Over the folded text (`Normalization.fold`):
 * - "the last week / month / year of" anything — "of the year", "of Q3", "of the contract";
 * - "last week / month of / in" a period: (the / this / that / each) year, quarter, month, week, a month, a year in
 *   digits, `Qn`, `Hn` — "last month in Brno" is still last month. Not "last year": a year is part of no month or
 *   quarter, and "last year in March" is March of last year;
 * - „poslední týden / měsíc“ in any case, then a period noun in the genitive (roku, měsíce, týdne, čtvrtletí,
 *   kvartálu, pololetí, a month) or after v / ve in the locative (roce, měsíci, týdnu, čtvrtletí, a month), with an
 *   optional tohoto / letošního / minulého / každého between. Not „poslední rok“, for the reason above.
 *
 * A last quarter of a year is the fourth quarter ([NumberedPeriods]): "the last quarter of 2025", "… of the
 * previous year", „poslední čtvrtletí roku“. One of a year [NumberedPeriods] does not read is in the table, so it is
 * not read as the quarter before either: "the last quarter of next year", "… of each year", "… of the year-over-year
 * growth", „poslední čtvrtletí příštího roku“, „… každého roku“.
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

    /** "last quarter of / in" up to two words, then "year"; „posledn… čtvrtletí“ up to two words, then „roku“. */
    private val enQuarterOfYearRe =
        Regex("""\blast\s+${EN_ADJ}quarter\s+(?:of|in)\s+(?:[a-z]+\s+){0,2}${EN_ADJ}year\b""")
    private val csQuarterOfYearRe =
        Regex("""\bposledn\w*\s+$CS_ADJ(?:ctvrtlet|kvartal)(?!n)\w*\s+(?:[a-z]+\s+){0,2}${CS_ADJ}roku\b""")

    private val enWholes = setOf("year", "quarter", "month", "week")
    private val csWholes = "roku roce mesice mesici tydne tydnu ctvrtleti kvartalu pololeti".split(' ').toSet()
    private val enCodeRe = Regex("""\d{4}|q[1-4]|h[12]""")

    /** Where the folded [n] names a part of a larger period, in the order they stand. */
    fun findAll(n: String): List<IntRange> =
        buildList {
            enAnyRe.findAll(n).forEach { add(it.range) }
            for (m in enRe.findAll(n)) {
                val w = m.groupValues[1]
                if (w in enWholes || enCodeRe.matches(w) || Months.ofWord(w, withYear = true) != null) add(m.range)
            }
            for (m in csRe.findAll(n)) {
                val w = m.groupValues[1]
                if (w in csWholes || Months.ofWord(w, withYear = false) != null) add(m.range)
            }
            val fourth = NumberedPeriods.quarters(n).map { it.range }
            (enQuarterOfYearRe.findAll(n) + csQuarterOfYearRe.findAll(n))
                .filterNot { m -> fourth.any { it.overlaps(m.range) } }
                .forEach { add(it.range) }
        }.sortedBy { it.first }
}
