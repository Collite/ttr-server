// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

/** Calendar-year endpoints, over folded text. One vocabulary for the reader and Scan's phrase boundaries. */
internal object YearRanges {
    // Keep invalid lengths/bounds here too: Scan must not salvage a single year from a rejected range.
    private val shape =
        Regex(
            """(?<![\p{L}\p{N}])(?:(?:od|from)\s+)?(\d{4,})\s*(?:[-–—]|\b(?:do|az|to|until|through)\b)\s*(\d{2,})(?![\p{L}\p{N}])""",
        )

    private val isoMonth = Regex("""\d{4}-\d{2}""")
    private val memo =
        SpanMemo { n ->
            shape
                .findAll(n)
                .filterNot { m ->
                    // The yyyy-MM prefix of yyyy-MM-dd must reach the existing ISO-date rule.
                    isoMonth.matches(m.value) && m.groupValues[2].toIntOrNull() in 1..12
                }.toList()
        }

    fun findAll(n: String): List<MatchResult> = memo(n)

    /** The legacy year-range numeric guard, including punctuation trimmed from Scan tokens. */
    fun guarded(
        text: String,
        start: Int,
        end: Int,
    ): Boolean = !numericPart(text.getOrNull(start - 1)) && !numericPart(text.getOrNull(end))

    private fun numericPart(c: Char?): Boolean = c != null && (c.isDigit() || c in ".,/-")

    fun years(m: MatchResult): Pair<Int, Int>? {
        val from = m.groupValues[1].takeIf { it.length == 4 }?.toIntOrNull() ?: return null
        val to = m.groupValues[2].takeIf { it.length == 4 }?.toIntOrNull() ?: return null
        return (from to to).takeIf {
            from in NamedYear.PLAUSIBLE_YEARS && to in NamedYear.PLAUSIBLE_YEARS && to >= from
        }
    }

    /** Only these words/digits belong to a complete range, including its optional od/from. */
    fun isPhrase(n: String): Boolean =
        findAll(n).singleOrNull()?.let { m ->
            m.range.first == 0 && m.range.last == n.lastIndex && years(m) != null
        } == true
}
