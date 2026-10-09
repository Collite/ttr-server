// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.v1.FrameRole
import org.tatrman.resolver.v1.TargetClass

/**
 * RV-P2.1.T5 — frame-role derivation (RV-21, Q-15 **RULED rule-based, no LLM assist**:
 * SUBJECT precision 1.000 against a 0.85 bar, on the 39-fixture corpus *and* on a held-out
 * set authored after the rules were frozen). This is the port of
 * `implementation/spikes/frame-roles/deriver.py`, and `FrameRolesFixtureTest` re-runs the
 * spike's own corpus against it in-process.
 *
 * **The precedence is the design, so this is an ordered pass and not a rule bag.** Roles that
 * follow from the MODEL are settled first, because they are the facts we are most sure of;
 * roles that follow from SYNTAX are layered on top; SUBJECT is resolved LAST, from the
 * residue — "what the question is about" is what remains once the filters, the groupings and
 * the operators are accounted for.
 *
 * ```
 * R0  target_class == OPERATOR                      -> {}         # RV-35, always
 * R1  target_class == MEMBER                        -> +FILTER    # a member IS a restriction
 * R2  object_kind  == measure                       -> +MEASURE   # a measure IS the measure
 * R3  prep in GROUPING_PREPS and not capable(m)     -> +GROUPING
 * R3' prep in DISTRIBUTIVE_PREPS, a model object, not capable  -> A/B (below)
 * R4  prep in FILTER_PREPS   and not capable(m)     -> +FILTER
 * R5  no prep, deprel == compound, not capable      -> +FILTER     # "WEB revenue"
 * R6  no prep, deprel starts "obl", not capable     -> +FILTER     # "minulý TÝDEN"
 * R6' no prep, deprel == advmod, binds an attribute -> +FILTER     # "LONI" (spike F-1)
 * R7  deprel == conj  -> inherit the head mention's FILTER / GROUPING
 * R8  m anchors a value and deprel not nsubj        -> +FILTER     # "účtu 501001"
 * R9  residue: the nsubj candidate, else the shallowest+leftmost   -> +SUBJECT (AT MOST ONE)
 * ```
 *
 * Three of these carry most of the weight:
 *
 * **R3's measure exception is what makes `podle`/`by` safe.** It is GROUP-BY in *"tržby podle
 * prodejen"* and ORDER-BY in *"prvních 10 stanic podle tržby"* — the same preposition, opposite
 * roles. The discriminator is neither the operator nor the word order: it is whether the
 * governed mention is **measure-capable**, which is model-driven and therefore does not need a
 * top-n operator to have been recognised first.
 *
 * ⚑ MS widened that predicate from `measure` to `measure ∪ entity_with_measures`, and the reason
 * is *"prodeje podle prodejen"*: `prodeje` binds the sales ENTITY, which declares measures, and
 * the question groups by the branch. Grouping by the sales themselves is not a reading anyone
 * asked for. R2 was NOT widened with it — see [measureCapable].
 *
 * **R9(a) `nsubj` is the highest-value signal in the set.** It separates *"Které POLOŽKY nebyly
 * skladem"* (subject = the dimension) from *"Zobraz TRŽBY podle prodejen"* (subject = the
 * measure) with no model-side heuristic. A deriver built on "the subject is the measure" gets
 * the stock-out question wrong; one built on "the subject is the root" gets H1 wrong, because
 * Stanza tags the Czech imperative *Zobraz* as a NOUN and sometimes as the root.
 *
 * **R9's at-most-one is a contract, not a convenience.** RV-15 fires an ask on *the*
 * load-bearing gap. A deriver that always names some subject would ask a question on a
 * follow-up turn whose subject lives in the conversation rather than in the core.
 */
object FrameRoles {
    private val SUBJECT_DEPRELS = setOf("nsubj", "nsubj:pass")

    /** R3' — the classes a distributive preposition never regroups: a kernel's trigger, a member value. */
    private val NOT_DISTRIBUTIVE = setOf(TargetClass.TARGET_CLASS_GROUNDING_TRIGGER, TargetClass.TARGET_CLASS_MEMBER)

    /**
     * One mention as the rules see it: where it sits in the parse, and the two model facts
     * (what class it bound, what kind of object that is). Nothing else about the model is
     * read — no ref-name string matching, no lexicon lookup.
     */
    data class Input(
        val id: String,
        val charStart: Int,
        val headToken: Int,
        val targetClass: TargetClass,
        val objectKind: String,
        val anchorsValue: Boolean,
        /**
         * cs LR S5 — the mention's exclusive end offset, so R3' can tell whether its preposition
         * lies INSIDE the declared phrase. Defaulted and last: `-1` reads as "no extent", and R3'
         * signal A then stays silent.
         */
        val charEnd: Int = -1,
    )

