// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

/**
 * The periods that run up to the reference day, over the folded text (`Normalization.fold`): a count of days, weeks,
 * months or years back („posledních 30 dní“, "the last 3 months", "past 2 years"), and year to date („od začátku
 * roku“, „letos dosud“, "YTD"). One table serves both readers: [DateRecognizer] reads them, and [OtherPeriods] counts
 * each as a period of its own beside another ("Q1 and the last 3 months", "Q2 vs YTD").
 */
internal object WindowPeriods {
    enum class CountUnit { DAY, WEEK, MONTH, YEAR }

    /** A count window: [count] [unit]s back, ending with the reference day. */
    data class Count(
        val count: Long,
        val unit: CountUnit,
        val range: IntRange,
    )

    private val countRe =
        Regex(
            """\b(?:last|past|poslednic?h?|minul\w*)\s+(\d{1,3})\s+""" +
                """(days?|den|dn[iu]|weeks?|tyden|tydn\w*|months?|mesic\w*|years?|rok|roky|roku|let)\b""",
        )

    /** The year-to-date phrase, without the ending [DateRecognizer] reads after it. */
    val yearToDateRe =
        Regex(
            """\bod\s+zacatku\s+(?:(?:tohoto|letosniho)\s+)?roku\b|\bletos\s+(?:dosud|zatim)\b""" +
                """|\byear[\s-]+to[\s-]+date\b|\bytd\b""",
        )

    /**
     * A word that names a time, matched whole: „konce“ / "end", a period noun in any case, a scope or relative word, a
     * day word, `Qn`, `Hn`. Year to date ends only at one ([DateRecognizer], ✅R215-O), and a comparison names a second
     * period only with one on its other side ([OtherPeriods], ✅R215-M).
     */
    val timeWordRe =
        Regex(
            """konce|end|den|dne|dni|dnu|dny|dnem|days?|tyden|tydn\w*|weeks?|mesic\w*|months?|ctvrtlet\w*""" +
                """|kvartal\w*|quarters?|pololet\w*|half|rok|roku|roce|rokem|years?""" +
                """|minul\w*|predchoz\w*|predesl\w*|tohoto|tomto|toho|letosn\w*|lonsk\w*|pristi\w*""" +
                """|last|previous|prior|this|that|next|current""" +
                """|dnes\w*|vcer\w*|zitr\w*|today|yesterday|tomorrow|now|ted|soucasn\w*|q[1-4]|h[12]""",
        )

    /** Every count window in the folded [n], in the order they stand. */
    fun counts(n: String): List<Count> = countMemo(n)

    private val countMemo = SpanMemo(compute = ::findCounts)

    private fun findCounts(n: String): List<Count> =
        countRe
            .findAll(n)
            .map { m ->
                val noun = m.groupValues[2]
                val unit =
                    when {
                        noun.startsWith("d") -> CountUnit.DAY
                        noun.startsWith("w") || noun.startsWith("ty") -> CountUnit.WEEK
                        noun.startsWith("mo") || noun.startsWith("mes") -> CountUnit.MONTH
                        else -> CountUnit.YEAR
                    }
                Count(m.groupValues[1].toLong(), unit, m.range)
            }.toList()
}
