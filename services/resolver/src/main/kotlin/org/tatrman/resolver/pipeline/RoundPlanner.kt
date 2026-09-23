// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.Span
import org.tatrman.resolver.v1.ValueKind
import org.tatrman.fuzzy.v1.TargetClass as FuzzyTargetClass

/**
 * RV-P2.3.T2 — **what to ask next**, as a pure function of the lattice.
 *
 * RV-33's cheapest rung is "deterministic, anchored, category-scoped, always eligible first", and
 * GI-14's iterative narrowing is what makes it a *loop* rather than a second pass. This is the
 * half that decides; [LookupRounds] is the half that asks. They are separated because the
 * interesting bugs live here — a planner that widens too early spends the estate's precision, and
 * one that never widens leaves recall on the floor — and neither is visible through a fake matcher.
 *
 * **Three tiers, strictly ordered, and only the highest non-empty one runs.** That ordering IS the
 * narrowing policy:
 *
 *  1. [Tier.ANCHORED_VALUE] — a **G4** value whose anchor mention is BOUND. The user named the axis, so
 *     the categories come from what that mention bound, not from the whole estate. This is the
 *     structural fix for issues.md §1 expressed as a query: *"the user said účet, so check `501001`
 *     against account first"*.
 *  2. [Tier.UNBOUND_MENTION] — a **G1** mention nothing in the model bound. Asked cross-category but
 *     **class-scoped** to model objects and operators, which is what `Lookup`'s class filter is
 *     for: "which operator is this?" must not be answered with a row of data that reads alike
 *     (contracts §1 addendum, rule 4). Binding one of these is also what makes the next round's
 *     tier 1 possible — that is the two-hop.
 *  3. [Tier.BROAD] — a **G3** value nothing scoped, asked with no categories and a capped
 *     `max_candidates`. Deliberately last and deliberately bounded: an unscoped code search is the
 *     over-generation Q-20 removed, and a lattice is allowed to say G3 rather than guess.
 *
 * **Why [asked] is a parameter and not state.** A tier that keeps re-proposing a query nobody
 * answered would never let the next tier run, and a fixpoint on "did the lattice change" cannot
 * tell that apart from progress. Threading the asked-set keeps the function pure, makes the loop
 * provably finite (the query space is spans × tiers, and every round consumes at least one of it),
 * and makes "we already tried that" a fact the planner can be tested on.
 */
object RoundPlanner {
    /** The narrowing priority. Lower ordinal = asked first; a tier runs only if all above are dry. */
    enum class Tier { ANCHORED_VALUE, UNBOUND_MENTION, BROAD }

