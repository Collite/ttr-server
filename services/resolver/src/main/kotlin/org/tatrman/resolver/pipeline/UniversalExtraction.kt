// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.resolver.v1.UniversalEntityType

/** A universal (engine-typed) binding: person/geo/time/money/number. */
data class UniversalBinding(
    val start: Int,
    val end: Int,
    val text: String,
    val entityType: UniversalEntityType,
    val rawText: String,
    val normalizedValue: String,
    val sourceEngine: String,
)

/**
 * extractUniversal (RG-P5.S1.T5) — turn the parse's NER entities into universal
 * bindings, keeping ONLY the universal classes (person/geo/time/money/number).
 * Institution/object spans are left for the domain path (they are declared
 * values, gated by fuzzy — spike §1). Classification is delegated to
 * [UniversalClassifier] so it can never drift from [SpanProposal]'s exclusion set.
 */
object UniversalExtraction {
    /**
     * @param text the question the parse was made from. With it, the calendar parts NER typed apart
     *   are joined into one date ([joinCalendarParts]); without it (blank) the entities come out
     *   exactly as the parse holds them.
     */
    fun extractUniversal(
        parse: AnalyzeResponse,
        text: String = "",
    ): List<UniversalBinding> =
        joinCalendarParts(
            parse.entitiesList.mapNotNull { e ->
                val type: UniversalEntityType =
                    UniversalClassifier.classify(e.label, e.normalizedValue, e.text) ?: return@mapNotNull null
                UniversalBinding(
                    start = e.charStart,
                    end = e.charEnd,
                    text = e.text,
                    entityType = type,
                    rawText = e.text,
                    normalizedValue = e.normalizedValue,
                    sourceEngine = e.sourceEngine,
                )
            },
            text,
        )

    /**
     * One date, not its parts: a day, month and year NER typed as SEPARATE dates are joined back
     * into the date the user wrote.
     *
     * NameTag (CNEC 2.0) types a written date two ways, and which one is not predictable from the
     * words. *„v květnu 2025“* comes back as one container entity (`cnec:T|B-tm`, "květnu 2025");
     * *„v říjnu 2025“* comes back as two bare entities, a month (`cnec:tm`, "říjnu") and a year
     * (`cnec:ty`, "2025"). Each universal is grounded on its own, so the second shape sent chrono a
     * month with no year — which it reads against the reference date, as it must for a bare month
     * — and a separate whole year: „v říjnu 2025“ became October of the CURRENT year beside all of
     * 2025. Joined here, chrono gets the span the user wrote, "říjnu 2025", and reads October 2025.
     *
     * The join is narrow on purpose:
     *  - only bare `td` (day), `tm` (month) and `ty` (year) parts, and only in calendar order —
     *    day before month before year. Two years („2024 a 2025“) or a year before a month are two
     *    dates, not one;
     *  - only across whitespace in [text]. „v říjnu a listopadu 2025“ keeps its „a“, so its two
     *    months stay two dates (a coordination is not this join's to decide);
     *  - only when the offsets agree with [text]; anything else leaves the parts as they came.
     *
     * The joined binding carries the container code NameTag itself uses for a joined date
     * (`cnec:T|B-<first part>`), so both shapes read the same downstream.
     */
    internal fun joinCalendarParts(
        bindings: List<UniversalBinding>,
        text: String,
    ): List<UniversalBinding> {
        if (text.isBlank() || bindings.size < 2) return bindings
        val out = mutableListOf<UniversalBinding>()
        // The calendar rank of the LAST part folded into the binding on top of `out`, or null when that
        // binding is not a joinable part (a container, another type, or a part already closed).
        var lastRank: Int? = null
        for (b in bindings) {
            val rank = calendarRank(b)
            val prev = out.lastOrNull()
            if (prev != null && lastRank != null && rank != null && rank > lastRank && joinable(prev, b, text)) {
                out[out.size - 1] =
                    prev.copy(
                        end = b.end,
                        text = text.substring(prev.start, b.end),
                        rawText = text.substring(prev.start, b.end),
                        normalizedValue = containerOf(prev),
                    )
                lastRank = rank
            } else {
                out += b
                lastRank = rank
            }
        }
        return out
    }

    /** `td` → 0, `tm` → 1, `ty` → 2 for a bare DATE part; null for anything else. */
    private fun calendarRank(b: UniversalBinding): Int? {
        if (b.entityType != UniversalEntityType.DATE) return null
        return when (b.normalizedValue.trim()) {
            "cnec:td" -> 0
            "cnec:tm" -> 1
            "cnec:ty" -> 2
            else -> null
        }
    }

    /** The two parts touch across whitespace only, and their offsets are the question's own. */
    private fun joinable(
        prev: UniversalBinding,
        next: UniversalBinding,
        text: String,
    ): Boolean {
        if (prev.start < 0 || prev.end > next.start || next.end > text.length) return false
        if (!text.regionMatches(prev.start, prev.text, 0, prev.text.length)) return false
        if (!text.regionMatches(next.start, next.text, 0, next.text.length)) return false
        return text.substring(prev.end, next.start).isBlank()
    }

    /** `cnec:tm` → `cnec:T|B-tm`; an already-joined binding keeps the container it has. */
    private fun containerOf(first: UniversalBinding): String {
        val code = first.normalizedValue.trim()
        return if (code.startsWith("cnec:T|")) code else "cnec:T|B-" + code.removePrefix("cnec:")
    }
}
