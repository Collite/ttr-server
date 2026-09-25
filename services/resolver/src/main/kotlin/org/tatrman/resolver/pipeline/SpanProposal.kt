// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.NerEntity
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.model.valueCategoriesByRef
import org.tatrman.text.Normalization.fold

/**
 * `proposeDomainSpans` — the Q-20 GO-WITH-FALLBACK core (spike §5). Naive
 * all-spans × fuzzy over-generates (P=0.5, 33 spurious binds); this proposes a
 * domain span ONLY where the dep parse ties a content subtree to a declared
 * entity-type anchor, gating it against that entity's vocabulary alone. That
 * recovered P=1.0 and killed over-generation (33→0) with ZERO LLM.
 *
 * Deterministic candidate sources:
 *   (a) **anchored subtrees** — for each declared anchor word found in the parse,
 *       the anchor's own nominal phrase (`pražských pobočkách` as ONE candidate)
 *       plus each nominal/proper-noun argument it governs (`středisko DF ADNAK`
 *       → the value `DF ADNAK`), gated against THAT entity only. Precision path.
 *   (b) **proper-noun arguments** — PROPN runs not already anchored and not
 *       universal-tagged, gated against ALL declared types. Admits data values
 *       like `Octavie` without re-admitting common-noun junk (the 33 spurious in
 *       config B were common nouns: `záznamy`, `roce`, `vývoj nákladů`).
 *   (c) **domain-eligible NER entities** — a NER span the classifier does NOT type
 *       as universal (CNEC objects/institutions: `op` products, `if` orgs) is a
 *       domain candidate gated against ALL declared types, EVEN when the POS tagger
 *       calls it a common NOUN (so (a)/(b) miss it). Live morphology tags a product
 *       name like `Octavie` NNFP4/NOUN while NameTag flags it `op`; fuzzy is the filter.
 *   (d) **n-gram floor (R4-γ)** — only when there is no dep parse (degraded
 *       language): content n-grams (1..[MAX_NGRAM]) over non-stopword,
 *       non-universal tokens, gated against ALL types.
 *
 * Universal-typed NER spans (person/geo/time/number) are removed before domain
 * gating (spike §1). Institutions/objects stay domain-eligible and are actively
 * proposed by (c) — a domain value like `DF ADNAK` is `io`-tagged, so NER is not the
 * domain filter; fuzzy is.
 *
 * RV-P2.1 adds one source and one exclusion, both needed by the lattice:
 *
 *   (e) **literal runs** — a run of code/number tokens (`501001`, `5010O1`, `10`), scoped
 *       to the categories of the nearest MENTION beside it and to nothing else. This is
 *       the deterministic half of RV-33's anchored lookup and the structural fix for
 *       issues.md §"Looking in wrong entity": `501001` is searched in the *account*
 *       because the question said *účtu*, not offered to every fuzzy column in the estate.
 *       A literal with no mention beside it is NOT proposed — an unscoped code search is
 *       exactly the over-generation Q-20 removed.
 *
 *   **An anchor word is nobody else's modifier and nobody else's value.** A declared
 *       anchor is a mention in its own right, so it is excluded from another anchor's
 *       phrase hull and from its governed arguments. Without this, Stanza's tagging of the
 *       Czech imperative (`Zobraz` comes back NOUN/`amod` under the measure — P0.2 report)
 *       silently swallows the operator word into `Zobraz náklady`, and `účtu` — governed by
 *       the root — is gated against the *measure's* categories instead of its own.
 */
object SpanProposal {
    private const val MAX_NGRAM = 3

    /** How far from a literal a mention may sit and still scope it (in tokens). */
    internal const val MAX_ANCHOR_DISTANCE = 3

    /** UPOS tags whose tokens are literals: codes, numbers, symbols. */
    private val LITERAL_UPOS = setOf("NUM", "SYM")

    /** Object kinds with no member vocabulary — nothing they govern is a value of theirs. */
    private val VALUELESS_OBJECT_KINDS = setOf("operator", "measure")

    /** Anchor-phrase pre-modifiers folded into the anchor noun's own candidate. */
    private val ANCHOR_PHRASE_RELATIONS = setOf("amod", "compound", "flat", "flat:name", "det", "nummod")

