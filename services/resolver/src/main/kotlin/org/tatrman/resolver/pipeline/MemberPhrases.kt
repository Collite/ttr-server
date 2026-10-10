// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.fuzzy.v1.SpanQuery
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.text.Normalization.fold

/**
 * A member's label written out unquoted, where the parse reads it as a clause — found by asking the
 * member vocabularies, and nothing else, whether the words right after a noun ARE one of their
 * values.
 *
 * *„…s důvodem Nedorazilo včas?“* / *"…with reason Did not get it on time in 2025?"* name a return
 * reason by its label. No proposal path reaches it: the tagger reads the label as what its words
 * are — a verb and an adverb, an auxiliary and a verb — so it is neither a nominal phrase
 * ([MentionLayer], path (a)), nor a proper noun (b), nor a NER entity (c), and the parser hangs the
 * head noun FROM it (`důvodem` is the `obl` of `Nedorazilo`), so no anchor governs it either. The
 * value was never looked up, and the composer, left with an unbound *důvodem*, wrote the reason as
 * the other world spells it (hartland CZ, 2026-10-10: `'Did not get it on time'` against a
 * database that stores `'Nedorazilo včas'` — 0 rows, a share of 0).
 *
 * **The slot is syntactic and narrow; the member vocabulary is the filter, and it must agree
 * verbatim.** A slot is a noun followed directly by a word that starts a clause rather than a
 * phrase: a verb, an auxiliary, an adverb, a particle, an adjective, a determiner or a pronoun — or
 * a noun the user CAPITALISED mid-sentence (*s důvodem Výměna dárku*), which is how a label is
 * typed. A lower-case noun after a noun is a nominal phrase and the nominal paths' business; a
 * proper noun is path (b)'s; a preposition, a conjunction, a number or punctuation starts
 * something else. From the slot the run extends over the following words — prepositions included,
 * because labels have them (*on time*) — up to punctuation, a conjunction, a quoted literal, a
 * universal (a date, a number NER typed) or [MAX_RUN] words.
 *
 * Every prefix of the run of at least [MIN_TOKENS] words is asked about, in ONE `BatchMatch`, of the
 * member vocabularies only (their categories, which carry no other rows). A prefix is confirmed
 * only when a returned MEMBER row is that prefix, verbatim modulo case, diacritics and spacing
 * ([fold]) — a TOKENS hit that merely shares words is not. Why verbatim: the open question "is any
 * stretch of this sentence a member?" is the naive all-spans × fuzzy proposal Q-20 rejected for
 * over-generating (`Stores in Paris` → the return reason *"Parts missing"*); equality with a whole
 * value is a fact about the sentence, not a similarity. The LONGEST confirmed prefix of a slot
 * wins.
 *
 * A confirmed phrase becomes one [DomainSpanCandidate.Origin.MEMBER_PHRASE] candidate, gated in the
 * broad pass like any value against exactly the vocabularies that confirmed it, so its binding
 * comes from the same [Binder] as every other. Its [DomainSpanCandidate.anchorHeadToken] is the
 * slot's noun, which links the value to that noun's mention whenever the noun binds (*důvod* →
 * `reason`).
 *
 * ⚠ **A miss leaves no trace**, as with [OperatorWords]: a slot whose words match no member is not a
 * gap — every *centrum mělo* would otherwise ask the user what *mělo* means. Off with the lookup
 * rung ([LookupRoundConfig.budgetMs] ≤ 0), bounded by its budget, and a failed or slow matcher leaves
 * the question exactly as it was.
 */
object MemberPhrases {
    private val log = LoggerFactory.getLogger(MemberPhrases::class.java)

    /** The shortest label asked about. One word is a single token every other path already sees. */
    internal const val MIN_TOKENS = 2

    /** The longest run asked about. hartland's longest return reason is seven words. */
    internal const val MAX_RUN = 8

    /** At most this many slots per question — a bound, not an expected count. */
    internal const val MAX_SLOTS = 4

