// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.resolver.model.Reach
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.v1.EvidenceClass

/**
 * RV-P2.2.T3 — **the binder**. The one place in this service where a candidate becomes a binding.
 *
 * Every producer's output passes here — the broad pass's `BatchMatch` (T5), the P2.3 lookup rounds,
 * the P2.4 re-gate's hypotheses — and each of them is a *proposer* (RV-7). None of them binds. That
 * is not a convention: `GateSpans` no longer contains a selection rule, and there is deliberately
 * no second function anywhere that takes a list of candidates and returns a winner.
 *
 * The decision is RV-14's lexicographic order:
 *
 *   `EXACT > DECLARED_ALIAS > LEARNED_ALIAS > ANCHORED_FUZZY_STRONG > UNANCHORED_FUZZY_STRONG > WEAK`
 *
 * and the whole of it reduces to one sentence — **a score compares only within a class**. Three
 * consequences the tests pin separately, because each removes a different failure the pre-RV
 * resolver had:
 *
 *  - **WEAK never binds**, whatever it scored, and is never offered as a clarification option
 *    either. issues.md's *středisko* rows at 0.667 do not become a binding and do not become a
 *    question; they become a typed gap, which is the honest thing the era before this had no way
 *    to say. A whole field of WEAK is a G1/G3, not a menu of guesses.
 *  - **the top class wins outright.** A 0.99 in a lower class loses to a 0.62 above it. This is
 *    what the old exact-dominance special case was reaching for — it filtered the sub-exact field
 *    away when an exact hit was present — generalized to every rung of the order, so it no longer
 *    needs to be a special case at all.
 *  - **a same-class tie is a refusal.** Two distinct identities inside [ResolverThresholds.ambiguityGap]
 *    of the top score bind nothing (RS-26); the door renders that as a clarification and the
 *    lattice as a mention with several bindings plus a G2.
 *
 * MH adds a fourth, in the same shape — a filter INSIDE the one decision, parameterised by
 * declared data, and a no-op when that data is absent:
 *
 *  - **a cross-kind tie is decided by the slot; a same-kind tie is a real ambiguity.** Two
 *    unrelated objects sharing a word (a dimension and the fact a channel term is pinned to)
 *    used to be a G2 on kinds alone. The sentence already says which kind it wants — you count
 *    things, you group by things, you restrict a measure by what is measured — so the slot's
 *    preferred kinds survive. Two objects of the SAME kind sharing a word is genuine homonymy
 *    and still refuses: that is the definition, not a gap.
 *  - **two readings proven EQUAL by declared relations collapse to the dimension; a nullable
 *    reach is a real question.** When every row of the fact the clause is about carries the
 *    dimension, *"restrict to the Stores channel"* and *"join to the store dimension"* select
 *    the same rows, so the more informative object wins and the suppressed reading is recorded
 *    (`Bind.equivalents`). When the key can be missing the two answers differ, and the honest
 *    verdict is the question — which is also why this rule may VETO the slot rule above it.
 *
 * The verdict keeps [Verdict.rejected] as well as [Verdict.admitted] because they go to different
 * places: admitted candidates are the lattice's bindings, rejected ones are the round's log
 * (P2.3.T5 — "rejected candidates appear in the round's log, not in the lattice"). Nothing is
 * silently dropped; it is dropped *somewhere legible*.
 */
object Binder {
    /** A matcher row and the class the core derived for it — the gate's unit of comparison. */
    data class ClassedMatch(
        val match: FuzzyMatch,
        val evidenceClass: EvidenceClass,
    )

    /** What the gate decided for ONE span. */
    sealed interface Verdict {
        /** The candidates that survived — what the lattice records. Empty ⇒ nothing was admitted. */
        val admitted: List<ClassedMatch>

        /** Everything the gate refused, with the class that refused it. For the rung log. */
        val rejected: List<ClassedMatch>
    }