    /** Relations by which an anchor governs a separate value argument. */
    private val GOVERNED_VALUE_RELATIONS = setOf("nmod", "appos", "obj", "obl", "dep", "conj", "flat")

    /** Multi-word run relations that glue a proper-noun phrase together. */
    private val PROPN_RUN_RELATIONS = setOf("flat", "flat:name", "compound", "nmod", "appos")

    private val NOMINAL_UPOS = setOf("NOUN", "PROPN", "X")

    /**
     * The syntactic head of a token run: the one whose `dep_head` points **outside** it.
     *
     * ⛑ Not the first word, and this cost a live drill. `headToken` is what `FrameRoles` reads for
     * every deprel rule and what the mention takes its lemma from — and a declared phrase's first
     * word is usually a modifier or a preposition. With the run's first token as head,
     * `marketplace revenues` (the MEASURE) came back FILTER, because `marketplace` is a
     * `compound` and R5 fires on that; `by month` (the GROUPING) came back SUBJECT, because its
     * head was `by`. The spans and the bindings were right and the whole role layer was wrong.
     *
     * Falls back to the first token when every `dep_head` stays inside the run — a coordination
     * shape this can meet, and the first token is no worse an answer there than any other.
     */
    private fun syntacticHead(
        run: List<Int>,
        tokens: List<Token>,
    ): Int {
        val inRun = run.toHashSet()
        return run.firstOrNull { i ->
            val head = tokens[i].depHead - 1 // dep_head is 1-based; 0 means root
            head < 0 || head !in inRun
        } ?: run.first()
    }

    /** A declared anchor as the word sequence it is, folded once at index time. */
    private data class AnchorPhrase(
        val words: List<String>,
        val et: ResolverEntityType,
    )

    // A minimal Czech stopword set for the parse-less n-gram floor only. When a
    // dep parse is present these never matter (anchoring drives proposal); the
    // floor is a degraded-language safety net, not the precision path.
    private val STOPWORDS =
        setOf(
            "a",
            "i",
            "o",
            "u",
            "v",
            "k",
            "s",
            "z",
            "na",
            "za",
            "do",
            "od",
            "po",
            "ve",
            "se",
            "je",
            "to",
            "jsme",
            "jsou",
            "byl",
            "byla",
            "bylo",
            "kolik",
            "jak",
            "kde",
            "kdy",
            "co",
            "který",
            "která",
            "které",
            "poslední",
            "za",
            "the",
            "of",
        )

