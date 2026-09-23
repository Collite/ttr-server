// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

/**
 * How a candidate's category was sourced (contracts §2, RS-15).
 *
 * **Widened at RV-P1.4 (additive, J-v2).** RV's contracts §1 names four layers —
 * `DECLARED | METADATA | DATA | LEARNED` — where this enum had two. The compiled lexicon artifact
 * carries the RV value per row, and collapsing it into the old pair would throw away the
 * declared-vs-metadata distinction the artifact was built to keep: an author's file states an
 * intent, a model label is a byproduct, and the evidence-class gate downstream ranks them
 * differently.
 *
 * The mapping onto what was already here:
 *  - [MEMBER] **is** RV's `DATA` — member vocabulary read out of the data, `id` is a data PK.
 *    Not renamed: it is wire value 0 and the name is used throughout the loaders.
 *  - [VOCABULARY] is the pre-RV conflation of DECLARED and METADATA (it covered both lexicon
 *    terms and `valueLabels`). Kept for the fixture-stub source, which cannot tell them apart.
 *    Anything reading the compiled artifact emits [DECLARED] or [METADATA] instead.
 *  - [LEARNED] is the estate overlay. Produced from RV-P7.3, when the overlay became a loaded
 *    layer rather than an empty slot.
 *
 * All of them flow through the same cascade with the same scoring — the layer is evidence, not a
 * filter, and lex-matcher never picks a winner across layers (that is the resolver's gate).
 */
enum class SourceTag {
    /** Data values — RV's `DATA`. The candidate `id` is a data PK (→ `resolved_id`). */
    MEMBER,

    /** Legacy: declared lexicon / `valueLabels`, undifferentiated. Superseded by the two below. */
    VOCABULARY,

    /** Authored in the `lexicon/` area or as TTR-M `def term` sugar. Carries a `targetRef`. */
    DECLARED,

    /** Harvested from model labels (`displayLabel`, `labelPlural`, `aliases`, `valueLabels`). */
    METADATA,

    /** The estate overlay (RV-4/18/20/23), loaded from the overlay archive since RV-P7.3. */
    LEARNED,
}

/**
 * S-4 confidence provenance: which producer + method yielded the score.
 *
 * RV-44 adds the winning `(norm, algorithm, distance)` for a row scored by a declared matching
 * profile — additive, and null on every other row, because "no norm fired" and "the canonical one
 * fired" must not read alike. [rawScore] stays the ENGINE's number even when [ProfileScorer]
 * replaces the reported score with the author's: losing it would make "how did retrieval actually
 * rate this?" unanswerable.
 */
data class Provenance(
    val producer: String,
    val method: String,
    val rawScore: Double,
    /** [Norm.wire] — `canonical` · `folded` · `lemma`. Null unless a profile scored the row. */
    val norm: String? = null,
    /** [MatchAlgorithm] — `exact` · `typos` · `tokens`. Null unless a profile scored the row. */
    val algorithm: String? = null,
    /** The winning edit distance. Null unless the winning algorithm was `typos`. */
    val distance: Int? = null,
    /**
     * LP-P0 (`fuzzy.match:v2`, contracts §4.6) — how each matched query token matched. Empty for
     * v1 rows: v1 never computed it, and an empty list must not be read as "nothing matched".
     */
    val tokenHits: List<TokenHit> = emptyList(),
    /** LP-P0 — v2's candidate coverage `C` (contracts §4.3). Null for v1 rows. */
    val coverage: Double? = null,
)

/**
 * RV-39 — the layer-version tuple, echoed on every response (S-1).
 *
 * Replaces nothing: the old opaque `vocabularyVersion` string stays alongside it (J-v2 additive).
 * The two answer different questions, and the old one answers its badly — it bakes in the member
 * load timestamp, so it changes on every refresh whether or not any vocabulary did. This tuple is
 * asked exactly one question, *did a layer change?*, and each component is content-derived.
 */
data class LayerVersions(
    /**
     * `CompiledLexicon.contentHash` of the loaded artifact — content of the entry table only, so
     * it does not move when the build clock does. Empty when no artifact is loaded.
     */
    val lexiconArtifactHash: String = "",
    /** category → the member index's version for that category. */
    val memberIndexVersions: Map<String, String> = emptyMap(),
    /** Absent (null) when no overlay is loaded — absence is the contract, not `""`. */
    val overlayVersion: String? = null,
)

