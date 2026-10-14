// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.SpanQuery
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.v1.Binding
import org.tatrman.resolver.v1.EvidenceClass
import org.tatrman.resolver.v1.TargetClass
import org.tatrman.resolver.v1.UniversalEntityType
import org.tatrman.ttr.lexicon.LexiconValidator

/**
 * RV-P1.6.T6 (RV-42) — **which kernel owns which span**, as a lattice annotation.
 *
 * P1.6 gave every grounding kernel its own `ground:` slice of the compiled lexicon and taught it
 * to answer *"is this span mine?"* ([org.tatrman.grounding.lexicon.GroundingSlice]). What was
 * missing is the other half of that question: the core never said which kernel a span should be
 * offered to in the first place, so a caller either asked all three or guessed. A trigger
 * annotation is that narrowing, recorded where the ladder can read it.
 *
 * **It is evidence, never a binding decision.** A trigger says "chrono has vocabulary for this
 * word", which is a fact about the LEXICON; it says nothing about which model object the mention
 * binds. So it rides the same batch as everything else but in its own slots, and the gate — the
 * thing that decides bindings and clarifications — never sees it. Two consequences worth stating,
 * because both are load-bearing:
 *
 *  - *roce* binding `md.dimension.Calendar.year` **and** carrying `ground:chrono` is not an
 *    ambiguity to clarify. It is one span with two true annotations, which is exactly what a
 *    lattice is for; the compiler pins the same overlap rule from the authoring side (a term may
 *    be both an `op:` and a `ground:` target, no warning).
 *  - the trigger is appended AFTER the model bindings, so the top binding still speaks for the
 *    mention in frame-role derivation. A grounding trigger must not be able to move a SUBJECT.
 *
 * **One BatchMatch still.** The trigger queries are extra slots on the pass the core already
 * makes (B-T1: never per-span RPCs), not a second round trip. The P2.3 lookup rounds are where a
 * *narrowed re-query* belongs; this is the broad pass telling them where to start.
 */
object GroundingTriggers {
    /**
     * The `ground:` category keys to search.
     *
     * Taken from the producer's own closed vocabulary rather than mirrored: `ttr-lexicon` is what
     * the estate's lexicon was validated and compiled against, and RG-LEX-012 rejects any kind
     * outside it — so a fourth kernel becomes askable here the moment the artifact that defines
     * it is on the classpath. A copy of the list in this repo would be a second rule free to
     * drift from the first, which is precisely the cross-repo seam the P1.6 review flagged.
     *
     * In the compiled lexicon a target ref IS the category key (lex-matcher's
     * `LexiconArchiveSource`), so `ground:chrono` is both. An estate that ships no grounding rows
     * makes these unknown categories, and an unknown category contributes nothing to its slot —
     * it does not fall back to the global index. Asking costs nothing there.
     */
    val CATEGORIES: List<String> =
        LexiconValidator.GROUNDING_KINDS
            .sorted()
            .map { LexiconValidator.GROUND_PREFIX + it }

    /**
     * The spans worth asking about: the MENTION layer, and every value-shaped span that may yet turn
     * out to be one, deduplicated by span.
     *
     * Literals, quoted strings and member names are excluded, and that boundary is the whole reason a
     * flat trigger table is enough. The kernel already matches its own slice against the span it is
     * handed (P1.6 T4/T5) — asking again here would be re-implementing that inside the resolver. What
     * the kernel cannot do from its own side is decide *which* kernel gets the span, and that is a
     * fact about the mention: "the user said *roce*, so the year beside it is chrono's".
     *
     * ttr-server#227 — a word span proposal offered as a VALUE that a mention could be
     * ([ValueReadings.mentionable]: the argument of an anchor, a proper noun) is asked too, because
     * whether it is a value is the gate's answer, and that answer arrives on the same batch. *roce*
     * under `webu` was proposed as web's value, read as the year attribute, and — never asked — lost the
     * trigger it carries everywhere else. It is asked AS the mention it would become
     * ([ValueReadings.asMention]), unanchored: the anchor scoped its value lookup, not the word, and a
     * trigger it fuzzily matched must class the same under `webu` as under `po čtvrtletích`. A
     * value-shaped span a member reads keeps no trigger: only a mention carries one.
     *
     * Two mentions of the same span (one anchor word owned by two entity types) ask once: a
     * trigger is about the surface, not about which model object scoped the lookup.
     */
    fun spansOf(
        candidates: List<DomainSpanCandidate>,
        ungatedMentions: List<DomainSpanCandidate>,
    ): List<DomainSpanCandidate> =
        (
            candidates.mapNotNull {
                when {
                    it.origin in MENTION_ORIGINS -> it
                    ValueReadings.mentionable(it) -> ValueReadings.asMention(it)
                    else -> null
                }
            } + ungatedMentions
        ).distinctBy { it.start to it.end }

