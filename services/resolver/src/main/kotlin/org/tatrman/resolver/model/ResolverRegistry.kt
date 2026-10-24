// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.model

import org.tatrman.text.Normalization
import org.tatrman.ttr.lexicon.TargetFacts

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
    /**
     * CN-P5 S5.1 — the content id of the model the lexicon was compiled from (the archive header's
     * `modelSnapshotHash`; several comma-joined for a layer of archives); the cn doors serve a model only when its own
     * content id is one of them. `""` without an archive, or from a per-request override.
     */
    val modelSnapshotHash: String = "",
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
    /**
     * CN-P4 (cn contracts §5, v6) — a concept's facts, verbatim from the archive's `targets[ref]`:
     * its stereotype (`kind` · `role` · `event` · `quality` · `phase`), its ER landing (an entity, or
     * the attribute for a value kind or a quality; `""` when it has none), how that landing is reached
     * (`direct` | `md-hop`) and its `of:` (`cn.<Name>`). `""` on every element and on every pre-v6
     * archive. A concept's [objectKind] is [KIND_CONCEPT], and its [ownerRef] (the entity around the
     * landing) is NOT containment: [ownersByRef] leaves it out.
     *
     * ⛔ Read, never derived: which concept grounds where is the model's binding, projected at compile time.
     */
    val stereotype: String = "",
    val erTarget: String = "",
    val via: String = "",
    val ofRef: String = "",
    /** CN-P4 (A-CN-4) — the concepts this er or md element lifts to, from the archive's reverse index. */
    val liftsTo: List<Lift> = emptyList(),
    /**
     * CN-P4 (cn contracts §1.5 steps 3–4) — the authored sense of this type's terms, by term as the
     * archive spells it ([anchors]' spelling): `default: true` and `sense_priority:`. Only terms that
     * state one are here, so an estate with none carries an empty map.
     */
    val senses: Map<String, TermSense> = emptyMap(),
)

/** CN-P4 (A-CN-4) — one entry of the reverse index: a concept an element lifts to, and how it lands. */
data class Lift(
    val qname: String,
    val via: String,
)

/** CN-P4 (§1.5) — a term's authored sense: the default sense of its form, and its priority (higher wins). */
data class TermSense(
    val default: Boolean = false,
    val priority: Double? = null,
)

/** CN-P4 — the [ResolverEntityType.objectKind] of a concept. Never a mention kind. */
const val KIND_CONCEPT = TargetFacts.CONCEPT

/** CN-P4 — the stereotype the ordering and the lift treat apart (L17-2, L17-3). */
const val STEREOTYPE_PHASE = "phase"

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
 *
 * CN-P4 — a concept is left out. Its `ownerRef` is the entity around its ER landing, which a concept
 * is not CONTAINED in: read as containment, the Binder would collapse `cn.Channel` onto
 * `channel_sales` as if the concept were its attribute, where the ordering asks (§1.5 row 7). A
 * concept governs as its landing through [governingRefByRef] instead.
 */
fun List<ResolverEntityType>.ownersByRef(): Map<String, String> =
    filter { it.ownerRef.isNotBlank() && it.objectKind != KIND_CONCEPT }.associate { it.ref to it.ownerRef }

/**
 * CN-P4 (cn contracts §5, A-CN-6) — concept → its ER landing (`erTarget`): **a concept governs as its
 * ER landing**. The one normalization member gating under a concept needs, read where a governor is
 * named: the governed scope ([valueCategoriesByRef]), and the refs `SpanProposal` gates a governed value
 * to — which the governor stamp (`SlotHints`), the Binder's tier-M rule (governor, its entity, the
 * reach test) and a dual reading's scope (`UniversalSeam`) read as given — and the anchors a named
 * member's reach is read against (`NamedMembers`). "zákazníci po splatnosti z Prahy" then gates
 * "Praha" as "zákazníci z Prahy" does, and "web" under `Channel` is looked up in
 * `channel_sales.channel` alone.
 *
 * A concept with no landing (ungrounded, or md-grounded with the hop missing) is ABSENT, so it
 * normalizes to nothing: its values resolve as open values, exactly as before P4.
 */
fun List<ResolverEntityType>.governingRefByRef(): Map<String, String> =
    filter { it.objectKind == KIND_CONCEPT && it.erTarget.isNotBlank() }.associate { it.ref to it.erTarget }

/**
 * CN-P4 (⚑R130-a, A-CN-72) — what a ref STANDS FOR as an object: a grounded concept's ER landing
 * ([governingRefByRef]), any other ref itself. A-CN-6 says a concept governs as its landing; the same holds
 * wherever the resolver asks what an object IS — its kind (a quality landing on a measure is a measure),
 * its entity type (a quoted literal's head facet), its slot facts, whether it owns values. Read at two points:
 * span proposal maps every candidate's gated refs through it, and the readers that start from a binding's ref
 * (the lattice's heads and kinds, the door's options) call it. The concept itself stays the binding's ref.
 */
fun List<ResolverEntityType>.standsFor(): (String) -> String {
    val governing = governingRefByRef()
    return if (governing.isEmpty()) { ref -> ref } else { ref -> governing[ref] ?: ref }
}

/**
 * CN-P4 (A-CN-72) — concept → the declared type of its ER landing, for a reader that needs the landing's own
 * facts (its kind, its owner) rather than its ref. A landing the registry does not list is ABSENT, so a reader
 * learns nothing about it — the "nothing declared" shape the other maps here use.
 */
