// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

/**
 * „poslední měsíc / týden / rok“ and "the last month / week / year": as often the last thirty, seven or 365 days as
 * the calendar period before, so chrono asks (TS-5). One table serves both readers, as [PeriodWords] does:
 * [DateRecognizer] asks between the two readings of the span, and [ConversationPeriods] knows that an earlier
 * question with one names a period nobody settled. [PeriodWords] gives way to it: "the last month" is no deictic
 * "last month".
 *
 * Over the folded text (`Normalization.fold`). The singular only, the adjective's case going with the noun's —
 * „poslední měsíce“ (recent months) is a plural, „posledních 3 měsíců“ a count, „poslední čtvrtletí“ the quarter
 * before, and an adjective decides it („poslední celý měsíc“ is no trailing month). A part of a larger period is
 * none either ([PartOfPeriods]): "the last week of the month" is a week of this one. Nor is the last month of a year
 * ([NumberedPeriods]): „poslední měsíc roku“ is December.
 */
internal object TrailingPeriods {
    /** The unit a trailing phrase names, the [days] its trailing reading spans, and its [period] unit, if #214 has one. */
    enum class TrailingUnit(
        val days: Long,
        val period: PeriodUnit?,
    ) {
        WEEK(7L, null),
        MONTH(30L, PeriodUnit.MONTH),
        YEAR(365L, PeriodUnit.YEAR),
    }

    data class Trailing(
        val unit: TrailingUnit,
        val range: IntRange,
    )

    private val re =
        Regex(
            """\b(?:posledni\s+(mesic|tyden|rok)|posledniho\s+(mesice|tydne|roku)|poslednimu\s+(mesici|tydnu|roku)""" +
                """|poslednim\s+(mesici|mesicem|tydnu|tydnem|roce|rokem)|the\s+last\s+(month|week|year))\b""",
        )

    /**
     * Every trailing phrase in the folded [n], in the order they stand. The last month of a year hides only its own
     * "the last month": in "the last month of the last year" the year is still the trailing one, which no reading
     * settles, as in "the last quarter of the last year" (RR16).
     */
    fun findAll(n: String): List<Trailing> = memo(n)

    private val memo = SpanMemo(compute = ::find)

    private fun find(n: String): List<Trailing> {
        val partOf = PartOfPeriods.findAll(n)
        val lastMonths = NumberedPeriods.lastMonths(n).map { it.range.first }
        return re
            .findAll(n)
            .filterNot { m -> partOf.any { it.overlaps(m.range) } || lastMonths.any { it in m.range } }
            .map { m ->
                val noun = m.groupValues.drop(1).first { it.isNotEmpty() }
                val unit =
                    when {
                        noun.startsWith("mesic") || noun == "month" -> TrailingUnit.MONTH
                        noun.startsWith("tyd") || noun == "week" -> TrailingUnit.WEEK
                        else -> TrailingUnit.YEAR
                    }
                Trailing(unit, m.range)
            }.toList()
    }
}
