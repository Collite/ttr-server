// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.LookupRequest
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.model.kindsByRef
import org.tatrman.resolver.model.ownersByRef
import org.tatrman.resolver.model.reachByRef
import org.tatrman.resolver.model.refByCategory
import org.tatrman.resolver.v1.Attribution
import org.tatrman.resolver.v1.Binding
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.GapRecord
import org.tatrman.resolver.v1.GateRequest
import org.tatrman.resolver.v1.GateResponse
import org.tatrman.resolver.v1.Hypothesis
import org.tatrman.resolver.v1.HypothesisOutcome
import org.tatrman.resolver.v1.Mention
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.RungLogEntry
import org.tatrman.resolver.v1.Span
import org.tatrman.resolver.v1.ValueKind
import org.tatrman.resolver.v1.ValueFinding

/**
 * RV-P2.4 — **`resolve.gate:v1`**: the re-gate sibling tool (Q-13 RULED = B, ⚑RV-1).
 *
 * An LLM rung reads a lattice, forms a hypothesis — *"that `5010O1` is a typo for `501001`"* — and
 * hands it back. This is where it comes back. **Nothing here decides anything.** Every hypothesis
 * is turned into a lexicon question, the answer goes through the same [Binder] the broad pass and
 * the lookup rounds use, and only what survives becomes a binding. That is RV-7's
 * proposer-not-binder made structural rather than promised: there is no path through this file
 * that produces a `Binding` without the gate's consent, and `SingleBinderTest` greps for exactly
 * that.
 *
 * **The handler contains no matching logic, deliberately** (T3). It routes:
 *
 *  - a **correction** (`5010O1` → `501001`) re-asks the span's lookup with the corrected surface;
 *  - a **ref proposal** scopes that lookup to the proposed ref's own category — in the compiled
 *    lexicon a target ref IS the category key, so this needs no registry round trip — and then
 *    requires the winner to actually point at the proposed ref. A hypothesis that names a target
 *    and gets a different one back has not been confirmed, it has been contradicted;
 *  - an **attribution** with neither falls back to the anchoring already in the lattice, which is
 *    the same scope [RoundPlanner]'s first tier uses.
 *
 * If a fourth kind is ever wanted, it belongs in the components this orchestrates, not here.
 *
 * **Stateless** (T4). The request carries the lattice exactly as `Resolve` carries the text, so
 * the service holds no session and gating the same batch twice is the same call twice — which is
 * what the Golem loop's retry semantics need, and why the idempotency test is a real test rather
 * than a formality.
 */
object ReGate {
    /** The rung name this tool writes; the caller's proposing rung rides on each hypothesis. */
    const val ACTION: String = "regate"

    private val log = LoggerFactory.getLogger(ReGate::class.java)

    /** Why a hypothesis did not become a binding. Machine-readable; see `HypothesisOutcome.reason`. */
    object Reason {
        /** The lattice has no span at those offsets — the caller is gating against a stale lattice. */
        const val NO_SPAN = "NO_SPAN"

        /** The vocabulary has no such term at all. The H2 pre-learning case, and a correct answer. */
        const val NO_CANDIDATE = "NO_CANDIDATE"

        /** Found, and refused by the RV-14 class floor. */
        const val WEAK = "WEAK"

        /** Several distinct identities — refuse over guess (RS-26) rather than take the hypothesis' word. */
        const val AMBIGUOUS = "AMBIGUOUS"

        /** A candidate bound, but not the one the hypothesis proposed. Contradicted, not confirmed. */
        const val REF_MISMATCH = "REF_MISMATCH"

        /**
         * The matcher could not be ASKED — it failed or timed out. Distinct from [NO_CANDIDATE] on
         * purpose, and the distinction is the whole reason this reason exists: `NO_CANDIDATE` is a
         * claim about the estate's vocabulary, and telling a rung "there is no such term" when the
         * truth is "we could not check" teaches it to stop proposing something that was right. An
         * infrastructure failure must never be reported as a semantic verdict.
         */
        const val LOOKUP_FAILED = "LOOKUP_FAILED"

        /**
         * LP contracts §2.4 — the span is a quoted literal, and a literal is closed to rungs.
         *
         * Not a verdict about the vocabulary and deliberately not [NO_CANDIDATE]: the matcher was
         * never asked, because the user already said what this span means. An LLM rung that reads
         * `"Pelex"` and proposes the store *Pelex Slovakia* is doing its job — it just may not be
         * allowed to win, and telling it "no such term" would be a lie about the estate.
         */
        const val VERBATIM_SPAN = "E_VERBATIM_SPAN"
    }