    /** One identity in the top class: it binds. */
    data class Bind(
        val winner: ClassedMatch,
        override val admitted: List<ClassedMatch>,
        override val rejected: List<ClassedMatch>,
        /**
         * MH-D3 — readings this bind was proven EQUAL to by the model's declared relations, and
         * therefore suppressed. Empty for every bind no reachability rule fired on, which is
         * nearly all of them. It is disclosure, not a second binding: a consumer may SURFACE it
         * ("read as the Store dimension; on this model the same rows as the Stores channel") and
         * must not re-plan on it.
         */
        val equivalents: List<EquivalentReading> = emptyList(),
    ) : Verdict

    /** Several distinct identities, same class, inside the tie band: ask, don't guess (RS-26). */
    data class Ambiguous(
        override val admitted: List<ClassedMatch>,
        override val rejected: List<ClassedMatch>,
    ) : Verdict

    /** Nothing reached the class floor — a first-class unknown, typed by [Gaps] as G1/G3/G4. */
    data class NoBind(
        override val rejected: List<ClassedMatch>,
    ) : Verdict {
        override val admitted: List<ClassedMatch> get() = emptyList()
    }

    /**
     * Classify then decide — the entry point every producer uses.
     *
     * [candidate] supplies the anchoring context (and nothing else): whether span proposal scoped
     * this lookup to an entity the user named, which is the only difference between the two
     * `*_FUZZY_STRONG` classes.
     */
    fun gate(
        matches: List<FuzzyMatch>,
        candidate: DomainSpanCandidate,
        thresholds: ResolverThresholds,
        owners: Map<String, String> = emptyMap(),
        kinds: Map<String, String> = emptyMap(),
        reach: Map<String, List<Reach>> = emptyMap(),
        memberOwners: Map<String, String> = emptyMap(),
    ): Verdict =
        // MH: the slot rides the CANDIDATE, because this function never sees the parse
        // (architecture A3). A re-gated synthetic candidate therefore carries `SlotHint.NONE`,
        // which is right: a re-decision with no parse must not invent a slot.
        decide(
            classify(matches, candidate, thresholds),
            thresholds,
            owners,
            candidate.slot,
            kinds,
            reach,
            memberOwners,
        )

    fun classify(
        matches: List<FuzzyMatch>,
        candidate: DomainSpanCandidate,
        thresholds: ResolverThresholds,
    ): List<ClassedMatch> = matches.map { ClassedMatch(it, EvidenceClasses.of(it, candidate.anchored, thresholds)) }

