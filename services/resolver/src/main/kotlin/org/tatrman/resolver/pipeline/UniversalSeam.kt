// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.fuzzy.v1.TargetClass
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.model.Reach
import org.tatrman.resolver.v1.UniversalEntityType
import org.tatrman.text.Normalization.fold

/**
 * UD (ttr-server#118) — the seam between a universal NER reading and the member reading proposed
 * over the same characters, decided once the gate has spoken (UD contracts §4).
 *
 * `SpanProposal` gives the governed argument of a value-bearing anchor a DUAL READING when the NER
 * typed it a place or a person (`Stores in TN`, `TN` = GPE): the governed pair, carrying the NER
 * entity it reads ([DomainSpanCandidate.dualReadingOf]). The universal layer has meanwhile typed the
 * same entity LOCATION. Both cannot reach the lattice — the composer would see two values for one
 * filter, and `Gaps` would raise a G3 on the grounded twin of a value that is attributed — so
 * exactly one reading keeps the span:
 *
 * - a dual reading that found a MEMBER (bound or ambiguous — an ask among members is still a member
 *   reading, ⚑UD-2) **supersedes** the universal it was proposed over: the universal is removed from
 *   the list that grounding, the lattice and the door read. The user named the entity, so the value
 *   is that entity's before it is a place.
 * - a dual reading that found no member is **withdrawn** (A-UD-2, A-UD-4): the universal stands
 *   alone, and the question reads exactly as it did before UD. Kept, it would reach the lattice as a
 *   second LITERAL beside the grounded place, with a second G3 over the same characters.
 *
 * **Only a member speaks** (✅UD-6, A-UD-4). The reason to take the span from the place is that the
 * value is a MEMBER of what the sentence scoped. A declared row — a model object's alias, an
 * operator word — found under the same characters says only that the place is spelled like a term
 * the estate declared (`Sales in Mobile` with a `mobile` channel alias), and the place stands.
 *
 * **…and only a member the governor reaches** (✅UD-7, A-UD-7, found by the C5 live drill). The
 * OPEN half asks every member vocabulary, and one hit in an entity the sentence never scoped is not
 * that entity's member: `Stores in Paris` found the return reason "Parts missing" (a TOKENS match),
 * and M3 cannot catch it, because it breaks ties and a lone member is no tie. So [inScope] drops,
 * before the Binder sees them, the member rows of a dual reading whose entity none of the anchor's
 * owners ([DomainSpanCandidate.dualReadingScope]) reaches — the predicate M3 uses: the owner itself,
 * its owning entity, or an entity that declares a reach from it. `Customers in TN` keeps
 * `customer_address.state` (declared reach from `customer`); `Paris` stays a place.
 *
 * **…or, for a place nothing governs, only a member that IS the name** (UD-P3). A bare place
 * (`mělo Brno DC nulové zásoby`) has no anchor, so `NamedMembers` scopes its reading to the
 * vocabularies whose own values the NER calls places, and [inScope] admits only a member whose value
 * is the span, whole. Superseding and withdrawing are the same as for a governed reading.
 *
 * **Paired by the entity, not by geometry** (A-UD-3, review-108 F1). The universal's extent is the
 * NER engine's; the candidate's is the parse tokens'. They need not agree to the character —
 * NameTag's end overshoots a hyphenated name, and an anchor word inside the name is left out of the
 * value — so a candidate supersedes the universal whose `[start, end)` is exactly the entity it was
 * proposed over, and a candidate that merely overlaps a universal by some other path (an accident of
 * geometry, not a reading of the same words) never removes anything.
 *
 * **A calendar word inside a bound grain is the grain, not a date (ttr-server#152).** English NER
 * types the calendar word of a grain phrase a DATE — `month` in *by month*, one span `quarter in
 * 2025` across *by quarter in 2025*, the adjective in *Monthly revenue* — and grounding read each
 * against today: a second, current-month range beside the year asked for, or the current quarter
 * INSTEAD of it. Where a declared phrase the matcher confirmed as a model object shares words with
 * a DATE, the words are the phrase's: the date keeps only the rest ([grainClaim]), and a date with
 * nothing left is superseded like a place a member took. Paired by GEOMETRY here, unlike the dual
 * reading: the grain was never proposed over the date, it merely shares its words, and which words
 * is exactly the question. A declared phrase that found nothing takes nothing — the date stands,
 * and the phrase, when it lay inside the date, is withdrawn with it ([withdrawnFromDate]).
 *
 * Contenders are counted AFTER `GateSpans.resolveOpenSiblings`, which has already collapsed the pair
 * to the half that spoke — by the same [speaks] — so "some dual reading found a member" is
 * well-defined and counted once (§4.5). Pure: the pipeline calls it once per fresh resolve, before
 * `GroundingRung.ground`, so a superseded universal is never grounded, never assembled and never
 * reported.
 */
