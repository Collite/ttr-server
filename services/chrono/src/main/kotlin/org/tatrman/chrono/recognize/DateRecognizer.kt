// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

import org.tatrman.chrono.recognize.ConversationPeriods.LeanedYear
import org.tatrman.grounding.lexicon.GroundingSlice
import org.tatrman.text.Normalization
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Rule-based cs + en date/period recognizer (A8.3). Duckling's rule-table *approach* ported to
 * Kotlin (see the A8.3 spike verdict): a prioritized list of composable rules over the span,
 * every result resolved against [reference] — there is no `now()` in this class.
 *
 * Rules are tried most-specific first; the first that matches wins. Anything unrecognized returns
 * null → the caller emits UNGROUNDABLE (or, below threshold, calls the llm-gateway fallback, A8.6).
 *
 * Intervals are half-open `[start, end)` with an EXCLUSIVE end (contracts §1.1).
 */
class DateRecognizer {
    private companion object {
        const val CHRONO_KIND = "chrono"

        /**
         * A trigger-supplied scope is weaker evidence than an authored "tento"/"minulý", but it is
         * still above chrono's 0.6 clarification floor — the estate declared the word.
         */
        const val TRIGGERED_SCOPELESS_CONFIDENCE = 0.8

        /** One range for every rule that reads a year, here and in [NamedYear]. */
        val PLAUSIBLE_YEARS = NamedYear.PLAUSIBLE_YEARS

        /**
         * A date whose year came from the question or the conversation rather than from the span.
         * The same as a yearless month read at the reference: the year is inferred either way, and
         * the inference is still well above chrono's 0.6 clarification floor.
         */
        const val LEANED_YEAR_CONFIDENCE = 0.85

        const val QUARTER_MONTHS = 3
        const val HALF_MONTHS = 6
    }

    /**
     * @param triggers RV-P1.6/RV-42 — the estate's `ground:chrono` trigger slice. It answers ONE
     *   question: "is this span mine?". It never says what the span means — every interval below
     *   is still decided by the rules in this class, so an empty slice (no lexicon archive) leaves
     *   recognition exactly as it was. What a trigger buys is the SCOPE-FREE reading: "fiskální
     *   rok" with no year, or a bare "čtvrtletí", is a period at the reference rather than a
     *   fall-through — the estate said the words are its own, so a bare mention is not noise.
     * @param context the conversation, nearest first: the question the span is in, then the user's
     *   earlier questions, newest first. Two readings lean on it ([ConversationPeriods]):
     *   - a date with no year of its own takes the year of the first text that names one — "po celý měsíc
     *     říjen" after "v říjnu 2025" is October 2025, not the reference's October. When that year is one
     *     nobody settled (a „minulý rok“ chrono asked about), the date is in neither the reference's year nor
     *     any other it could name: it is left ungrounded, like an anaphoric word with no anchor;
     *   - an anaphoric period word reads against the period of its unit the conversation is about —
     *     "the previous year" after "Marketplace revenue in 2025" is 2024 (#214).
     *   Empty reads both against the reference date, as before.
     * @return null when the span is no time chrono can read; [ChronoReading.Unanchored] when it is a period
     *   read against the conversation and the conversation gives it nothing to read against.
     */
    fun read(
        span: String,
        reference: LocalDate,
        triggers: GroundingSlice = GroundingSlice.empty(CHRONO_KIND),
        context: List<String> = emptyList(),
    ): ChronoReading? = (readSpan(span, reference, triggers, context) as? SpanReading.Read)?.reading

    /**
     * [read], telling apart the two kinds of null: a span that is no time, and one a rule recognised that names no
     * one period („1. a 2. čtvrtletí“, "next Q2", "excluding Q4"). `Scan` needs the second: a window that names no one
     * period must not be answered by a part of it ([org.tatrman.chrono.scan.SpanScanner]).
     */
    internal fun readSpan(
        span: String,
        reference: LocalDate,
        triggers: GroundingSlice = GroundingSlice.empty(CHRONO_KIND),
        context: List<String> = emptyList(),
    ): SpanReading? {
        val n = Normalization.fold(span).trim()
        if (n.isEmpty()) return null
        val triggered = triggers.matches(span)
        val target = detectTarget(n)
        val conversation = ConversationPeriods(context, reference, PeriodWords.findAll(n))
        val years = Years(stated = spanYear(n, conversation), leaned = lazy { conversation.leanedYear() })
        val base =
            numbered(n, NumberedPeriods.lastMonths(n), 1, ::monthCode, reference, years)
                ?: partOfLarger(n)
                ?: fiscalYear(n, reference)
                ?: yearToDate(n, reference, years.stated)
                ?: numbered(n, NumberedPeriods.quarters(n), QUARTER_MONTHS, ::quarterCode, reference, years)
                ?: numbered(n, NumberedPeriods.halves(n), HALF_MONTHS, ::halfCode, reference, years)
                ?: older(n, reference)
                ?: fiscalQuarter(n, reference, triggered, conversation)
                ?: periodCode(n)?.let(::Hit)
                ?: isoDate(n)?.let(::Hit)
                ?: numericDate(n, reference, years)
                ?: namedMonthDate(n, reference, years)
                ?: relative(n, reference, triggered, conversation)
                ?: calendarYear(n)?.let(::Hit)
                ?: return null
        val reading =
            when (base) {
                NotOne -> return SpanReading.NoOnePeriod
                Several -> return SpanReading.Several
                is NoAnchor ->
                    ChronoReading.Unanchored(
                        "'${span.trim()}' is read against the ${base.unit.noun} the conversation is about, and " +
                            "${base.why}; it is left ungrounded rather than read against the reference date",
                    )
                is Hit ->
                    ChronoReading.Recognized(
                        if (target == null) {
                            base.recognition
                        } else {
                            base.recognition.copy(
                                target = target,
                                alternatives = base.recognition.alternatives.map { it.copy(target = target) },
                            )
                        },
                    )
            }
        return SpanReading.Read(reading)
    }

    /** [read], for a caller that needs only the period: an unanchored span is no period either. */
    fun recognize(
        span: String,
        reference: LocalDate,
        triggers: GroundingSlice = GroundingSlice.empty(CHRONO_KIND),
        context: List<String> = emptyList(),
    ): ChronoRecognition? = (read(span, reference, triggers, context) as? ChronoReading.Recognized)?.recognition

    /** What one rule made of the span. */
    private sealed interface Read

    private data class Hit(
        val recognition: ChronoRecognition,
    ) : Read

    /**
     * The span is no one period, though a rule recognised its shape — „1. a 2. čtvrtletí“ names two. It ends the
     * read: a later rule must not read one part of it as the whole.
     */
    private data object NotOne : Read

    /**
     * The span names several periods side by side, each perhaps one on its own: "Q2 2025 vs 2024", "this quarter and
     * last month", „leden až březen 2025“. It ends the read as [NotOne] does; `Scan` may answer it by its parts.
     */
    private data object Several : Read

