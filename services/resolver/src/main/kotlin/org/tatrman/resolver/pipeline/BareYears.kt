// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.v1.UniversalEntityType

/**
 * ttr-server#206 — a year written straight after the measure, with no preposition: "Store revenue 2025",
 * „Tržby z tržiště 2025“. People drop the preposition all the time, and the year then means exactly
 * what `in 2025` means.
 *
 * **Why it was lost.** The NER types such a year a NUMBER, not a date — and which one it picks depends
 * on the words around it, not on the year:
 *
 *  - Stanza (en, OntoNotes): `CARDINAL` in "Marketplace revenue 2025", `DATE` in "Marketplace revenue
 *    in 2025", "2025 marketplace revenue" and "Revenue 2025 by channel";
 *  - NameTag 3 (cs, CNEC 2.0): `n_` / `nc` in „Tržby z tržiště 2025“ and „Tržby 2025“, `ty` in
 *    „Tržby z tržiště v roce 2025“.
 *
 * A number is universal `MISC`, and the grounding rung offers only DATEs to the kernel, so the year
 * got no interval and no anchor column. And a number that follows a noun with no preposition is what
 * a CODE looks like (`účet 501001`), so `Gaps.namesSomething` (#139) made it a G3: grounded,
 * unattributed, and the consumer could not honour it.
 *
 * **The rule.** A number the NER typed `MISC` is offered to the date kernel as a DATE when all of:
 *
 *  1. its surface is one token of exactly four digits — the shape of a year (the kernel decides
 *     whether the four digits are a plausible one);
 *  2. that token has no `case` dependent — a preposition makes it something else (`over 2000`, a
 *     threshold), or the NER already typed it (`in 2025`);
 *  3. the token it hangs off lies inside a mention whose top binding is a MEASURE (object kind
 *     `measure`): a measure is never named by a number, so the number after it cannot be its code;
 *  4. it stands right after that mention — nothing but punctuation between the mention's last token
 *     and the year.
 *
 * …and it STAYS a date only when the kernel grounds it to an anchor column ([settle]); otherwise the
 * value goes back to exactly what the NER said, G3 included. So the rule proposes and the kernel
 * decides, and nothing is attached that `in 2025` would not attach.
 *
 * What the rule deliberately does not reach:
 *
 *  - **a number before the measure** — a count precedes what it counts ("1500 units");
 *  - **a number after anything that is not a measure** — `Revenue of store 1042`, „Tržby prodejny
 *    2025“: the number stands next to the store, and a number after a thing is what names it. An
 *    entity that merely HAS measures (`entity_with_measures`, an order or an invoice) is excluded for
 *    the same reason: "order 2025" may be its number;
 *  - **a year the parse hangs off one measure while it stands after another** — `Revenue and stock on
 *    hand 2025`, „Stav zásob a tržby 2025“. Word order and syntax disagree about whose year it is, so
 *    it is not attached at all and stays the G3 it was (rule 4: the mention it hangs off must be the
 *    one it stands after).
 *
 * Not by relation, for the reason `Gaps.namesSomething` gives: the same year hangs off its measure as
 * `nmod:unmarked` (en) or `nummod` (cs) in one question and as `dep` in the next.
 *
 * With two measures in a question and the year right after the one it hangs off ("Stock on hand and
 * revenue 2025"), it attaches: the kernel names ONE anchor column for the estate, so the attribution
 * is the one `in 2025` gets in the same question, and nothing here chooses between two measures'
 * dates.
 */
object BareYears {
    /** The object kind whose mention a bare year may attach to (rule 3). */
    private const val MEASURE = "measure"

    private val YEAR_SHAPE = Regex("""\d{4}""")

    /**
     * The spans of [universals] that are bare years beside a measure, keyed as the grounding rung keys
     * them. [gated] is the broad pass's gate (each span with its contenders, the top one first); [kinds]
     * is ref → object kind (`kindsByRef`).
     */
    fun find(
        universals: List<UniversalBinding>,
        parse: AnalyzeResponse,
        gated: List<GatedSpan>,
        kinds: Map<String, String>,
    ): Set<Pair<Int, Int>> {
        val tokens = parse.tokensList
        if (tokens.isEmpty()) return emptySet()
        // Each measure mention as the token range it covers. The top contender speaks for the span,
        // and its kind is read the way the Binder reads it: by the match's target ref.
        val measures =
            gated.mapNotNull { span ->
                val top = span.contenders.firstOrNull() ?: return@mapNotNull null
                if (!kinds[top.match.targetRef].equals(MEASURE, ignoreCase = true)) return@mapNotNull null
                val covered = tokensIn(tokens, span.candidate.start, span.candidate.end)
                if (covered.isEmpty()) null else covered.first()..covered.last()
            }
        if (measures.isEmpty()) return emptySet()
        return universals
            .filter { it.entityType == UniversalEntityType.MISC && isBareYear(it, tokens, measures) }
            .map { it.start to it.end }
            .toSet()
    }

    /** [universals] with each of [years] typed a DATE — what the grounding rung is offered. */
    fun asDates(
        universals: List<UniversalBinding>,
        years: Set<Pair<Int, Int>>,
    ): List<UniversalBinding> =
        if (years.isEmpty()) {
            universals
        } else {
            universals.map { if ((it.start to it.end) in years) it.copy(entityType = UniversalEntityType.DATE) else it }
        }

    /** What the lattice is assembled from once the kernel has spoken. */
    data class Settled(
        val universals: List<UniversalBinding>,
        val grounded: Map<Pair<Int, Int>, GroundingRung.Grounded>,
    )

    /**
     * A bare year stays a DATE only where the kernel grounded it to an anchor column. Anywhere else —
     * the kernel is off, it did not read the span, or the estate names no date column — the value goes
     * back to exactly what the NER said, and so does its grounding: an interval the kernel returned for
     * a span that is a number again must not ride along onto it.
     */
    fun settle(
        original: List<UniversalBinding>,
        years: Set<Pair<Int, Int>>,
        grounded: Map<Pair<Int, Int>, GroundingRung.Grounded>,
    ): Settled {
        if (years.isEmpty()) return Settled(original, grounded)
        val attached = years.filter { grounded[it]?.anchorAttributeRef?.isNotBlank() == true }.toSet()
        val dropped = years - attached
        return Settled(
            universals = asDates(original, attached),
            grounded = grounded.filterKeys { it !in dropped },
        )
    }

    private fun isBareYear(
        universal: UniversalBinding,
        tokens: List<Token>,
        measures: List<IntRange>,
    ): Boolean {
        val covered = tokensIn(tokens, universal.start, universal.end)
        val year = covered.singleOrNull() ?: return false // (1) one token…
        if (!YEAR_SHAPE.matches(tokens[year].text)) return false // …of four digits
        if (tokens.any { it.depHead - 1 == year && relation(it) == "case" }) return false // (2)
        val governor = tokens[year].depHead - 1
        val measure = measures.firstOrNull { governor in it } ?: return false // (3)
        if (year <= measure.last) return false // (4) after the measure…
        return (measure.last + 1 until year).all { tokens[it].upos.equals("PUNCT", ignoreCase = true) } // …right after
    }

    private fun tokensIn(
        tokens: List<Token>,
        start: Int,
        end: Int,
    ): List<Int> = tokens.indices.filter { tokens[it].charStart >= start && tokens[it].charEnd <= end }

    /** The universal relation, without a language subtype (`nmod:unmarked` → `nmod`). */
    private fun relation(token: Token): String = token.depRelation.substringBefore(':').lowercase()
}
