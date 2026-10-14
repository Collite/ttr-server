// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.resolver.v1.Binding

/**
 * ttr-server#227 — **a value never carries a model object: a value-shaped word that reads as one is a
 * mention, and any other value loses the reading.**
 *
 * Span proposal offers some nominal words as VALUES before anything has read them: the nominal argument
 * of a one-word anchor that has values ([DomainSpanCandidate.Origin.GOVERNED_VALUE] and its open sibling),
 * a proper noun, a NER entity. That is a guess about the word's shape, and the gate is what reads it. When
 * the gate's best reading is a member, the guess was right and the span is a value. When it is a MODEL
 * OBJECT — an attribute, a measure, an entity — the word NAMES that object, and a value cannot carry it:
 * the proto's attribution binds a member (`<attribute_ref>#<id>`), and a consumer that reads a value as a
 * restriction compares the column to its own name.
 *
 * hartland, 2026-10-13. The same word went both ways on the noun it hung from:
 *
 *  - „Tržby z tržiště po čtvrtletích v roce 2025“ — `roce` hangs from `čtvrtletích`, a declared multi-word
 *    phrase that governs no value. Nothing proposed it, so the mention layer took it: a mention the
 *    `ground:chrono` trigger binds, and the year beside it is chrono's.
 *  - „Porovnej tržby z tržiště a z webu v roce 2025“ — `roce` hangs from `webu`, a one-word anchor of an
 *    entity, so it was proposed as that entity's value. Its open sibling read it as
 *    `er.entity.date_dim.year` (`za rok`, by the lemma), the lattice made that a value attribution, and
 *    the trigger was never asked: values were not trigger spans.
 *
 * So a value-shaped span is asked about triggers as the mention it may become ([GroundingTriggers.spansOf]),
 * and [settle] decides each gated span once its contenders are in:
 *
 *  - **a trigger claims it and no member reads it** — it is exactly the mention the mention layer makes of
 *    the same word when no anchor governs it ([asMention]): no contenders, bound by the trigger alone, and
 *    the kernel's. That holds whether the lookup read it as an object or found nothing: what made `roce`
 *    the kernel's under `po čtvrtletích` is the trigger, not a row that happens to share its lemma. An
 *    object reading came from a lookup asked of every category, not from a declaration that the word names
 *    that object (a declared anchor is proposed as a mention in the first place), and a FILTER mention bound
 *    to a column with nothing to restrict it by is a restriction on nothing.
 *  - **its best reading is a model object, and no trigger claims it** — a mention bound to that object, with
 *    the evidence it had.
 *  - **anything else that is a value stays one, without its model-object readings** — a code, a quoted
 *    literal, a member phrase, a dual reading, or a value-shaped word a member reads. An object was never a
 *    reading a value could take, so a tie with one is no tie, and a value that read only as one is
 *    unattributed (G3/G4) and asked again like any other.
 *
 * ⚠ Settled on the SPANS, not on what is built from them. Contenders are admitted in two places — the
 * broad pass's gate and a lookup round ([LookupRounds]) — and both hand their spans to [settle] before the
 * lattice, the round planner or the door (`GateSpans.outcomeOf`) reads them. Deciding it only where the
 * lattice writes attributions left the door binding the column the lattice had refused, and a round that
 * admitted an object after the broad pass undid the kernel's claim. [settle] is idempotent: a span it
 * settled is a mention or a value without objects, and it leaves both as they are.
 */
object ValueReadings {
    /**
     * The value origins that are nominal phrases a mention could be. A literal run is a code or a number,
     * a quoted literal is the user's string, and a member phrase or a named member was proposed only to
     * the member vocabularies — none of them is a word that names a model object.
     */
    internal val MENTION_SHAPED =
        setOf(
            DomainSpanCandidate.Origin.GOVERNED_VALUE,
            DomainSpanCandidate.Origin.OPEN_VALUE,
            DomainSpanCandidate.Origin.PROPER_NOUN,
            DomainSpanCandidate.Origin.NER_ENTITY,
        )

    /** Every origin whose span the lattice puts in the value layer (`LatticeAssembler.layerOf`). */
    private val VALUE_ORIGINS =
        MENTION_SHAPED +
            setOf(
                DomainSpanCandidate.Origin.LITERAL,
                DomainSpanCandidate.Origin.QUOTED_LITERAL,
                DomainSpanCandidate.Origin.MEMBER_PHRASE,
                DomainSpanCandidate.Origin.NAMED_MEMBER,
            )

    /**
     * Whether [candidate] was proposed as a value and is a word a mention could be. A dual reading is the
     * seam's to decide (`UniversalSeam`: one that survived it found a member), and a span with no head word
     * has nothing a mention could be built on.
     */
    fun mentionable(candidate: DomainSpanCandidate): Boolean =
        candidate.origin in MENTION_SHAPED && !candidate.dualReading && candidate.headToken >= 0

    /** [spans] as [settle] decides each; one span out for each span in, in the same order. */
    fun settle(
        spans: List<GatedSpan>,
        triggers: Map<Pair<Int, Int>, List<Binding>>,
    ): List<GatedSpan> = spans.map { settle(it, triggers) }

    /** [span] as the lattice, the round planner and the door read it (see the class doc). */
    fun settle(
        span: GatedSpan,
        triggers: Map<Pair<Int, Int>, List<Binding>>,
    ): GatedSpan {
        val candidate = span.candidate
        if (candidate.origin !in VALUE_ORIGINS) return span
        if (mentionable(candidate)) {
            val top = span.contenders.firstOrNull()?.match
            val claimed = triggers[candidate.start to candidate.end].orEmpty().isNotEmpty()
            if (claimed && top?.source != SourceTag.MEMBER) {
                return GatedSpan(asMention(candidate), emptyList(), ambiguous = false)
            }
            if (top != null && Bindings.namesModelObject(top)) return span.copy(candidate = asMention(candidate))
        }
        return withoutObjects(span)
    }

    /**
     * [span] without its model-object contenders. What the gate proved equal to the winner goes with the
     * winner, and a tie that only an object made is no longer one.
     */
    private fun withoutObjects(span: GatedSpan): GatedSpan {
        val kept = span.contenders.filterNot { Bindings.namesModelObject(it.match) }
        if (kept.size == span.contenders.size) return span
        return GatedSpan(
            candidate = span.candidate,
            contenders = kept,
            ambiguous = span.ambiguous && kept.size > 1,
            equivalents = if (kept.firstOrNull() === span.contenders.first()) span.equivalents else emptyList(),
        )
    }

    /**
     * [candidate]'s words as the mention layer proposes a leftover phrase (`MentionLayer.propose`): no
     * entity it was gated to and no categories, because the value lookup's scope is not the mention's.
     * The lattice reads a mention's kind from what binds it, falling back to that scope, and an open
     * value's scope is every declared type.
     */
    fun asMention(candidate: DomainSpanCandidate): DomainSpanCandidate =
        DomainSpanCandidate(
            text = candidate.text,
            start = candidate.start,
            end = candidate.end,
            gatedEntityRefs = emptyList(),
            categories = emptyList(),
            anchored = false,
            origin = DomainSpanCandidate.Origin.ANCHOR_PHRASE,
            headToken = candidate.headToken,
            lemma = candidate.lemma,
        )
}
