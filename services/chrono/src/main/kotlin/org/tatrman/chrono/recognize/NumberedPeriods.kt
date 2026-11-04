// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

/**
 * Quarters and half-years named by their number, cs and en, over the folded text (`Normalization.fold`):
 * „2. čtvrtletí 2025“, „druhý kvartál“, "Q2 2025", "2025Q2", "the second quarter of 2025", „1. pololetí“,
 * "H1 2026", "the first half of 2026" — and the last month of a year, which is its twelfth (✅R215-R). One table
 * serves both readers, as [PeriodWords] does: [DateRecognizer] reads the period a span IS, [ConversationPeriods] the
 * quarters and months an earlier question names.
 *
 * A number with a year of its own is matched first, and a yearless one only where no such match stands. "last"
 * and „poslední“ count as the fourth quarter, the second half and December only before a year ("the last quarter
 * of 2025", "the last half of 2025") or the word for one ("the last quarter of the year", "the last month of the
 * previous year", „poslední pololetí loňského roku“ — then a yearless period, its year from the year word if any):
 * alone they are relative or trailing words, [PeriodWords]' and [TrailingPeriods]'. One of a year chrono cannot
 * name ("next year", „každého roku“) is [PartOfPeriods]'. An English ordinal half needs its year or the word for one
 * ("the first half of 2026", "the second half of last year"); alone, "the first half" is as often a half of
 * something else. The nouns stop short of their adjectives: *čtvrtletní*, *kvartální* and *pololetní* name a grain,
 * not a period.
 */
internal object NumberedPeriods {
    /**
     * One numbered period: its [number] in the year (1–4 for a quarter, 1–2 for a half, 12 for the last month), its
     * [year] if written, and
     * whether a [yearWord] in it says the year ("of last year", „loňského roku“): a reader that cannot read that word
     * must not fall back to another year.
     */
    data class Numbered(
        val number: Int,
        val year: Int?,
        val range: IntRange,
        val yearWord: Boolean = false,
    )

    private const val CS_ADJ = PeriodWords.CS_ADJ
    private const val EN_ADJ = PeriodWords.EN_ADJ
    private const val CS_QUARTER = """(?:ctvrtlet|kvartal)(?!n)\w*"""
    private const val CS_HALF = """pololet(?!n)\w*"""
    private const val EN_QUARTER = """quarter\b"""
    private const val EN_ORDINALS = """first|second|third|fourth|1st|2nd|3rd|4th"""

    /**
     * What says which year a "last quarter of the year" is in: a word [PeriodWords] reads as a year ([YearPhrases]), or
     * none — an article, "that" or „toho“, and the year comes from the conversation. Not "next" or „příštího“, which
     * chrono does not read: the quarter would land in the reference's year ([PartOfPeriods] takes those).
     */
    private const val EN_YEAR_WORD = YearPhrases.EN_READ
    private const val CS_YEAR_WORD = YearPhrases.CS_READ

    /** "of / in (the) (previous) (fiscal) year", the year word in group 2; not "year-over-year", "year-end". */
    private const val EN_OF_YEAR =
        """\s+(?:of|in)\s+(?:(?:the|that)\s+)?(?:($EN_YEAR_WORD)\s+)?${EN_ADJ}year\b(?!-|\s+(?:over|on)\b)"""

    /** „(loňského) roku“, the year word in group 2. */
    private const val CS_OF_YEAR = """\s+(?:toho\s+|($CS_YEAR_WORD)\s+)?${CS_ADJ}roku\b"""

    /** „v (loňském) roce“, the year word in group 3, after [CS_OF_YEAR]'s. */
    private const val CS_YEAR_LOC = YearPhrases.CS_READ_LOC
    private const val CS_IN_YEAR = """\s+v\s+(?:($CS_YEAR_LOC)\s+)?${CS_ADJ}roce\b"""

