// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

/**
 * Which year phrases chrono reads and which it reads nowhere, over the folded text (`Normalization.fold`). One table,
 * so the rules that lean on it cannot drift (review-215 RR13, A-TS-3):
 * - [EN_READ], [CS_READ], [CS_READ_LOC]: the year words [PeriodWords] reads, which [NumberedPeriods] takes after "the
 *   last quarter of …" ("… of the previous year", „poslední čtvrtletí loňského roku“, „… v loňském roce“);
 * - [unreadRe]: a year chrono reads nowhere — next / following / coming year, the year after, each / every year, a year
 *   ago, earlier or from now, in two years, „příštího roku“, „následujícího roku“, „každý rok“, „před rokem“, and each /
 *   „každé“ before a numbered period. A quarter, half or last month in one is not read ([DateRecognizer]'s numbered
 *   rule; [PartOfPeriods] takes "the last quarter of next year", which [NumberedPeriods] does not match), nor is year to
 *   date, nor is it a quarter or December an earlier question names ([ConversationPeriods]).
 */
internal object YearPhrases {
    /** The English year words chrono reads, as [PeriodWords] reads them. */
    const val EN_READ = """(?:this|current|last|previous|prior|past)"""

    /** The Czech ones, in the genitive: „(poslední čtvrtletí) loňského roku“. */
    const val CS_READ = """(?:tohoto|letosniho|minuleho|lonskeho|predchoziho|predesleho)"""

    /** The Czech ones, in the locative: „v loňském roce“. */
    const val CS_READ_LOC = """(?:tomto|letosnim|minulem|lonskem|predchozim|predeslem)"""

    private const val EN_ADJ = PeriodWords.EN_ADJ
    private const val CS_ADJ = PeriodWords.CS_ADJ
    private const val ROK = """$CS_ADJ(?:rok|roku|roce|rokem)\b"""

    /** A numbered period right after "each", "every" or „každé“: "every Q2", „každé 2. čtvrtletí“. */
    private const val NUMBER_AFTER =
        """(?=\s+(?:the\s+)?(?:[1-4]\.|q[1-4]\b|h[12]\b|(?:first|second|third|fourth|1st|2nd|3rd|4th|last)\b""" +
            """|prvn|druh|tret|ctvrt(?!let)|posledn))"""

    private val unreadRe =
        Regex(
            """\b(?:next|following|coming|upcoming|each|every)\s+${EN_ADJ}years?\b""" +
                """|\byear\s+after\b""" +
                """|\b(?:a|one|\d+|two|three)\s+years?\s+(?:ago|earlier|before|later|from\s+now)\b""" +
                """|\bin\s+(?:a|one|\d+|two|three)\s+years?\b""" +
                """|\b(?:pristi|presprist|nasledujic|nadchazejic|kazd)\w*\s+$ROK""" +
                """|\bpred\s+(?:\w+\s+)?(?:rokem|lety)\b|\bnapresrok\b""" +
                """|\b(?:each|every|kazd\w*)$NUMBER_AFTER""",
        )

    /** Does the folded [n] hold a year phrase chrono reads nowhere? */
    fun unreadIn(n: String): Boolean = unreadRe.containsMatchIn(n)
}
