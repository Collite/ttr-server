// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.slf4j.LoggerFactory
import org.tatrman.nlp.v1.AnalyzeResponse

/**
 * MH T2 — the SYNTACTIC SLOT a proposed span sits in.
 *
 * The problem it exists for: one word, two objects. On hartland `prodejna` is the store
 * dimension's label AND a form of the Stores-channel term pinned to the sales fact, so the
 * Binder sees two unrelated identities in one tie band and — correctly, on the evidence it had —
 * refuses. But the sentence already says which one is wanted: you *count* things, you *group by*
 * things, and you *restrict a measure* by the thing being measured. Since MS the two candidates
 * differ in `objectKind`, so the slot's kind preference can break the tie
 * (`Binder.decide`, contracts §7.3). ⚑ ttr-demo#62 (ruled 2026-10-12): a FILTER is the one slot
 * that does not break THIS tie — "revenue of stores" may mean either, and the user is asked.
 *
 * **Why the slot is computed here and not in the Binder** (architecture A3): `GateSpans.gate`
 * never sees the parse — `ResolverPipeline` hands it candidates only — and `ReGate` synthesises
 * candidates with `headToken = -1` for a span it is re-deciding. So the slot is stamped onto the
 * candidate in the pipeline, where the parse is in scope, and the Binder reads
 * `candidate.slot`. A re-gate therefore gets [SlotHint.NONE], which is structurally right: the
 * broad pass already slotted that span, and a re-decision with no parse must not invent one.
 *
 * The same signals `FrameRoles` (R3–R9) already trusts, read at the same tokens: the `case`
 * child's lemma, the head token's `deprel`, the `conj` sibling. What is new is only *when* they
 * are read — before binding instead of after it — and one table (`count-heads`) that had no
 * reader at all, because nothing in the system had ever looked at a count quantifier.
 *
 * ⚠ Under MH a mis-attached preposition costs a wrong OBJECT, where before it cost a wrong ROLE.
 * That is why the rule is a *preference* (✅MH-D2) that never empties the band, and why T3's
 * reachability check can veto it.
 *
 * Pure, deterministic, no I/O.
 */
object SlotHints {
    private val log = LoggerFactory.getLogger(SlotHints::class.java)

    /** How far up the dependency chain a span looks for the candidate it attaches to. */
    private const val MAX_HEAD_HOPS = 3

    private const val KIND_MEASURE = "measure"
    private const val KIND_ENTITY_WITH_MEASURES = "entity_with_measures"

    /** Deprels that make a bare (preposition-less) nominal a restriction of its head. */
    private val BARE_FILTER_RELATIONS = setOf("nmod", "obl", "compound")

    private val SUBJECT_RELATIONS = setOf("nsubj", "nsubj:pass")

    /** MH tier M — the kinds that can OWN a member, and so can govern one. */
    private val GOVERNING_KINDS = setOf("entity", "attribute")

    /** MH tier M — the two candidates a governed value is proposed as (A-MH-1b). */
    private val VALUE_ORIGINS =
        setOf(DomainSpanCandidate.Origin.GOVERNED_VALUE, DomainSpanCandidate.Origin.OPEN_VALUE)

