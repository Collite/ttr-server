// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import org.slf4j.LoggerFactory
import org.tatrman.diagnostics.RgDiagnostics
import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.NlpOp
import org.tatrman.nlp.v1.StatusResponse
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.client.GroundingClient
import org.tatrman.resolver.client.NlpClient
import org.tatrman.resolver.model.Reach
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.model.kindsByRef
import org.tatrman.resolver.model.ownersByRef
import org.tatrman.resolver.model.ResolverRegistry
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.token.ResumeOption
import org.tatrman.resolver.token.ResumePayload
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.token.ResumeTokenException
import org.tatrman.resolver.v1.AwaitingClarification
import org.tatrman.resolver.v1.BindingProvenance
import org.tatrman.resolver.v1.Candidate
import org.tatrman.resolver.v1.Capabilities
import org.tatrman.resolver.v1.Domain
import org.tatrman.resolver.v1.GateRequest
import org.tatrman.resolver.v1.GateResponse
import org.tatrman.resolver.v1.EntityBinding
import org.tatrman.resolver.v1.Option
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.resolver.v1.Resolution
import org.tatrman.resolver.v1.Span
import org.tatrman.resolver.v1.Universal

/**
 * The deterministic resolver pipeline (RG-P5): parse → extractUniversal →
 * proposeDomainSpans (anchored, Q-20) → gateSpans (one BatchMatch) → assemble a
 * `Resolution | AwaitingClarification`. ZERO LLM in this module — `NoLlmDependencyTest` guards
 * the classpath.
 *
 * ⚑ **Upstreams are THREE since R1 (ruled 2026-08-28), not two.** This sentence used to read
 * "the only upstreams are nlp and lex-matcher"; the grounding kernel ([GroundingRung]) is the
 * third, and it is OFF by default. The distinction the old wording was really making still
 * holds — none of the three is an LLM client of this module — but a kernel may itself be
 * configured with an LLM fallback, so "no LLM anywhere behind this call" is a deployment
 * property (hartland: `CHRONO_LLM_FALLBACK_ENABLED=false`), not something a classpath test can
 * promise. Stated rather than quietly outgrown.
 *
 * S2 additions: the registry is snapshot-fed ([registry], RS-24) with the caller's
 * per-request `Registry` override winning; a clarification is offered under a
 * signed HMAC resume token and a resume with a matching pin binds at confidence
 * 1.0 with **no re-fuzzy** (RS-26); the capability matrix drives degrade — an
 * unsupported language falls to the fold+fuzzy floor with every binding
 * `degraded=true` + RG-RES-001, and the `capabilities` echo reports what actually
 * backed the resolve (F-T3 honesty, RS-25).
 */
