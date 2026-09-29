// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.LookupRequest
import org.tatrman.fuzzy.v1.TargetClass
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.model.ResolverThresholds

/**
 * ttr-server#58 — the operator words no nominal path reaches, found by asking the matcher.
 *
 * An operator word reached the lattice two ways, and both were accidents. [SpanProposal]'s anchored
 * path proposes it when the registry declares it as an anchor, but the archive channel — every real
 * estate — declares no operator anchors (`LexiconArchiveRegistrySource` leaves `OPERATOR` out on
 * purpose). [MentionLayer] proposes it when the tagger calls it a noun, which it does for the Czech
 * imperative (`Zobraz` comes back NOUN) and for nothing else. A correctly tagged operator word — the
 * English imperative `Show` (VERB), the Czech count adjective `nejlepších` (ADJ) — became nothing:
 * no mention, no binding, and a count beside it scoped by the noun it counts instead of by the
 * operator whose argument it is (`nejlepších 10 prodejen` → a G4 on `10`, looked up as a store).
 *
 * So the resolver asks. Before span proposal, each word in an operator **slot** is looked up in the
 * matcher's operator class and nothing else, and a word the matcher confirms becomes an anchor of
 * that operator for this request — the same anchor a registry override declares, proposed and gated
 * by the same code, so the archive channel ends where the h2 golden already was.
 *
 * **The slots are syntactic and narrow; the matcher is the vocabulary.** Nothing here knows an
 * operator word. The two shapes are the two places an operator word sits that no nominal path
 * covers:
 *  - [Slot.COMMAND] — an imperative verb (`Mood=Imp`): `Show`, `Compare`, `Ukaž`. Not every root
 *    verb: in *"Which stores show growth?"* `show` is the root and not a command, and a surface
 *    match would bind it to `op:show`.
 *  - [Slot.COUNT] — an adjective beside a number that counts a noun: `nejlepších 10 prodejen`,
 *    `10 nejlepších prodejen`, `the 10 largest stores`. The adjective must hang from the number or
 *    from the noun the number counts, so a predicate (*Tržby byly nejvyšší 3 roky po sobě*, where
 *    the adjective is the root) is not a slot.
 *
 * ⚠ **A miss leaves no trace.** ttr-server#58 asked for a gap when the matcher knows no operator
 * for the word. That is right for a noun and wrong for these words: every English question with a
 * command in it would ask the user what the verb means, and `dalších 10 prodejen` would ask about
 * `dalších`. A slot word the matcher does not confirm is simply not a mention, which is exactly what
 * it was before this class existed. Q-20's cut is safe for the same reason: the lookup is scoped to
 * the operator class, so a slot word can only ever bind an operator.
 */
object OperatorWords {
    private val log = LoggerFactory.getLogger(OperatorWords::class.java)

    /** Where the word sits — which also decides whether it may scope a literal ([SpanProposal]). */
    enum class Slot { COMMAND, COUNT }

    /** A slot word the matcher confirmed, with the operators it confirmed it as. */
    data class Word(
        val token: Int,
        val slot: Slot,
        val operators: List<ResolverEntityType>,
    )

    /** At most this many words are asked about per question — a bound, not an expected count. */
    internal const val MAX_WORDS = 4

    /** What the operator-class answer's kind is, as `objectKind` spells it. */
    internal const val KIND_OPERATOR = "operator"

    /** The nouns a count can count. */
    private val COUNTED_UPOS = setOf("NOUN", "PROPN")

    /**
     * The slot words of [parse], in token order, capped at [MAX_WORDS]. Empty without a dep parse:
     * [Slot.COUNT] is a tree shape, and the parse-less floor has no tree to read.
     *
     * A word inside a quoted literal or inside a universal NER span is never a slot. The first is a
     * string the user wants passed through; the second already has a reading (`posledních 12
     * měsíců`, typed a date, is chrono's).
     */
    fun slots(
        parse: AnalyzeResponse,
        literals: Literals = Literals.NONE,
    ): List<Pair<Int, Slot>> {
        val tokens = parse.tokensList
        if (tokens.none { it.depHead > 0 }) return emptyList()
        val universal =
            parse.entitiesList
                .filter { UniversalClassifier.isUniversal(it.label, it.normalizedValue, it.text) }
                .map { it.charStart until it.charEnd }
        return tokens.indices
            .mapNotNull { i ->
                val token = tokens[i]
                val mid = (token.charStart + token.charEnd) / 2
                when {
                    i in literals.tokens || universal.any { mid in it } -> null
                    isCommand(token) -> i to Slot.COMMAND
                    isCount(i, tokens) -> i to Slot.COUNT
                    else -> null
                }
            }.take(MAX_WORDS)
    }