    /** A slot's noun. */
    private val HEAD_UPOS = setOf("NOUN", "PROPN")

    /** Words that start a clause — a label the tagger reads as a sentence. */
    private val CLAUSE_START_UPOS = setOf("VERB", "AUX", "ADV", "PART", "ADJ", "DET", "PRON", "INTJ")

    /** Nominal words: a label may start with one only when the user capitalised it. */
    private val NOMINAL_UPOS = setOf("NOUN")

    /** Words that end a run. */
    private val RUN_STOP_UPOS = setOf("PUNCT", "CCONJ", "SCONJ", "SYM")

    /** One slot: the noun, and the words after it a label may span (token indices, in order). */
    data class Slot(
        val head: Int,
        val run: List<Int>,
    )

    /** A confirmed label: its token extent, its surface, and the member vocabularies holding it. */
    data class Phrase(
        val head: Int,
        val firstToken: Int,
        val lastToken: Int,
        val start: Int,
        val end: Int,
        val text: String,
        val attributeRefs: List<String>,
        val categories: List<String>,
    )

    /**
     * The slots of [parse], in token order, capped at [MAX_SLOTS]. Empty without a dep parse — the
     * parse-less floor already offers every n-gram — and empty when the estate has no member
     * vocabulary to ask.
     */
    fun slots(
        parse: AnalyzeResponse,
        literals: Literals = Literals.NONE,
    ): List<Slot> {
        val tokens = parse.tokensList
        if (tokens.none { it.depHead > 0 }) return emptyList()
        val universal =
            parse.entitiesList
                .filter { UniversalClassifier.isUniversal(it.label, it.normalizedValue, it.text) }
                .map { it.charStart until it.charEnd }

        fun blocked(i: Int): Boolean {
            val t = tokens[i]
            val mid = (t.charStart + t.charEnd) / 2
            return i in literals.tokens || universal.any { mid in it }
        }

        fun startsLabel(i: Int): Boolean {
            val t = tokens[i]
            val upos = t.upos.uppercase()
            return when {
                upos in CLAUSE_START_UPOS -> true
                upos in NOMINAL_UPOS -> t.text.firstOrNull()?.isUpperCase() == true
                else -> false
            }
        }

        val out = mutableListOf<Slot>()
        for (h in 0 until tokens.size - 1) {
            if (tokens[h].upos.uppercase() !in HEAD_UPOS || blocked(h)) continue
            val first = h + 1
            if (blocked(first) || !startsLabel(first)) continue
            val run = mutableListOf<Int>()
            var i = first
            while (i < tokens.size && run.size < MAX_RUN) {
                if (tokens[i].upos.uppercase() in RUN_STOP_UPOS || blocked(i)) break
                run += i
                i++
            }
            if (run.size >= MIN_TOKENS) out += Slot(h, run)
            if (out.size == MAX_SLOTS) break
        }
        return out
    }

