// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.SpanQuery
import org.tatrman.fuzzy.v1.TargetClass
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.v1.EvidenceClass
import org.tatrman.ttr.lexicon.LexiconValidator

/**
 * LP contracts §3 — **which words say HOW a quoted literal restricts its attribute**.
 *
 * The third application of the RV-35 trigger-slice pattern, after operators and `ground:`, and the
 * one whose retrieval could not simply reuse span proposal. `GroundingTriggers` asks about the
 * MENTION spans, because a grounding trigger is a word the estate already proposes as a mention
 * (*roce*, *Q1*). A predicate form is not: *začínající na* is a participle and a preposition, and
 * `SpanProposal` proposes content subtrees around model anchors — on the hero question
 * *Ukaž dodací místa začínající na "Pelex"* the ONLY span it proposes is `dodací místa`
 * (`VerbatimLatticeTest` asserts exactly that, and it is the right behaviour: proposing every
 * participle would be the over-generation Q-20 removed).
 *
 * So the retrieval is anchored on the **literal** instead, which is what makes it cheap and is
 * what the design meant by *match first, locate second*: a quoted literal is a rare, explicit,
 * user-typed marker, and the words that qualify it sit in a window three tokens wide immediately
 * before it (§2.1's own bound, [SpanProposal.MAX_ANCHOR_DISTANCE]). A question with no literal
 * asks nothing here at all.
 *
 * **Left only, and that is a claim about language rather than an optimisation.** Czech and English
 * both put the predicate before the string it applies to — *začínající na "Pelex"*, *starts with
 * "Pelex"*. A form to the RIGHT of a literal is qualifying something else.
 *
 * **One BatchMatch still** (B-T1): these are extra slots on the pass the core already makes, after
 * the gate's and the grounding triggers', never a second round trip.
 */
object PredicateTriggers {
    /**
     * D1/D2 — the predicate a negator in front of a trigger turns it into, both ways.
     *
     * Declared FIRST: [CATEGORIES] reads it, and an object's properties initialise in order.
     */
    private val NEGATIONS: Map<String, String> =
        mapOf(
            "starts_with" to "not_starts_with",
            "ends_with" to "not_ends_with",
            "contains" to "not_contains",
            "equals" to "not_equals",
        )

    /**
     * The `pred:` category keys to search — the producer's own closed vocabulary, not a copy —
     * plus the three negations this object composes itself ([negate]).
     *
     * Same reasoning as [GroundingTriggers.CATEGORIES]: `ttr-lexicon` is what the estate's lexicon
     * was validated against, and `RG-LEX-030` rejects anything outside its set, so a new predicate
     * becomes askable the moment the artifact defining it is on the classpath. In the compiled
     * lexicon a target ref IS the category key, so `pred:contains` is both. An estate whose archive
     * predates LP makes these unknown categories, and an unknown category contributes nothing
     * rather than falling back to the global index — asking costs nothing. That is also why the
     * negated kinds are named here as well: a stdlib that lists *nezačínající na* is askable even
     * before this service's `ttr-lexicon` pin moves to the version whose closed set names it.
     */
    val CATEGORIES: List<String> =
        (LexiconValidator.PREDICATE_KINDS + NEGATIONS.values)
            .toSortedSet()
            .map { LexiconValidator.PRED_PREFIX + it }

    /**
     * The widest `pred:` form, in words: *s názvem přesně*, *not starting with*. `ttr-lexicon`'s
     * RG-LEX-032 refuses a wider one (`LexiconValidator.MAX_PREDICATE_FORM_TOKENS`), so a window of
     * this width reaches every form an estate can author.
     *
     * ⛑ It was 2, with a KDoc claiming "the widest authored form is two words" beside a slice that
     * shipped the three-word *s názvem přesně* — a form that could then only ever match as a
     * fragment, which is how *s názvem* came to mean `equals` (review-103 F1).
     */
    const val MAX_FORM_TOKENS = 3

    /**
     * The words that negate the trigger right after them. Czech negates a verb or participle with
     * a PREFIX (*nezačínající*), and those forms are listed in the slice; what reaches this set is
     * the separate particle (*ne*, *nikoli*) and English's *not* / *never* / *no* / *n't*.
     */
    private val NEGATORS = setOf("not", "never", "no", "n't", "ne", "nikoli", "nikoliv")

