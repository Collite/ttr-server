// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.v1.Attribution
import org.tatrman.resolver.v1.Binding
import org.tatrman.resolver.v1.Grounding
import org.tatrman.resolver.v1.LexiconVersions
import org.tatrman.resolver.v1.Mention
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.RungLogEntry
import org.tatrman.resolver.v1.Span
import org.tatrman.resolver.v1.TargetClass
import org.tatrman.resolver.v1.ValueFinding
import org.tatrman.resolver.v1.ValueKind
import org.tatrman.ttr.lexicon.LexiconValidator

/**
 * RV-P2.1.T4 — the annotation lattice (contracts §1), assembled from what the deterministic
 * core actually computed. Nothing here invents evidence: every binding comes from a matcher
 * candidate that survived the gate, every value from a proposed span, every grounding from the
 * universal extraction. Where a concept was missing it was added to the internal model
 * ([DomainSpanCandidate.origin], [GatedSpan], [ResolverEntityType.objectKind]) rather than
 * faked at this boundary.
 *
 * The two layers are RV-2's: **mentions** are content nominal phrases (what the question talks
 * about, bound to model objects and operators), **values** are literals, proper names and
 * grounded spans (what the question restricts BY). A span's layer is decided by which
 * deterministic source proposed it — never by what happened to match, so a mention that binds
 * nothing is still a mention, and that is what makes G1 expressible at all.
 */
object LatticeAssembler {
    /** The only kernel the rung is wired to today; see [GroundingRung]. */
    private const val CHRONO_KERNEL = "chrono"

