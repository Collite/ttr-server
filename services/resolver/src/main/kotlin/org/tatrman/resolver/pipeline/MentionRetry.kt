// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.fuzzy.v1.SpanQuery
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.model.ResolverThresholds

/**
 * ttr-server#204 — **a mention whose phrase binds nothing is asked again as its head, and every word
 * that leaves it either restricts nothing or is reported.**
 *
 * The phrase builders fold a head noun's article and adjectives into its mention, and they should:
 * the mention is what the user said. The matcher holds the estate's words, though, and an English
 * mention carries an article almost every time. `the marketplace`, `the web`, `Total revenue` and
 * `What share` were each looked up as written and came back G1, while `marketplace`, `web`, `Revenue`
 * and `share` bind on their own.
 *
 * **What is asked.** [SpanProposal.retryOf] names the dependents a mention may be asked without: its
 * `det` dependents and its bare `amod` dependents. The mention is asked again as what is left.
 *
 * **What becomes of each word left out — the rule that keeps a restriction from vanishing.**
 *  - **Silent** — a closed, per-language list of words that restrict nothing, held as data
 *    (`frame-roles.conf`, `silent-modifiers`; [FrameRolePreps.isSilent]): articles, `what` / `which`,
 *    and adjectives that only say "all of it" (`total`, `celkové`). These may vanish. When every
 *    word left out is silent the binding keeps the mention's whole span: `Total revenue` is one
 *    mention, bound to revenue, with no gap.
 *  - **Residue** — every other determiner (`no`, `all`, `these`) and every other bare adjective
 *    (`promotional`, `akčních`). The head binds over its own words only (the head and the
 *    dependents it kept), and each residue becomes an unbound mention of its own over its own words
 *    ([DomainSpanCandidate.residue]): a G1 with no frame role, which a consumer must ask or compose
 *    about rather than answer the unrestricted number. `akčních tržeb` reads "revenue, and `akčních`
 *    not understood" — never "revenue".
 *  - **Known** — each left-out adjective and each residue determiner is also asked once, in the same
 *    batch, with no categories (the matcher's global index, every class). If any row that is not
 *    member data answers it at the bind floor, the word is the estate's (`largest` is an `op:top-n`
 *    trigger, `meziroční` a chrono trigger), and the retry is cancelled: nothing is left out, and the
 *    mention stays as it was. A slot the matcher did not answer counts as known. A silent determiner
 *    is never asked: an article names nothing.
 *
 * **Where it is asked**, because mentions are bound in two places:
 *  - **the broad pass, for a gated anchor phrase** — one more `SpanQuery`, scoped to the anchor's
 *    own categories, as a trailing slot of the same `BatchMatch`; its rows are read only when the
 *    phrase itself bound NOTHING (`GateSpans.gate`);
 *  - **the lookup round, for any mention still G1** — the leftover nouns `MentionLayer` proposes
 *    (`What share`, `the highest share`) are bound nowhere else. The round asks the phrase and, beside
 *    it, the head ([RoundPlanner]); the head's answer is admitted only if the phrase's was not
 *    (`LookupRounds`).
 *
 * A row reached through the head is stamped with [ALGORITHM_SUFFIX] on `provenance.method`, so the
 * binding's `producer.algorithm` says how it was reached. No wire change, no extra round trip.
 */
object MentionRetry {
    /** Appended to `provenance.method`, which becomes the binding's `producer.algorithm`. */
    const val ALGORITHM_SUFFIX: String = "+core"

    /** One mention's second lookup. */
    data class Retry(
        /** The mention, as proposed — the span its first lookup was about. */
        val mention: DomainSpanCandidate,
        /** The gated candidate this is, by its index in the broad pass; -1 for a mention only a round asks. */
        val candidateIndex: Int,
        /** What it is asked as: the phrase's words, less the ones left out. */
        val core: String,
        /** The words left out that the lexicon is asked about; a known one cancels this retry. */
        val asked: List<String>,
        /**
         * The mention the head binds as when a word left out must surface: the head and the
         * dependents it kept. Null when every word left out is silent — the mention keeps its span.
         */
        val narrowed: DomainSpanCandidate?,
        /** The words left out that are not silent, each an unbound mention of its own. */
        val residues: List<DomainSpanCandidate>,
    ) {
        val start: Int get() = mention.start
        val end: Int get() = mention.end

        /** The mention the head's binding is recorded on. */
        val bound: DomainSpanCandidate get() = narrowed ?: mention
    }

