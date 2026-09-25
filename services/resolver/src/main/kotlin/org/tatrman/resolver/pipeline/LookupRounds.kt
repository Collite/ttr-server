// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.LookupRequest
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.model.kindsByRef
import org.tatrman.resolver.model.memberEntityByCategory
import org.tatrman.resolver.model.ownersByRef
import org.tatrman.resolver.model.reachByRef
import org.tatrman.resolver.v1.Hypothesis
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.RungLogEntry
import org.tatrman.resolver.v1.Span

/**
 * The wall-clock budget and fan-out bounds of the `lookup` rung (RV-P2.3.T4).
 *
 * Budget is wall-clock **only**: lookup rounds are deterministic and never count as LLM
 * invocations (⚑RV-3), so the ladder's `max_llm_invocations` has nothing to say about them.
 * [budgetMs] defaults to contracts §3's ruled `rung_timeouts_ms.lookup`.
 *
 * There is no round cap, and that is not an oversight — the loop is finite by construction. Every
 * round consumes at least one member of a finite query space (spans × tiers, via the planner's
 * asked-set), so it terminates whether or not the clock does. Termination is therefore structural
 * and the budget is what bounds *latency* — but only because each round's calls carry what is LEFT
 * of it as their own timeout. Checking the clock between rounds is not enough on its own: the
 * matcher stub's deadline is 30s (`fuzzy.deadline-seconds`), so a single hung round would otherwise
 * add two orders of magnitude more than [budgetMs] to a resolve. See [LookupRounds.lookup].
 */
data class LookupRoundConfig(
    val budgetMs: Long = 250,
    val maxCandidates: Int = 10,
    val broadMaxCandidates: Int = 10,
    val maxQueriesPerRound: Int = 8,
) {
    companion object {
        val DEFAULT = LookupRoundConfig()

        /** Rounds off. What an estate gets before the P3 delivery chain gives it anything to narrow with. */
        val DISABLED = LookupRoundConfig(budgetMs = 0)
    }
}

/**
 * RV-P2.3.T3/T4 — **the deterministic narrowing loop**: plan, ask, gate, re-assemble, repeat.
 *
 * This is RV-33's `lookup` rung and the cheapest one there is — no LLM, no cascade, one bounded
 * question per span the planner thought was worth asking about again. It runs after the broad pass
 * and before emit, and everything it learns re-enters through the *same* [Binder] the broad pass
 * used. A round is a proposer (RV-7); it cannot bind, it can only offer candidates to the gate.
 *
 * **Why N `Lookup` calls and not one batch — a ruling this list had to make.** The p2-3 task list
 * says "one gRPC batch per round"; contracts §1 addendum, frozen at P1.4 T5 (three days after that
 * list was written), says `Lookup` **is** the lookup-round contract, and its proto comment says so
 * in as many words. They cannot both be honoured, because `Lookup` is single-term and there is no
 * `BatchLookup`. Contracts wins, and not merely because it is normative and later: the planner's
 * output is `(span, categories, class scope, method?)`, and `SpanQuery` can express **none** of the
 * last two — a `BatchMatch` round would be the broad pass again under a different name, which is
 * exactly the thing the rung exists to improve on. B-T1's "never per-span RPCs" was aimed at the
 * broad pass fanning out over every span × category; a round is bounded by
 * [LookupRoundConfig.maxQueriesPerRound] and asks only about spans the lattice reports as empty.
 * The calls in a round are issued concurrently, so a round costs one round-trip, not N.
 *
 * **Monotonicity (RV-9) is structural, not checked.** The planner only ever targets spans the
 * lattice reports as having *nothing* — an unattributed value, an unbound mention — so a round has
 * nothing to displace. Results are appended, never merged-and-re-decided, which is the other way
 * this could have gone wrong: re-running the gate over the union of old and new candidates would
 * let a strong newcomer evict an existing binding, and that is a removal however it is spelled.
 * Candidates the gate refuses go to the round's log, never to the lattice (T5).
 */