    /**
     * Stamp [SlotHint] onto every `ANCHOR_PHRASE` candidate. Same list, same order, same size —
     * only `slot` is filled, so this cannot change which spans are proposed.
     *
     * Value-origin candidates (`GOVERNED_VALUE`, `PROPER_NOUN`, `NER_ENTITY`, `LITERAL`,
     * `NGRAM_FLOOR`) keep [Slot.NONE]: they are literals, not mentions of model objects, and
     * the Binder's kind rules are about which OBJECT a word names. A governed value carries only its
     * governor ([SlotHint.governorRef]) and, when it is a conjunct, its coordination
     * ([SlotHint.coordGroup]).
     */
    fun stamp(
        parse: AnalyzeResponse,
        candidates: List<DomainSpanCandidate>,
        kindsByRef: Map<String, String>,
        ownersByRef: Map<String, String>,
        lang: String,
        preps: FrameRolePreps,
    ): List<DomainSpanCandidate> {
        val tokens = parse.tokensList
        // No dependency tree ⇒ no slots. The n-gram floor runs in exactly this case (R4-γ), and
        // guessing a slot off token order would be the kind of syntax-free inference the whole
        // anchored-proposal design refuses.
        if (tokens.none { it.depHead > 0 }) return candidates

        val byHead = HashMap<Int, DomainSpanCandidate>()
        for (c in candidates) {
            if (c.origin == DomainSpanCandidate.Origin.ANCHOR_PHRASE && c.headToken >= 0) {
                byHead.putIfAbsent(c.headToken, c)
            }
        }

        fun headIndexOf(depHead: Int): Int = depHead - 1

        fun governorOf(token: Int): Int = if (token in tokens.indices) headIndexOf(tokens[token].depHead) else -1

        fun isConj(token: Int): Boolean = token in tokens.indices && tokens[token].depRelation == "conj"

        /** The `case` child's lemma, exactly as `FrameRoles.prepositionOf` reads it. */
        fun prepOf(head: Int): String? =
            tokens
                .firstOrNull { headIndexOf(it.depHead) == head && it.depRelation == "case" }
                ?.let { (it.lemma.ifBlank { it.text }).lowercase() }

        val countWords = preps.countHeads(lang)

        /**
         * A count quantifier sitting on, or beside, the counted noun. Three attachments, because
         * the UD analyses differ across languages and even across Czech parses of one shape:
         * `kolik` may govern the noun (`det:numgov`), be a dependent of it, or be an `advmod` of
         * the same verb ("How many stores do we have?" attaches `how`→`many`→`stores`).
         */
        fun hasCountWord(head: Int): Boolean {
            if (countWords.isEmpty()) return false
            val headGovernor = governorOf(head)
            return tokens.withIndex().any { (i, t) ->
                i < head &&
                    (t.lemma.ifBlank { t.text }).lowercase() in countWords &&
                    (
                        headIndexOf(t.depHead) == head ||
                            headGovernor == i ||
                            (head in tokens.indices && t.depHead == tokens[head].depHead)
                    )
            }
        }

        /** The conj-sibling candidate, in either direction — a coordination has no privileged end. */
        fun conjSibling(head: Int): DomainSpanCandidate? {
            val child =
                tokens.withIndex().firstOrNull { (_, t) ->
                    t.depRelation == "conj" && headIndexOf(t.depHead) == head
                }
            if (child != null) byHead[child.index]?.let { return it }
            if (head in tokens.indices && tokens[head].depRelation == "conj") {
                return byHead[governorOf(head)]
            }
            return null
        }

        /**
         * True iff some GOVERNED_VALUE candidate hangs from this span: its head token is a
         * dependent of [head], which is exactly where span proposal looked for it
         * (`SpanProposal.governedValues` walks the anchor head's children).
         *
         * ⛑ ttr-demo#62 — this used to read `anchorHeadToken == head` alone, and span proposal never
         * sets that field on a governed value: it belongs to the literal path (RV-P2.1), where it
         * becomes the lattice's `anchor_mention_id`. So rule 4 never fired on a real question, and
         * „Tržby prodejny Praha“ / "Stores in Nashville" read their store word by the filter or
         * subject rule instead. The parse already says which word governs the value; read it here
         * rather than widening a field the lattice publishes.
         *
         * A value hanging by `conj` is not counted: span proposal offers a coordinated noun as a
         * governed value („Compare stores and warehouses“), but it is the span's SIBLING, and
         * coordination is rule 1's to read — a sibling that is no mention decides nothing.
         */
        fun hasGovernedValue(head: Int): Boolean =
            candidates.any {
                it.origin == DomainSpanCandidate.Origin.GOVERNED_VALUE &&
                    (it.anchorHeadToken == head || (governorOf(it.headToken) == head && !isConj(it.headToken)))
            }

        /**
         * ttr-demo#62 — the coordination ruling (Bora, 2026-10-14): of two mentions joined by
         * „a“/„nebo“/"and"/"or", the one with two readings takes the reading where they intersect —
         * so the sibling decides only when it has ONE reading. Its kinds when it does; nothing when it
         * has two readings itself, whatever their kinds („Srovnej prodejny a prodejny“ still asks).
         *
         * One reading is counted the way the Binder's containment collapse counts it: an entity
         * declared beside its own attribute or measure is one answer at two granularities, not two.
         */
        fun coordKinds(sibling: DomainSpanCandidate): Set<String> {
            val refs = sibling.gatedEntityRefs.distinct()
            val owners = refs.mapNotNull { ownersByRef[it]?.takeIf { o -> o.isNotBlank() } }.toSet()
            if (refs.count { it !in owners } != 1) return emptySet()
            // …and in coordination a measure stands for its fact: a sibling that is a channel's
            // revenue lends the channel's kind too (the Binder reads a candidate measure the same way).
            return refs
                .flatMap { ref ->
                    val kind = kindsByRef[ref]
                    val family = if (kind == KIND_MEASURE) ownersByRef[ref]?.let { kindsByRef[it] } else null
                    listOfNotNull(kind, family)
                }.filter { it.isNotBlank() }
                .toSet()
        }

        /** Walk up to [MAX_HEAD_HOPS] governors for the candidate this span attaches to. */
        fun headCandidate(head: Int): DomainSpanCandidate? {
            var i = governorOf(head)
            var hops = 0
            while (i >= 0 && hops < MAX_HEAD_HOPS) {
                byHead[i]?.let { if (it.headToken != head) return it }
                i = governorOf(i)
                hops++
            }
            return null
        }

        /** A ref's FACT: itself when it is one, its owner when it is a measure on one. */
        fun factOf(ref: String): String =
            when (kindsByRef[ref]) {
                KIND_ENTITY_WITH_MEASURES -> ref
                KIND_MEASURE -> ownersByRef[ref] ?: ref
                else -> ref
            }

        fun measureCapable(ref: String): Boolean = kindsByRef[ref] in setOf(KIND_MEASURE, KIND_ENTITY_WITH_MEASURES)

        fun headFields(head: Int): Pair<List<String>, Boolean> {
            val hc = headCandidate(head) ?: return emptyList<String>() to false
            return hc.gatedEntityRefs.map(::factOf).distinct() to hc.gatedEntityRefs.any(::measureCapable)
        }

        // MH tier M — the governor of a VALUE span.
        //
        // The candidate that reaches the Binder with several member rows is the OPEN sibling,
        // whose own `gatedEntityRefs` is every declared type — so the governor cannot come from
        // it. It comes from the GOVERNED_VALUE candidate over the same characters, whose refs
        // ARE the anchor's owners; the two are proposed as a pair (`SpanProposal`, A-MH-1b).
        //
        // Among those owners the governor is the one that could own a member: exactly one
        // entity/attribute. Two of them, or none, is not a governor this rule can use — a fact
        // never selects among member owners (design §2a), and an ambiguous anchor must not have
        // its ambiguity resolved silently by a value.
        val governedRefsBySpan =
            candidates
                .filter { it.origin == DomainSpanCandidate.Origin.GOVERNED_VALUE }
                .associate { (it.start to it.end) to it.gatedEntityRefs }

        fun governorOfValue(c: DomainSpanCandidate): String {
            val refs = governedRefsBySpan[c.start to c.end] ?: return ""
            val governing = refs.filter { kindsByRef[it] in GOVERNING_KINDS }
            return governing.singleOrNull().orEmpty()
        }

        // ttr-demo#62, the coordination ruling (2026-10-14), over VALUES — which governed values are
        // conjuncts of one coordination („Tržby pro Brno a Liberec“). UD hangs every conjunct on the
        // first by `conj`, so a value's coordination is named by its FIRST conjunct's head token: climb
        // `conj` while the governor is itself a value's head. A group needs two heads (the governed and
        // open halves of one span share theirs); a value in no coordination gets -1. What the group
        // decides is `CoordinatedValues`'s.
        val valueHeads =
            candidates
                .filter { it.origin in VALUE_ORIGINS && it.headToken >= 0 }
                .map { it.headToken }
                .toSet()

        fun coordRoot(head: Int): Int {
            var at = head
            var hops = 0
            while (isConj(at) && governorOf(at) in valueHeads && hops++ < tokens.size) at = governorOf(at)
            return at
        }
        val rootByHead = valueHeads.associateWith(::coordRoot)
        val coordinated =
            rootByHead.values
                .groupingBy { it }
                .eachCount()
                .filterValues { it >= 2 }
                .keys

        fun coordGroupOf(c: DomainSpanCandidate): Int = rootByHead[c.headToken]?.takeIf { it in coordinated } ?: -1

        return candidates.map { c ->
            if (c.origin in VALUE_ORIGINS) {
                val governor = governorOfValue(c)
                val group = coordGroupOf(c)
                return@map if (governor.isBlank() && group < 0) {
                    c
                } else {
                    c.copy(slot = SlotHint(governorRef = governor, coordGroup = group))
                }
            }
            if (c.origin != DomainSpanCandidate.Origin.ANCHOR_PHRASE || c.headToken < 0) return@map c
            val head = c.headToken
            val prep = prepOf(head)
            val deprel = if (head in tokens.indices) tokens[head].depRelation else ""

            val hint =
                when {
                    // 1 — coordination first: "srovnej prodejny a web" is a comparison of
                    // channels, and the sibling's KIND is the only thing that says so. It wins
                    // over a count head because "kolik prodejen a webů" is still a comparison.
                    // Only a sibling with ONE reading lends its kind (`coordKinds`, ttr-demo#62).
                    conjSibling(head) != null -> {
                        val sibling = conjSibling(head)!!
                        SlotHint(slot = Slot.COORD_WITH, coordSiblingKinds = coordKinds(sibling))
                    }
                    // 2 — you count THINGS.
                    hasCountWord(head) -> SlotHint(slot = Slot.COUNT_HEAD)
                    // 3 — "podle X" wants a groupable axis.
                    prep != null && prep in preps.grouping(lang) -> {
                        val (refs, capable) = headFields(head)
                        SlotHint(slot = Slot.GROUP_BY, headRefs = refs, headMeasureCapable = capable)
                    }
                    // 4 — a span that governs a value ("prodejna Nashville") names an object
                    // with members. Before FILTER: the two co-occur, and the value is the
                    // stronger signal.
                    hasGovernedValue(head) -> SlotHint(slot = Slot.GOVERNED_VALUE)
                    // 5 — a filter preposition, or a bare nominal hanging off another mention.
                    (prep != null && prep in preps.filter(lang)) ||
                        (prep == null && deprel in BARE_FILTER_RELATIONS && headCandidate(head) != null) -> {
                        val (refs, capable) = headFields(head)
                        SlotHint(slot = Slot.FILTER, headRefs = refs, headMeasureCapable = capable)
                    }
                    // 6 — a subject with nothing else to say about it. No kind preference; it is
                    // here so the reachability rule can still see the clause's head.
                    deprel in SUBJECT_RELATIONS -> {
                        val (refs, capable) = headFields(head)
                        SlotHint(slot = Slot.SUBJECT, headRefs = refs, headMeasureCapable = capable)
                    }
                    else -> SlotHint.NONE
                }

            if (hint.slot != Slot.NONE) {
                log.debug("slot {} for span '{}' (head {}): {}", hint.slot, c.text, head, hint)
            }
            c.copy(slot = hint)
        }
    }
}

