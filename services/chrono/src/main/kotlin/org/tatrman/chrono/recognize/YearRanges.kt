// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

import org.tatrman.text.Normalization

/** Candidate syntax only; DateRecognizer owns the range verdict consumed by Ground and Scan. */
internal object YearRanges {
    private const val NOUN = """(?:rok|roky|roku|letech|let|years?)"""
    private const val PREFIX =
        """(?:(?:od|from)\s+(?:$NOUN\s+)?|""" +
            """(?:(?:v|ve|in|for)\s+(?:the\s+)?)?(?:the\s+)?$NOUN\s+)?"""
    private val shape =
        Regex(
            """(?<![\p{L}\p{N}])$PREFIX(\d{4})\s*(?:[-–—]\s*(\d{4}|\d{2})|\b(?:do|az|to|until|through)\b\s*(\d{4}))(?![\p{L}\p{N}])""",
        )
    private val memo = SpanMemo { n -> shape.findAll(n).toList() }
    private const val QUALIFIER =
        """(?:fiscal|financial|accounting|fy|fiskaln\w*|financn\w*|ucetn\w*|""" +
            """hospodarsk\w*|seasons?|sezon\w*)"""
    private val qualifierBefore = Regex("""\b$QUALIFIER(?:\s+$NOUN)?\s*$""")
    private val qualifierAfter = Regex("""^\s+$QUALIFIER\b""")

    /** Date punctuation is tested before validity: an endpoint inside a numeric date is no range candidate. */
    fun findAll(
        n: String,
        before: String,
        after: String,
    ): List<MatchResult> {
        val matches = memo(n)
        if (matches.isEmpty()) return emptyList()
        val prefix = Normalization.fold(before)
        val framed = prefix + n + Normalization.fold(after)
        return matches.filter { m ->
            val first = m.groups[1]!!.range
            val last = (m.groups[2] ?: m.groups[3])!!.range
            guarded(framed, prefix.length + m.range.first, prefix.length + m.range.last + 1) &&
                !NamedYear.isAmountAt(framed, prefix.length + first.first..prefix.length + first.last) &&
                !NamedYear.isAmountAt(framed, prefix.length + last.first..prefix.length + last.last)
        }
    }

    private fun guarded(
        text: String,
        start: Int,
        end: Int,
    ): Boolean {
        fun numeric(
            c: Char?,
            far: Char?,
        ): Boolean = c != null && (c.isDigit() || c in "/-" || (c in ".," && far?.isDigit() == true))
        return !numeric(text.getOrNull(start - 1), text.getOrNull(start - 2)) &&
            !numeric(text.getOrNull(end), text.getOrNull(end + 1))
    }

    fun qualified(
        before: String,
        after: String,
    ): Boolean = qualifierBefore.containsMatchIn(before) || qualifierAfter.containsMatchIn(after)

    fun years(m: MatchResult): Pair<Int, Int>? {
        val from = m.groupValues[1].takeIf { it.length == 4 }?.toIntOrNull() ?: return null
        val last = m.groupValues[2].ifEmpty { m.groupValues[3] }
        val to = last.takeIf { it.length == 4 }?.toIntOrNull() ?: return null
        return (from to to).takeIf {
            from in NamedYear.PLAUSIBLE_YEARS && to in NamedYear.PLAUSIBLE_YEARS && to >= from
        }
    }
}