object UniversalSeam {
    /**
     * @property universals the universal bindings that keep their span — what grounding, the lattice
     *   and the door read from here on
     * @property gated the gated spans, minus every dual reading that found no member
     * @property superseded each removed universal with the span that won it — a dual reading, or a
     *   grain that took all of a date's words (ttr-server#152) — for the log
     * @property trimmed each date a grain took SOME words of, with what it kept (ttr-server#152): the
     *   kept one is in [universals], the original is not
     */
    data class Result(
        val universals: List<UniversalBinding>,
        val gated: List<GatedSpan>,
        val superseded: List<Pair<UniversalBinding, GatedSpan>>,
        val trimmed: List<Pair<UniversalBinding, UniversalBinding>> = emptyList(),
    )

    /**
     * Whether a gated span found something that counts: for a dual reading, a MEMBER row (✅UD-6);
     * for every other span, any contender, as it always has been. The one definition, read here and
     * by `GateSpans.resolveOpenSiblings`, so the two cannot disagree about which half spoke.
     */
    fun speaks(g: GatedSpan): Boolean =
        if (g.candidate.dualReading) {
            g.contenders.any { it.match.source == SourceTag.MEMBER }
        } else {
            g.contenders.isNotEmpty()
        }

    /**
     * ✅UD-7 — the matches a dual reading may be decided on: every non-member row, and each MEMBER
     * row whose entity one of [DomainSpanCandidate.dualReadingScope] reaches. Every other candidate
     * gets [matches] unchanged. Called by the broad-pass gate BEFORE the Binder, so its verdict —
     * bind, ask, or nothing — is made over the rows that may answer, and a filtered-out member is
     * never an option either. The lookup rounds do not call it because they never re-gate a dual
     * reading: they re-ask G1, G3 and G4 gaps only, a dual reading that survived the seam found a
     * member (so it has none of those), and a withdrawn one has no span left to attach to. A round
     * that starts re-asking G2 must call this first.
     *
     * The entity of a member row is the one `Binder`'s M3 names: [memberOwners] maps its category to
     * the owning entity, [owners] lifts an attribute to its entity.
     */
    fun inScope(
        matches: List<FuzzyMatch>,
        candidate: DomainSpanCandidate,
        owners: Map<String, String>,
        reach: Map<String, List<Reach>>,
        memberOwners: Map<String, String>,
    ): List<FuzzyMatch> {
        if (!candidate.dualReading) return matches
        // UD-P3 — a NAMED_MEMBER reading has no anchor of its own: its scope is the vocabularies whose
        // values the NER names (the data's counterpart of the reach), only a member that IS the name
        // answers for it as written, and the question's model objects decide the rest (I-13, S2).
        if (candidate.origin == DomainSpanCandidate.Origin.NAMED_MEMBER) {
            return NamedMembers.admitted(matches, candidate, owners, reach, memberOwners)
        }
        val scope = candidate.dualReadingScope
        return matches.filter { m ->
            if (m.source != SourceTag.MEMBER) return@filter true
            val owner = memberOwners[m.category] ?: m.category
            val entity = owners[owner]?.takeIf { it.isNotBlank() } ?: owner
            scope.any { g ->
                val governorEntity = owners[g]?.takeIf { it.isNotBlank() } ?: g
                entity == g || entity == governorEntity || reach[entity].orEmpty().any { it.factRef == g }
            }
        }
    }