    data class Plan(
        val retries: List<Retry>,
        /** Every word some retry asks about, once each — one guard slot per word. */
        val asked: List<String>,
    ) {
        /** The retries the broad pass asks, in slot order. */
        val gated: List<Retry> get() = retries.filter { it.candidateIndex >= 0 }

        /** How many trailing slots [queries] appends. */
        val slotCount: Int get() = gated.size + asked.size

        companion object {
            val EMPTY = Plan(emptyList(), emptyList())
        }
    }

    /** A gated mention's second lookup: its rows, stamped, and the mention they bind as. */
    data class Second(
        val rows: List<FuzzyMatch>,
        val bindsAs: DomainSpanCandidate,
    )

    /** What the batch said about the plan: only the retries no known word cancelled. */
    data class Answers(
        /** For `GateSpans.gate`, by candidate index. */
        val byCandidate: Map<Int, Second>,
        /** For `LookupRounds`, by the mention's span. */
        val bySpan: Map<Pair<Int, Int>, Retry>,
    ) {
        companion object {
            val NONE = Answers(emptyMap(), emptyMap())
        }
    }

    /**
     * The retries worth planning: every MENTION — a gated anchor phrase in [candidates] or a leftover
     * in [ungated] — with something [SpanProposal.retryOf] may leave out. A phrase the estate declared
     * word for word is never one ([DomainSpanCandidate.declaredPhrase]). Empty without a dep parse.
     */
    fun plan(
        parse: AnalyzeResponse,
        candidates: List<DomainSpanCandidate>,
        ungated: List<DomainSpanCandidate>,
        preps: FrameRolePreps,
        lang: String,
    ): Plan {
        val tokens = parse.tokensList
        if (tokens.none { it.depHead > 0 }) return Plan.EMPTY

        fun silent(i: Int) = preps.isSilent(lang, tokens[i].text, tokens[i].lemma)

        fun retry(
            mention: DomainSpanCandidate,
            index: Int,
        ): Retry? {
            if (mention.origin != DomainSpanCandidate.Origin.ANCHOR_PHRASE || mention.declaredPhrase) return null
            if (mention.headToken !in tokens.indices) return null
            val phrase =
                tokens.indices.filter {
                    tokens[it].charStart >= mention.start &&
                        tokens[it].charEnd <= mention.end
                }
            val left = SpanProposal.retryOf(phrase, mention.headToken, tokens) ?: return null
            // A dependent is silent when its own word is on the list and nothing hangs from it.
            val (quiet, loud) = left.dropped.partition { it.tokens.size == 1 && silent(it.root) }
            val narrowed =
                if (loud.isEmpty()) {
                    null
                } else {
                    // The head's own words must be one run: a residue inside it would be a word the
                    // binding claims and the gap reports at once. Not met by any parse seen; refused.
                    val run = left.core.min()..left.core.max()
                    if (loud.any { d -> d.tokens.any { it in run } }) return null
                    candidateOver(mention, left.core, tokens)
                }
            return Retry(
                mention = mention,
                candidateIndex = index,
                core = SpanProposal.surface(left.core, tokens),
                asked =
                    (loud.map { it.root } + quiet.filterNot { it.determiner }.map { it.root })
                        .sorted()
                        .map { tokens[it].text },
                narrowed = narrowed,
                residues = loud.map { residueOver(it.tokens, it.root, tokens) },
            )
        }

        val retries = candidates.mapIndexedNotNull { i, c -> retry(c, i) } + ungated.mapNotNull { retry(it, -1) }
        return Plan(retries, retries.flatMap { it.asked }.distinct())
    }