/**
 * MH — the syntactic position a span occupies, as far as the Binder needs to know it.
 *
 * Not a frame ROLE: `FrameRoles` answers "what does this mention DO in the query" after binding,
 * over bound mentions; this answers "what kind of thing is the sentence asking for here" before
 * binding, so a tie between two unrelated objects can be broken. They read the same tokens and
 * deliberately stay separate rules — a slot that fired only when a role did would inherit the
 * role's exemptions, which are about measures, not about homonyms.
 */
enum class Slot {
    NONE,
    SUBJECT,
    COUNT_HEAD,
    GROUP_BY,
    GOVERNED_VALUE,
    FILTER,
    COORD_WITH,
}

/**
 * MH — a span's slot plus the facts the Binder's two rules need about its clause.
 *
 * @property headRefs the FACTS of the candidate this span's head attaches to (`FILTER` /
 *   `GROUP_BY` / `SUBJECT`) — a measure is mapped to its owner, so the list is always facts, not
 *   a mix. It is what the reachability rule calls H: the fact the clause is actually about.
 * @property headMeasureCapable true iff any head ref is `measure` or `entity_with_measures`.
 *   Only a measure-capable head makes "tržby z prodejen" a restriction OF a measure; a plain
 *   entity head ("vratky z prodejen" before returns declare measures) still carries [headRefs],
 *   which is what lets the reachability rule fix the slot rule's mis-bind (design.md T2 §4, E6).
 * @property coordSiblingKinds the kinds of the `conj` sibling's refs — "compare X and Y" wants
 *   both sides on the same axis, so the sibling's species is the preference. A measure lends its
 *   fact's kind as well. Empty when the sibling has two readings itself: only an unambiguous
 *   sibling decides (ttr-demo#62, coordination).
 */