class LookupRounds(
    private val fuzzy: FuzzyClient,
    // Public so the re-gate tool can borrow the rung's own candidate cap: a hypothesis is one more
    // bounded question about one span, and it should not be able to ask for more than a round can.
    val config: LookupRoundConfig = LookupRoundConfig.DEFAULT,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    /** The rung name a round writes into the log (contracts §3 vocabulary). */
    companion object {
        const val RUNG: String = "lookup"

        private val log = LoggerFactory.getLogger(LookupRounds::class.java)
    }

    data class Result(
        val gated: List<GatedSpan>,
        val ungated: List<GatedSpan>,
        val lattice: ResolutionState,
        val log: List<RungLogEntry>,
    )

    suspend fun run(
        lattice: ResolutionState,
        gated: List<GatedSpan>,
        ungated: List<GatedSpan>,
        entityTypes: List<ResolverEntityType>,
        thresholds: ResolverThresholds,
        reassemble: (List<GatedSpan>, List<GatedSpan>) -> ResolutionState,
    ): Result {
        if (config.budgetMs <= 0) return Result(gated, ungated, lattice, emptyList())

        val deadline = nanoTime() + config.budgetMs * 1_000_000
        val asked = mutableSetOf<String>()
        val log = mutableListOf<RungLogEntry>()
        // Accumulated across rounds, keyed by span: what has been tried on each still-open
        // question. Gaps are recomputed from scratch every re-assembly, so this is carried here
        // and re-attached — see [withHypotheses].
        // MS-P3·S2 — the same declared containment the broad pass gated with; a round that
        // clarified what the broad pass bound would be a second selection rule (contracts §8.3).
        val owners = entityTypes.ownersByRef()
        // MH: built once here, like `owners` — plan risk 6 is a defaulted parameter silently
        // skipping one of the three producers, and the way it is avoided is that all three read
        // the registry through the same three helpers.
        val kinds = entityTypes.kindsByRef()
        val reach = entityTypes.reachByRef()
        val memberOwners = entityTypes.memberEntityByCategory()
        val tried = mutableMapOf<Pair<Int, Int>, List<Hypothesis>>()
        var currentGated = gated
        var currentUngated = ungated
        var currentLattice = lattice
        var round = 0

        while (true) {
            // What is LEFT of the budget, and it is handed to the round rather than merely checked
            // before it — the round is what spends it (see [lookup]).
            val remainingMs = (deadline - nanoTime()) / 1_000_000
            if (remainingMs <= 0) break
            val queries = RoundPlanner.plan(currentLattice, entityTypes, asked, config)
            if (queries.isEmpty()) break
            asked += queries.map { it.key }
            round++

            val startedAt = nanoTime()
            val answers = ask(queries, remainingMs)
            var added = 0
            val proposed = mutableListOf<Hypothesis>()
            for ((query, candidates) in answers) {
                val span = spanFor(query, currentGated, currentUngated) ?: continue
                // The same gate the broad pass used. A round is a proposer; this is where its
                // proposals stop being proposals (RV-7).
                val verdict = Binder.gate(candidates, span.candidate, thresholds, owners, kinds, reach, memberOwners)
                // What the gate refused, recorded where a refusal belongs. `RungLogEntry.hypotheses`
                // is contracts' "what it PROPOSED (never what it bound)", and a WEAK candidate is
                // exactly that: something the round offered and the binder declined. It goes in the
                // log, never in the lattice (T5) — a lattice that carried its own rejects would be
                // inviting a reader to overrule the gate.
                val rejects = verdict.rejected.map { hypothesisOf(query, it) }
                proposed += rejects
                tried.merge(query.spanStart to query.spanEnd, rejects) { a, b -> a + b }
                if (verdict.admitted.isEmpty()) continue
                val merged = merge(span, verdict)
                added += verdict.admitted.size
                currentGated = replace(currentGated, span, merged)
                currentUngated = replace(currentUngated, span, merged)
            }

            currentLattice = withHypotheses(reassemble(currentGated, currentUngated), tried)
            log +=
                RungLogEntry
                    .newBuilder()
                    .setRound(round)
                    .setRung(RUNG)
                    .setAction("lookup")
                    .addAllMentionIds(mentionIdsFor(answers.keys, currentLattice))
                    .addAllValueIds(valueIdsFor(answers.keys, currentLattice))
                    .addAllHypotheses(proposed)
                    .setBindingsAdded(added)
                    .setGapsOpen(currentLattice.gapsCount)
                    .setElapsedMs((nanoTime() - startedAt) / 1_000_000)
                    .build()
        }
        return Result(currentGated, currentUngated, currentLattice, log)
    }

    /** One round's questions, concurrently — a round costs one round-trip, not one per span. */
    private suspend fun ask(
        queries: List<RoundPlanner.Query>,
        remainingMs: Long,
    ): Map<RoundPlanner.Query, List<FuzzyMatch>> =
        coroutineScope {
            queries
                .map { query -> query to async { lookup(query, remainingMs) } }
                .associate { (query, deferred) -> query to deferred.await() }
        }

    /**
     * One narrowing question, bounded and never silently mistaken for an answer.
     *
     * Two things this does NOT do, each of which it used to:
     *
     *  - **It does not let a slow matcher outlive the budget.** [remainingMs] is what is left of
     *    [LookupRoundConfig.budgetMs]; without it the only bound is the stub's own 30s deadline, and
     *    a rung advertised at 250ms could add 30s to a resolve.
     *  - **It does not swallow cancellation.** `runCatching` catches [CancellationException] along
     *    with everything else, which would turn a caller who has hung up into a loop that keeps
     *    planning rounds for them. It is rethrown, and only a real failure is absorbed.
     *
     * A failure absorbs to "no candidates" because a round has no other move — but it is LOGGED, and
     * that distinction matters more one rung up: the same failure reaching [ReGate] is reported to
     * the proposer as `LOOKUP_FAILED`, never as the claim that the vocabulary has no such term.
     */
    private suspend fun lookup(
        query: RoundPlanner.Query,
        remainingMs: Long,
    ): List<FuzzyMatch> =
        try {
            val response = withTimeoutOrNull(remainingMs) { fuzzy.lookup(requestOf(query)) }
            if (response == null) {
                log.warn(
                    "lookup round exhausted its {}ms budget term={} tier={} — round abandoned, gap left open",
                    remainingMs,
                    query.term,
                    query.tier,
                )
                emptyList()
            } else {
                response.candidatesList
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(
                "lookup round failed term={} tier={} — treated as NO ANSWER, not as an empty vocabulary",
                query.term,
                query.tier,
                e,
            )
            emptyList()
        }

    private fun requestOf(query: RoundPlanner.Query): LookupRequest {
        val builder =
            LookupRequest
                .newBuilder()
                .setTerm(query.term)
                .addAllCategories(query.categories)
                .addAllTargetClasses(query.targetClasses)
                .setMaxCandidates(query.maxCandidates)
        query.methodOverride?.let { builder.methodOverride = it }
        return builder.build()
    }

    private fun spanFor(
        query: RoundPlanner.Query,
        gated: List<GatedSpan>,
        ungated: List<GatedSpan>,
    ): GatedSpan? =
        (gated + ungated).firstOrNull {
            it.candidate.start == query.spanStart && it.candidate.end == query.spanEnd
        }

    /**
     * Append, never re-decide. A candidate whose identity is already on the span is the same answer
     * reached twice — it confirms, it does not duplicate (RV-9's "add or confirm").
     */
    private fun merge(
        span: GatedSpan,
        verdict: Binder.Verdict,
    ): GatedSpan {
        val known = span.contenders.map { Binder.identityKey(it.match) }.toSet()
        val additions = verdict.admitted.filter { Binder.identityKey(it.match) !in known }
        if (additions.isEmpty()) return span
        val contenders = span.contenders + additions
        return span.copy(
            contenders = contenders,
            // A span the broad pass left empty is ambiguous exactly when the round that filled it
            // found several identities; one that already had a binding is untouched by the guard
            // above, because the planner never asks about it.
            ambiguous = span.ambiguous || (span.contenders.isEmpty() && verdict is Binder.Ambiguous),
        )
    }

    private fun replace(
        spans: List<GatedSpan>,
        old: GatedSpan,
        new: GatedSpan,
    ): List<GatedSpan> = spans.map { if (it === old) new else it }

    private fun hypothesisOf(
        query: RoundPlanner.Query,
        rejected: Binder.ClassedMatch,
    ): Hypothesis =
        Hypothesis
            .newBuilder()
            .setSpan(
                Span
                    .newBuilder()
                    .setStart(query.spanStart)
                    .setEnd(query.spanEnd)
                    .setText(query.term),
            ).setRef(Binder.identityKey(rejected.match))
            .setProposingRung(RUNG)
            .build()

    /**
     * Re-attach what has been tried to the gaps that are still open — discharging the ⚑ P2.1.T6
     * left on `GapRecord.hypotheses_tried` ("filled by later rung activity — empty from the pure
     * core pass"). This IS that later activity.
     *
     * It has to be re-attached rather than accumulated in place because [Gaps] recomputes the whole
     * gap list from the finished annotation every round, which is the right design — what a gap IS
     * depends on the roles and the anchor, and both move as rounds bind things — but it means a gap
     * record has no memory of its own. The loop keeps that memory instead.
     *
     * A gap that CLOSED simply takes its history with it: there is nothing left to escalate about.
     */
    private fun withHypotheses(
        lattice: ResolutionState,
        tried: Map<Pair<Int, Int>, List<Hypothesis>>,
    ): ResolutionState {
        if (tried.isEmpty()) return lattice
        val builder = lattice.toBuilder()
        lattice.gapsList.forEachIndexed { i, gap ->
            val hypotheses = tried[gap.span.start to gap.span.end] ?: return@forEachIndexed
            builder.setGaps(i, gap.toBuilder().addAllHypothesesTried(hypotheses))
        }
        return builder.build()
    }

    private fun mentionIdsFor(
        queries: Collection<RoundPlanner.Query>,
        lattice: ResolutionState,
    ): List<String> =
        lattice.mentionsList
            .filter { m -> queries.any { it.spanStart == m.span.start && it.spanEnd == m.span.end } }
            .map { it.id }

    private fun valueIdsFor(
        queries: Collection<RoundPlanner.Query>,
        lattice: ResolutionState,
    ): List<String> =
        lattice.valuesList
            .filter { v -> queries.any { it.spanStart == v.span.start && it.spanEnd == v.span.end } }
            .map { it.id }
}
