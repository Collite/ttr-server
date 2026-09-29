// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.v1.Disposition
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.GapRecord
import org.tatrman.resolver.v1.Mention
import org.tatrman.resolver.v1.Span
import org.tatrman.resolver.v1.TargetClass
import org.tatrman.resolver.v1.ValueFinding
import org.tatrman.resolver.v1.ValueKind

/**
 * RV-P2.1.T6 — what the core could not settle, as TYPED records (RV-19).
 *
 * A gap is not an error and not an absence: it is the deterministic core saying *what kind of
 * not-knowing this is*, because the kind selects the policy. Contracts §3 gives each kind its
 * own rung list and its own ask rule — G1 escalates then asks, G3 emits unless load-bearing,
 * G5 degrades with a banner — so a single untyped "unresolved" would collapse four different
 * responses into one. This is also the surface RV-P4 was blocked on: before it, a
 * `ResolveResponse` could carry bindings or an ambiguity, and had no way at all to say
 * "nothing in this estate binds that word".
 *
 * Five of the six kinds are produced here. **G6_INCOHERENT is not, by construction**: "the
 * question does not cohere" is a judgement about meaning, and contracts §3 routes it to the
 * `capable` rung — an LLM. A deterministic core that claimed to detect incoherence would be
 * guessing, which is the one thing this core does not do. The record exists so the ladder can
 * write it; `GapsTest` constructs it to keep the shape honest.
 */
object Gaps {
    fun assess(
        mentions: List<Mention>,
        values: List<ValueFinding>,
        ambiguousSpans: Set<Pair<Int, Int>>,
        parse: AnalyzeResponse,
        degraded: Boolean,
    ): List<GapRecord> {
        val gaps = mutableListOf<GapRecord>()
        val rolesByMention = mentions.associate { it.id to it.frameRolesList }
        val operators =
            mentions
                .filter { it.bindingsList.firstOrNull()?.targetClass == TargetClass.TARGET_CLASS_OPERATOR }
                .map { it.id }
                .toSet()

        for (mention in mentions) {
            val ambiguous = (mention.span.start to mention.span.end) in ambiguousSpans
            val kind =
                when {
                    // Several distinct candidates and no dominant one. The lattice keeps them all;
                    // the door renders the same fact as a clarification (refuse over guess, RS-26).
                    ambiguous -> GapKind.GAP_KIND_G2_AMBIGUOUS
                    // Nothing in this estate binds the word. Load-bearing exactly when it carries
                    // SUBJECT, which is what RV-15 fires an ask on.
                    mention.bindingsCount == 0 -> GapKind.GAP_KIND_G1_UNBOUND
                    else -> continue
                }
            gaps +=
                record(kind, mention.span, mention.frameRolesList)
                    .setMentionId(mention.id)
                    .build()
        }

        for (value in values) {
            // Ambiguity outranks every exemption below, and that is the point: if the gate is
            // asking the user about this span, the lattice may not report it settled. A literal
            // that reached two members (one code, two accounts) HAS attributions — it is not
            // unattributed, it is over-attributed — so the three "not a gap" rules would have
            // dropped it, and the ladder driving off gaps would see nothing to resolve on the
            // one span the door is refusing to guess (p2-1 review).
            val ambiguous = (value.span.start to value.span.end) in ambiguousSpans
            if (!ambiguous) {
                if (value.attributionsCount > 0) continue
                if (value.anchorMentionId in operators) continue // the operator's own argument, not a gap
                if (selfGrounding(value) && !namesSomething(value, parse.tokensList)) continue
            }
            val anchored = value.anchorMentionId.isNotBlank()
            val kind =
                when {
                    // Several distinct identities in the contender band — the same fact the door
                    // renders as a clarification, typed here so it can be re-entered through the
                    // gate (contracts §1: a gap sits on a mention OR on a value, `value_id`).
                    ambiguous -> GapKind.GAP_KIND_G2_AMBIGUOUS
                    // The scope WAS known — the user named the axis and the lookup in it missed.
                    // That is a method miss, and it is what a widening round (P2.2/P2.3) acts on.
                    anchored -> GapKind.GAP_KIND_G4_METHOD_MISS
                    // Nothing scoped it, so there is no method to blame: a literal or a universal
                    // hint that no attribute claimed. issues.md's `Praze` is exactly this, and the
                    // whole complaint was that the live resolver forced it into a binding instead.
                    else -> GapKind.GAP_KIND_G3_UNATTRIBUTED
                }
            gaps +=
                record(kind, value.span, rolesByMention[value.anchorMentionId].orEmpty())
                    .setValueId(value.id)
                    .build()
        }

        if (degraded) {
            // The capability matrix forced the fold+fuzzy floor (RG-RES-001). One record for the
            // whole question, DEGRADED rather than UNRESOLVED: the answer still goes out, with the
            // honesty banner the door already carries in `capabilities.degraded_reasons`.
            val end = parse.tokensList.lastOrNull()?.charEnd ?: 0
            gaps +=
                record(GapKind.GAP_KIND_G5_NLP_DARK, span(0, end, ""), emptyList())
                    .setDisposition(Disposition.DISPOSITION_DEGRADED)
                    .build()
        }

        return gaps.sortedWith(compareBy({ it.span.start }, { it.span.end }))
    }