    /** The trailing slots: each gated retry in its own scope, then each asked word in none (global). */
    fun queries(
        plan: Plan,
        perSpanLimit: Int,
    ): List<SpanQuery> =
        plan.gated.map { r ->
            SpanQuery
                .newBuilder()
                .setQuery(r.core)
                .addAllCategories(r.mention.categories)
                .setLimit(perSpanLimit)
                .build()
        } +
            plan.asked.map { word ->
                SpanQuery
                    .newBuilder()
                    .setQuery(word)
                    .setLimit(perSpanLimit)
                    .build()
            }

    /** The plan's answers, read from [response]'s slots from [offset] on — where [queries] put them. */
    fun collect(
        response: BatchMatchResponse,
        plan: Plan,
        offset: Int,
        thresholds: ResolverThresholds,
    ): Answers {
        if (plan.retries.isEmpty()) return Answers.NONE
        val results = response.resultsList
        val gated = plan.gated
        val known =
            plan.asked
                .filterIndexed { k, _ ->
                    // Unanswered is KNOWN: a retry is a bet on what the left-out words mean, and a
                    // matcher that did not answer has not covered it.
                    val slot = results.getOrNull(offset + gated.size + k) ?: return@filterIndexed true
                    slot.matchesList.any { it.source != SourceTag.MEMBER && it.score >= thresholds.bind }
                }.toSet()
        val cleared = plan.retries.filter { r -> r.asked.none { it in known } }
        val byCandidate =
            gated
                .withIndex()
                .filter { (_, r) -> r in cleared }
                .associate { (k, r) ->
                    r.candidateIndex to
                        Second(
                            results
                                .getOrNull(offset + k)
                                ?.matchesList
                                .orEmpty()
                                .map(::stamp),
                            r.bound,
                        )
                }
        return Answers(byCandidate, cleared.associateBy { it.start to it.end })
    }

    /**
     * The residues of the gated retries the broad pass bound — the head's binding now recorded on
     * [Retry.narrowed]. Each is a mention of its own, unbound, for the lattice and the rounds.
     */
    fun residuesBound(
        gated: List<GatedSpan>,
        answers: Answers,
    ): List<DomainSpanCandidate> =
        answers.bySpan.values
            .filter { r ->
                r.narrowed != null && gated.any { it.candidate === r.narrowed && it.contenders.isNotEmpty() }
            }.flatMap { it.residues }

    /** [response] without the slots from [offset] on, so every positional reader sees what it always did. */
    fun strip(
        response: BatchMatchResponse,
        offset: Int,
    ): BatchMatchResponse =
        if (response.resultsCount <= offset) {
            response
        } else {
            response
                .toBuilder()
                .clearResults()
                .addAllResults(response.resultsList.take(offset))
                .build()
        }

    /** A row reached through the head, so marked: see the class doc. */
    fun stamp(match: FuzzyMatch): FuzzyMatch {
        val method = match.provenance.method.ifBlank { "TATRMAN" }
        if (method.endsWith(ALGORITHM_SUFFIX)) return match
        return match
            .toBuilder()
            .setProvenance(match.provenance.toBuilder().setMethod(method + ALGORITHM_SUFFIX))
            .build()
    }

    /** [mention], over [indices] only: same scope, same head, same slot, fewer words. */
    private fun candidateOver(
        mention: DomainSpanCandidate,
        indices: List<Int>,
        tokens: List<Token>,
    ): DomainSpanCandidate =
        mention.copy(
            text = SpanProposal.surface(indices, tokens),
            start = indices.minOf { tokens[it].charStart },
            end = indices.maxOf { tokens[it].charEnd },
        )

    /** A word left out that restricts the mention, as an unbound mention of its own. */
    private fun residueOver(
        indices: List<Int>,
        root: Int,
        tokens: List<Token>,
    ): DomainSpanCandidate =
        DomainSpanCandidate(
            text = SpanProposal.surface(indices, tokens),
            start = indices.minOf { tokens[it].charStart },
            end = indices.maxOf { tokens[it].charEnd },
            gatedEntityRefs = emptyList(),
            categories = emptyList(),
            anchored = false,
            origin = DomainSpanCandidate.Origin.ANCHOR_PHRASE,
            headToken = root,
            lemma = tokens[root].lemma.ifBlank { tokens[root].text },
            residue = true,
        )
}
