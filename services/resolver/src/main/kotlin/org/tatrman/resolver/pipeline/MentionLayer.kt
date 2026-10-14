// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Token

/**
 * RV-P2.1 — the mention layer: every content nominal phrase in the question, whether or not
 * anything in the estate binds it.
 *
 * Span *proposal* (Q-20) is deliberately narrow, because it decides what gets MATCHED and a
 * wide proposal over-generates bindings (naive all-spans×fuzzy: P=0.5, 33 spurious binds).
 * The lattice's mention layer is a different question — what the user TALKED ABOUT — and it
 * costs nothing in precision to answer it fully: an unmatched mention produces a typed **gap**,
 * never a binding. Without it the core cannot say "I do not know this word", which is exactly
 * the `issues.md` complaint ("Not admitting not knowing the entity") and the reason RV-P4
 * waited for this phase.
 *
 * Everything already proposed for gating keeps its span; this only adds the leftovers. Values
 * are not mentions: literals, universal-typed spans (a date, a person) and proper nouns are the
 * other layer of RV-2's two-layer model, and are excluded here.
 *
 * **…and the adjectives no span took (ttr-server#237, [leftOutModifiers]).** The leftovers are headed
 * by nouns, so an adjective is a mention only inside its noun's phrase — and a gated span can take the
 * noun without it.
 */
object MentionLayer {
    /** Modifier relations that belong to a nominal phrase. Numerals do not: they are values. */
    private val PHRASE_RELATIONS = setOf("amod", "compound", "flat", "flat:name", "det")

    private val NOMINAL_UPOS = setOf("NOUN", "X")

    /**
     * The content nominal phrases NOT already covered by a gated candidate, in span order.
     * Returns an empty list when the parse carries no dep tree: without one there is no phrase
     * structure to speak of, and the honest record of that is the G5 degrade gap, not a pile of
     * invented mentions.
     */
    fun propose(
        parse: AnalyzeResponse,
        gated: List<DomainSpanCandidate>,
        // LP contracts §2 — the quoted literals. A word inside quotes is not something the user
        // talked ABOUT, it is a string they want passed through: it heads no mention, joins no
        // phrase, and no phrase reaches across it (review-103 F4a). Without this a NOUN inside
        // quotes became an ungated mention with a grounding-trigger slot and an UNBOUND_MENTION
        // lookup — *dodací místa "Zelená louka"* was looked up, bound, and then attributed the
        // literal to its own words. Empty for a question with no quotes.
        literals: Literals = Literals.NONE,
    ): List<DomainSpanCandidate> {
        val tokens = parse.tokensList
        if (tokens.isEmpty() || tokens.none { it.depHead > 0 }) return emptyList()

        val universal =
            parse.entitiesList
                .filter { UniversalClassifier.isUniversal(it.label, it.normalizedValue, it.text) }
                .map { it.charStart until it.charEnd }

        val children = HashMap<Int, MutableList<Int>>()
        tokens.forEachIndexed { idx, t -> if (t.depHead > 0) children.getOrPut(t.depHead) { mutableListOf() }.add(idx) }

        // Tokens some gated candidate already speaks for. They may not be absorbed into a
        // leftover phrase — a word that is its own mention is nobody else's modifier, the same
        // rule span proposal applies to declared anchors.
        val claimed =
            tokens.indices
                .filter { i -> gated.any { it.start <= tokens[i].charStart && it.end >= tokens[i].charEnd } }
                .toHashSet()
                .apply { addAll(literals.tokens) }

        // ⛑ …with ONE exception, and it is the difference between `Marketplace revenue` resolving
        // and not. A **proper noun** is claimed by span proposal's PROPN branch on sight, with no
        // model evidence behind it — it is a guess that this word names something, made because
        // proper nouns usually do. When such a word is also a `compound`/`amod` modifier of a
        // nominal head, the guess is wrong in the way that costs most: the head noun is left
        // standing alone, and a bare head ("revenue") is exactly the word an estate declines to
        // declare, because bare measure words are ambiguous across its objects.
        //
        // The tell is that the SAME question with the same word tagged NOUN resolves — the PROPN
        // branch simply never fires there, the phrase forms, and the declared term matches. So the
        // exclusion was doing nothing but making the lattice depend on capitalisation.
        //
        // The proper noun keeps its own candidate: overlapping spans are already normal here (a
        // narrow mention and the wider phrase containing it both reach the gate), and the narrow
        // one is what carries the value/filter reading. This only stops it from BLOCKING the wider
        // one. ⚠ Deliberately narrow — a token claimed by any other candidate origin still blocks.
        val propnOnlyClaims =
            tokens.indices
                .filter { i ->
                    val covering = gated.filter { it.start <= tokens[i].charStart && it.end >= tokens[i].charEnd }
                    covering.isNotEmpty() && covering.all { it.origin == DomainSpanCandidate.Origin.PROPER_NOUN }
                }.toHashSet()
        val blocking = claimed - propnOnlyClaims
        // The words a quoted literal hangs from are its restriction, not a phrase modifier — the
        // rule span proposal applies to anchor phrases (see `SpanProposal.literalGovernors`).
        val governors = SpanProposal.literalGovernors(tokens, literals.tokens)

        val out = mutableListOf<DomainSpanCandidate>()
        tokens.forEachIndexed { idx, token ->
            if (token.upos.uppercase() !in NOMINAL_UPOS) return@forEachIndexed
            if (token.text.any { it.isDigit() }) return@forEachIndexed
            if (isUniversal(token, universal)) return@forEachIndexed
            if (idx in claimed) return@forEachIndexed

            val phrase = phraseIndices(idx, children, tokens, universal, blocking, literals.tokens, governors)
            val start = phrase.minOf { tokens[it].charStart }
            val end = phrase.maxOf { tokens[it].charEnd }
            // Anything the gate already asked about is already a lattice span — including the
            // case where a wider gated phrase contains this head.
            if (gated.any { it.start <= start && it.end >= end }) return@forEachIndexed
            if (out.any { it.start <= start && it.end >= end }) return@forEachIndexed

            out +=
                DomainSpanCandidate(
                    text = SpanProposal.surface(phrase, tokens),
                    start = start,
                    end = end,
                    gatedEntityRefs = emptyList(),
                    categories = emptyList(),
                    anchored = false,
                    origin = DomainSpanCandidate.Origin.ANCHOR_PHRASE,
                    headToken = idx,
                    lemma = token.lemma.ifBlank { token.text },
                )
        }
        return out.sortedWith(compareBy({ it.start }, { it.end }))
    }