data class SlotHint(
    val slot: Slot = Slot.NONE,
    val headRefs: List<String> = emptyList(),
    val headMeasureCapable: Boolean = false,
    val coordSiblingKinds: Set<String> = emptySet(),
    /**
     * MH tier M — for a VALUE span, the ref of the object that GOVERNS it (`stores in TN` ⇒ the
     * store dimension). Blank when the value has no governor, when the governor is a fact (which
     * must never pick among member owners — refuse-over-guess, design §2a), or when the anchor's
     * owners leave it undecidable.
     *
     * Orthogonal to [slot], which is about the span's own syntactic role: a value candidate keeps
     * `Slot.NONE` and carries this, so the object rules stay no-ops on it by construction and only
     * the member-governance rule reads it.
     */
    val governorRef: String = "",
    /**
     * ttr-demo#62 (coordination, 2026-10-14) — for a VALUE span that is a conjunct of a coordination
     * of values („Brno a Liberec“), the head token of the coordination's FIRST conjunct, shared by every
     * conjunct of it; -1 for a value in no coordination and for every non-value span. Read only by
     * `CoordinatedValues`, which decides the reading the conjuncts share.
     */
    val coordGroup: Int = -1,
) {
    companion object {
        /** No slot: a bare word, a fragment, a re-gate, a parse with no tree. Both rules no-op. */
        val NONE = SlotHint()
    }
}
