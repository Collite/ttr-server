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
    ): ChronoReading? {
        val n = Normalization.fold(span).trim()
        if (n.isEmpty()) return null
        val triggered = triggers.matches(span)
        val target = detectTarget(n)
        val conversation = ConversationPeriods(context, reference, PeriodWords.findAll(n))
        val years = Years(stated = spanYear(n, conversation), leaned = conversation.leanedYear())
        val base =
            fiscalYear(n)?.let(::Hit)
                ?: fiscalQuarter(n, reference, triggered, conversation)
                ?: periodCode(n)?.let(::Hit)
                ?: isoDate(n)?.let(::Hit)
                ?: numericDate(n, reference, years)
                ?: namedMonthDate(n, reference, years)
                ?: relative(n, reference, triggered, conversation)
                ?: calendarYear(n)?.let(::Hit)
                ?: return null
        return when (base) {
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
    private data class Years(
        val stated: SpanYear = SpanYear.None,
        val leaned: LeanedYear = LeanedYear.None,
    )

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

    // ----- fiscal year: "fiscal year 2026" / "fiskalni rok 2026" -----

    private val fiscalYearRe = Regex("""(?:fiscal|financial|fiskaln\w*|financn\w*|ucetni)\s+(?:year|rok)\s+(\d{4})""")

    /** The fiscal/accounting adjectives alone — used when a trigger supplies the missing scope. */
    private val fiscalWordRe = Regex("""(?:fiscal|financial|fiskaln\w*|financn\w*|ucetni)""")

    private fun fiscalYear(n: String): ChronoRecognition? {
        val y =
            fiscalYearRe
                .find(n)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull() ?: return null
        return yearInterval(y, ChronoKind.FISCAL_YEAR, 0.95)
    }

    // ----- fiscal/accounting quarter: "last fiscal quarter" / "poslední fiskální čtvrtletí" (Q-18) -----
    // Words are diacritic-folded (Normalization.fold) before the recognizer runs, so cs stems match
    // accent-free: čtvrtletí→ctvrtleti, fiskální→fiskalni, účetní→ucetni, minulé→minule, poslední→posledni.

    private val quarterWordRe = Regex("""(?:ctvrtlet\w*|quarter)""")

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
        // Every quarter here is relative. A quarter OF a stated year ("the last quarter of 2025") is not,
        // and no rule reads it yet: it stays ungrounded (#213).
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
     * A unit smaller than a year beside the digits ([subYearUnitRe]: "the second quarter of 2025",
     * "the last week of 2025", "druhé pololetí 2025") is a period OF that year, and no rule reads
     * one yet. The whole year would be a confident wrong number, so the span stays ungrounded: a
     * consumer falls through on a date with no range and reads the words from the question (#213).
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
    private fun statesYear(n: String): Boolean =
        digitRunRe.findAll(n).any { it.value.length == 4 && it.value.toInt() in PLAUSIBLE_YEARS }

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

    /** Two periods back: *předminulý týden*. Asked before [lastScopeWords], which they contain. */
    private val twoBackScopeWords = listOf("predlon", "predminul")

    /** The locative of *rok* (*v tomto roce*) — a whole word, since "roce" sits inside other words. */
    private val roceRe = Regex("""\broce\b""")

    private val lastNRe =
        Regex("""(?:last|past|poslednic?h?|minul\w*)\s+(\d{1,3})\s+(day|days|month|months|den|dn[iíuů]|mesic\w*)""")

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
        lastNRe.find(n)?.let { m ->
            val count = m.groupValues[1].toLong()
            val unit = m.groupValues[2]
            val start =
                if (unit.startsWith("month") || unit.startsWith("mesic")) {
                    reference.minusMonths(count)
                } else {
                    reference.minusDays(count)
                }
            return Hit(ChronoRecognition(start, reference.plusDays(1), ChronoKind.RELATIVE, 0.85))
        }
        if (hasAny(n, "week", "tyden", "tydn")) return week(n, reference, triggered)?.let(::Hit)
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

    /** A week, read against the reference date by its scope words; scopeless only when [triggered]. */
    private fun week(
        n: String,
        reference: LocalDate,
        triggered: Boolean,
    ): ChronoRecognition? {
        val thisScope = hasAny(n, thisScopeWords)
        val lastScope = hasAny(n, lastScopeWords)
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