    fun assemble(
        parse: AnalyzeResponse,
        gate: GateOutcome,
        ungatedMentions: List<GatedSpan>,
        universals: List<UniversalBinding>,
        entityTypes: List<ResolverEntityType>,
        snapshotHash: String,
        batch: BatchMatchResponse,
        lang: String,
        preps: FrameRolePreps,
        degraded: Boolean = false,
        // RV-P1.6.T6 — the `ground:` slices that claimed each span, keyed by (start, end). Empty
        // for an estate that ships no grounding vocabulary, which is every estate until the P3
        // delivery chain lands, and the lattice is then exactly what P2.1 emitted.
        triggers: Map<Pair<Int, Int>, List<Binding>> = emptyMap(),
        // ✅ R1 — what a grounding KERNEL made of each span, keyed the same way `triggers` is.
        // Computed upstream (ResolverPipeline) because it costs an RPC and this object is pure;
        // empty whenever the rung is off, which leaves the lattice exactly as P2.1 emitted it.
        grounded: Map<Pair<Int, Int>, GroundingRung.Grounded> = emptyMap(),
    ): ResolutionState {
        val gatedByLayer =
            gate.gated.groupBy { layerOf(it, hasTrigger = (it.candidate.start to it.candidate.end) in triggers) }
        val mentionSpans =
            (gatedByLayer[Layer.MENTION].orEmpty() + ungatedMentions)
                .sortedWith(compareBy({ it.candidate.start }, { it.candidate.end }))
        val valueSpans = gatedByLayer[Layer.VALUE].orEmpty().sortedWith(compareBy({ it.candidate.start }))

        val mentionBuilders =
            mentionSpans.mapIndexed { i, span ->
                val builder =
                    Mention
                        .newBuilder()
                        .setId("m${i + 1}")
                        .setSpan(span(span.candidate.start, span.candidate.end, span.candidate.text))
                        .setLemma(span.candidate.lemma)
                for (match in span.contenders) {
                    // MH-D3: the equivalents qualify the BINDING, so they ride the winner and
                    // nothing else. A bound span has exactly one contender; an ambiguous span's
                    // contenders carry none, because nothing was proven equal to anything.
                    builder.addBindings(Bindings.of(match, snapshotHash, span.equivalents))
                }
                // LAST, deliberately (RV-42): a grounding trigger is evidence about which KERNEL
                // owns the span, not about which model object the mention is, and the top binding
                // is what frame-role derivation lets speak for the mention.
                builder.addAllBindings(triggers[span.candidate.start to span.candidate.end].orEmpty())
                builder
            }
        // Which mention a literal's scope came from — resolvable only now that ids exist. An
        // UNBOUND mention is not an anchor: it has no categories to lend, and that distinction
        // is what separates a scoped miss (G4) from an unscoped one (G3).
        val mentionIdByHead =
            mentionSpans
                .mapIndexed { i, span -> span to "m${i + 1}" }
                .filter { (span, _) -> span.candidate.headToken >= 0 && span.contenders.isNotEmpty() }
                .associate { (span, id) -> span.candidate.headToken to id }

        val gatedValues =
            valueSpans.map { span ->
                val builder =
                    ValueFinding
                        .newBuilder()
                        .setSpan(span(span.candidate.start, span.candidate.end, span.candidate.text))
                        .setKind(ValueKind.VALUE_KIND_LITERAL)
                mentionIdByHead[span.candidate.anchorHeadToken]?.let { builder.anchorMentionId = it }
                for (match in span.contenders) {
                    val binding = Bindings.of(match, snapshotHash)
                    // The same rule the re-gate applies: an attribution names an attribute, so an
                    // operator or a grounding-trigger row is not one (`Bindings.attributable`).
                    // Both producers are filtered because either can be the one that runs first —
                    // the core pass here, or a later lookup round.
                    if (!Bindings.attributable(binding)) continue
                    builder.addAttributions(
                        Attribution
                            .newBuilder()
                            .setAttributeRef(Bindings.attributeRefOf(match.match))
                            .setBinding(binding),
                    )
                }
                builder
            }
        // Which mention carries a trigger, by the token it heads (RV-P1.6.T6). A separate map from
        // `mentionIdByHead` above and NOT a reuse of it: that one answers "whose categories scoped
        // this lookup?" and therefore admits only bound mentions, while a trigger lends no
        // categories at all — it names a kernel, and an otherwise unbound mention can do that.
        val triggerByHead =
            mentionSpans
                .zip(mentionBuilders)
                .mapNotNull { (span, mention) ->
                    if (span.candidate.headToken < 0) return@mapNotNull null
                    val kind =
                        mention.bindingsList
                            .firstNotNullOfOrNull { GroundingTriggers.kindOf(it.ref).ifBlank { null } }
                            ?: return@mapNotNull null
                    span.candidate.headToken to TriggerAnchor(mention.id, kind)
                }.toMap()

        // A grounded span whose trigger-carrying mention governs it: "the user said *roce*, so the
        // year beside it is chrono's". Recorded on `Grounding.ref` — the field the contract already
        // defines for it — and deliberately NOT on `anchor_mention_id`, whose documented meaning is
        // the categories that scoped an ATTRIBUTION lookup and which Gaps reads to tell G3 from G4.
        // Overloading it would turn every trigger next to an unattributed literal into a method miss.
        val narrowed = mutableListOf<Pair<ValueFinding.Builder, String>>()
        val groundedValues =
            universals.map { universal ->
                val anchor = triggerAnchorOf(universal, parse, triggerByHead)
                val kernelResult = grounded[universal.start to universal.end]
                val grounding =
                    Grounding
                        .newBuilder()
                        // A kernel that answered SPEAKS for the span: `Grounding.kernel` is
                        // documented as "chrono | money | geo | the NER engine that typed it",
                        // and the NER engine is the fallback, not the winner.
                        .setKernel(if (kernelResult != null) CHRONO_KERNEL else universal.sourceEngine)
                        .setKind(universal.entityType.name)
                        // The kernel's interval REPLACES the NER engine's normalization when it
                        // has one. stanza says `2025`; chrono says `2025-01-01/2026-01-01`, which
                        // is the only one of the two a half-open filter can be built from.
                        .setNormalizedValue(
                            kernelResult?.normalizedValue?.ifBlank { null } ?: universal.normalizedValue,
                        )
                val builder =
                    ValueFinding
                        .newBuilder()
                        .setSpan(span(universal.start, universal.end, universal.text))
                        .setKind(ValueKind.VALUE_KIND_GROUNDED)
                // The anchor column, as an attribution. This is the fact the composer reads to
                // turn a grounded value into a filter — and the reason it is an ATTRIBUTION and
                // not a new field is that "this literal could be a value of THAT attribute" is
                // exactly what an attribution already means. No binding rides with it: the value
                // is not a member of the attribute, it is a range over it.
                kernelResult?.anchorAttributeRef?.takeIf { it.isNotBlank() }?.let { ref ->
                    builder.addAttributions(Attribution.newBuilder().setAttributeRef(ref))
                }
                // Only when the two independent statements AGREE: the universal layer says what
                // kind of thing this is, the trigger says which kernel claimed the words beside it.
                if (anchor != null && GroundingTriggers.kernelOf(universal.entityType) == anchor.kind) {
                    grounding.ref = LexiconValidator.GROUND_PREFIX + anchor.kind
                    narrowed += builder to anchor.mentionId
                }
                builder.setGrounding(grounding)
            }
        val valueBuilders =
            (gatedValues + groundedValues)
                .sortedWith(compareBy({ it.span.start }, { it.span.end }))
                .mapIndexed { i, builder -> builder.setId("v${i + 1}") }
        val values = valueBuilders.map { it.build() }

        // Frame roles are a post-binding, pre-emit stage (RV-21): they read the parse AND the
        // bindings, and the anchor relation between a value and its mention is one of their
        // inputs (rule R8), so they cannot run before both layers have ids.
        val anchoring = values.mapNotNull { it.anchorMentionId.ifBlank { null } }.toSet()
        val objectKinds = entityTypes.associate { it.ref to it.objectKind }
        val roles =
            FrameRoles.derive(
                mentionSpans.zip(mentionBuilders).map { (span, mention) ->
                    FrameRoles.Input(
                        id = mention.id,
                        charStart = span.candidate.start,
                        headToken = span.candidate.headToken,
                        // The top contender speaks for the mention: a lattice may hold several
                        // candidates, but a role is a structural fact about the span, and the
                        // strongest candidate is the one the ask/compose path will act on.
                        targetClass =
                            mention.bindingsList.firstOrNull()?.targetClass
                                ?: TargetClass.TARGET_CLASS_UNSPECIFIED,
                        objectKind = objectKindOf(span, mention, objectKinds),
                        anchorsValue = mention.id in anchoring,
                    )
                },
                parse,
                lang,
                preps,
            )
        val mentions = mentionBuilders.map { it.addAllFrameRoles(roles[it.id].orEmpty()).build() }

        // Gaps are computed last, from the finished annotation: what a gap IS depends on the
        // roles (is it load-bearing?) and on the anchor (was there a scope to miss?).
        val gaps =
            Gaps.assess(
                mentions = mentions,
                values = values,
                ambiguousSpans =
                    gate.gated
                        .filter { it.ambiguous }
                        .map { it.candidate.start to it.candidate.end }
                        .toSet(),
                parse = parse,
                degraded = degraded,
            )

        val builder =
            ResolutionState
                .newBuilder()
                .setParse(parse)
                .addAllMentions(mentions)
                .addAllValues(values)
                .addAllGaps(gaps)
                .setLexiconVersions(lexiconVersions(batch))
        builder.addRungLog(
            RungLogEntry
                .newBuilder()
                .setRung(CORE_RUNG)
                .setAction("annotate")
                .setBindingsAdded(mentions.sumOf { it.bindingsCount } + values.sumOf { it.attributionsCount })
                .setGapsOpen(gaps.size),
        )
        // The narrowing itself (RV-42, GI-14 extended to grounding): one entry per trigger-carrying
        // mention, naming the values its kernel now owns. This is the audit trail for a decision
        // the core makes and someone else acts on — a caller reads "chrono, on m4, for v2" instead
        // of offering the whole question to all three kernels. A mention with no value under it
        // still gets an entry: the span is chrono's whether or not the universal layer typed
        // anything beside it. No counters here — `bindings_added`/`gaps_open` describe the PASS,
        // and the pass has exactly one `annotate` entry.
        val narrowings = narrowed.groupBy({ it.second }, { it.first.id })
        for (mention in mentions) {
            val kind = mention.bindingsList.firstNotNullOfOrNull { GroundingTriggers.kindOf(it.ref).ifBlank { null } }
            if (kind == null) continue
            builder.addRungLog(
                RungLogEntry
                    .newBuilder()
                    .setRung(CORE_RUNG)
                    .setAction(GROUND_NARROW_ACTION)
                    .addMentionIds(mention.id)
                    .addAllValueIds(narrowings[mention.id].orEmpty()),
            )
        }
        return builder.build()
    }

