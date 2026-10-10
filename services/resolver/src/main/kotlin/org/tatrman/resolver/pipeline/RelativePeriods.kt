// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.NerEntity
import org.tatrman.text.Normalization

/**
 * A period stated RELATIVE to today — *minulý měsíc*, *letos*, *loni*, *tento týden* — typed a
 * date, so the grounding kernel is asked about it.
 *
 * The grounding rung grounds the DATE universals, and the universal layer is NER's. NameTag types
 * a written date (*v říjnu 2025*, *posledních 12 měsíců*) but not a relative period: *minulý
 * měsíc* came back with no entity at all. The words then fell to the mention layer as an unbound
 * FILTER mention (G1), nothing grounded them, and the ladder carried the gap as a note. The answer
 * to „Tržby z tržiště za minulý měsíc pro Brno DC“ was the DC's all-time total, told as an answer
 * (the Czech demo's reserve question, 2026-10-10). chrono reads *minulý měsíc* correctly; it was
 * never asked.
 *
 * So the phrases chrono reads are added to the parse as DATE entities, beside NER's own, before
 * anything reads the parse. Every consumer then treats them exactly as it treats a date NameTag
 * typed: span proposal and the mention layer leave the words alone, the universal layer types
 * them, and the grounding rung asks the kernel for the interval and its column.
 *
 * **Narrow on purpose.** A phrase is added only when the kernel reads it on its own words — a
 * scope word (*minulý*, *tento*, *letošní*, *loňský*, *předchozí*) beside a period noun (*měsíc*,
 * *týden*, *rok*, *čtvrtletí*), or an adverb or adjective that is a year by itself (*letos*,
 * *loni*, *předloni*, *loňské*). Three things are deliberately NOT added:
 *  - a bare period noun (*po měsících*, *roce*): a breakdown or a trigger word, never a period;
 *  - *poslední měsíc*: as often the trailing thirty days as the calendar month before, so the
 *    kernel does not claim it either;
 *  - anything NER already typed: its entity stands, so a date is never grounded twice.
 *
 * What the kernel cannot ground stays honest downstream: an interval-less DATE value carries no
 * range, and the door refuses a time grain nothing restricts by.
 */
object RelativePeriods {
    /** `NerEntity.source_engine` of the entities added here — the universal's `universal:<engine>` provenance. */
    const val SOURCE_ENGINE = "relative-periods"

    /** A coarse DATE label with no CNEC code: [UniversalClassifier] types it DATE by the label. */
    private const val LABEL = "DATE"

    // Folded (no diacritics, lower case). The forms are listed rather than stemmed where a stem would
    // reach another word: `tent\w*` is not a demonstrative, `rok\w*` reaches `rokle`.
    private const val THIS =
        "tento|tohoto|tomto|tomuto|timto|tenhle|tohohle|tomhle|toto|tohle|letosn\\w*|aktualn\\w*|soucasn\\w*"
    private const val LAST = "minul\\w*|predminul\\w*|predchoz\\w*|lonsk\\w*|predlonsk\\w*"
    private const val UNIT = "mesic\\w*|tyden|tydn\\w*|rok|roku|roce|rokem|ctvrtlet\\w*"

    private val PATTERNS =
        listOf(
            // cs: a scope word beside a period noun — "minulý měsíc", "v tomto roce", "letošní čtvrtletí"
            Regex("""\b(?:$THIS|$LAST)\s+(?:$UNIT)\b"""),
            // cs: a year by itself — "letos", "loni", "vloni", "předloni"
            Regex("""\b(?:letos|loni|vloni|predloni)\b"""),
            // cs: a year adjective with no period noun after it — "loňské tržby", "letošní obrat"
            Regex("""\b(?:letosn|lonsk|predlonsk)\w*\b"""),
            // en: NER usually types these already; added when it did not
            Regex("""\b(?:this|last|previous|current)\s+(?:week|month|quarter|year)\b"""),
        )

    /**
     * [parse] with a DATE entity for each relative period in [text] that no entity already covers,
     * entities in span order. The parse itself when there is none.
     */
    fun augment(
        parse: AnalyzeResponse,
        text: String,
    ): AnalyzeResponse {
        val added =
            find(text).filterNot { (start, end) ->
                parse.entitiesList.any { it.charStart < end && start < it.charEnd }
            }
        if (added.isEmpty()) return parse
        val entities =
            (
                parse.entitiesList +
                    added.map { (start, end) ->
                        NerEntity
                            .newBuilder()
                            .setText(text.substring(start, end))
                            .setLabel(LABEL)
                            .setCharStart(start)
                            .setCharEnd(end)
                            .setSourceEngine(SOURCE_ENGINE)
                            .build()
                    }
            ).sortedBy { it.charStart }
        return parse
            .toBuilder()
            .clearEntities()
            .addAllEntities(entities)
            .build()
    }

    /** The relative periods in [text] as `[start, end)` offsets into it, longest first where two overlap. */
    internal fun find(text: String): List<Pair<Int, Int>> {
        val folded = foldInPlace(text)
        val matches =
            PATTERNS
                .flatMap { re -> re.findAll(folded).map { it.range.first to it.range.last + 1 } }
                .sortedWith(compareBy<Pair<Int, Int>>({ it.first }, { -(it.second - it.first) }))
        val out = mutableListOf<Pair<Int, Int>>()
        for (m in matches) {
            if (out.none { it.first < m.second && m.first < it.second }) out += m
        }
        return out
    }

    /**
     * [Normalization.fold], one character at a time, so every offset into the result is an offset
     * into [text]. A character whose fold is not exactly one character is only lower-cased — Czech
     * has none, and an offset that drifted would type the wrong words a date.
     */
    private fun foldInPlace(text: String): String =
        buildString(text.length) {
            for (c in text) {
                val f = Normalization.fold(c.toString())
                append(if (f.length == 1) f[0] else c.lowercaseChar())
            }
        }
}