    /**
     * ttr-server#237 — **an adjective a gated span left out of its noun's phrase is a mention of its own.**
     *
     * Every word the user wrote is in some span or reported as a gap; an adjective must not vanish
     * because the noun it modifies was matched without it. That happened in front of every DECLARED
     * phrase: a multi-word anchor names its own extent (`SpanProposal`), so in *Měsíční tržby z
     * tržiště* the span is `tržby z tržiště`, [propose] heads its leftovers at nouns only, and
     * „Měsíční“ was in nothing — no mention, no gap. The question asked for a monthly breakdown, the
     * lattice held the measure and the year, and the caller answered one total. *Akční tržby z
     * tržiště* and *Promotional marketplace revenue* lost their restriction the same way. A governed
     * value or a proper-noun run takes nominals only, so an adjective under one was lost too.
     *
     * **What qualifies:** an `amod` dependent that no gated span and no leftover in [mentions] covers,
     * hanging from a word a gated span in [gated] DOES cover. That is the gap and nothing wider: an
     * adjective of a leftover noun is in the noun's phrase already, and one beside a date is the date's.
     *
     * **What it becomes:** a one-word mention, unbound, like the leftovers, flagged
     * [DomainSpanCandidate.leftOut]. The lookup round asks it as it asks them (`RoundPlanner`, the model
     * objects and the operators): the calendar attributes answer „Měsíční“ and „Roční“, and R6"
     * (`FrameRoles`) makes a grain adjective that binds an attribute the GROUPING, as it makes `Monthly`.
     * A word nothing binds stays a G1, so no fast path answers the question without it. It is never the
     * SUBJECT (R9): it modifies what the question is about. The caller keeps it out of the
     * grounding-trigger annotation: a word the NER (or `RelativePeriods`) did not type a date is no
     * period of its own, and a trigger row that only reads like it (`Čtvrtletní` / `čtvrtletí`) would
     * bind it — no gap — with nothing to ground.
     *
     * **What stays silent:** a word on the language's `silent-modifiers` list (`celkové`, `total`:
     * [FrameRolePreps.isSilent], the rule `MentionRetry` applies to a phrase's own modifiers); a word in
     * an operator slot, which `OperatorWords` asked the operator class about and which leaves no trace
     * when it is none (ttr-server#58: `dalších 10 prodejen`); a word a quoted literal hangs from, which is
     * the literal's predicate (`PredicateTriggers`); and a code, a universal-typed word or a quoted one,
     * which are values.
     */
    fun leftOutModifiers(
        parse: AnalyzeResponse,
        gated: List<DomainSpanCandidate>,
        mentions: List<DomainSpanCandidate>,
        literals: Literals,
        preps: FrameRolePreps,
        lang: String,
    ): List<DomainSpanCandidate> {
        val tokens = parse.tokensList
        if (tokens.none { it.depHead > 0 }) return emptyList()
        val universal =
            parse.entitiesList
                .filter { UniversalClassifier.isUniversal(it.label, it.normalizedValue, it.text) }
                .map { it.charStart until it.charEnd }
        val governors = SpanProposal.literalGovernors(tokens, literals.tokens)
        val slots = OperatorWords.slots(parse, literals).map { it.first }.toSet()

        fun covered(
            i: Int,
            spans: List<DomainSpanCandidate>,
        ) = spans.any { it.start <= tokens[i].charStart && it.end >= tokens[i].charEnd }

        return tokens.indices.mapNotNull { i ->
            val token = tokens[i]
            val head = token.depHead - 1 // dep_head is 1-based; 0 is the root
            val qualifies =
                token.depRelation.substringBefore(':') == "amod" &&
                    head in tokens.indices &&
                    head != i &&
                    head !in literals.tokens &&
                    covered(head, gated) &&
                    i !in literals.tokens &&
                    i !in governors &&
                    i !in slots &&
                    !covered(i, gated) &&
                    !covered(i, mentions) &&
                    !isUniversal(token, universal) &&
                    token.text.none { it.isDigit() } &&
                    !preps.isSilent(lang, token.text, token.lemma)
            if (!qualifies) return@mapNotNull null
            DomainSpanCandidate(
                text = token.text,
                start = token.charStart,
                end = token.charEnd,
                gatedEntityRefs = emptyList(),
                categories = emptyList(),
                anchored = false,
                origin = DomainSpanCandidate.Origin.ANCHOR_PHRASE,
                headToken = i,
                lemma = token.lemma.ifBlank { token.text },
                leftOut = true,
            )
        }
    }