    /** The pure ordering decision — see the class doc. */
    fun decide(
        classed: List<ClassedMatch>,
        thresholds: ResolverThresholds,
        owners: Map<String, String> = emptyMap(),
        // MH — all three defaulted, and all three no-ops when absent: a pre-MH archive, a
        // registry with no kinds, a re-gate with no parse, and an estate that declared no
        // relations each land here with empty inputs and get byte-identical pre-MH behaviour.
        slot: SlotHint = SlotHint.NONE,
        kinds: Map<String, String> = emptyMap(),
        reach: Map<String, List<Reach>> = emptyMap(),
        // MH tier M — fuzzy category → the entity whose member it is (`owner(m)`; MV §5.3,
        // `memberEntityByCategory`). Defaulted and no-op when absent, like the three above it.
        memberOwners: Map<String, String> = emptyMap(),
    ): Verdict {
        val rejected = mutableListOf<ClassedMatch>()
        val eligible = mutableListOf<ClassedMatch>()
        // RV-14, and it is the first thing that happens rather than a filter somewhere downstream:
        // a WEAK candidate is not a weaker binding, it is not a binding.
        for (c in classed) {
            if (c.evidenceClass == EvidenceClass.EVIDENCE_CLASS_WEAK) rejected += c else eligible += c
        }
        if (eligible.isEmpty()) return NoBind(rejected)

        val topRank = eligible.minOf { EvidenceClasses.rank(it.evidenceClass) }
        val (inTopClass, belowTopClass) = eligible.partition { EvidenceClasses.rank(it.evidenceClass) == topRank }
        rejected += belowTopClass

        // Scores are comparable HERE and only here — one class, so one scale.
        val ranked = inTopClass.sortedByDescending { it.match.score }
        val top = ranked.first()
        val (contenders, outOfBand) = ranked.partition { top.match.score - it.match.score <= thresholds.ambiguityGap }
        rejected += outOfBand

        // The same identity reached twice (two spans, two categories, one target) is one candidate,
        // and the stronger reading of it speaks — never an ambiguity with itself.
        val distinct = contenders.distinctBy { identityKey(it.match) }

        // MS-P3.S2 — the declared-containment collapse (contracts §8.3, design.md §10.2 amendment 2).
        //
        // An entity tied with its OWN attribute is one answer reached at two granularities, not a
        // tie: `tržby` declared for both `er.entity.sales` and its measure `amount_czk` used to
        // reach here as two `V:` identities inside the band and leave as a G2 — asking the user to
        // choose between an entity and its own measure, a question no user can parse. The attribute
        // speaks: it is the more specific object, and the entity reading stays recoverable through
        // the owner ref it declared.
        //
        // This is the same kind of rule as the `distinctBy` above it — "one answer reached twice" —
        // and deliberately no more than that. It fires ONLY on a declared containment relation
        // between two `V:` identities that are already in the top class and already inside the tie
        // band: no class is compared across, no score is weighed, WEAK is long gone, and a genuine
        // ambiguity (two siblings, or two unrelated objects) still refuses. `owners` empty — the
        // pre-v3 estate, and every estate that declared no mention facet — is byte-identical.
        //
        // ⛔ The containment is DECLARED data, threaded in from the registry. It is never parsed
        // out of a ref string here: `er.entity.sales.amount_czk` looking like a child of
        // `er.entity.sales` is a spelling coincidence this service is not permitted to read.
        val ownedRefs =
            distinct
                .filter { isModelObject(it) }
                .mapNotNull { owners[it.match.targetRef]?.takeIf { ref -> ref.isNotBlank() } }
                .toSet()
        val (collapsed, survived) =
            distinct.partition { isModelObject(it) && it.match.targetRef in ownedRefs }
        // A containment CYCLE would collapse everything and leave nothing to bind — `{a owns b,
        // b owns a}`, or a ref declared as its own owner. Neither can arise from a model (an
        // attribute's owner is an entity, and entities own nothing), but `owners` is data this
        // service did not produce, so the collapse declines rather than throwing on `single()`.
        //
        // ⚠ It declines by yielding the UN-collapsed set to the ordinary size check below, not by
        // returning a verdict of its own (review-084 F3). Returning `Ambiguous` here made
        // `Ambiguous` reachable with ONE admitted candidate — an invariant this class had held
        // since RV-14 — and `GateSpans.outcomeOf` renders any ambiguous span by offering its
        // contenders, so a single self-owning row came back as a clarification with one option.
        // Asking the user to choose between one thing is the failure the containment collapse
        // exists to remove; producing it from malformed data is no better than producing it from
        // good data.
        val admitted0 =
            if (survived.isEmpty()) {
                distinct
            } else {
                // Nothing is silently dropped — the owner rides the rung log like every other refusal.
                rejected += collapsed
                survived
            }

        // MH T2 — the slot preference (contracts §7.3, ✅MH-D2 preference, not admissibility).
        //
        // Two unrelated objects can share a word — a dimension and the fact a channel term is
        // pinned to — and neither owns the other, so the collapse above does not apply and this
        // band is a genuine G2 on kinds alone. But since MS the two DIFFER in kind, and the
        // sentence says which kind it wants: you count things, you group by things, you restrict
        // a measure by what is being measured. So the slot's preferred kinds survive.
        //
        // A PREFERENCE, deliberately: it removes the dis-preferred kinds only when at least one
        // preferred candidate remains. It never empties the band, never promotes anything from
        // below it, and never touches an `M:` row — a data value is a different species, and the
        // question "which OBJECT does this word name" is not asked of it.
        val admitted1 = slotPreference(admitted0, slot, kinds, rejected)

        // MH T3 — the reachability collapse (contracts §7.3, ✅MH-D3 the equivalent is CARRIED).
        //
        // The slot said which KIND the sentence wants. This asks the harder question: do the two
        // readings select the SAME ROWS of the fact this clause is about? Both halves are
        // decidable from declarations alone — `def relation` gives the reach, `cardinality.to`
        // gives whether it can be missing — so the answer is checked rather than assumed.
        //
        // It runs over the PRE-slot set (`admitted0`), which is what lets it re-admit a reading
        // the slot dropped (architecture A8): under a nullable reach the two readings genuinely
        // differ, and the honest verdict is the question, even though the slot had already
        // picked. It is also what lets it VETO the slot — the E6 case, where the slot prefers
        // the only measure-ish candidate and that candidate is the wrong fact.
        val (admitted2, equivalents) = reachabilityCollapse(admitted0, admitted1, slot, kinds, owners, reach, rejected)

        // MH tier M — member governance (contracts §7.5 M3, design §2a).
        //
        // Everything above is about `V:` identities: which OBJECT a word names. This is the other
        // homonymy — one VALUE held by several attributes (`TN` under three `state` columns) —
        // and it is a SAME-kind tie no object rule can see. The evidence the sentence offers is
        // the governor, and the model says which owners that governor can reach.
        val admitted = memberGovernance(admitted2, slot, kinds, owners, reach, memberOwners, rejected)

        return if (admitted.size > 1) {
            Ambiguous(admitted, rejected)
        } else {
            Bind(admitted.single(), admitted, rejected, equivalents)
        }
    }

