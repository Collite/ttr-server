// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

/**
 * The outcome of gating the proposed spans against the vocabulary (RG-P5.S1.T4).
 * A resolve is EITHER fully bound OR awaiting clarification (the proto `outcome`
 * oneof) — instance ambiguity anywhere forces [Clarify] (refuse-over-guess, RS-26).
 */
sealed interface GateOutcome {
    /**
     * RV-P2.1 — what the gate saw, span by span, whichever way the outcome went. The
     * `Bound`/`Clarify` split above answers "what does the door return"; this answers
     * "what does the lattice say", and the two are different questions: a span that
     * matched NOTHING contributes to neither a binding nor an option, and is precisely
     * the G1 the lattice exists to record.
     */
    val gated: List<GatedSpan>
}

/** All spans bound with no unresolved instance ambiguity. */
data class Bound(
    val bindings: List<DomainBinding>,
    val confidence: Double,
    override val gated: List<GatedSpan> = emptyList(),
) : GateOutcome

/** At least one span was ambiguous among distinct instances — ask, don't guess. */
data class Clarify(
    val options: List<ClarificationOption>,
    override val gated: List<GatedSpan> = emptyList(),
) : GateOutcome

/**
 * One proposed span and every candidate the gate ADMITTED, in score order, each carrying the
 * RV-14 evidence class the gate derived for it. [ambiguous] is the refuse-over-guess verdict for
 * THIS span: two or more distinct identities in the top class inside the tie band, which the door
 * renders as a clarification and the lattice as a mention with several bindings plus a G2 gap.
 *
 * RV-P2.2 changed both halves of [contenders]: it is no longer "everything above the bind floor"
 * but "everything in the winning class", and it carries the class rather than leaving the lattice
 * to re-derive one. WEAK candidates are therefore absent by construction — which is what lets a
 * span that matched only garbage still be a G1/G3 rather than a mention with bindings nobody
 * trusts. What the gate refused is not lost: it rides [Binder.Verdict.rejected] into the round's
 * log (P2.3.T5).
 */
data class GatedSpan(
    val candidate: DomainSpanCandidate,
    val contenders: List<Binder.ClassedMatch>,
    val ambiguous: Boolean,
    /**
     * MH-D3 — readings the Binder proved equal to the WINNER of this span and suppressed. Empty
     * unless the reachability rule fired, and empty on every ambiguous span by construction: a
     * span that is asking proved nothing equal.
     */
    val equivalents: List<EquivalentReading> = emptyList(),
)

/**
 * One resolved domain binding (internal model; T5 maps it to the `EntityBinding`
 * proto). MEMBER hits carry [resolvedId] (the member's value since A-MV-15 → instance-determinate)
 * and [memberOf] (the attribute whose vocabulary it is);
 * VOCABULARY hits carry [targetRef] (a declared lexicon target). [siblingRefs] is
 * the Q-20 sibling-column expansion — a value match on a KOD/NAZEV column also
 * points at its sibling column (a catalog lookup, not inference).
 */
data class DomainBinding(
    val span: DomainSpanCandidate,
    val entityTypeRef: String,
    val rawText: String,
    val vocabularySource: String, // "MEMBER" | "VOCABULARY"
    val resolvedId: String?,
    val resolvedLabel: String,
    val targetRef: String?,
    val siblingRefs: List<String>,
    val score: Double,
    val algorithm: String,
    val snapshotHash: String,
    /**
     * MV (review-104 F6) — for a MEMBER binding, the attribute whose member vocabulary [resolvedId]
     * is a value of; blank otherwise. [entityTypeRef] names the ENTITY since MV-T3, so without this
     * `TN` as a billing state and as a shipping state of one customer were the same binding.
     */
    val memberOf: String = "",
)

/**
 * One clarification option (internal model; T5 maps it to the `Option` proto).
 * Carries [entityTypeRef] so a MEMBER pin can reconstruct its Domain on resume
 * (RG-P6 review F), and the [spanStart]/[spanEnd]/[spanText] of the surface span it
 * disambiguates so a multi-span clarification is attributable (RG-P6 review M).
 */
data class ClarificationOption(
    val id: String,
    val label: String,
    val resolvedId: String?,
    val targetRef: String?,
    val entityTypeRef: String,
    val spanStart: Int,
    val spanEnd: Int,
    val spanText: String,
    /**
     * MH — the option's mention kind (`entity` | `entity_with_measures` | `measure` |
     * `attribute`; blank for a MEMBER option and for a ref the archive declares nothing about).
     * A G2 over a homonym is unanswerable when both options are just labels; the species is what
     * makes it a question a human can answer.
     */
    val objectKind: String = "",
    /**
     * MH tier M — for a MEMBER option, the ref whose member vocabulary produced it — its ATTRIBUTE
     * since MV-T3 (`GateSpans.vocabularyRefOf`), and signed into the resume token since review-104
     * F6; blank for a VOCABULARY option, which names its object through [targetRef] already.
     *
     * The exact complement of [objectKind], and for the same reason: a clarification is only a
     * question if its options can be told apart. `object_kind` is what separates two OBJECTS
     * sharing a word; this is what separates two MEMBERS sharing a value, where the label, the
     * span and the (absent) target ref are all identical and the owner is the whole difference.
     */
    val memberOf: String = "",
)

/**
 * The sibling-column catalog (Q-20): a column category → the sibling categories a
 * value match should also point at (KOD ↔ NAZEV). Snapshot-fed in S2; injected here
 * so gate stays a pure function.
 */
typealias SiblingCatalog = Map<String, List<String>>
