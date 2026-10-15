// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.slf4j.LoggerFactory
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.resolver.model.Reach
import org.tatrman.resolver.model.ResolverThresholds

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
 *  1. A conjunct's READINGS are the entities owning the member rows the gate admits for it with NO scope
 *     applied — its lookup's rows over every vocabulary (the open half asked them all), through the one
 *     [Binder], so WEAK rows and rows outside the tie band are no readings. Before the scope, because the
 *     scope is exactly what read `Brno` one-sidedly: under a measure it is the measure's fact, and a fact
 *     never selects among member owners (`Binder.memberGovernance`'s posture, design §2a).
 *  2. A conjunct with one reading is UNAMBIGUOUS, one with several AMBIGUOUS; one with none takes no part.
 *  3. When every unambiguous conjunct has the same reading E, and E is among the readings of some
 *     ambiguous conjunct, the coordination intersects at E: those conjuncts, and the unambiguous ones,
 *     keep E's member rows only ([keep]) instead of the scope's. Each then binds its member of E.
 *  4. Otherwise nothing changes, and the scope decides as before (and the Binder asks where it still
 *     ties): no unambiguous conjunct (`Brno a Plzeň` — both a store and a DC), no ambiguous one (every
 *     conjunct already has one reading, which is not what the ruling is about), unambiguous conjuncts
 *     that disagree, or an intersection that is empty.
 *
 * **Only where no entity governs.** A coordination whose governor names an ENTITY (`prodejen`, `sklady`,
 * "stores" — [SlotHint.governorRef]) is not decided here: the sentence named the axis, and its scope has
 * already read every conjunct by it („Tržby prodejen Brno a Liberec“ → both stores). The coordination
 * overrides only a scope the sentence did not name — a measure's fact — so „sklady Brno a Liberec“ can
 * never become two stores.
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
     * @param rowsOf the batch's rows for the candidate at an index, as the matcher answered them
     */
    fun intersect(
        candidates: List<DomainSpanCandidate>,
        rowsOf: (Int) -> List<FuzzyMatch>,
        thresholds: ResolverThresholds,
        owners: Map<String, String>,
        kinds: Map<String, String>,
        reach: Map<String, List<Reach>>,
        memberOwners: Map<String, String>,
    ): Map<Int, String> {
        val groups =
            candidates.indices
                .filter { candidates[it].origin in VALUE_ORIGINS && candidates[it].slot.coordGroup >= 0 }
                .groupBy { candidates[it].slot.coordGroup }
        if (groups.isEmpty()) return emptyMap()

        val decided = HashMap<Int, String>()
        for ((_, members) in groups) {
            // An entity governs ⇒ its scope has read the conjuncts already (see the class doc).
            if (members.any { candidates[it].slot.governorRef.isNotBlank() }) continue
            val bySpan = members.groupBy { candidates[it].start to candidates[it].end }
            if (bySpan.size < 2) continue

            val readings =
                bySpan.mapValues { (_, at) ->
                    // The open half asked every vocabulary; a span proposed without one answers by its
                    // own rows. Either way the rows of the whole span, once each.
                    val asker =
                        at.firstOrNull { candidates[it].origin == DomainSpanCandidate.Origin.OPEN_VALUE } ?: at.first()
                    val rows = at.flatMap(rowsOf).distinctBy { "${it.category}|${it.candidateId}|${it.targetRef}" }
                    Binder
                        .gate(rows, candidates[asker], thresholds, owners, kinds, reach, memberOwners)
                        .admitted
                        .filter { it.match.source == SourceTag.MEMBER }
                        .map { entityOf(it.match, owners, memberOwners) }
                        .toSet()
                }
            val unambiguous = readings.filterValues { it.size == 1 }
            val ambiguous = readings.filterValues { it.size > 1 }
            if (unambiguous.isEmpty() || ambiguous.isEmpty()) continue
            val shared =
                unambiguous.values
                    .flatten()
                    .toSet()
                    .singleOrNull() ?: continue
            val takers = ambiguous.filterValues { shared in it }.keys
            if (takers.isEmpty()) continue

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

    /** [matches] with the member rows of any entity but [entity] removed; every other row stays. */
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
