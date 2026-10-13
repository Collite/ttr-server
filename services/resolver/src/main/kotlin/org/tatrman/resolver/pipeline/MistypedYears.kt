// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.v1.UniversalEntityType

/**
 * ttr-server#206 — a year the NER typed a NUMBER: "Store revenue 2025", „Tržby z tržiště 2025“,
 * „Tržby z prodejen za 2025“. Each means exactly what `in 2025` / „v roce 2025“ means.
 *
 * **Why it was lost.** Which type the NER gives a year depends on the words around it, not on the
 * year:
 *
 *  - Stanza (en, OntoNotes): `CARDINAL` in "Marketplace revenue 2025", `DATE` in "Marketplace revenue
 *    in 2025", "2025 marketplace revenue" and "Revenue 2025 by channel";
 *  - NameTag 3 (cs, CNEC 2.0): `n_` / `nc` in „Tržby z tržiště 2025“, „Tržby 2025“, „… za 2025“,
 *    „… ve 2025“ and „… během 2025“, but `ty` in „… v roce 2025“ and „… v 2025“.
 *
 * A number is universal `MISC`, and the grounding rung offers only DATEs to the kernel, so the year
 * got no interval and no anchor column. After a noun with no preposition `Gaps.namesSomething` (#139)
 * read it as a code — a G3. With a preposition nothing read it at all: the year was dropped with no
 * gap, and the consumer applied its default period instead of the one the user named.
 *
 * **The rule.** A number the NER typed `MISC` is offered to the date kernel as a DATE when it is one
 * token of exactly four digits, in [PLAUSIBLE_YEARS], whose only dependents are punctuation and at
 * most one preposition, and when ONE of these holds:
 *
 *  - **A — a bare year after a measure.** It has no preposition; the token it hangs off lies inside a
 *    mention whose top binding is a MEASURE (object kind `measure`) — a measure is never named by a
 *    number, so the number after it cannot be its code; and it stands right after that mention,
 *    nothing but punctuation between.
 *  - **B — a year after a temporal preposition.** Its preposition (`case` dependent, by lemma or
 *    surface) is in the locale's `temporal-preps` table (`frame-roles.conf`): a closed list of the
 *    prepositions that put a PERIOD on a year — *za*, *v*, *ve*, *během*; *in*, *for*, *during*.
 *
 * …and it STAYS a date only when the kernel grounds it to an anchor column ([settle]); otherwise the
 * value goes back to exactly what the NER said. So the rule proposes and the kernel decides, and
 * nothing is attached that `in 2025` would not attach.
 *
 * What it deliberately does not reach:
 *
 *  - **a number with anything but a preposition hanging off it** — „Tržby za 2000 Kč“: Stanza makes
 *    `2000` the head of the phrase, with `za` as its preposition AND `Kč` under it. A unit makes it an
 *    amount, whatever the preposition says;
 *  - **a number before the measure** — a count precedes what it counts ("1500 units"; „v 2025
 *    kusech“, where the preposition belongs to `kusech`);
 *  - **a bare number after anything that is not a measure** — `Revenue of store 1042`, „Tržby prodejny
 *    2025“, and an entity that merely HAS measures (`entity_with_measures`: "order 2025" may be its
 *    number);
 *  - **a range preposition** (*od*, *do*, *since*, *until*, *through*): the kernel reads the year
 *    span alone, so „do 2025“ would become the calendar year 2025 — a different period from the one
 *    asked, told as an answer. They are not in the table;
 *  - **a bare year the parse hangs off one measure while it stands after another** — `Revenue and
 *    stock on hand 2025`: syntax and word order disagree about whose year it is, so it stays the G3 it
 *    was.
 *
 * Not by relation, for the reason `Gaps.namesSomething` gives: the same year hangs off its measure as
 * `nmod:unmarked`, `nummod` or `dep` from one question to the next.
 *
 * With two measures and a bare year right after the one it hangs off ("Stock on hand and revenue
 * 2025") it attaches: the kernel names ONE anchor column for the estate, so the attribution is the one
 * `in 2025` gets in the same question, and nothing here chooses between two measures' dates.
 */
object MistypedYears {
    /** The object kind whose mention a bare year may attach to (rule A). */
    private const val MEASURE = "measure"

    /**
     * The years a four-digit number may be. The same range chrono reads a calendar year in
     * (`NamedYear.PLAUSIBLE_YEARS`); restated rather than shared because the resolver does not depend
     * on the kernel, and checked here so an amount outside it is never offered at all.
     */
    private val PLAUSIBLE_YEARS = 1900..2999

    private val YEAR_SHAPE = Regex("""\d{4}""")

    /** What may hang off a year and leave it a year: its preposition and punctuation. */
    private val YEAR_DEPENDENTS = setOf("case", "punct")

    /**
     * The spans of [universals] that are years the NER typed a number, keyed as the grounding rung
     * keys them. [gated] is the broad pass's gate (each span with its contenders, the top one first);
     * [kinds] is ref → object kind (`kindsByRef`); [temporalPreps] is the locale's `temporal-preps`
     * table (`FrameRolePreps.temporal`).
     */
    fun find(
        universals: List<UniversalBinding>,
        parse: AnalyzeResponse,
        gated: List<GatedSpan>,
        kinds: Map<String, String>,
        temporalPreps: Set<String>,
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
        return universals
            .filter { it.entityType == UniversalEntityType.MISC }
            .filter { u ->
                val year = yearToken(u, tokens) ?: return@filter false
                val preps = tokens.filter { it.depHead - 1 == year && relation(it) == "case" }
                when (preps.size) {
                    0 -> bareAfterMeasure(year, tokens, measures)
                    1 -> preps.single().let { p -> word(p.lemma) in temporalPreps || word(p.text) in temporalPreps }
                    else -> false
                }
            }.map { it.start to it.end }
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
     * A year stays a DATE only where the kernel grounded it to an anchor column. Anywhere else — the
     * kernel is off, it did not read the span, or the estate names no date column — the value goes back
     * to exactly what the NER said, and so does its grounding: an interval the kernel returned for a
     * span that is a number again must not ride along onto it.
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

    /**
     * The universal's one token, when it has the shape of a year and nothing but its preposition and
     * punctuation hangs off it; null otherwise.
     */
    private fun yearToken(
        universal: UniversalBinding,
        tokens: List<Token>,
    ): Int? {
        val year = tokensIn(tokens, universal.start, universal.end).singleOrNull() ?: return null
        val text = tokens[year].text
        if (!YEAR_SHAPE.matches(text) || text.toInt() !in PLAUSIBLE_YEARS) return null
        if (tokens.any { it.depHead - 1 == year && relation(it) !in YEAR_DEPENDENTS }) return null
        return year
    }

    /** Rule A: hung off a word of a measure mention, and standing right after that mention. */
    private fun bareAfterMeasure(
        year: Int,
        tokens: List<Token>,
        measures: List<IntRange>,
    ): Boolean {
        val governor = tokens[year].depHead - 1
        val measure = measures.firstOrNull { governor in it } ?: return false
        if (year <= measure.last) return false
        return (measure.last + 1 until year).all { tokens[it].upos.equals("PUNCT", ignoreCase = true) }
    }

    private fun tokensIn(
        tokens: List<Token>,
        start: Int,
        end: Int,
    ): List<Int> = tokens.indices.filter { tokens[it].charStart >= start && tokens[it].charEnd <= end }

    private fun word(s: String): String = s.trim().lowercase()

    /** The universal relation, without a language subtype (`nmod:unmarked` → `nmod`). */
    private fun relation(token: Token): String = token.depRelation.substringBefore(':').lowercase()
}
