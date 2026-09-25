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
     * ✅ **Both channels supply it since LP-P2b.** The per-request `Registry` override always
     * could; the snapshot channel does now, from the archive's `targets[ref]`
     * (`TargetFacts.nameRef/codeRef/codeFormat`, `ttr-lexicon-compiled/v4`). Before that the
     * archive had no field for name/code at all, so an archive-fed estate — every real one — left
     * a quoted literal HEADLESS (G3). That was ⚑LPQ-5, and it is closed.
     *
     * `""` is still the honest answer in two cases that are NOT gaps: an estate whose archive
     * predates v4, and a model that declares no `semantics { name: · code: }`. Both leave the
     * literal headless, which is the documented no-head outcome rather than a guess.
     *
     * ⛔ Never derived here. "The entity's name column" is a DECLARED fact, and the one rule that
     * decides it lives in the model, exactly as [objectKind]'s does.
     */
    val nameRef: String = "",
    val codeRef: String = "",
    val codeFormat: String = "",
    /**
     * MV (member-vocabulary contracts §5.2) — this ref is an INDEXED attribute (or column): its
     * values are a member vocabulary, registered and queried under this ref as its category, and
     * [ownerRef] is the entity whose population they are. [membersOf] turns the flag round into
     * what a governed lookup needs — "which vocabularies does this entity own?"
     *
     * Both channels supply it: the per-request override (`EntityType.member_vocabulary`) and the
     * snapshot channel, from the v5 archive's `targets[ref].memberVocabulary`, which lists every
     * indexed attribute whether or not it has a term — such a type carries no [anchors]. `false`
     * on an older archive, where [membersOf] is empty and a governed value is looked up exactly
     * as it was before MV.
     */
    val memberVocabulary: Boolean = false,
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
 * For a member row that is the vocabulary's ATTRIBUTE (MV §1: a category is exactly one attribute
 * ref), which is what `ClarificationOption.memberOf` names. The ENTITY behind it — `owner(m)` since
 * MV — is [memberEntityByCategory], built from this. A category the registry does not declare is
 * ABSENT, and the caller reads that as "the category names itself".
 *
 * Injective on a projected registry (MV §5.2, pinned by `MvRegistryTest`): every archive entry is
 * gated by its own ref, so no two categories share a ref and no category has two.
 */
fun List<ResolverEntityType>.refByCategory(): Map<String, String> =
    flatMap { et -> et.categories.map { it to et.ref } }.toMap()

/**
 * MV (member-vocabulary contracts §5.2) — entity ref → the member vocabularies it owns (refs, in
 * ref order). An entity with none is ABSENT, the shape the other maps here use for "nothing
 * declared", and every entity on a pre-v5 archive is absent — which is what makes the governed
 * lookup behave exactly as it did before MV there (§6).
 *
 * ⛔ Built from the declared [ResolverEntityType.memberVocabulary] + [ResolverEntityType.ownerRef]
 * pair, never by matching ref prefixes: `er.entity.store.state` looks like a member of
 * `er.entity.store`, and a rule that relied on how it looks would be a second rule.
 */
fun List<ResolverEntityType>.membersOf(): Map<String, List<String>> =
    filter { it.memberVocabulary && it.ownerRef.isNotBlank() }
        .groupBy({ it.ownerRef }, { it.ref })
        .mapValues { (_, refs) -> refs.distinct().sorted() }

/**
 * MV (member-vocabulary contracts §5.3) — the ONE scope rule for a value scoped to an object:
 * ref → the categories a value it governs is looked up in = the object's own categories, then
 * those of every member vocabulary it owns ([membersOf]).
 *
 * A governed value is a value OF the governor, and an entity's values live in its attributes'
 * vocabularies, never under the entity's own ref — lex-matcher registers each vocabulary under its
 * attribute ref and matches categories by exact key, with no hierarchy (MH contracts §7.5 ⚑). So
 * before MV an anchor's own categories could not reach its entity's values at all.
 *
 * Three producers ask this question and must not drift into three spellings of it: `SpanProposal`
 * (the governed block of path (a)), `RoundPlanner` (the anchored tier re-asks a value inside what
 * its anchor bound) and `ReGate` (a hypothesis scoped by a ref or by the value's anchor). The own
 * categories come FIRST, so a registry with no member vocabularies yields byte-identical scopes.
 */
fun List<ResolverEntityType>.valueCategoriesByRef(): Map<String, List<String>> {
    val members = membersOf()
    val categoriesByRef = associate { it.ref to it.categories }
    return associate { et ->
        et.ref to (et.categories + members[et.ref].orEmpty().flatMap { categoriesByRef[it].orEmpty() }).distinct()
    }
}

/**
 * MV (member-vocabulary contracts §5.3) — fuzzy category → the ENTITY a member row of that
 * category belongs to: `owner(m)`. The category's ref ([refByCategory]), then that ref's declared
 * owner ([ownersByRef]) — `er.entity.store.state` ⇒ `er.entity.store`.
 *
 * One map, read by both places that ask: `GateSpans` (a member binding's and option's
 * `entity_type_ref`) and the Binder's tier-M governance. MH contracts §7.5 had to spell this as
 * `entityOf(owner(m))` because `owner(m)` came out column-level; with MV it is simply the rule.
 * Read for MEMBER rows only — a declared row names its object through `target_ref`.
 */
fun List<ResolverEntityType>.memberEntityByCategory(): Map<String, String> {
    val owners = ownersByRef()
    return refByCategory().mapValues { (_, ref) -> owners[ref]?.takeIf { it.isNotBlank() } ?: ref }
}

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
