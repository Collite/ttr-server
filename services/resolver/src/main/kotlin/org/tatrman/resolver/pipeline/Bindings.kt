// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.resolver.v1.EquivalentReading as ProtoEquivalentReading
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.resolver.v1.Binding
import org.tatrman.resolver.v1.BindingProvenance
import org.tatrman.resolver.v1.MatchMethod
import org.tatrman.resolver.v1.SourceTag
import org.tatrman.resolver.v1.TargetClass
import org.tatrman.fuzzy.v1.SourceTag as FuzzySourceTag
import org.tatrman.fuzzy.v1.TargetClass as FuzzyTargetClass

/**
 * RV-P2.1.T4 — one matcher candidate → one lattice [Binding]. This is the whole
 * lex-matcher → resolver translation, in one place, so the two vocabularies can be
 * compared side by side instead of being reconciled ad hoc at three call sites.
 *
 * Two things are DERIVED here rather than copied, each for a reason:
 *
 *  - **the uniform ref.** A declared row carries `target_ref`; a member row carries a data
 *    PK and no ref at all, because "a data value points at nothing but itself". The lattice
 *    needs one addressable ref per binding, so a member becomes `<category>#<candidate_id>`
 *    — the same `#` form the vocabulary side already uses (`er.branch#term-pobocka`).
 *  - **the target class of a member.** lex-matcher leaves it UNSPECIFIED on member rows for
 *    its own good reason; the resolver knows the row came out of the member index, so it
 *    says MEMBER. Nothing is invented — the layer IS the evidence.
 * The **evidence class** is the one thing this mapper no longer derives. P2.1 computed it here
 * from the layer and the score and left a ⚑ saying RV-P2.2 would replace it; the replacement is
 * [EvidenceClasses], and the shape of the fix is that [of] now *takes* a [Binder.ClassedMatch]
 * rather than a bare row. A mapper cannot mint a class it was not handed, and the only things
 * that produce one are the gate and the trigger-annotation path — which is what makes "the gate
 * is the only binder" a property of the types instead of a rule someone has to remember.
 */
object Bindings {
    /** `TYPOS(2)` → distance 2. The matcher carries the method as this string form (RV-32). */
    private val TYPOS = Regex("""^TYPOS\((\d+)\)$""", RegexOption.IGNORE_CASE)

    fun of(
        classed: Binder.ClassedMatch,
        snapshotHash: String,
        /**
         * MH-D3 — readings proven equal to this one by declared relations. Defaulted, so every
         * existing call site keeps producing byte-identical bindings; only the WINNER of a bound
         * span is ever handed a non-empty list.
         */
        equivalents: List<EquivalentReading> = emptyList(),
    ): Binding {
        val match = classed.match
        val isMember = match.source == FuzzySourceTag.MEMBER
        val builder =
            Binding
                .newBuilder()
                .setRef(refOf(match, isMember))
                .setTargetClass(targetClassOf(match, isMember))
                .setEvidenceClass(classed.evidenceClass)
                .setSource(sourceOf(match.source))
                .setInClassScore(match.score)
                .setProducer(
                    BindingProvenance
                        .newBuilder()
                        .setVocabularySource(match.source.name)
                        .setAlgorithm(match.provenance.method.ifBlank { "TATRMAN" })
                        .setScore(match.score)
                        .setSnapshotHash(snapshotHash),
                )
        if (match.hasMatchMethod()) {
            val method = match.matchMethod.trim()
            val typos = TYPOS.matchEntire(method)
            when {
                typos != null -> {
                    builder.method = MatchMethod.MATCH_METHOD_TYPOS
                    builder.maxDistance = typos.groupValues[1].toInt()
                }
                method.equals("TOKENS", ignoreCase = true) -> builder.method = MatchMethod.MATCH_METHOD_TOKENS
                method.equals("EXACT", ignoreCase = true) -> builder.method = MatchMethod.MATCH_METHOD_EXACT
                // An unknown method is left UNSPECIFIED rather than guessed: the estate authored
                // something this build does not understand, and saying so is the honest answer.
            }
        }
        if (match.hasUniquenessMargin()) builder.uniquenessMargin = match.uniquenessMargin
        if (match.hasAutoBindable()) builder.autoBindable = match.autoBindable
        for (e in equivalents) {
            builder.addEquivalents(
                ProtoEquivalentReading
                    .newBuilder()
                    .setRef(e.ref)
                    .setRule(e.rule),
            )
        }
        return builder.build()
    }

