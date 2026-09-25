// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.fuzzy.v1.SpanQuery
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.model.kindsByRef
import org.tatrman.resolver.model.memberEntityByCategory
import org.tatrman.resolver.model.ownersByRef
import org.tatrman.resolver.model.reachByRef

/**
 * gateSpans (RG-P5.S1.T4) — the heart. All proposed spans go out in ONE
 * `BatchMatch` (never per-span RPCs — B-T1's point); the response comes back
 * positional to the request; this turns it into bindings or a clarification.
 *
 * **RV-P2.2 moved the selection rule out of this file.** What used to live here — a bind floor, an
 * exact-dominance special case, a tie band over the whole contender field — is now [Binder], and
 * the three of them turned out to be one rule badly separated: RV-14's class order. This object
 * kept everything that is genuinely about *spans* (build one batch, read it positionally, expand
 * siblings, dedupe across spans, render options) and delegates the one question "which of these
 * candidates, if any" to the binder, which is the same call the P2.3 lookup rounds and the P2.4
 * re-gate make. There is deliberately no second selection rule in this service.
 *
 * What remains here, unchanged from RG:
 *  - the same resolved id reached via two spans dedupes to one binding;
 *  - a MEMBER value on a KOD/NAZEV column also points at its sibling column
 *    (Q-20 sibling-column expansion — a catalog lookup);
 *  - a span's clarification options are capped at [ResolverThresholds.maxOptions] (20)
 *    independently, so a second ambiguous span can never be dropped by a global truncation.
 */
object GateSpans {
    /** Build the single `BatchMatch` request: one `SpanQuery` per candidate, positional. */
    fun buildBatchRequest(
        candidates: List<DomainSpanCandidate>,
        locale: String?,
        perSpanLimit: Int,
    ): BatchMatchRequest {
        val builder = BatchMatchRequest.newBuilder()
        for (c in candidates) {
            builder.addSpans(
                SpanQuery
                    .newBuilder()
                    .setQuery(c.text)
                    .addAllCategories(c.categories)
                    .setLimit(perSpanLimit)
                    .build(),
            )
        }
        if (!locale.isNullOrBlank()) builder.locale = locale
        return builder.build()
    }

    fun gate(
        candidates: List<DomainSpanCandidate>,
        response: BatchMatchResponse,
        entityTypes: List<ResolverEntityType>,
        thresholds: ResolverThresholds,
        siblings: SiblingCatalog,
        snapshotHash: String,
    ): GateOutcome =
        outcomeOf(
            // MS-P3·S2 — one owners map per gate call, shared by every span (contracts §8.3).
            // MH: two more, built the same way and in the same place, so the three producers that
            // gate cannot end up asking the registry three slightly different questions.
            run {
                val owners = entityTypes.ownersByRef()
                val kinds = entityTypes.kindsByRef()
                val reach = entityTypes.reachByRef()
                // MV §5.3 — category → the ENTITY a member row belongs to. The same map
                // `entityRefOf` reads below, so the Binder and the door name one owner.
                val memberOwners = entityTypes.memberEntityByCategory()
                candidates
                    .mapIndexed { i, cand ->
                        // The ONE decision, made in the one place that makes it (RV-P2.2). Note what is NOT
                        // filtered before the call: the bind floor is the binder's too, because a candidate
                        // the gate refused is still something the rung log should be able to name.
                        val verdict =
                            Binder.gate(
                                response.resultsList
                                    .getOrNull(i)
                                    ?.matchesList
                                    .orEmpty(),
                                cand,
                                thresholds,
                                owners,
                                kinds,
                                reach,
                                memberOwners,
                            )
                        GatedSpan(
                            cand,
                            verdict.admitted,
                            ambiguous = verdict is Binder.Ambiguous,
                            // MH-D3: only a Bind can carry equivalents — an ambiguous span proved
                            // nothing equal, which is precisely why it is asking.
                            equivalents = (verdict as? Binder.Bind)?.equivalents.orEmpty(),
                        )
                    }.let(::resolveOpenSiblings)
            },
            entityTypes,
            thresholds,
            siblings,
            snapshotHash,
        )

