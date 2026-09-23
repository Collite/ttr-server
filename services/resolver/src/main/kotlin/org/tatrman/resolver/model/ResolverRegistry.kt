// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.model

/**
 * The resolver-side registry (RG-P5, RS-24) — the plain-Kotlin view of the
 * declared vocabulary the pipeline gates against. Built from either the snapshot
 * (default, S2) or a caller-supplied `Registry` proto override (which wins for
 * that request). Kept as an internal model so span proposal / gateSpans never
 * touch the wire types directly.
 */
data class ResolverRegistry(
    val entityTypes: List<ResolverEntityType>,
    val locales: List<String>,
    val thresholds: ResolverThresholds,
    val snapshotHash: String,
)

/**
 * One declared entity type. [anchors] are the declared anchor words (the lexicon
 * `term`/`entityAliases` for er/db/md kinds) that Q-20's anchored span proposal
 * ties content subtrees to; [categories] are the fuzzy categories a span gated to
 * this type is matched against (one BatchMatch slot per proposed span).
 *
 * [objectKind] is what the ref IS in the model — `measure` | `attribute` | `entity` |
 * `entity_with_measures` (MS contracts §5) or `operator` — and frame-role derivation reads it
 * before it reads any syntax (RV-P2.1, Q-15 rule R2: "a measure IS the measure", which is what
 * keeps *podle tržby* an ORDER-BY instead of a GROUP-BY).
 *
 * Both channels now supply it: the per-request `Registry` override as always, and — since
 * MS-P2·S2 — the snapshot channel, from the compiled lexicon archive's `targets` map, which
 * `MentionKinds` filled at compile time from the E-R model's declared mention facet. Blank ⇒ R2
 * does not fire, which is the correct reading for an estate that declared nothing.
 *
 * `dimension` has left this list: it was never produced by anything and MS does not produce it.
 *
 * ⛔ Neither channel may derive the kind from the ref STRING. One rule decides, upstream.
 */
data class ResolverEntityType(
    val ref: String,
    val categories: List<String>,
    val anchors: List<String>,
    val objectKind: String = "",
    /**
     * MS — the declaring entity/table's ref for a member (`measure` / `attribute`); `""` for an
     * owner, and for any ref the archive declares nothing about. Spelled exactly as a [ref] is,
     * because MS-P3's declared-containment collapse looks the owner up in this same set.
     */
    val ownerRef: String = "",
    /**
     * MH — the facts with a declared relation TO this ref, with the to-side lower bound.
     *
     * Empty for members, for entities nothing relates to, and for every archive built before
     * schema `ttr-lexicon-compiled/v3` — which is what makes the Binder's reachability rule a
     * no-op on a pre-MH estate rather than a behaviour change nobody asked for.
     *
     * ⛔ Declared structure, never derived: it comes from the model's `def relation`s, projected
     * at COMPILE time. Guessing a join from two ref strings that share a prefix would be a second
     * rule, and the two would eventually disagree — the same argument that keeps `objectKind`
     * out of the ref string.
     */
    val reachedFrom: List<Reach> = emptyList(),
    /**
     * LP contracts §2.1 — the model's MENTION facet: which attribute carries this entity under
     * the aspect a quoted literal is about. `semantics { name: · code: }`, as full attribute refs
     * (`er.entity.store.name`), with [codeFormat] copied from the code attribute so the
     * code-shape test uses the MODEL's pattern rather than one this service invented.
     *
     * ⚑ Empty on the snapshot channel today, and that is a channel gap, not an estate's silence:
     * the archive's `targets` map (`TargetFacts`, ttr-lexicon 0.13.3) carries `objectKind`,
     * `ownerRef` and `reachedFrom` and has no field for name/code. The per-request `Registry`
     * override supplies them now; lighting up the archive is a tatrman change riding the LP-P2a
     * bundle cut (LP control room ⚑LPQ-5). Until then a quoted literal on an archive-fed estate
     * lands HEADLESS — G3, the documented no-head outcome — rather than attributed by guess.
     *
     * ⛔ Never derived here. "The entity's name column" is a DECLARED fact, and the one rule that
     * decides it lives in the model, exactly as [objectKind]'s does.
     */
    val nameRef: String = "",
    val codeRef: String = "",
    val codeFormat: String = "",
)

/**
 * MH — one declared relation, seen from the object it points AT: the ref of the object that
 * relates to it, and whether every row of that object carries this one (`cardinality.to`'s lower
 * bound ≥ 1).
 *
 * [factRef] is named for the case the rules act on, but it is *the object with a declared relation
 * TO this ref* and nothing narrower — on a real estate it is often another dimension
 * (`er.entity.customer_address` is reached from `er.entity.customer`). Harmless, because the
 * reachability rule only pairs a dimension with a measure-CAPABLE candidate, but a reader of a raw
 * archive should not have to work that out.
 *
 * [mandatory] is the load-bearing half. "Sales *of the Stores channel*" and "sales *joined to the
 * store dimension*" are the same rows only when no fact row can be missing its store; a nullable
 * key makes the two readings differ, and the Binder must then refuse rather than pick.
 */
data class Reach(
    val factRef: String,
    val mandatory: Boolean,
)