    /**
     * MH T3 — decide a {dimension, fact} tie against the model's declared relations.
     *
     * Let **D** be a `V:` identity of kind `entity`, **F** a `V:` identity of kind
     * `entity_with_measures` or `measure`, `f` the FACT of F (itself, or its owner when it is a
     * measure), and **H** each fact in [SlotHint.headRefs] — the fact the clause is actually
     * about. Four rules, evaluated for every (D, F) pair and every H (contracts §7.3):
     *
     *  1. `H ∉ reach(D)` and `H ≠ f` — neither reading is about this clause; say nothing.
     *  2. `H == f` and the reach is MANDATORY — every row of H carries a D, so *"restrict H to
     *     the channel"* and *"join H to the dimension"* are the same rows. Collapse to **D** (the
     *     more informative object: group-by and member filters stay possible) and record F as an
     *     equivalent reading.
     *  3. `H ∈ reach(D)` and `H ≠ f` — the channel term is pinned to a DIFFERENT fact than the
     *     one being measured, so the dimension is the only reading that fits. Collapse to **D**.
     *     This is E6, and it is the rule that stops the slot rule mis-binding "vratky z prodejen".
     *  4. `H == f` but the reach is NULLABLE — the join drops H rows with no D while the channel
     *     restriction keeps them. The readings DIFFER: force both in, so the verdict is the
     *     question, and let the door say why.
     *
     * Rule 4 dominates 2 and 3 for the same pair — an estate that declares both a mandatory and a
     * nullable relation between one pair is contradicting itself, and refuse-over-guess decides.
     * With no [SlotHint.headRefs] nothing fires at all: a bare word has no clause head, and the
     * single-word regression must keep asking (design.md §4).
     *
     * ⚠ The equivalence is E-R level. Two readings can be declared-equal and still differ on
     * dirty data — an orphan FK no constraint enforced — which is exactly why
     * [EquivalentReading] says *equal by declaration* and nothing stronger.
     */
    private fun reachabilityCollapse(
        admitted0: List<ClassedMatch>,
        admitted1: List<ClassedMatch>,
        slot: SlotHint,
        kinds: Map<String, String>,
        owners: Map<String, String>,
        reach: Map<String, List<Reach>>,
        rejected: MutableList<ClassedMatch>,
    ): Pair<List<ClassedMatch>, List<EquivalentReading>> {
        if (slot.headRefs.isEmpty() || reach.isEmpty()) return admitted1 to emptyList()

        val objects = admitted0.filter { isModelObject(it) }
        val dimensions = objects.filter { kindOf(it, kinds) == KIND_ENTITY }
        val facts = objects.filter { kindOf(it, kinds) in setOf(KIND_ENTITY_WITH_MEASURES, KIND_MEASURE) }
        if (dimensions.isEmpty() || facts.isEmpty()) return admitted1 to emptyList()

        val drop = LinkedHashSet<ClassedMatch>()
        val admit = LinkedHashSet<ClassedMatch>()
        val equivalents = LinkedHashSet<EquivalentReading>()

        for (d in dimensions) {
            val reachOf = reach[d.match.targetRef].orEmpty()
            for (f in facts) {
                val factRef =
                    if (kindOf(f, kinds) == KIND_MEASURE) {
                        owners[f.match.targetRef].orEmpty().ifBlank { f.match.targetRef }
                    } else {
                        f.match.targetRef
                    }
                // Rule 4's domination is PER PAIR (review-087 F5, contracts §7.3): a nullable
                // reach says THIS dimension and THIS fact select different rows, which is no
                // reason to discard what another pair proved about a third object. A global flag
                // silently dropped the mandatory pair's `reach-equal` disclosure.
                val pairDrop = LinkedHashSet<ClassedMatch>()
                val pairEquivalents = LinkedHashSet<EquivalentReading>()
                var differ = false

                for (h in slot.headRefs) {
                    val reachedH = reachOf.filter { it.factRef == h }
                    when {
                        // 4 — a nullable reach anywhere in THIS pair's declarations wins.
                        h == factRef && reachedH.any { !it.mandatory } -> differ = true
                        // 2 — declared-equal on this model.
                        h == factRef && reachedH.any { it.mandatory } -> {
                            pairDrop += f
                            pairEquivalents += EquivalentReading(f.match.targetRef, RULE_REACH_EQUAL)
                        }
                        // 3 — the channel's fact is not the fact being measured.
                        reachedH.isNotEmpty() -> pairDrop += f
                        // 1 — neither reading is about this clause.
                        else -> Unit
                    }
                }

                if (differ) {
                    // Both readings stay in, and nothing about THIS pair is claimed equal — a
                    // pair whose readings were just shown to differ must not also be disclosed
                    // as equivalent.
                    admit += d
                    admit += f
                } else if (pairDrop.isNotEmpty()) {
                    admit += d
                    drop += pairDrop
                    equivalents += pairEquivalents
                }
            }
        }

        if (admit.isEmpty() && drop.isEmpty()) return admitted1 to emptyList()

        // A fact a rule-4 pair re-admitted is never dropped on another pair's account: the
        // refusal is the stronger claim, so it wins the intersection.
        drop.removeAll(admit)

        val readmitted = admit.filter { it !in admitted1 && it !in drop }
        rejected.removeAll(readmitted.toSet())
        val result = (admitted1 + readmitted).filter { it !in drop }
        // Never empty the band: a rule that would leave nothing to bind declines instead, the
        // same way the containment collapse does (review-084 F3).
        if (result.isEmpty()) return admitted1 to emptyList()
        rejected += drop.filter { it in admitted1 || it in readmitted }.filter { it !in rejected }
        return result to equivalents.toList()
    }