    fun proposeDomainSpans(
        parse: AnalyzeResponse,
        entityTypes: List<ResolverEntityType>,
        // LP contracts §2 — the quoted literals of this question. A literal is the user saying
        // "these characters, verbatim", which is the opposite of a span to look up: nothing
        // inside one is proposed, and a literal token is nobody's anchor-phrase modifier and
        // nobody's governed value — the "an anchor word is nobody else's" rule, applied to a
        // stronger claim. Empty for a question with no quotes, which is nearly all of them.
        literals: Literals = Literals.NONE,
    ): List<DomainSpanCandidate> {
        val tokens = parse.tokensList
        if (tokens.isEmpty()) return emptyList()

        val allCategories = entityTypes.flatMap { it.categories }.distinct()
        val allRefs = entityTypes.map { it.ref }
        // MV §5.3 — where a value governed by each object is looked up (its own categories, then
        // its member vocabularies'). Built once per proposal; read by the governed block below.
        val valueCategories = entityTypes.valueCategoriesByRef()
        val universal = universalCharRanges(parse.entitiesList)

        val hasParse = tokens.any { it.depHead > 0 }
        if (!hasParse) {
            return ngramFloor(tokens, universal, allRefs, allCategories, literals)
        }

        // children[headIndex1Based] = token list indices whose dep_head points here.
        val children = HashMap<Int, MutableList<Int>>()
        tokens.forEachIndexed { idx, t ->
            if (t.depHead > 0) children.getOrPut(t.depHead) { mutableListOf() }.add(idx)
        }

        // Fold declared anchors once, as WORD SEQUENCES keyed on their first word.
        //
        // ⛑ This used to be a single-token map, and that quietly made every multi-word declared
        // term unmatchable: `fold("by month")` is a key with a space in it, and no token folds to
        // that. An estate could author `by month` → a calendar column exactly as the schema
        // intends and nothing in the pipeline would ever see it — the term was not stale or
        // misspelled, it was **unreachable**.
        //
        // ⚠ Not fixed by widening `MentionLayer.PHRASE_RELATIONS` to cross a preposition. `by`
        // attaches with `case`, and admitting `case` would drag prepositions into every leftover
        // phrase on every question. The anchor index is the right place precisely because the
        // estate has NAMED this word sequence: matching it is honouring a declaration, not
        // guessing at syntax.
        val anchorPhrases = HashMap<String, MutableList<AnchorPhrase>>()
        for (et in entityTypes) {
            for (anchor in et.anchors) {
                val words = fold(anchor).split(' ').filter { it.isNotBlank() }
                if (words.isEmpty()) continue
                anchorPhrases.getOrPut(words.first()) { mutableListOf() }.add(AnchorPhrase(words, et))
            }
        }

        val out = mutableListOf<DomainSpanCandidate>()
        val coveredTokens = HashSet<Int>()

        val folded = tokens.map { fold(it.lemma.ifBlank { it.text }) }

        /** The longest declared phrase starting at [from], per owning entity type. */
        fun matchesAt(from: Int): List<AnchorPhrase> {
            val byFirst = anchorPhrases[folded.getOrNull(from) ?: return emptyList()] ?: return emptyList()
            val hits =
                byFirst.filter { phrase ->
                    phrase.words.withIndex().all { (i, w) -> folded.getOrNull(from + i) == w }
                }
            if (hits.isEmpty()) return emptyList()
            // Longest wins: an estate that declared both `by month` and `by month end` meant the
            // longer one where the question says it. Ties (same length, several owners) all stand
            // — that is a real ambiguity and the gate's to settle, not this layer's.
            val longest = hits.maxOf { it.words.size }
            return hits.filter { it.words.size == longest }
        }

        // Every token that IS part of a declared anchor. An anchor is a mention of its own
        // model object, so it may not be folded into a sibling anchor's phrase nor taken
        // as that anchor's governed value (RV-P2.1 — see the class doc).
        val anchorTokens =
            tokens.indices
                .flatMap { i -> matchesAt(i).flatMap { p -> (i until i + p.words.size).toList() } }
                .toHashSet()
        // LP: and so do the literal tokens, for a stronger reason. An anchor is excluded because
        // it is a mention of its own; a literal is excluded because it is not a mention at all —
        // it is a string the user wants passed through, and a hull that swallowed it would gate
        // `dodací místa "Pelex"` as one phrase against the store vocabulary.
        anchorTokens += literals.tokens

        // (a) anchored subtrees
        tokens.forEachIndexed { idx, t ->
            // LP: `zákazník "dodací místo"` asks for the STRING, not for the entity the estate
            // declared under that name. Quoting is the escape hatch, so it escapes the anchor
            // index too — otherwise the one construct that means "do not look this up" would be
            // the one construct guaranteed to.
            if (idx in literals.tokens) return@forEachIndexed
            val hits = matchesAt(idx)
            if (hits.isEmpty()) return@forEachIndexed
            // A MULTI-word anchor names its own extent: the estate said which words, so the span
            // is exactly those and no subtree expansion applies. Single-word anchors keep the
            // Q-20 behaviour below unchanged — phrase expansion plus governed values.
            val multiWord = hits.filter { it.words.size > 1 }
            if (multiWord.isNotEmpty()) {
                val span = (idx until idx + multiWord.first().words.size).toList()
                out +=
                    candidate(
                        span,
                        tokens,
                        multiWord.map { it.et.ref }.distinct(),
                        multiWord.flatMap { it.et.categories }.distinct(),
                        anchored = true,
                        origin = DomainSpanCandidate.Origin.ANCHOR_PHRASE,
                        headToken = syntacticHead(span, tokens),
                    )
                coveredTokens += span
                return@forEachIndexed
            }
            // MS-P3.S1 (contracts §8.2) — the anchor phrase is ONE candidate carrying every
            // owner that declared this word, exactly as the multi-word branch above builds it.
            //
            // ⛑ Emitting one candidate per owner did not produce two mentions: the phrase hull
            // does not depend on the owner, so both candidates had the identical span and
            // `dedupe` — which keys on (start, end) — kept the FIRST and dropped the rest. The
            // effect was a competitor silently deleted, and WHICH one survived was the order of
            // `entityTypes` in the registry. `tržby` declared for both an entity and its own
            // measure was gated against whichever the archive happened to list first, so the
            // Binder was never shown the choice it exists to make.
            val phraseIdx = anchorPhraseIndices(idx, children, tokens, universal, anchorTokens, literals.tokens)
            if (phraseIdx.isNotEmpty()) {
                out +=
                    candidate(
                        phraseIdx,
                        tokens,
                        hits.map { it.et.ref }.distinct(),
                        hits.flatMap { it.et.categories }.distinct(),
                        anchored = true,
                        origin = DomainSpanCandidate.Origin.ANCHOR_PHRASE,
                        headToken = idx,
                    )
                coveredTokens += phraseIdx
            }
            // Governed value arguments (e.g. `středisko` → `DF ADNAK`). Only for an anchor that
            // HAS values: an operator or a measure has no member vocabulary, so its nominal
            // arguments are not its values. Without this the operator word — which Stanza often
            // makes the root — governs the rest of the question, and every noun under it is
            // proposed as a value of `op:show` (h2's `stanic`). A blank kind admits values, which
            // is the pre-RV behaviour for a snapshot that carries no object kinds.
            val valueOwners = hits.map { it.et }.filter { it.objectKind !in VALUELESS_OBJECT_KINDS }
            if (valueOwners.isNotEmpty()) {
                // ⚑ A-MH-1a (MH-P3·S1·T2). Governed values used to be emitted PER OWNER — one
                // candidate per owner on the SAME span — on the argument that merging them would
                // offer `DF ADNAK` to every owner sharing the anchor. But `dedupe` keys on
                // `(start, end)`, so all but one were silently discarded and WHICH one survived
                // was decided by the order the registry happened to list the owners in. That is
                // not scoping, it is a coin toss with a stable-looking result.
                //
                // So the candidate is built ONCE, gated to the UNION of the value-bearing owners
                // — the MS-P3·S1 move applied to values. The gate can then find the one owner
                // whose vocabulary actually holds the value, which is a question about DATA that
                // `SpanProposal` has no business answering: it proposes spans, it does not decide
                // whose member a word is.
                //
                // ✅ MV (member-vocabulary contracts §5.3) — and the categories are where the
                // owners' VALUES live: each owner's own categories ∪ its member vocabularies
                // (`valueCategoriesByRef`). An entity's values are indexed under its attributes'
                // refs, never its own, so before MV this lookup could not find `TN` under `stores`
                // at all and every tier-M bind came through the open sibling and M3 (MH §7.5 ⚑).
                // The GATED refs stay the owners: they are the governor the gate reasons about.
                val refs = valueOwners.map { it.ref }.distinct()
                val categories = valueOwners.flatMap { valueCategories[it.ref] ?: it.categories }.distinct()
                for (childIdx in children[idx + 1].orEmpty()) {
                    val child = tokens[childIdx]
                    if (child.depRelation !in GOVERNED_VALUE_RELATIONS) continue
                    if (child.upos.uppercase() !in NOMINAL_UPOS) continue
                    if (childIdx in anchorTokens) continue
                    val valueIdx = subtreeIndices(childIdx, children, tokens, universal, anchorTokens, literals.tokens)
                    if (valueIdx.isEmpty()) continue
                    out +=
                        candidate(
                            valueIdx,
                            tokens,
                            refs,
                            categories,
                            anchored = true,
                            origin = DomainSpanCandidate.Origin.GOVERNED_VALUE,
                            headToken = childIdx,
                        )
                    // ⚑ A-MH-1b (MH-P3·S1·T3) — the OPEN sibling, same span, every declared type.
                    //
                    // The governed candidate above asks the anchor's owners and nobody else, which
                    // is right when they hold the value and silently wrong when they cannot: a
                    // fact governor (`sales in TN`) has no member vocabulary, so the lookup was
                    // always going to come back empty, and `coveredTokens` then stopped path (b)
                    // from ever proposing the word again. The question became a G3 gap for a
                    // reason that has nothing to do with the word.
                    //
                    // `SpanProposal` cannot know which lookup will succeed — that is a fact about
                    // the DATA — so it proposes both and lets the gate choose. Both ride the one
                    // batch (no second round trip), and `GateSpans.resolveOpenSiblings` drops this
                    // sibling whenever the governed reading BOUND, so a working governed lookup is
                    // byte-identical to what it was before.
                    out +=
                        candidate(
                            valueIdx,
                            tokens,
                            allRefs,
                            allCategories,
                            anchored = false,
                            origin = DomainSpanCandidate.Origin.OPEN_VALUE,
                            headToken = childIdx,
                        )
                    coveredTokens += valueIdx
                }
            }
        }

        // (b) proper-noun arguments not already anchored
        tokens.forEachIndexed { idx, t ->
            if (idx in coveredTokens || idx in literals.tokens) return@forEachIndexed
            if (t.upos.uppercase() != "PROPN") return@forEachIndexed
            if (isUniversal(t, universal)) return@forEachIndexed
            val runIdx = propnRun(idx, children, tokens, universal, coveredTokens, literals.tokens)
            if (runIdx.isEmpty()) return@forEachIndexed
            out +=
                candidate(
                    runIdx,
                    tokens,
                    allRefs,
                    allCategories,
                    anchored = false,
                    origin = DomainSpanCandidate.Origin.PROPER_NOUN,
                    headToken = idx,
                )
            coveredTokens += runIdx
        }

        // (c) domain-eligible NER entities. A NER span the classifier does NOT type as
        // universal — CNEC objects/institutions (`op` products, `if` orgs) — is a domain
        // candidate even when the POS tagger calls it a common NOUN, so the anchored/PROPN
        // paths above miss it (RG hero: live morphology tags "Octavie" NNFP4/NOUN, yet
        // NameTag flags it `op`). Gated against ALL declared types; fuzzy stays the filter.
        // Skipped where an already-emitted candidate covers the entity's span.
        for (e in parse.entitiesList) {
            if (UniversalClassifier.isUniversal(e.label, e.normalizedValue)) continue
            if (out.any { it.start <= e.charStart && it.end >= e.charEnd }) continue
            // LP: NER is the path that finds a domain value the POS tagger missed — and a quoted
            // name is exactly the shape NameTag flags. `Pelex` in quotes is a string, not an org.
            if (literals.overlaps(e.charStart, e.charEnd)) continue
            out +=
                DomainSpanCandidate(
                    e.text,
                    e.charStart,
                    e.charEnd,
                    allRefs,
                    allCategories.distinct(),
                    anchored = false,
                    origin = DomainSpanCandidate.Origin.NER_ENTITY,
                    headToken = tokens.indexOfFirst { it.charStart >= e.charStart && it.charEnd <= e.charEnd },
                )
        }

        // (e) literal runs, scoped by the mention beside them (RV-P2.1 / RV-33).
        val gated = dedupe(out)
        val proposed = dedupe(gated + literalRuns(tokens, universal, gated, coveredTokens + literals.tokens))
        // LP: the invariant, stated once at the end rather than trusted to six exclusions above.
        // Each of those keeps a literal out of the source it guards; this one is the promise the
        // lattice depends on — NOTHING proposed overlaps a literal, however it was proposed.
        return if (literals.isEmpty) proposed else proposed.filterNot { literals.overlaps(it.start, it.end) }
    }

