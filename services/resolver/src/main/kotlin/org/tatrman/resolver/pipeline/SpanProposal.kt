// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.NerEntity
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.model.KIND_CONCEPT
import org.tatrman.resolver.model.landingTypeByRef
import org.tatrman.resolver.model.standsFor
import org.tatrman.resolver.model.valueCategoriesByRef
import org.tatrman.resolver.v1.UniversalEntityType
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
 *       plus each nominal/proper-noun argument it governs (`středisko QT ORLAK`
 *       → the value `QT ORLAK`), gated against THAT entity only. Precision path.
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
 * proposed by (c) — a domain value like `QT ORLAK` is `io`-tagged, so NER is not the
 * domain filter; fuzzy is.
 *
 * **One exception, the governed argument (UD, ttr-server#118).** A place or a person name that a
 * value-bearing anchor governs (`Stores in TN`, `TN` typed a place) is ALSO proposed as that
 * anchor's governed pair, carrying the entity it reads ([DomainSpanCandidate.dualReadingOf]);
 * `UniversalSeam` then lets the member reading take the span only when it found a member. A
 * measure governs a place for its fact, and a multi-word anchor governs one too (✅UD-8:
 * `Marketplace revenue for Memphis DC`). Every other path — (b), (c), (e), the floor, the phrase
 * hull — still refuses universal characters, so a bare, an unanchored or a pre-modifier place
 * stays a place (UD contracts §3). A place COORDINATED with a governed one (`Tržby pro Brno a Liberec`,
 * `Liberec` the `conj` of `Brno`) is governed by the same anchor (ttr-demo#62), and proposed the same way.
 *
 * **…and a declared word the NER typed a date by itself (ttr-server#152).** Stanza types the grain
 * adjective of *Monthly revenue* a DATE, and the declared `monthly` never bound. A one-word anchor
 * whose word is the whole of a DATE is proposed as if the NER had not typed it; `UniversalSeam`
 * keeps one reading of the word after the gate. A multi-word anchor (`by month`) always names its
 * own extent, so it never needed this.
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
 *
 * **Operator words (ttr-server#58)** are anchors too, when the matcher has confirmed them for this
 * question ([OperatorWords]): a word with no declared anchor of its own that sits in an operator slot
 * is an anchor of the operator the matcher named, and path (a) proposes it exactly as it proposes a
 * declared one. A declared anchor on the same word wins. A COUNT word scopes the literal beside it —
 * `10` in `nejlepších 10 prodejen` is `op:top-n`'s argument — and a COMMAND word scopes none:
 * `Show 501001` is a code nobody named the axis of, not a code the verb owns.
 */
object SpanProposal {
    private const val MAX_NGRAM = 3

    /** How far from a literal a mention may sit and still scope it (in tokens). */
    internal const val MAX_ANCHOR_DISTANCE = 3

    /** UPOS tags whose tokens are literals: codes, numbers, symbols. */
    private val LITERAL_UPOS = setOf("NUM", "SYM")

    private const val MEASURE_KIND = "measure"

    private const val OPERATOR_KIND = "operator"

    /** Object kinds with no member vocabulary — nothing they govern is a value of theirs. */
    private val VALUELESS_OBJECT_KINDS = setOf(OPERATOR_KIND, MEASURE_KIND)

    /** Anchor-phrase pre-modifiers folded into the anchor noun's own candidate. */
    private val ANCHOR_PHRASE_RELATIONS = setOf("amod", "compound", "flat", "flat:name", "det", "nummod")

    /** Relations by which an anchor governs a separate value argument. */
    private val GOVERNED_VALUE_RELATIONS = setOf("nmod", "appos", "obj", "obl", "dep", "conj", "flat")

    /** Multi-word run relations that glue a proper-noun phrase together. */
    private val PROPN_RUN_RELATIONS = setOf("flat", "flat:name", "compound", "nmod", "appos")

    /** ✅UD-9 — relations by which a word continues a place's name past its NER extent (`Praha DC`). */
    private val NAME_TAIL_RELATIONS = PROPN_RUN_RELATIONS + setOf("flat:foreign")

    /** …and the relation by which a name's last word hangs on the word that continues it (`Memphis` ← `DC`). */
    private val HEAD_FINAL_NAME_RELATIONS = setOf("compound")

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
    internal fun syntacticHead(
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
        // ttr-server#58 — the slot words the matcher confirmed as operators ([OperatorWords.find]).
        // Empty unless the lookup ran, which keeps every caller that does not ask byte-identical.
        operatorWords: List<OperatorWords.Word> = emptyList(),
    ): List<DomainSpanCandidate> {
        val tokens = parse.tokensList
        if (tokens.isEmpty()) return emptyList()

        val allCategories = entityTypes.flatMap { it.categories }.distinct()
        val allRefs = entityTypes.map { it.ref }
        // MV §5.3 — where a value governed by each object is looked up (its own categories, then
        // its member vocabularies'). Built once per proposal; read by the governed block below.
        val valueCategories = entityTypes.valueCategoriesByRef()
        // CN-P4 (A-CN-6, ⚑R130-a / A-CN-72) — a concept stands for its ER landing as an object. Every candidate
        // leaves here gated to landings (see the return), and whether an anchor owns values is its landing's
        // kind: a quality landing on a measure governs no value, as the measure does not. The identity on an
        // estate with no `model cn`.
        val standsFor = entityTypes.standsFor()
        val landings = entityTypes.landingTypeByRef()

        /** An anchor as an object: a concept stands for its landing's type; any other anchor for itself. */
        fun objectOf(et: ResolverEntityType): ResolverEntityType? =
            if (et.objectKind == KIND_CONCEPT) landings[et.ref] else et

        fun kindOf(et: ResolverEntityType): String = objectOf(et)?.objectKind.orEmpty()

        /** A measure anchor's fact: the declared owner of what it stands for. */
        fun factOf(et: ResolverEntityType): String = objectOf(et)?.ownerRef.orEmpty()
        val universal = universalCharRanges(parse.entitiesList)
        // ttr-server#152 — the one-word dates: token → the char range of the DATE the NER typed
        // over exactly that word and nothing else (`Monthly`). A date wider than its word (`last
        // year`) is not here, so a declared `year` never takes a word out of `last year`.
        val dateWords: Map<Int, IntRange> =
            parse.entitiesList
                .filter {
                    UniversalClassifier.classify(it.label, it.normalizedValue, it.text) ==
                        UniversalEntityType.DATE
                }.mapNotNull { e ->
                    tokensWithin(e, tokens).singleOrNull()?.let { it to (e.charStart until e.charEnd) }
                }.toMap()

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

        // ✅ A declared word matches the token's LEMMA or its SURFACE (cs LR S5). The index used to
        // read lemmas only, and that made every inflected declared phrase unreachable on a
        // lemmatising parser: the estate declares `po měsících` (by month), MorphoDiTa reads
        // `po měsíc`, and the phrase never formed. The bare `měsících` then bound the stdlib time
        // trigger, and „Tržby z tržiště v roce 2025 po měsících“ came back as one total with no gap.
        // (`tržby z tržiště` reached the lattice only because the estate ALSO declared the lemma
        // form `tržba z tržiště`.) A word the estate wrote as the user writes it is a declaration
        // too — the third way a correctly-declared term could not reach the lattice.
        val surfaces = tokens.map { fold(it.text) }

        fun wordAt(
            i: Int,
            word: String,
        ): Boolean = folded.getOrNull(i) == word || surfaces.getOrNull(i) == word

        // ttr-server#58 — a confirmed operator word, as the one-word anchor phrase a registry that
        // declared it would have produced. Consulted only where no declared anchor matches.
        val spoken =
            operatorWords
                .filter { it.token in tokens.indices }
                .associate { w -> w.token to w.operators.map { AnchorPhrase(listOf(folded[w.token]), it) } }

        /** The longest declared phrase starting at [from], per owning entity type. */
        fun matchesAt(from: Int): List<AnchorPhrase> {
            val lemma = folded.getOrNull(from) ?: return emptyList()
            val byFirst =
                (anchorPhrases[lemma].orEmpty() + anchorPhrases[surfaces[from]].orEmpty())
                    .distinct()
                    .ifEmpty { return spoken[from].orEmpty() }
            val hits =
                byFirst.filter { phrase ->
                    phrase.words.withIndex().all { (i, w) -> wordAt(from + i, w) }
                }
            if (hits.isEmpty()) return spoken[from].orEmpty()
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
        // …and the words a literal HANGS from are its restriction, not a sibling's modifier: see
        // [literalGovernors]. Empty on the floor, where there is no chain to walk.
        val governors = literalGovernors(tokens, literals.tokens)

        // The tokens of every multi-word anchor proposed so far — the words an estate has named as
        // one phrase. Tokens are walked in order, so a phrase is always recorded before any word
        // inside it is reached.
        val declaredPhraseTokens = HashSet<Int>()

        /**
         * The value arguments the anchor [hits] matched governs, from its head token [head].
         *
         * Governed value arguments (e.g. `středisko` → `QT ORLAK`) are proposed only for an anchor
         * that HAS values: an operator or a measure has no member vocabulary, so its nominal
         * arguments are not its values. Without this the operator word — which Stanza often makes
         * the root — governs the rest of the question, and every noun under it is proposed as a
         * value of `op:show` (h2's `stanic`). A blank kind admits values, which is the pre-RV
         * behaviour for a snapshot that carries no object kinds.
         *
         * ✅UD-8 — **a PLACE has two more governors, and only a place does.** The dual reading
         * below is the one way into the domain for a value the NER typed universal: paths (b), (c),
         * (e) and the floor all refuse its characters. So it is offered wherever an anchor governs
         * the place, not only where an ordinary governed value would be:
         *
         *  - **under a measure**, scoped to the measure's FACT (its declared owner):
         *    `Marketplace revenue for Memphis DC` asks for the fact's rows at that warehouse, so the
         *    place is the fact's member before it is a place — the governor `UniversalSeam.inScope`
         *    reaches through the fact's declared relations, exactly as it does for `Sales in TN`.
         *    A measure still governs no ordinary value: `revenue for books` proposes nothing new.
         *  - **under a multi-word anchor** ([placesOnly]), whose own extent is fixed: the estate
         *    named the phrase, and the hartland measure phrases (`marketplace revenue`, `tržby z
         *    tržiště`) are where a DC name is asked. Its ordinary governed values are still not
         *    proposed — `v roce` under `tržby z tržiště` would become a value of the fact.
         *
         * Both were a G3 on hartland: the place stood, and the open lookup round could not attach a
         * member to a span the domain never proposed, so the question lost its warehouse filter.
         */
        fun governedValues(
            head: Int,
            hits: List<AnchorPhrase>,
            placesOnly: Boolean,
        ) {
            val valueOwners = hits.map { it.et }.filter { kindOf(it) !in VALUELESS_OBJECT_KINDS }
            // ⚑ A-MH-1a (MH-P3·S1·T2). Governed values used to be emitted PER OWNER — one
            // candidate per owner on the SAME span — on the argument that merging them would
            // offer `QT ORLAK` to every owner sharing the anchor. But `dedupe` keys on
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
            val refs = if (placesOnly) emptyList() else valueOwners.map { it.ref }.distinct()
            val categories = valueOwners.flatMap { valueCategories[it.ref] ?: it.categories }.distinct()
            // ✅UD-8 — a place's governors: the value-bearing owners, then each measure's fact.
            // With no measure among the hits this is exactly [valueOwners], so a dual reading
            // under an entity is gated as it always was.
            val placeOwners =
                (
                    valueOwners.map { it.ref } +
                        hits.map { it.et }.filter { kindOf(it) == MEASURE_KIND }.map(::factOf)
                ).filter { it.isNotBlank() }.distinct()
            if (refs.isEmpty() && placeOwners.isEmpty()) return
            // A fact the registry does not declare lends its ref, which no vocabulary is keyed by:
            // the governed half then finds nothing and the open half carries the reading.
            val placeCategories = placeOwners.flatMap { valueCategories[it] ?: listOf(it) }.distinct()

            /**
             * ttr-demo#62 (coordination, 2026-10-14) — the places coordinated with the value argument
             * [first]: its `conj` dependents, transitively (UD hangs every conjunct on the first), that
             * the NER typed a place or a person. In „Tržby pro Brno a Liberec“ the parse hangs `Liberec`
             * on `Brno`, not on `Tržby`, so before this it was nobody's governed value: it stood as a
             * bare place with no scope of its own (G3), while `Brno` was read under `Tržby`. A
             * conjunct is governed by what governs the coordination, so it is proposed exactly as a
             * place argument of this anchor is — the governed pair, gated to [placeOwners]. A conjunct
             * the NER did not type keeps the path it always had (it lies inside its first conjunct's
             * subtree, or is a proper noun of its own), and an anchor word is never a conjunct value.
             *
             * Only a value this anchor TAKES lends its governor to its conjuncts (see the loop below):
             * in „Tržby za elektroniku a Brno“ a measure takes no `elektroniku` (it governs no ordinary
             * value), so `Brno`, its conjunct, is no value of `Tržby` either and stays a bare place.
             */
            fun placeConjuncts(first: Int): List<Int> {
                if (placeOwners.isEmpty()) return emptyList()
                val out = mutableListOf<Int>()
                val stack = ArrayDeque(listOf(first))
                while (stack.isNotEmpty()) {
                    for (c in children[stack.removeLast() + 1].orEmpty()) {
                        if (tokens[c].depRelation != "conj" || c in out) continue
                        if (tokens[c].upos.uppercase() !in NOMINAL_UPOS || c in anchorTokens) continue
                        out += c
                        stack.addLast(c)
                    }
                }
                return out.filter { dualReadingEntity(it, tokens, parse.entitiesList) != null }
            }

            /** Propose [childIdx] as this anchor's governed value — the pair, or nothing. True iff proposed. */
            fun proposeValue(childIdx: Int): Boolean {
                // ✅ UD (ttr-server#118, option 1) — the DUAL READING. A place or a person name
                // the NER typed universal is excluded from every domain path below, and until
                // UD from this one too: `subtreeIndices` refuses a universal root, so `Stores in
                // TN` with `TN` labelled GPE proposed no value at all and `TN` left as a grounded
                // place the composer cannot filter stores by. But the sentence has scoped it —
                // the user named the entity, so the value is that entity's before it is a place.
                //
                // So here, and ONLY here (the governed argument of an anchor), a LOCATION/PERSON
                // child takes exactly the path a non-universal argument takes: the governed pair,
                // flagged. Its extent is the ENTITY's, not the parse subtree — the NER is what
                // says where the place name begins and ends. Whether the member reading wins is
                // decided after the gate (`UniversalSeam`), never here: proposal is
                // unconditional. Every other reader of `universal` is untouched, so a bare, an
                // unanchored or a pre-modifier place stays a place (UD contracts §3.2).
                //
                // The candidate records WHICH entity it reads (`dualReadingOf`), because its
                // own extent is the tokens' and need not equal the entity's: an anchor word
                // inside the name is dropped below, and an engine's offsets can disagree with
                // the parse (review-108 F1). The seam pairs the two readings by that record.
                val dual = dualReadingEntity(childIdx, tokens, parse.entitiesList)
                if (dual == null && refs.isEmpty()) return false
                if (dual != null && placeOwners.isEmpty()) return false
                val governedRefs = if (dual != null) placeOwners else refs
                val valueIdx =
                    if (dual != null) {
                        withNameTail(
                            tokensWithin(dual, tokens).filterNot { it in anchorTokens },
                            tokens,
                            universal,
                            anchorTokens + literals.tokens,
                        )
                    } else {
                        subtreeIndices(childIdx, children, tokens, universal, anchorTokens, literals.tokens)
                    }
                if (valueIdx.isEmpty()) return false
                out +=
                    candidate(
                        valueIdx,
                        tokens,
                        governedRefs,
                        if (dual != null) placeCategories else categories,
                        anchored = true,
                        origin = DomainSpanCandidate.Origin.GOVERNED_VALUE,
                        headToken = childIdx,
                        dualReadingOf = dual?.let { it.charStart to it.charEnd },
                        dualReadingScope = if (dual != null) governedRefs else emptyList(),
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
                        dualReadingOf = dual?.let { it.charStart to it.charEnd },
                        // ✅UD-7 — the OPEN half asks everything, but answers only for the
                        // owners the governed half is gated to (`UniversalSeam.inScope`).
                        dualReadingScope = if (dual != null) governedRefs else emptyList(),
                    )
                coveredTokens += valueIdx
                return true
            }

            val seen = HashSet<Int>()
            for (childIdx in children[head + 1].orEmpty()) {
                val child = tokens[childIdx]
                if (child.depRelation !in GOVERNED_VALUE_RELATIONS) continue
                if (child.upos.uppercase() !in NOMINAL_UPOS) continue
                if (childIdx in anchorTokens) continue
                if (!seen.add(childIdx) || !proposeValue(childIdx)) continue
                // ttr-demo#62 — a conjunct is governed by what governs the value it is coordinated with, so
                // only when that value was proposed (`placeConjuncts`).
                for (conjunct in placeConjuncts(childIdx)) if (seen.add(conjunct)) proposeValue(conjunct)
            }
        }

        // (a) anchored subtrees
        tokens.forEachIndexed { idx, t ->
            // LP: `zákazník "dodací místo"` asks for the STRING, not for the entity the estate
            // declared under that name. Quoting is the escape hatch, so it escapes the anchor
            // index too — otherwise the one construct that means "do not look this up" would be
            // the one construct guaranteed to.
            if (idx in literals.tokens) return@forEachIndexed
            // A declared phrase covers the words inside it: `matchesAt`'s longest-wins, carried
            // past the phrase's first word. A hit that lies wholly inside a phrase already
            // proposed is a word of that phrase, not a mention of its own.
            //
            // ⛑ hartland, 2026-10-01. With `revenue` declared bare (→ the all-channel fact) and
            // `marketplace revenue` declared whole (→ one channel's), *"What are the marketplace
            // revenues for 2025 by month?"* proposed both: the inner `revenues` is the phrase's
            // HEAD, not its first word, so same-start longest-wins never saw the two compete. Its
            // own anchor phrase folded `the` in, left the sibling anchor `marketplace` out, and the
            // hull `the [marketplace] revenues` gated against the bare word's owner — matched
            // nothing, gapped G1, and the demo's headline question asked "the revenues?".
            //
            // Only CONTAINMENT yields. Two phrases that merely overlap (`marketplace revenue` /
            // `revenue growth`) both stand — neither names the other's words, and which reading
            // the question means is the gate's to settle.
            val hits =
                matchesAt(idx).filterNot { p ->
                    (idx until idx + p.words.size).all { it in declaredPhraseTokens }
                }
            if (hits.isEmpty()) return@forEachIndexed
            // A MULTI-word anchor names its own extent: the estate said which words, so the span
            // is exactly those and no subtree expansion applies. Single-word anchors keep the
            // Q-20 behaviour below unchanged — phrase expansion plus governed values. A multi-word
            // one governs a place only (✅UD-8).
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
                        // ttr-server#204 — the estate's words, every one: never asked again without some.
                    ).copy(declaredPhrase = true)
                coveredTokens += span
                declaredPhraseTokens += span
                // ✅UD-8 — the phrase governs what its head governs, but only a PLACE is proposed
                // here: see [governedValues]. `Marketplace revenue for Memphis DC`.
                governedValues(syntacticHead(span, tokens), multiWord, placesOnly = true)
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
            // ttr-server#152 — a declared word the NER typed a date, ALL of the date (`Monthly`), is
            // proposed like any other declared word: the estate named it, and a date reading of the
            // same one word cannot also be kept. Which reading keeps it is decided after the gate,
            // by `UniversalSeam` — the declared one only when the matcher confirms it.
            val phraseUniversal =
                dateWords[idx]
                    ?.takeIf { hits.none { it.et.objectKind == OPERATOR_KIND } }
                    ?.let { date -> universal.filterNot { it == date } }
                    ?: universal
            val phraseIdx =
                anchorPhraseIndices(idx, children, tokens, phraseUniversal, anchorTokens, literals.tokens, governors)
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
            governedValues(idx, hits, placesOnly = false)
        }

        // (b) proper-noun arguments not already anchored. Tokens are walked in index order, so a
        // name is entered at its FIRST word — which is not always its head: the parser may hang
        // `QT` off `ORLAK` (flat). The run is built from the name's own head (`nameChain`), so the
        // name is proposed whole whichever word the parser made its head.
        tokens.forEachIndexed { idx, t ->
            if (idx in coveredTokens || idx in literals.tokens) return@forEachIndexed
            if (t.upos.uppercase() != "PROPN") return@forEachIndexed
            if (isUniversal(t, universal)) return@forEachIndexed
            val chain = nameChain(idx, tokens, universal, coveredTokens, literals.tokens)
            val head = chain.last()
            val runIdx = propnRun(head, children, tokens, universal, coveredTokens, literals.tokens, chain)
            if (runIdx.isEmpty()) return@forEachIndexed
            out +=
                candidate(
                    runIdx,
                    tokens,
                    allRefs,
                    allCategories,
                    anchored = false,
                    origin = DomainSpanCandidate.Origin.PROPER_NOUN,
                    headToken = head,
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
            if (UniversalClassifier.isUniversal(e.label, e.normalizedValue, e.text)) continue
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

        // (e) literal runs, scoped by the mention beside them (RV-P2.1 / RV-33) — never by a command.
        val gated = dedupe(out)
        val commands = operatorWords.filter { it.slot == OperatorWords.Slot.COMMAND }.map { it.token }.toSet()
        val proposed =
            dedupe(gated + literalRuns(tokens, universal, gated, coveredTokens + literals.tokens, commands))
        // LP: the invariant, stated once at the end rather than trusted to six exclusions above.
        // Each of those keeps a literal out of the source it guards; this one is the promise the
        // lattice depends on — NOTHING proposed overlaps a literal, however it was proposed.
        val kept = if (literals.isEmpty) proposed else proposed.filterNot { literals.overlaps(it.start, it.end) }
        // CN-P4 (⚑R130-a / A-CN-72) — and every candidate names what its refs STAND FOR: a concept anchor's
        // landing. Its categories are untouched (the concept's own term rows are still asked); its gated refs —
        // which the slot stamp, the Binder's tier M, a dual reading's scope, a named member's anchors and the
        // lattice read as the object — are the landing. One point, so no reader has to know the rule.
        return kept.map { c ->
            val refs = c.gatedEntityRefs.map(standsFor).distinct()
            val scope = c.dualReadingScope.map(standsFor).distinct()
            if (refs == c.gatedEntityRefs &&
                scope == c.dualReadingScope
            ) {
                c
            } else {
                c.copy(gatedEntityRefs = refs, dualReadingScope = scope)
            }
        }
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
        // ttr-server#58 — the heads of COMMAND operator words, which scope no literal.
        commands: Set<Int> = emptySet(),
    ): List<DomainSpanCandidate> {
        val mentions =
            gated.filter {
                it.origin == DomainSpanCandidate.Origin.ANCHOR_PHRASE && it.headToken >= 0 && it.headToken !in commands
            }
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

    /**
     * What a mention is asked as when its phrase, as folded, bound nothing ([retryOf]): [core], the
     * phrase without the words left out, and [dropped], those words, one dependent at a time.
     */
    internal data class Retry(
        val core: List<Int>,
        val dropped: List<Dropped>,
    ) {
        /** The adjectives among the words left out. */
        val modifiers: List<Int> get() = dropped.filterNot { it.determiner }.map { it.root }
    }

    /**
     * One dependent a mention may stand without ([detachable]): its own token [root]; [tokens], what
     * leaves the phrase with it (it, what hangs from it, and for a conjunct its `and` / comma); and
     * [words], what it is as a mention of its own — a determiner's whole subtree, an adjective's one word.
     */
    internal data class Dropped(
        val root: Int,
        val tokens: List<Int>,
        val determiner: Boolean,
        val words: List<Int> = tokens,
    )

    /**
     * ttr-server#204 — the words of a mention a second lookup may leave out.
     *
     * Both phrase builders fold the head noun's `det` and `amod` dependents into its mention — this
     * file's anchor phrase and `MentionLayer`'s leftover — and #98's narrowness control says why they
     * must: *the account* / *toho účtu* is ONE mention, and a clarification quotes the user's words
     * back. But the matcher holds the estate's word, not the user's phrase. `marketplace` is declared
     * and `the marketplace` is not, so an `EXACT` row cannot meet the phrase, and every English
     * mention with an article in it came back G1 (`the marketplace`, `the web`, `What share`) while
     * the bare word bound. `MentionRetry` asks again with what this returns, and decides what becomes
     * of each word left out — silently gone, or surfaced as a word nothing understood.
     *
     * Which words may leave is [detachable]'s rule, over the phrase's own words. A known word cancels
     * the retry: whether the lexicon knows the word is the matcher's to say, and `largest` is an
     * operator word — *the largest stores* asked as `stores` would answer a different question. A
     * DECLARED modifier never gets this far: an anchor word is nobody's modifier, so `Monthly` in
     * *Monthly revenue* is a mention of its own already.
     *
     * Null when nothing would be left out — the second lookup would be the first one again. The head
     * is never left out: it is what the mention is.
     */
    internal fun retryOf(
        phrase: List<Int>,
        head: Int,
        tokens: List<Token>,
    ): Retry? {
        if (head !in phrase) return null
        val inPhrase = phrase.toHashSet()
        val dropped = detachable(head, tokens, dependentsOf(tokens), inPhrase)
        if (dropped.isEmpty()) return null
        val left = dropped.flatMap { it.tokens }.toSet()
        return Retry(phrase.filter { it !in left }.sorted(), dropped.sortedBy { it.root })
    }

    /**
     * ttr-server#204 / #237 — **the dependents of [head] a mention may stand without: ONE rule, two
     * readers.** `MentionRetry` asks a phrase that bound nothing again without them ([retryOf], [scope]
     * the phrase's own words); `MentionLayer.leftOutModifiers` makes the ones a declared phrase never
     * took mentions of their own ([scope] the words no span or leftover covers). Which of them restrict
     * nothing is `FrameRolePreps.restrictsNothing`, also one rule for both.
     *
     *  - **Every `det*` dependent, with whatever hangs from it.** An article or an interrogative says
     *    WHICH one (#98 took the interrogative out of the anchor phrase outright); `no`, `all`, `these`
     *    restrict, and are reported.
     *  - **A BARE `amod` dependent — an adjective with no words of its own — and its conjuncts.**
     *    *Měsíční a čtvrtletní tržby* coordinates two adjectives; the second hangs from the first as
     *    `conj`, so it is the first's dependent but no word of its own: each conjunct leaves as its own
     *    dependent, with its `and` / comma.
     *  - **An adjective with words of its own stays** — *How many stores*, *vyzvednuté na prodejně*:
     *    what it governs restricts the noun, and the noun alone would be another question. Outside a
     *    declared phrase those words are mentions or values of their own (`na prodejně`), and *How
     *    many* is the count `SlotHints` reads.
     *
     * Every word returned is in [scope]; a dependent part of which falls outside it is not returned.
     */
    internal fun detachable(
        head: Int,
        tokens: List<Token>,
        dependents: Map<Int, List<Int>>,
        scope: Set<Int>,
    ): List<Dropped> {
        val dropped = mutableListOf<Dropped>()
        for (c in dependents[head].orEmpty()) {
            if (c !in scope || c == head) continue
            val relation = tokens[c].depRelation
            when {
                relation == "det" || relation.startsWith("det:") -> {
                    // A parse with a cycle in it could reach the head from below; the head stays.
                    val subtree = (subtreeWithin(c, dependents, scope) - head).sorted()
                    dropped += Dropped(c, subtree, determiner = true)
                }
                relation.substringBefore(':') == "amod" -> {
                    val conjuncts = coordination(c, tokens, dependents, scope) ?: continue
                    for (word in listOf(c) + conjuncts) {
                        val glue = dependents[word].orEmpty().filter { it in scope && tokens[it].depRelation in GLUE }
                        dropped +=
                            Dropped(word, (listOf(word) + glue).sorted(), determiner = false, words = listOf(word))
                    }
                }
            }
        }
        return dropped
    }

    /** What joins coordinated adjectives and is no word of either: `a` / `and`, the comma. */
    private val GLUE = setOf("cc", "punct")

    /**
     * The conjuncts of [adjective] when it has no words of its own — every dependent is a conjunct or
     * glue, and every conjunct has nothing but glue under it — or null when it has some.
     */
    private fun coordination(
        adjective: Int,
        tokens: List<Token>,
        dependents: Map<Int, List<Int>>,
        scope: Set<Int>,
    ): List<Int>? {
        val conjuncts = mutableListOf<Int>()
        for (d in dependents[adjective].orEmpty()) {
            when (tokens[d].depRelation) {
                in GLUE -> {}
                "conj" -> {
                    if (d !in scope || dependents[d].orEmpty().any { tokens[it].depRelation !in GLUE }) return null
                    conjuncts += d
                }
                else -> return null
            }
        }
        return conjuncts
    }

    /** token → its dependents, by 0-based index (`dep_head` is 1-based; 0 is the root). */
    internal fun dependentsOf(tokens: List<Token>): Map<Int, List<Int>> {
        val dependents = HashMap<Int, MutableList<Int>>()
        tokens.forEachIndexed { i, t ->
            if (t.depHead > 0) dependents.getOrPut(t.depHead - 1) { mutableListOf() } += i
        }
        return dependents
    }

    /** [root] and everything below it, inside [scope] — what leaves a phrase with [root]. */
    private fun subtreeWithin(
        root: Int,
        dependents: Map<Int, List<Int>>,
        scope: Set<Int>,
    ): Set<Int> {
        val out = HashSet<Int>()
        val stack = ArrayDeque(listOf(root))
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            if (i !in scope || !out.add(i)) continue
            dependents[i]?.let { stack.addAll(it) }
        }
        return out
    }

    private fun anchorPhraseIndices(
        headIdx: Int,
        children: Map<Int, List<Int>>,
        tokens: List<Token>,
        universal: List<IntRange>,
        anchorTokens: Set<Int>,
        literalTokens: Set<Int> = emptySet(),
        literalGovernors: Set<Int> = emptySet(),
    ): List<Int> {
        if (isUniversal(tokens[headIdx], universal)) return emptyList()
        val included = sortedSetOf(headIdx)
        for (c in children[headIdx + 1].orEmpty()) {
            if (c in anchorTokens) continue // a sibling anchor is its own mention, not a modifier
            // A modifier a quoted literal hangs from says how the literal restricts this thing;
            // it is not part of the thing's name (*prodejny začínající na „abl“*).
            if (c in literalGovernors) continue
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
        // contiguous hull, dropping any universal token inside it — and never filling a gap with
        // a governor either, which would put back the word the loop above just left out
        return contiguousHull(included, tokens, universal, anchorTokens + literalGovernors, headIdx, literalTokens)
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

    /**
     * From [idx] up to the head of the name it is part of: `[idx, …, head]`.
     *
     * A token climbs while it is a name part ([PROPN_RUN_RELATIONS]) of an open proper noun — not
     * covered, not a literal, not universal — the same test [propnRun] applies going down, so the
     * run is one name whichever word the parser made its head. A token that is no one's name part
     * is its own head, and the chain is `[idx]`.
     */
    private fun nameChain(
        idx: Int,
        tokens: List<Token>,
        universal: List<IntRange>,
        covered: Set<Int>,
        literalTokens: Set<Int>,
    ): List<Int> {
        val chain = mutableListOf(idx)
        while (true) {
            val token = tokens[chain.last()]
            val parent = token.depHead - 1 // dep_head is 1-based; 0 means root
            if (token.depRelation !in PROPN_RUN_RELATIONS || parent !in tokens.indices || parent in chain) break
            val p = tokens[parent]
            if (p.upos.uppercase() != "PROPN" || parent in covered || parent in literalTokens) break
            if (isUniversal(p, universal)) break
            chain += parent
        }
        return chain
    }

    private fun propnRun(
        headIdx: Int,
        children: Map<Int, List<Int>>,
        tokens: List<Token>,
        universal: List<IntRange>,
        covered: Set<Int>,
        literalTokens: Set<Int> = emptySet(),
        /** The words climbed through to reach [headIdx] (`nameChain`) — part of the name too. */
        chain: List<Int> = emptyList(),
    ): List<Int> {
        val included = sortedSetOf(headIdx)
        included += chain
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
     * LP — the tokens a quoted literal HANGS from: every token on the `dep_head` chain above a
     * literal's tokens, the literal's own excluded.
     *
     * A phrase modifier among them is the literal's restriction, not part of its head's name. Both
     * Czech analysers make the participle in *prodejny začínající na „abl“* an `amod` of the noun,
     * and the literal hangs from the participle; folded into the anchor phrase it made *prodejny
     * začínající*, which names nothing, so the mention bound nothing (G1) and the literal lost its
     * head with it (G3). An adjective the literal does NOT hang from — *kamenné prodejny* — is still
     * part of the name. Only a real parse has a chain; the floor returns nothing here, which is why
     * the demo estate first met this on 2026-09-25, the day its analyser came up.
     */
    internal fun literalGovernors(
        tokens: List<Token>,
        literalTokens: Set<Int>,
    ): Set<Int> {
        if (literalTokens.isEmpty()) return emptySet()
        val out = HashSet<Int>()
        for (start in literalTokens) {
            var current = start
            // Bounded by the question's length, so a parse with a cycle in it cannot hang a resolve.
            var hops = 0
            while (hops++ < tokens.size) {
                val next = (tokens.getOrNull(current)?.depHead ?: 0) - 1 // 1-based; 0 is the root
                if (next < 0 || next == current) break
                // A chain already walked from another literal token has nothing new above it.
                if (next !in literalTokens && !out.add(next)) break
                current = next
            }
        }
        return out
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
        dualReadingOf: Pair<Int, Int>? = null,
        dualReadingScope: List<String> = emptyList(),
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
            dualReadingOf = dualReadingOf,
            dualReadingScope = dualReadingScope,
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
    internal fun universalCharRanges(entities: List<NerEntity>): List<IntRange> =
        entities.filter { UniversalClassifier.isUniversal(it.label, it.normalizedValue, it.text) }.map {
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

    /**
     * UD — the NER entity that makes [childIdx] a dual reading: the entity covering the token's
     * mid-character (the geometry [isUniversal] uses) whose universal type has a member reading to
     * offer ([UniversalClassifier.DUAL_READING_TYPES]). `null` when no such entity covers it — a
     * date or an amount under an anchor stays as it was.
     */
    private fun dualReadingEntity(
        childIdx: Int,
        tokens: List<Token>,
        entities: List<NerEntity>,
    ): NerEntity? {
        val mid = (tokens[childIdx].charStart + tokens[childIdx].charEnd) / 2
        return entities.firstOrNull { e ->
            mid in e.charStart until e.charEnd &&
                UniversalClassifier.dualReadingType(e.label, e.normalizedValue, e.text) != null
        }
    }

    /**
     * ✅UD-9 — the words the parse hangs on a place name right AFTER its NER extent: `Praha DC`,
     * where NameTag types `Praha` a place and the parse makes `DC` its `nmod`. The member's name is
     * the whole run, and a reading of `Praha` alone left `DC` behind as an unbound mention (G1),
     * which turned a bound member into a clarification about „DC“.
     *
     * Contiguous and conservative: the next token joins only while it is a nominal whose head is
     * already in the run, by a name-continuing relation, and is neither an anchor, a quoted
     * literal, nor part of another universal entity. The first token that fails ends the run, so a
     * preposition (`Brno v lednu`) or an anchor word (`Brno prodejny`) is never swallowed. Nothing
     * extends to the LEFT: the NER's start stands. Where the NER already spans the name
     * (`Dallas DC` typed whole), there is no tail and the extent is exactly the entity's.
     *
     * The tail word may also be the run's HEAD rather than its dependent: English puts a compound's
     * head last, so Stanza parses `for Memphis DC` with `Memphis` the `compound` of `DC` and `DC`
     * hung on the noun the name belongs to. The word the run's last token is a [HEAD_FINAL_NAME_RELATIONS]
     * dependent of continues the name the same way — a reading of `Memphis` alone left `DC` behind,
     * and in a world with a state called `DC` the leftover asked which `DC` was meant.
     */
    internal fun withNameTail(
        extent: List<Int>,
        tokens: List<Token>,
        universal: List<IntRange>,
        excluded: Set<Int>,
    ): List<Int> {
        if (extent.isEmpty()) return extent
        val run = extent.toMutableList()
        var next = extent.max() + 1
        while (next < tokens.size) {
            val t = tokens[next]
            if (next in excluded || isUniversal(t, universal)) break
            if (t.upos.uppercase() !in NOMINAL_UPOS) break
            val hangsOnRun = t.depRelation in NAME_TAIL_RELATIONS && (t.depHead - 1) in run
            val last = tokens[run.last()]
            val headsRun = last.depRelation in HEAD_FINAL_NAME_RELATIONS && last.depHead - 1 == next
            if (!hangsOnRun && !headsRun) break
            run += next
            next++
        }
        return run
    }

    /** Every token whose mid-character lies inside [entity]'s extent — the entity, as tokens. */
    internal fun tokensWithin(
        entity: NerEntity,
        tokens: List<Token>,
    ): List<Int> {
        val extent = entity.charStart until entity.charEnd
        return tokens.indices.filter { (tokens[it].charStart + tokens[it].charEnd) / 2 in extent }
    }
}
