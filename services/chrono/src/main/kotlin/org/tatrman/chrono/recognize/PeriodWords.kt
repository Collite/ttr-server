// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

import java.time.LocalDate

/**
 * The calendar units a relative period word comes in (#214). A period of a unit is one integer, its
 * index, so "one before" is `index - 1` across a year boundary: a year is itself, a quarter is
 * `year * 4 + (q - 1)`, a month is `year * 12 + (m - 1)`.
 */
enum class PeriodUnit {
    YEAR,
    QUARTER,
    MONTH,
    ;

    /** The index of the period of this unit that [date] is in. */
    fun indexOf(date: LocalDate): Int =
        when (this) {
            YEAR -> date.year
            QUARTER -> date.year * 4 + (date.monthValue - 1) / 3
            MONTH -> date.year * 12 + date.monthValue - 1
        }

    /** The first day of the period [index]. */
    fun startOf(index: Int): LocalDate =
        when (this) {
            YEAR -> LocalDate.of(index, 1, 1)
            QUARTER -> LocalDate.of(Math.floorDiv(index, 4), Math.floorMod(index, 4) * 3 + 1, 1)
            MONTH -> LocalDate.of(Math.floorDiv(index, 12), Math.floorMod(index, 12) + 1, 1)
        }

    /** The period [index] as its code: `2025`, `2025Q1`, `202502`. */
    fun codeOf(index: Int): String =
        when (this) {
            YEAR -> "%04d".format(index)
            QUARTER -> "%04dQ%d".format(Math.floorDiv(index, 4), Math.floorMod(index, 4) + 1)
            MONTH -> "%04d%02d".format(Math.floorDiv(index, 12), Math.floorMod(index, 12) + 1)
        }

    /** The unit's name in a sentence. */
    val noun: String get() = name.lowercase()
}

/**
 * What a relative period word is read against (#214, Bora's ruling of 2026-10-14).
 */
enum class PeriodWordKind {
    /** The reference date: "this year", "last month", „loni“, „poslední čtvrtletí“. */
    DEICTIC,

    /**
     * The period of the same unit the conversation is about, and the reference date when it names none:
     * "the previous year", "the month before", „předchozí čtvrtletí“, „předešlý rok“.
     */
    ANAPHORIC,

    /** Either, so ask when the two readings differ: „minulý rok“, „minulý měsíc“, „minulé čtvrtletí“. */
    AMBIGUOUS,
}

/**
 * A relative period word found in a folded text: its unit, its kind, how many periods back it steps
 * ([delta]: 0 this one, -1 the one before, -2 the one before that), and where it stands.
 */
data class PeriodWord(
    val unit: PeriodUnit,
    val kind: PeriodWordKind,
    val delta: Int,
    val range: IntRange,
    val text: String,
)

/**
 * The relative period words of years, quarters and months, cs and en, as phrases over the folded text
 * (`Normalization.fold`: lower-case, no diacritics). One table serves both readers: [DateRecognizer] reads
 * the word a span IS, and [ConversationPeriods] reads the words an earlier question USED.
 *
 * A Czech scope word declines with its noun (*minulý rok*, *minulého roku*, *v minulém roce*, *minulým
 * rokem*), so a phrase is a stem and a noun in any case form, with an optional fiscal or calendar
 * adjective between them. The one-word years (*letos*, *loni*, *loňský*, *minuloroční*, *předloni*) need no
 * noun. The nouns stop short of their adjectives: *měsíční* and *čtvrtletní* name a grain, not a period.
 *
 * „poslední“ is a scope word for a quarter only. *Za poslední měsíc* and *za poslední rok* are as often the
 * trailing thirty days or twelve months as the calendar period before, so for a month or a year it stays no
 * scope word at all, as it was before #214.
 *
 * A phrase right before a four-digit year is not relative: "last year 2024" is 2024 and "the last quarter of
 * 2025" is a quarter OF 2025 (#213), so neither is read here.
 *
 * Weeks are not in the table: #214 ruled on years, months and quarters, and a week keeps reading against the
 * reference date ([DateRecognizer]'s week arm, unchanged).
 */
object PeriodWords {
    private data class Rule(
        val unit: PeriodUnit,
        val kind: PeriodWordKind,
        val delta: Int,
        val re: Regex,
    )

    /** Not a relative word when a year follows: "last year 2024", "the last quarter of 2025", „poslední čtvrtletí roku 2025“. */
    private const val NO_YEAR_AFTER = """(?![\s(]*(?:of\s+|in\s+|roku\s+)?\d{4})"""

    /** An adjective between a scope word and its noun: „minulý fiskální rok“, „předchozí celý měsíc“. */
    internal const val CS_ADJ = """(?:(?:fiskaln|financn|ucetn|kalendarn|cel|upln)\w*\s+)?"""

    /** An adjective between a scope word and its noun: "the previous fiscal year", "last full month". */
    internal const val EN_ADJ = """(?:(?:fiscal|financial|accounting|calendar|full|complete|whole|entire)\s+)?"""

    private const val CS_THIS =
        """(?:tento|toto|tato|tomto|tohoto|tomuto|timto|tenhle|tohle|tomhle|tohohle""" +
            """|aktualn\w*|soucasn\w*)"""