/**
 * MS-P3·S2 — the declared containment the Binder collapses on: member ref → its owner's ref.
 *
 * Built here and nowhere else so the three producers that gate (`GateSpans`, the lookup rounds,
 * the re-gate) cannot drift into three spellings of the same question. Refs with no declared owner
 * are absent rather than mapped to `""`, so a lookup answers "not owned" by missing.
 *
 * ⛔ A derivation, not a parse: the pairs come from [ResolverEntityType.ownerRef], which the
 * archive carries because the model declared it. Splitting a ref on dots to guess a parent would
 * be a second rule, and the two would eventually disagree.
 */
fun List<ResolverEntityType>.ownersByRef(): Map<String, String> =
    filter { it.ownerRef.isNotBlank() }.associate { it.ref to it.ownerRef }

/**
 * MH — ref → its declared reach, for the Binder's reachability rule.
 *
 * Built here beside [ownersByRef] for the same reason: the three producers that gate
 * (`GateSpans`, the lookup rounds, the re-gate) must not drift into three spellings of one
 * question. A ref with no relations is ABSENT rather than mapped to an empty list, so a lookup
 * answers "nothing declared" by missing — the shape the rule already reads `owners` with.
 */
fun List<ResolverEntityType>.reachByRef(): Map<String, List<Reach>> =
    filter { it.reachedFrom.isNotEmpty() }.associate { it.ref to it.reachedFrom }

/**
 * MH — ref → its mention kind, for the Binder's slot rule.
 *
 * The same map `LatticeAssembler` builds for frame roles, hoisted here so the gate can read kinds
 * too. Blank kinds are omitted: `""` means "the archive declared nothing", and a rule that
 * compared against it would be treating silence as a species.
 */

fun List<ResolverEntityType>.kindsByRef(): Map<String, String> =
    filter { it.objectKind.isNotBlank() }.associate { it.ref to it.objectKind }

/**
 * MH tier M — fuzzy category → the ref whose vocabulary that category is.
 *
 * This IS `owner(m)` for a member row: the same question `GateSpans.entityRefOf` answers, hoisted
 * here beside the other three so the Binder can ask it without a second scan of the registry and
 * without a second spelling of the rule. Built once per gate call like `owners`/`kinds`/`reach`;
 * a category the registry does not declare is ABSENT, and the caller reads that as "the category
 * names itself", which is exactly `entityRefOf`'s `?: m.category` fallback.
 */
fun List<ResolverEntityType>.refByCategory(): Map<String, String> =
    flatMap { et -> et.categories.map { it to et.ref } }.toMap()

/**
 * Gating thresholds — ported from the live ENTITIES_ONLY config
 * (`ResolverGraph.kt:38-48`). Provenance for the numbers is that file, except [strong],
 * which RV-P2.2 adds and whose provenance is recorded on the property.
 *
 * The three the RV-P2.2 gate reads, and what each one decides:
 *
 *  - [bind] — the MATCHER's floor. Below it a row is not evidence of anything and never
 *    enters the gate. Unchanged from RG.
 *  - [strong] — the CLASS floor (RV-14). Above it an unvouched similarity is
 *    `*_FUZZY_STRONG`; below it, WEAK — and WEAK never binds. See the property.
 *  - [ambiguityGap] — the TIE BAND, now applied *within* one evidence class rather than
 *    across the whole contender field. Two identities inside it are a G2 the gate refuses
 *    to guess between; outside it the scores are comparable and the higher one wins,
 *    because same-class scores are the one comparison RV-14 permits.
 */
data class ResolverThresholds(
    val bind: Double,
    val ambiguityGap: Double,
    val exact: Double,
    val maxOptions: Int,
    /**
     * The RV-14 class floor: how similar an **unvouched** hit must be to count as evidence.
     *
     * Unvouched means the data layer — a member row matched by surface similarity alone, with no
     * authored method behind it. Where the estate DID author a method, the method is the vouching
     * and this floor is not applied ([EvidenceClasses] documents why; short Czech anchor words are
     * where applying it would bite).
     *
     * ⚑ **Not ruled by RV-14 — the only default in the gate without a decision behind it.** RV-14
     * ordains the classes and that WEAK never binds; it names no number, and the effort has no
     * calibration corpus (RV-14 rejected weighted sums for exactly that reason). 0.70 is the
     * tightest value consistent with the two observations on record, and both are greppable:
     *
     *  - `issues.md` §1, the garbage this list exists to kill — `501001` reaching *středisko*
     *    rows at **0.667** and **0.500**. Both must land in WEAK.
     *  - `GateSpansTest`'s member-ambiguity fixture — `DF` reaching `DF ADNAK` **0.72** and
     *    `DF BELUS` **0.70**, a real partial-token pair that must stay a clarification.
     *
     * The gap between 0.667 and 0.70 is what the number is fitted to, and fitting a threshold to
     * two fixtures is worth saying out loud rather than dressing up as a ruling. An estate raises
     * it via `resolver.threshold-strong`; a calibrated value is the RV-14 γ "named future".
     */
    val strong: Double = LIVE_STRONG,
) {
    companion object {
        /** See [ResolverThresholds.strong] — fitted, not ruled. */
        const val LIVE_STRONG: Double = 0.70

        /** The live ENTITIES_ONLY defaults (also mirrored in `application.conf`). */
        val LIVE = ResolverThresholds(bind = 0.5, ambiguityGap = 0.05, exact = 0.9999, maxOptions = 20)
    }
}