    /**
     * „poslední měsíc“ in the singular, the adjective's case going with the noun's ([TrailingPeriods]): not *poslední
     * měsíce* (the last months).
     */
    private const val CS_LAST_MONTH =
        """(?:posledni\s+${CS_ADJ}mesic|posledniho\s+${CS_ADJ}mesice|poslednimu\s+${CS_ADJ}mesici""" +
            """|poslednim\s+$CS_ADJ(?:mesici|mesicem))\b"""

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
            // the last quarter of the year · in the last quarter of the previous year (Bora 2026-11-03: the fourth)
            Regex("""\b(last)\s+$EN_ADJ$EN_QUARTER$EN_OF_YEAR"""),
            // poslední čtvrtletí roku · v posledním kvartálu loňského roku
            Regex("""\b(posledn\w*)\s+$CS_ADJ$CS_QUARTER$CS_OF_YEAR"""),
        )

    private val halfWithYear =
        listOf(
            // H1 2026 · H1/2026
            Pattern(Regex("""\bh([12])\s*[/-]?\s*(\d{4})\b""")),
            // 2026 H1 · 2026H1 · 2026-H1
            Pattern(Regex("""\b(\d{4})\s*[/-]?\s*h([12])\b"""), yearFirst = true),
            // 1. pololetí 2026 · v 1. pololetí roku 2026
            Pattern(Regex("""\b([12])\.\s*$CS_ADJ$CS_HALF\s+(?:roku\s+)?(\d{4})\b""")),
            // první pololetí 2026 · ve druhém pololetí roku 2026 · poslední pololetí 2025
            Pattern(Regex("""\b(prvn\w*|druh\w*|posledn\w*)\s+$CS_ADJ$CS_HALF\s+(?:roku\s+)?(\d{4})\b""")),
            // the first half of 2026 · the second half-year of 2025 · the last half of 2025
            Pattern(Regex("""\b(first|second|1st|2nd|last)\s+half(?:[\s-]+year)?\s+(?:of\s+|in\s+)?(\d{4})\b""")),
        )
    private val halfYearless =
        listOf(
            Regex("""\bh([12])\b"""),
            Regex("""\b([12])\.\s*$CS_ADJ$CS_HALF"""),
            Regex("""\b(prvn\w*|druh\w*)\s+$CS_ADJ$CS_HALF"""),
            // the second half of last year · the last half of the year (Bora 2026-11-04: the second)
            Regex("""\b(first|second|1st|2nd|last)\s+half(?:[\s-]+year)?$EN_OF_YEAR"""),
            // poslední pololetí roku · v posledním pololetí loňského roku
            Regex("""\b(posledn\w*)\s+$CS_ADJ$CS_HALF$CS_OF_YEAR"""),
        )

    private val monthWithYear =
        listOf(
            // the last month of 2025
            Pattern(Regex("""\b(last\s+${EN_ADJ}month)\s+(?:of|in)\s+(\d{4})\b""")),
            // poslední měsíc roku 2025 · v posledním měsíci roku 2025 · poslední měsíc v roce 2025
            Pattern(Regex("""\b($CS_LAST_MONTH)\s+(?:roku\s+|v\s+roce\s+)?(\d{4})\b""")),
        )
    private val monthYearless =
        listOf(
            // the last month of the year · the last month of last year (Bora 2026-11-04: December)
            Regex("""\b(last\s+${EN_ADJ}month)$EN_OF_YEAR"""),
            // poslední měsíc roku · v posledním měsíci loňského roku · poslední měsíc v roce
            Regex("""\b($CS_LAST_MONTH)(?:$CS_OF_YEAR|$CS_IN_YEAR)"""),
        )

    /** The quarters the folded [n] names by number, in the order the patterns find them. */
    fun quarters(n: String): List<Numbered> = find(n, quarterWithYear, quarterYearless, ::quarterOf)

    /** The half-years the folded [n] names by number. */
    fun halves(n: String): List<Numbered> = find(n, halfWithYear, halfYearless, ::halfOf)

    /** The last months of a year the folded [n] names: each is December, its [Numbered.number] 12. */
    fun lastMonths(n: String): List<Numbered> = find(n, monthWithYear, monthYearless) { 12 }

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
                found += Numbered(number, null, m.range, yearWord = m.groupValues.drop(2).any { it.isNotEmpty() })
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

    /** 1–2 from a digit or an ordinal word, cs or en; "last"/„poslední“ is the second. */
    private fun halfOf(word: String): Int? =
        word.toIntOrNull()?.takeIf { it in 1..2 }
            ?: when {
                word == "first" || word == "1st" || word.startsWith("prvn") -> 1
                word == "second" ||
                    word == "2nd" ||
                    word == "last" ||
                    word.startsWith("druh") ||
                    word.startsWith("posledn") -> 2
                else -> null
            }
}

/** Do two ranges of one text share a character? */
internal fun IntRange.overlaps(other: IntRange): Boolean = first <= other.last && other.first <= last