    fun derive(
        mentions: List<Input>,
        parse: AnalyzeResponse,
        lang: String,
        preps: FrameRolePreps,
    ): Map<String, List<FrameRole>> {
        val tokens = parse.tokensList
        val roles = mentions.associate { it.id to sortedSetOf<FrameRole>(compareBy { role -> role.number }) }
        val grouping = preps.grouping(lang)
        val filter = preps.filter(lang)
        val distributive = preps.distributive(lang)
        val byHead = mentions.filter { it.headToken >= 0 }.associateBy { it.headToken }

        // -- R0 operators carry no frame role, ever (RV-35) --------------------
        val scoreable = mentions.filter { it.targetClass != TargetClass.TARGET_CLASS_OPERATOR }

        // -- R1/R2 model-driven roles -----------------------------------------
        for (m in scoreable) {
            if (m.targetClass == TargetClass.TARGET_CLASS_MEMBER) roles.getValue(m.id) += FrameRole.FRAME_ROLE_FILTER
            if (isMeasure(m)) roles.getValue(m.id) += FrameRole.FRAME_ROLE_MEASURE
        }

        // -- R3..R6' syntax-driven roles --------------------------------------
        for (m in scoreable) {
            if (m.headToken < 0 || m.headToken >= tokens.size) continue
            val capable = measureCapable(m)
            val prep = prepositionOf(parse, m.headToken)
            val relation = tokens[m.headToken].depRelation
            when {
                prep != null && prep in grouping -> {
                    // "podle X" / "by X" is GROUP-BY for a dimension and ORDER-BY for anything
                    // measure-capable. The measure case needs no extra role — R2 already gave it
                    // MEASURE — and crucially must NOT become a grouping. A measure-CAPABLE entity
                    // gets no role here either: it is not the grouping axis, and MS-R6 leaves the
                    // rows/count/value reading to the operator layer.
                    if (!capable) roles.getValue(m.id) += FrameRole.FRAME_ROLE_GROUPING
                }
                // R3' — `po` is "per month" or "after the year"; see [distributiveRoles]. Not for a
                // grounding trigger („po roce 2020“ binds the time kernel) nor a member (R1 already
                // made it a restriction): those keep R4's FILTER, exactly as before.
                prep != null && prep in distributive && !capable && m.targetClass !in NOT_DISTRIBUTIVE ->
                    roles.getValue(m.id) += distributiveRoles(m, parse)
                prep != null && prep in filter -> {
                    if (!capable) roles.getValue(m.id) += FrameRole.FRAME_ROLE_FILTER
                }
                prep == null && !capable ->
                    when {
                        // A noun premodifying another noun restricts it: "WEB revenue".
                        relation == "compound" -> roles.getValue(m.id) += FrameRole.FRAME_ROLE_FILTER
                        // A bare oblique with no preposition is an adverbial restriction:
                        // "minulý TÝDEN". With a preposition it was caught above.
                        relation.startsWith("obl") -> roles.getValue(m.id) += FrameRole.FRAME_ROLE_FILTER
                        // Spike F-1: a temporal adverb has no nominal head at all — `loni`
                        // ("last year") is ADV/advmod, and `obl` is a nominal relation. The
                        // report's one-line fix reads "advmod + Calendar binding"; the rules may
                        // not match a ref string, so the model fact used is the object KIND —
                        // an adverb that binds an attribute is restricting by it.
                        relation == "advmod" && m.objectKind.equals("attribute", ignoreCase = true) ->
                            roles.getValue(m.id) += FrameRole.FRAME_ROLE_FILTER
                    }
            }
        }

        // -- R7 coordination: `conj` inherits the head mention's axis role ------
        for (m in scoreable) {
            if (m.headToken < 0 || m.headToken >= tokens.size) continue
            if (tokens[m.headToken].depRelation != "conj") continue
            val parent = byHead[headIndexOf(tokens[m.headToken].depHead)] ?: continue
            for (role in listOf(FrameRole.FRAME_ROLE_GROUPING, FrameRole.FRAME_ROLE_FILTER)) {
                if (role in roles.getValue(parent.id)) roles.getValue(m.id) += role
            }
        }

        // -- R8 a mention that anchors a value is a filter axis ------------------
        // ("účtu 501001", "kategorii Elektronika"). Skipped when the mention is the clause
        // subject — "kategorie Knihy" in a share-of question is what the question is ABOUT,
        // not a restriction on it.
        for (m in scoreable) {
            if (!m.anchorsValue || m.headToken < 0 || m.headToken >= tokens.size) continue
            if (tokens[m.headToken].depRelation in SUBJECT_DEPRELS) continue
            if (FrameRole.FRAME_ROLE_GROUPING in roles.getValue(m.id)) continue
            roles.getValue(m.id) += FrameRole.FRAME_ROLE_FILTER
        }

        // -- R9 SUBJECT, last, from the residue ---------------------------------
        val candidates =
            scoreable.filter {
                FrameRole.FRAME_ROLE_FILTER !in roles.getValue(it.id) &&
                    FrameRole.FRAME_ROLE_GROUPING !in roles.getValue(it.id)
            }
        val pick =
            candidates.firstOrNull {
                it.headToken >= 0 && it.headToken < tokens.size && tokens[it.headToken].depRelation in SUBJECT_DEPRELS
            } ?: candidates.minWithOrNull(compareBy({ depth(parse, it.headToken) }, { it.charStart }))
        if (pick != null) roles.getValue(pick.id) += FrameRole.FRAME_ROLE_SUBJECT

        return roles.mapValues { (_, set) -> set.toList() }
    }

