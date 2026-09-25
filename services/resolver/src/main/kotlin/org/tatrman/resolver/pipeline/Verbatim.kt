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
 *  2. failing that, the nearest MODEL_OBJECT mention within 3 tokens **to the left**, and only
 *     when there is none there, the nearest within 3 tokens to the right — the same
 *     [SpanProposal.MAX_ANCHOR_DISTANCE] bound source (e) scopes a bare code by, and the only rule
 *     left when there is no parse to walk;
 *  3. failing that, nothing: the literal is **headless**, and the lattice says so with a G3 gap
 *     instead of offering it to every name column in the estate. An unscoped verbatim filter is
 *     the over-generation Q-20 removed, wearing a different hat.
 *
 * **Distance is measured between two edges, never between two anchors** (review-103 F2). From
 * the literal's OUTER delimiter — the opening quote for a word on its left, the closing one for a
 * word on its right — to the NEAREST token of the mention's span. Measuring from the first content
 * token counted the opening quote whenever a tokenizer splits it off (the floor tokenizer always
 * does), and measuring to the mention's head word counted the rest of a two-word head: together
 * they put *dodací místa začínající na "Pelex"* five tokens apart, and every trigger + literal
 * question on the floor parse lost its head. The predicate window ([PredicateTriggers]) was always
 * measured from the delimiter; now both questions use one origin.
 *
 * **Left first is a rule, not a tie-break.** Czech and English both put the thing before the
 * string that restricts it (*dodací místa "Pelex"*, *stores named "Pelex"*), so a mention to the
 * right is only a fallback for the rare postposed head. As a mere tie-break it let
 * *prodejny začínající na "Pel" podle zákazníků* filter the customer column.
 *
 * The attribute is the head's DECLARED mention facet — `semantics { name: · code: }`, carried on
 * [ResolverEntityType.nameRef]/[ResolverEntityType.codeRef] — never a column this object picked
 * by name. A code-shaped literal takes `code` **when the head declares one**; everything else
 * takes `name`. An **attribute** mention is its own answer: in *zákazníky s názvem "Valmy"* the
 * user named the column (review-103 F14). A head that yields nothing — an entity with no facet, a
 * measure — does not end the search; the walk goes on to the next candidate inside the same
 * bounds, because *a mention with no name column* is not a thing a string can restrict.
 */
object VerbatimAttribution {
    /** §2.1 — how far up the dep chain a literal may look for its head. */
    private const val MAX_HOPS = 3

    /** §2.1 — the fallback shape of a code, for a literal the head's own pattern did not claim. */
    private val CODE_SHAPE = Regex("^[A-Z0-9][A-Z0-9\\-/.]*$")

    /** MS contracts §5 — the object kind of an attribute ref (see [ResolverEntityType.objectKind]). */
    private const val ATTRIBUTE_KIND = "attribute"

    /** The kinds that are never a thing a string restricts, whatever else they declare. */
    private val NON_HEAD_KINDS = setOf("measure", "operator")

    /** Which aspect of its head a literal was attributed to — what §2.2's default reads. */
    enum class Facet { NAME, CODE }

    /** What a literal was attributed to, through which mention, and under which facet. */
    data class Attributed(
        val attributeRef: String,
        val mentionId: String,
        val facet: Facet = Facet.NAME,
    )

