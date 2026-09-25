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
                .filter { UniversalClassifier.isUniversal(it.label, it.normalizedValue) }
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

        val out = mutableListOf<DomainSpanCandidate>()
        tokens.forEachIndexed { idx, token ->
            if (token.upos.uppercase() !in NOMINAL_UPOS) return@forEachIndexed
            if (token.text.any { it.isDigit() }) return@forEachIndexed
            if (isUniversal(token, universal)) return@forEachIndexed
            if (idx in claimed) return@forEachIndexed

            val phrase = phraseIndices(idx, children, tokens, universal, blocking, literals.tokens)
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

    private fun phraseIndices(
        headIdx: Int,
        children: Map<Int, List<Int>>,
        tokens: List<Token>,
        universal: List<IntRange>,
        claimed: Set<Int>,
        literalTokens: Set<Int>,
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