    /**
     * Whether a binding may become a value ATTRIBUTION.
     *
     * An attribution says *this literal could be a value of THAT attribute*, so what it names has
     * to be an attribute. Three classes can never satisfy that and all three reach a value span by
     * ordinary means: an OPERATOR is an action, a GROUNDING_TRIGGER is evidence about which
     * KERNEL owns the span (RV-42), and a STRING_PREDICATE is the *comparison* rather than either
     * side of it (LP contracts §3.4). Golem's `entityTypeRefs` excludes the same set, for the same
     * reason — "an operator is an action and a grounding trigger is evidence".
     *
     * STRING_PREDICATE is the one of the three that is **most** likely to be the strongest match on
     * its span, which is why excluding it matters rather than merely tidying: *obsahující* is a
     * whole word an author declared EXACT, so on *dodací místa obsahující "Pelex"* it wins its
     * anchor outright. Admitted, it would write `attribute_ref = "pred:contains"` and the query
     * door would refuse the turn with `'pred:contains' is not an addressable object or attribute`
     * — the hartland `ground:chrono` failure below, verbatim, with a different prefix. The lesson
     * was paid for once; this is it applied before the second time.
     *
     * ⛑ **hartland, 2026-09-16.** *"Jak se vyvíjela tržba z tržiště v roce 2025?"* — one of the
     * estate's own advertised questions — died at the query door with `'ground:chrono' is not an
     * addressable object or attribute`. The G3 BROAD round asked the matcher about *roce* with no
     * class scoping, the `ground:chrono` trigger row came back, and its ref was written straight
     * into `attribute_ref`. The kernel had meanwhile grounded *2025* perfectly, onto
     * `er.entity.date_dim.cal_date` — so the refusal named chrono while chrono was the one part
     * working, and the trigger that ANCHORED that grounding is what killed the turn.
     *
     * ⚠ The fix belongs HERE, on accept, and not on the request. Scoping the round's
     * `target_classes` is a ruled-out design: a member row carries no class at all, so an
     * allow-list would exclude the very rows that tier exists to reach (contracts §1 addendum,
     * rule 4 — pinned by `RoundPlannerTest`). Hence the asymmetry: a row that POSITIVELY declares
     * a non-attributable class is rejected, and UNSPECIFIED is kept, which leaves members
     * untouched and needs no cooperation from the matcher.
     */
    fun attributable(binding: Binding): Boolean = binding.targetClass !in NEVER_ATTRIBUTABLE

    /**
     * The classes that positively declare themselves non-attributable (§3.4).
     *
     * A set rather than a chain of `!=`, so the list is one thing to read and one thing to extend.
     * UNSPECIFIED is deliberately ABSENT: a member row carries no class at all, and excluding
     * "no class" would exclude the rows the value tier exists to reach — see the asymmetry in the
     * KDoc above.
     */
    private val NEVER_ATTRIBUTABLE =
        setOf(
            TargetClass.TARGET_CLASS_OPERATOR,
            TargetClass.TARGET_CLASS_GROUNDING_TRIGGER,
            TargetClass.TARGET_CLASS_STRING_PREDICATE,
        )

    /** The attribute a value is attributed to: its category — the member index is keyed by it. */
    fun attributeRefOf(match: FuzzyMatch): String =
        if (match.source == FuzzySourceTag.MEMBER) match.category else match.targetRef.ifBlank { match.category }

    private fun refOf(
        match: FuzzyMatch,
        isMember: Boolean,
    ): String =
        when {
            isMember && match.candidateId.isNotBlank() -> "${match.category}#${match.candidateId}"
            match.targetRef.isNotBlank() -> match.targetRef
            else -> match.category
        }

    private fun targetClassOf(
        match: FuzzyMatch,
        isMember: Boolean,
    ): TargetClass =
        when {
            match.targetClass != FuzzyTargetClass.TARGET_CLASS_UNSPECIFIED ->
                TargetClass.forNumber(match.targetClass.number) ?: TargetClass.TARGET_CLASS_UNSPECIFIED
            isMember -> TargetClass.TARGET_CLASS_MEMBER
            else -> TargetClass.TARGET_CLASS_UNSPECIFIED
        }

    private fun sourceOf(source: FuzzySourceTag): SourceTag =
        when (source) {
            FuzzySourceTag.MEMBER -> SourceTag.SOURCE_TAG_DATA
            FuzzySourceTag.DECLARED -> SourceTag.SOURCE_TAG_DECLARED
            FuzzySourceTag.METADATA -> SourceTag.SOURCE_TAG_METADATA
            FuzzySourceTag.LEARNED -> SourceTag.SOURCE_TAG_LEARNED
            FuzzySourceTag.VOCABULARY -> SourceTag.SOURCE_TAG_VOCABULARY
            else -> SourceTag.SOURCE_TAG_UNSPECIFIED
        }
}