    /**
     * **R2's predicate — and since MS-P2, one that fires in production.**
     *
     * The kinds ARRIVE. The chain, end to end, and every link is a file you can open:
     *
     *  1. an estate declares the mention facet in its model — `semantics { name: · code: ·
     *     measures: [...] }` on an `entity` or a db `table` (MS contracts §1.1);
     *  2. `MentionKinds` (ttr-semantics) turns those declared facts into ONE of
     *     `measure | attribute | entity | entity_with_measures` — the whole derivation table, in
     *     one place, at COMPILE time (contracts §5);
     *  3. `LexiconCompiler` writes them into the compiled lexicon archive's `targets` map
     *     (`ttr-lexicon-compiled/v2`);
     *  4. `LexiconArchiveRegistrySource` copies them onto `ResolverEntityType.objectKind`
     *     verbatim, and `LatticeAssembler.objectKindOf` hands them to [Input].
     *
     * So a blank kind now means what it says — *this estate declared no mention facet* — and R2
     * being inert for it is the correct reading rather than a missing wire.
     *
     * **History, because it explains the shape of everything around this rule.** Until 2026-09-01
     * this comment was an obituary: `objectKind` had no source in the system at all. `meta.v1`
     * contained zero occurrences of `measure`; `ttr-metadata`'s `ModelObject.kind` vocabulary
     * (`table | view | column | entity | attribute | relation | …`) had no member for it; the
     * archive stated only a target CLASS (`MODEL_OBJECT | MEMBER | OPERATOR |
     * GROUNDING_TRIGGER`), a different axis; and the per-request `Registry` override had the
     * field with nothing populating it. The cost was wider than R2, because this predicate also
     * gated R3–R6 — so nothing was ever exempted from FILTER or GROUPING *for being the measure*,
     * and a measure mention came back SUBJECT with its compound modifier taking FILTER through
     * R5. MS supplied the missing authority; it did not unwire anything here.
     *
     * ⛔ **The rule that outlived the obituary: no kind is DERIVED in this service.** Not from the
     * ref's prefix, not from its dots, not from the category it matched on.
     * `LexiconArchiveRegistrySource` refuses to do it for the same reason, and the reason is
     * unchanged now that an authority exists: a second rule is free to drift from the model's own,
     * and the two would disagree about a question no one thinks to re-check. The model decides,
     * through exactly one table, upstream.
     *
     * That rule is now **enforced, not merely stated**: `verifyNoKindDerivation` in this module's
     * `build.gradle.kts` fails the build if any MAIN source imports `org.tatrman.ttr.semantics`,
     * so the local fix this comment forbids cannot compile. (Naming the table in prose, as the
     * chain above does, stays legal — explaining where a kind comes from is the opposite of
     * deriving one.) Added at review-084 F2, which found that the `testImplementation` scope
     * everyone assumed was doing this job was doing nothing: `ttr-metadata` puts `ttr-semantics`
     * on the runtime classpath regardless.
     */
    private fun isMeasure(mention: Input): Boolean = mention.objectKind.equals("measure", ignoreCase = true)

