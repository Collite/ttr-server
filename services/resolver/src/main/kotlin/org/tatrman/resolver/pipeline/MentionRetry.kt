// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.fuzzy.v1.SpanQuery
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.resolver.model.ResolverThresholds

/**
 * ttr-server#204 — **a mention whose phrase binds nothing is asked again without its determiners and
 * the adjectives the lexicon does not know.**
 *
 * The phrase builders fold a head noun's article and adjectives into its mention, and they should:
 * the mention is what the user said. The matcher holds the estate's words, though, and an English
 * mention carries an article almost every time. `the marketplace`, `the web`, `Total revenue` and
 * `What share` were each looked up as written and came back G1, while `marketplace`, `web`, `Revenue`
 * and `share` bind on their own. The follow-up *And on the web?* could not read as a change of view,
 * and *What share of …* lost its ratio operator.
 *
 * **What is asked, and when.** [SpanProposal.retryOf] names the words a mention may be asked without:
 * its `det` dependents, and its bare `amod` dependents. Two places ask, because mentions are bound in
 * two places:
 *  - **The broad pass, for a gated anchor phrase.** One more `SpanQuery` with the head's words, scoped
 *    to the anchor's own categories — the scope the phrase had — as a trailing slot of the same
 *    `BatchMatch`. Its rows are read only when the phrase itself bound NOTHING (`GateSpans.gate`): a
 *    phrase that bound, or that is ambiguous on its own, keeps its answer.
 *  - **The lookup round, for any mention still G1** — the leftover nouns `MentionLayer` proposes
 *    (`What share`, `the highest share`) are bound nowhere else. The round asks the phrase and, beside
 *    it, the head ([RoundPlanner]), and the head's answer is admitted only if the phrase's was not
 *    (`LookupRounds`).
 *
 * **A modifier the lexicon knows is never left out.** Each adjective a retry would drop is asked once,
 * in the same batch, with no categories — the matcher's global index, every class. If any row that is
 * not member data answers it at the bind floor, the word is the estate's (`largest` is an `op:top-n`
 * trigger, `meziroční` a chrono trigger, `měsíční` one edit from the declared `měsíčně`), and every
 * retry that would drop it is cancelled: the mention stays as it was, a G1 if it was one. A slot the
 * matcher did not answer counts as known. Determiners need no such question — they never name
 * anything.
 *
 * **The span is the user's.** A retried mention keeps its folded extent, so `Total` stays inside
 * `Total revenue` and never becomes a mention, a value or a filter of its own; the clarification
 * text and the binding's `raw_text` are what the user wrote. The row is stamped with
 * [ALGORITHM_SUFFIX] on `provenance.method`, so the binding's `producer.algorithm` says how it was
 * reached. Its score and its evidence class are the row's own: the head's words are words the user
 * wrote, unlike a dictionary form (`LemmaLookup`).
 *
 * No wire change, no config, and no extra round trip: the broad-pass slots ride the one `BatchMatch`,
 * and the round's second question rides the round that asks the first.
 */
object MentionRetry {
    /** Appended to `provenance.method`, which becomes the binding's `producer.algorithm`. */
    const val ALGORITHM_SUFFIX: String = "+core"

    /** One mention's second lookup. */
    data class Retry(
        val start: Int,
        val end: Int,
        /** The gated candidate this is, by its index in the broad pass; -1 for a mention only a round asks. */
        val candidateIndex: Int,
        /** What it is asked as: the phrase's words, less the ones left out. */
        val core: String,
        /** The scope its first lookup had — the anchor's categories; empty for a leftover mention. */
        val categories: List<String>,
        /** The adjectives left out, as written. A known one cancels this retry. */
        val modifiers: List<String>,
    )

    data class Plan(
        val retries: List<Retry>,
        /** Every adjective some retry leaves out, once each — one guard slot per word. */
        val modifiers: List<String>,
    ) {
        /** The retries the broad pass asks, in slot order. */
        val gated: List<Retry> get() = retries.filter { it.candidateIndex >= 0 }

        /** How many trailing slots [queries] appends. */
        val slotCount: Int get() = gated.size + modifiers.size

        companion object {
            val EMPTY = Plan(emptyList(), emptyList())
        }
    }

    /** What the batch said about the plan. */
    data class Answers(
        /** A gated mention's second lookup, stamped, by candidate index — for `GateSpans.gate`. */
        val byCandidate: Map<Int, List<FuzzyMatch>>,
        /** What a lookup round may ask a still-unbound mention as, by its span — for `LookupRounds`. */
        val bySpan: Map<Pair<Int, Int>, String>,
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
    ): Plan {
        val tokens = parse.tokensList
        if (tokens.none { it.depHead > 0 }) return Plan.EMPTY

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
            return Retry(
                start = mention.start,
                end = mention.end,
                candidateIndex = index,
                core = SpanProposal.surface(left.core, tokens),
                categories = mention.categories,
                modifiers = left.modifiers.map { tokens[it].text },
            )
        }

        val retries = candidates.mapIndexedNotNull { i, c -> retry(c, i) } + ungated.mapNotNull { retry(it, -1) }
        return Plan(retries, retries.flatMap { it.modifiers }.distinct())
    }

    /** The trailing slots: each gated retry in its own scope, then each modifier in none (global). */
    fun queries(
        plan: Plan,
        perSpanLimit: Int,
    ): List<SpanQuery> =
        plan.gated.map { r ->
            SpanQuery
                .newBuilder()
                .setQuery(r.core)
                .addAllCategories(r.categories)
                .setLimit(perSpanLimit)
                .build()
        } +
            plan.modifiers.map { word ->
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
            plan.modifiers
                .filterIndexed { k, _ ->
                    // Unanswered is KNOWN: a retry is a bet that the dropped words mean nothing, and a
                    // matcher that did not say so has not covered it.
                    val slot = results.getOrNull(offset + gated.size + k) ?: return@filterIndexed true
                    slot.matchesList.any { it.source != SourceTag.MEMBER && it.score >= thresholds.bind }
                }.toSet()
        val cleared = plan.retries.filter { r -> r.modifiers.none { it in known } }
        val byCandidate =
            gated
                .withIndex()
                .filter { (_, r) -> r in cleared }
                .associate { (k, r) ->
                    r.candidateIndex to
                        results
                            .getOrNull(offset + k)
                            ?.matchesList
                            .orEmpty()
                            .map(::stamp)
                }
        return Answers(byCandidate, cleared.associate { (it.start to it.end) to it.core })
    }

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
}
