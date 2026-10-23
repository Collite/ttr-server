// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.resolver.model.ConceptFacts

/**
 * CN-P4 S4.4 (cn contracts §1.5, A-CN-8) — the concept steps of the resolution-order table, inside the
 * Binder's one decision. The table, per span, each step keeping the survivors of the one before:
 *
 *  0. the longest covered span wins — span proposal's rule, before any of this;
 *  1. **a phase yields to its bearer** — [beforeScore];
 *  2. exact form over partial — RV-14's class order: the Binder keeps the top class first;
 *  3. `default: true`, ignored on a phase — [beforeScore];
 *  4. `sense_priority`, higher wins — [beforeScore];
 *  5. in-class score — the Binder's tie band;
 *  6. **R-cn-1** — one concept against element readings binds the concept iff it grounds to each of
 *     them; otherwise honest ambiguity, concepts first — [groundedConcept].
 *
 * ⚑ A-CN-71 — step 1 runs inside the top class, after step 2, where the table lists it before. The two
 * orders differ only for a phase and its bearer read in different classes, and there the phase read
 * EXACTLY must not yield to a bearer read partially ("zákazník po splatnosti" is not "zákazník" with
 * two words left over). Today no concept row can be read partially (concept terms compile to an
 * exact-only profile), so no estate can tell the two orders apart yet.
 *
 * Every step reads OBJECT readings only (`V:` rows): a member is a data value, and a sense, a bearer or
 * a grounding is a claim about which object a word names. And every step is a preference that never
 * empties the band, the posture of the Binder's MH rules: a step with nothing to choose leaves the band
 * as it found it. On a registry with no concept tier ([ConceptFacts.isEmpty]) all three are no-ops.
 */
object ConceptOrder {
    /** The [EquivalentReading.rule] R-cn-1 discloses an element reading under: the concept grounds to it. */
    const val RULE_CN_GROUNDS = "cn-grounds"

    /** Steps 1, 3 and 4, over one evidence class's candidates, before the score band. */
    fun beforeScore(
        inClass: List<Binder.ClassedMatch>,
        concepts: ConceptFacts,
        rejected: MutableList<Binder.ClassedMatch>,
    ): List<Binder.ClassedMatch> {
        if (concepts.isEmpty) return inClass
        var kept = inClass

        // 1 — a phase yields to its bearer: a phase reading whose `of:` chain is also read on this span
        // is the bare kind's or role's word, never the phase's (L17-3).
        val refs = objects(kept).map { it.match.targetRef }.toSet()
        kept = drop(kept, rejected) { c -> concepts.isPhase(ref(c)) && concepts.bearers(ref(c)).any { it in refs } }

        // ⚑R130-d — an element reading a concept in the band grounds to is that concept's reading (R-cn-1 folds
        // it at step 6), so at steps 3–4 it takes that concept's sense as well as its own: it stands or falls with
        // the concept instead of being discarded before R-cn-1 can record it as a source.
        fun lenders(c: Binder.ClassedMatch): List<Binder.ClassedMatch> =
            if (concepts.isConcept(ref(c))) {
                listOf(c)
            } else {
                listOf(c) + objects(kept).filter { concepts.isConcept(ref(it)) && concepts.groundsTo(ref(it), ref(c)) }
            }

        // 3 — the default sense of the form, ignored on a phase (AM-36).
        kept =
            keepPreferred(kept, rejected) { c ->
                lenders(c).any { l ->
                    !concepts.isPhase(ref(l)) &&
                        concepts.sense(ref(l), l.match.candidate)?.default == true
                }
            }

        // 4 — the authored priority, higher wins; a term that states none ranks as 0.
        val priority =
            objects(kept).associateWith { c ->
                lenders(c).maxOf { l -> concepts.sense(ref(l), l.match.candidate)?.priority ?: 0.0 }
            }
        val top = priority.values.maxOrNull()
        if (top != null) kept = keepPreferred(kept, rejected) { c -> priority[c] == top }
        return kept
    }

    /**
     * Step 6, R-cn-1 — a concept and element readings of equal strength. ONE concept that grounds to
     * every element reading (its ER landing, or a target it is grounded on — [ConceptFacts.groundsTo])
     * names the same thing: the concept binds, and each element reading is disclosed as an equivalent
     * ([RULE_CN_GROUNDS]) — which is also what the lift reads its `lift:` sources from. Otherwise the
     * readings differ, and the band stays a question with the concepts first ("kanál": `Channel` lands on
     * the attribute, not on `channel_sales`).
     */
    fun groundedConcept(
        admitted: List<Binder.ClassedMatch>,
        concepts: ConceptFacts,
        rejected: MutableList<Binder.ClassedMatch>,
    ): Pair<List<Binder.ClassedMatch>, List<EquivalentReading>> {
        if (concepts.isEmpty) return admitted to emptyList()
        val (cn, elements) = objects(admitted).partition { concepts.isConcept(ref(it)) }
        if (cn.isEmpty() || elements.isEmpty()) return admitted to emptyList()

        val concept = cn.singleOrNull()
        if (concept != null && elements.all { concepts.groundsTo(ref(concept), ref(it)) }) {
            rejected += elements
            return (admitted - elements.toSet()) to elements.map { EquivalentReading(ref(it), RULE_CN_GROUNDS) }
        }
        return (cn + (admitted - cn.toSet())) to emptyList()
    }

    private fun ref(c: Binder.ClassedMatch): String = c.match.targetRef

    private fun objects(band: List<Binder.ClassedMatch>) = band.filter { it.match.source != SourceTag.MEMBER }

    /** Removes the object readings [dropIf] names — unless that would remove them all. */
    private fun drop(
        band: List<Binder.ClassedMatch>,
        rejected: MutableList<Binder.ClassedMatch>,
        dropIf: (Binder.ClassedMatch) -> Boolean,
    ): List<Binder.ClassedMatch> {
        val objects = objects(band)
        val dropped = objects.filter(dropIf)
        if (dropped.isEmpty() || dropped.size == objects.size) return band
        rejected += dropped
        return band - dropped.toSet()
    }

    /** Keeps the object readings [prefers] names, when some are and some are not. */
    private fun keepPreferred(
        band: List<Binder.ClassedMatch>,
        rejected: MutableList<Binder.ClassedMatch>,
        prefers: (Binder.ClassedMatch) -> Boolean,
    ): List<Binder.ClassedMatch> = drop(band, rejected) { !prefers(it) }
}