    /** [OtherPeriods.outside]'s answer as a [Read]: [Several], or [NotOne] for a comparison that leans on the period. */
    private fun OtherPeriods.Beside.read(): Read =
        when (this) {
            OtherPeriods.Beside.SEVERAL -> Several
            OtherPeriods.Beside.COMPARED -> NotOne
        }

    /** An anaphoric reading of a [unit] word with nothing to read against ([ConversationPeriods.Reading.NoAnchor]). */
    private data class NoAnchor(
        val unit: PeriodUnit,
        val why: String,
    ) : Read

    /**
     * The year a date that writes none is in, from outside its own digits.
     *
     * @property stated a year the span names in words — "loni v říjnu", "letos v září", „v březnu
     *   předchozího roku“. Its own, so it is as good as a written one. „v březnu minulého roku“ may name two
     *   ([SpanYear.Two], #214).
     * @property leaned the year the question or the conversation names (`read`'s `context`).
     */
    private class Years(
        val stated: SpanYear = SpanYear.None,
        leaned: Lazy<LeanedYear> = lazyOf(LeanedYear.None),
    ) {
        /** Read only by a rule that falls back to it: `Scan` reads many windows, and few of them get this far. */
        val leaned: LeanedYear by leaned
    }

    /** The year a span says in words or digits ([spanYear]). */
    private sealed interface SpanYear {
        data object None : SpanYear

        data class One(
            val year: Int,
        ) : SpanYear

        /** „minulý rok“ whose deictic and anaphoric readings differ: the date is in one of the two. */
        data class Two(
            val deictic: Int,
            val anaphoric: Int,
        ) : SpanYear

        data class Unanchored(
            val why: String,
        ) : SpanYear
    }

    /**
     * The year the span itself says: a year it writes ([NamedYear.writtenYears]) or a relative year word,
     * read by its kind against the [conversation]. Two different relative years, or a written year the word
     * disagrees with, name no one year.
     */
    private fun spanYear(
        n: String,
        conversation: ConversationPeriods,
    ): SpanYear {
        val written = NamedYear.writtenYears(n)
        val words = PeriodWords.findAll(n, PeriodUnit.YEAR).distinctBy { it.kind to it.delta }
        if (words.size > 1) return SpanYear.None
        val word = words.singleOrNull() ?: return written.singleOrNull()?.let { SpanYear.One(it) } ?: SpanYear.None
        return when (val r = conversation.read(word)) {
            is ConversationPeriods.Reading.One ->
                SpanYear.One(r.index).takeIf {
                    written.isEmpty() ||
                        written == setOf(r.index)
                }
            is ConversationPeriods.Reading.Two -> SpanYear.Two(r.deictic, r.anaphoric).takeIf { written.isEmpty() }
            is ConversationPeriods.Reading.NoAnchor -> SpanYear.Unanchored(r.why)
        } ?: SpanYear.None
    }

    // ----- date-role targeting (which column, not which interval) -----

    private fun detectTarget(n: String): DateTarget? =
        when {
            hasAny(n, "due", "splatn") -> DateTarget.DUE
            hasAny(n, "posted", "posting", "zauctov") -> DateTarget.POSTING
            hasAny(n, "document date", "doc date", "datum dokladu", "datum vystaveni") -> DateTarget.DOCUMENT
            else -> null
        }

    // ----- a part of a larger period: "the last month of the year", „poslední týden v měsíci“ (✅R215-N) -----

    /**
     * A last week, month or year of a period that contains it ([PartOfPeriods]) is no one period this class reads.
     * Second in the chain, after the last month of a year (December, [numbered]): the trailing ask, the deictic "last
     * month", the week rule's "last" and the month and fiscal-year rules would each read a part of the phrase as the
     * whole.
     */
    private fun partOfLarger(n: String): Read? = NotOne.takeIf { PartOfPeriods.findAll(n).isNotEmpty() }

    // ----- the rules older than TS read only a span that names one period (review-215 P5) -----

    /**
     * The rules after this one — fiscal quarter, period code, dates, month names, relative words, a bare year — read
     * the span as a whole and keep no range of what they read: a span naming two periods would be read as one of
     * them („leden až březen 2025“ as January, "this quarter and last month" as this quarter). So a span that names
     * more than one ([OtherPeriods.several]) is no one period here, as the numbered rule asks of its own. So is one in a
     * year chrono reads nowhere ([YearPhrases.unreadIn]: "March next year", „v březnu příštího roku“), which the
     * month rule would put in the reference's year.
     */
    private fun older(
        n: String,
        reference: LocalDate,
    ): Read? = if (YearPhrases.unreadIn(n)) NotOne else OtherPeriods.several(n, reference)?.read()

    // ----- fiscal year: "fiscal year 2026" / "fiskalni rok 2026" -----

    private val fiscalYearRe = Regex("""(?:fiscal|financial|fiskaln\w*|financn\w*|ucetni)\s+(?:year|rok\w*)\s+(\d{4})""")

    /** The fiscal/accounting adjectives alone — used when a trigger supplies the missing scope. */
    private val fiscalWordRe = Regex("""(?:fiscal|financial|fiskaln\w*|financn\w*|ucetni)""")

    /**
     * "fiscal year 2026" → that fiscal year. A quarter or half of it ("Q4 of fiscal year 2025", "the second half of
     * fiscal year 2025") is that quarter or half, as "Q4 FY2025" is: this rule gives way, and [numbered] reads it with
     * the fiscal year's digits as its year (RR14). So it does for one a range or exclusion word governs: "fiscal year
     * 2025 excluding Q4" is no one period, and is not read at all (RR19). And for a month of it: "December of fiscal
     * year 2025" is December 2025, the month rule's (P4). A span that names another period or year beside it is no
     * one period: "fiscal year 2025 vs 2024" ([OtherPeriods], P5).
     */
    private fun fiscalYear(
        n: String,
        reference: LocalDate,
    ): Read? {
        if (NumberedPeriods.quarters(n).isNotEmpty() || NumberedPeriods.halves(n).isNotEmpty()) return null
        val m = fiscalYearRe.find(n) ?: return null
        if (Months.find(n) != null) {
            // Only a month OF the calendar, before its name, can take the fiscal year's digits.
            val month = Regex("""\b[a-z]+\b""").findAll(n).firstOrNull { Months.find(it.value) != null }
            val before = month != null && month.range.last < m.range.first
            val link = month?.let { n.substring(it.range.last + 1, if (before) m.range.first else it.range.last + 1) }
            val ofCalendar = link != null &&
                (Regex("""^\s+(?:of|in)\s+$""").matches(link) || (m.value.startsWith("fiskal") && link.isBlank()))
            val defining = Regex("""\b(?:ending|starting|beginning|through|until|to|from|od|do)\b""").containsMatchIn(n)
            return if (before && ofCalendar && !defining) null else NotOne
        }
        val y = m.groupValues[1].toIntOrNull() ?: return null
        OtherPeriods.outside(n, m.range, reference, wholeYear = true)?.let { return it.read() }
        return Hit(yearInterval(y, ChronoKind.FISCAL_YEAR, 0.95))
    }