    /** One class-scoped query per span, in the same order — the trailing slots of the batch. */
    fun queries(
        spans: List<DomainSpanCandidate>,
        perSpanLimit: Int,
    ): List<SpanQuery> =
        spans.map { span ->
            SpanQuery
                .newBuilder()
                .setQuery(span.text)
                .addAllCategories(CATEGORIES)
                .setLimit(perSpanLimit)
                .build()
        }

    /**
     * The trigger bindings per span, read from the batch slots starting at [offset].
     *
     * Same floor as the gate applies to a model candidate — a trigger below the bind threshold is
     * not evidence of anything — and one binding per slice: several terms of one kernel can fire
     * on a span ("rok" and "roce" are both within TYPOS(1) of *roce*), but they are the same
     * assertion made twice, so the strongest speaks for the kernel.
     *
     * Missing slots yield nothing rather than throwing: a matcher that answered short must degrade
     * to "no trigger", never take the resolve down.
     */
    fun collect(
        spans: List<DomainSpanCandidate>,
        response: BatchMatchResponse,
        offset: Int,
        thresholds: ResolverThresholds,
        snapshotHash: String,
    ): Map<Pair<Int, Int>, List<Binding>> {
        if (spans.isEmpty()) return emptyMap()
        val out = LinkedHashMap<Pair<Int, Int>, List<Binding>>()
        spans.forEachIndexed { i, span ->
            val result = response.resultsList.getOrNull(offset + i) ?: return@forEachIndexed
            val bindings =
                result.matchesList
                    .asSequence()
                    // Classified but NOT gated, and RV-P2.2 is where that distinction has to be
                    // stated rather than implied. A trigger is evidence about which kernel owns a
                    // span; it competes with nothing, so there is no winner to pick and running it
                    // through [Binder] would make one — the model binding and the trigger on the
                    // same span would become rivals in one class order, and the stronger would
                    // suppress the other. The class still comes from [EvidenceClasses], so a
                    // trigger reports its evidence as honestly as any binding does.
                    .map { Binder.ClassedMatch(it, EvidenceClasses.of(it, span.anchored, thresholds)) }
                    .filter { it.evidenceClass != EvidenceClass.EVIDENCE_CLASS_WEAK }
                    .sortedByDescending { it.match.score }
                    .map { Bindings.of(it, snapshotHash) }
                    .filter { it.targetClass == TargetClass.TARGET_CLASS_GROUNDING_TRIGGER }
                    .distinctBy { it.ref }
                    .toList()
            if (bindings.isNotEmpty()) out[span.start to span.end] = bindings
        }
        return out
    }

    /** `ground:chrono` → `chrono`; anything else → "", so a caller cannot invent a kernel name. */
    fun kindOf(ref: String): String =
        ref
            .removePrefix(LexiconValidator.GROUND_PREFIX)
            .takeIf { ref.startsWith(LexiconValidator.GROUND_PREFIX) && it in LexiconValidator.GROUNDING_KINDS }
            .orEmpty()

    /**
     * Which kernel would own a universally-typed span — the agreement check that keeps a chrono
     * trigger from anchoring an amount.
     *
     * The universal layer types a span (`DATE`, `MONEY`, `LOCATION`); the trigger names a kernel.
     * They are two independent statements about the same stretch of text, and only when they agree
     * has anything been narrowed. `PERSON`/`ORGANIZATION`/`MISC` map to no kernel: no grounding
     * service claims them, so a trigger next to one narrows nothing.
     */
    fun kernelOf(entityType: UniversalEntityType): String =
        when (entityType) {
            UniversalEntityType.DATE -> "chrono"
            UniversalEntityType.MONEY -> "money"
            UniversalEntityType.LOCATION -> "geo"
            else -> ""
        }

    /**
     * The origins that make a span a mention. `NGRAM_FLOOR` is included on purpose: in the
     * parse-less degraded path every span is a floor guess, and a trigger hit there is still a
     * lexicon fact rather than a syntactic guess — excluding it would silently switch grounding
     * narrowing off for exactly the estates that need the most help.
     */
    private val MENTION_ORIGINS =
        setOf(
            DomainSpanCandidate.Origin.ANCHOR_PHRASE,
            DomainSpanCandidate.Origin.NGRAM_FLOOR,
        )
}
