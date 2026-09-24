// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.model.ResolverEntityType

/**
 * LP contracts §2.1 — who a quoted literal is ABOUT.
 *
 * The scanner ([QuoteScanner]) says where a literal is; this says which attribute it restricts.
 * The rule is the parse first and proximity second, both bounded:
 *
 *  1. walk the literal's `dep_head` chain, at most 3 hops, and take the first token that heads a
 *     MODEL_OBJECT mention — *dodací místa začínající na "Pelex"* hangs the literal off *místa*
 *     through the preposition, which is exactly the structure the question has;
 *  2. failing that, the nearest MODEL_OBJECT mention within 3 tokens, left preferred on a tie —
 *     the same [SpanProposal.MAX_ANCHOR_DISTANCE] rule source (e) scopes a bare code by, and the
 *     only rule left when there is no parse to walk;
 *  3. failing that, nothing: the literal is **headless**, and the lattice says so with a G3 gap
 *     instead of offering it to every name column in the estate. An unscoped verbatim filter is
 *     the over-generation Q-20 removed, wearing a different hat.
 *
 * The attribute is the head's DECLARED mention facet — `semantics { name: · code: }`, carried on
 * [ResolverEntityType.nameRef]/[ResolverEntityType.codeRef] — never a column this object picked
 * by name. A code-shaped literal takes `code` **when the head declares one**; everything else
 * takes `name`. Where the head declares neither, the literal is headless by the same rule as (3):
 * a mention with no name column is not a thing a string can restrict.
 */
object VerbatimAttribution {
    /** §2.1 — how far up the dep chain a literal may look for its head. */
    private const val MAX_HOPS = 3

    /** §2.1 — the fallback shape of a code, for a head that declares no `code_format`. */
    private val CODE_SHAPE = Regex("^[A-Z0-9][A-Z0-9\\-/.]*$")

    /** What a literal was attributed to, and through which mention. */
    data class Attributed(
        val attributeRef: String,
        val mentionId: String,
    )

    /**
     * One MODEL_OBJECT mention, as this rule needs it: the token it heads, its id, and the entity
     * type its top binding names. Built by [LatticeAssembler], which is the only place that has
     * all three at once.
     */
    data class Head(
        val headToken: Int,
        val mentionId: String,
        val entityType: ResolverEntityType,
    )

    /**
     * A STRING_PREDICATE mention, as this rule needs it: the token it heads and the `pred:` ref
     * its strongest predicate binding names.
     *
     * Deliberately NOT a [Head]. A head is a thing a string restricts and carries an entity type;
     * a trigger is the comparison and carries no model object at all — giving them one type would
     * invite a caller to ask a predicate for its `nameRef`.
     */
    data class Trigger(
        val headToken: Int,
        val ref: String,
    )

    fun attribute(
        literal: LiteralTokenSpan,
        parse: AnalyzeResponse,
        heads: List<Head>,
    ): Attributed? {
        if (heads.isEmpty()) return null
        val head = nearest(literal, parse, heads, Head::headToken) ?: return null
        val ref = attributeRefOf(literal.literal.text, head.entityType) ?: return null
        return Attributed(ref, head.mentionId)
    }

    /**
     * LP contracts §2/§3 — the `pred:` ref that says HOW this literal restricts its attribute, or
     * `""` when the question names none.
     *
     * The same bounded search as [attribute], over a different class, and for a reason that is
     * structural rather than tidy: in *dodací místa začínající na "Pelex"* the literal's dep chain
     * runs `Pelex → na → začínající → místa`, so the predicate is ON the path to the head and is
     * reached by the same walk that finds the head — one rule, one window, two questions.
     *
     * `""` is not a failure. §2.2 gives the default from the attribute the literal was attributed
     * to (a `name` ⇒ `contains`, a `code` ⇒ `equals`), which is the reading a user who wrote no
     * trigger meant. The empty string says "the question did not say", and the consumer's default
     * is where that is answered — never here, because this object does not know which of the two
     * attributes won.
     *
     * ⚠ This depends on the predicate words reaching the lattice as a mention at all, which is
     * span proposal's business, not this rule's. Where no span is proposed over the trigger the
     * ref is `""` and §2.2's default applies — *starting with "Shell"* degrades to *contains
     * "Shell"*, which is wider than asked but never wrong about WHICH column. LP-P3's live check
     * is where that coverage gets measured.
     */
    fun predicate(
        literal: LiteralTokenSpan,
        parse: AnalyzeResponse,
        triggers: List<Trigger>,
    ): String {
        if (triggers.isEmpty()) return ""
        return nearest(literal, parse, triggers, Trigger::headToken)?.ref.orEmpty()
    }

