// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import info.debatty.java.stringsimilarity.Levenshtein

/** The query in every form a [Norm] can compare on, computed once per scored category. */
data class QueryForms(
    val canonical: String,
    val folded: String,
    val lemma: String,
) {
    companion object {
        /**
         * @param lemma the query's lemmatised form. Defaults to [folded] — with no lemmatiser
         *   installed the lemma axis collapses onto the folded one, which is what every other
         *   lemma-aware path in this engine already does.
         */
        fun of(
            query: String,
            lemma: String? = null,
        ): QueryForms {
            val folded = TextNormalizer.fold(query)
            return QueryForms(TextNormalizer.canonical(query), folded, lemma ?: folded)
        }
    }
}

/** Which `(norm, algorithm, distance)` produced a score — the winner travels in provenance. */
data class Firing(
    val norm: Norm,
    val algorithm: String,
    val distance: Int?,
    val score: Double,
)

/**
 * RV-P3.0 T6 (RV-44) — scores a row that carries a **declared matching profile**.
 *
 * Beside [MethodDispatcher] rather than inside it, because the two do different things to different
 * rows. Dispatch is a *gate*: it admits or refuses a row on the author's method and leaves the
 * engine's score alone (the P1.4 T4 ruling). A profile row has no separate gate — its rules
 * ARE both questions at once, "does this term match at all" and "how strongly", so a profile that
 * fires nowhere is a row that does not match, and one that fires somewhere carries the author's own
 * number. That is not a third engine scale sneaking in: it replaces the engine's number **only**
 * where an author actually wrote one (⚑M-2), and rows with no profile — the whole member index,
 * every harvested model label — go down the untouched path.
 *
 * Combination across a profile's rules is **max** (dis_max). With monotone-decreasing scores that
 * equals first-match-wins, and max is what makes a mis-ordered `match:` list harmless.
 *
 * **review-103 F3 — a `tokens` firing competes on `min(S, 1.0)`.** Its score is the engine's, and
 * the engine's scale runs past 1.0 for an ordered exact match (v1's order bonus; v2's `+ ε·C`, so a
 * one-token exact hit is 1.01). Compared raw, `[{canonical, exact 1.0}, {folded, tokens}]` fired
 * `tokens` at 1.01 over its own exact rule, and the row was demoted from EXACT to DECLARED_ALIAS —
 * the order bonus outranking the author's number. Capped, the two tie at 1.0, and a tie goes to the
 * declared firing (exact/typos) in either rule order, so the list stays order-insensitive. A
 * `tokens` firing that wins still carries the engine's own score, uncapped.
 */