fun List<ResolverEntityType>.landingTypeByRef(): Map<String, ResolverEntityType> {
    val governing = governingRefByRef()
    if (governing.isEmpty()) return emptyMap()
    val byRef = associateBy { it.ref }
    return governing.mapNotNull { (concept, landing) -> byRef[landing]?.let { concept to it } }.toMap()
}

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
 * A MEMBER row's vocabulary: the first declared type, in registry order, whose categories hold [category], else the
 * category itself (what a binding's `memberOf` names). One rule for `GateSpans` and `concept.search:v1`, so a door
 * lifts a member from the refs `resolve.bind:v1` lifts it from (review-134 F6).
 */
fun List<ResolverEntityType>.memberVocabularyOf(category: String): String =
    firstOrNull { category in it.categories }?.ref ?: category

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
 *
 * CN-P4 (A-CN-6, ⚑R130-c) — a concept's scope is its own categories first (its terms: a hypothesis naming
 * the concept must still reach its row), then its ER landing's ([governingRefByRef]): a value it governs is
 * a value of the landing. A scope is never empty — an empty category list is the matcher's unscoped ask — so
 * a type with no category of its own (a term-less concept or landing) falls back to its ref, the convention
 * every category here follows.
 */
fun List<ResolverEntityType>.valueCategoriesByRef(): Map<String, List<String>> {
    val members = membersOf()
    val categoriesByRef = associate { it.ref to it.categories }
    val governing = governingRefByRef()

    fun scopeOf(
        ref: String,
        own: List<String>,
    ): List<String> = (own + members[ref].orEmpty().flatMap { categoriesByRef[it].orEmpty() }).distinct()
    return associate { et ->
        val landing = governing[et.ref]
        val scope =
            if (landing == null) {
                scopeOf(et.ref, et.categories)
            } else {
                (et.categories + scopeOf(landing, categoriesByRef[landing].orEmpty())).distinct()
            }
        et.ref to scope.ifEmpty { listOf(et.ref) }
    }
}

/**
 * CN-P4 — the concept tier as the Binder's ordering (cn contracts §1.5) and the lift (A-CN-5/7) read
 * it, built from the registry once per gate call, beside the maps above. [NONE] on a registry with no
 * concept and no lift, which is every estate without a `model cn`: each rule reading it is then a
 * no-op.
 */
class ConceptFacts internal constructor(
    private val byRef: Map<String, ResolverEntityType>,
) {
    /** No concept, no lift and no authored sense — nothing for the ordering or the lift to read. */
    val isEmpty: Boolean get() = byRef.isEmpty()

    fun isConcept(ref: String): Boolean = byRef[ref]?.objectKind == KIND_CONCEPT

    fun stereotype(ref: String): String = byRef[ref]?.stereotype.orEmpty()

    fun isPhase(ref: String): Boolean = isConcept(ref) && stereotype(ref) == STEREOTYPE_PHASE

    fun erTarget(ref: String): String = byRef[ref]?.erTarget.orEmpty()

    fun via(ref: String): String = byRef[ref]?.via.orEmpty()

    fun liftsTo(ref: String): List<Lift> = byRef[ref]?.liftsTo.orEmpty()

    /** The `of:` chain above [ref], nearest first — what "a phase yields to its bearer" walks. Cycle-safe. */
    fun bearers(ref: String): List<String> {
        val out = LinkedHashSet<String>()
        var at = byRef[ref]?.ofRef.orEmpty()
        while (at.isNotBlank() && at != ref && out.add(at)) at = byRef[at]?.ofRef.orEmpty()
        return out.toList()
    }

    /** [form]'s authored sense under [ref], matched as the archive spells its terms (folded); null when none. */
    fun sense(
        ref: String,
        form: String,
    ): TermSense? {
        val senses = byRef[ref]?.senses ?: return null
        if (senses.isEmpty()) return null
        val folded = Normalization.fold(form)
        return senses[form] ?: senses.entries.firstOrNull { (term, _) -> Normalization.fold(term) == folded }?.value
    }

    /**
     * R-cn-1 — [concept] grounds to [element]: the element is the concept's ER landing, or a target the
     * concept is grounded on (the element lifts to it — an md target, or the hop's E-R image).
     */
    fun groundsTo(
        concept: String,
        element: String,
    ): Boolean =
        (erTarget(concept).isNotBlank() && erTarget(concept) == element) ||
            liftsTo(element).any { it.qname == concept }

    companion object {
        val NONE = ConceptFacts(emptyMap())
    }
}

/** CN-P4 — [ConceptFacts] over this registry: its concepts, its lifting elements and its authored senses. */
fun List<ResolverEntityType>.conceptFacts(): ConceptFacts {
    val relevant =
        filter { it.objectKind == KIND_CONCEPT || it.liftsTo.isNotEmpty() || it.senses.isNotEmpty() }
    return if (relevant.isEmpty()) ConceptFacts.NONE else ConceptFacts(relevant.associateBy { it.ref })
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
     *  - `GateSpansTest`'s member-ambiguity fixture — `QT` reaching `QT ORLAK` **0.72** and
     *    `QT BELUS` **0.70**, a real partial-token pair that must stay a clarification.
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
