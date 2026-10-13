// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.resolver.v1.Binding

/**
 * ttr-server#227 — **a value-shaped span whose best reading is a model object is a mention of it.**
 *
 * Span proposal offers some nominal words as VALUES before anything has read them: the nominal argument
 * of a one-word anchor that has values ([DomainSpanCandidate.Origin.GOVERNED_VALUE] and its open sibling),
 * a proper noun, a NER entity. That is a guess about the word's shape, and the gate is what reads it. When
 * the gate's best reading is a member, the guess was right and the span is a value. When it is a MODEL
 * OBJECT — an attribute, a measure, an entity — the word NAMES that object, and a value attribution
 * cannot carry it: the proto's attribution binds a member (`<attribute_ref>#<id>`), and a consumer that
 * reads it as a restriction compares the column to its own name.
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
 * So a value-shaped span is now asked about triggers as a mention is ([GroundingTriggers.spansOf]), and
 * after the gate one whose best reading is a model object leaves the value layer:
 *
 *  - **with a trigger** ([ownedByKernel]), it is exactly the mention the mention layer makes of the same
 *    word when no anchor governs it ([asMention]): ungated, bound by the trigger alone, and the kernel's.
 *    Its object reading came from a lookup asked of every category, not from a declaration that the word
 *    names that object (a declared anchor is proposed as a mention in the first place), and a FILTER
 *    mention bound to a column with nothing to restrict it by is a restriction on nothing. The door drops
 *    it too, so `Resolution` and the lattice tell the same story.
 *  - **without one** ([mentionOf]), it is a mention bound to the object it read as, with the evidence it
 *    had.
 *
 * A value-shaped span that found nothing stays an unattributed value (G3/G4), as before, and a value no
 * mention can be (a code, a quoted literal, a member phrase) keeps only member attributions
 * ([Bindings.attributable]). Nothing is dropped: every span ends in one layer or the other.
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

    /**
     * Whether [span] was proposed as a value and its best reading is a model object. A dual reading is
     * the seam's to decide (`UniversalSeam`: one that survived it found a member), so it is never one.
     */
    fun readsAsObject(span: GatedSpan): Boolean =
        span.candidate.origin in MENTION_SHAPED &&
            !span.candidate.dualReading &&
            span.candidate.headToken >= 0 &&
            span.contenders.firstOrNull()?.let { Bindings.namesModelObject(it.match) } == true

    /** The value-shaped spans that read as an object and that a grounding trigger claims. */
    fun ownedByKernel(
        gated: List<GatedSpan>,
        triggers: Map<Pair<Int, Int>, List<Binding>>,
    ): List<GatedSpan> =
        gated.filter { readsAsObject(it) && triggers[it.candidate.start to it.candidate.end].orEmpty().isNotEmpty() }

    /** [span] in the mention layer, bound to what the gate read it as, when it [readsAsObject]; else [span]. */
    fun mentionOf(span: GatedSpan): GatedSpan =
        if (readsAsObject(span)) span.copy(candidate = asMention(span.candidate)) else span

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
