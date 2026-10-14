// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

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
 *  - after a quarter — "Q2 2025", "2025Q2", „2. čtvrtletí 2025“, "the second quarter of 2025";
 *  - as a year relative to the reference, adverb or adjective in any case form — "letos",
 *    "letošní prodeje", "tohoroční", "loni", "vloni", "loňské výpadky zásob", "vloňský",
 *    "minuloroční", "předloni", "předloňský", "předminuloroční", "this year", "last year's";
 *  - as a year relative to the conversation — "the previous year", „předchozí / předešlý rok“ — or
 *    to either — „minulý rok“ (#214). Those are read by [PeriodWords] and [ConversationPeriods], which
 *    also read the written years here.
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

    /** After a quarter: "Q2 2025", "2025Q2", „2. čtvrtletí 2025“, "the second quarter of 2025" (#214). */
    private val afterQuarterRe =
        Regex("""\b(?:q[1-4]|ctvrtlet(?!n)\w*|quarter)\s*(?:of\s+|in\s+|roku\s+|[/-]\s*)?(\d{4})\b""")
    private val beforeQuarterRe = Regex("""\b(\d{4})\s*[/-]?\s*q[1-4]\b""")

    /** Any four-digit run: a year only if the words around it say so ([writtenYears]), else [bareYears]. */
    private val fourDigitsRe = Regex("""\b(\d{4})\b""")

    /** The words that make a number an amount: a currency or a unit after it, a comparison or a sign before. */
    private val amountAfterRe =
        Regex(
            """^\s*(?:kc|czk|usd|eur|gbp|korun\w*|dolar\w*|dollars?|euros?|ks|kus\w*|pcs|pieces?|units?|items?|%|,-)""",
        )
    private val amountBeforeRe =
        Regex("""(?:[${'$'}€£]|\b(?:over|under|above|below|than|top|nad|pod|pres|nez|aspon|alespon))\s*$""")

    /**
     * Every year [text] names, by the rules above. Empty when it names none; more than one when it
     * names several, which is a different answer from one — "October 2024 against October 2025"
     * says nothing about which year a third, yearless month is in.
     *
     * A year said relative to the reference is read the way [ConversationPeriods] reads it, with no
     * conversation before the text: "minulý rok" and "the previous year" are the year before the reference.
     */
    fun yearsIn(
        text: String,
        reference: LocalDate,
    ): Set<Int> = ConversationPeriods(listOf(text), reference).yearsIn(0)

    /** The years the folded [n] writes out in digits, where the words around them make them years. */
    fun writtenYears(n: String): Set<Int> {
        val years = mutableSetOf<Int>()
        afterWordRe.findAll(n).forEach { m ->
            if (Months.find(m.groupValues[1]) != null) years += m.groupValues[2].toInt()
        }
        sequenceOf(afterYearWordRe, afterTimePrepositionRe, numericDateRe, isoDateRe, afterQuarterRe, beforeQuarterRe)
            .flatMap { it.findAll(n) }
            .forEach { years += it.groupValues[1].toInt() }
        years.retainAll { it in PLAUSIBLE_YEARS }
        return years
    }

    /**
     * The four-digit runs in the folded [n] that could be years, written or not, less the ones that are
     * plainly amounts ("over 2000 dollars", „nad 2000 Kč“). Not years by themselves — "2025" alone looks
     * exactly like an amount — but an anaphoric word cannot read past one either (#214).
     */
    fun bareYears(n: String): Set<Int> =
        fourDigitsRe
            .findAll(n)
            .filterNot { amountBeforeRe.containsMatchIn(n.substring(0, it.range.first)) }
            .filterNot { amountAfterRe.containsMatchIn(n.substring(it.range.last + 1)) }
            .map { it.value.toInt() }
            .filter { it in PLAUSIBLE_YEARS }
            .toSet()

    /** The one year [text] names — null when it names none, or several. */
    fun single(
        text: String,
        reference: LocalDate,
    ): Int? = yearsIn(text, reference).singleOrNull()

    /**
     * The year of the first of [texts] that names any — null when none does, or when the first that
     * does names several. Nearest first: a question that names its own year is not overruled by
     * the one before it, and a question that names two is not settled by an older one. A relative year in
     * an earlier text is read against the texts after it, the way chrono read it then (#214).
     */
    fun firstOf(
        texts: List<String>,
        reference: LocalDate,
    ): Int? = ConversationPeriods(texts, reference).leanedYear()
}
