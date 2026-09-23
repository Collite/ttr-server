// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.api

import io.grpc.Status
import io.grpc.StatusException
import org.tatrman.fuzzy.core.AlgorithmType
import org.tatrman.fuzzy.core.CascadeStep
import org.tatrman.fuzzy.core.FuzzyMatchResult
import org.tatrman.fuzzy.core.FuzzyMatcher
import org.tatrman.fuzzy.core.LayerVersions
import org.tatrman.fuzzy.core.LookupQuery
import org.tatrman.fuzzy.core.MatchMethod
import org.tatrman.fuzzy.core.SourceTag as CoreSourceTag
import org.tatrman.fuzzy.core.TargetClass as CoreTargetClass
import org.tatrman.fuzzy.core.SpanQuery
import org.tatrman.fuzzy.core.StringRepository
import org.tatrman.fuzzy.core.cascadeFrom
import org.tatrman.fuzzy.telemetry.FuzzyTelemetry
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.CategoryStatus
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.FuzzyServiceGrpcKt
import org.tatrman.fuzzy.v1.FuzzyStatusRequest
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.fuzzy.v1.LoaderWarning
import org.tatrman.fuzzy.v1.LookupRequest
import org.tatrman.fuzzy.v1.LookupResponse
import org.tatrman.fuzzy.v1.MatchRequest
import org.tatrman.fuzzy.v1.Provenance as ProtoProvenance
import org.tatrman.fuzzy.v1.TokenHit as ProtoTokenHit
import org.tatrman.fuzzy.v1.LayerVersions as ProtoLayerVersions
import org.tatrman.fuzzy.v1.SourceTag as ProtoSourceTag
import org.tatrman.fuzzy.v1.TargetClass as ProtoTargetClass
import org.slf4j.LoggerFactory

