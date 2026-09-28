// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.fuzzy.v1.SourceTag

/**
 * UD (ttr-server#118) — the seam between a universal NER reading and the member reading proposed
 * over the same characters, decided once the gate has spoken (UD contracts §4).
 *
 * `SpanProposal` gives the governed argument of a value-bearing anchor a DUAL READING when the NER
 * typed it a place or a person (`Stores in TN`, `TN` = GPE): the governed pair, carrying the NER
 * entity it reads ([DomainSpanCandidate.dualReadingOf]). The universal layer has meanwhile typed the
 * same entity LOCATION. Both cannot reach the lattice — the composer would see two values for one
 * filter, and `Gaps` would raise a G3 on the grounded twin of a value that is attributed — so
 * exactly one reading keeps the span:
 *
 * - a dual reading that found a MEMBER (bound or ambiguous — an ask among members is still a member
 *   reading, ⚑UD-2) **supersedes** the universal it was proposed over: the universal is removed from
 *   the list that grounding, the lattice and the door read. The user named the entity, so the value
 *   is that entity's before it is a place.
 * - a dual reading that found no member is **withdrawn** (A-UD-2, A-UD-4): the universal stands
 *   alone, and the question reads exactly as it did before UD. Kept, it would reach the lattice as a
 *   second LITERAL beside the grounded place, with a second G3 over the same characters.
 *
 * **Only a member speaks** (✅UD-6, A-UD-4). The reason to take the span from the place is that the
 * value is a MEMBER of what the sentence scoped. A declared row — a model object's alias, an
 * operator word — found under the same characters says only that the place is spelled like a term
 * the estate declared (`Sales in Mobile` with a `mobile` channel alias), and the place stands.
 *
 * **Paired by the entity, not by geometry** (A-UD-3, review-108 F1). The universal's extent is the
 * NER engine's; the candidate's is the parse tokens'. They need not agree to the character —
 * NameTag's end overshoots a hyphenated name, and an anchor word inside the name is left out of the
 * value — so a candidate supersedes the universal whose `[start, end)` is exactly the entity it was
 * proposed over, and a candidate that merely overlaps a universal by some other path (an accident of
 * geometry, not a reading of the same words) never removes anything.
 *
 * Contenders are counted AFTER `GateSpans.resolveOpenSiblings`, which has already collapsed the pair
 * to the half that spoke — by the same [speaks] — so "some dual reading found a member" is
 * well-defined and counted once (§4.5). Pure: the pipeline calls it once per fresh resolve, before
 * `GroundingRung.ground`, so a superseded universal is never grounded, never assembled and never
 * reported.
 */
object UniversalSeam {
    /**
     * @property universals the universal bindings that keep their span — what grounding, the lattice
     *   and the door read from here on
     * @property gated the gated spans, minus every dual reading that found no member
     * @property superseded each removed universal with the dual-reading span that won it, for the log
     */
    data class Result(
        val universals: List<UniversalBinding>,
        val gated: List<GatedSpan>,
        val superseded: List<Pair<UniversalBinding, GatedSpan>>,
    )

    /**
     * Whether a gated span found something that counts: for a dual reading, a MEMBER row (✅UD-6);
     * for every other span, any contender, as it always has been. The one definition, read here and
     * by `GateSpans.resolveOpenSiblings`, so the two cannot disagree about which half spoke.
     */
    fun speaks(g: GatedSpan): Boolean =
        if (g.candidate.dualReading) {
            g.contenders.any { it.match.source == SourceTag.MEMBER }
        } else {
            g.contenders.isNotEmpty()
        }

    fun supersede(
        universals: List<UniversalBinding>,
        gated: List<GatedSpan>,
    ): Result {
        val answered = gated.filter { it.candidate.dualReading && speaks(it) }
        val superseded =
            universals.mapNotNull { u ->
                answered
                    .firstOrNull { it.candidate.dualReadingOf == (u.start to u.end) }
                    ?.let { u to it }
            }
        val lost = superseded.map { it.first }.toSet()
        return Result(
            universals = universals.filterNot { it in lost },
            gated = gated.filterNot { it.candidate.dualReading && !speaks(it) },
            superseded = superseded,
        )
    }
}
