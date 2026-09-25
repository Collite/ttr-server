// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.resolver.v1.UniversalEntityType

/**
 * The single source of truth for "is this NER span universal?" — used by BOTH
 * [SpanProposal] (to remove universal spans before domain gating) and
 * [UniversalExtraction] (to type them). Keeping one classifier makes the two
 * agree by construction: a span is EITHER universal (excluded from the domain
 * path) OR domain-eligible, never both.
 *
 * Q-20 spike §1: NameTag removes `g*`/`p*`/`t*`/`n*` (LOCATION/PERSON/DATE/NUMBER)
 * before domain gating; institutions (`i*`) and objects (`o*`) stay domain-eligible
 * — a domain value like `QT ORLAK` is `io`-tagged, so NER is not the domain filter,
 * the fuzzy gate is.
 *
 * ⚠ BRITTLENESS (ported note): the CNEC leading-letter mapping mirrors NameTag 3 /
 * CNEC 2.0 container codes and is intentionally coarse. It is a heuristic over a
 * *closed* label set; if the NER backend's label scheme changes (a different model,
 * a coarse-label front), this map must be re-validated. The parity corpus (T6)
 * asserts it against the live NameTag output — that is the guard, not this comment.
 */
object UniversalClassifier {
    // Coarse labels nlp/NameTag may emit at the front (PER/LOC/ORG/DATE/MONEY…).
    private val COARSE: Map<String, UniversalEntityType?> =
        mapOf(
            "PER" to UniversalEntityType.PERSON,
            "PERSON" to UniversalEntityType.PERSON,
            "LOC" to UniversalEntityType.LOCATION,
            "LOCATION" to UniversalEntityType.LOCATION,
            "GPE" to UniversalEntityType.LOCATION,
            "DATE" to UniversalEntityType.DATE,
            "TIME" to UniversalEntityType.DATE,
            "MONEY" to UniversalEntityType.MONEY,
            "NUMBER" to UniversalEntityType.MISC,
            "CARDINAL" to UniversalEntityType.MISC,
            "ORDINAL" to UniversalEntityType.MISC,
            "PERCENT" to UniversalEntityType.MISC,
            // Domain-eligible — NOT universal (fuzzy gates these).
            "ORG" to null,
            "ORGANIZATION" to null,
            "INSTITUTION" to null,
            "MISC" to UniversalEntityType.MISC,
        )

    // CNEC 2.0 leading letters that are universal (removed before domain gating).
    private val CNEC_UNIVERSAL: Map<Char, UniversalEntityType> =
        mapOf(
            'p' to UniversalEntityType.PERSON,
            'g' to UniversalEntityType.LOCATION,
            't' to UniversalEntityType.DATE,
            'n' to UniversalEntityType.MISC,
        )

    /**
     * The universal type of an NER entity, or `null` if it is domain-eligible
     * (institution/object) and must be gated against declared vocabulary instead.
     *
     * [normalizedValue] carries the raw CNEC container code as `cnec:<code>` (NameTag 3
     * preserves it there) and, WHEN PRESENT, WINS over [label]. This matters because the
     * nlp NameTag front collapses BOTH objects/other-proper names (`o*` — domain-eligible,
     * the fuzzy gate owns them) AND numbers (`n*` — universal MISC) into the single coarse
     * label `"MISC"`, erasing the distinction the domain path needs. Classifying on the raw
     * container letter keeps them apart — the RG hero's `op`-tagged "Octavie" reaches
     * `er.product` instead of binding as a universal MISC (while `no`-tagged numbers stay MISC).
     * Entities without a `cnec:` code (other engines' coarse labels) fall back to [label].
     */
    fun classify(
        label: String,
        normalizedValue: String = "",
    ): UniversalEntityType? {
        cnecContainer(normalizedValue)?.let { return CNEC_UNIVERSAL[it] }

        val up = label.trim().uppercase()
        if (up.isEmpty()) return null
        if (COARSE.containsKey(up)) return COARSE[up]
        // CNEC container/type codes handed over as the label itself: short, lowercase
        // (e.g. "th", "gu", "ps", "no").
        val raw = label.trim()
        if (raw.length in 1..2 && raw.all { it.isLowerCase() }) {
            return CNEC_UNIVERSAL[raw.first()]
        }
        // Unknown label: treat as domain-eligible (don't silently swallow a value).
        return null
    }

    /** The CNEC container letter from a `cnec:<code>` normalized_value, or `null` if absent. */
    private fun cnecContainer(normalizedValue: String): Char? {
        val v = normalizedValue.trim()
        if (!v.startsWith("cnec:")) return null
        return v.removePrefix("cnec:").firstOrNull()?.lowercaseChar()
    }

    /** True iff the entity denotes a universal span (excluded from domain proposal). */
    fun isUniversal(
        label: String,
        normalizedValue: String = "",
    ): Boolean = classify(label, normalizedValue) != null
}