    /**
     * Ask the member vocabularies about every prefix of every slot, in one `BatchMatch`, and keep the
     * longest prefix per slot that a member row spells exactly. [text] is the question as typed, so
     * a phrase's surface is what the user wrote (`charStart` … `charEnd`), spacing included.
     */
    suspend fun find(
        fuzzy: FuzzyClient,
        parse: AnalyzeResponse,
        text: String,
        literals: Literals,
        entityTypes: List<ResolverEntityType>,
        thresholds: ResolverThresholds,
        config: LookupRoundConfig,
    ): List<Phrase> {
        if (config.budgetMs <= 0) return emptyList()
        val vocabularies = entityTypes.filter { it.memberVocabulary }
        if (vocabularies.isEmpty()) return emptyList()
        val slots = slots(parse, literals)
        if (slots.isEmpty()) return emptyList()
        val tokens = parse.tokensList
        val categories = vocabularies.flatMap { it.categories }.distinct()
        val refByCategory = vocabularies.flatMap { v -> v.categories.map { it to v.ref } }.toMap()

        // (slot, prefix length, surface) — longest first within a slot, so the first confirmed wins.
        val windows =
            slots.flatMap { slot ->
                (slot.run.size downTo MIN_TOKENS).map { n ->
                    val first = tokens[slot.run.first()]
                    val last = tokens[slot.run[n - 1]]
                    Triple(slot, n, surfaceOf(text, first.charStart, last.charEnd))
                }
            }
        val request =
            BatchMatchRequest
                .newBuilder()
                .addAllSpans(
                    windows.map { (_, _, surface) ->
                        SpanQuery
                            .newBuilder()
                            .setQuery(surface)
                            .addAllCategories(categories)
                            .setLimit(config.maxCandidates)
                            .build()
                    },
                ).build()
        val response =
            try {
                withTimeoutOrNull(config.budgetMs) { fuzzy.batchMatch(request) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("member-phrase lookup failed — treated as NO ANSWER, the words stay unproposed", e)
                null
            }
        if (response == null) return emptyList()

        val found = mutableListOf<Phrase>()
        val settled = mutableSetOf<Slot>()
        windows.forEachIndexed { i, (slot, n, surface) ->
            if (slot in settled) return@forEachIndexed
            val key = key(surface)
            // The category is checked HERE as well as asked for: a matcher that ignored the filter
            // must not be able to turn a clause into a model object.
            val exact =
                response.resultsList
                    .getOrNull(i)
                    ?.matchesList
                    .orEmpty()
                    .filter {
                        it.source == SourceTag.MEMBER &&
                            it.category in refByCategory &&
                            it.score >= thresholds.bind &&
                            key(it.candidate) == key
                    }
            if (exact.isEmpty()) return@forEachIndexed
            settled += slot
            val firstToken = slot.run.first()
            val lastToken = slot.run[n - 1]
            val cats = exact.map { it.category }.distinct()
            found +=
                Phrase(
                    head = slot.head,
                    firstToken = firstToken,
                    lastToken = lastToken,
                    start = tokens[firstToken].charStart,
                    end = tokens[lastToken].charEnd,
                    text = surface,
                    attributeRefs = cats.mapNotNull { refByCategory[it] }.distinct(),
                    categories = cats,
                )
        }
        return found.sortedBy { it.start }
    }

    /**
     * The confirmed phrases as candidates for the broad pass. A phrase that overlaps a span another
     * path already proposed is dropped: that path saw the words first, and two values over the same
     * characters would be two answers to one question.
     */
    fun candidates(
        phrases: List<Phrase>,
        proposed: List<DomainSpanCandidate>,
        parse: AnalyzeResponse,
    ): List<DomainSpanCandidate> =
        phrases
            .filter { p -> proposed.none { it.start < p.end && p.start < it.end } }
            .map { p ->
                val head = headOf(p, parse)
                DomainSpanCandidate(
                    text = p.text,
                    start = p.start,
                    end = p.end,
                    gatedEntityRefs = p.attributeRefs,
                    categories = p.categories,
                    anchored = false,
                    origin = DomainSpanCandidate.Origin.MEMBER_PHRASE,
                    headToken = head,
                    lemma = parse.tokensList[head].lemma,
                    anchorHeadToken = p.head,
                )
            }

    /** The token whose `dep_head` leaves the phrase — its syntactic head; the first token otherwise. */
    private fun headOf(
        p: Phrase,
        parse: AnalyzeResponse,
    ): Int {
        val range = p.firstToken..p.lastToken
        return range.firstOrNull { i -> (parse.tokensList[i].depHead - 1) !in range } ?: p.firstToken
    }

    private fun surfaceOf(
        text: String,
        start: Int,
        end: Int,
    ): String = if (start in 0..end && end <= text.length) text.substring(start, end) else ""

    /** Verbatim modulo case, diacritics and spacing — a stored `char(n)` value's padding included. */
    private fun key(s: String): String = fold(s.trim()).replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("\\s+")
}