    /**
     * One MODEL_OBJECT mention, as this rule needs it: the token it heads, the first and last
     * tokens of its span, its id, and the entity type its top binding names. Built by
     * [LatticeAssembler], which is the only place that has all of it at once.
     *
     * [firstToken]/[lastToken] exist for the distance rule: the nearest word of a two-word head is
     * its LAST word when the literal follows it, and the head token alone cannot say that.
     */
    data class Head(
        val headToken: Int,
        val mentionId: String,
        val entityType: ResolverEntityType,
        val firstToken: Int = headToken,
        val lastToken: Int = headToken,
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

    /**
     * The attribute [literal] restricts, or null when no head in reach declares one.
     *
     * [codeRefs] are the code attributes the registry declares (every entity's
     * [ResolverEntityType.codeRef]); an attribute head that IS one of them is a code facet, so
     * *s kódem "AB12"* defaults to `equals`, not `contains`.
     */
    fun attribute(
        literal: LiteralTokenSpan,
        parse: AnalyzeResponse,
        heads: List<Head>,
        codeRefs: Set<String> = emptySet(),
    ): Attributed? {
        if (heads.isEmpty()) return null
        for (head in candidates(literal, parse, heads)) {
            val (ref, facet) = facetOf(literal.literal.text, head.entityType, codeRefs) ?: continue
            return Attributed(ref, head.mentionId, facet)
        }
        return null
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
     * **Left only** (review-103 F2b). A predicate form qualifies the string AFTER it — the claim
     * [PredicateTriggers] is built on — so a trigger to the right of a literal belongs to the next
     * literal, however the parse attached it: *prodejny zákazníka "Valmy" začínající na "Pe"* must
     * not hand *Valmy* the *začínající na* that *Pe* owns.
     *
     * `""` is not a failure: §2.2's default applies. [LatticeAssembler] fills it in from the
     * [Facet] the attribution landed on (review-103 D5), because only it knows both halves.
     */
    fun predicate(
        literal: LiteralTokenSpan,
        parse: AnalyzeResponse,
        triggers: List<Trigger>,
    ): String {
        val open = literal.openToken() ?: return ""
        val left = triggers.filter { it.headToken < open }
        if (left.isEmpty()) return ""
        byParse(literal, parse, left, Trigger::headToken)?.let { return it.ref }
        return left
            .map { it to open - it.headToken }
            .filter { (_, d) -> d in 1..SpanProposal.MAX_ANCHOR_DISTANCE }
            .minByOrNull { (_, d) -> d }
            ?.first
            ?.ref
            .orEmpty()
    }

    /**
     * §2.2's default, spelled as the ref a trigger would have produced: a code is matched whole, a
     * name is searched inside.
     */
    fun defaultPredicate(facet: Facet): String =
        when (facet) {
            Facet.CODE -> PRED_EQUALS
            Facet.NAME -> PRED_CONTAINS
        }

    /**
     * §2.2's companion: which attribute of [entityType] a literal of this shape restricts, or null
     * when the entity declares no attribute that a string could restrict.
     */
    fun attributeRefOf(
        text: String,
        entityType: ResolverEntityType,
    ): String? = facetOf(text, entityType, emptySet())?.first

    /**
     * The attribute and facet [text] takes under [entityType].
     *
     *  - An **attribute** head is its own answer (review-103 F14): the user named the column. It is
     *    a CODE facet when some entity declares it as its `code`, a NAME facet otherwise.
     *  - A **measure** or an operator is never a head.
     *  - Otherwise the entity's declared facet: `code` when the literal is code-shaped AND the
     *    entity declares a code, `name` when it declares a name, else nothing.
     */
    private fun facetOf(
        text: String,
        entityType: ResolverEntityType,
        codeRefs: Set<String>,
    ): Pair<String, Facet>? {
        if (entityType.objectKind == ATTRIBUTE_KIND) {
            return entityType.ref to (if (entityType.ref in codeRefs) Facet.CODE else Facet.NAME)
        }
        if (entityType.objectKind in NON_HEAD_KINDS) return null
        if (entityType.codeRef.isNotBlank() && codeShaped(text, entityType)) return entityType.codeRef to Facet.CODE
        return entityType.nameRef.ifBlank { null }?.let { it to Facet.NAME }
    }

    /**
     * §2.1, as amended by review-103 (rulings 3, F6): the head's own pattern **or** the fallback
     * shape — not one instead of the other.
     *
     * [ResolverEntityType.codeFormat] is always a regex since the tatrman lexicon fix (an authored
     * `code_pattern`, or a period mask the compiler translated); a malformed one is a model defect
     * and falls back to the shape rule rather than failing a question that has nothing to do with
     * codes. The shape needs at least one digit — the rule that keeps a shouted `PELEX` out of the
     * code column — EXCEPT under a head that declares a code and no name, where there is no name
     * column for a letter-only literal to belong to (hartland's `AAAAAAAABAAAAAAA` keys).
     */
    private fun codeShaped(
        text: String,
        entityType: ResolverEntityType,
    ): Boolean {
        val declared =
            entityType.codeFormat.isNotBlank() &&
                runCatching { Regex(entityType.codeFormat).matches(text) }.getOrDefault(false)
        if (declared) return true
        if (!CODE_SHAPE.matches(text)) return false
        val codeOnly = entityType.nameRef.isBlank()
        return codeOnly || text.any { it.isDigit() }
    }

    /**
     * The heads [attribute] may try, best first and without repeats: the dep chain in hop order,
     * then the left neighbours by distance, then — only when there are none — the right ones.
     */
    private fun candidates(
        literal: LiteralTokenSpan,
        parse: AnalyzeResponse,
        heads: List<Head>,
    ): Sequence<Head> =
        sequence {
            yieldAll(chain(literal, parse, heads, Head::headToken))
            yieldAll(byDistance(literal, heads))
        }.distinct()

    /** (1) — the first item on the dep_head chain, bounded to [MAX_HOPS]. */
    private fun <T> byParse(
        literal: LiteralTokenSpan,
        parse: AnalyzeResponse,
        items: List<T>,
        tokenOf: (T) -> Int,
    ): T? = chain(literal, parse, items, tokenOf).firstOrNull()

    /** (1) — every item on the dep_head chain, in hop order, bounded to [MAX_HOPS]. */
    private fun <T> chain(
        literal: LiteralTokenSpan,
        parse: AnalyzeResponse,
        items: List<T>,
        tokenOf: (T) -> Int,
    ): List<T> {
        val tokens = parse.tokensList
        if (tokens.isEmpty()) return emptyList()
        val byToken = items.groupBy(tokenOf)
        val out = mutableListOf<T>()
        var current = literal.tokens.firstOrNull() ?: return emptyList()
        repeat(MAX_HOPS) {
            val token = tokens.getOrNull(current) ?: return out
            // `dep_head` is 1-based; 0 is the root, and the root has nothing above it.
            val next = token.depHead - 1
            if (next < 0 || next == current) return out
            out += byToken[next].orEmpty()
            current = next
        }
        return out
    }

    /**
     * (2) — the heads within [SpanProposal.MAX_ANCHOR_DISTANCE] tokens, edge to edge: every left
     * one nearest first, and the right ones only when the left is empty.
     */
    private fun byDistance(
        literal: LiteralTokenSpan,
        heads: List<Head>,
    ): List<Head> {
        val open = literal.openToken() ?: return emptyList()
        val close = literal.closeToken() ?: return emptyList()
        val reach = 1..SpanProposal.MAX_ANCHOR_DISTANCE
        val left =
            heads
                .filter { it.lastToken < open }
                .map { it to open - it.lastToken }
                .filter { (_, d) -> d in reach }
                .sortedBy { (_, d) -> d }
                .map { it.first }
        if (left.isNotEmpty()) return left
        return heads
            .filter { it.firstToken > close }
            .map { it to it.firstToken - close }
            .filter { (_, d) -> d in reach }
            .sortedBy { (_, d) -> d }
            .map { it.first }
    }

    /** The literal's first token, delimiter included — the edge a left neighbour is measured to. */
    private fun LiteralTokenSpan.openToken(): Int? = (tokens + delimiterTokens).minOrNull()

    /** The literal's last token, delimiter included — the edge a right neighbour is measured to. */
    private fun LiteralTokenSpan.closeToken(): Int? = (tokens + delimiterTokens).maxOrNull()

    private const val PRED_EQUALS = "pred:equals"
    private const val PRED_CONTAINS = "pred:contains"
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