    private const val ROK = """$CS_ADJ(?:rok|roku|roce|rokem)\b"""
    private const val MESIC = """${CS_ADJ}mesic(?!n)\w*"""
    private const val CTVRTLETI = """${CS_ADJ}ctvrtlet(?!n)\w*"""

    /**
     * The `\b` ends a phrase on a whole word, so a trailing `\w*` cannot give letters back to slip past the
     * year check ("loňského 2025" must not match as "loňské").
     */
    private fun rule(
        unit: PeriodUnit,
        kind: PeriodWordKind,
        delta: Int,
        vararg alternatives: String,
    ) = Rule(unit, kind, delta, Regex(alternatives.joinToString("|") { """(?:$it)\b$NO_YEAR_AFTER""" }))

    private val rules: List<Rule> =
        listOf(
            // ---- years ----
            rule(
                PeriodUnit.YEAR,
                PeriodWordKind.DEICTIC,
                0,
                """\bletos\w*""",
                """\btohorocn\w*""",
                """\b$CS_THIS\s+$ROK""",
                """\b(?:this|current)\s+${EN_ADJ}year\b""",
            ),
            rule(
                PeriodUnit.YEAR,
                PeriodWordKind.DEICTIC,
                -1,
                """\bv?loni\b""",
                """\bv?lonsk\w*""",
                // „minuloroční“ is deictic (ruled 2026-10-14), unlike „minulý rok“.
                """\bminulorocn\w*""",
                """\blast\s+${EN_ADJ}year\b""",
            ),
            rule(
                PeriodUnit.YEAR,
                PeriodWordKind.DEICTIC,
                -2,
                """\bpredlon\w*""",
                """\bpredminulorocn\w*""",
                """\bpredminul\w*\s+$ROK""",
                """\byear\s+before\s+last\b""",
            ),
            rule(
                PeriodUnit.YEAR,
                PeriodWordKind.ANAPHORIC,
                -1,
                """\b(?:predchoz|predesl)\w*\s+$ROK""",
                """\b(?:previous|prior)\s+${EN_ADJ}year\b""",
                """\byear\s+(?:before|prior)\b(?!\s+last)""",
            ),
            rule(PeriodUnit.YEAR, PeriodWordKind.AMBIGUOUS, -1, """\bminul(?!orocn)\w*\s+$ROK"""),
            // ---- months ----
            rule(
                PeriodUnit.MONTH,
                PeriodWordKind.DEICTIC,
                0,
                """\b(?:this|current)\s+${EN_ADJ}month\b""",
                """\b$CS_THIS\s+$MESIC""",
            ),
            rule(PeriodUnit.MONTH, PeriodWordKind.DEICTIC, -1, """\blast\s+${EN_ADJ}month\b"""),
            rule(
                PeriodUnit.MONTH,
                PeriodWordKind.DEICTIC,
                -2,
                """\bpredminul\w*\s+$MESIC""",
                """\bmonth\s+before\s+last\b""",
            ),
            rule(
                PeriodUnit.MONTH,
                PeriodWordKind.ANAPHORIC,
                -1,
                """\b(?:predchoz|predesl)\w*\s+$MESIC""",
                """\b(?:previous|prior)\s+${EN_ADJ}month\b""",
                """\bmonth\s+(?:before|prior)\b(?!\s+last)""",
            ),
            rule(PeriodUnit.MONTH, PeriodWordKind.AMBIGUOUS, -1, """\bminul\w*\s+$MESIC"""),
            // ---- quarters ----
            rule(
                PeriodUnit.QUARTER,
                PeriodWordKind.DEICTIC,
                0,
                """\b(?:this|current)\s+${EN_ADJ}quarter\b""",
                """\b$CS_THIS\s+$CTVRTLETI""",
            ),
            rule(
                PeriodUnit.QUARTER,
                PeriodWordKind.DEICTIC,
                -1,
                """\b(?:last|past)\s+${EN_ADJ}quarter\b""",
                """\bposledn\w*\s+$CTVRTLETI""",
            ),
            rule(
                PeriodUnit.QUARTER,
                PeriodWordKind.DEICTIC,
                -2,
                """\bpredminul\w*\s+$CTVRTLETI""",
                """\bquarter\s+before\s+last\b""",
            ),
            rule(
                PeriodUnit.QUARTER,
                PeriodWordKind.ANAPHORIC,
                -1,
                """\b(?:predchoz|predesl)\w*\s+$CTVRTLETI""",
                """\b(?:previous|prior)\s+${EN_ADJ}quarter\b""",
                """\bquarter\s+(?:before|prior)\b(?!\s+last)""",
            ),
            rule(PeriodUnit.QUARTER, PeriodWordKind.AMBIGUOUS, -1, """\bminul\w*\s+$CTVRTLETI"""),
        )

    /** Every relative word of [unit] in the folded text [n], in the order they stand. */
    fun findAll(
        n: String,
        unit: PeriodUnit,
    ): List<PeriodWord> =
        rules
            .filter { it.unit == unit }
            .flatMap { rule ->
                rule.re.findAll(n).map { PeriodWord(unit, rule.kind, rule.delta, it.range, it.value) }
            }.sortedBy { it.range.first }

    /** Every relative word of any unit in the folded text [n]. */
    fun findAll(n: String): List<PeriodWord> = PeriodUnit.entries.flatMap { findAll(n, it) }
}
