// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.resolver.model.ConceptFacts

/**
 * CN-P4 S4.4 (cn contracts §5, A-CN-5/7) — the concepts one bound reading reads as: the reverse lift
 * via both layers, from the archive's `liftsTo`.
 *
 *  - a term that names a concept reads as that concept (`term:<concept>`);
 *  - an element hit lifts from its own ref;
 *  - a member hit lifts from its category (value kinds and qualities on that attribute) **and** from the
 *    category's owner (the thing that has the value) — never across a relation, so a city of
 *    `customer_address` lifts to nothing a `customer` concept is;
 *  - a lift never lands on a phase (the archive already leaves phases out; this holds it here too).
 *
 * The union is deduped by concept. `via` is a function of the concept (one grounding, CN-D12), so what
 * the union keeps is every SOURCE, as `lift_provenance` ("tržby" → one `cn.revenue` reading from
 * `term:cn.revenue` and `lift:md.measure.revenue`, the element reading R-cn-1 folded into it). Every
 * reading carries its stereotype: a member of `channel` lifts to both `Channel` and `Sale`, and the
 * frame builder filters (A-CN-7).
 */
object ConceptLifts {
    /** One concept reading — the internal twin of `resolver.v1.ConceptReading`. */
    data class LiftedConcept(
        val qname: String,
        val stereotype: String,
        val via: String,
        val provenance: List<String>,
        /** `cn/ungrounded` or `md/er-unmapped` when the concept has no ER landing; `""` otherwise. */
        val note: String,
    )

    const val NOTE_UNGROUNDED = "cn/ungrounded"
    const val NOTE_ER_UNMAPPED = "md/er-unmapped"

    private const val VIA_MD_HOP = "md-hop"

    /**
     * [top]'s concept readings. [category] is the vocabulary a member row came from (its attribute) and
     * [owner] that vocabulary's entity; [equivalents] are the element readings the Binder folded into a
     * concept by R-cn-1, each a further source of that concept.
     */
    fun of(
        top: FuzzyMatch,
        category: String,
        owner: String,
        equivalents: List<EquivalentReading>,
        concepts: ConceptFacts,
    ): List<LiftedConcept> {
        if (concepts.isEmpty) return emptyList()
        val sources = LinkedHashMap<String, MutableList<String>>()
        val vias = HashMap<String, String>()

        fun lift(from: String) {
            for (l in concepts.liftsTo(from)) {
                if (concepts.isPhase(l.qname)) continue
                sources.getOrPut(l.qname) { mutableListOf() } += "lift:$from"
                vias.putIfAbsent(l.qname, l.via)
            }
        }

        if (top.source == SourceTag.MEMBER) {
            lift(category)
            if (owner.isNotBlank() && owner != category) lift(owner)
        } else if (concepts.isConcept(top.targetRef)) {
            val concept = top.targetRef
            sources[concept] = mutableListOf("term:$concept")
            for (e in equivalents.filter { it.rule == ConceptOrder.RULE_CN_GROUNDS }) {
                if (concepts.liftsTo(e.ref).any { it.qname == concept }) sources.getValue(concept) += "lift:${e.ref}"
            }
        } else {
            lift(top.targetRef)
        }

        return sources.map { (qname, from) ->
            val via = concepts.via(qname).ifBlank { vias[qname].orEmpty() }
            LiftedConcept(
                qname = qname,
                stereotype = concepts.stereotype(qname),
                via = via,
                provenance = from.distinct(),
                note =
                    when {
                        concepts.erTarget(qname).isNotBlank() -> ""
                        via == VIA_MD_HOP -> NOTE_ER_UNMAPPED
                        else -> NOTE_UNGROUNDED
                    },
            )
        }
    }

    /**
     * ⚑ A-CN-70 — what a concept binding's `Domain` names: the concept's ER landing; else the element
     * R-cn-1 proved it equal to (an md target whose hop is missing — "tržby" keeps naming
     * `md.measure.revenue`); else nothing. A pre-P4 consumer of the door therefore never meets a `cn.*`
     * ref where it reads an object, and the concept itself rides in `EntityBinding.concepts`. Null for a
     * reading that is not a concept's.
     */
    fun domainTargetOf(
        top: FuzzyMatch,
        equivalents: List<EquivalentReading>,
        concepts: ConceptFacts,
    ): String? {
        if (top.source == SourceTag.MEMBER || !concepts.isConcept(top.targetRef)) return null
        return concepts.erTarget(top.targetRef).ifBlank {
            equivalents.firstOrNull { it.rule == ConceptOrder.RULE_CN_GROUNDS }?.ref.orEmpty()
        }
    }
}