    // ----- a quarter or half-year by its number: „2. čtvrtletí 2026“, "Q2", „1. pololetí“, "H2 2026" (TS-6) -----
    // ----- and the last month of a year: "the last month of last year", „poslední měsíc roku“ (✅R215-R) -----

    private fun quarterCode(
        year: Int,
        number: Int,
    ) = PeriodCodes.numbered(year, 'Q', number)

    private fun halfCode(
        year: Int,
        number: Int,
    ) = PeriodCodes.numbered(year, 'H', number)

    private fun monthCode(
        year: Int,
        number: Int,
    ) = "%04d%02d".format(year, number)

    /**
     * The one quarter, half-year or last month of a year a span names by number ([NumberedPeriods]) → a PERIOD
     * recognition with its `yyyyQn` / `yyyyHn` / `yyyyMM` code. Run first, a last month of a year is not
     * [partOfLarger]'s, and "the last month of fiscal year 2026" not [fiscalYear]'s. A yearless one takes its year the
     * way a yearless month does: from the span's own year words („letošní 2. čtvrtletí“), then from the question or
     * the conversation, then the reference's — and asks between this year and last when it is later than the
     * reference's own quarter, half or month (#214, contracts §5): "the last month of the year" asks as "December"
     * does.
     *
     * A span that names any other period is no one period ([NotOne], ✅R215-M): several numbered ones ("Q1 a Q2
     * 2025"), a number joined to this one („1. a 2. čtvrtletí“, "first and second quarter of 2025" — only the second
     * stands beside its noun), a second year ("Q2 2025 vs 2024", "Q2 this year vs last year"), a month, a relative
     * word, a count or year to date ("Q1 and last month", "Q2 vs YTD"). Which ones count is [OtherPeriods]'.
     *
     * A span that states a year this rule cannot read is not read either (RR13, A-TS-3): its year is not the
     * reference's or the conversation's. That is a year chrono reads nowhere ([YearPhrases]: "Q2 next year", "Q2 a
     * year ago", „každé 2. čtvrtletí“), and a year in digits no pattern places ("Q2 for 2025", "Q2 sales 2025", as
     * [relative] does not read one, #213). Nor is a year that stands only after a bound word ("Q2 since 2020", „2.
     * čtvrtletí do roku 2025“, "Q2 since last year", RR18): it is where a range starts or ends, not the period's year.
     * A period that a range or exclusion word governs is a bound or the part left out, not the period ("since Q2",
     * "in 2025 excluding Q4", „kromě 4. čtvrtletí“, "Q2 onwards", RR19). A yearless one takes a fallback year only
     * from a span that says nothing else about a year ([YearPhrases.speaksOfYear], RR17): not "Q2 FY25", "next Q2",
     * "Q2 in previous years". A plural noun („ve 2. čtvrtletích“) is no one period.
     */
    private fun numbered(
        n: String,
        found: List<NumberedPeriods.Numbered>,
        months: Int,
        codeOf: (year: Int, number: Int) -> String,
        reference: LocalDate,
        years: Years,
    ): Read? {
        if (found.isEmpty()) return null
        val one = found.singleOrNull() ?: return Several
        if (NumberedPeriods.plural(n, one)) return NotOne
        if (bounded(n, one.range)) return NotOne
        OtherPeriods.outside(n, one.range, reference)?.let { return it.read() }
        if (YearPhrases.unreadIn(n)) return NotOne

        fun inYear(
            year: Int,
            confidence: Double,
        ): ChronoRecognition {
            val start = LocalDate.of(year, (one.number - 1) * months + 1, 1)
            val code = codeOf(year, one.number)
            return ChronoRecognition(start, start.plusMonths(months.toLong()), ChronoKind.PERIOD, confidence, code)
        }
        one.year?.let { return Hit(inYear(it, 0.9)) }
        // "Q2 since 2020", „2. čtvrtletí do roku 2025“: a range's bound is not the period's year (RR18).
        if (years.stated != SpanYear.None && yearOnlyBound(n)) return NotOne
        when (val said = years.stated) {
            is SpanYear.One -> return Hit(inYear(said.year, 0.9))
            is SpanYear.Two -> return twoReadings(inYear(said.deictic, 0.9), inYear(said.anaphoric, 0.9))
            is SpanYear.Unanchored -> return NoAnchor(PeriodUnit.YEAR, said.why)
            // "the last quarter of the last year": a year word no reading settles is no other year to fall back on;
            // nor are digits no pattern placed, nor a span that says anything else about a year ("Q2 FY25", "next
            // Q2", "Q2 of the contract year", RR17).
            SpanYear.None -> if (one.yearWord || statesYear(n) || YearPhrases.speaksOfYear(n, one.range)) return NotOne
        }
        when (val leaned = years.leaned) {
            is LeanedYear.One -> return Hit(inYear(leaned.year, LEANED_YEAR_CONFIDENCE))
            is LeanedYear.Unclear -> return NoAnchor(PeriodUnit.YEAR, leaned.why)
            LeanedYear.None -> Unit
        }
        val current = (reference.monthValue - 1) / months + 1
        return Hit(
            if (one.number > current) {
                // Later than the reference's own: this year (upcoming) or last (most recent past), as a bare
                // future month asks.
                inYear(reference.year, 0.6).copy(alternatives = listOf(inYear(reference.year - 1, 0.6)))
            } else {
                inYear(reference.year, 0.85)
            },
        )
    }

    // ----- fiscal/accounting quarter: "last fiscal quarter" / "poslední fiskální čtvrtletí" (Q-18) -----
    // Words are diacritic-folded (Normalization.fold) before the recognizer runs, so cs stems match
    // accent-free: čtvrtletí→ctvrtleti, fiskální→fiskalni, účetní→ucetni, minulé→minule, poslední→posledni.

    private val quarterWordRe = Regex("""(?:ctvrtlet\w*|kvartal\w*|quarter)""")

    /**
     * A relative fiscal/accounting quarter → a PERIOD recognition carrying a `yyyyQn` period code plus
     * the calendar-quarter interval. The scope word decides what it is read against (#214, [PeriodWords]):
     * "this/last/past quarter", „toto/poslední čtvrtletí“ the reference date; "the previous/prior quarter",
     * "the quarter before", „předchozí/předešlé čtvrtletí“ the quarter the conversation is about; „minulé
     * čtvrtletí“ either, asking when the two differ.
     *
     * A bare "quarter" is ambiguous and left for the LLM fallback — UNLESS [triggered], in which case
     * it reads as the CURRENT quarter at the lower [TRIGGERED_SCOPELESS_CONFIDENCE], exactly as a
     * scopeless month or year does in [relative]. The estate declared the word; a bare mention of it
     * is not noise. A span naming this quarter AND an earlier one ("tento poslední čtvrtletí") is not a
     * match either way.
     */
    private fun fiscalQuarter(
        n: String,
        reference: LocalDate,
        triggered: Boolean,
        conversation: ConversationPeriods,
    ): Read? {
        if (!quarterWordRe.containsMatchIn(n)) return null
        // Every quarter here is relative. A quarter OF a stated year ("the last quarter of 2025", „2. čtvrtletí
        // 2025“) is not: it is [numbered]'s, which runs first (TS-6).
        if (statesYear(n)) return null
        val words = PeriodWords.findAll(n, PeriodUnit.QUARTER).distinctBy { it.kind to it.delta }
        if (hasAny(n, thisScopeWords) && words.any { it.delta != 0 }) return null // ambiguous both
        if (words.size > 1) return null
        val word = words.singleOrNull()
        return when {
            word != null -> period(word, conversation)
            triggered -> Hit(current(PeriodUnit.QUARTER, reference))
            else -> null // no scope and no trigger → the LLM fallback's
        }
    }

