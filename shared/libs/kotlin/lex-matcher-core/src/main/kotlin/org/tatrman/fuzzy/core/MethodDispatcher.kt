// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import info.debatty.java.stringsimilarity.Levenshtein

/**
 * RV-P1.4 T4 — honours the authored [MatchMethod] on the cascade's output.
 *
 * **A gate, not a third retrieval path, and not a rescorer.**
 *  - *Not a retrieval path*: the cascade is recall-oriented and already surfaced every candidate
 *    worth considering; the method is precision, so dispatch may only narrow. An `EXACT` term the
 *    cascade did not surface is a term the query did not match anyway (an exact hit scores at the
 *    top of the token algorithm by construction).
 *  - *Not a rescorer*: an admitted candidate keeps the engine's score. Rewriting scores per method
 *    would put three scales in one response — precisely the problem [FuzzyMatcher.matchCascade]
 *    avoids by returning one algorithm's results wholesale instead of merging.
 *
 * Candidates with no authored method (every METADATA row, and a member row of a store that
 * predates member methods) pass through untouched. That is what keeps T7's "no behaviour change
 * without the artifact present" true: with nothing authored, [dispatch] returns its input list, same
 * instance.
 *
 * **Member rows (MV).** A member row carries its vocabulary's method, and the gate holds it to that
 * method exactly as it holds a declared term — `state` is EXACT, so `tx` does not reach `TN`. Two
 * things are the DECLARED layer's alone, and a member row takes part in neither (review-104 F4/F9):
 * the caller's `method_override` (a rung widening or tightening its own terms) and RV-32's
 * uniqueness margin (a verdict about which declared target a term means).
 */