    /**
     * @param parse the question's parse, for its tokens: a date that runs INTO a grain is cut back
     *   to its own words, and a cut is made between words, never inside one. Null (or a parse with
     *   no tokens) leaves only the whole-date case: a date wholly inside a grain is superseded, one
     *   that runs past it stands as it came.
     * @param text the question, where the cut date's words are read from.
     */
    fun supersede(
        universals: List<UniversalBinding>,
        gated: List<GatedSpan>,
        parse: AnalyzeResponse? = null,
        text: String = "",
    ): Result {
        val answered = gated.filter { it.candidate.dualReading && speaks(it) }
        val superseded =
            universals.mapNotNull { u ->
                answered
                    .firstOrNull { it.candidate.dualReadingOf == (u.start to u.end) }
                    ?.let { u to it }
            }
        val lost = superseded.map { it.first }.toSet()
        val grains = gated.filter { bindsModelObject(it) }
        val kept = mutableListOf<UniversalBinding>()
        val grainSuperseded = mutableListOf<Pair<UniversalBinding, GatedSpan>>()
        val trimmed = mutableListOf<Pair<UniversalBinding, UniversalBinding>>()
        for (u in universals) {
            if (u in lost) continue
            when (val claim = grainClaim(u, grains, parse?.tokensList.orEmpty(), text)) {
                null -> kept += u
                is GrainClaim.Whole -> grainSuperseded += u to claim.by
                is GrainClaim.Edge -> {
                    kept += claim.rest
                    trimmed += u to claim.rest
                }
            }
        }
        val dates = universals.filter { it.entityType == UniversalEntityType.DATE }
        return Result(
            universals = kept,
            gated =
                gated.filterNot {
                    (it.candidate.dualReading && !speaks(it)) || withdrawnFromDate(it, dates)
                },
            superseded = superseded + grainSuperseded,
            trimmed = trimmed,
        )
    }

    // ── ttr-server#152 — a calendar word inside a bound grain is the grain, not a date ──────────

    /** What a bound declared phrase takes from a date it shares words with. */
    private sealed interface GrainClaim {
        /** Every word of the date is the grain's (`month` in `by month`, `Monthly`): it is not a date. */
        data class Whole(
            val by: GatedSpan,
        ) : GrainClaim

        /** The date ran into the grain at one edge (`quarter in 2025`): it keeps the rest (`2025`). */
        data class Edge(
            val rest: UniversalBinding,
        ) : GrainClaim
    }

    /**
     * A declared phrase that BOUND a model object (`by month` → the month attribute): a mention the
     * matcher confirmed. Not an operator word, a member, nor a phrase that found nothing — only a
     * declared model term says "these words name a column", and only a confirmed one has said it.
     */
    private fun bindsModelObject(g: GatedSpan): Boolean =
        g.candidate.origin == DomainSpanCandidate.Origin.ANCHOR_PHRASE &&
            g.contenders.any { c ->
                c.match.source != SourceTag.MEMBER &&
                    c.match.targetClass in MODEL_OBJECT_CLASSES
            }

    /** A model object's row; UNSPECIFIED is a declared row from an archive that predates classes. */
    private val MODEL_OBJECT_CLASSES =
        setOf(TargetClass.TARGET_CLASS_MODEL_OBJECT, TargetClass.TARGET_CLASS_UNSPECIFIED)

    /**
     * The words a date is cut between and does not keep at the cut. Punctuation and a conjunction
     * always go ([ALWAYS_CUT]). An adposition or a subordinator goes only when it says no more than
     * the date does without it — `in` of `quarter in 2025` ([WITHIN_WORDS]). Any other one bounds
     * the date (`since`, `until`, `before`, `from`, `od`, `do`, …): it stays, so the kernel reads
     * the range the user wrote, never the year alone — an unknown one too, since keeping a word
     * costs a decline at worst and dropping it a confident wrong range. A determiner is not cut
     * either — `this` of `month this year` is the period's own word.
     */
    private fun isCut(t: Token): Boolean =
        t.upos in ALWAYS_CUT || (t.upos in CUT_IF_WITHIN && fold(t.lemma.ifBlank { t.text }) in WITHIN_WORDS)