    // ----- period code: "202605", "period 202605", "obdobi 202605" -----

    private val sixDigit = Regex("""\b(\d{6})\b""")

    private fun periodCode(n: String): ChronoRecognition? {
        val m = sixDigit.find(n) ?: return null
        val code = m.groupValues[1]
        val year = code.substring(0, 4).toInt()
        val month = code.substring(4, 6).toInt()
        if (month !in 1..12 || year !in PLAUSIBLE_YEARS) return null
        val explicit = hasAny(n, "period", "obdobi")
        // A bare 6-digit run is ambiguous with document/order ids ("doklad 200312", "objednávka
        // 202612"). Only read it as an accounting period when the span says so ("period"/"období") or
        // when the code stands alone as the whole span — otherwise fall through (→ LLM fallback), so
        // an embedded id is never silently grounded as a month at 0.9 confidence.
        if (!explicit && n.trim() != code) return null
        return monthInterval(year, month, ChronoKind.PERIOD, if (explicit) 0.97 else 0.9, code)
    }

    // ----- ISO date: 2026-03-15 -----

    private val isoRe = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")

    private fun isoDate(n: String): ChronoRecognition? {
        val m = isoRe.find(n) ?: return null
        val d =
            runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }
                .getOrNull() ?: return null
        return dayInterval(d, 0.97)
    }

    // ----- numeric cs/en date: 15.3.2026 · 15.3. · 15/3/2026 -----

    // Tolerates spaces around separators — Czech dates are often written "15. 3. 2026".
    private val dmyRe = Regex("""\b(\d{1,2})\s*[./]\s*(\d{1,2})(?:\s*[./]\s*(\d{4}))?\.?""")

    private fun numericDate(
        n: String,
        reference: LocalDate,
        years: Years = Years(),
    ): Read? {
        val m = dmyRe.find(n) ?: return null
        val day = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        if (month !in 1..12 || day !in 1..31) return null
        val written = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()

        fun on(year: Int): ChronoRecognition? =
            runCatching { LocalDate.of(year, month, day) }
                .getOrNull()
                // Slightly lower confidence when the year was inferred (no explicit year in the span).
                ?.let { dayInterval(it, if (written == null) 0.9 else 0.96) }
        if (written != null) return on(written)?.let(::Hit)
        return when (val stated = years.stated) {
            is SpanYear.One -> on(stated.year)?.let(::Hit)
            is SpanYear.Two -> twoReadings(on(stated.deictic), on(stated.anaphoric))
            is SpanYear.Unanchored -> NoAnchor(PeriodUnit.YEAR, stated.why)
            SpanYear.None ->
                when (val leaned = years.leaned) {
                    is LeanedYear.One -> on(leaned.year)?.let(::Hit)
                    is LeanedYear.Unclear -> NoAnchor(PeriodUnit.YEAR, leaned.why)
                    LeanedYear.None -> on(reference.year)?.let(::Hit)
                }
        }
    }

    /** A date in a year „minulý rok“ names, which is one of two: ask between them (#214). */
    private fun twoReadings(
        deictic: ChronoRecognition?,
        anaphoric: ChronoRecognition?,
    ): Read? {
        if (deictic == null || anaphoric == null) return null
        return Hit(deictic.copy(alternatives = listOf(anaphoric)))
    }

    // ----- named month: "March 15 2026" · "15. května" · "May 2026" · "May period" -----

    private val yearRe = Regex("""\b(\d{4})\b""")
    private val dayRe = Regex("""\b(\d{1,2})\b""")

    private fun namedMonthDate(
        n: String,
        reference: LocalDate,
        years: Years = Years(),
    ): Read? {
        val month = Months.find(n) ?: return null
        val written =
            yearRe
                .find(n)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
        // A 1–2 digit token → the day-of-month; else month granularity (a period). The year is only
        // ever a 4-digit token (yearRe), and `\b\d{1,2}\b` cannot match digits inside it, so no
        // value-based exclusion is needed — filtering out days that merely equal year%100 (e.g. the
        // 26th in "May 26 2026") only dropped legitimate days and collapsed them to a whole month.
        val day =
            dayRe
                .findAll(n)
                .map { it.groupValues[1].toInt() }
                .firstOrNull { it in 1..31 }

        fun dayIn(
            year: Int,
            confidence: Double,
        ): ChronoRecognition? {
            val d = day ?: return null
            return runCatching { LocalDate.of(year, month, d) }.getOrNull()?.let { dayInterval(it, confidence) }
        }
        // A year said in words that is one of two („v březnu minulého roku“, #214): ask which.
        if (written == null) {
            when (val said = years.stated) {
                is SpanYear.Two ->
                    return if (day != null) {
                        twoReadings(dayIn(said.deictic, 0.95), dayIn(said.anaphoric, 0.95))
                    } else {
                        twoReadings(monthPeriod(said.deictic, month, 0.9), monthPeriod(said.anaphoric, month, 0.9))
                    }
                is SpanYear.Unanchored -> return NoAnchor(PeriodUnit.YEAR, said.why)
                is SpanYear.One, SpanYear.None -> Unit
            }
        }
        val stated = written ?: (years.stated as? SpanYear.One)?.year
        // A year the span does not name comes from the question or the conversation before it falls
        // back to the reference — and a month that has one is never the ambiguous bare future month. A
        // year the conversation names but nobody settled is no year to fall back from (#214).
        val year =
            stated ?: when (val leaned = years.leaned) {
                is LeanedYear.One -> leaned.year
                is LeanedYear.Unclear -> return NoAnchor(PeriodUnit.YEAR, leaned.why)
                LeanedYear.None -> null
            }
        return Hit(
            if (day != null) {
                dayIn(year ?: reference.year, if (stated != null) 0.95 else 0.85) ?: return null
            } else if (stated != null) {
                monthPeriod(stated, month, 0.9)
            } else if (year != null) {
                monthPeriod(year, month, LEANED_YEAR_CONFIDENCE)
            } else if (month > reference.monthValue) {
                // A bare FUTURE month (no year) is genuinely ambiguous: this year (upcoming) or last
                // year (most recent past). Primary = this year; the alternative drives A8.6 clarification.
                monthPeriod(reference.year, month, 0.6)
                    .copy(alternatives = listOf(monthPeriod(reference.year - 1, month, 0.6)))
            } else {
                // A bare past/current month resolves to this year unambiguously.
                monthPeriod(reference.year, month, 0.85)
            },
        )
    }

    private fun monthPeriod(
        year: Int,
        month: Int,
        confidence: Double,
    ): ChronoRecognition = monthInterval(year, month, ChronoKind.PERIOD, confidence, "%04d%02d".format(year, month))

    // ----- bare calendar year: "2025" -----

    private val digitRunRe = Regex("""\d+""")

    /**
     * A span that is nothing but a four-digit year.
     *
     * **Last in the chain, and that placement is the rule.** Every other recognizer describes a
     * year more specifically than this one does, and each would be shadowed if this ran earlier:
     * `fiscalYear` ("fiscal year 2025"), `periodCode` ("202505"), `isoDate` ("2025-03-15"),
     * `numericDate` ("15.3.2025"), `namedMonthDate` ("May 2025") and `relative` ("last year") all
     * contain or imply a four-digit run. Running last means this fires only on what nothing else
     * claimed.
     *
     * Before this existed a bare "2025" reached the end of the chain and returned null — chrono
     * answered UNGROUNDABLE, the resolver produced an interval-less value, and the door refused
     * the question for want of a column. `yearRe` was present but reachable only from inside
     * [namedMonthDate], behind its `Months.find` guard, so it never saw a span without a month.
     *
     * The year must be the span's ONLY digit run. A second number means the span is some other
     * construct that the recognizers above declined to claim, and guessing a year out of it would
     * be inventing a filter the user never asked for.
     *
     * A year word beside the digits ("the year 2025", "v roce 2025", "this year 2025") still lands
     * here: [relative] does not read a span that writes out its year ([statesYear], #213).
     *
     * A unit smaller than a year beside the digits ([subYearUnitRe]) is a period OF that year, never the
     * whole year. A quarter or a half-year of it ("the second quarter of 2025", „druhé pololetí 2025“) is
     * [numbered]'s, which runs first (TS-6). A week, a month by a relative word or a day of it ("the last week
     * of 2025", "last 3 months of 2025") no rule reads yet: the whole year would be a confident wrong number,
     * so the span stays ungrounded, and a consumer falls through on a date with no range and reads the words
     * from the question (#213).
     */
    private fun calendarYear(n: String): ChronoRecognition? {
        if (subYearUnitRe.containsMatchIn(n)) return null
        val digitRuns = digitRunRe.findAll(n).map { it.value }.toList()
        val only = digitRuns.singleOrNull() ?: return null
        val y = only.takeIf { it.length == 4 }?.toIntOrNull() ?: return null
        if (y !in PLAUSIBLE_YEARS) return null
        // Explicit and unambiguous — the user named the year. Held just below `fiscalYear`'s 0.95:
        // that span also said the WORD "year", where this one is inferred from shape alone.
        return yearInterval(y, ChronoKind.CALENDAR_YEAR, 0.9)
    }

    /**
     * Does the span write out a year of its own: a four-digit run in [PLAUSIBLE_YEARS], the same
     * run [calendarYear] reads? Such a span is not a period at the reference, so neither
     * [relative] nor [fiscalQuarter] reads it (#213).
     */
    private fun statesYear(n: String): Boolean = statedYears(n).isNotEmpty()

    /**
     * A range or exclusion word right before a numbered period: the period is a bound ("since Q2", "until Q3", „od 2.
     * čtvrtletí“, „v roce 2025 do 3. čtvrtletí“) or the part left out ("excluding Q4", „kromě 4. čtvrtletí“), not the
     * period the span is about (RR19). Not *from*, *in*, *of*, *for*, „z“, „v“, „za“: "sales from Q2" is Q2's sales.
     */
    private val rangeBeforeRe =
        Regex(
            """\b(?:since|after|before|until|till|through|thru|up\s+to|by|excluding|except|without|other\s+than""" +
                """|od|po|pred|do|az\s+do|krome|bez|mimo|vyjma)\s+(?:the\s+)?$""",
        )

    /** A range word right after it: "Q2 onwards", "Q2 and later", „2. čtvrtletí a dál“. */
    private val rangeAfterRe = Regex("""^\s+(?:onwards?|and\s+(?:later|after|beyond)|a\s+(?:dal|dale|pozdeji))\b""")

    /** Does a range or exclusion word govern the numbered period at [own] ([rangeBeforeRe], [rangeAfterRe])? */
    private fun bounded(
        n: String,
        own: IntRange,
    ): Boolean =
        rangeBeforeRe.containsMatchIn(n.substring(0, own.first)) ||
            rangeAfterRe.containsMatchIn(n.substring(own.last + 1))

    /** A word that makes the year after it a range's bound: "since", "up to", „od (roku)“, „až do“ (RR18). */
    private val boundBeforeRe =
        Regex(
            """\b(?:since|until|till|through|thru|after|before|up\s+to|od|do|az\s+do|po|pred)\s+""" +
                """(?:(?:roku|roce|rokem|fy)\s*)?$""",
        )

    private val yearDigitsRe = Regex("""(?<!\d)\d{4}(?!\d)""")

    /**
     * Does every year the span says, in digits or in words, stand right after a bound word: "since 2020", „od roku
     * 2020“, "until last year", „od loňska“? Then the year is where a range starts or ends, not the year a period is in.
     */
    private fun yearOnlyBound(n: String): Boolean {
        val at =
            yearDigitsRe
                .findAll(n)
                .filter { it.value.toInt() in PLAUSIBLE_YEARS }
                .map { it.range }
                .toList() +
                PeriodWords.findAll(n, PeriodUnit.YEAR).map { it.range }
        return at.isNotEmpty() && at.all { boundBeforeRe.containsMatchIn(n.substring(0, it.first)) }
    }

    /** The years the span writes out: its four-digit runs in [PLAUSIBLE_YEARS] ([statesYear]). */
    private fun statedYears(n: String): Set<Int> =
        digitRunRe
            .findAll(n)
            .filter { it.value.length == 4 }
            .map { it.value.toInt() }
            .filter { it in PLAUSIBLE_YEARS }
            .toSet()

    /**
     * A unit smaller than a year, on the folded span: an English word with its plural, matched
     * whole, and a Czech noun stem in any case. Whole words and noun stems only, so the adjectives
     * that name a grain rather than a period do not count: "monthly", "quarterly", "weekly",
     * "daily", *měsíční*, *čtvrtletní*, *pololetní*, *kvartální*, *týdenní*, *denní*. The `dn` stem
     * takes *dne*, *dni*, *dnů*, *dny* and also *dnes*, which is a day too.
     */
    private val subYearUnitRe =
        Regex(
            """\b(?:quarters?|half|halves|weeks?|months?|days?|today|yesterday|tomorrow)\b""" +
                """|\b(?:ctvrtlet|kvartal|pololet|mesic)(?!n)\w*|\btyden\b|\btydn\w*|\bden\b|\bdn\w*|\bvcera\b|\bzitra\b""",
        )

    // ----- relative: today/yesterday/tomorrow · this/last week/month/year · last N days/months -----

    /**
     * The week's scope words, folded (Normalization.fold), matched as substrings of the span. Years, months
     * and quarters read theirs by kind from [PeriodWords] (#214); a week keeps these, read against the
     * reference date.
     *
     * Czech declines the demonstrative and the adjective with the noun, so each scope comes in the
     * forms a question puts it in: *tento týden*, *tohoto týdne*, *v tomto týdnu*, *tenhle týden*;
     * *minulý / minulého / v minulém*. `thisScopeWords` also guards [fiscalQuarter] against a span that
     * names this quarter and an earlier one.
     */
    private val thisScopeWords =
        (
            "this current " +
                "tento tato toto tohoto tomto tomuto timto tenhle tohle tomhle tohohle " +
                // "letos", "letošní", "tohoroční" — this year
                "letos tohorocn aktualn soucasn"
        ).split(' ')
    private val lastScopeWords = "last previous minul predchoz predesl loni lonsk".split(' ')

    /**
     * "past" and „uplynulý“ scope a week only right before the noun, the way [PeriodWords] reads them for a month
     * or a year: "past due this week" is this week's overdue items, not last week.
     */
    private val pastWeekRe = Regex("""\bpast\s+${PeriodWords.EN_ADJ}week\b|\buplynul\w*\s+(?:tyden|tydn\w*)""")

    /** A week noun, whole: not the grain adjectives *weekly*, *týdenní*. */
    private val weekNounRe = Regex("""\bweeks?\b|\btyden\b|\btydn\w*""")

    /**
     * A week with its scope word right before the noun: „minulý týden“, „v tomto týdnu“, "this week", "the past
     * week". The week rule reads only this phrase: a scope word elsewhere scopes another noun, and "minulý měsíc
     * po týdnech" or "last month by week" is last month broken down by week, not last week (S0.3: `Scan` keeps the
     * longest window that reads, so a breakdown word must not change the reading).
     */
    private val scopedWeekRe =
        Regex(
            """\b(?:this|current|last|past|previous|prior)\s+${PeriodWords.EN_ADJ}week\b""" +
                """|\b(?:tento|tato|toto|tohoto|tomto|tomuto|timto|tenhle|tohle|tomhle|tohohle""" +
                """|aktualn\w*|soucasn\w*|minul\w*|predchoz\w*|predesl\w*|predminul\w*|uplynul\w*)""" +
                """\s+(?:tyden|tydn\w*)""",
        )

    /** Two periods back: *předminulý týden*. Asked before [lastScopeWords], which they contain. */
    private val twoBackScopeWords = listOf("predlon", "predminul")

    /** The locative of *rok* (*v tomto roce*) — a whole word, since "roce" sits inside other words. */
    private val roceRe = Regex("""\broce\b""")

    private fun relative(
        n: String,
        reference: LocalDate,
        triggered: Boolean,
        conversation: ConversationPeriods,
    ): Read? {
        // Every period here is relative. A span that writes out its own year is not (#213): "the year
        // 2025" and "this year 2025" are `calendarYear`'s, and "the last week of 2025" or "week 12 of
        // 2025" is a period of that year, which no rule reads yet.
        if (statesYear(n)) return null
        when {
            hasAny(n, "today", "dnes") -> return Hit(dayInterval(reference, 0.95))
            hasAny(n, "yesterday", "vcera") -> return Hit(dayInterval(reference.minusDays(1), 0.95))
            hasAny(n, "tomorrow", "zitra") -> return Hit(dayInterval(reference.plusDays(1), 0.95))
        }
        trailing(n, reference)?.let { return Hit(it) }
        WindowPeriods.counts(n).firstOrNull()?.let { w ->
            return when (w.unit) {
                WindowPeriods.CountUnit.DAY -> Hit(lastN(reference, w.count))
                WindowPeriods.CountUnit.MONTH -> Hit(lastN(reference, w.count, months = true))
                // No rule reads a count of weeks or years yet, and "last 6 weeks" is not last week.
                WindowPeriods.CountUnit.WEEK, WindowPeriods.CountUnit.YEAR -> NotOne
            }
        }
        if (weekNounRe.containsMatchIn(n)) {
            // The week its own scope word stands before: „minulý týden“, "past due this week".
            scopedWeekRe.find(n)?.let { return week(it.value, reference, triggered)?.let(::Hit) }
            // A week noun with none is a breakdown when the span scopes something else („minulý měsíc po
            // týdnech“, "this year by week"); alone, it is the current week only when triggered.
            if (!hasAny(n, thisScopeWords) && !hasAny(n, lastScopeWords) && !hasAny(n, twoBackScopeWords)) {
                return week(n, reference, triggered)?.let(::Hit)
            }
        }
        // A month word before a year word: "minulý měsíc" names a month, whatever year a span adds to it.
        for (unit in listOf(PeriodUnit.MONTH, PeriodUnit.YEAR)) {
            val words = PeriodWords.findAll(n, unit).distinctBy { it.kind to it.delta }
            if (words.size > 1) return null // two different periods of one unit: not one period
            val word = words.singleOrNull() ?: continue
            return period(word, conversation)
        }
        // A declared trigger with no scope word reads as the CURRENT period (RV-42): the estate put the
        // word in its slice, so "fiskální rok" is a period it means, not noise. Scored lower than an
        // authored "tento"/"minulý", because the scope is inferred.
        if (!triggered) return null
        return when {
            hasAny(n, "month", "mesic") -> Hit(current(PeriodUnit.MONTH, reference))
            hasAny(n, "year", "rok") || roceRe.containsMatchIn(n) ->
                Hit(
                    yearInterval(
                        reference.year,
                        // A scopeless fiscal mention is a FISCAL_YEAR, not a plain calendar year —
                        // same kind the explicit `fiskalni rok 2026` rule produces.
                        if (fiscalWordRe.containsMatchIn(n)) ChronoKind.FISCAL_YEAR else ChronoKind.RELATIVE,
                        TRIGGERED_SCOPELESS_CONFIDENCE,
                    ),
                )
            else -> null
        }
    }

    // ----- year to date: „od začátku roku“, „letos dosud“, "year to date", "YTD" (TS-6) -----

    /** An ending that is today: „do dneška“, „dodnes“, „do současnosti“, "as of today", "to date", "so far". */
    private val ytdTodayEndRe =
        Regex(
            """^(?:(?:az\s+)?do\s+(?:dneska|dnesniho\s+dne|dnes|ted|soucasnosti|soucasneho\s+dne)|dodnes|so\s+far""" +
                """|(?:until|till|through|to|as\s+of|up\s+to|by)\s+(?:today|now|date))\b""",
        )

    /** An ending that is today, standing before the phrase: „dodnes od začátku roku“, „do dneška od začátku roku“. */
    private val ytdTodayBeforeRe =
        Regex("""(?:\bdodnes|\bdo\s+(?:dneska|dnesniho\s+dne|dnes|ted|soucasnosti)|\bso\s+far)\s+$""")

    /** An ending that is a month's end: „do března“, „do konce září“, "through March", "by (the) end of September". */
    private val ytdMonthEndRe =
        Regex(
            """^(?:(?:az\s+)?do\s+(?:konce\s+)?|(?:until|till|through|thru|to|by)\s+(?:(?:the\s+)?end\s+of\s+)?)""" +
                """([a-z]+)\b""",
        )

    /**
     * Any other ending: a word that ends a span of time, then whatever follows. `by` ends one only before a number or
     * "the end"; before any other word it is a breakdown ("year to date by region", "YTD by month"), no ending.
     * „do“ and "to" end one only before a word that names a time ([ytdToRe]).
     */
    private val ytdAnyEndRe = Regex("""^(?:until|till|through|thru|as\s+of|up\s+to)\b|^by\s+(?:\d|(?:the\s+)?end\b)""")

    /**
     * „do“ / "to" and the word after it. They end year to date only when it names a time ([WindowPeriods.timeWordRe],
     * a digit, a month); before any other word they name a place or a party: „vývoz od začátku roku do Německa“,
     * "year to date to our top customers" (✅R215-O).
     */
    private val ytdToRe = Regex("""^(?:(?:az\s+)?do|to)\s+(?:the\s+)?(\d|[a-z]+)""")

    /** Does the year-to-date [ending] name a time it ends at, other than today or a month's end? */
    private fun otherEnding(ending: String): Boolean {
        if (ytdAnyEndRe.containsMatchIn(ending)) return true
        val word = ytdToRe.find(ending)?.groupValues?.get(1) ?: return false
        return word[0].isDigit() ||
            Months.ofWord(word, withYear = true) != null ||
            WindowPeriods.timeWordRe.matches(word)
    }

    /**
     * From the first day of the year through the reference day: `[1 Jan, reference + 1 day)`. Asked before the
     * day words and the month names, which would read the phrase's own ending as the whole of it: „od začátku roku
     * do dneška“ is year to date, not today, and „do března“ is not March.
     *
     * An ending that is today („do dneška“, „do současnosti“, "as of today", "so far") is year to date. One that is
     * a month („do března“, "through September", "by end of March") runs to the end of that month. Either is in the
     * year the span states, in digits or in words ([stated]: "YTD last year through March"), else the reference's.
     * Any other ending that names a time („do 15. března“, „do konce 2. čtvrtletí“, „do minulého měsíce“) leaves the
     * span no one period ([NotOne]); a breakdown ("by region") is no ending (✅R215-O). With no ending, a span that
     * states a year other than the reference's („od začátku roku 2025“, "YTD last year", „loni od začátku roku“) is
     * not year to date: the year rules read it, as before S0.2. A year word with two readings or none settled says no
     * one year, and nor does one chrono reads nowhere ([YearPhrases]: "YTD next year"); a span that states no year is
     * this year's only when it says nothing else about one ([YearPhrases.speaksOfYear]: not "YTD FY25", RR17).
     *
     * A span that names another period beside the phrase and its ending is no one period ([OtherPeriods], ✅R215-M):
     * "Q2 vs YTD", "year to date vs last year", „od začátku roku oproti loňsku“.
     */
    private fun yearToDate(
        n: String,
        reference: LocalDate,
        stated: SpanYear,
    ): Read? {
        // „od začátku loňského roku“, "since the start of last year": from the start of another year, no rule's yet.
        if (startOfAnotherYearRe.containsMatchIn(n)) return NotOne
        val m = WindowPeriods.yearToDateRe.find(n) ?: return null
        val digits = statedYears(n)
        if (digits.size > 1) return Several
        val year =
            when (stated) {
                is SpanYear.One -> stated.year
                is SpanYear.Two, is SpanYear.Unanchored -> return NotOne
                SpanYear.None -> digits.singleOrNull() ?: reference.year
            }
        // "YTD next year", "YTD a year ago": a year chrono reads nowhere is not the reference's (RR13).
        if (YearPhrases.unreadIn(n)) return NotOne
        val start = LocalDate.of(year, 1, 1)
        var at = m.range.last + 1
        leadingYearRe.find(n.substring(at))?.let { at += it.range.last + 1 }
        PeriodWords
            .findAll(n, PeriodUnit.YEAR)
            .firstOrNull { it.range.first >= at && leadingYearWordRe.matches(n.substring(at, it.range.first)) }
            ?.let { at = it.range.last + 1 }
        val ending = n.substring(at).trimStart()
        val today = ytdTodayEndRe.find(ending)
        // The same ending before the phrase: „dodnes od začátku roku“ is year to date, not today and year to date.
        val todayBefore = ytdTodayBeforeRe.find(n.substring(0, m.range.first))
        val ownStart = todayBefore?.range?.first ?: m.range.first
        val monthEnd = ytdMonthEndRe.find(ending)
        val month = monthEnd?.let { Months.ofWord(it.groupValues[1], withYear = true) }
        // The phrase, its year and the ending it reads are its own; anything else is another period.
        val endingLength = (today ?: monthEnd?.takeIf { month != null })?.let { it.range.last + 1 } ?: 0
        val ownEnd = if (endingLength > 0) n.length - ending.length + endingLength else at
        // An ending that names another time („do 15. března“, "to last month") bounds the phrase: no one period.
        if (today == null && month == null && otherEnding(ending)) return NotOne
        OtherPeriods.outside(n, ownStart until ownEnd, reference)?.let { return it.read() }
        // "YTD FY25", "YTD in previous years": a span that says anything else about a year is not this year's (RR17).
        if (stated == SpanYear.None && digits.isEmpty() && YearPhrases.speaksOfYear(n, ownStart until ownEnd)) {
            return NotOne
        }
        val end =
            when {
                today != null || todayBefore != null -> reference.plusDays(1)
                month != null -> LocalDate.of(year, month, 1).plusMonths(1)
                otherEnding(ending) -> return NotOne
                year != reference.year -> return null
                else -> reference.plusDays(1)
            }
        if (!end.isAfter(start)) return NotOne
        return Hit(ChronoRecognition(start, end, ChronoKind.RELATIVE, 0.9))
    }

    /**
     * From the start of a year other than this one: „od začátku loňského / minulého / předchozího roku“, "since the
     * start of last year". It runs to the reference day, which is no period a rule reads (review-215 P4, round 4); read
     * as the whole year, as before S0.3, it was a wrong one.
     */
    private val startOfAnotherYearRe =
        Regex(
            """\bod\s+zacatku\s+(?:v?lonsk|minul|predchoz|predesl|predlon|predminul|pristi|nasledujic)\w*\s+""" +
                """${PeriodWords.CS_ADJ}roku\b""" +
                """|\bsince\s+the\s+(?:start|beginning)\s+of\s+(?:last|the\s+previous|the\s+prior|next)\s+year\b""",
        )

    /** The year a „od začátku roku 2025 …“ writes right after the phrase. */
    private val leadingYearRe = Regex("""^\s*\d{4}\b""")

    /** What may stand between the phrase and a year word right after it: "YTD for last year through March". */
    private val leadingYearWordRe = Regex("""\s*(?:(?:for|of|in|za|v)\s+)?""")

    // ----- „poslední měsíc / týden / rok“, "the last month": ask (TS-5) -----

    /**
     * „Za poslední měsíc“ is the last thirty days as often as the calendar month before, and a wrong one is a
     * wrong number told as an answer — so it asks, with two options: `trailing` (the last 30 / 7 / 365 days,
     * ending with the reference day, as „posledních 30 dní“ reads) and `calendar` (the month, week or year before
     * the reference's). Which phrases ask is [TrailingPeriods]'.
     */
    private fun trailing(
        n: String,
        reference: LocalDate,
    ): ChronoRecognition? {
        val unit = TrailingPeriods.findAll(n).firstOrNull()?.unit ?: return null
        val calendar =
            when (unit) {
                TrailingPeriods.TrailingUnit.MONTH ->
                    periodRecognition(PeriodUnit.MONTH, PeriodUnit.MONTH.indexOf(reference) - 1, 0.85)
                TrailingPeriods.TrailingUnit.WEEK -> weekInterval(reference, -1L, 0.85)
                TrailingPeriods.TrailingUnit.YEAR -> periodRecognition(PeriodUnit.YEAR, reference.year - 1, 0.85)
            }
        return lastN(reference, unit.days).copy(
            optionId = ChronoRecognition.TRAILING,
            alternatives = listOf(calendar.copy(optionId = ChronoRecognition.CALENDAR)),
        )
    }

    /**
     * The last [count] days ending with the reference day — `[reference − N + 1 day, reference + 1 day)`, exactly N
     * days (A-TS-1) — or, with [months], the last [count] months ending with it: from the day after the same date N
     * months back, `reference − N months + 1 day`. Not `(reference + 1 day) − N months`: a shorter month clamps
     * that a day early (on 30 December, 30 November rather than 1 December). „posledních 30 dní“, "last 3 months"
     * and the trailing option of „poslední měsíc“ all read it.
     */
    private fun lastN(
        reference: LocalDate,
        count: Long,
        months: Boolean = false,
    ): ChronoRecognition {
        val end = reference.plusDays(1)
        val start = if (months) reference.minusMonths(count).plusDays(1) else end.minusDays(count)
        return ChronoRecognition(start, end, ChronoKind.RELATIVE, 0.85)
    }

    /** A week, read against the reference date by its scope words; scopeless only when [triggered]. */
    private fun week(
        n: String,
        reference: LocalDate,
        triggered: Boolean,
    ): ChronoRecognition? {
        val thisScope = hasAny(n, thisScopeWords)
        val lastScope = hasAny(n, lastScopeWords) || pastWeekRe.containsMatchIn(n)
        val twoBack = hasAny(n, twoBackScopeWords)
        val scopeless = !thisScope && !lastScope && !twoBack
        if (scopeless && !triggered) return null
        // "předminulý" also contains "minul", so the two-back words are asked first.
        val delta =
            when {
                twoBack -> -2L
                lastScope -> -1L
                else -> 0L
            }
        return weekInterval(reference, delta, if (scopeless) TRIGGERED_SCOPELESS_CONFIDENCE else 0.9)
    }

    /**
     * A relative year, month or quarter [word], read by its kind against the [conversation] (#214): one
     * period, or — for „minulý …“ whose two readings differ — the deictic one with the anaphoric one as the
     * alternative to ask between, or no anchor.
     */
    private fun period(
        word: PeriodWord,
        conversation: ConversationPeriods,
    ): Read =
        when (val r = conversation.read(word)) {
            is ConversationPeriods.Reading.One ->
                // Read against the conversation, the period is inferred, like a leaned year.
                Hit(periodRecognition(word.unit, r.index, if (r.inferred) LEANED_YEAR_CONFIDENCE else 0.9))
            is ConversationPeriods.Reading.Two ->
                Hit(
                    periodRecognition(word.unit, r.deictic, 0.9)
                        .copy(alternatives = listOf(periodRecognition(word.unit, r.anaphoric, 0.9))),
                )
            is ConversationPeriods.Reading.NoAnchor -> NoAnchor(word.unit, r.why)
        }

    /** The reference's own period of [unit], for a declared trigger with no scope word (RV-42). */
    private fun current(
        unit: PeriodUnit,
        reference: LocalDate,
    ): ChronoRecognition = periodRecognition(unit, unit.indexOf(reference), TRIGGERED_SCOPELESS_CONFIDENCE)

    /**
     * The period [index] of [unit] as a recognition. A year is RELATIVE with no code (it must not route
     * through a period table, see [ChronoKind.CALENDAR_YEAR]); a month is RELATIVE with its `yyyyMM` code, so
     * a period-keyed fact can bind it (#140) while a date-column fact still gets an interval; a quarter is a
     * PERIOD with its `yyyyQn` code (Q-18).
     */
    private fun periodRecognition(
        unit: PeriodUnit,
        index: Int,
        confidence: Double,
    ): ChronoRecognition {
        val start = unit.startOf(index)
        return when (unit) {
            PeriodUnit.YEAR -> yearInterval(start.year, ChronoKind.RELATIVE, confidence)
            PeriodUnit.MONTH ->
                monthInterval(start.year, start.monthValue, ChronoKind.RELATIVE, confidence, unit.codeOf(index))
            PeriodUnit.QUARTER ->
                ChronoRecognition(
                    start,
                    start.plusMonths(3),
                    ChronoKind.PERIOD,
                    confidence,
                    periodCode = unit.codeOf(index),
                )
        }
    }

    // ----- interval constructors -----

    private fun dayInterval(
        d: LocalDate,
        confidence: Double,
    ) = ChronoRecognition(d, d.plusDays(1), ChronoKind.ABSOLUTE, confidence)

    private fun monthInterval(
        year: Int,
        month: Int,
        kind: ChronoKind,
        confidence: Double,
        code: String? = null,
    ): ChronoRecognition {
        val start = LocalDate.of(year, month, 1)
        return ChronoRecognition(start, start.plusMonths(1), kind, confidence, periodCode = code)
    }

    private fun yearInterval(
        year: Int,
        kind: ChronoKind,
        confidence: Double,
    ): ChronoRecognition {
        val start = LocalDate.of(year, 1, 1)
        return ChronoRecognition(start, start.plusYears(1), kind, confidence)
    }

    private fun weekInterval(
        reference: LocalDate,
        weekDelta: Long,
        confidence: Double = 0.9,
    ): ChronoRecognition {
        val monday = reference.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(weekDelta)
        return ChronoRecognition(monday, monday.plusWeeks(1), ChronoKind.RELATIVE, confidence)
    }

    private fun hasAny(
        n: String,
        vararg needles: String,
    ): Boolean = needles.any { n.contains(it) }

    private fun hasAny(
        n: String,
        needles: List<String>,
    ): Boolean = needles.any { n.contains(it) }
}
