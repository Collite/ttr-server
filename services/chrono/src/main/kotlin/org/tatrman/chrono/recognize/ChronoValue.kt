// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

import java.time.LocalDate

/** How the span was recognized — drives confidence and the recipe shape (A8.4). */
enum class ChronoKind {
    /** A specific calendar date or explicit interval ("15.3.2026", "2026-03-15"). */
    ABSOLUTE,

    /** Relative to `reference_datetime` ("last week", "poslední 3 měsíce", "letos"). */
    RELATIVE,

    /** A fiscal/accounting period ("May period", "období 202605", "květnové období"). */
    PERIOD,

    /** A whole fiscal year ("fiscal year 2026", "fiskální rok 2026"). */
    FISCAL_YEAR,

    /**
     * A bare calendar year ("2025", "v roce 2025") — January 1st through December 31st, with no
     * fiscal adjective and no month.
     *
     * Deliberately NOT folded into one of the four above. It is not [ABSOLUTE] (that is a single
     * day), not [RELATIVE] (it names the year outright rather than leaning on the reference), not
     * [PERIOD] (which means an *accounting* period and carries a `periodCode`; a calendar year has
     * none and must not be routed through the period table), and not [FISCAL_YEAR] (whose whole
     * point is that the estate's fiscal year may not start in January). Reusing any of them would
     * have made the recipe builder pick the wrong shape — see its `kind == PERIOD` branch.
     */
    CALENDAR_YEAR,
}

/**
 * Which date-role column the wording targets. `null` means "no explicit target" → the recipe
 * builder anchors on the entity's `event_date`. Set when the span/question names a specific role
 * ("due in May" → DUE, "posted last month" → POSTING).
 */
enum class DateTarget {
    EVENT,
    DUE,
    DOCUMENT,
    POSTING,
}

/**
 * A recognized temporal value: a half-open day interval `[startInclusive, endExclusive)` (end
 * EXCLUSIVE per contracts §1.1 — conditions use `< end`, never `<=`), plus how it was recognized.
 * The recipe builder (A8.4) turns this into a FilterRecipe or JoinRecipe.
 */
data class ChronoRecognition(
    val startInclusive: LocalDate,
    val endExclusive: LocalDate,
    val kind: ChronoKind,
    val confidence: Double,
    /**
     * For [ChronoKind.PERIOD]: the canonical `yyyyMM` period code (e.g. "202605"), `yyyyQn` for a quarter
     * (Q-18) or `yyyyHn` for a half-year (TS-6). The A8.4 recipe builder re-renders a month code per the
     * period table's metadata `code_format` when that differs; a half-year never routes through a period table.
     */
    val periodCode: String? = null,
    /** Explicit date-role override parsed from the wording; null → anchor on event_date. */
    val target: DateTarget? = null,
    /**
     * Other equally-plausible readings of an ambiguous span (A8.6) — e.g. a bare future month is
     * either this year (upcoming) or last year (most recent past). Empty = unambiguous. When
     * non-empty the service returns AWAITING_CLARIFICATION with one option per {this} + alternative.
     */
    val alternatives: List<ChronoRecognition> = emptyList(),
    /**
     * The clarification option id of this reading when the period code does not tell the readings apart —
     * „poslední měsíc“ asks between `trailing` (the last thirty days) and `calendar` (the month before), TS-5.
     * Null → the option id is the period code.
     */
    val optionId: String? = null,
) {
    init {
        require(!endExclusive.isBefore(startInclusive)) {
            "end ($endExclusive) must not be before start ($startInclusive)"
        }
        require(confidence in 0.0..1.0) { "confidence must be in 0..1, was $confidence" }
    }

    /** ISO-8601 start (inclusive), for Normalized.DateTimeInterval. */
    val startIso: String get() = startInclusive.toString()

    /** ISO-8601 end (exclusive), for Normalized.DateTimeInterval. */
    val endIso: String get() = endExclusive.toString()

    companion object {
        /** [optionId] of „poslední měsíc“'s last-N-days reading (TS-5, contracts §2.5). */
        const val TRAILING = "trailing"

        /** [optionId] of „poslední měsíc“'s calendar-period reading. */
        const val CALENDAR = "calendar"
    }
}

/** What [DateRecognizer.read] made of a span. */
sealed interface ChronoReading {
    /** The span is a period: its interval, and the other readings to ask between when it is ambiguous. */
    data class Recognized(
        val recognition: ChronoRecognition,
    ) : ChronoReading

    /** The span is a period read against the conversation, and the conversation gives it nothing to read against. */
    data class Unanchored(
        val reason: String,
    ) : ChronoReading
}

/** What [DateRecognizer.readSpan] made of a span that is a time: a reading, or no one period. */
internal sealed interface SpanReading {
    data class Read(
        val reading: ChronoReading,
    ) : SpanReading

    /**
     * A rule recognised the span's shape, and a word in it changes what any part of it means: "next Q2", "excluding
     * Q4", "Q2 since 2020", "Q2 vs the same quarter last year". No part of it is a period on its own.
     */
    data object NoOnePeriod : SpanReading

    /** The span names several periods side by side („1. a 2. čtvrtletí“, "Q2 2025 vs 2024"); a part may be one. */
    data object Several : SpanReading
}