    private val ALWAYS_CUT = setOf("CCONJ", "PUNCT")

    private val CUT_IF_WITHIN = setOf("ADP", "SCONJ")

    /** Folded lemmas, both languages: a date's "within", which a bare date already means. */
    private val WITHIN_WORDS = setOf("in", "during", "within", "for", "of", "at", "on", "v", "ve", "za", "behem")

    /**
     * Which of [u]'s words a bound grain takes, or null when it takes none.
     *
     * English NER types the calendar word of a grain phrase a date: `month` in *by month*, and,
     * when the year follows, ONE span `quarter in 2025` across *by quarter in 2025*. Grounded, the
     * first became the current month beside the year the user asked for — two ANDed ranges — and
     * the second the current quarter, with 2025 gone. The grain is what the user said those words
     * for, so a DATE keeps only the words no bound grain has:
     *
     *  - **none left** — it is superseded, and nothing grounds it;
     *  - **some left, on one side** — it is cut back to them (minus the words [isCut] drops at
     *    the cut), and the kernel is asked about what the user wrote as a date: `2025`, `since
     *    2024`. The cut date is the user's words only: the NER's normalisation described the wider
     *    span (`quarter in 2025`), so it does not ride along — what the kernel cannot ground stays
     *    unnormalised, never the old span's value;
     *  - **some left on both sides** — a grain in the middle of a date is not this rule's to split,
     *    and the date stands as it came.
     *
     * DATE only, and only a [bindsModelObject] phrase: a phrase that found nothing takes nothing,
     * so a question whose grain the estate never declared reads exactly as before.
     */
    private fun grainClaim(
        u: UniversalBinding,
        grains: List<GatedSpan>,
        tokens: List<Token>,
        text: String,
    ): GrainClaim? {
        if (u.entityType != UniversalEntityType.DATE) return null
        val over = grains.filter { it.candidate.start < u.end && u.start < it.candidate.end }
        if (over.isEmpty()) return null
        val inGrain = { at: Int -> over.any { at >= it.candidate.start && at < it.candidate.end } }
        val words = tokens.indices.filter { mid(tokens[it]) in u.start until u.end }
        if (words.isNotEmpty() && words.none { inGrain(mid(tokens[it])) }) return null
        if (words.isEmpty()) {
            // No tokens to cut between: only a date the grains cover to the last character goes.
            return if ((u.start until u.end).all(inGrain)) GrainClaim.Whole(over.first()) else null
        }
        val rest =
            words
                .filterNot { inGrain(mid(tokens[it])) }
                .dropWhile { isCut(tokens[it]) }
                .dropLastWhile { isCut(tokens[it]) }
        if (rest.isEmpty()) return GrainClaim.Whole(over.first())
        // a gap inside what is left means the grain sat in the middle
        if (rest.last() - rest.first() + 1 != rest.size) return null
        val start = tokens[rest.first()].charStart
        val end = tokens[rest.last()].charEnd
        if (start == u.start && end == u.end) return null
        if (start < 0 || end > text.length || start >= end) return null
        val cut = text.substring(start, end)
        return GrainClaim.Edge(u.copy(start = start, end = end, text = cut, rawText = cut, normalizedValue = ""))
    }

    /**
     * A declared phrase proposed over a date's words that the matcher did NOT confirm gives them
     * back: the date stands, and no unbound mention (and no G1) is left on words that were a date
     * before `SpanProposal` offered them (`Monthly`, when the estate's `monthly` is not found).
     */
    private fun withdrawnFromDate(
        g: GatedSpan,
        dates: List<UniversalBinding>,
    ): Boolean =
        g.candidate.origin == DomainSpanCandidate.Origin.ANCHOR_PHRASE &&
            g.contenders.isEmpty() &&
            dates.any { g.candidate.start >= it.start && g.candidate.end <= it.end }

    private fun mid(t: Token): Int = (t.charStart + t.charEnd) / 2
}