    /**
     * The door's answer, derived from the gated spans alone (RV-P2.3).
     *
     * Split out of [gate] because a lookup round changes what the gated spans say and the DOOR has
     * to move with them: a caller reading `Resolution.bindings` and a caller reading the lattice
     * are entitled to the same story, and before this the outcome was welded to the one BatchMatch
     * that happened to come first. Pure over its inputs, so the loop can re-derive it as often as
     * it likes.
     */
    fun outcomeOf(
        gated: List<GatedSpan>,
        entityTypes: List<ResolverEntityType>,
        thresholds: ResolverThresholds,
        siblings: SiblingCatalog,
        snapshotHash: String,
    ): GateOutcome {
        val bindings = mutableListOf<DomainBinding>()
        val options = mutableListOf<ClarificationOption>()
        val memberEntities = entityTypes.memberEntityByCategory()

        for (span in gated) {
            if (span.contenders.isEmpty()) continue
            if (span.ambiguous) {
                // instance ambiguity — offer the distinct contenders, don't bind. Each option is
                // attributed to THIS span and this span's options are capped independently
                // (RG-P6 review M).
                span.contenders
                    .take(thresholds.maxOptions)
                    .forEach { options += toOption(it.match, span.candidate, entityTypes, memberEntities) }
            } else {
                bindings +=
                    toBinding(
                        span.candidate,
                        span.contenders.first().match,
                        entityTypes,
                        memberEntities,
                        siblings,
                        snapshotHash,
                    )
            }
        }

        // NOTE: no global re-truncation here — each span's options are already capped
        // at maxOptions above; a flat `options.take(maxOptions)` would drop later
        // spans wholesale (RG-P6 review M). Full multi-span RESUME (returning the
        // already-bound spans alongside a pin) remains a tracked design item.
        if (options.isNotEmpty()) return Clarify(options, gated)

        val deduped = dedupeByIdentity(bindings)
        return Bound(deduped, confidence = deduped.minOfOrNull { it.score } ?: 0.0, gated = gated)
    }

    // --- helpers ------------------------------------------------------------

    /**
     * ⚑ A-MH-1b (MH tier M) — one span, one answer: collapse a governed value and its OPEN sibling.
     *
     * `SpanProposal` proposes a governed value twice (`GOVERNED_VALUE` gated to the anchor's
     * owners, `OPEN_VALUE` gated to everything) because it cannot know which lookup will find
     * anything — that is a fact about the data, and both questions ride the one batch. Here the
     * ANSWERS are in, so the pair collapses to the one that spoke, and everything downstream —
     * `outcomeOf`, the lookup rounds, the lattice — sees exactly one span per char range, as it
     * always has. Without this the sibling reached the lattice as a second value span and a
     * second G3 gap over the same characters.
     *
     * The governed reading has precedence: it is the one the SENTENCE scoped. The open sibling
     * speaks only where the governor's own vocabulary had nothing to say, which is the case M2
     * exists for (`sales in TN` — a fact holds no members) and the case that used to become a
     * silent gap. A governed lookup that is itself ambiguous keeps its span: a tie inside the
     * governor's scope is a real question, and widening it would not make it easier to answer.
     *
     * Paired strictly by origin, so no other candidate on any other path is affected.
     */
    private fun resolveOpenSiblings(gated: List<GatedSpan>): List<GatedSpan> {
        if (gated.none { it.candidate.origin == DomainSpanCandidate.Origin.OPEN_VALUE }) return gated

        fun spanOf(g: GatedSpan) = g.candidate.start to g.candidate.end

        fun spansWith(origin: DomainSpanCandidate.Origin) =
            gated.filter { it.candidate.origin == origin && it.contenders.isNotEmpty() }.map(::spanOf).toSet()

        val governedAnswered = spansWith(DomainSpanCandidate.Origin.GOVERNED_VALUE)
        val openAnswered = spansWith(DomainSpanCandidate.Origin.OPEN_VALUE)

        return gated.filter { g ->
            when (g.candidate.origin) {
                // An open sibling that found nothing is not a reading, it is a duplicate of the
                // span the governed candidate already carries — keeping it produced a second
                // value span and a second G3 gap over the same characters (caught by
                // `ms-shared-anchor-cs`, whose governed value matches nothing either way).
                DomainSpanCandidate.Origin.OPEN_VALUE ->
                    g.contenders.isNotEmpty() && spanOf(g) !in governedAnswered
                DomainSpanCandidate.Origin.GOVERNED_VALUE ->
                    g.contenders.isNotEmpty() || spanOf(g) !in openAnswered
                else -> true
            }
        }
    }

    /**
     * The declared type whose vocabulary a match's category is, or the category itself. For a
     * member row that is its ATTRIBUTE (MV §1: a category is exactly one attribute ref) — what
     * `memberOf` names.
     */
    private fun vocabularyRefOf(
        m: FuzzyMatch,
        entityTypes: List<ResolverEntityType>,
    ): String = entityTypes.firstOrNull { m.category in it.categories }?.ref ?: m.category

