// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

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

        /**
         * The range a four-digit run has to fall in to be read as a year. Mirrors the guard
         * `periodCode` already applies to its own `yyyy` half, so the two entry points cannot
         * disagree about what counts as a plausible year.
         */
        val PLAUSIBLE_YEARS = 1900..2999
    }

    /**
     * @param triggers RV-P1.6/RV-42 — the estate's `ground:chrono` trigger slice. It answers ONE
     *   question: "is this span mine?". It never says what the span means — every interval below
     *   is still decided by the rules in this class, so an empty slice (no lexicon archive) leaves
     *   recognition exactly as it was. What a trigger buys is the SCOPE-FREE reading: "fiskální
     *   rok" with no year, or a bare "čtvrtletí", is a period at the reference rather than a
     *   fall-through — the estate said the words are its own, so a bare mention is not noise.
     */
    fun recognize(
        span: String,
        reference: LocalDate,
        triggers: GroundingSlice = GroundingSlice.empty(CHRONO_KIND),
    ): ChronoRecognition? {
        val n = Normalization.fold(span).trim()
        if (n.isEmpty()) return null
        val triggered = triggers.matches(span)
        val target = detectTarget(n)
        val base =
            fiscalYear(n)
                ?: fiscalQuarter(n, reference, triggered)
                ?: periodCode(n)
                ?: isoDate(n)
                ?: numericDate(n, reference)
                ?: namedMonthDate(n, reference)
                ?: relative(n, reference, triggered)
                ?: calendarYear(n)
                ?: return null
        return if (target != null) base.copy(target = target) else base
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
     * the calendar-quarter interval (relative to [reference]). "this/current" = the reference's own
     * quarter; "last/previous" = one quarter earlier (with year rollover).
     *
     * A bare "quarter" is ambiguous and left for the LLM fallback — UNLESS [triggered], in which case
     * it reads as the CURRENT quarter at the lower [TRIGGERED_SCOPELESS_CONFIDENCE], exactly as a
     * scopeless month or year does in [relative]. The estate declared the word; a bare mention of it
     * is not noise. An ambiguous BOTH scopes is still not a match either way.
     */
    private fun fiscalQuarter(
        n: String,
        reference: LocalDate,
        triggered: Boolean = false,
    ): ChronoRecognition? {
        if (!quarterWordRe.containsMatchIn(n)) return null
        // The month/year scope words decline the same way before a quarter ("v tomto čtvrtletí").
        val thisScope = hasAny(n, thisScopeWords)
        val lastScope = hasAny(n, "last", "previous", "past", "posledni", "minul", "predchoz")
        val scopeless = !thisScope && !lastScope
        if (scopeless && !triggered) return null // no scope and no trigger → the LLM fallback's
        if (thisScope && lastScope) return null // ambiguous both → not a quarter match
        return quarterInterval(
            reference,
            if (lastScope) -1 else 0,
            if (scopeless) TRIGGERED_SCOPELESS_CONFIDENCE else 0.9,
        )
    }

    private fun quarterInterval(
        reference: LocalDate,
        deltaQuarters: Int,
        confidence: Double = 0.9,
    ): ChronoRecognition {
        val refQuarter = reference.year * 4 + (reference.monthValue - 1) / 3 + deltaQuarters
        val year = Math.floorDiv(refQuarter, 4)
        val q = Math.floorMod(refQuarter, 4) // 0..3
        val start = LocalDate.of(year, q * 3 + 1, 1)
        return ChronoRecognition(
            start,
            start.plusMonths(3),
            ChronoKind.PERIOD,
            confidence,
            periodCode = "%04dQ%d".format(year, q + 1),
        )
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
    ): ChronoRecognition? {
        val m = dmyRe.find(n) ?: return null
        val day = m.groupValues[1].toInt()
        val month = m.groupValues[2].toInt()
        val year = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: reference.year
        if (month !in 1..12 || day !in 1..31) return null
        val d = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return null
        // Slightly lower confidence when the year was inferred (no explicit year in the span).
        return dayInterval(d, if (m.groupValues[3].isEmpty()) 0.9 else 0.96)
    }

    // ----- named month: "March 15 2026" · "15. května" · "May 2026" · "May period" -----

    private val yearRe = Regex("""\b(\d{4})\b""")
    private val dayRe = Regex("""\b(\d{1,2})\b""")

    private fun namedMonthDate(
        n: String,
        reference: LocalDate,
    ): ChronoRecognition? {
        val month = Months.find(n) ?: return null
        val year =
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
        return if (day != null) {
            val d = runCatching { LocalDate.of(year ?: reference.year, month, day) }.getOrNull() ?: return null
            dayInterval(d, if (year != null) 0.95 else 0.85)
        } else if (year != null) {
            monthPeriod(year, month, 0.9)
        } else if (month > reference.monthValue) {
            // A bare FUTURE month (no year) is genuinely ambiguous: this year (upcoming) or last
            // year (most recent past). Primary = this year; the alternative drives A8.6 clarification.
            monthPeriod(reference.year, month, 0.6)
                .copy(alternatives = listOf(monthPeriod(reference.year - 1, month, 0.6)))
        } else {
            // A bare past/current month resolves to this year unambiguously.
            monthPeriod(reference.year, month, 0.85)
        }
    }

    private fun monthPeriod(
        year: Int,
        month: Int,
        confidence: Double,
    ): ChronoRecognition = monthInterval(year, month, ChronoKind.PERIOD, confidence, "%04d%02d".format(year, month))

    // ----- bare calendar year: "2025" -----

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
     */
    private fun calendarYear(n: String): ChronoRecognition? {
        val digitRuns = Regex("""\d+""").findAll(n).map { it.value }.toList()
        val only = digitRuns.singleOrNull() ?: return null
        val y = only.takeIf { it.length == 4 }?.toIntOrNull() ?: return null
        if (y !in PLAUSIBLE_YEARS) return null
        // Explicit and unambiguous — the user named the year. Held just below `fiscalYear`'s 0.95:
        // that span also said the WORD "year", where this one is inferred from shape alone.
        return yearInterval(y, ChronoKind.CALENDAR_YEAR, 0.9)
    }

    // ----- relative: today/yesterday/tomorrow · this/last week/month/year · last N days/months -----

    /**
     * The scope words, folded (Normalization.fold), matched as substrings of the span.
     *
     * Czech declines the demonstrative and the adjective with the noun, so each scope comes in the
     * forms a question puts it in: *tento měsíc*, *tohoto měsíce*, *v tomto měsíci*, *tenhle týden*;
     * *minulý / minulého / v minulém*; *loňský rok*, *loňské tržby*. Only the stems that cannot be a
     * different word inside a date span are listed — `posledni` is NOT a scope for a month or a year,
     * because *za poslední měsíc* is as often the trailing thirty days as the calendar month before.
     */
    private val thisScopeWords =
        (
            "this current " +
                "tento tato toto tohoto tomto tomuto timto tenhle tohle tomhle tohohle " +
                // "letos", "letošní" — this year
                "letos aktualn soucasn"
        ).split(' ')
    private val lastScopeWords =
        // "loni", "vloni", "loňský" — last year
        "last previous minul predchoz loni lonsk".split(' ')

    /** Two periods back: *předloni*, *předloňský*, *předminulý měsíc*. Asked before [lastScopeWords], which they contain. */
    private val twoBackScopeWords = listOf("predlon", "predminul")

    /** The locative of *rok* (*v minulém roce*) — a whole word, since "roce" sits inside other words. */
    private val roceRe = Regex("""\broce\b""")

    private val lastNRe =
        Regex("""(?:last|past|poslednic?h?|minul\w*)\s+(\d{1,3})\s+(day|days|month|months|den|dn[iíuů]|mesic\w*)""")

    private fun relative(
        n: String,
        reference: LocalDate,
        triggered: Boolean = false,
    ): ChronoRecognition? {
        when {
            hasAny(n, "today", "dnes") -> return dayInterval(reference, 0.95)
            hasAny(n, "yesterday", "vcera") -> return dayInterval(reference.minusDays(1), 0.95)
            hasAny(n, "tomorrow", "zitra") -> return dayInterval(reference.plusDays(1), 0.95)
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
            return ChronoRecognition(start, reference.plusDays(1), ChronoKind.RELATIVE, 0.85)
        }
        val thisScope = hasAny(n, thisScopeWords)
        val lastScope = hasAny(n, lastScopeWords)
        val twoBack = hasAny(n, twoBackScopeWords)
        // A declared trigger with no scope word reads as the CURRENT period (RV-42): the estate
        // put the word in its slice, so "fiskální rok" is a period it means, not noise. Scoped at
        // a lower confidence than an authored "tento"/"minulý", because the scope is inferred.
        val scopeless = !thisScope && !lastScope && !twoBack
        if (scopeless && !triggered) return null
        // "předloni" also contains "loni", so the two-back words are asked first.
        val delta =
            when {
                twoBack -> -2L
                lastScope -> -1L
                else -> 0L
            }
        val confidence = if (scopeless) TRIGGERED_SCOPELESS_CONFIDENCE else 0.9
        return when {
            hasAny(n, "week", "tyden", "tydn") -> weekInterval(reference, delta, confidence)
            hasAny(n, "month", "mesic") -> {
                val base = reference.plusMonths(delta)
                // Carry a period code (yyyyMM) so a period-coded package (e.g. an
                // accounting ledger keyed by period, issue #140) can bind "minulý
                // měsíc" to its period; kind stays RELATIVE so a date-column fact
                // still gets an interval filter.
                monthInterval(
                    base.year,
                    base.monthValue,
                    ChronoKind.RELATIVE,
                    confidence,
                    "%04d%02d".format(base.year, base.monthValue),
                )
            }
            hasAny(n, "year", "rok", "letos", "loni", "lonsk", "predlon") || roceRe.containsMatchIn(n) ->
                yearInterval(
                    reference.year + delta.toInt(),
                    // A scopeless fiscal mention is a FISCAL_YEAR, not a plain calendar year —
                    // same kind the explicit `fiskalni rok 2026` rule produces.
                    if (scopeless && fiscalWordRe.containsMatchIn(n)) ChronoKind.FISCAL_YEAR else ChronoKind.RELATIVE,
                    confidence,
                )
            else -> null
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