    /**
     * MH tier M (M3) — decide a MEMBER-vs-MEMBER tie by the value's GOVERNOR.
     *
     * `TN` is a member of `store.state`, of `customer_address.state` and of `warehouse.state`.
     * Three `M:` rows, one label, one span, no target ref: nothing the slot rule or the
     * reachability rule reads even applies, because those ask which OBJECT a word names and this
     * word names a data row. What the sentence adds is the noun the value hangs off —
     * *"**stores** in TN"*, *"**customers** in TN"* — and what the MODEL adds is which entities
     * that noun can reach.
     *
     * So, with **G** the governor ([SlotHint.governorRef]) and **E(m)** the ENTITY owning the
     * attribute whose vocabulary produced m:
     *
     *  1. `E(m) == G` — the governor owns the member outright (`stores in TN`).
     *  2. `G ∈ reach(E(m))` — the governor is declared to relate to the owning entity in one hop
     *     (`customers in TN`: `customer_address` declares `Reach(customer)`).
     *  3. `E(m) == owners[G]` — the governor is itself an attribute or measure of the owning
     *     entity, so it names the same table at a different granularity.
     *
     * Survivors bind if there is exactly one, ask if there are several (the governor narrowed but
     * did not decide), and — crucially — **an empty survivor set is a NO-OP**: a governor that
     * reaches none of the owners has said nothing, and silence is not an answer (L-33, and the
     * same posture the containment collapse takes). `mandatory` is deliberately not consulted: a
     * nullable relation still names the owner the user meant.
     *
     * ✅ E(m) is `owner(m)` since MV-T3 (member-vocabulary contracts §5.3, A-MH-2): the producers
     * hand [memberOwners] as `memberEntityByCategory` — category → the ENTITY — the same map
     * `GateSpans` names a member's entity with. P3·S1·T1 had measured `owner(m)` as the
     * column-level attribute ref, which is why this used to read `owners[owner(m)]`; the extra
     * `owners` step below is kept so a caller passing the older category → attribute map
     * (`refByCategory`) still gets the entity, and it is a no-op on the entity map.
     *
     * Refuse-over-guess is what keeps a FACT governor out: *"sales in TN"* is the store's state or
     * the customer's, and the fact reaches both. Only `entity`/`attribute` governors are admitted,
     * and [SlotHints] is the other half of that guard — it never stamps a fact as a governor.
     */
    private fun memberGovernance(
        admitted: List<ClassedMatch>,
        slot: SlotHint,
        kinds: Map<String, String>,
        owners: Map<String, String>,
        reach: Map<String, List<Reach>>,
        memberOwners: Map<String, String>,
        rejected: MutableList<ClassedMatch>,
    ): List<ClassedMatch> {
        val governor = slot.governorRef
        if (governor.isBlank()) return admitted
        if (kinds[governor] !in GOVERNING_KINDS) return admitted

        val members = admitted.filter { !isModelObject(it) }
        if (members.size < 2) return admitted

        /** The ENTITY behind a member's owning category — see the ✅ above. */
        fun entityOf(c: ClassedMatch): String {
            val owner = memberOwners[c.match.category] ?: c.match.category
            return owners[owner]?.takeIf { it.isNotBlank() } ?: owner
        }

        // Two owning entities at minimum, or there is nothing to break — one owner reached twice
        // is the `distinctBy` case above, not this one.
        if (members.map(::entityOf).distinct().size < 2) return admitted

        val governorEntity = owners[governor]?.takeIf { it.isNotBlank() } ?: governor
        val keep =
            members.filter { m ->
                val e = entityOf(m)
                e == governor || e == governorEntity || reach[e].orEmpty().any { it.factRef == governor }
            }
        if (keep.isEmpty() || keep.size == members.size) return admitted

        val dropped = members - keep.toSet()
        rejected += dropped
        return admitted - dropped.toSet()
    }