    /**
     * What the lexicon said about ONE hypothesis. Three outcomes, kept apart because two of them
     * used to collapse into an empty list and one of those was a lie — see [Reason.LOOKUP_FAILED].
     */
    private sealed interface Answer {
        /** The lattice has no span at the hypothesis' offsets, so nothing was asked. */
        object NoSpan : Answer

        /** The matcher answered. An EMPTY list is a real answer: the vocabulary has no such term. */
        data class Answered(
            val candidates: List<FuzzyMatch>,
        ) : Answer

        /** The matcher could not be asked. NOT an empty vocabulary. */
        object Failed : Answer

        /** The span is (or touches) a VERBATIM value, so nothing was asked and nothing may bind. */
        data class Verbatim(
            val valueId: String,
        ) : Answer
    }

    suspend fun run(
        request: GateRequest,
        fuzzy: FuzzyClient,
        entityTypes: List<ResolverEntityType>,
        thresholds: ResolverThresholds,
        snapshotHash: String,
        maxCandidates: Int,
        maxConcurrentLookups: Int,
    ): GateResponse {
        val lattice = request.lattice
        // Keyed by span offsets, and a MENTION wins a collision — the same precedence the gating
        // loop below uses when it decides which layer a surviving binding lands on. Stated once
        // here rather than implied twice.
        val mentions = lattice.mentionsList.associateBy { it.span.start to it.span.end }
        val values = lattice.valuesList.associateBy { it.span.start to it.span.end }
        // LP §2.4 — keyed by OVERLAP, not by exact offsets, which is the difference between a rule
        // and a formality. A rung reading the lattice proposes `Pelex` (33–38), the content; the
        // VERBATIM value is `"Pelex"` (32–39), the delimiters included. On exact keys that
        // hypothesis finds no span and is refused as NO_SPAN — right outcome, wrong reason, and a
        // reason a rung is entitled to act on.
        val verbatimValues = lattice.valuesList.filter { it.kind == ValueKind.VALUE_KIND_VERBATIM }

        fun verbatimAt(span: Span) = verbatimValues.firstOrNull { span.start < it.span.end && span.end > it.span.start }
        val categoriesByRef = entityTypes.associate { it.ref to it.categories }
        // MS-P3·S2 — see GateSpans: every producer gates through the same containment map.
        val owners = entityTypes.ownersByRef()
        // MH: built once here, like `owners` — plan risk 6 is a defaulted parameter silently
        // skipping one of the three producers, and the way it is avoided is that all three read
        // the registry through the same three helpers.
        val kinds = entityTypes.kindsByRef()
        val reach = entityTypes.reachByRef()
        val memberOwners = entityTypes.refByCategory()
        // `Gate` is a public rpc and `hypotheses` is an unbounded repeated field, so the fan-out has
        // to be bounded by this service rather than by the caller's good manners: without this a
        // single request opens one concurrent matcher RPC per hypothesis, each with a 30s deadline.
        // The bound is the lookup rung's own per-round cap, for the same reason this tool already
        // borrows its `max_candidates`: a hypothesis is one more bounded question about one span.
        val inFlight = Semaphore(maxConcurrentLookups.coerceAtLeast(1))

        val verified =
            coroutineScope {
                request.hypothesesList
                    .map { hypothesis ->
                        val key = hypothesis.span.start to hypothesis.span.end
                        val latticeText = mentions[key]?.span?.text ?: values[key]?.span?.text
                        val categories = scopeFor(hypothesis, values[key], lattice, categoriesByRef)
                        val verbatim = verbatimAt(hypothesis.span)
                        hypothesis to
                            async {
                                when {
                                    // FIRST, and before the matcher is reached: a literal costs no
                                    // RPC to refuse, and refusing it after asking would make the
                                    // rung's proposal look like a near miss.
                                    verbatim != null -> Answer.Verbatim(verbatim.id)
                                    latticeText == null -> Answer.NoSpan
                                    else ->
                                        inFlight.withPermit {
                                            lookup(fuzzy, hypothesis, latticeText, categories, maxCandidates)
                                        }
                                }
                            }
                    }.map { (hypothesis, deferred) -> hypothesis to deferred.await() }
            }

        val outcomes = mutableListOf<HypothesisOutcome>()
        val bindings = mutableListOf<Binding>()
        // Applied to a COPY of the caller's lattice, so `updated_gaps` describes the world after
        // gating without this tool ever having mutated anything the caller still holds.
        val mentionBindings = mutableMapOf<Pair<Int, Int>, MutableList<Binding>>()
        val valueBindings = mutableMapOf<Pair<Int, Int>, MutableList<Binding>>()
        // "What this round touched" (`RungLogEntry.mention_ids` / `value_ids`): every span this call
        // actually asked about, whether or not the answer bound anything. Ordered sets so the entry
        // stays a pure function of the request (T4).
        val touchedMentions = linkedSetOf<String>()
        val touchedValues = linkedSetOf<String>()

        for ((hypothesis, answer) in verified) {
            val key = hypothesis.span.start to hypothesis.span.end
            val mention = mentions[key]
            val value = values[key]
            when {
                mention != null -> touchedMentions += mention.id
                value != null -> touchedValues += value.id
            }
            val candidates =
                when (answer) {
                    is Answer.NoSpan -> {
                        outcomes += outcome(hypothesis, Reason.NO_SPAN)
                        continue
                    }
                    is Answer.Failed -> {
                        outcomes += outcome(hypothesis, Reason.LOOKUP_FAILED)
                        continue
                    }
                    is Answer.Verbatim -> {
                        // Recorded as touched: the round DID consider this value, and a rung log
                        // that omitted it would read as though the hypothesis had never arrived.
                        touchedValues += answer.valueId
                        outcomes += outcome(hypothesis, Reason.VERBATIM_SPAN)
                        continue
                    }
                    is Answer.Answered -> answer.candidates
                }
            if (candidates.isEmpty()) {
                outcomes += outcome(hypothesis, Reason.NO_CANDIDATE)
                continue
            }

            // A mention is its own anchor phrase; a value is anchored exactly when the lattice
            // already said so. The hypothesis' own confidence is NOT evidence of anchoring — a
            // proposer that could talk itself into a higher evidence class is a proposer that binds.
            val anchored = mention != null || (value?.anchorMentionId?.isNotBlank() == true)
            val verdict =
                Binder.gate(
                    candidates,
                    anchorCandidate(hypothesis, anchored),
                    thresholds,
                    owners,
                    kinds,
                    reach,
                    memberOwners,
                )
            when {
                verdict is Binder.Ambiguous -> outcomes += outcome(hypothesis, Reason.AMBIGUOUS)
                verdict is Binder.Bind -> {
                    val binding = Bindings.of(verdict.winner, snapshotHash).withRung(hypothesis.proposingRung)
                    if (hypothesis.ref.isNotBlank() && !confirms(binding.ref, hypothesis.ref)) {
                        outcomes += outcome(hypothesis, Reason.REF_MISMATCH)
                    } else {
                        bindings += binding
                        outcomes += accepted(hypothesis, binding)
                        if (mention != null) {
                            mentionBindings.getOrPut(key) { mutableListOf() } += binding
                        } else {
                            valueBindings.getOrPut(key) { mutableListOf() } += binding
                        }
                    }
                }
                // NoBind with something rejected = the class floor refused it; with nothing
                // rejected the lookup answered and every row fell below the matcher's own floor.
                else ->
                    outcomes +=
                        outcome(hypothesis, if (verdict.rejected.isEmpty()) Reason.NO_CANDIDATE else Reason.WEAK)
            }
        }

        val updatedMentions = lattice.mentionsList.map { it.plus(mentionBindings[it.span.start to it.span.end]) }
        val updatedValues = lattice.valuesList.map { it.plus(valueBindings[it.span.start to it.span.end]) }
        val gaps =
            Gaps.assess(
                mentions = updatedMentions,
                values = updatedValues,
                // Ambiguity is re-derived from what THIS call decided; a span the caller's lattice
                // recorded as ambiguous stays ambiguous because nothing here bound it.
                ambiguousSpans =
                    lattice.gapsList
                        .filter { it.kind == GapKind.GAP_KIND_G2_AMBIGUOUS }
                        .map { it.span.start to it.span.end }
                        .toSet(),
                parse = lattice.parse,
                // G5 is NOT derivable here — see [carriedG5]. Never true.
                degraded = false,
            )
        val updatedGaps = (gaps + carriedG5(lattice)).sortedWith(compareBy({ it.span.start }, { it.span.end }))

        return GateResponse
            .newBuilder()
            .addAllGatedBindings(bindings)
            .addAllUpdatedGaps(updatedGaps)
            .addAllOutcomes(outcomes)
            .setRungLogEntry(
                RungLogEntry
                    .newBuilder()
                    .setRound(nextRound(lattice))
                    .setRung(rungOf(request))
                    .setAction(ACTION)
                    .addAllMentionIds(touchedMentions)
                    .addAllValueIds(touchedValues)
                    .addAllHypotheses(request.hypothesesList)
                    .setBindingsAdded(bindings.size)
                    .setGapsOpen(updatedGaps.size),
                // `elapsed_ms` is deliberately LEFT UNSET, and it is the one field here that could
                // be filled and should not be. T4's contract is that gating the same batch twice is
                // the same call twice — `ReGateTest` compares the two responses BYTE FOR BYTE — and
                // a wall-clock reading would make the response a function of the clock as well as
                // the request. That is the flake p2-5 found in a golden, and it does not get to
                // reappear here. Latency for this rpc is the `resolve.gate` span's to report.
            ).build()
    }