data class FuzzyMatchResult(
    val candidateId: String,
    val candidate: String,
    val score: Double,
    val category: String,
    // RG-P2 additive (response-side; the pinned MatchRequest is untouched):
    val source: SourceTag = SourceTag.MEMBER,
    val targetRef: String? = null,
    val provenance: Provenance = Provenance("fuzzy", "TATRMAN", score),
    /**
     * RV-32 — the **authored** match method (`EXACT` · `TOKENS` · `TYPOS(n)`), a different axis
     * from [Provenance.method], which is the algorithm that produced the score. Null for member
     * candidates: nobody authored a method for a data value.
     *
     * RV-P1.4 T2 carries it; **T4 honours it** (dispatch). Carrying it first is deliberate — the
     * value has to survive the loader and the cascade before the dispatcher can be trusted to read it.
     */
    val matchMethod: String? = null,
    /**
     * RV-32 — the uniqueness margin, **contractual for TOKENS** and null for every other method.
     *
     * `bestScore(this target) − bestScore(the best other target)`, over the TOKENS candidates in
     * this response. Negative for a target that lost to a better one; equal to the candidate's own
     * score when nothing competes.
     *
     * Measured **within the declared layer**, not across layers. A declared term is not made
     * ambiguous by a member value that happens to score near it — that is the 0..n-bindings case
     * (RV-2) the resolver's evidence-class gate exists to arbitrate, and computing the margin
     * across layers would smuggle that decision into the matcher, which never picks a winner
     * across layers (T2's rule).
     *
     * Competing *aliases of the same target* are not competition: identity is the target ref, so
     * two spellings of one measure yield one target, not a tie.
     */
    val uniquenessMargin: Double? = null,
    /**
     * RV-32 — false ⇒ the caller **must not auto-bind** this candidate; offer it, rank it, ask
     * about it, but do not resolve to it unattended. Null means no such decision was made (no
     * authored TOKENS method), which is every pre-RV candidate.
     *
     * The decision travels, not just the number, so the floor stays one value in one place instead
     * of every consumer re-deciding what "close enough" means.
     *
     * **Reason-agnostic** (RV-P1.4 T6). Two independent things set it false: an RV-32 margin under
     * the floor, and an estate overlay entry with NEGATIVE polarity. The caller's action is the same
     * either way — offer it, rank it, ask about it, do not bind it unattended — so one channel
     * carries both. If RV-P7 turns out to need the reason, that is an additive field then, not a
     * second flag now.
     */
    val autoBindable: Boolean? = null,
    /**
     * RV-P7.3 T3 — the estate's overlay carries a NEGATIVE entry for this (term, target).
     *
     * P1.4 predicted this field and deferred it correctly: *"if RV-P7 turns out to need the reason,
     * that is an additive field then"*. It turned out to, for a reason that is about the margin
     * rather than about the caller. [autoBindable] alone cannot survive the pipeline, because
     * `MethodDispatcher.recomputeMargins` derives that flag from the margin and would overwrite a
     * suppression — so the only safe order was overlay-last, and overlay-last is exactly what T3
     * forbids: a denied candidate would go on counting as a rival and go on making the candidate
     * the user actually meant look ambiguous.
     *
     * Carrying the reason on the row fixes both. The margin computation reads it — a suppressed
     * row is not a rival, and never has its `autoBindable` re-enabled — so suppression may now run
     * BEFORE the margin, which is where the estate's own statement belongs.
     *
     * **Engine-internal, and deliberately not on the wire.** `Binding.auto_bindable` is still the
     * whole instruction to the caller (P1.4's reason-agnostic ruling stands): offer it, rank it,
     * ask about it, do not bind it unattended. Nothing downstream acts differently on *why*.
     */
    val suppressed: Boolean = false,
    /**
     * RV-38 — the target's class. Null for member values; the kind is derived from this, never
     * stored. T5's lookup scopes on it.
     */
    val targetClass: TargetClass? = null,
    /**
     * RV-P1.4 T4 — [matchMethod] already parsed, threaded through from [Candidate.authoredMethod]
     * so the dispatcher never re-parses it per request.
     *
     * The default keeps every direct construction (tests, overlay additions) honest: a result built
     * from a method string alone still gets the right parse, just at construction rather than at
     * load. `copy()` carries it, which matters — the dispatcher copies rows to stamp margins and
     * must not lose the method it dispatched on.
     */
    val authoredMethod: MatchMethod? = MatchMethod.parse(matchMethod),
    /**
     * RV-P1.4 T4 — [candidate] in its authored form, threaded through from [Candidate.canonicalValue].
     *
     * Null when there is no authored method, exactly as on [Candidate]: `EXACT`/`TYPOS(n)` are the
     * only readers, and they only ever look at rows that have one. [MethodDispatcher] falls back to
     * normalising on the spot for a row that arrived without it.
     */
    val canonicalCandidate: String? = null,
    /**
     * RV-44 — the row's resolved matching profile, threaded through from [Candidate.matchProfile].
     * Null for every row the author never wrote a rule for (⚑M-2), which is what makes their
     * scoring byte-identical to the pre-RV-44 service.
     */
    val matchProfile: MatchProfile? = null,
    /**
     * RV-44 — [candidate] in its lemma form, from [Candidate.lemmaValue]. Only a profile's `lemma`
     * norm reads it; absent, the scorer falls back to the folded surface, which is where the
     * lemma axis collapses anyway with no lemmatiser installed.
     */
    val lemmaCandidate: String? = null,
)