    /** MH tier M — the kinds a governor may have; a fact never selects among member owners. */
    private val GOVERNING_KINDS = setOf(KIND_ENTITY, KIND_ATTRIBUTE)

    /**
     * MH T2 — keep the kinds the slot prefers, when the band holds more than one kind.
     *
     * Returns [admitted0] unchanged when there is nothing to decide: fewer than two distinct
     * kinds among the `V:` rows (so there is no cross-kind homonymy), a slot with no preference,
     * or a preference nothing matches. That last case is the one worth stating: an unmatched
     * preference leaves a genuine tie as a tie rather than collapsing it to whichever row
     * happened to be first.
     */
    private fun slotPreference(
        admitted0: List<ClassedMatch>,
        slot: SlotHint,
        kinds: Map<String, String>,
        rejected: MutableList<ClassedMatch>,
    ): List<ClassedMatch> {
        val objects = admitted0.filter { isModelObject(it) }
        if (objects.map { kindOf(it, kinds) }.distinct().size < 2) return admitted0

        val preferred = preferredKinds(slot)
        if (preferred.isEmpty()) return admitted0
        val keep = objects.filter { kindOf(it, kinds) in preferred }
        if (keep.isEmpty()) return admitted0

        // ⛔ SILENCE IS NOT A SPECIES (review-087 F1). Only a row whose kind is KNOWN and
        // dis-preferred is dropped. A blank kind means the archive declares nothing about that
        // ref — every md-owned ref is kind-less by construction, since `MentionKinds` is derived
        // for `Entity`/`DbTable`/`Attribute`/`DbColumn` and `ttr-metadata`'s `Model` has no md
        // schema — and dropping it would make a PARTIALLY described registry bind where a fully
        // undeclared one asks. A registry that says less must degrade toward asking, never
        // toward binding.
        val dropped = objects.filter { kindOf(it, kinds).isNotBlank() && kindOf(it, kinds) !in preferred }
        if (dropped.isEmpty()) return admitted0
        rejected += dropped
        return admitted0 - dropped.toSet()
    }