    // --- helpers ------------------------------------------------------------

    /**
     * Ask the lexicon about one hypothesis, and never confuse "it said no" with "it did not say".
     *
     * The failure branch is why this is a function rather than a `runCatching` at the call site. A
     * swallowed failure would reach the caller as [Reason.NO_CANDIDATE] — a claim about the estate's
     * vocabulary — and a proposer that is told its correct guess does not exist learns to stop
     * making it. [CancellationException] is rethrown rather than absorbed: it means the caller has
     * gone, and the remaining hypotheses should go with them.
     */
    private suspend fun lookup(
        fuzzy: FuzzyClient,
        hypothesis: Hypothesis,
        latticeText: String,
        categories: List<String>,
        maxCandidates: Int,
    ): Answer =
        try {
            Answer.Answered(
                fuzzy
                    .lookup(
                        LookupRequest
                            .newBuilder()
                            .setTerm(surfaceOf(hypothesis, latticeText))
                            .addAllCategories(categories)
                            .setMaxCandidates(maxCandidates)
                            .build(),
                    ).candidatesList,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn(
                "re-gate lookup failed span={}-{} rung={} — reported as LOOKUP_FAILED, not NO_CANDIDATE",
                hypothesis.span.start,
                hypothesis.span.end,
                hypothesis.proposingRung,
                e,
            )
            Answer.Failed
        }

    /**
     * The caller's degraded-floor banner, carried across the re-gate rather than re-derived.
     *
     * `Gaps.assess` mints G5 from a `degraded` flag that belongs to the RESOLVE's capability
     * assessment (RS-25), and a `ResolutionState` carries no such field — so asking for it here
     * could only ever answer `false`. Since `updated_gaps` is the caller's whole next gap list and
     * not a delta, re-deriving alone would silently retire RG-RES-001 across one gate call: a
     * question resolved on the fold+fuzzy floor would come back looking undegraded. Carried for the
     * same reason G2 is — this call learned nothing that bears on it.
     */
    private fun carriedG5(lattice: ResolutionState): List<GapRecord> =
        lattice.gapsList.filter { it.kind == GapKind.GAP_KIND_G5_NLP_DARK }

    /**
     * One past the highest round the caller's log already carries. Round 0 is reserved for the
     * core's own deterministic pass, so a gate entry must never claim it — which an unset field
     * would. DERIVED from the request rather than counted in the service, so the response stays a
     * pure function of its inputs and T4's idempotency holds.
     */
    private fun nextRound(lattice: ResolutionState): Int = (lattice.rungLogList.maxOfOrNull { it.round } ?: 0) + 1

    /**
     * The rung this entry belongs to: whichever one proposed. One `gate` call is one
     * escalate→gate pair (RV-11), so a batch from two rungs is a caller error rather than a
     * shape to model; the first hypothesis names the pair.
     */
    private fun rungOf(request: GateRequest): String =
        request.hypothesesList
            .firstOrNull { it.proposingRung.isNotBlank() }
            ?.proposingRung
            .orEmpty()

    /**
     * The corrected surface if the hypothesis offers one, else the span the LATTICE already has —
     * the lattice's text, not the hypothesis', because the hypothesis is the untrusted half of this
     * exchange and its `span.text` is only a label for offsets it does not own.
     */
    private fun surfaceOf(
        hypothesis: Hypothesis,
        latticeText: String,
    ): String = hypothesis.correction.ifBlank { latticeText.ifBlank { hypothesis.span.text } }

    /**
     * Where to look. A proposed ref scopes to its own category (in the compiled lexicon a target
     * ref IS the category key — the same fact `GroundingTriggers` relies on), widened to the
     * declaring entity's categories when the registry knows it, so a hypothesis naming an entity
     * still reaches that entity's member columns. With no ref, the lattice's own anchoring
     * supplies the scope — the same scope a P2.3 round would have used.
     */
    private fun scopeFor(
        hypothesis: Hypothesis,
        value: ValueFinding?,
        lattice: ResolutionState,
        categoriesByRef: Map<String, List<String>>,
    ): List<String> {
        if (hypothesis.ref.isNotBlank()) {
            val base = hypothesis.ref.substringBefore('#')
            return categoriesByRef[base] ?: listOf(base)
        }
        val anchorId = value?.anchorMentionId.orEmpty()
        if (anchorId.isBlank()) return emptyList()
        return lattice.mentionsList
            .firstOrNull { it.id == anchorId }
            ?.bindingsList
            ?.flatMap { categoriesByRef[it.ref] ?: listOf(it.ref) }
            ?.distinct()
            .orEmpty()
    }

    /**
     * A binding confirms a proposed ref when it IS that ref, or a MEMBER of it — `501001` on
     * `md.dimension.Account.code` arrives as `md.dimension.Account.code#501001`, and refusing that
     * would reject every correct member hypothesis there is.
     *
     * That widening runs in ONE direction, deliberately. A hypothesis that named the specific member
     * `…Account.code#501001` and got the bare attribute `…Account.code` back has NOT been confirmed:
     * it asked about a value and was answered about a column. Accepting it would also emit the
     * attribute as the hypothesis' binding, so the proposer would be told "yes" and handed something
     * it did not ask for — which is [Reason.REF_MISMATCH] in person. Specific ⊂ general confirms;
     * general ⊅ specific does not.
     */
    private fun confirms(
        bound: String,
        proposed: String,
    ): Boolean = bound == proposed || bound.startsWith("$proposed#")

    /**
     * The span as the gate needs to see it. Only [DomainSpanCandidate.anchored] is load-bearing
     * here — it is the one input to [EvidenceClasses] that does not come from the matcher row —
     * so the rest is filled from the hypothesis rather than invented.
     */
    private fun anchorCandidate(
        hypothesis: Hypothesis,
        anchored: Boolean,
    ): DomainSpanCandidate =
        DomainSpanCandidate(
            text = hypothesis.span.text,
            start = hypothesis.span.start,
            end = hypothesis.span.end,
            gatedEntityRefs = emptyList(),
            categories = emptyList(),
            anchored = anchored,
            // MH: no `slot`, and that is STRUCTURAL rather than an omission (architecture A3).
            // A re-gate has no parse — it is re-deciding a span the broad pass already slotted —
            // and a rule that invented a slot here would be deciding an object from a hypothesis'
            // say-so, which is exactly what RV-7 forbids. `SlotHint.NONE` ⇒ both MH rules no-op.
        )

    private fun outcome(
        hypothesis: Hypothesis,
        reason: String,
    ): HypothesisOutcome =
        HypothesisOutcome
            .newBuilder()
            .setHypothesis(hypothesis)
            .setAccepted(false)
            .setReason(reason)
            .build()

    private fun accepted(
        hypothesis: Hypothesis,
        binding: Binding,
    ): HypothesisOutcome =
        HypothesisOutcome
            .newBuilder()
            .setHypothesis(hypothesis)
            .setAccepted(true)
            .setBinding(binding)
            .build()

    private fun Binding.withRung(rung: String): Binding =
        if (rung.isBlank()) {
            this
        } else {
            toBuilder().setProducer(producer.toBuilder().setProposingRung(rung)).build()
        }

    private fun Mention.plus(added: List<Binding>?): Mention =
        if (added.isNullOrEmpty()) this else toBuilder().addAllBindings(added).build()

    private fun ValueFinding.plus(added: List<Binding>?): ValueFinding {
        // An attribution names a model ATTRIBUTE, so a binding that cannot be one is dropped here
        // rather than written as an `attribute_ref` (see `Bindings.attributable`). The hypothesis
        // OUTCOME still reports such a row as accepted — it did match the span; it simply is not
        // an attribution — and `Mention.plus` above deliberately has no equivalent filter, because
        // a trigger on a mention is exactly where a trigger belongs.
        val attributions = added?.filter(Bindings::attributable)
        if (attributions.isNullOrEmpty()) return this
        return toBuilder()
            .addAllAttributions(
                attributions.map {
                    Attribution
                        .newBuilder()
                        .setAttributeRef(it.ref.substringBefore('#'))
                        .setBinding(it)
                        .build()
                },
            ).build()
    }
}