    /** The rung name the core writes for its own deterministic pass (contracts §3 vocabulary). */
    const val CORE_RUNG: String = "core"

    /** The action a grounding narrowing is logged under (RV-42) — see the emit site above. */
    const val GROUND_NARROW_ACTION: String = "ground-narrow"

    /** A trigger-carrying mention, as the value layer needs to see it. */
    private data class TriggerAnchor(
        val mentionId: String,
        val kind: String,
    )

    /**
     * The trigger-carrying mention that GOVERNS a grounded span, via the dep parse.
     *
     * Syntax, not proximity: *"v roce 2025"* attaches `2025` to `roce`, and adjacency would just as
     * happily attach it to whatever the next question puts next to it. Without a dep parse there is
     * no government relation to read, and the honest answer is that nothing was narrowed.
     */
    private fun triggerAnchorOf(
        universal: UniversalBinding,
        parse: AnalyzeResponse,
        triggerByHead: Map<Int, TriggerAnchor>,
    ): TriggerAnchor? {
        if (triggerByHead.isEmpty()) return null
        val tokens = parse.tokensList
        return tokens.indices
            .asSequence()
            .filter { tokens[it].charStart >= universal.start && tokens[it].charEnd <= universal.end }
            .mapNotNull { triggerByHead[tokens[it].depHead - 1] }
            .firstOrNull()
    }