    private fun phraseIndices(
        headIdx: Int,
        children: Map<Int, List<Int>>,
        tokens: List<Token>,
        universal: List<IntRange>,
        claimed: Set<Int>,
        literalTokens: Set<Int>,
        governors: Set<Int>,
    ): List<Int> {
        // A phrase never spans a literal: only the modifiers on the head's own side of every
        // quoted string may join it (see `SpanProposal.literalFreeSide`).
        val side = SpanProposal.literalFreeSide(headIdx, literalTokens)
        val included = sortedSetOf(headIdx)
        for (c in children[headIdx + 1].orEmpty()) {
            val child = tokens[c]
            if (child.depRelation !in PHRASE_RELATIONS) continue
            if (isUniversal(child, universal)) continue
            if (child.text.any { it.isDigit() }) continue
            if (c in claimed) continue
            if (c in governors) continue
            if (c !in side) continue
            included += c
        }
        val lo = included.min()
        val hi = included.max()
        return (lo..hi).filter {
            it in included ||
                (
                    !isUniversal(tokens[it], universal) &&
                        it !in claimed &&
                        it !in governors &&
                        tokens[it].text.none { c -> c.isDigit() }
                )
        }
    }

    private fun isUniversal(
        token: Token,
        universal: List<IntRange>,
    ): Boolean {
        val mid = (token.charStart + token.charEnd) / 2
        return universal.any { mid in it }
    }
}
