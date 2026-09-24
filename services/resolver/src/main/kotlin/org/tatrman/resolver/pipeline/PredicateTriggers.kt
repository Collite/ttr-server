// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.fuzzy.v1.BatchMatchResponse
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
 * both put the predicate before the string it applies to — *začínající na "Shell"*, *starts with
 * "Shell"*. A form to the RIGHT of a literal is qualifying something else.
 *
 * **One BatchMatch still** (B-T1): these are extra slots on the pass the core already makes, after
 * the gate's and the grounding triggers', never a second round trip.
 */
object PredicateTriggers {
    /**
     * The `pred:` category keys to search — the producer's own closed vocabulary, not a copy.
     *
     * Same reasoning as [GroundingTriggers.CATEGORIES]: `ttr-lexicon` is what the estate's lexicon
     * was validated against, and `RG-LEX-030` rejects anything outside this set, so a sixth
     * predicate becomes askable the moment the artifact defining it is on the classpath. In the
     * compiled lexicon a target ref IS the category key, so `pred:contains` is both. An estate
     * whose archive predates LP makes these unknown categories, and an unknown category
     * contributes nothing rather than falling back to the global index — asking costs nothing.
     */
    val CATEGORIES: List<String> =
        LexiconValidator.PREDICATE_KINDS
            .sorted()
            .map { LexiconValidator.PRED_PREFIX + it }

    /** The widest authored form in the stdlib slice is two words (*začínající na*, *starts with*). */
    private const val MAX_FORM_TOKENS = 2

    /**
     * One candidate stretch of text, and the tokens it covers.
     *
     * [tokens] is a list rather than a head index because a form may be two words and the dep
     * chain from the literal may reach either of them: *Pelex → na → začínající* enters the bigram
     * at its second word. Emitting a trigger for every covered token lets
     * [VerbatimAttribution.predicate]'s walk find the form wherever it lands inside it, with no
     * special case for which word of a phrase is "the" head.
     */
    data class Window(
        val tokens: List<Int>,
        val text: String,
    )

    /**
     * The windows worth asking about: up to [MAX_FORM_TOKENS] words, ending immediately before a
     * literal, within [SpanProposal.MAX_ANCHOR_DISTANCE] tokens of it.
     *
     * Deduplicated by text, because two literals in one question routinely share the word before
     * them, and the answer would be the same twice.
     */
    fun windowsOf(
        literals: Literals,
        parse: AnalyzeResponse,
    ): List<Window> {
        if (literals.isEmpty) return emptyList()
        val tokens = parse.tokensList
        if (tokens.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, Window>()
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
                    out.putIfAbsent(text, Window(covered, text))
                }
            }
        }
        return out.values.toList()
    }

    /** One class-scoped query per window, in the same order — the trailing slots of the batch. */
    fun queries(
        windows: List<Window>,
        perSpanLimit: Int,
    ): List<SpanQuery> =
        windows.map { window ->
            SpanQuery
                .newBuilder()
                .setQuery(window.text)
                .addAllCategories(CATEGORIES)
                .setLimit(perSpanLimit)
                .build()
        }

    /**
     * The triggers per covered token, read from the batch slots starting at [offset].
     *
     * **Anchored, deliberately.** [EvidenceClasses.of] takes an `anchored` flag that says whether
     * something in the question scoped this stretch of text. Here something did, and it is
     * stronger than the anchor a mention gets: the user typed quotation marks. A predicate form
     * three tokens before a quoted literal is exactly as anchored as text gets in this system.
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
    ): List<VerbatimAttribution.Trigger> {
        if (windows.isEmpty()) return emptyList()
        val byToken = LinkedHashMap<Int, VerbatimAttribution.Trigger>()
        windows.forEachIndexed { i, window ->
            val result = response.resultsList.getOrNull(offset + i) ?: return@forEachIndexed
            val best =
                result.matchesList
                    .asSequence()
                    .filter { it.targetClass == TargetClass.TARGET_CLASS_STRING_PREDICATE }
                    .filter {
                        EvidenceClasses.of(it, anchored = true, thresholds) != EvidenceClass.EVIDENCE_CLASS_WEAK
                    }.maxByOrNull { it.score }
                    ?: return@forEachIndexed
            val ref = best.targetRef.ifBlank { best.category }
            // A wider window wins a token it shares with a narrower one — `začínající na` is a
            // better statement about token 4 than `na` alone. `putIfAbsent` on a list ordered
            // narrow-first would keep the worse one, so overwrite and let the last (widest) stand.
            for (token in window.tokens) byToken[token] = VerbatimAttribution.Trigger(token, ref)
        }
        return byToken.values.toList()
    }
}