class ResolverPipeline(
    private val nlp: NlpClient,
    private val fuzzy: FuzzyClient,
    private val registry: SnapshotRegistry,
    private val siblings: SiblingCatalog,
    private val tokenCodec: ResumeTokenCodec,
    // The per-language preposition tables the frame-role rules dispatch on (RV-P2.1.T5).
    // Defaulted to the shipped ones so a caller that has no opinion gets working roles;
    // the service passes the estate's, which may replace them (see FrameRolePreps).
    private val preps: FrameRolePreps = FrameRolePreps.shipped(),
    // RV-P2.3 — the `lookup` rung. Injected so a caller can hand it a clock (the budget is
    // wall-clock, and a test that races a real one is a test that flakes) or switch the rung off
    // entirely, which is what an estate with no lexicon to narrow against effectively has.
    private val lookupRounds: LookupRounds = LookupRounds(fuzzy),
    // ✅ R1 — the grounding kernel, and the estate to ask it about. Null client = rung off, which
    // is the default everywhere: it adds an upstream to a hot path, so it is opted into.
    private val grounding: GroundingClient? = null,
    private val defaultPackage: String = "",
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val analyzeOps =
        listOf(NlpOp.TOKENIZE, NlpOp.LEMMATIZE, NlpOp.POS_TAG, NlpOp.DEP_PARSE, NlpOp.NER, NlpOp.DETECT_LANGUAGE)

    /**
     * RV-P2.4 — `resolve.gate:v1`. The re-gate sibling: hypotheses in, gated bindings out.
     *
     * Deliberately NOT a branch of [resolve]: `resolve.bind` stays single-purpose (text → lattice)
     * per Q-13's ruling, and the two share no state because there is none to share — the caller
     * carries the lattice. The estate's declared vocabulary comes from the snapshot registry.
     *
     * ⚑ A per-request `Registry` override is expressible on `Resolve` and NOT on `Gate`: contracts
     * §1 gives `GateRequest` two fields and this list does not own that shape. A caller resolving
     * against an overridden registry and then re-gating gets the estate's snapshot for the second
     * call, which is the conservative direction but worth a ruling if overrides go into real use.
     */
    suspend fun gate(request: GateRequest): GateResponse {
        val current = registry.current()
        return ReGate.run(
            request = request,
            fuzzy = fuzzy,
            entityTypes = current.entityTypes,
            thresholds = current.thresholds,
            snapshotHash = current.snapshotHash,
            maxCandidates = lookupRounds.config.maxCandidates,
            // `hypotheses` is unbounded on the wire, so the fan-out is bounded here — by the same
            // per-round cap the lookup rung gives itself, for the same reason this call already
            // borrows its `maxCandidates`.
            maxConcurrentLookups = lookupRounds.config.maxQueriesPerRound,
        )
    }

    suspend fun resolve(request: ResolveRequest): ResolveResponse =
        when {
            request.hasResume() -> resumeResolve(request)
            request.hasFresh() -> freshResolve(request)
            else -> {
                log.info("empty resolve request conversation_id={}", request.conversationId)
                ResolveResponse
                    .newBuilder()
                    .setResolution(Resolution.getDefaultInstance())
                    .setTraceId(request.conversationId)
                    .setCapabilities(Capabilities.getDefaultInstance())
                    .build()
            }
        }

    // --- fresh path ---------------------------------------------------------

    private suspend fun freshResolve(request: ResolveRequest): ResolveResponse {
        val fresh = request.fresh
        val locale = fresh.locale
        val parse = nlp.analyze(analyzeRequest(fresh.text, locale))
        val status = runCatching { nlp.getStatus() }.getOrNull()
        val assessment = assess(parse, status)

        val resolverRegistry =
            if (request.hasRegistry()) {
                fromProto(
                    request.registry,
                    registry.current().thresholds,
                )
            } else {
                registry.current()
            }

        // LP contracts §2 — scanned once, here, because this is the last place the raw text
        // exists: everything below works from the parse, which carries tokens and not the string.
        val literals = Literals.of(fresh.text, parse)
        // LP §2.4 — a literal is never NER-typed and never grounded. The universal layer is where
        // that has to be said: `"12.5.2024"` in quotes is a string the user wants matched, and a
        // chrono value is precisely what it must NOT become — a date range filter is a different
        // question from a name containing those characters. Dropping the universals here also
        // keeps them out of `GroundingRung`, which grounds what this list holds.
        val universals =
            if (assessment.csNer) {
                UniversalExtraction.extractUniversal(parse).filterNot { literals.overlaps(it.start, it.end) }
            } else {
                emptyList()
            }
        // MH — the slot is stamped HERE, right after proposal, because this is where the parse is
        // in scope: `GateSpans.gate` receives candidates only (architecture A3). Same list, same
        // order; only `DomainSpanCandidate.slot` is filled.
        val candidates =
            SlotHints.stamp(
                parse,
                SpanProposal.proposeDomainSpans(parse, resolverRegistry.entityTypes, literals),
                resolverRegistry.entityTypes.kindsByRef(),
                resolverRegistry.entityTypes.ownersByRef(),
                assessment.language,
                preps,
            )
        // The mention layer is derived BEFORE the batch now (RV-P1.6.T6): a mention nothing in the
        // model binds can still be a grounding trigger, and it can only be asked about in the one
        // BatchMatch this pass makes. The gate reads slots [0, candidates), the trigger annotation
        // reads the trailing ones — one round trip, two questions, kept apart.
        val ungatedMentions = MentionLayer.propose(parse, candidates)
        val triggerSpans = GroundingTriggers.spansOf(candidates, ungatedMentions)
        // LP §3 — and the third question on the same pass: which words say HOW a quoted literal
        // restricts its attribute. Anchored on the LITERAL rather than on the mentions, because
        // span proposal does not reach a participle (see `PredicateTriggers`); empty, and
        // therefore free, for every question with no literal in it.
        val predicateWindows = PredicateTriggers.windowsOf(literals, parse)
        val batchReq =
            GateSpans
                .buildBatchRequest(
                    candidates,
                    locale.ifBlank { null },
                    resolverRegistry.thresholds.maxOptions,
                ).toBuilder()
                .addAllSpans(GroundingTriggers.queries(triggerSpans, resolverRegistry.thresholds.maxOptions))
                .addAllSpans(PredicateTriggers.queries(predicateWindows, resolverRegistry.thresholds.maxOptions))
                .build()
        val batchResp = fuzzy.batchMatch(batchReq)
        val broadPass =
            GateSpans.gate(
                candidates,
                batchResp,
                resolverRegistry.entityTypes,
                resolverRegistry.thresholds,
                siblings,
                resolverRegistry.snapshotHash,
            )
        val triggers =
            GroundingTriggers.collect(
                triggerSpans,
                batchResp,
                offset = candidates.size,
                thresholds = resolverRegistry.thresholds,
                snapshotHash = resolverRegistry.snapshotHash,
            )
        // The predicate slots sit AFTER the grounding ones, so the offset is both of the earlier
        // blocks. Stated as a sum rather than a constant because that is the invariant: each
        // question appends its own slots and reads back from where the previous one ended.
        val predicates =
            PredicateTriggers.collect(
                predicateWindows,
                batchResp,
                offset = candidates.size + triggerSpans.size,
                thresholds = resolverRegistry.thresholds,
            )
        // ✅ R1 — ground the time-typed universals ONCE, before the lattice is assembled, and
        // reuse the result across every re-assembly the lookup loop performs below. Doing it here
        // rather than inside LatticeAssembler keeps that object pure and keeps the RPC count at
        // "once per resolve" instead of "once per narrowing round".
        val groundedSpans =
            grounding
                ?.let {
                    GroundingRung.ground(
                        client = it,
                        universals = universals,
                        questionText = fresh.text,
                        // The caller's package wins; config is the single-estate fallback (D1).
                        pkg = request.context.`package`.ifBlank { defaultPackage },
                        referenceDatetime = request.context.referenceDatetime,
                        locale = locale,
                    )
                }.orEmpty()

        // The lattice (RV-P2.1) is annotation, not outcome: it is emitted the same way whether
        // the gate bound everything or is asking a question, because what the core UNDERSTOOD
        // does not change with what it decided.
        val assemble = { gated: List<GatedSpan>, ungated: List<GatedSpan> ->
            LatticeAssembler.assemble(
                parse = parse,
                gate =
                    GateSpans.outcomeOf(
                        gated,
                        resolverRegistry.entityTypes,
                        resolverRegistry.thresholds,
                        siblings,
                        resolverRegistry.snapshotHash,
                    ),
                ungatedMentions = ungated,
                universals = universals,
                entityTypes = resolverRegistry.entityTypes,
                snapshotHash = resolverRegistry.snapshotHash,
                batch = batchResp,
                lang = assessment.language,
                preps = preps,
                degraded = assessment.degradedFloor,
                triggers = triggers,
                grounded = groundedSpans,
                literals = literals,
                predicates = predicates,
            )
        }

        // RV-P2.3 — the narrowing loop, between the broad pass and emit. It re-enters through the
        // same gate and re-assembles from the same internal model, so an emitted lattice is the
        // same KIND of object whether zero rounds ran or five did. Everything after this line is
        // written against the loop's result and cannot tell the difference.
        val ungatedSpans = ungatedMentions.map { GatedSpan(it, emptyList(), ambiguous = false) }
        val rounds =
            lookupRounds.run(
                lattice = assemble(broadPass.gated, ungatedSpans),
                gated = broadPass.gated,
                ungated = ungatedSpans,
                entityTypes = resolverRegistry.entityTypes,
                thresholds = resolverRegistry.thresholds,
                reassemble = assemble,
            )
        // The door moves with the lattice: a round that bound a span the broad pass missed changes
        // BOTH what the core understood and what it decided, and a caller reading `Resolution` is
        // entitled to the same story as one reading the lattice.
        val outcome =
            GateSpans.outcomeOf(
                rounds.gated,
                resolverRegistry.entityTypes,
                resolverRegistry.thresholds,
                siblings,
                resolverRegistry.snapshotHash,
            )
        val lattice =
            rounds.lattice
                .toBuilder()
                .addAllRungLog(rounds.log)
                .build()

        val builder =
            ResolveResponse
                .newBuilder()
                .setParse(parse)
                .setTraceId(parse.traceId.ifBlank { request.conversationId })
                .setElapsedMs(parse.elapsedMs)
                .setCapabilities(capabilities(assessment))
                .setResolutionState(lattice)

        when (outcome) {
            is Clarify ->
                builder.awaiting = awaitingOf(outcome, request.conversationId, parse.traceId, request.callerSubject)
            is Bound -> builder.resolution = resolutionOf(universals, outcome, parse, assessment.degradedFloor)
        }
        return builder.build()
    }

    // --- resume path (RS-26) ------------------------------------------------

    private fun resumeResolve(request: ResolveRequest): ResolveResponse {
        val resume = request.resume
        // A bad token is RG-RES-002 — refuse over guess; the gRPC layer maps the throw.
        val payload = tokenCodec.verify(resume.token).getOrThrow()
        // Subject binding (RG-P6 review C): the token was signed to a specific OBO
        // subject; a different principal (or an empty-subject caller replaying a
        // user's token) is refused even though the HMAC is valid. Empty==empty is the
        // dev-network path where no identity was required at issue OR resume.
        if (payload.subject != request.callerSubject) {
            throw ResumeTokenException(
                "resume token was issued to a different caller than the one resuming it",
            )
        }
        val option =
            payload.options.firstOrNull { it.id == resume.selectedOptionId }
                ?: throw ResumeTokenException(
                    "selected_option_id '${resume.selectedOptionId}' is not in the signed option set",
                )

        // A signed pin binds at confidence 1.0 with NO re-fuzzy (fuzzy is not called).
        val binding = pinnedBinding(option)
        return ResolveResponse
            .newBuilder()
            .setTraceId(payload.conversationId)
            .setResolution(
                Resolution
                    .newBuilder()
                    .addBindings(binding)
                    .setConfidence(1.0)
                    .setRationale("resumed via signed pin (${option.id})"),
            ).setCapabilities(Capabilities.newBuilder().setFuzzyReady(true))
            .build()
    }

    private fun pinnedBinding(option: ResumeOption): EntityBinding {
        // Prefer the signed entity_type_ref (present for MEMBER options too, RG-P6
        // review F); fall back to the VOCABULARY target's ref prefix for older tokens.
        val entityTypeRef = option.entityTypeRef?.ifBlank { null } ?: option.targetRef?.substringBefore('#') ?: ""
        val domain =
            Domain
                .newBuilder()
                .setEntityTypeRef(entityTypeRef)
                .setRawText(option.label)
                .setResolvedLabel(option.label)
        if (option.resolvedId != null) domain.resolvedId = option.resolvedId
        // review-104 F6 — the attribute a member pin is a value of, carried from the signed option.
        option.memberOf?.takeIf { it.isNotBlank() }?.let { domain.memberOf = it }
        if (option.targetRef !=
            null
        ) {
            domain.addCandidates(
                Candidate
                    .newBuilder()
                    .setTargetRef(option.targetRef)
                    .setScore(1.0)
                    .setResolvedLabel(option.label),
            )
        }
        return EntityBinding
            .newBuilder()
            .setDomain(domain)
            .setProvenance(
                BindingProvenance
                    .newBuilder()
                    .setVocabularySource(if (option.resolvedId != null) "MEMBER" else "VOCABULARY")
                    .setAlgorithm("hmac-pin")
                    .setScore(1.0),
            ).build()
    }

    // --- capability assessment (RS-25) --------------------------------------

    /**
     * What actually backed this resolve, honestly (F-T3). A capability counts as
     * available if the matrix advertises it OR the parse itself carried it. An
     * unsupported language (no dep parse AND no NER either way) is the fold+fuzzy
     * floor — every binding is then degraded.
     */
    private data class Assessment(
        val language: String,
        val csNer: Boolean,
        val depParse: Boolean,
        val degradedFloor: Boolean,
    )

    private fun assess(
        parse: AnalyzeResponse,
        status: StatusResponse?,
    ): Assessment {
        val lang = parse.detectedLanguage.ifBlank { parse.language }
        val caps = status?.capabilitiesList.orEmpty().filter { it.language.equals(lang, ignoreCase = true) }
        val csNer = caps.any { it.op == NlpOp.NER } || parse.entitiesList.isNotEmpty()
        val depParse = caps.any { it.op == NlpOp.DEP_PARSE } || parse.tokensList.any { it.depHead > 0 }
        return Assessment(lang, csNer, depParse, degradedFloor = !csNer && !depParse)
    }

    private fun capabilities(a: Assessment): Capabilities {
        val builder =
            Capabilities
                .newBuilder()
                .setLanguage(a.language)
                .setCsNer(a.csNer)
                .setDepParse(a.depParse)
                .setFuzzyReady(true)
                .setDegraded(a.degradedFloor)
        if (a.degradedFloor) builder.addDegradedReasons(RgDiagnostics.render("RG-RES-001", "span" to "(all)"))
        return builder.build()
    }

    // --- assembly -----------------------------------------------------------

    private fun analyzeRequest(
        text: String,
        locale: String,
    ): AnalyzeRequest =
        AnalyzeRequest
            .newBuilder()
            .setText(text)
            .setLanguage(locale)
            .addAllOps(analyzeOps)
            .build()

    private fun resolutionOf(
        universals: List<UniversalBinding>,
        bound: Bound,
        parse: AnalyzeResponse,
        degraded: Boolean,
    ): Resolution {
        val nerVersions = parse.usedList.filter { it.op.equals("NER", ignoreCase = true) }
        val builder = Resolution.newBuilder().setConfidence(bound.confidence)
        for (u in universals) builder.addBindings(universalBinding(u, nerVersions, degraded))
        for (b in bound.bindings) builder.addBindings(domainBinding(b, parse, degraded))
        builder.rationale =
            "deterministic bind: ${universals.size} universal, ${bound.bindings.size} domain" +
            if (degraded) " (degraded floor)" else ""
        return builder.build()
    }

    private fun universalBinding(
        u: UniversalBinding,
        nerVersions: List<org.tatrman.nlp.v1.EngineVersion>,
        degraded: Boolean,
    ): EntityBinding =
        EntityBinding
            .newBuilder()
            .setSpan(span(u.start, u.end, u.text))
            .setUniversal(
                Universal
                    .newBuilder()
                    .setEntityType(u.entityType)
                    .setRawText(u.rawText)
                    .setNormalizedValue(u.normalizedValue)
                    .setSourceEngine(u.sourceEngine),
            ).setProvenance(
                BindingProvenance
                    .newBuilder()
                    .setVocabularySource("universal:${u.sourceEngine}")
                    .setAlgorithm("ner")
                    .setScore(1.0)
                    .addAllModelVersions(nerVersions),
            ).setDegraded(degraded)
            .build()

    private fun domainBinding(
        b: DomainBinding,
        parse: AnalyzeResponse,
        degraded: Boolean,
    ): EntityBinding {
        val domain =
            Domain
                .newBuilder()
                .setEntityTypeRef(b.entityTypeRef)
                .setRawText(b.rawText)
                .setResolvedLabel(b.resolvedLabel)
        if (b.resolvedId != null) domain.resolvedId = b.resolvedId
        if (b.memberOf.isNotBlank()) domain.memberOf = b.memberOf
        // Sibling-column expansion (Q-20): the value also points at its sibling column.
        for (sibling in b.siblingRefs) {
            domain.addCandidates(
                Candidate
                    .newBuilder()
                    .setTargetRef(sibling)
                    .setScore(b.score)
                    .setResolvedLabel(b.resolvedLabel),
            )
        }
        if (b.targetRef != null && b.siblingRefs.isEmpty()) {
            domain.addCandidates(
                Candidate
                    .newBuilder()
                    .setTargetRef(b.targetRef)
                    .setScore(b.score)
                    .setResolvedLabel(b.resolvedLabel),
            )
        }
        return EntityBinding
            .newBuilder()
            .setSpan(span(b.span.start, b.span.end, b.rawText))
            .setDomain(domain)
            .setProvenance(
                BindingProvenance
                    .newBuilder()
                    .setVocabularySource(b.vocabularySource)
                    .setAlgorithm(b.algorithm)
                    .setScore(b.score)
                    .setSnapshotHash(b.snapshotHash)
                    .addAllModelVersions(parse.usedList),
            ).setDegraded(degraded)
            .build()
    }

    private fun awaitingOf(
        clarify: Clarify,
        conversationId: String,
        parseRef: String,
        subject: String,
    ): AwaitingClarification {
        val builder = AwaitingClarification.newBuilder()
        val signedOptions = mutableListOf<ResumeOption>()
        for (o in clarify.options) {
            val opt = Option.newBuilder().setId(o.id).setLabel(o.label)
            if (o.resolvedId != null) opt.resolvedId = o.resolvedId
            if (o.targetRef != null) opt.targetRef = o.targetRef
            if (o.entityTypeRef.isNotBlank()) opt.entityTypeRef = o.entityTypeRef
            // MH: the species, so the door can word the question by KIND. Not signed into the
            // resume token — it is presentation, and a resume must only be able to pick from the
            // identities that were offered.
            if (o.objectKind.isNotBlank()) opt.objectKind = o.objectKind
            // MH tier M: the vocabulary a member option came from — its attribute since MV-T3.
            if (o.memberOf.isNotBlank()) opt.memberOf = o.memberOf
            opt.span = span(o.spanStart, o.spanEnd, o.spanText)
            builder.addOptions(opt)
            // ...and SIGNED since review-104 F6: with `entityTypeRef` naming the entity, the
            // attribute is what tells two members of one entity apart, so it is identity, not
            // presentation, and a resume must rebuild it rather than trust the caller for it.
            signedOptions +=
                ResumeOption(
                    o.id,
                    o.label,
                    o.targetRef,
                    o.resolvedId,
                    o.entityTypeRef,
                    memberOf = o.memberOf.takeIf { it.isNotBlank() },
                )
        }
        // Sign the EXACT offered set (so a resume can only pick from it, RS-26) AND the
        // OBO subject it was issued to (so a leaked token can't be replayed by another
        // principal, RG-P6 review C).
        val payload =
            ResumePayload(
                conversationId = conversationId,
                parseRef = parseRef,
                options = signedOptions,
                issuedAt = System.currentTimeMillis() / 1000,
                keyId = tokenCodec.activeKeyId,
                subject = subject,
            )
        builder.resumeToken = tokenCodec.sign(payload)
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

    companion object {
        /** Map the caller-supplied `Registry` proto override into the internal model (RS-24). */
        fun fromProto(
            reg: Registry,
            fallback: ResolverThresholds,
        ): ResolverRegistry {
            val entityTypes =
                reg.entityTypesList.map {
                    ResolverEntityType(
                        it.ref,
                        it.categoriesList.toList(),
                        it.anchorsList.toList(),
                        it.objectKind,
                        it.ownerRef,
                        // MH: the override channel never lags the snapshot channel — a caller
                        // that can state a kind must be able to state the reach that qualifies
                        // it, or a fixture could only ever exercise half of the Binder's rules.
                        it.reachedFromList.map { r -> Reach(r.factRef, r.mandatory) },
                        // LP: the same argument again. Both channels carry the mention facet
                        // since P2b, and this one still has to: a caller that could not say which
                        // column is the name could not exercise the verbatim arm without building
                        // an archive first.
                        it.nameAttributeRef,
                        it.codeAttributeRef,
                        it.codeFormat,
                        // MV: and once more — a caller that could not mark a member vocabulary
                        // could not exercise the governed lookup without building an archive.
                        it.memberVocabulary,
                    )
                }
            val thresholds =
                if (reg.hasThresholds()) {
                    // proto3 scalar default 0 is the "unset" sentinel. A caller may only
                    // move a safety threshold in the CONSERVATIVE direction — raising
                    // `bind`/`ambiguity_gap`/`exact` all make the gate MORE likely to
                    // refuse/clarify. Clamping to `maxOf(override, fallback)` closes the
                    // refuse-over-guess hole where a per-request `bind = 0.1` lowered the
                    // 0.5 floor and let near-junk matches bind (RG-P6 review E). `max_options`
                    // is a display cap, not a safety floor, so it takes any positive value.
                    //
                    // ⚑ RV-P2.2 — `strong` is a safety floor by the same argument and would
                    // belong in this clamp, but it has NO wire field to clamp: `Thresholds` is
                    // the caller-facing proto and adding one is a contract change this list
                    // does not own. It therefore always takes the estate's configured value,
                    // which is the conservative outcome (a caller cannot lower the class floor
                    // because a caller cannot reach it at all). Worth an explicit ruling if a
                    // per-request class floor is ever wanted.
                    val t = reg.thresholds
                    ResolverThresholds(
                        bind = if (t.bind > 0) maxOf(t.bind, fallback.bind) else fallback.bind,
                        ambiguityGap =
                            if (t.ambiguityGap >
                                0
                            ) {
                                maxOf(t.ambiguityGap, fallback.ambiguityGap)
                            } else {
                                fallback.ambiguityGap
                            },
                        exact = if (t.exact > 0) maxOf(t.exact, fallback.exact) else fallback.exact,
                        maxOptions = if (t.maxOptions > 0) t.maxOptions else fallback.maxOptions,
                        strong = fallback.strong,
                    )
                } else {
                    fallback
                }
            return ResolverRegistry(entityTypes, reg.localesList.toList(), thresholds, reg.snapshotHash)
        }
    }
}
