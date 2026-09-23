// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Token

/**
 * A quoted literal as the text carries it: the §1.4 content and the char offsets of its
 * delimiters ([start] inclusive, [end] exclusive — so `text.substring(start, end)` is the
 * literal *with* both quote marks).
 */
data class QuotedLiteral(
    val text: String,
    val start: Int,
    val end: Int,
)

/**
 * A literal placed on a tokenisation: which tokens carry its content, and which carry only its
 * delimiters.
 *
 * The split matters downstream (LP-P1·S2): the content tokens are the span the VERBATIM value
 * covers, and [delimiterTokens] are the ones an anchor hull must *not* swallow — a stray PUNCT
 * token at the edge of a hull is how a quote mark ends up inside a proposed domain span.
 */
data class LiteralTokenSpan(
    val literal: QuotedLiteral,
    val tokens: List<Int>,
    val delimiterTokens: List<Int>,
)

/**
 * LP contracts §1 — `scanQuotedLiterals`, the one marker the user has for *take this string
 * verbatim*.
 *
 * ⚑ **This is one of two implementations of §1.1–§1.4.** The other is golem's
 * `agent/quoted_literals.py` (LP-L2), and they are held together by **one shared fixture file**
 * (`project/server/features/literal-partial-match/fixtures/quoted-literals.fixtures.yaml`, copied
 * into both repos), not by shared code — the two worlds share no runtime. A rule changed here and
 * not there is a fork, and the corpus is where a rule is changed.
 *
 * The algorithm is deliberately dull: left to right, an opening delimiter is any member of the
 * double-quote family that a §1.2 boundary precedes, and it closes at the **nearest** family
 * member a §1.2 boundary follows. Unpaired ⇒ text. Empty ⇒ text. No nesting, no escaping —
 * `„a "b" c“` is the literal `a "b` and a stray `“`, which is ugly and pinned (fixture 17) so
 * that nobody "fixes" it twice.
 *
 * ### Offsets
 * [scan] returns **UTF-16 code-unit offsets** — Kotlin string indices, so `substring` works. The
 * nlp service is Python and its `char_start`/`char_end` are code *point* offsets; the two agree
 * for everything in the BMP (every delimiter here, every Czech letter) and drift by one per
 * astral character earlier in the text. Nothing in the domain writes emoji into a question, so
 * this is stated rather than defended against — and [toTokenSpans] compares offsets from the two
 * sources, so it is the place that would feel it.
 */
object QuoteScanner {
    /** §1.1 — the closed delimiter set. Any member opens, any member closes. */
    private val DELIMITERS = setOf('"', '\u201E', '\u201C', '\u201D', '\u201F', '\u00AB', '\u00BB')

    /** §1.2 — what may precede an opening delimiter (besides start-of-text or whitespace). */
    private val OPEN_PRECEDERS = setOf('(', '[', '{', '-', '\u2013', '\u2014', ':', ';', ',')

    /** §1.2 — what may follow a closing delimiter (besides end-of-text or whitespace). */
    private val CLOSE_FOLLOWERS = setOf(')', ']', '}', '.', ',', ';', ':', '!', '?', '-', '\u2013', '\u2014')

    /**
     * §1.2's "whitespace", widened to every Unicode space so the two implementations agree.
     *
     * ⛑ `Character.isWhitespace` is false for U+00A0 and U+202F — Java excludes the non-breaking
     * spaces by design — while Python's `str.isspace()` includes them. Czech typography and every
     * paste out of Word put a NBSP in front of exactly the kind of short word a literal follows,
     * so the narrow reading would have made the Kotlin scanner miss literals the Python one finds:
     * a fork the shared corpus could not have caught, because a YAML fixture reads the same either
     * way. `isSpaceChar` adds the Zs category; the union is Python's set.
     */
    private fun Char.isBoundarySpace(): Boolean = isWhitespace() || Character.isSpaceChar(this)