    /**
     * What each slot asks for (contracts §7.3). Empty = no preference, and the band is untouched.
     *
     * `FILTER` splits on whether the clause head is measure-capable: *"tržby z prodejen"* under a
     * measure head is naming what is being measured (the channel/fact reading), but *"vratky z
     * prodejen"* under a plain entity head is not — there the fact reading would be the WRONG
     * fact, so nothing is preferred and the reachability rule decides instead.
     */
    private fun preferredKinds(slot: SlotHint): Set<String> =
        when (slot.slot) {
            Slot.COUNT_HEAD -> setOf(KIND_ENTITY)
            Slot.GROUP_BY -> setOf(KIND_ENTITY, KIND_ATTRIBUTE)
            Slot.GOVERNED_VALUE -> setOf(KIND_ENTITY)
            Slot.FILTER -> if (slot.headMeasureCapable) setOf(KIND_ENTITY_WITH_MEASURES, KIND_MEASURE) else emptySet()
            Slot.COORD_WITH -> slot.coordSiblingKinds
            Slot.SUBJECT, Slot.NONE -> emptySet()
        }

    private fun kindOf(
        c: ClassedMatch,
        kinds: Map<String, String>,
    ): String = kinds[c.match.targetRef] ?: ""

    private const val KIND_ENTITY = "entity"
    private const val KIND_ATTRIBUTE = "attribute"
    private const val KIND_MEASURE = "measure"
    private const val KIND_ENTITY_WITH_MEASURES = "entity_with_measures"

    /** The only rule name MH produces; the wire vocabulary is open (contracts §5). */
    private const val RULE_REACH_EQUAL = "reach-equal"

    /**
     * A `V:` identity — a declared model object rather than a data row.
     *
     * The containment collapse touches these and only these: `M:` rows are data values, and one
     * value being stored in a table the other names is not the same relation at all.
     */
    private fun isModelObject(c: ClassedMatch): Boolean = c.match.source != SourceTag.MEMBER

    /**
     * What makes two candidates the same THING: a member is its data PK, anything else is its
     * declared target ref. Two rows that agree here are one answer reached twice, not a tie.
     */
    fun identityKey(m: FuzzyMatch): String =
        if (m.source == SourceTag.MEMBER) "M:${m.candidateId}" else "V:${m.targetRef}"
}

/**
 * MH-D3 — a reading the Binder proved EQUAL to a binding by the model's declared relations.
 *
 * The Kotlin twin of `resolver.v1.EquivalentReading`. It says exactly one thing, and the wording
 * matters because it is what a governed answer would show a user: *equal by DECLARATION*. Two
 * readings can be declared-equal and still differ on dirty data (an orphan FK a constraint never
 * enforced); this claim is about what the model says, not about what the rows do.
 *
 * @property ref the suppressed reading, e.g. `er.entity.store_sales`
 * @property rule which rule proved it — `reach-equal` today; an open vocabulary, so a later tier
 *   (an MD lattice proof, say) can name itself without a wire change.
 */
data class EquivalentReading(
    val ref: String,
    val rule: String,
)