    /**
     * A grounded value that needs nothing from the model. A date, an amount or a number IS the
     * value the planner will use; a person, a place or an organisation is only a hint until an
     * attribute claims it — which is why `Praze` is a gap and `2025` is not. A number that names
     * something (an account code) is the exception, [namesSomething] (#139).
     */
    private fun selfGrounding(value: ValueFinding): Boolean =
        value.kind == ValueKind.VALUE_KIND_GROUNDED &&
            value.grounding.kind in setOf("DATE", "MONEY", "MISC")

    /**
     * ttr-server#139 — a grounded number that NAMES something rather than counting it: `účet 501001`,
     * `account 501001`. The NER typed it a number (NameTag `cnec:n_`, Stanza `CARDINAL`), so no path
     * proposed it for a lookup, and [selfGrounding] then let it leave with no attribution and no gap:
     * the code was lost and nothing asked. It is a G3 instead, so the turn can ask.
     *
     * Told apart from a quantity by WORD ORDER, never by the number: the value's head token carries a
     * digit and FOLLOWS the NOUN/PROPN it hangs off, with no `case` of its own. A count comes before
     * what it counts (`top 10 products`, `10 nejlepších prodejen`); a code comes after what it names
     * (`account 501001`, `store 42`, `účet 501001`). A threshold has its preposition (`sales above
     * 1000`), an amount a verb takes names nothing, and a bare number and a parse-less value (a re-gate
     * of a parse-less lattice) have no noun to name: all of them stay self-grounding.
     *
     * Not by relation: live on hartland (resolver 0.11.6, 2026-09-29) cs Stanza hung the code off
     * `účet` as `dep` in `účet 501001` but as `nummod` in `Náklady na účet 501001 v roce 2025`, and a
     * relation rule missed the second. The one noun a count FOLLOWS is a rank word: cs `Top 10` hangs
     * `10` off the noun `Top` ([RANK_HEADS]).
     */
    private fun namesSomething(
        value: ValueFinding,
        tokens: List<Token>,
    ): Boolean {
        if (value.grounding.kind != "MISC") return false
        val inSpan =
            tokens.indices.filter { i ->
                tokens[i].charStart >= value.span.start && tokens[i].charEnd <= value.span.end
            }
        // the span's head: the one token whose own head lies outside it (`depHead` is 1-based, 0 = root)
        val head = inSpan.firstOrNull { (tokens[it].depHead - 1) !in inSpan } ?: return false
        val token = tokens[head]
        if (token.text.none { it.isDigit() }) return false
        val governorIdx = token.depHead - 1
        val governor = tokens.getOrNull(governorIdx) ?: return false
        if (governor.upos.uppercase() !in setOf("NOUN", "PROPN")) return false
        if (head < governorIdx) return false // before its noun: it counts it
        if (governor.lemma.ifBlank { governor.text }.lowercase() in RANK_HEADS) return false
        return tokens.none { it.depHead - 1 == head && relation(it) == "case" }
    }

    /** Nouns a COUNT follows (cs `Top 10`: `10` is `nummod` of `Top`). Lower-case lemma or surface. */
    private val RANK_HEADS = setOf("top")

    /** The universal relation, without a language subtype (`nmod:poss` → `nmod`). */
    private fun relation(token: Token): String = token.depRelation.substringBefore(':').lowercase()

    private fun record(
        kind: GapKind,
        span: Span,
        roles: List<org.tatrman.resolver.v1.FrameRole>,
    ): GapRecord.Builder =
        GapRecord
            .newBuilder()
            .setSpan(span)
            .setKind(kind)
            .addAllFrameRoles(roles)
            // The core's own verdict is always UNRESOLVED: it neither ignores a gap nor decides a
            // user confirmed anything. The other dispositions are written by the ladder and by the
            // ask round-trip (RV-P4/P2.4).
            .setDisposition(Disposition.DISPOSITION_UNRESOLVED)

    private fun span(
        start: Int,
        end: Int,
        text: String,
    ): Span =
        Span
            .newBuilder()
            .setStart(start)
            .setEnd(end)
            .setText(text)
            .build()
}