class GrpcService(
    private val fuzzyMatcher: FuzzyMatcher,
    private val repository: StringRepository,
    private val telemetry: FuzzyTelemetry? = null,
) : FuzzyServiceGrpcKt.FuzzyServiceCoroutineImplBase() {
    private val logger = LoggerFactory.getLogger(GrpcService::class.java)

    override suspend fun match(request: MatchRequest): FuzzyMatchResponse {
        try {
            val steps =
                cascadeFrom(
                    request.algorithmsList.map { CascadeStep(AlgorithmType.fromString(it.algorithm), it.minScore) },
                    if (request.hasAlgorithm()) request.algorithm else null,
                )
            val outcome =
                fuzzyMatcher.matchCascade(
                    query = request.query,
                    category = if (request.hasCategory()) request.category else null,
                    steps = steps,
                    limit = if (request.limit > 0) request.limit else 10,
                )
            return buildResponse(outcome.matches, outcome.matchedAlgorithm?.name ?: "", repository.vocabularyVersion())
        } catch (e: Exception) {
            logger.error("Error processing gRPC match request", e)
            return FuzzyMatchResponse
                .newBuilder()
                .setIsError(true)
                .setError(e.message ?: "Unknown gRPC error")
                .setLayerVersions(repository.layerVersions().toProto())
                .build()
        }
    }

    override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse =
        try {
            val spans = request.spansList.map { SpanQuery(it.query, it.categoriesList.toList(), it.limit) }
            val batch = fuzzyMatcher.batchMatch(spans)
            val results =
                batch.results.map { span ->
                    buildResponse(span.matches, span.matchedAlgorithm?.name ?: "", batch.vocabularyVersion)
                }
            BatchMatchResponse.newBuilder().addAllResults(results).build()
        } catch (e: Exception) {
            logger.error("Error processing gRPC batchMatch request", e)
            // Whole-call failure still returns a shaped response (empty results).
            BatchMatchResponse.newBuilder().build()
        }

    override suspend fun getStatus(request: FuzzyStatusRequest): FuzzyStatusResponse {
        val builder =
            FuzzyStatusResponse
                .newBuilder()
                .setReady(repository.isCatalogReady())
                .setVocabularyVersion(repository.vocabularyVersion())
                .setLayerVersions(repository.layerVersions().toProto())
                .setEngineVersion(fuzzyMatcher.engineVersion)
        repository.categoryStatuses().forEach { s ->
            builder.addCategories(
                CategoryStatus
                    .newBuilder()
                    .setCategory(s.category)
                    .setSource(
                        if (s.source ==
                            CoreSourceTag.VOCABULARY
                        ) {
                            ProtoSourceTag.VOCABULARY
                        } else {
                            ProtoSourceTag.MEMBER
                        },
                    ).setSize(s.size)
                    .setLoadedAtEpochMs(s.loadedAtEpochMs)
                    .build(),
            )
        }
        repository.loaderWarnings().forEach { w ->
            builder.addWarnings(
                LoaderWarning
                    .newBuilder()
                    .setCode(w.code)
                    .setCategory(w.category)
                    .setMessage(w.message)
                    .build(),
            )
        }
        return builder.build()
    }

    /**
     * RV-P1.4 T5 — the lookup rung's call (RV-33). One term, scoped by category and/or target
     * class, with an optional method override; deterministic, no cascade.
     *
     * An unparseable `method_override` is an **error**, not a silent fallback: the caller asked for
     * a specific precision, and quietly using a different one would return plausible candidates
     * under a rule nobody chose. That is the opposite of the loader's degrade-never-fail posture,
     * and correctly so — a broken archive is the estate's problem to survive, a malformed request
     * is the caller's to fix.
     *
     * Unlike [match], failure is signalled by **gRPC status**, not by an in-band `is_error` flag.
     * That is deliberate for a surface being frozen: `INVALID_ARGUMENT` tells a caller its request
     * is wrong and retrying will not help, which `is_error` + a string cannot. The status has to be
     * set explicitly — grpc-kotlin maps any other exception to `UNKNOWN`, which is indistinguishable
     * from a server fault.
     */
    override suspend fun lookup(request: LookupRequest): LookupResponse {
        val override =
            if (request.hasMethodOverride()) {
                MatchMethod.parse(request.methodOverride)
                    ?: throw StatusException(
                        Status.INVALID_ARGUMENT.withDescription(
                            "Unrecognised method_override '${request.methodOverride}' " +
                                "(expected EXACT | TOKENS | TYPOS(n))",
                        ),
                    )
            } else {
                null
            }

        val result =
            try {
                fuzzyMatcher.lookup(
                    LookupQuery(
                        term = request.term,
                        categories = request.categoriesList.toList(),
                        targetClasses = request.targetClassesList.mapNotNull { it.toCore() }.toSet(),
                        methodOverride = override,
                        maxCandidates = request.maxCandidates,
                    ),
                )
            } catch (e: Exception) {
                // A fault on our side, told apart from the caller's malformed request above.
                logger.error("Lookup failed for term '{}'", request.term, e)
                throw StatusException(Status.INTERNAL.withDescription(e.message ?: "Lookup failed").withCause(e))
            }

        val b =
            LookupResponse
                .newBuilder()
                .addAllCandidates(result.candidates.map { it.toProto() })
                .setLayerVersions(repository.layerVersions().toProto())
                .setVocabularyVersion(repository.vocabularyVersion())
                .addAllUnknownCategories(result.unknownCategories)
        if (request.hasMethodOverride()) b.setAppliedMethodOverride(request.methodOverride)
        return b.build()
    }

    /** UNSPECIFIED means "no class", which as a *filter* means "do not filter" — so it drops out. */
    private fun ProtoTargetClass.toCore(): CoreTargetClass? =
        when (this) {
            ProtoTargetClass.TARGET_CLASS_MODEL_OBJECT -> CoreTargetClass.MODEL_OBJECT
            ProtoTargetClass.TARGET_CLASS_MEMBER -> CoreTargetClass.MEMBER
            ProtoTargetClass.TARGET_CLASS_OPERATOR -> CoreTargetClass.OPERATOR
            ProtoTargetClass.TARGET_CLASS_GROUNDING_TRIGGER -> CoreTargetClass.GROUNDING_TRIGGER
            ProtoTargetClass.TARGET_CLASS_UNSPECIFIED, ProtoTargetClass.UNRECOGNIZED -> null
        }

    private fun CoreTargetClass.toProto(): ProtoTargetClass =
        when (this) {
            CoreTargetClass.MODEL_OBJECT -> ProtoTargetClass.TARGET_CLASS_MODEL_OBJECT
            CoreTargetClass.MEMBER -> ProtoTargetClass.TARGET_CLASS_MEMBER
            CoreTargetClass.OPERATOR -> ProtoTargetClass.TARGET_CLASS_OPERATOR
            CoreTargetClass.GROUNDING_TRIGGER -> ProtoTargetClass.TARGET_CLASS_GROUNDING_TRIGGER
        }

    private fun buildResponse(
        matches: List<FuzzyMatchResult>,
        matchedAlgorithm: String,
        vocabularyVersion: String,
    ): FuzzyMatchResponse =
        FuzzyMatchResponse
            .newBuilder()
            .addAllMatches(matches.map { it.toProto() })
            .setMatchedAlgorithm(matchedAlgorithm)
            .setVocabularyVersion(vocabularyVersion)
            // RV-39 — on EVERY response, including the error-free empty one: a caller that got no
            // candidates still needs to know which layers it got none from.
            .setLayerVersions(repository.layerVersions().toProto())
            .build()

    /**
     * RV-39 tuple → wire. `overlayVersion` is left UNSET when null rather than written as `""` —
     * the field is `optional` precisely so "no overlay exists" is expressible.
     */
    private fun LayerVersions.toProto(): ProtoLayerVersions {
        val b =
            ProtoLayerVersions
                .newBuilder()
                .setLexiconArtifactHash(lexiconArtifactHash)
                .putAllMemberIndexVersions(memberIndexVersions)
        overlayVersion?.let { b.setOverlayVersion(it) }
        return b.build()
    }

    private fun FuzzyMatchResult.toProto(): FuzzyMatch {
        val b =
            FuzzyMatch
                .newBuilder()
                .setCandidateId(candidateId)
                .setCandidate(candidate)
                .setScore(score)
                .setCategory(category)
                .setSource(
                    when (source) {
                        CoreSourceTag.MEMBER -> ProtoSourceTag.MEMBER
                        CoreSourceTag.VOCABULARY -> ProtoSourceTag.VOCABULARY
                        CoreSourceTag.DECLARED -> ProtoSourceTag.DECLARED
                        CoreSourceTag.METADATA -> ProtoSourceTag.METADATA
                        CoreSourceTag.LEARNED -> ProtoSourceTag.LEARNED
                    },
                ).setProvenance(
                    ProtoProvenance
                        .newBuilder()
                        .setProducer(provenance.producer)
                        .setMethod(provenance.method)
                        .setRawScore(provenance.rawScore)
                        // RV-44 — the winning (norm, algorithm, distance), all three left UNSET
                        // unless a declared profile scored the row.
                        .also { pb ->
                            provenance.norm?.let { pb.setNorm(it) }
                            provenance.algorithm?.let { pb.setAlgorithm(it) }
                            provenance.distance?.let { pb.setDistance(it) }
                            // LP-P0 — v2 provenance; nothing is written for a v1 row.
                            provenance.tokenHits.forEach { h ->
                                pb.addTokenHits(
                                    ProtoTokenHit
                                        .newBuilder()
                                        .setQueryToken(h.queryToken)
                                        .setCandidateToken(h.candidateToken)
                                        .setKind(h.kind)
                                        .setDistance(h.distance)
                                        .setQueryPos(h.queryPos)
                                        .setCandidatePos(h.candidatePos),
                                )
                            }
                            provenance.coverage?.let { pb.setCoverage(it) }
                        }.build(),
                )
        targetRef?.let { b.setTargetRef(it) }
        matchMethod?.let { b.setMatchMethod(it) }
        // RV-32 — both left UNSET when null. `auto_bindable` especially: proto3's default for an
        // unset bool is `false`, so writing the null case would tell every caller "do not bind
        // this", which is the opposite of "no decision applies".
        uniquenessMargin?.let { b.setUniquenessMargin(it) }
        autoBindable?.let { b.setAutoBindable(it) }
        targetClass?.let { b.setTargetClass(it.toProto()) }
        return b.build()
    }
}