    /**
     * What the mention's target IS in the model. The binding's own ref answers it for a model
     * object; a member ref (`<attribute>#<id>`) is not an entity ref, so the span's gated entity
     * — the type its lookup was scoped to — answers for it. No ref STRING is interpreted either
     * way: both are lookups into what the registry declared.
     */
    private fun objectKindOf(
        span: GatedSpan,
        mention: Mention.Builder,
        objectKinds: Map<String, String>,
    ): String =
        mention.bindingsList
            .firstNotNullOfOrNull { objectKinds[it.ref]?.ifBlank { null } }
            ?: span.candidate.gatedEntityRefs.firstNotNullOfOrNull { objectKinds[it]?.ifBlank { null } }
            ?: ""

    private enum class Layer { MENTION, VALUE, DROPPED }

    private fun layerOf(
        span: GatedSpan,
        hasTrigger: Boolean,
    ): Layer =
        when (span.candidate.origin) {
            DomainSpanCandidate.Origin.ANCHOR_PHRASE -> Layer.MENTION
            DomainSpanCandidate.Origin.GOVERNED_VALUE,
            DomainSpanCandidate.Origin.OPEN_VALUE,
            DomainSpanCandidate.Origin.PROPER_NOUN,
            DomainSpanCandidate.Origin.NER_ENTITY,
            DomainSpanCandidate.Origin.LITERAL,
            -> Layer.VALUE
            // The parse-less n-gram floor guesses spans; a floor guess that matched is worth
            // reporting, one that did not is noise, and the honest record of the whole situation
            // is the G5 degrade gap rather than a mention layer built on no syntax.
            //
            // A TRIGGER counts as having matched, and the p1-6 review is why this reads
            // `contenders OR trigger` rather than `contenders`: [GroundingTriggers.MENTION_ORIGINS]
            // includes the floor deliberately — a trigger hit is a lexicon fact, not a syntactic
            // guess — but triggers are merged in below, AFTER this split, so the query went out,
            // the answer came back, and the span was dropped before it could carry it. Grounding
            // narrowing was switched off for exactly the degraded estates the inclusion exists to
            // help, and nothing failed, because the trigger tests stopped at "was it asked?".
            DomainSpanCandidate.Origin.NGRAM_FLOOR ->
                if (span.contenders.isEmpty() && !hasTrigger) Layer.DROPPED else Layer.MENTION
        }

    private fun lexiconVersions(batch: BatchMatchResponse): LexiconVersions {
        val layers =
            batch.resultsList
                .firstOrNull { it.hasLayerVersions() }
                ?.layerVersions
                ?: return LexiconVersions.getDefaultInstance()
        val builder =
            LexiconVersions
                .newBuilder()
                .setLexiconArtifactHash(layers.lexiconArtifactHash)
                .putAllMemberIndexVersions(layers.memberIndexVersionsMap)
        if (layers.hasOverlayVersion()) builder.overlayVersion = layers.overlayVersion
        return builder.build()
    }

    private fun span(
        start: Int,
        end: Int,
        text: String,
    ): Span =
        Span
            .newBuilder()
            .setStart(start)
            .setEnd(end)
            .setText(text)
            .build()
}
