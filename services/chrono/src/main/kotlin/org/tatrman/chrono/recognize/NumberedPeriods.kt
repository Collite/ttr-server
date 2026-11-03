// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

/**
 * Quarters and half-years named by their number, cs and en, over the folded text (`Normalization.fold`):
 * „2. čtvrtletí 2025“, „druhý kvartál“, "Q2 2025", "2025Q2", "the second quarter of 2025", „1. pololetí“,
 * "H1 2026", "the first half of 2026". One table serves both readers, as [PeriodWords] does: [DateRecognizer]
 * reads the period a span IS, [ConversationPeriods] the quarters an earlier question names.
 *
 * A number with a year of its own is matched first, and a yearless one only where no such match stands. "last"
 * and „poslední“ count as the fourth quarter only before a year ("the last quarter of 2025"): alone they are
 * relative words, [PeriodWords]'. An English ordinal half needs its year ("the first half of 2026"); alone, "the
 * first half" is as often a half of something else. The nouns stop short of their adjectives: *čtvrtletní*,
 * *kvartální* and *pololetní* name a grain, not a period.
 */
internal object NumberedPeriods {
    /** One numbered period: its [number] in the year (1–4 for a quarter, 1–2 for a half), its [year] if written. */
    data class Numbered(
        val number: Int,
        val year: Int?,
        val range: IntRange,
    )

    private const val CS_ADJ = PeriodWords.CS_ADJ
    private const val EN_ADJ = PeriodWords.EN_ADJ
    private const val CS_QUARTER = """(?:ctvrtlet|kvartal)(?!n)\w*"""
    private const val CS_HALF = """pololet(?!n)\w*"""
    private const val EN_QUARTER = """quarter\b"""
    private const val EN_ORDINALS = """first|second|third|fourth|1st|2nd|3rd|4th"""

    /** *první, druhé, třetí, čtvrté* in any case; `ctvrt` stops short of *čtvrtletí* itself. */
    private const val CS_ORDINALS = """prvn\w*|druh\w*|tret\w*|ctvrt(?!let)\w*"""

    /** A pattern and whether its year comes before the number (`2025Q2`). */
    private class Pattern(
        val re: Regex,
        val yearFirst: Boolean = false,
    )

    private val quarterWithYear =
        listOf(
            // Q2 2025 · Q2/2025
            Pattern(Regex("""\bq([1-4])\s*[/-]?\s*(\d{4})\b""")),
            // 2025 Q2 · 2025Q2 · 2025-Q2
            Pattern(Regex("""\b(\d{4})\s*[/-]?\s*q([1-4])\b"""), yearFirst = true),
            // 2. čtvrtletí 2025 · ve 2. fiskálním čtvrtletí roku 2025 · 3. kvartál 2025
            Pattern(Regex("""\b([1-4])\.\s*$CS_ADJ$CS_QUARTER\s+(?:roku\s+)?(\d{4})\b""")),
            // the second quarter of 2025 · the 2nd quarter in 2025 · the last quarter of 2025
            Pattern(Regex("""\b($EN_ORDINALS|last)\s+$EN_ADJ$EN_QUARTER\s+(?:of\s+|in\s+)?(\d{4})\b""")),
            // druhé čtvrtletí 2025 · ve druhém čtvrtletí roku 2025 · poslední čtvrtletí 2025
            Pattern(Regex("""\b($CS_ORDINALS|posledn\w*)\s+$CS_ADJ$CS_QUARTER\s+(?:roku\s+)?(\d{4})\b""")),
        )
    private val quarterYearless =
        listOf(
            Regex("""\bq([1-4])\b"""),
            Regex("""\b([1-4])\.\s*$CS_ADJ$CS_QUARTER"""),
            Regex("""\b($EN_ORDINALS)\s+$EN_ADJ$EN_QUARTER"""),
            Regex("""\b($CS_ORDINALS)\s+$CS_ADJ$CS_QUARTER"""),
        )

