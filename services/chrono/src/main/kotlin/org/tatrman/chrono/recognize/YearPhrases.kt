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
 *   date, nor is it a quarter or December an earlier question names ([ConversationPeriods]);
 * - [speaksOfYear]: whether a span with no year a rule reads says anything else about one (RR17). A yearless period
 *   takes a fallback year only when it does not.
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

    /**
     * A numbered period right after "each", "every" or „každé“: "every Q2", „každé 2. čtvrtletí“. Not an ordinal with a
     * hyphen after it: "every first-time buyer" (RR20).
     */
    private const val NUMBER_AFTER =
        """(?=\s+(?:the\s+)?(?:[1-4]\.|q[1-4]\b|h[12]\b|(?:first|second|third|fourth|1st|2nd|3rd|4th|last)\b(?!-)""" +
            """|(?:prvn|druh|tret|ctvrt(?!let)|posledn)\w*\b(?!-)))"""

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

    /**
     * A year noun or adverb, in any number and case: *year(s)*, *yr(s)*, *annually*, *yearly*; „rok“, „roku“, „roce“,
     * „rokem“, „roky“, „letech“, „lety“, „ročně“, „každoročně“, „napřesrok“. Czech „let“ only after a genitive plural,
     * a number or „podle“ („posledních let“, „pět let“, „podle let“): "let" is an English verb too.
     */
    private const val YEAR_NOUN =
        """(?:years?|yrs?|annually|yearly|annum|rok|roku|roce|rokem|roky|letech|lety|rocne|kazdorocne|napresrok)"""
    private const val BEFORE_LET =
        """(?:\b[a-z]*ch|\b\d+|\b(?:podle|behem|mezi|dvou|tri|ctyr|pet|sest|sedm|osm|devet|deset|mnoha|nekolika|par))"""
    private val yearWordRe = Regex("""\b$YEAR_NOUN\b|$BEFORE_LET\s+let\b""")

    /** Stands for the period a rule read, in the text [speaksOfYear] searches: a frame may start right after it. */
    private const val OWN = '\u0000'

    /** What places a year noun in time, right after it: "the year before", "the year ahead", „rok předtím“. */
    private const val UNPLACED =
        """(?!\s+(?:before|prior|after|ago|earlier|later|ahead|from\s+now|to\s+come|predtim|pote|drive|pozdeji|zpet)\b)"""

    private const val EN_FRAME_ADJ = """(?:fiscal|financial|accounting|calendar|full|complete|whole|entire|present)"""
    private const val CS_FRAME_ADJ =
        """(?:fiskaln|financn|ucetn|kalendarn|hospodarsk|cel|upln|bezn|stavajic|probihajic)\w*"""

    /**
     * The year nouns that say nothing about which year ([speaksOfYear]), blanked before it looks for one:
     * - a measure: "year over year", "year-on-year", "year-end";
     * - "(of / in / for / during) (the / that) (fiscal …) year", or "(fiscal …) year" right after the period ("Q2
     *   fiscal year"); "the present year" is the current one (RR20);
     * - „(toho / tohoto) (fiskálního …) roku“, or „(fiskálního …) roku“ right after the period; „v (tomto / celém)
     *   roce“; „běžného / stávajícího / probíhajícího roku“ is the current year (RR20).
     *
     * None of them with a word after it that places it in time ([UNPLACED]). *by year*, *per year*, *a year*, *each
     * year*, „za rok“, „po letech“ are no frame: a breakdown, or a year apart.
     */
    private val neutralRes =
        listOf(
            Regex("""\byear(?:\s*-\s*|\s+)(?:over|on|to)(?:\s*-\s*|\s+)year\b|\byear(?:\s*-\s*|\s+)end\b"""),
            Regex(
                """(?:(?:^|$OWN)\s*|\b(?:of|in|for|during)\s+(?:(?:the|that)\s+)?|\b(?:the|that)\s+)""" +
                    """(?:$EN_FRAME_ADJ\s+)?year\b$UNPLACED""",
            ),
            Regex("""(?:(?:^|$OWN)\s*|\b(?:toho|tohoto)\s+)(?:$CS_FRAME_ADJ\s+)?roku\b$UNPLACED"""),
            Regex("""\bv\s+(?:(?:tomto|$CS_FRAME_ADJ)\s+)?roce\b$UNPLACED"""),
        )

    /** A two-digit year anywhere: *'25*, *FY25*. */
    private val twoDigitYearRe = Regex("""['’‘]\s*\d{2}\b|\bfy\s*['’‘]?\s*\d{2}\b""")

    /** One or two digits joined to the period: "Q2 25", "Q2/25", „2. čtvrtletí 25“; not a share ("Q2 25%"). */
    private val digitsAfterRe = Regex("""^(?:\s*[/'’‘-]\s*|\s+)\d{1,2}\b(?!\s*%)""")

    /** A relative word right before the period: "next Q2", „minulé 2. čtvrtletí“, "the coming fiscal Q1". */
    private val relativeBeforeRe =
        Regex(
            """(?:\b(?:next|coming|upcoming|following|last|previous|prior|past)""" +
                """|\b(?:pristi|nadchazejic|nasledujic|minul|predchoz|predesl|uplynul)\w*)\s+$EN_ADJ$CS_ADJ$""",
        )

    /** "Q2 before last", "Q2 after next". */
    private val relativeAfterRe = Regex("""^\s+(?:before\s+last|after\s+next)\b""")

    /** Does the folded [n] hold a year phrase chrono reads nowhere? */
    fun unreadIn(n: String): Boolean = unreadRe.containsMatchIn(n)

    /**
     * Does the folded [n] say anything about a year outside [own], the range a rule read (RR17, A-TS-3)? A yearless
     * period may take the conversation's or the reference's year only when it does not. That is an allow-list: what
     * may stand beside the period is a neutral frame ("of the fiscal year", „kalendářního roku“, "year over year"),
     * and anything else that names a year says something about which one:
     * - a year noun or adverb ([yearWordRe]): "Q2 in previous years", "Q2 annually", "Q2 by year", "the second quarter
     *   of the contract year", „2. čtvrtletí o rok dříve“, „… posledních let“;
     * - a two-digit year: "Q2 FY25", "Q2 '25", "Q2/25", „2. čtvrtletí 25“;
     * - a relative word right before the period, or "before last" / "after next" right after it: "next Q2", "last Q4",
     *   „minulé 2. čtvrtletí“, "Q2 before last".
     *
     * Asked only where no year was read: a read year word ("of last year", „loňského roku“) says the year first.
     */
    fun speaksOfYear(
        n: String,
        own: IntRange,
    ): Boolean {
        val before = n.substring(0, own.first)
        val after = n.substring(own.last + 1)
        if (relativeBeforeRe.containsMatchIn(before) || relativeAfterRe.containsMatchIn(after)) return true
        if (digitsAfterRe.containsMatchIn(after)) return true
        val rest = before + OWN.toString().repeat(own.last - own.first + 1) + after
        if (twoDigitYearRe.containsMatchIn(rest)) return true
        val unframed = neutralRes.fold(rest) { s, re -> re.replace(s) { " ".repeat(it.value.length) } }
        return yearWordRe.containsMatchIn(unframed)
    }
}