    /**
     * Code/number runs (`501001`, `5010O1`, `10`), each gated against the categories of the
     * nearest **mention** — the anchored phrase closest in token distance, left preferred on a
     * tie, within [MAX_ANCHOR_DISTANCE]. A literal with no mention beside it is not proposed at
     * all: an unscoped code search is the over-generation Q-20 removed, and the lattice can say
     * "unattributed" (G3) without having guessed first.
     */
    private fun literalRuns(
        tokens: List<Token>,
        universal: List<IntRange>,
        gated: List<DomainSpanCandidate>,
        covered: Set<Int>,
    ): List<DomainSpanCandidate> {
        val mentions = gated.filter { it.origin == DomainSpanCandidate.Origin.ANCHOR_PHRASE && it.headToken >= 0 }
        if (mentions.isEmpty()) return emptyList()

        val out = mutableListOf<DomainSpanCandidate>()
        var i = 0
        while (i < tokens.size) {
            if (!isLiteral(tokens[i]) || i in covered || isUniversal(tokens[i], universal)) {
                i++
                continue
            }
            // a run of adjacent literal tokens is ONE literal: Stanza splits `5010O1` into
            // `5010O` + `1`, and searching either half finds nothing.
            var end = i
            while (end + 1 < tokens.size &&
                isLiteral(tokens[end + 1]) &&
                tokens[end + 1].charStart <= tokens[end].charEnd + 1 &&
                (end + 1) !in covered &&
                !isUniversal(tokens[end + 1], universal)
            ) {
                end++
            }
            val indices = (i..end).toList()
            val anchor =
                mentions
                    .filter { kotlin.math.abs(it.headToken - i) <= MAX_ANCHOR_DISTANCE }
                    .minWithOrNull(
                        compareBy({ kotlin.math.abs(it.headToken - i) }, { it.headToken > i }, { it.headToken }),
                    )
            if (anchor != null) {
                out +=
                    candidate(
                        indices,
                        tokens,
                        anchor.gatedEntityRefs,
                        anchor.categories,
                        anchored = true,
                        origin = DomainSpanCandidate.Origin.LITERAL,
                        headToken = i,
                        anchorHeadToken = anchor.headToken,
                    )
            }
            i = end + 1
        }
        return out
    }