    /**
     * One candidate stretch of text, and the tokens it covers.
     *
     * [tokens] is a list rather than a head index because a form may be several words and the dep
     * chain from the literal may reach any of them: *Pelex → na → začínající* enters the bigram at
     * its second word. Emitting a trigger for every covered token lets
     * [VerbatimAttribution.predicate]'s walk find the form wherever it lands inside it, with no
     * special case for which word of a phrase is "the" head.
     */
    data class Window(
        val tokens: List<Int>,
        val text: String,
    )

    /**
     * The windows worth asking about: up to [MAX_FORM_TOKENS] words, ending at or before a literal's
     * opening delimiter, starting within [SpanProposal.MAX_ANCHOR_DISTANCE] tokens of it.
     *
     * Every window is kept, even when two share a text: two literals in one question routinely
     * have the same words before them (*začínající na "Ax" a zákazníky začínající na "Bx"*), and
     * each window's tokens must receive the answer. The QUESTION is asked once per text
     * ([queries]); the ANSWER is applied to every window that has it ([collect]). Deduplicating
     * the windows themselves kept the first one's token positions only, and the second literal
     * lost its trigger (review-103 F13).
     */
    fun windowsOf(
        literals: Literals,
        parse: AnalyzeResponse,
    ): List<Window> {
        if (literals.isEmpty) return emptyList()
        val tokens = parse.tokensList
        if (tokens.isEmpty()) return emptyList()
        val out = LinkedHashMap<List<Int>, Window>()
        for (literal in literals.spans) {
            // The literal's own tokens INCLUDE its delimiters, and an opening quote is a token:
            // starting from the first of them is what makes the window land on real words.
            val first = (literal.tokens + literal.delimiterTokens).minOrNull() ?: continue
            for (width in 1..MAX_FORM_TOKENS) {
                for (start in (first - SpanProposal.MAX_ANCHOR_DISTANCE).coerceAtLeast(0) until first) {
                    val end = start + width
                    if (end > first) continue
                    val covered = (start until end).toList()
                    // A window that reaches into another literal is not a predicate form; it is
                    // part of a string somebody quoted.
                    if (covered.any { it in literals.tokens }) continue
                    val text = covered.mapNotNull { tokens.getOrNull(it)?.text }.joinToString(" ")
                    if (text.isBlank()) continue
                    out.putIfAbsent(covered, Window(covered, text))
                }
            }
        }
        return out.values.toList()
    }

    /** The distinct window texts, in first-seen order: one batch slot each. */
    private fun textsOf(windows: List<Window>): List<String> = windows.map { it.text }.distinct()

    /** One class-scoped query per distinct window text, in order — the trailing slots of the batch. */
    fun queries(
        windows: List<Window>,
        perSpanLimit: Int,
    ): List<SpanQuery> =
        textsOf(windows).map { text ->
            SpanQuery
                .newBuilder()
                .setQuery(text)
                .addAllCategories(CATEGORIES)
                .setLimit(perSpanLimit)
                .build()
        }

    /** How many batch slots [queries] appended — what the next block's offset has to skip. */
    fun slotCount(windows: List<Window>): Int = textsOf(windows).size

