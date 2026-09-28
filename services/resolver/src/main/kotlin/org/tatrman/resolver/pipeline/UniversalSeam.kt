// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

/**
 * UD (ttr-server#118) — the seam between a universal NER reading and the member reading proposed
 * over the same characters, decided once the gate has spoken (UD contracts §4).
 *
 * `SpanProposal` gives the governed argument of a value-bearing anchor a DUAL READING when the NER
 * typed it a place or a person (`Stores in TN`, `TN` = GPE): the governed pair, flagged
 * [DomainSpanCandidate.dualReading]. The universal layer has meanwhile typed the same characters
 * LOCATION. Both cannot reach the lattice — the composer would see two values for one filter, and
 * `Gaps` would raise a G3 on the grounded twin of a value that is attributed — so exactly one
 * reading keeps the span:
 *
 * - a dual reading with ≥ 1 contender (bound or ambiguous — an ask among members is still a member
 *   reading, ⚑UD-2) that COVERS the universal **supersedes** it: the universal is removed from the
 *   list that grounding, the lattice and the door read. The user named the entity, so the value is
 *   that entity's before it is a place.
 * - a dual reading with no contender is **withdrawn** (A-UD-2): the universal stands alone, and the
 *   question reads exactly as it did before UD. Kept, it would reach the lattice as an unattributed
 *   LITERAL beside the grounded place, with a second G3 over the same characters.
 *
 * Why the flag and not mere overlap: only a candidate proposed AS a dual reading may supersede. A
 * candidate that happens to overlap a universal span by some other path is an accident of geometry,
 * not a reading of the same words, and never removes anything.
 *
 * Contenders are counted AFTER `GateSpans.resolveOpenSiblings`, which has already collapsed the pair
 * to the half that spoke, so "some dual reading found something" is well-defined and counted once
 * (§4.5). Pure: the pipeline calls it once per fresh resolve, before `GroundingRung.ground`, so a
 * superseded universal is never grounded, never assembled and never reported.
 */
object UniversalSeam {
    /**
     * @property universals the universal bindings that keep their span — what grounding, the lattice
     *   and the door read from here on
     * @property gated the gated spans, minus every dual reading that found nothing
     * @property superseded each removed universal with the dual-reading span that won it, for the log
     */
    data class Result(
        val universals: List<UniversalBinding>,
        val gated: List<GatedSpan>,
        val superseded: List<Pair<UniversalBinding, GatedSpan>>,
    )

    fun supersede(
        universals: List<UniversalBinding>,
        gated: List<GatedSpan>,
    ): Result {
        val answered = gated.filter { it.candidate.dualReading && it.contenders.isNotEmpty() }
        val superseded =
            universals.mapNotNull { u ->
                answered
                    .firstOrNull { it.candidate.start <= u.start && it.candidate.end >= u.end }
                    ?.let { u to it }
            }
        val lost = superseded.map { it.first }.toSet()
        return Result(
            universals = universals.filterNot { it in lost },
            gated = gated.filterNot { it.candidate.dualReading && it.contenders.isEmpty() },
            superseded = superseded,
        )
    }
}