    /**
     * The entity a match is about. MV (member-vocabulary contracts §5.3): for a MEMBER row, the
     * ENTITY that owns its vocabulary ([memberEntities], the map the Binder's tier-M governance
     * reads too) — a PK is a row of an entity, and the entity is what a resumed pin rebuilds its
     * Domain from. For a declared row, the type whose vocabulary it came from, as always.
     *
     * Before MV this returned the member's ATTRIBUTE on every archive-fed estate, because the
     * archive projects each vocabulary as its own type — which is why MH §7.5 had to spell the
     * governor rule as `entityOf(owner(m))`. That deviation is now simply the rule (A-MH-2).
     */
    private fun entityRefOf(
        m: FuzzyMatch,
        entityTypes: List<ResolverEntityType>,
        memberEntities: Map<String, String>,
    ): String =
        if (m.source == SourceTag.MEMBER) {
            memberEntities[m.category] ?: m.category
        } else {
            vocabularyRefOf(m, entityTypes)
        }

    private fun toBinding(
        cand: DomainSpanCandidate,
        top: FuzzyMatch,
        entityTypes: List<ResolverEntityType>,
        memberEntities: Map<String, String>,
        siblings: SiblingCatalog,
        snapshotHash: String,
    ): DomainBinding {
        val isMember = top.source == SourceTag.MEMBER
        val entityRef = entityRefOf(top, entityTypes, memberEntities)
        return DomainBinding(
            span = cand,
            entityTypeRef = entityRef,
            rawText = cand.text,
            vocabularySource = top.source.name,
            resolvedId = if (isMember) top.candidateId else null,
            resolvedLabel = top.candidate,
            targetRef = if (!isMember && top.targetRef.isNotBlank()) top.targetRef else null,
            siblingRefs = siblings[top.category].orEmpty(),
            score = top.score,
            algorithm = top.provenance.method.ifBlank { "TATRMAN" },
            snapshotHash = snapshotHash,
        )
    }

    private fun toOption(
        m: FuzzyMatch,
        cand: DomainSpanCandidate,
        entityTypes: List<ResolverEntityType>,
        memberEntities: Map<String, String>,
    ): ClarificationOption {
        val isMember = m.source == SourceTag.MEMBER
        return ClarificationOption(
            id = Binder.identityKey(m),
            label = m.candidate,
            resolvedId = if (isMember) m.candidateId else null,
            targetRef = if (!isMember && m.targetRef.isNotBlank()) m.targetRef else null,
            entityTypeRef = entityRefOf(m, entityTypes, memberEntities),
            spanStart = cand.start,
            spanEnd = cand.end,
            spanText = cand.text,
            // MH: the option's SPECIES, so a G2 can be worded as "the stores (a dimension) or the
            // Stores channel (sales)?" instead of two labels a user cannot tell apart. Blank for
            // a MEMBER option and for any ref the archive declares nothing about.
            //
            // The `isMember` guard is the same one its two neighbours above have, and it is
            // structural rather than defensive (review-087 F6): a member is a DATA ROW, and a
            // kind is a claim about a declared object. A member row that happens to carry its
            // owner's `target_ref` would otherwise be labelled with the owner's species.
            objectKind =
                if (isMember) "" else entityTypes.firstOrNull { it.ref == m.targetRef }?.objectKind.orEmpty(),
            // MH tier M — the mirror of the line above, and the same guard: a member is a data
            // row and its owner is the only thing that names it; a vocabulary row names itself.
            //
            // MV: the VOCABULARY's ref — the attribute — while `entityTypeRef` above names its
            // entity. Two attributes of one entity can hold the same value (a billing and a
            // shipping state), and then only the attribute tells the two options apart
            // (resolver.proto `member_of`: "the attribute (or entity)").
            memberOf = if (isMember) vocabularyRefOf(m, entityTypes) else "",
        )
    }

    /** Same resolved id (MEMBER) or same target_ref (VOCABULARY) → one binding (highest score). */
    private fun dedupeByIdentity(bindings: List<DomainBinding>): List<DomainBinding> {
        val best = LinkedHashMap<String, DomainBinding>()
        for (b in bindings) {
            val key =
                b.resolvedId?.let { "M:$it" } ?: b.targetRef?.let { "V:$it" } ?: "S:${b.entityTypeRef}:${b.rawText}"
            val existing = best[key]
            if (existing == null || b.score > existing.score) best[key] = b
        }
        return best.values.toList()
    }
}