    /**
     * Ask the matcher about every slot word, concurrently, in the operator class only.
     *
     * `Lookup` and not extra slots on the broad pass's one `BatchMatch`, for two reasons: a
     * `SpanQuery` cannot scope by class, and the answer is needed BEFORE proposal, which builds
     * that batch. The same ruling `LookupRounds` made; the calls run concurrently, so this costs one
     * round trip, and only for a question that has a slot word.
     *
     * Bounded by the lookup rung's own budget ([LookupRoundConfig.budgetMs]), and off with it: an
     * estate that switched the rung off has said it has no lexicon to narrow against, and this is a
     * `Lookup` like any other. A failed or slow matcher leaves the question exactly as it was
     * without this step — the words are simply not mentions.
     */
    suspend fun find(
        fuzzy: FuzzyClient,
        parse: AnalyzeResponse,
        literals: Literals,
        thresholds: ResolverThresholds,
        config: LookupRoundConfig,
    ): List<Word> {
        if (config.budgetMs <= 0) return emptyList()
        val slots = slots(parse, literals)
        if (slots.isEmpty()) return emptyList()
        val tokens = parse.tokensList
        val answers =
            withTimeoutOrNull(config.budgetMs) {
                coroutineScope {
                    slots.map { (i, _) -> async { ask(fuzzy, tokens[i].text, config.maxCandidates) } }.awaitAll()
                }
            }
        if (answers == null) {
            log.warn(
                "operator lookup exhausted its {}ms budget — {} word(s) left unasked",
                config.budgetMs,
                slots.size,
            )
            return emptyList()
        }
        return slots.zip(answers).mapNotNull { (slot, matches) ->
            // The class is checked HERE as well as asked for: a matcher that ignored the filter must
            // not be able to turn a verb into a model object or a data value.
            val operators =
                matches
                    .filter { it.targetClass == TargetClass.TARGET_CLASS_OPERATOR && it.score >= thresholds.bind }
                    .map(::operatorType)
                    .distinctBy { it.ref }
            if (operators.isEmpty()) null else Word(slot.first, slot.second, operators)
        }
    }

    /**
     * The operators of [words] the registry does not already declare, as entity types. They join the
     * registry for this one request, so every reader downstream sees an `operator` kind for them —
     * the reading a registry override that declares the same operator gets.
     */
    fun entityTypes(
        words: List<Word>,
        declared: List<ResolverEntityType>,
    ): List<ResolverEntityType> {
        val known = declared.map { it.ref }.toHashSet()
        return words.flatMap { it.operators }.distinctBy { it.ref }.filter { it.ref !in known }
    }

    private fun isCommand(token: Token): Boolean =
        token.upos.equals("VERB", ignoreCase = true) &&
            token.featsMap["Mood"]?.split(',')?.any { it.trim().equals("Imp", ignoreCase = true) } == true

    /**
     * An adjective next to a number that counts a noun after both — the count read the way
     * `Gaps.namesSomething` reads it: a number BEFORE its noun counts it, one after it names it.
     *
     * The three words must form one phrase, and parsers build it three ways, all seen live: both
     * hang from the noun (`Zobraz nejlepších 10 prodejen`), the adjective hangs from the number
     * (`nejlepších 10 prodejen`, cs), or the number hangs from the adjective (`the 5 largest
     * stores`, en).
     */
    private fun isCount(
        i: Int,
        tokens: List<Token>,
    ): Boolean {
        val adjective = tokens[i]
        if (!adjective.upos.equals("ADJ", ignoreCase = true)) return false
        val adjectiveHead = adjective.depHead - 1 // dep_head is 1-based; 0 means root
        return listOf(i - 1, i + 1).any { n ->
            val number = tokens.getOrNull(n) ?: return@any false
            if (!number.upos.equals("NUM", ignoreCase = true) && number.text.none { it.isDigit() }) return@any false
            val numberHead = number.depHead - 1
            val noun = if (numberHead == i) adjectiveHead else numberHead
            val counted = tokens.getOrNull(noun) ?: return@any false
            counted.upos.uppercase() in COUNTED_UPOS &&
                noun > maxOf(i, n) &&
                (adjectiveHead == n || adjectiveHead == noun)
        }
    }

    private suspend fun ask(
        fuzzy: FuzzyClient,
        term: String,
        maxCandidates: Int,
    ): List<FuzzyMatch> =
        try {
            fuzzy
                .lookup(
                    LookupRequest
                        .newBuilder()
                        .setTerm(term)
                        .addTargetClasses(TargetClass.TARGET_CLASS_OPERATOR)
                        .setMaxCandidates(maxCandidates)
                        .build(),
                ).candidatesList
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("operator lookup failed — treated as NO ANSWER, the word stays unproposed", e)
            emptyList()
        }

    /** In the compiled lexicon an operator's target ref IS its category key (`op:top-n`). */
    private fun operatorType(match: FuzzyMatch): ResolverEntityType {
        val ref = match.targetRef.ifBlank { match.category }
        return ResolverEntityType(
            ref = ref,
            categories = listOf(match.category.ifBlank { ref }),
            anchors = emptyList(),
            objectKind = KIND_OPERATOR,
        )
    }
}