    /**
     * The triggers per covered token, read from the batch slots starting at [offset].
     *
     * **Anchored, deliberately.** [EvidenceClasses.of] takes an `anchored` flag that says whether
     * something in the question scoped this stretch of text. Here something did, and it is
     * stronger than the anchor a mention gets: the user typed quotation marks. A predicate form
     * three tokens before a quoted literal is exactly as anchored as text gets in this system.
     *
     * **Whole forms only** (review-103 F1). A row counts only when the window IS the form — see
     * [coversWholeForm]. Under v2 the score is computed over the QUERY's tokens, so the one-word
     * window `v` scored 1.0 against the form *v názvu*, no rival target meant the margin was the
     * score itself, and the fragment bound: *s názvem "Valmy"* became `= ?` and *named "Valmy"*
     * with it. The slice now authors every form EXACT (ruling 1); this rule holds for a slice or an
     * estate override that still says TOKENS.
     *
     * **Negation** (review-103 F12, D2). A negator right before the window turns the form into
     * its negation — *not starting with*, *do n't start with* — so a negated question never runs
     * as its own opposite.
     *
     * The strongest form per window speaks for it — *začíná* and *začínající* both firing is one
     * assertion made twice, the same argument [GroundingTriggers.collect] makes for a kernel. A
     * missing slot yields nothing rather than throwing: a matcher that answered short must degrade
     * to "no predicate", never take the resolve down.
     */
    fun collect(
        windows: List<Window>,
        response: BatchMatchResponse,
        offset: Int,
        thresholds: ResolverThresholds,
        parse: AnalyzeResponse = AnalyzeResponse.getDefaultInstance(),
    ): List<VerbatimAttribution.Trigger> {
        if (windows.isEmpty()) return emptyList()
        val slotByText = textsOf(windows).withIndex().associate { (i, text) -> text to offset + i }
        val byToken = LinkedHashMap<Int, VerbatimAttribution.Trigger>()
        for (window in windows) {
            val result = slotByText[window.text]?.let { response.resultsList.getOrNull(it) } ?: continue
            val best =
                result.matchesList
                    .asSequence()
                    .filter { it.targetClass == TargetClass.TARGET_CLASS_STRING_PREDICATE }
                    .filter { coversWholeForm(window, it) }
                    .filter {
                        EvidenceClasses.of(it, anchored = true, thresholds) != EvidenceClass.EVIDENCE_CLASS_WEAK
                    }.maxByOrNull { it.score }
                    ?: continue
            val authored = best.targetRef.ifBlank { best.category }
            val ref = if (negated(window, parse)) negate(authored) else authored
            // A wider window wins a token it shares with a narrower one — `začínající na` is a
            // better statement about token 4 than `na` alone. Windows arrive narrow-first, so
            // overwriting lets the last (widest) stand.
            for (token in window.tokens) byToken[token] = VerbatimAttribution.Trigger(token, ref)
        }
        return byToken.values.toList()
    }

    /**
     * True when [match] is the WHOLE form [window] asked about, not a form [window] is a piece of.
     *
     *  - An `EXACT` row needs no further proof: the matcher's method gate already compared the
     *    window's canonical text to the form's.
     *  - Any other row must have the window's width, and — where the engine reports per-token
     *    provenance (`fuzzy.match:v2`) — a hit on every one of the form's words. v1 reports none,
     *    and there the equal width is all the evidence there is.
     */
    internal fun coversWholeForm(
        window: Window,
        match: FuzzyMatch,
    ): Boolean {
        if (match.hasMatchMethod() && match.matchMethod.trim().equals(EXACT, ignoreCase = true)) return true
        val formWidth =
            match.candidate
                .trim()
                .split(WHITESPACE)
                .count { it.isNotBlank() }
        if (formWidth != window.tokens.size) return false
        val hits = match.provenance.tokenHitsList
        return hits.isEmpty() || hits.map { it.candidatePos }.toSet().size == formWidth
    }

    /** True when the token right before [window] negates it. */
    private fun negated(
        window: Window,
        parse: AnalyzeResponse,
    ): Boolean {
        val tokens = parse.tokensList
        val before = window.tokens.firstOrNull()?.minus(1) ?: return false
        val word = tokens.getOrNull(before)?.text?.lowercase() ?: return false
        if (word in NEGATORS) return true
        // `doesn't` on a tokenizer that splits the apostrophe off: `doesn` `'` `t`.
        return word == "t" &&
            tokens.getOrNull(before - 1)?.text in APOSTROPHES &&
            tokens
                .getOrNull(before - 2)
                ?.text
                ?.lowercase()
                ?.endsWith("n") == true
    }

    /** [ref] under a negator: each predicate turns into its opposite and back. */
    internal fun negate(ref: String): String {
        val kind = ref.removePrefix(LexiconValidator.PRED_PREFIX)
        val flipped =
            NEGATIONS[kind]
                ?: NEGATIONS.entries.firstOrNull { it.value == kind }?.key
                ?: return ref
        return LexiconValidator.PRED_PREFIX + flipped
    }

    private const val EXACT = "EXACT"
    private val WHITESPACE = Regex("\\s+")
    private val APOSTROPHES = setOf("'", "’")
}