    private val halfWithYear =
        listOf(
            // H1 2026 · H1/2026
            Pattern(Regex("""\bh([12])\s*[/-]?\s*(\d{4})\b""")),
            // 2026 H1 · 2026H1 · 2026-H1
            Pattern(Regex("""\b(\d{4})\s*[/-]?\s*h([12])\b"""), yearFirst = true),
            // 1. pololetí 2026 · v 1. pololetí roku 2026
            Pattern(Regex("""\b([12])\.\s*$CS_ADJ$CS_HALF\s+(?:roku\s+)?(\d{4})\b""")),
            // první pololetí 2026 · ve druhém pololetí roku 2026
            Pattern(Regex("""\b(prvn\w*|druh\w*)\s+$CS_ADJ$CS_HALF\s+(?:roku\s+)?(\d{4})\b""")),
            // the first half of 2026 · the second half-year of 2025
            Pattern(Regex("""\b(first|second|1st|2nd)\s+half(?:[\s-]+year)?\s+(?:of\s+|in\s+)?(\d{4})\b""")),
        )
    private val halfYearless =
        listOf(
            Regex("""\bh([12])\b"""),
            Regex("""\b([12])\.\s*$CS_ADJ$CS_HALF"""),
            Regex("""\b(prvn\w*|druh\w*)\s+$CS_ADJ$CS_HALF"""),
        )

    /** The quarters the folded [n] names by number, in the order the patterns find them. */
    fun quarters(n: String): List<Numbered> = find(n, quarterWithYear, quarterYearless, ::quarterOf)

    /** The half-years the folded [n] names by number. */
    fun halves(n: String): List<Numbered> = find(n, halfWithYear, halfYearless, ::halfOf)

    private fun find(
        n: String,
        withYear: List<Pattern>,
        yearless: List<Regex>,
        numberOf: (String) -> Int?,
    ): List<Numbered> {
        val found = mutableListOf<Numbered>()
        for (p in withYear) {
            for (m in p.re.findAll(n)) {
                if (found.any { it.range.overlaps(m.range) }) continue
                val (a, b) = m.destructured
                val number = numberOf(if (p.yearFirst) b else a) ?: continue
                val year = (if (p.yearFirst) a else b).toInt()
                if (year !in NamedYear.PLAUSIBLE_YEARS) continue
                found += Numbered(number, year, m.range)
            }
        }
        for (re in yearless) {
            for (m in re.findAll(n)) {
                if (found.any { it.range.overlaps(m.range) }) continue
                val number = numberOf(m.groupValues[1]) ?: continue
                found += Numbered(number, null, m.range)
            }
        }
        return found
    }

    /** 1–4 from a digit or an ordinal word, cs or en; "last"/„poslední“ is the fourth. */
    private fun quarterOf(word: String): Int? =
        word.toIntOrNull()?.takeIf { it in 1..4 }
            ?: when {
                word == "first" || word == "1st" || word.startsWith("prvn") -> 1
                word == "second" || word == "2nd" || word.startsWith("druh") -> 2
                word == "third" || word == "3rd" || word.startsWith("tret") -> 3
                word == "fourth" ||
                    word == "4th" ||
                    word == "last" ||
                    word.startsWith("ctvrt") ||
                    word.startsWith("posledn") -> 4
                else -> null
            }

    /** 1–2 from a digit or an ordinal word, cs or en. */
    private fun halfOf(word: String): Int? =
        word.toIntOrNull()?.takeIf { it in 1..2 }
            ?: when {
                word == "first" || word == "1st" || word.startsWith("prvn") -> 1
                word == "second" || word == "2nd" || word.startsWith("druh") -> 2
                else -> null
            }
}

/** Do two ranges of one text share a character? */
internal fun IntRange.overlaps(other: IntRange): Boolean = first <= other.last && other.first <= last