class ProfileScorer(
    /**
     * ⚑M-5 — a global floor on the declared score. **Default 0 = off**: RV-14's classes,
     * WEAK-never-binds and the RV-32 margin already do the safety work, so this knob exists for an
     * estate that wants it and for nobody else. Deliberately different from the legacy default,
     * where no class system exists — recorded so that nobody "harmonizes" the two later.
     */
    private val minInClassScore: Double = DEFAULT_MIN_IN_CLASS_SCORE,
) {
    private val levenshtein = Levenshtein()

    /**
     * The scored row, or **null when nothing fired** — the caller drops it.
     *
     * @param engineScore what the cascade scored this candidate. It is the score a `tokens` rule
     *   contributes, because TOKENS *is* the engine's own algorithm (RV-32, untouched by RV-44):
     *   a second opinion there would only disagree with itself.
     */
    fun score(
        forms: QueryForms,
        result: FuzzyMatchResult,
        profile: MatchProfile,
    ): FuzzyMatchResult? {
        val winner = fire(forms, result, profile) ?: return null
        if (minInClassScore > 0.0 && winner.score < minInClassScore) return null

        return result.copy(
            score = winner.score,
            // `rawScore` stays the ENGINE's number. The declared score is the author's, and losing
            // the engine's would make "how did the retrieval actually rate this?" unanswerable.
            provenance =
                result.provenance.copy(
                    norm = winner.norm.wire,
                    algorithm = winner.algorithm,
                    distance = winner.distance,
                ),
        )
    }

    private fun fire(
        forms: QueryForms,
        result: FuzzyMatchResult,
        profile: MatchProfile,
    ): Firing? {
        var best: Firing? = null
        for (rule in profile.rules) {
            for (firing in firings(forms, result, rule)) {
                if (best == null || firing.beats(best)) best = firing
            }
        }
        return best
    }

    /**
     * Strictly better than [other] — so the earlier firing keeps a tie — on the comparison score
     * ([rank]), with one tie rule: the author's declared firing beats the engine's `tokens` reading.
     */
    private fun Firing.beats(other: Firing): Boolean {
        val mine = rank()
        val theirs = other.rank()
        if (mine != theirs) return mine > theirs
        return algorithm != MatchAlgorithm.TOKENS && other.algorithm == MatchAlgorithm.TOKENS
    }

    /** The comparison score: a `tokens` firing's engine score capped at 1.0 (review-103 F3). */
    private fun Firing.rank(): Double = if (algorithm == MatchAlgorithm.TOKENS) minOf(score, 1.0) else score

    private fun firings(
        forms: QueryForms,
        result: FuzzyMatchResult,
        rule: NormRule,
    ): List<Firing> {
        val queryForm = forms.on(rule.norm)
        val candidateForm = result.on(rule.norm)
        val out = mutableListOf<Firing>()

        if (rule.exact != null && queryForm == candidateForm) {
            out += Firing(rule.norm, MatchAlgorithm.EXACT, null, rule.exact)
        }
        // TOKENS keeps the engine's score and the engine's margin — RV-44 generalizes the
        // AUTHORING surface, it does not re-open RV-32.
        if (rule.tokens) {
            out += Firing(rule.norm, MatchAlgorithm.TOKENS, null, result.score)
        }
        val typos = rule.typos
        if (typos != null && rule.exact != null && !isShortTerm(result)) {
            // Unbounded on purpose: debatty's bounded overload RETURNS the limit when the true
            // distance exceeds it, so `distance(a, b, n) <= n` is vacuously true. Terms are short.
            val d = levenshtein.distance(queryForm, candidateForm).toInt()
            // A firing that scores at or below zero is not a weak match, it is a broken rule: the
            // author's budget outran their anchor. `RG-LEX-017` rejects that pair at authoring
            // time, so this only ever catches an artifact built by a toolchain that predates the
            // check — but a negative score on the wire would sort below rows nothing matched at
            // all, and silently. Dropping the firing is the honest reading of "it does not match".
            val score = rule.exact - d * typos.penalty
            if (d in 1..typos.distance && score > 0.0) {
                out += Firing(rule.norm, MatchAlgorithm.TYPOS, d, score)
            }
        }
        return out
    }

    /**
     * ⚑M-4 — the short-term guard, measured on the **authored** term (not the query): a one-edit
     * neighbourhood around a two-character token reaches most of its siblings, so `typos` never
     * fires there however the estate declared it. The build already warned (`RG-LEX-101`); this is
     * where the rule actually does not fire.
     */
    private fun isShortTerm(result: FuzzyMatchResult): Boolean =
        (result.canonicalCandidate ?: TextNormalizer.canonical(result.candidate)).length <= SHORT_TERM_MAX_CHARS

    private fun QueryForms.on(norm: Norm): String =
        when (norm) {
            Norm.CANONICAL -> canonical
            Norm.FOLDED -> folded
            Norm.LEMMA -> lemma
        }

    private fun FuzzyMatchResult.on(norm: Norm): String =
        when (norm) {
            Norm.CANONICAL -> canonicalCandidate ?: TextNormalizer.canonical(candidate)
            Norm.FOLDED -> TextNormalizer.fold(candidate)
            Norm.LEMMA -> lemmaCandidate ?: TextNormalizer.fold(candidate)
        }

    companion object {
        /** ⚑M-5 — off. See [minInClassScore]. */
        const val DEFAULT_MIN_IN_CLASS_SCORE: Double = 0.0

        /** ⚑M-4 — canonical length at or below which `typos` never fires. */
        const val SHORT_TERM_MAX_CHARS: Int = 3
    }
}