    /** A code or a number: the POS tagger said so, or the surface carries a digit. */
    private fun isLiteral(token: Token): Boolean =
        token.upos.uppercase() in LITERAL_UPOS || token.text.any { it.isDigit() }

    /**
     * A digit-bearing surface. Narrower than [isLiteral] on purpose: a spelled-out numeral
     * (`deset poboček`) stays part of the phrase it quantifies, while `5010O` does not.
     */
    private fun isCode(token: Token): Boolean = token.text.any { it.isDigit() }

    // --- helpers ------------------------------------------------------------

    /**
     * A UD **interrogative** determiner — `PronType=Int` (Czech often reports `Int,Rel`).
     *
     * ⛑ **hartland, 2026-09-16.** `det` sits in [ANCHOR_PHRASE_RELATIONS] so that *"the account"*
     * and *"toho účtu"* stay ONE mention, and for an article or a demonstrative that is right: it
     * says WHICH one, and the estate's own word is still inside the phrase. An interrogative is
     * the opposite — it ASKS which one — and folding it in changes the text handed to the matcher.
     *
     * The estate's own advertised question *"Which portfolios does client `conseq:8801234` hold?"*
     * was therefore looked up as **"Which portfolios"** against a METADATA row whose term is
     * `portfolios` and whose method is **EXACT**: the mention bound nothing, `Gaps` raised
     * `G1_UNBOUND` on it (`bindingsCount == 0`), `chooseAsk` found it load-bearing, and the turn
     * ended as *"I don't recognise \"Which portfolios\""* — offering no option, because nothing
     * matched the phrase and so there was nothing to sign. The bare noun binds on the first try,
     * and `coveredTokens` had already suppressed that one-word span, so no rung downstream could
     * recover it. `client` in the same question bound only because its hull is the bare word.
     *
     * ⚑ Narrow on purpose — `PronType=Int` and nothing else. Excluding `det` wholesale would undo
     * the phrase behaviour the relation set exists for, and the four hero lattice goldens carry no
     * `det` relation at all, so this guard moves none of them.
     */
    private fun isInterrogativeDeterminer(token: Token): Boolean =
        token.depRelation == "det" &&
            token.featsMap["PronType"]
                ?.split(',')
                ?.any { it.trim().equals("Int", ignoreCase = true) } == true