    /**
     * R3–R6's predicate: this mention IS a measure, or it HAS measures.
     *
     * The distinction from [isMeasure] is MS-R6 and it is deliberate. *"prodeje podle prodejen"*
     * groups by the branch, not by the sales — so an entity that declares measures must be
     * exempted from GROUPING and FILTER exactly as a measure is. But it must NOT be stamped
     * MEASURE: whether *"kolik prodejů"* wants rows, a count, or the value of a measure is a
     * reading the operator layer makes from operator evidence (design.md §6.3, contracts §9), and
     * a role asserted here would pre-empt it with the one fact the model cannot supply.
     *
     * Everything else stays keyed to the narrower predicate: R2 above, and R6' below, which reads
     * `attribute` exactly — spike F-1 found a temporal adverb binding a calendar ATTRIBUTE, and
     * widening it to measure-capable kinds would make *"loni"* a filter for binding an entity.
     */
    private fun measureCapable(mention: Input): Boolean =
        isMeasure(mention) || mention.objectKind.equals("entity_with_measures", ignoreCase = true)

    /**
     * **R3' — a distributive preposition (cs `po`), decided by two independent signals.**
     *
     * „Tržby z tržiště po měsících“ is by month; „tržby po roce 2020“ is after 2020. The same
     * preposition, so neither table can hold it, and no one signal is reliable on its own:
     *
     *  - **A — the declaration.** The estate wrote the preposition into the phrase (`po měsících`
     *    → the month attribute) and nothing hangs a value on it: the estate named a breakdown.
     *    Says GROUPING, or nothing. Needs no morphology, so it holds for an unlemmatised
     *    `po mesicich` too.
     *  - **B — the grammar.** The governed noun's `Number`: plural reads per-X (GROUPING),
     *    singular reads after-X (FILTER). Silent when the tagger gives no number — an unknown
     *    word, a sentence without diacritics.
     *
     * Agreement, or one voice, decides. **Disagreement is not settled here**: the mention carries
     * both GROUPING and FILTER, which no other rule produces, and a consumer reads that pair as
     * "undecided" and hands the sentence to a reader that can weigh it (golem: the LLM lane). Both
     * silent keeps R4's FILTER, so a mention neither signal speaks for reads exactly as before.
     */
    private fun distributiveRoles(
        m: Input,
        parse: AnalyzeResponse,
    ): Set<FrameRole> {
        val tokens = parse.tokensList
        val case = caseTokenOf(parse, m.headToken)
        val declared =
            case != null && m.charEnd > m.charStart && case.charStart >= m.charStart && case.charEnd <= m.charEnd
        val a = if (declared && !m.anchorsValue) FrameRole.FRAME_ROLE_GROUPING else null
        val b =
            when (tokens[m.headToken].featsMap["Number"]) {
                "Plur" -> FrameRole.FRAME_ROLE_GROUPING
                "Sing" -> FrameRole.FRAME_ROLE_FILTER
                else -> null
            }
        return when {
            a == null && b == null -> setOf(FrameRole.FRAME_ROLE_FILTER)
            a == null || b == null || a == b -> setOfNotNull(a ?: b)
            else -> setOf(FrameRole.FRAME_ROLE_GROUPING, FrameRole.FRAME_ROLE_FILTER)
        }
    }

    /** The adposition attached to [headToken] by a `case` relation, if any. */
    private fun caseTokenOf(
        parse: AnalyzeResponse,
        headToken: Int,
    ): Token? = parse.tokensList.firstOrNull { headIndexOf(it.depHead) == headToken && it.depRelation == "case" }

    /** The lemma of the adposition attached to [headToken] by a `case` relation, folded low. */
    private fun prepositionOf(
        parse: AnalyzeResponse,
        headToken: Int,
    ): String? = caseTokenOf(parse, headToken)?.let { (it.lemma.ifBlank { it.text }).lowercase() }

    /** Depth of a token in the dep tree; cycle-safe, because a bad parse must not hang a resolve. */
    private fun depth(
        parse: AnalyzeResponse,
        token: Int,
    ): Int {
        var current = token
        var depth = 0
        val seen = HashSet<Int>()
        while (current in parse.tokensList.indices && seen.add(current)) {
            val next = headIndexOf(parse.tokensList[current].depHead)
            if (next < 0) break
            current = next
            depth++
        }
        return depth
    }

    /** `dep_head` is 1-based with 0 for the root; the token list is 0-based. */
    private fun headIndexOf(depHead: Int): Int = depHead - 1
}