class MethodDispatcher(
    /**
     * RV-32's floor: below this, a TOKENS match is not discriminative enough to auto-bind.
     *
     * Deliberately small. The floor answers one narrow question — *is this effectively a tie?* —
     * and the resolver's evidence-class gate does the actual ranking. A high floor here would
     * suppress good matches on a decision the matcher is not the right layer to make.
     */
    private val uniquenessFloor: Double = DEFAULT_UNIQUENESS_FLOOR,
    /**
     * RV-44 — scoring for rows that carry a declared matching profile. A collaborator, not a
     * subclass: dispatch stays a gate for everything else, and a row with no profile never touches
     * this object at all.
     */
    private val profileScorer: ProfileScorer = ProfileScorer(),
) {
    private val levenshtein = Levenshtein()

    fun dispatch(
        query: String,
        results: List<FuzzyMatchResult>,
        override: MatchMethod? = null,
        lemmaQuery: String? = null,
    ): List<FuzzyMatchResult> {
        // The override replaces the authored method on rows that HAVE one, and only those. It must
        // not impose a method on rows nobody authored: `method_override = EXACT` on a lookup round
        // would otherwise gate the entire data layer on exact equality and silently drop every
        // member candidate — a caller widening its own declared layer would narrow the estate's.
        val parsed = results.map { it to it.effectiveMethod(override) }
        // Nothing authored anywhere ⇒ the input list, same instances: the pre-RV service, and the
        // reason a member-only estate pays nothing for either mechanism. RV-44 widened the question
        // from "did anyone author a method?" to "did anyone author a matching rule at all?".
        if (parsed.none { (result, method) -> method != null || result.matchProfile != null }) return results

        val canonicalQuery = TextNormalizer.canonical(query)
        val forms = QueryForms.of(query, lemmaQuery)
        val admitted =
            parsed.mapNotNull { (result, method) ->
                val profile = result.effectiveProfile(override)
                if (profile != null) {
                    // A profile answers "does it match" and "how strongly" in one pass, so it
                    // replaces the gate for its own rows rather than running after it.
                    profileScorer.score(forms, result, profile)?.let { it to method }
                } else if (admits(canonicalQuery, result, method)) {
                    result to method
                } else {
                    null
                }
            }
        return withUniquenessMargin(admitted)
    }

    /**
     * Recomputes the margins over an already-admitted, **merged** result set.
     *
     * [dispatch] runs per category, so its margins are category-local — and since the compiled
     * artifact keys one category per target ref, cross-target competition is invisible there by
     * construction. Any path that merges several categories into one answer (a BatchMatch span, a
     * T5 lookup) has to ask the uniqueness question again over the union, or it would report a
     * clean margin for a term that is in fact ambiguous across the very categories the caller asked
     * about — the failure T4's `LexiconArchiveSource` category convention predicted.
     *
     * Admission is not re-run: it is per-candidate and already decided.
     *
     * **Safe to run after the overlay speaks — and RV-P7.3 T3 requires it to.** Until P7.3 this
     * carried the opposite instruction: it derived `autoBindable` from the margin *alone*, so
     * running it after a consult silently re-enabled auto-binding on a candidate the estate had
     * denied, and the only safe order was overlay-last. Overlay-last is also wrong, for the
     * complementary reason — a denied target went on counting as a rival. Both are fixed by
     * [FuzzyMatchResult.suppressed]: the estate's statement now lives on the row, so this function
     * can read it instead of overwriting it. `OverlayLayerTest` and `LearnedLayerSpec` pin both
     * halves.
     */
    fun recomputeMargins(
        results: List<FuzzyMatchResult>,
        override: MatchMethod? = null,
    ): List<FuzzyMatchResult> =
        withUniquenessMargin(
            // Same override rule as [dispatch] — and it has to be repeated here, because a row's
            // reported `matchMethod` is always the AUTHORED one. Reading it back without the
            // override would recompute the margin over the wrong set of rows.
            results.map { it to it.effectiveMethod(override) },
        )

    /**
     * The method this call actually dispatches by: the caller's [override] where one is given, the
     * authored method otherwise — and **null stays null**.
     *
     * The override replaces a method, it never grants one: a row nobody authored has nothing to
     * override, so `method_override = EXACT` cannot reach into the data layer and gate it on exact
     * equality. A caller widening its own declared layer must not narrow the estate's.
     *
     * ⚑ MV (review-104 F9) — and a MEMBER row is never overridden, although it now carries a
     * method. "Rows nobody authored" used to be exactly the data layer; since MV every member row
     * carries its vocabulary's method, so the rule is stated by layer rather than inferred from a
     * null. `EXACT` on a rung would otherwise drop every partial member hit, and `TOKENS` would widen
     * an EXACT state code so `tx` reaches `TN`.
     */
    private fun FuzzyMatchResult.effectiveMethod(override: MatchMethod?): MatchMethod? =
        if (source == SourceTag.MEMBER) authoredMethod else authoredMethod?.let { override ?: it }

    /**
     * The profile this call actually scores by — and it obeys the same rule as [effectiveMethod]:
     * an override REPLACES what the estate declared (with the sugar profile that override means)
     * but never grants a profile to a row that has none. A rung widening its own declared layer
     * must not start scoring the member index by somebody's declared numbers.
     */
    private fun FuzzyMatchResult.effectiveProfile(override: MatchMethod?): MatchProfile? =
        matchProfile?.let { if (override == null) it else sugarProfile(override) }

    /** The RV-44 M-T6 table, mirrored: `method_override` is sugar like any other method. */
    private fun sugarProfile(method: MatchMethod): MatchProfile =
        MatchProfile(
            listOf(
                when (method) {
                    MatchMethod.Exact -> NormRule(Norm.CANONICAL, exact = SUGAR_EXACT_SCORE)
                    MatchMethod.Tokens -> NormRule(Norm.CANONICAL, tokens = true)
                    is MatchMethod.Typos ->
                        NormRule(
                            Norm.CANONICAL,
                            exact = SUGAR_EXACT_SCORE,
                            typos = TyposRule(method.maxDistance, SUGAR_TYPOS_PENALTY),
                        )
                },
            ),
        )

    /**
     * `null` (unauthored) and [MatchMethod.Tokens] admit everything the engine scored — TOKENS *is*
     * the engine's own algorithm, so a second opinion here would only disagree with itself.
     * EXACT and TYPOS(n) are the author's narrowing, and both compare on the **authored** form
     * ([TextNormalizer.canonical], diacritics intact) rather than the engine's fold.
     */
    private fun admits(
        canonicalQuery: String,
        result: FuzzyMatchResult,
        method: MatchMethod?,
    ): Boolean =
        when (method) {
            null, MatchMethod.Tokens -> true
            MatchMethod.Exact -> canonicalQuery == result.canonical
            // Unbounded on purpose: debatty's bounded overload *returns the limit* when the true
            // distance exceeds it, so `distance(a, b, n) <= n` is vacuously true and would admit
            // everything. Terms are short; the cap is the author's `n`, applied here.
            // RV-44 ⚑M-4 — the short-term guard reaches this path too, so a row from a
            // pre-profile archive is held to the same rule as one that carries a profile. One
            // guard, one place it is decided, whatever built the artifact. It suppresses the
            // FUZZ, never the term: distance 0 is the word itself, and a guard that refused that
            // would make a short TYPOS row unmatchable rather than merely un-fuzzy.
            is MatchMethod.Typos -> {
                val d = levenshtein.distance(canonicalQuery, result.canonical)
                d <= method.maxDistance.toDouble() &&
                    (d == 0.0 || result.canonical.length > ProfileScorer.SHORT_TERM_MAX_CHARS)
            }
        }

    /**
     * The candidate's authored form — precomputed at load ([Candidate.canonicalValue]) for every row
     * that came off the index, normalised on the spot for one that did not (an overlay addition, a
     * hand-built test row). Only [MatchMethod.Exact] and [MatchMethod.Typos] read it.
     */
    private val FuzzyMatchResult.canonical: String
        get() = canonicalCandidate ?: TextNormalizer.canonical(candidate)

    /**
     * RV-32 — annotates each TOKENS candidate with how far its target beat the best *other* target,
     * and with the floor decision that follows.
     *
     * Identity is the target ref (falling back to the candidate id when a row somehow lacks one):
     * two aliases of one measure are one binding, not a tie, so they must not depress each other's
     * margin. Only TOKENS rows compete — see [FuzzyMatchResult.uniquenessMargin] for why the margin
     * stays inside the declared layer instead of ranking across layers.
     *
     * **RV-P7.3 T3 — a SUPPRESSED row is not a rival.** An overlay NEGATIVE is the estate saying
     * this term does not mean that target; a target the estate has denied cannot then be the reason
     * another one looks ambiguous. Left in the rival set, one user's "no, not that one" would go on
     * blocking auto-binding of the target they actually picked, forever — the ask would repeat
     * every time, which is the precise opposite of learning. Suppressed rows still get their own
     * margin computed and reported: they are offered and ranked, just never bound (RV-2).
     */
    private fun withUniquenessMargin(admitted: List<Pair<FuzzyMatchResult, MatchMethod?>>): List<FuzzyMatchResult> {
        val bestByTarget =
            admitted
                .filter { (result, method) -> competes(result, method) && !result.suppressed }
                .groupBy { (result, _) -> result.identity }
                .mapValues { (_, rows) -> rows.maxOf { (result, _) -> result.score } }

        if (bestByTarget.isEmpty()) return admitted.map { (result, _) -> result }

        // "The best OTHER target" needs only the top two targets, found once — the previous
        // filterKeys-per-row rebuilt the whole map for every candidate.
        val ranked = bestByTarget.entries.sortedByDescending { it.value }
        val leader = ranked[0]
        val runnerUp = ranked.getOrNull(1)?.value

        return admitted.map { (result, method) ->
            if (!competes(result, method)) {
                result
            } else {
                // A suppressed row is absent from `bestByTarget`, so its own best is its own score.
                val mine = bestByTarget[result.identity] ?: result.score
                val rival =
                    when {
                        // Its rivals are the surviving targets; the leader IS one of them, since a
                        // suppressed row never entered the ranking.
                        result.suppressed -> leader.value
                        result.identity == leader.key -> runnerUp
                        else -> leader.value
                    }
                // Unopposed ⇒ the gap is measured from zero, which clears any sane floor. That is
                // the right reading: nothing else in the layer answers to this query.
                val margin = if (rival == null) mine else mine - rival
                result.copy(
                    uniquenessMargin = margin,
                    // The estate's NEGATIVE is final: a margin cannot argue a denied target back
                    // into auto-binding, whatever the arithmetic says.
                    autoBindable = !result.suppressed && margin >= uniquenessFloor,
                )
            }
        }
    }

    private val FuzzyMatchResult.identity: String get() = targetRef ?: candidateId

    /**
     * Whether a row takes part in RV-32's margin: a DECLARED `TOKENS` row. A member row does not,
     * even a `TOKENS` one (MV, review-104 F4). Two stores named alike are two data values, and which
     * one the user meant is the resolver's tie band to settle — as a clarification. A margin verdict
     * here would read `auto_bindable = false`, which the resolver classes WEAK: never bound, never
     * offered, so the span became a gap where it used to be a menu.
     */
    private fun competes(
        result: FuzzyMatchResult,
        method: MatchMethod?,
    ): Boolean = method == MatchMethod.Tokens && result.source != SourceTag.MEMBER

    companion object {
        const val DEFAULT_UNIQUENESS_FLOOR: Double = 0.05

        /** RV-44 M-T6 — what `EXACT`/`TYPOS(d)` sugar scores an equality hit at. */
        const val SUGAR_EXACT_SCORE: Double = 1.00

        /** RV-44 M-T6 — the per-edit penalty `TYPOS(d)` sugar carries. */
        const val SUGAR_TYPOS_PENALTY: Double = 0.05
    }
}