    private fun anchorPhraseIndices(
        headIdx: Int,
        children: Map<Int, List<Int>>,
        tokens: List<Token>,
        universal: List<IntRange>,
        anchorTokens: Set<Int>,
        literalTokens: Set<Int> = emptySet(),
    ): List<Int> {
        if (isUniversal(tokens[headIdx], universal)) return emptyList()
        val included = sortedSetOf(headIdx)
        for (c in children[headIdx + 1].orEmpty()) {
            if (c in anchorTokens) continue // a sibling anchor is its own mention, not a modifier
            // A code is a VALUE of the thing, never part of its name: `nummod` is in the phrase
            // relations for numeral words, and without this `účtu 5010O` becomes one mention and
            // the code is never looked up at all.
            if (isCode(tokens[c])) continue
            // An interrogative determiner asks WHICH one; it is not part of the thing's name.
            if (isInterrogativeDeterminer(tokens[c])) continue
            if (tokens[c].depRelation in ANCHOR_PHRASE_RELATIONS && !isUniversal(tokens[c], universal)) {
                included += c
            }
        }
        // contiguous hull, dropping any universal token inside it
        return contiguousHull(included, tokens, universal, anchorTokens, headIdx, literalTokens)
    }

    private fun subtreeIndices(
        rootIdx: Int,
        children: Map<Int, List<Int>>,
        tokens: List<Token>,
        universal: List<IntRange>,
        anchorTokens: Set<Int>,
        literalTokens: Set<Int> = emptySet(),
    ): List<Int> {
        val acc = sortedSetOf<Int>()
        val stack = ArrayDeque<Int>()
        stack.addLast(rootIdx)
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            if (i in acc) continue
            if (isUniversal(tokens[i], universal)) continue
            if (i in anchorTokens && i != rootIdx) continue
            if (tokens[i].upos.uppercase() !in NOMINAL_UPOS && i != rootIdx) continue
            acc += i
            for (c in children[i + 1].orEmpty()) stack.addLast(c)
        }
        return contiguousHull(acc, tokens, universal, anchorTokens, rootIdx, literalTokens)
    }

    private fun propnRun(
        headIdx: Int,
        children: Map<Int, List<Int>>,
        tokens: List<Token>,
        universal: List<IntRange>,
        covered: Set<Int>,
        literalTokens: Set<Int> = emptySet(),
    ): List<Int> {
        val included = sortedSetOf(headIdx)
        for (c in children[headIdx + 1].orEmpty()) {
            if (c in covered) continue
            if (tokens[c].depRelation in PROPN_RUN_RELATIONS &&
                tokens[c].upos.uppercase() == "PROPN" &&
                !isUniversal(tokens[c], universal)
            ) {
                included += c
            }
        }
        return contiguousHull(included, tokens, universal, head = headIdx, literalTokens = literalTokens)
    }

    /**
     * Take the contiguous token-index hull (min..max) of [indices], but drop any
     * token in the gap that is universal-tagged (so a value phrase never swallows
     * an intervening date/person span). Non-universal gap tokens are kept so the
     * emitted phrase reads naturally.
     */
    private fun contiguousHull(
        indices: Set<Int>,
        tokens: List<Token>,
        universal: List<IntRange>,
        anchorTokens: Set<Int> = emptySet(),
        head: Int = -1,
        literalTokens: Set<Int> = emptySet(),
    ): List<Int> {
        // LP (review-103 F4b) — the hull is CUT at a literal, never stretched across one. A phrase
        // whose modifiers sit on both sides of a quoted string used to keep its head and tail and
        // span the literal, and the final "nothing overlaps a literal" filter then dropped the
        // WHOLE phrase: *prodejny zákazníka "Valmy" začínající na "Pe"* lost its store mention,
        // and the leftover pass re-proposed it with the literal inside. Only the head's own side
        // of every literal can belong to it.
        val kept =
            if (head < 0 || literalTokens.isEmpty()) {
                indices
            } else {
                val side = literalFreeSide(head, literalTokens)
                indices.filter { it in side }.toSet()
            }
        if (kept.isEmpty()) return emptyList()
        val lo = kept.min()
        val hi = kept.max()
        return (lo..hi).filter {
            !isUniversal(tokens[it], universal) &&
                (it in kept || (it !in anchorTokens && !isCode(tokens[it])))
        }
    }

    /**
     * The token range around [head] that no literal token interrupts — everything strictly
     * between the nearest literal token on each side. The whole question when there are none.
     */
    internal fun literalFreeSide(
        head: Int,
        literalTokens: Set<Int>,
    ): IntRange {
        if (literalTokens.isEmpty()) return Int.MIN_VALUE..Int.MAX_VALUE
        val lo = literalTokens.filter { it < head }.maxOrNull()?.plus(1) ?: Int.MIN_VALUE
        val hi = literalTokens.filter { it > head }.minOrNull()?.minus(1) ?: Int.MAX_VALUE
        return lo..hi
    }

    private fun candidate(
        indices: List<Int>,
        tokens: List<Token>,
        refs: List<String>,
        categories: List<String>,
        anchored: Boolean,
        origin: DomainSpanCandidate.Origin,
        headToken: Int,
        anchorHeadToken: Int = -1,
    ): DomainSpanCandidate {
        val sorted = indices.sorted()
        val start = sorted.minOf { tokens[it].charStart }
        val end = sorted.maxOf { tokens[it].charEnd }
        return DomainSpanCandidate(
            surface(sorted, tokens),
            start,
            end,
            refs,
            categories.distinct(),
            anchored,
            origin,
            headToken,
            tokens.getOrNull(headToken)?.let { it.lemma.ifBlank { it.text } }.orEmpty(),
            anchorHeadToken,
        )
    }

    /**
     * The surface of a token run, respecting character adjacency: tokens that touch in the
     * source are joined without a space. Stanza splits `5010O1` into two tokens, and the
     * query `5010O 1` matches nothing that `5010O1` would.
     */
    internal fun surface(
        indices: List<Int>,
        tokens: List<Token>,
    ): String {
        val sb = StringBuilder()
        var previousEnd = -1
        for (i in indices) {
            if (previousEnd in 0 until tokens[i].charStart) sb.append(' ')
            sb.append(tokens[i].text)
            previousEnd = tokens[i].charEnd
        }
        return sb.toString()
    }

    /**
     * Collapse candidates that resolve to the same char span (anchored wins).
     *
     * ⚑ A-MH-1b: the key carries [DomainSpanCandidate.anchored], so an anchored candidate and an
     * OPEN one for the same span are kept as two. They are two different QUESTIONS about one span
     * — "is this a value of the thing that governs it?" and "is it a value of anything?" — and
     * collapsing them threw the second away unasked. Anchored still wins within its own half, so
     * nothing that reached the gate before reaches it differently now: no path in this file emits
     * an anchored and an unanchored candidate for one span except the governed-value pair above.
     */
    private fun dedupe(cands: List<DomainSpanCandidate>): List<DomainSpanCandidate> {
        val bySpan = LinkedHashMap<Triple<Int, Int, Boolean>, DomainSpanCandidate>()
        for (c in cands) {
            val k = Triple(c.start, c.end, c.anchored)
            val existing = bySpan[k]
            if (existing == null || (!existing.anchored && c.anchored)) bySpan[k] = c
        }
        return bySpan.values.toList()
    }

    private fun ngramFloor(
        tokens: List<Token>,
        universal: List<IntRange>,
        allRefs: List<String>,
        allCategories: List<String>,
        literals: Literals,
    ): List<DomainSpanCandidate> {
        val content =
            tokens.indices.filter { i ->
                !isUniversal(tokens[i], universal) &&
                    // LP: the floor is the loosest source there is — every content n-gram, gated
                    // against every declared type. A literal has to be excluded HERE above all,
                    // because this is the path a degraded language takes and a quoted string is
                    // the one thing on it whose meaning does not depend on analysis.
                    i !in literals.tokens &&
                    fold(tokens[i].text) !in STOPWORDS &&
                    tokens[i].text.any { it.isLetter() }
            }
        val out = mutableListOf<DomainSpanCandidate>()
        // windows over the ORIGINAL token order, size 1..MAX_NGRAM, contiguous runs only.
        for (start in content.indices) {
            for (n in 1..MAX_NGRAM) {
                val windowPos = start until minOf(start + n, content.size)
                val idx = windowPos.map { content[it] }
                // require token-order contiguity so windows read as real phrases
                if (idx.zipWithNext().any { (a, b) -> b != a + 1 }) continue
                out +=
                    candidate(
                        idx,
                        tokens,
                        allRefs,
                        allCategories,
                        anchored = false,
                        origin = DomainSpanCandidate.Origin.NGRAM_FLOOR,
                        headToken = idx.first(),
                    )
            }
        }
        return dedupe(out)
    }

    // Universal NER spans (removed before domain gating) come from the shared
    // UniversalClassifier — the same classification UniversalExtraction types by,
    // so the exclusion set and the universal bindings agree by construction.
    private fun universalCharRanges(entities: List<NerEntity>): List<IntRange> =
        entities.filter { UniversalClassifier.isUniversal(it.label, it.normalizedValue) }.map {
            it.charStart until
                it.charEnd
        }

    private fun isUniversal(
        token: Token,
        universal: List<IntRange>,
    ): Boolean {
        val mid = (token.charStart + token.charEnd) / 2
        return universal.any { mid in it }
    }
}
