// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.slf4j.LoggerFactory
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.SourceTag

/**
 * ttr-demo#62, the coordination ruling (Bora, 2026-10-14), over MEMBER VALUES: of two values joined by
 * „a“ / „nebo“ / "and" / "or", one with several readings and the other with one, the ambiguous one takes
 * the reading where the two INTERSECT, and nothing is asked. `SlotHints` rule 1 (`COORD_WITH`) does this
 * for mentions of model objects; this is the same ruling for the values a sentence restricts by.
 *
 * *„Tržby pro Brno a Liberec v roce 2025“* on hartland's Czech world: `Brno` is a store AND the
 * distribution centre "Brno DC" (both rows in one tie band), `Liberec` is a store only. The places sit
 * under `Tržby`, the all-channel revenue, so their scope is that measure's fact (✅UD-8), and the fact
 * reaches the warehouse but not the store (`UniversalSeam.inScope`). So `Brno` read "Brno DC" and
 * `Liberec`, out of scope, stayed a bare place (G3): the question could be answered as Brno DC's
 * revenue alone. The two are one coordination, and the reading they share is the store.
 *
 * **The rule.** For each coordination of governed values ([SlotHint.coordGroup]):
 *
 *  1. A conjunct's READINGS are what its span's own verdict admits, made exactly as `GateSpans` makes it
 *     but with NO scope applied: each half (the governed and the open candidate) gated on its OWN rows by
 *     its own candidate, through the one [Binder], and the governed half speaking for the span where it
 *     found a member (`GateSpans.resolveOpenSiblings`). So WEAK rows and rows outside a half's tie band
 *     are no readings, and the two halves are never pooled into one band: they come from two requests
 *     (the governor's vocabularies, and every vocabulary), and a score compares only within one class of
 *     one request. Before the scope, because the scope is exactly what read `Brno` one-sidedly: under a
 *     measure it is the measure's fact, and a fact never selects among member owners
 *     (`Binder.memberGovernance`'s posture, design §2a).
 *  2. A conjunct whose verdict admits the members of ONE entity and nothing else is UNAMBIGUOUS; one
 *     that admits the members of several, or a model object beside a member (a declared word that ties
 *     with the value), is AMBIGUOUS — so it never forces its entity on the others; one that admits no
 *     member takes no part.
 *  3. When every unambiguous conjunct has the same reading E, the coordination reads as E:
 *      - and E is among the member readings of some ambiguous conjunct — those conjuncts take it;
 *      - or there is no ambiguous conjunct and at least two unambiguous ones agree on E („Tržby pro
 *        Liberec a Olomouc“, both only stores — ruled 2026-10-16, consistent with „Brno a Liberec“).
 *     Then those conjuncts, and the unambiguous ones, keep E's member rows only ([keep]) instead of the
 *     scope's, even where the scope does not reach E. Each binds its member of E — or, where a model
 *     object tied with the value, asks between E's member and that object: [keep] narrows members only,
 *     and which OBJECT a word names is not a question about values.
 *  4. Otherwise nothing changes, and the scope decides as before (and the Binder asks where it still
 *     ties): no unambiguous conjunct (`Brno a Plzeň` — both a store and a DC), unambiguous conjuncts
 *     that disagree, a single conjunct with a reading, or ambiguous conjuncts none of which includes E
 *     (the intersection is empty).
 *
 * **Only where no entity governs.** A coordination whose governor names an ENTITY (`prodejen`, `sklady`,
 * "stores" — [SlotHint.entityGoverned]) is not decided here: the sentence named the axis, and its scope has
 * already read every conjunct by it („Tržby prodejen Brno a Liberec“ → both stores). That holds for an
 * anchor word declared for SEVERAL entities too, which leaves [SlotHint.governorRef] blank: it named an
 * axis, only an ambiguous one. The coordination overrides only a scope the sentence did not name — a
 * measure's fact — so „sklady Brno a Liberec“ can never become two stores.
 *
 * Pure: the gate calls it once per pass over the batch it already has, before deciding any span.
 */
object CoordinatedValues {
    private val log = LoggerFactory.getLogger(CoordinatedValues::class.java)

    private val VALUE_ORIGINS =
        setOf(DomainSpanCandidate.Origin.GOVERNED_VALUE, DomainSpanCandidate.Origin.OPEN_VALUE)

