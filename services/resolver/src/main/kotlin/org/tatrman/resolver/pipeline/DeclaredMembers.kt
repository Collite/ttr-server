// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.fuzzy.v1.TargetClass

/**
 * A VALUE LABEL — a declared member of an er attribute — in the lattice's spelling.
 *
 * The lexicon archive spells a value label `er.entity.<e>.<a>.<code>` (`valueLabels` keyed by the stored value; ttr-core's
 * `MetadataExtractor` writes `"$ref.$code"`), and lex-matcher indexes it under that ref as its category. The lattice
 * spells EVERY member `<attribute-ref>#<id>` — a data member is `<category>#<candidate_id>` — and the query door writes
 * what follows the `#`. Read as the archive spells it, „tržiště“ bound `er.entity.sales.channel.marketplace`: a ref the
 * door takes for the attribute, with nothing to compare to.
 *
 * One rule, read by every place a value label leaves the matcher's spelling — the binding ([Bindings]), the fresh
 * domain binding and the clarification option ([GateSpans]), the re-gate's confirmation and scope ([ReGate]) — so the
 * lattice, the resolution and a resumed pick name the same member. `md.` members keep their spelling: no door
 * addresses md, and their readers take them as the archive gives them.
 */
object DeclaredMembers {
    /** `er.entity.<entity>.<attribute>`. */
    private const val ER_ATTRIBUTE_SEGMENTS = 4

    /**
     * [ref] as an er value label in the archive's spelling, split into its attribute and its code — the code whole, dots
     * and `#` included; null for anything else (an object or attribute ref, a ref already in the member spelling, an md
     * ref, an empty code).
     */
    fun split(ref: String): Pair<String, String>? {
        if (!ref.startsWith("er.")) return null
        val parts = ref.split('.')
        if (parts.size <= ER_ATTRIBUTE_SEGMENTS) return null
        val head = parts.take(ER_ATTRIBUTE_SEGMENTS)
        if (head.any { it.isEmpty() || '#' in it }) return null
        val code = parts.drop(ER_ATTRIBUTE_SEGMENTS).joinToString(".")
        if (code.isEmpty()) return null
        return head.joinToString(".") to code
    }

    /** A matcher row that is a value label: declared (never a data member), classed MEMBER, an er archive ref. */
    fun of(match: FuzzyMatch): Pair<String, String>? =
        if (match.source != SourceTag.MEMBER && match.targetClassValue == TargetClass.TARGET_CLASS_MEMBER_VALUE) {
            split(match.targetRef)
        } else {
            null
        }

    /** [ref] in the lattice's spelling: a value label as `<attribute>#<code>`, anything else as it is. */
    fun spelled(ref: String): String = split(ref)?.let { (attribute, code) -> "$attribute#$code" } ?: ref

    /**
     * The archive spelling of an er member ref (`<attribute>#<code>` → `<attribute>.<code>`): the category lex-matcher
     * indexes a value label under. Null when [ref] is no er member ref.
     */
    fun archiveSpelling(ref: String): String? {
        if (!ref.startsWith("er.") || '#' !in ref) return null
        val attribute = ref.substringBefore('#')
        val code = ref.substringAfter('#')
        if (code.isEmpty() || attribute.split('.').size != ER_ATTRIBUTE_SEGMENTS) return null
        return "$attribute.$code"
    }
}