    /**
     * §1.1–§1.4. Linear in [text]: a successful pairing advances past everything it scanned, and
     * the **first** failure ends the scan — [closingFrom] searching from `i+1` and finding nothing
     * means there is no closing-capable delimiter left in the text at all, so no later opener can
     * pair either. (Walking on, as the §1.5 reference does one character at a time, reaches the
     * same answer by rescanning the same tail once per opener: same literals, O(n²) on a text of
     * nothing but unpairable quotes.)
     */
    fun scan(text: String): List<QuotedLiteral> {
        val out = mutableListOf<QuotedLiteral>()
        var i = 0
        while (i < text.length) {
            if (text[i] !in DELIMITERS || !opensAt(text, i)) {
                i++
                continue
            }
            val close = closingFrom(text, i + 1)
            if (close < 0) break
            // §1.4 — trim the edges, keep the inside exactly as typed.
            val inner = text.substring(i + 1, close).trim { it.isBoundarySpace() }
            // §1.3 — whitespace-only content is text, and BOTH delimiters are consumed with it:
            // `zákazník "" Valmy"` has one empty pair and one stray quote.
            if (inner.isNotEmpty()) out += QuotedLiteral(inner, i, close + 1)
            i = close + 1
        }
        return out
    }

    /** §1.2 — an opening delimiter is preceded by start-of-text, whitespace or [OPEN_PRECEDERS]. */
    private fun opensAt(
        text: String,
        at: Int,
    ): Boolean {
        if (at == 0) return true
        val before = text[at - 1]
        return before.isBoundarySpace() || before in OPEN_PRECEDERS
    }

    /**
     * §1.3 — the nearest delimiter at or after [from] that §1.2 lets close, or −1 when the opening
     * delimiter is unpaired (⇒ text).
     */
    private fun closingFrom(
        text: String,
        from: Int,
    ): Int {
        for (j in from until text.length) {
            if (text[j] !in DELIMITERS) continue
            val closes = j + 1 == text.length || text[j + 1].isBoundarySpace() || text[j + 1] in CLOSE_FOLLOWERS
            if (closes) return j
        }
        return -1
    }

    /**
     * The tokenisation a literal is placed on: the parse's own tokens, or — when there is no parse
     * at all (nlp unavailable, or a language the estate cannot analyse) — a whitespace split of the
     * raw text, which is the same floor `SpanProposal` (d) drops to.
     *
     * Degraded tokens carry text and offsets and nothing else: no lemma, no upos. A caller that
     * needs a POS tag must check, not assume, and the pipeline's `degraded` banner is already the
     * honest word for what these are.
     */
    fun tokens(
        text: String,
        parse: AnalyzeResponse,
    ): List<Token> {
        if (parse.tokensCount > 0) return parse.tokensList
        val out = mutableListOf<Token>()
        var i = 0
        while (i < text.length) {
            if (text[i].isBoundarySpace()) {
                i++
                continue
            }
            var end = i
            while (end < text.length && !text[end].isBoundarySpace()) end++
            out +=
                Token
                    .newBuilder()
                    .setText(text.substring(i, end))
                    .setCharStart(i)
                    .setCharEnd(end)
                    .build()
            i = end
        }
        return out
    }

    /**
     * Place each literal on [tokens].
     *
     * A literal's token span is every token overlapping the **inner** range `[start+1, end−1)` —
     * the delimiters excluded. Tokens that overlap only a delimiter are [LiteralTokenSpan
     * .delimiterTokens].
     *
     * Both tokenisations a backend can produce land correctly on that one rule: a tokeniser that
     * splits `"Pelex"` into PUNCT + `Pelex` + PUNCT gives one content token and two delimiter
     * tokens; one that glues the whole thing into a single `"Pelex"` token gives one content token
     * and none — the literal covers that token, and its text stays the §1.4 text (`Pelex`), never
     * the token surface.
     */
    fun toTokenSpans(
        literals: List<QuotedLiteral>,
        tokens: List<Token>,
    ): List<LiteralTokenSpan> =
        literals.map { literal ->
            val innerStart = literal.start + 1
            val innerEnd = literal.end - 1
            val content = mutableListOf<Int>()
            val delimiters = mutableListOf<Int>()
            tokens.forEachIndexed { index, token ->
                when {
                    token.charStart < innerEnd && token.charEnd > innerStart -> content += index
                    token.charStart < literal.end && token.charEnd > literal.start -> delimiters += index
                }
            }
            LiteralTokenSpan(literal, content, delimiters)
        }

    /** [toTokenSpans] over the effective tokenisation of [text] — see [tokens]. */
    fun toTokenSpans(
        literals: List<QuotedLiteral>,
        text: String,
        parse: AnalyzeResponse,
    ): List<LiteralTokenSpan> = toTokenSpans(literals, tokens(text, parse))
}
