// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

import org.tatrman.text.Normalization
import java.time.LocalDate

/**
 * The year a text names — what a date that names none leans on.
 *
 * "po celý měsíc říjen" is October of the year the conversation is about: the October of
 * "v říjnu 2025" one sentence earlier, not the reference's. [DateRecognizer] asks this of the
 * span first, then of the question around it, then of each earlier question, newest first
 * ([firstOf]).
 *
 * What counts as naming a year is narrower than "contains four digits", on purpose: "nad 2000 Kč"
 * holds a plausible year and is an amount. A year counts only where the words around it make it
 * one:
 *  - after a month — "říjnu 2025", "15. října 2025", "October 15, 2025";
 *  - after a year word — "v roce 2025", "za rok 2025", "fiscal year 2025", "FY2025";
 *  - after a preposition only a time takes — "in 2025", "during 2025", "since 2025",
 *    "během 2025" (not "for", "od", "do": "for 2000 dollars", "do 2000 Kč");
 *  - inside a full date — "15.10.2025", "2025-10-15", "2025-10";
 *  - as a year relative to the reference — "letos", "loni", "předloni", "minulý rok",
 *    "this year", "last year".
 *
 * Any other bare "2025" is NOT read. It looks exactly like an amount, and missing it costs only the
 * reading chrono gave before this existed (the reference year), where misreading an amount would
 * put a year into the question that the user never said.
 */
object NamedYear {
    /**
     * The range a four-digit run has to fall in to be read as a year — shared by every rule that
     * reads one, so no two of them disagree about what counts as a plausible year.
     */
    val PLAUSIBLE_YEARS = 1900..2999

    /** A word, a day-of-month (with its ordinal suffix or Czech dot) and a four-digit run. */
    private val afterWordRe = Regex("""\b([a-z]+)\.?(?:\s+\d{1,2}(?:st|nd|rd|th)?\.?,?)?\s+(\d{4})\b""")
    private val afterYearWordRe = Regex("""\b(?:rok|roku|roce|rokem|year|fy)\s*(\d{4})\b""")
    private val afterTimePrepositionRe = Regex("""\b(?:in|during|since|until|throughout|behem)\s+(\d{4})\b""")
    private val numericDateRe = Regex("""\b\d{1,2}\s*[./]\s*\d{1,2}\s*[./]\s*(\d{4})\b""")
    private val isoDateRe = Regex("""\b(\d{4})-\d{1,2}(?:-\d{1,2})?\b""")

    /** Relative years, folded: two back, one back, this one. */
    private val twoBackRe = Regex("""\bpredlon\w*|\bpredminul\w*\s+(?:rok|roku|roce|rokem)\b|\byear before last\b""")
    private val lastRe =
        Regex(
            """\b(?:v?loni|lonsk\w*)\b|\b(?:minul|predchoz)\w*\s+(?:rok|roku|roce|rokem)\b|\b(?:last|previous) year\b""",
        )
    private val thisRe =
        Regex(
            """\bletos\w*|\b(?:tento|tomto|tohoto|tenhle|aktualn\w*|soucasn\w*)\s+(?:rok|roku|roce|rokem)\b""" +
                """|\b(?:this|current) year\b""",
        )

    /**
     * Every year [text] names, by the rules above. Empty when it names none; more than one when it
     * names several, which is a different answer from one — "October 2024 against October 2025"
     * says nothing about which year a third, yearless month is in.
     */
    fun yearsIn(
        text: String,
        reference: LocalDate,
    ): Set<Int> {
        val n = Normalization.fold(text)
        val years = mutableSetOf<Int>()
        afterWordRe.findAll(n).forEach { m ->
            if (Months.find(m.groupValues[1]) != null) years += m.groupValues[2].toInt()
        }
        sequenceOf(afterYearWordRe, afterTimePrepositionRe, numericDateRe, isoDateRe)
            .flatMap { it.findAll(n) }
            .forEach { years += it.groupValues[1].toInt() }
        years.retainAll { it in PLAUSIBLE_YEARS }
        // Each relative word must START a word: "předloni" contains "loni", and the `\b` in front of
        // `loni` is what keeps a year two back from also reading as one back.
        if (twoBackRe.containsMatchIn(n)) years += reference.year - 2
        if (lastRe.containsMatchIn(n)) years += reference.year - 1
        if (thisRe.containsMatchIn(n)) years += reference.year
        return years
    }

    /** The one year [text] names — null when it names none, or several. */
    fun single(
        text: String,
        reference: LocalDate,
    ): Int? = yearsIn(text, reference).singleOrNull()

    /**
     * The year of the first of [texts] that names any — null when none does, or when the first that
     * does names several. Nearest first: a question that names its own year is not overruled by
     * the one before it, and a question that names two is not settled by an older one.
     */
    fun firstOf(
        texts: List<String>,
        reference: LocalDate,
    ): Int? {
        for (text in texts) {
            val years = yearsIn(text, reference)
            if (years.isNotEmpty()) return years.singleOrNull()
        }
        return null
    }
}