    /**
     * One planned question. Carries what `LookupRequest` can express and the core cannot say any
     * other way — class scoping and a method override are both absent from `SpanQuery`, which is
     * precisely why a round is a `Lookup` and not another `BatchMatch`.
     */
    data class Query(
        val spanStart: Int,
        val spanEnd: Int,
        val term: String,
        val categories: List<String>,
        val targetClasses: List<FuzzyTargetClass>,
        val methodOverride: String?,
        val maxCandidates: Int,
        val tier: Tier,
    ) {
        /** What "already asked" means: the same span, scoped the same way, at the same tier. */
        val key: String
            get() = "$tier|$spanStart-$spanEnd|${categories.sorted()}|${targetClasses.map {
                it.number
            }}|$methodOverride"
    }

    /**
     * **The rung plans from GAPS, not from spans**, and that is the correction that makes it a
     * rung at all rather than a second sweep.
     *
     * Contracts §3 gives every gap kind its own rung list, and `lookup` is eligible for G1 · G2 ·
     * G3 · G4 — so "what should the cheapest rung try next" is already answered by the gap table,
     * and re-deriving it from raw span shape can only disagree with it. It disagreed immediately:
     * planning over unattributed values asked the lexicon about `10` in *"prvních 10 čerpacích
     * stanic"*, which `Gaps` deliberately does not record as a gap at all (it is an operator's own
     * argument, not a missing attribution). One wasted round per question, on a span where success
     * would have been worse than failure.
     *
     * G2 is left out on purpose: re-asking an ambiguous span with the same scope returns the same
     * ambiguity, so the only honest narrowing for it is a stricter METHOD, and the list names three
     * priorities, none of which is ambiguity. Worth a later list. G5/G6 are not `lookup`'s at all
     * per contracts §3 — one is a degraded parse, the other a judgement about meaning.
     */
    fun plan(
        lattice: ResolutionState,
        entityTypes: List<ResolverEntityType>,
        asked: Set<String>,
        config: LookupRoundConfig,
    ): List<Query> {
        val categoriesByRef = entityTypes.associate { it.ref to it.categories }
        val mentionsById = lattice.mentionsList.associateBy { it.id }
        val valuesById = lattice.valuesList.associateBy { it.id }

        for (tier in Tier.entries) {
            val queries =
                lattice.gapsList.mapNotNull { gap ->
                    when (tier) {
                        // G4 — the user named the axis and the lookup in it missed. The scope is
                        // known, so the round re-asks inside it: "the user said účet, so check
                        // `501001` against account". This is the only tier that narrows rather
                        // than widens, which is why it goes first.
                        Tier.ANCHORED_VALUE -> {
                            if (gap.kind != GapKind.GAP_KIND_G4_METHOD_MISS) return@mapNotNull null
                            val value = valuesById[gap.valueId] ?: return@mapNotNull null
                            val anchor = mentionsById[value.anchorMentionId] ?: return@mapNotNull null
                            // The categories the anchor actually BOUND — an anchor that bound
                            // nothing lends no scope, which is the same distinction `Gaps` turns
                            // on when it tells a scoped miss from an unscoped one.
                            val categories =
                                anchor.bindingsList
                                    .flatMap { categoriesByRef[it.ref].orEmpty() }
                                    .distinct()
                            if (categories.isEmpty()) return@mapNotNull null
                            query(gap.span, categories, emptyList(), config.maxCandidates, tier)
                        }

                        // G1 — nothing in this estate bound the word. Asked cross-category but
                        // CLASS-scoped: a mention names something in the model, and answering
                        // "what is this word?" with a data value that reads alike is where
                        // issues.md started.
                        Tier.UNBOUND_MENTION -> {
                            if (gap.kind != GapKind.GAP_KIND_G1_UNBOUND) return@mapNotNull null
                            query(gap.span, emptyList(), MENTION_CLASSES, config.maxCandidates, tier)
                        }

                        // G3 — nothing scoped it, so there is no scope to re-ask inside and the
                        // only move left is to widen. Last, and bounded: an unscoped search is the
                        // over-generation Q-20 removed, and a lattice is allowed to keep saying G3
                        // rather than force a binding (P-3).
                        Tier.BROAD -> {
                            if (gap.kind != GapKind.GAP_KIND_G3_UNATTRIBUTED) return@mapNotNull null
                            // LP contracts §2.4 — except a VERBATIM one. A headless literal is a
                            // G3 like any other and is the ONE G3 that must not widen: the user
                            // quoted it, which says "do not look this up" in the plainest way the
                            // language has. The gap stays open and is answered by asking the user
                            // which column, not by searching the estate for a string they already
                            // told us is a string.
                            if (valuesById[gap.valueId]?.kind == ValueKind.VALUE_KIND_VERBATIM) {
                                return@mapNotNull null
                            }
                            query(gap.span, emptyList(), emptyList(), config.broadMaxCandidates, tier)
                        }
                    }
                }
            val fresh = queries.filter { it.key !in asked }.take(config.maxQueriesPerRound)
            if (fresh.isNotEmpty()) return fresh
        }
        return emptyList()
    }

    private fun query(
        span: Span,
        categories: List<String>,
        targetClasses: List<FuzzyTargetClass>,
        maxCandidates: Int,
        tier: Tier,
    ) = Query(
        spanStart = span.start,
        spanEnd = span.end,
        term = span.text,
        categories = categories,
        targetClasses = targetClasses,
        methodOverride = null,
        maxCandidates = maxCandidates,
        tier = tier,
    )

    /**
     * What an unbound mention may be. Members are excluded BY the class filter rather than by a
     * category list, which is the whole reason the filter exists: a mention is a name for
     * something in the model, and answering "what is this word?" with a data value that happens to
     * read alike is the failure mode issues.md opened with.
     */
    private val MENTION_CLASSES =
        listOf(
            FuzzyTargetClass.TARGET_CLASS_MODEL_OBJECT,
            FuzzyTargetClass.TARGET_CLASS_OPERATOR,
        )
}