    /**
     * §2.2's companion: which attribute of [entityType] a literal of this shape restricts, or null
     * when the entity declares no attribute that a string could restrict.
     *
     * Code beats name only when the literal LOOKS like a code and the head HAS one. The shape test
     * prefers the model's own `code_format` where the entity declared one — a regex invented here
     * would be a second rule about what a code is, and the model already has the first.
     */
    fun attributeRefOf(
        text: String,
        entityType: ResolverEntityType,
    ): String? {
        val codeShaped =
            if (entityType.codeFormat.isNotBlank()) {
                runCatching { Regex(entityType.codeFormat).matches(text) }.getOrDefault(false)
            } else {
                CODE_SHAPE.matches(text) && text.any { it.isDigit() }
            }
        if (codeShaped && entityType.codeRef.isNotBlank()) return entityType.codeRef
        return entityType.nameRef.ifBlank { null }
    }

    /**
     * The §2.1 search, over whatever class the caller is asking about: the parse first, proximity
     * second, both bounded.
     *
     * Generic because the two questions — *what does this literal restrict* and *how* — are one
     * search asked twice. Two copies of a bounded graph walk is how the two windows drift apart,
     * and the window is the contract here: 3 hops, 3 tokens, left preferred.
     */
    private fun <T> nearest(
        literal: LiteralTokenSpan,
        parse: AnalyzeResponse,
        items: List<T>,
        tokenOf: (T) -> Int,
    ): T? = byParse(literal, parse, items, tokenOf) ?: byDistance(literal, items, tokenOf)

    /** (1) — the dep_head chain, bounded to [MAX_HOPS]. */
    private fun <T> byParse(
        literal: LiteralTokenSpan,
        parse: AnalyzeResponse,
        items: List<T>,
        tokenOf: (T) -> Int,
    ): T? {
        val tokens = parse.tokensList
        if (tokens.isEmpty()) return null
        val byToken = items.associateBy(tokenOf)
        var current = literal.tokens.firstOrNull() ?: return null
        repeat(MAX_HOPS) {
            val token = tokens.getOrNull(current) ?: return null
            // `dep_head` is 1-based; 0 is the root, and the root has nothing above it.
            val next = token.depHead - 1
            if (next < 0 || next == current) return null
            byToken[next]?.let { return it }
            current = next
        }
        return null
    }

    /**
     * (2) — the nearest item within [SpanProposal.MAX_ANCHOR_DISTANCE] tokens, left preferred.
     *
     * Left preference is not a coin toss: Czech and English both put the thing before the string
     * that restricts it (*dodací místa "Pelex"*, *stores named "Pelex"*), so on an equal distance
     * the word to the left is the one the question was about. The same holds for the trigger —
     * *začínající na "Shell"* puts it left too.
     */
    private fun <T> byDistance(
        literal: LiteralTokenSpan,
        items: List<T>,
        tokenOf: (T) -> Int,
    ): T? {
        val first = literal.tokens.firstOrNull() ?: return null
        val last = literal.tokens.last()
        return items
            .map { it to distanceTo(tokenOf(it), first, last) }
            .filter { (_, d) -> d in 1..SpanProposal.MAX_ANCHOR_DISTANCE }
            .minWithOrNull(compareBy({ (_, d) -> d }, { (item, _) -> if (tokenOf(item) < first) 0 else 1 }))
            ?.first
    }

    private fun distanceTo(
        head: Int,
        first: Int,
        last: Int,
    ): Int =
        when {
            head < first -> first - head
            head > last -> head - last
            else -> 0 // inside the literal — not a neighbour, and excluded from proposal anyway
        }
}

/**
 * LP-P1·S2 — the literals of one question, scanned once and carried through the pass.
 *
 * Scanned in [org.tatrman.resolver.pipeline.ResolverPipeline] because the raw text lives there and
 * nowhere below it: `AnalyzeResponse` carries tokens, not the string they came from. Two consumers
 * read it — [SpanProposal], which must propose nothing inside a literal, and [LatticeAssembler],
 * which emits the VERBATIM values — and both would otherwise have to re-scan and could then
 * disagree about where a literal is.
 */
data class Literals(
    val spans: List<LiteralTokenSpan>,
) {
    val isEmpty: Boolean get() = spans.isEmpty()

    /** Token indices covered by a literal — its content AND its delimiters. Nobody else's. */
    val tokens: Set<Int> = spans.flatMap { it.tokens + it.delimiterTokens }.toHashSet()

    /** True when `[start, end)` touches any literal, delimiters included. */
    fun overlaps(
        start: Int,
        end: Int,
    ): Boolean = spans.any { start < it.literal.end && end > it.literal.start }

    companion object {
        val NONE = Literals(emptyList())

        fun of(
            text: String,
            parse: AnalyzeResponse,
        ): Literals = Literals(QuoteScanner.toTokenSpans(QuoteScanner.scan(text), text, parse))

        /** For the degraded path and for tests: literals over a tokenisation already in hand. */
        fun of(
            text: String,
            tokens: List<Token>,
        ): Literals = Literals(QuoteScanner.toTokenSpans(QuoteScanner.scan(text), text, tokens))
    }
}