    /**
     * Candidate index → the entity whose member rows that candidate keeps, for every candidate whose
     * reading a coordination decides; empty when none does.
     *
     * @param verdictOf the Binder's verdict for the candidate at an index over its own batch rows with NO
     *   scope applied — the broad pass's own gate call (`GateSpans` passes it), minus `UniversalSeam.inScope`
     */
    fun intersect(
        candidates: List<DomainSpanCandidate>,
        verdictOf: (Int) -> Binder.Verdict,
        owners: Map<String, String>,
        memberOwners: Map<String, String>,
    ): Map<Int, String> {
        val groups =
            candidates.indices
                .filter { candidates[it].origin in VALUE_ORIGINS && candidates[it].slot.coordGroup >= 0 }
                .groupBy { candidates[it].slot.coordGroup }
        if (groups.isEmpty()) return emptyMap()

        val decided = HashMap<Int, String>()
        for ((_, members) in groups) {
            // An entity governs — one, or several — ⇒ its scope has read the conjuncts already (class doc).
            if (members.any { entityGoverns(candidates[it].slot) }) continue
            val bySpan = members.groupBy { candidates[it].start to candidates[it].end }
            if (bySpan.size < 2) continue

            val readings =
                bySpan
                    .mapValues { (_, at) -> readingsOf(at, candidates, verdictOf, owners, memberOwners) }
                    .filterValues { it.entities.isNotEmpty() }
            val unambiguous = readings.filterValues { it.entities.size == 1 && !it.objects }
            val ambiguous = readings - unambiguous.keys
            if (unambiguous.isEmpty()) continue
            val shared =
                unambiguous.values
                    .flatMap { it.entities }
                    .toSet()
                    .singleOrNull() ?: continue
            val takers = ambiguous.filterValues { shared in it.entities }.keys
            // Ambiguous conjuncts present: the ones that include E take it, or nothing does (rule 3, 4). None
            // present: two unambiguous conjuncts that agree are one reading, already (ruled 2026-10-16).
            if (if (ambiguous.isEmpty()) unambiguous.size < 2 else takers.isEmpty()) continue

            for (span in takers + unambiguous.keys) {
                for (i in bySpan.getValue(span)) decided[i] = shared
            }
            log.info(
                "coordination: {} of {} conjuncts read as {} ({} ambiguous took it)",
                takers.size + unambiguous.size,
                bySpan.size,
                shared,
                takers.size,
            )
        }
        return decided
    }

    /** An entity governs the value: one ([SlotHint.governorRef]), or several (see the class doc). */
    private fun entityGoverns(slot: SlotHint): Boolean = slot.entityGoverned || slot.governorRef.isNotBlank()

    /**
     * One conjunct's readings (rule 1–2): the entities owning the member rows its verdict admits, and
     * whether that verdict admits a model object beside them.
     */
    private data class Readings(
        val entities: Set<String>,
        val objects: Boolean,
    )

    /**
     * The readings of the conjunct whose halves are [at]: each half's own verdict ([verdictOf]), the
     * halves kept as `GateSpans` keeps them (rule 1).
     */
    private fun readingsOf(
        at: List<Int>,
        candidates: List<DomainSpanCandidate>,
        verdictOf: (Int) -> Binder.Verdict,
        owners: Map<String, String>,
        memberOwners: Map<String, String>,
    ): Readings {
        val halves =
            at.map { i ->
                val verdict = verdictOf(i)
                GatedSpan(candidates[i], verdict.admitted, ambiguous = verdict is Binder.Ambiguous)
            }
        val admitted = GateSpans.resolveOpenSiblings(halves).flatMap { it.contenders }
        return Readings(
            entities =
                admitted
                    .filter { it.match.source == SourceTag.MEMBER }
                    .map { entityOf(it.match, owners, memberOwners) }
                    .toSet(),
            objects = admitted.any { it.match.source != SourceTag.MEMBER },
        )
    }

    /**
     * [matches] with the member rows of any entity but [entity] removed; every other row stays.
     *
     * `GateSpans` calls this IN PLACE of `UniversalSeam.inScope` for a conjunct the coordination decided,
     * so the reach check is not made for it, on purpose: „Tržby pro Brno a Liberec“ binds two
     * `store.store_name` members under the all-channel revenue, whose fact has no relation to the store.
     * Reinstating the check would undo the ruling. What makes that safe is downstream: the query door
     * (golem's door shaper) finds no relation from the measure's fact to the members' entity and hands
     * the question to the composer (the LLM lane), so it is never answered as a fast-path number.
     */
    fun keep(
        matches: List<FuzzyMatch>,
        entity: String,
        owners: Map<String, String>,
        memberOwners: Map<String, String>,
    ): List<FuzzyMatch> =
        matches.filter { it.source != SourceTag.MEMBER || entityOf(it, owners, memberOwners) == entity }

    /** The entity owning a member row — the one `UniversalSeam.inScope` and the Binder's M3 name. */
    private fun entityOf(
        m: FuzzyMatch,
        owners: Map<String, String>,
        memberOwners: Map<String, String>,
    ): String {
        val owner = memberOwners[m.category] ?: m.category
        return owners[owner]?.takeIf { it.isNotBlank() } ?: owner
    }
}
