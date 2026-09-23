// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.grpc

import com.google.protobuf.kotlin.toByteString
import org.tatrman.dispatch.v1.DispatchRequest
import org.tatrman.meta.v1.OverallStatus
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.LimitOffsetNode
import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.Warning
import org.tatrman.query.v1.CacheStatus
import org.tatrman.query.v1.CompileResponse
import org.tatrman.query.v1.GetStatusRequest
import org.tatrman.query.v1.GetStatusResponse
import org.tatrman.query.v1.QueryServiceGrpcKt
import org.tatrman.query.v1.RowWindow
import org.tatrman.query.v1.RunRequest
import org.tatrman.translate.v1.DetectSchemaRequest
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.ParseRequest
import org.tatrman.translate.v1.ParseResponse
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.SchemaDecision
import org.tatrman.translate.v1.TranslateRequest
import org.tatrman.translate.v1.TranslateResponse
import org.tatrman.validate.v1.SecurityRuleApplied
import org.tatrman.validate.v1.ValidateRequest
import org.tatrman.validate.v1.ValidateResponse
import org.tatrman.validate.v1.ValidationOptions
import org.tatrman.worker.receipt.ExecutionReceiptBuilder
import org.tatrman.worker.v1.ExecutionReceipt
import org.tatrman.worker.v1.ResultBatch
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.slf4j.LoggerFactory
import shared.otel.tracedFlow
import shared.otel.withSpan
import org.tatrman.query.cache.CacheKey
import org.tatrman.query.cache.CachedPlan
import org.tatrman.query.cache.CompiledPlanCache
import org.tatrman.query.client.DispatcherClient
import org.tatrman.query.client.TranslatorClient
import org.tatrman.query.client.TranslatorDetectClient
import org.tatrman.query.client.TranslatorTranslateClient
import org.tatrman.query.client.ValidatorClient
import org.tatrman.query.fingerprint.PredictedFingerprintComputer
import org.tatrman.query.retry.RetryOutcome
import org.tatrman.query.retry.RetryPolicy
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Agent-facing entry point. Chains
 *   Translator.ParseToRelNode → Validator.Validate → Dispatcher.Dispatch
 * for `Run`, stops after Validator for `Compile`, passes through to
 * `Translator.Translate` for `Translate`.
 *
 * Cache (Section B) sits in front of Translator only — Validator runs
 * every call because security depends on `user_id`. Retries (Section C)
 * apply to the unary front-of-pipeline RPCs; once a `ResultBatch` reaches
 * the caller, retries are no longer safe.
 *
 * Errors travel as a single-element error stream
 * (`is_first = is_last = true` + empty `arrow_ipc` + populated `messages`)
 * so callers handle every failure mode uniformly. This matches the shape
 * the Dispatcher and Worker also use.
 */
class QueryServiceImpl(
    rawTranslator: TranslatorClient,
    rawTranslatorDetect: TranslatorDetectClient,
    rawTranslatorTranslate: TranslatorTranslateClient,
    rawValidator: ValidatorClient,
    rawDispatcher: DispatcherClient,
    private val cache: CompiledPlanCache,
    private val retry: RetryPolicy,
    openTelemetry: OpenTelemetry = GlobalOpenTelemetry.get(),
) : QueryServiceGrpcKt.QueryServiceCoroutineImplBase() {
    private val tracer = openTelemetry.getTracer("query")

    // Tracing decorators (Stage 4.1 T3) — every downstream RPC opens a child span
    // on the active trace, on whichever run/compile branch reaches it, so the
    // orchestration breakdown (parse/detect/translate/validate/dispatch) is visible
    // under `query.run`. Cross-pod span nesting is delivered by gRPC
    // auto-instrumentation; these decorators cover the in-process seam exercised by
    // the component test. The branchy run/compile bodies stay untouched.
    private val translator =
        TranslatorClient { req -> tracer.withSpan("query.parse") { rawTranslator.parse(req) } }
    private val translatorDetect =
        TranslatorDetectClient { req -> tracer.withSpan("query.detect_schema") { rawTranslatorDetect.detect(req) } }
    private val translatorTranslate =
        TranslatorTranslateClient { req ->
            tracer.withSpan("query.translate") { rawTranslatorTranslate.translate(req) }
        }
    private val validator =
        ValidatorClient { req -> tracer.withSpan("query.validate") { rawValidator.validate(req) } }
    private val dispatcher =
        DispatcherClient { req -> rawDispatcher.dispatch(req).tracedFlow(tracer, "query.dispatch") }

    private val activeRuns = AtomicInteger(0)

    val activeRunCount: Int
        get() = activeRuns.get()

    override fun run(request: RunRequest): Flow<ResultBatch> {
        val context = ensureCorrelationId(request.context)
        val key = cacheKeyFor(request)
        val window = request.rowWindow

        // The orchestration body is unchanged; we collect it inside a `query.run`
        // span so the per-stage decorator spans nest under it (Stage 4.1 T3).
        val inner =
            flow {
                activeRuns.incrementAndGet()
                try {
                    windowRefusal(window)?.let { reason ->
                        emit(errorBatch("invalid_row_window", reason, context))
                        return@flow
                    }
                    // The validator's `top_n_applied` warnings, from whichever pass raised one.
                    val capNotices = mutableListOf<ResponseMessage>()
                    // ES-P0·S0.3 — the receipt's plan half. `security_applied` accumulates across
                    // every validate pass this run makes (ER, then DB), de-duplicated: a rule both
                    // passes report is one rule, and this set IS the document's Security section
                    // (the one A-1 called structurally unreachable from kantheon). It is derived
                    // fresh on every run — including a cache hit, because ttr-query re-validates
                    // every call (security depends on `user_id`), so the cache never holds it.
                    val securityApplied = linkedSetOf<SecurityRuleApplied>()
                    var effectiveSchemaLabel = ""
                    val compileStart = System.currentTimeMillis()
                    val cachedHit = if (request.bypassCache) null else cache.lookup(key)
                    val (erPlan, dbPlan, detectionMessages) =
                        if (cachedHit != null) {
                            when (cachedHit.effectiveSchema) {
                                SchemaCode.DB -> {
                                    val physicalPlan = cachedHit.physicalPlan
                                    if (physicalPlan == null) {
                                        emit(
                                            errorBatch(
                                                "cache_corrupted",
                                                "Cached DB plan missing physicalPlan",
                                                context,
                                            ),
                                        )
                                        return@flow
                                    }
                                    val dbValidated =
                                        validateDbPlanCompile(windowed(physicalPlan, window), context, window)
                                            ?: run { return@flow }
                                    capNotices += dbValidated.capNotices()
                                    securityApplied += dbValidated.securityAppliedList
                                    effectiveSchemaLabel = SCHEMA_DB
                                    Triple(PlanNode.getDefaultInstance(), dbValidated.plan, cachedHit.detectionMessages)
                                }
                                else -> {
                                    val erValidated =
                                        validateErPlanCompile(windowed(cachedHit.erPlan, window), context, window)
                                            ?: run { return@flow }
                                    val dbParsed =
                                        translateToDbPlain(erValidated.plan, context) ?: run { return@flow }
                                    val dbValidated =
                                        validateDbPlanCompile(dbParsed.plan, context, window) ?: run { return@flow }
                                    capNotices += erValidated.capNotices() + dbValidated.capNotices()
                                    securityApplied += erValidated.securityAppliedList
                                    securityApplied += dbValidated.securityAppliedList
                                    effectiveSchemaLabel = SCHEMA_ER
                                    Triple(erValidated.plan, dbValidated.plan, cachedHit.detectionMessages)
                                }
                            }
                        } else {
                            val resolution = resolveSchema(request, context)
                            if (resolution.isError) {
                                emit(
                                    errorBatch(
                                        "detection_failed",
                                        resolution.detectionMessages.firstOrNull()?.humanMessage ?: "Detection failed",
                                        context,
                                    ),
                                )
                                return@flow
                            }
                            when (resolution.effectiveSchema) {
                                SchemaCode.DB -> {
                                    val dbParsed =
                                        parseSingle(
                                            request.source,
                                            request.sourceLanguage,
                                            SchemaCode.DB,
                                            SchemaCode.DB,
                                            context,
                                        )
                                            ?: run {
                                                emit(
                                                    errorBatch(
                                                        "translator_unavailable",
                                                        "Failed to parse DB path",
                                                        context,
                                                    ),
                                                )
                                                return@flow
                                            }
                                    if (dbParsed.messagesList.any { it.severity == Severity.ERROR }) {
                                        emit(
                                            errorBatch(
                                                "translator_rejected",
                                                dbParsed.messagesList.first().humanMessage,
                                                context,
                                            ),
                                        )
                                        return@flow
                                    }
                                    val dbValidated =
                                        validateDbPlanCompile(windowed(dbParsed.plan, window), context, window) ?: run {
                                            emit(
                                                errorBatch(
                                                    "validator_unavailable",
                                                    "Failed to validate DB path",
                                                    context,
                                                ),
                                            )
                                            return@flow
                                        }
                                    capNotices += dbValidated.capNotices()
                                    securityApplied += dbValidated.securityAppliedList
                                    effectiveSchemaLabel = SCHEMA_DB
                                    val requiredParams = dbParsed.context.parametersList.toList()
                                    val fp = PredictedFingerprintComputer.compute(dbParsed.plan)
                                    cache.record(
                                        key,
                                        CachedPlan(
                                            erPlan = PlanNode.getDefaultInstance(),
                                            requiredParameters = requiredParams,
                                            predictedSchemaFingerprint = fp,
                                            cachedAt = Instant.now(),
                                            effectiveSchema = SchemaCode.DB,
                                            physicalPlan = dbParsed.plan,
                                            detectionMessages = resolution.detectionMessages,
                                        ),
                                    )
                                    Triple(
                                        PlanNode.getDefaultInstance(),
                                        dbValidated.plan,
                                        resolution.detectionMessages,
                                    )
                                }
                                else -> {
                                    val erPlanFromParse =
                                        parseAndCache(request, context, key) ?: run { return@flow }
                                    val erValidated =
                                        validateErPlanCompile(windowed(erPlanFromParse, window), context, window)
                                            ?: run { return@flow }
                                    val dbParsed =
                                        translateToDbPlain(erValidated.plan, context) ?: run { return@flow }
                                    val dbValidated =
                                        validateDbPlanCompile(dbParsed.plan, context, window) ?: run { return@flow }
                                    capNotices += erValidated.capNotices() + dbValidated.capNotices()
                                    securityApplied += erValidated.securityAppliedList
                                    securityApplied += dbValidated.securityAppliedList
                                    effectiveSchemaLabel = SCHEMA_ER
                                    Triple(erValidated.plan, dbValidated.plan, resolution.detectionMessages)
                                }
                            }
                        }

                    val compileMs = System.currentTimeMillis() - compileStart
                    val firstBatchAnnotations =
                        mutableListOf<Warning>().apply {
                            add(warning("compile_duration_ms", compileMs.toString()))
                            add(warning(if (cachedHit != null) "cache_hit" else "cache_miss", key.sourceHash))
                            detectionMessages.forEach { msg ->
                                add(warning(msg.code, msg.humanMessage))
                            }
                        }

                    // ⚑ES-1 — the plan half, built once and carried on the first batch and on
                    // the last. `dispatch_target` is dispatch's fact, learned from the first
                    // batch below and folded in when the last batch is stamped.
                    val planHalf =
                        ExecutionReceiptBuilder.planHalf(
                            dispatchedPlan = dbPlan,
                            securityApplied = securityApplied.toList(),
                            effectiveSchema = effectiveSchemaLabel,
                            cacheHit = cachedHit != null,
                            compileMs = compileMs,
                        )

                    val dispatchReq =
                        DispatchRequest
                            .newBuilder()
                            .setPlan(dbPlan)
                            .setContext(context)
                            .setOptions(request.executionOptions)
                            .build()

                    // The row cap's warning is a statement about the PLAN — validate cannot see the
                    // data. It reaches the caller only if the answer actually REACHED the cap, on the
                    // last batch, where the row count is known: a 5-row answer under a 200-row cap is
                    // complete and says nothing; a 200-row one may have had rows withheld, and says so.
                    val rowCap = rootLimit(dbPlan)
                    val capNotice = capNotices.firstOrNull()
                    var rowsStreamed = 0L
                    var firstSeen = false
                    // Dispatch stamps the worker endpoint on the first batch it forwards
                    // (contracts §1.3 (b)); ttr-query remembers it so the LAST batch — the one a
                    // consumer may read alone — names the worker too.
                    var dispatchTarget = ""
                    dispatcher.dispatch(dispatchReq).collect { batch ->
                        rowsStreamed += batch.batchRowCount
                        if (batch.receipt.dispatchTarget.isNotEmpty()) {
                            dispatchTarget = batch.receipt.dispatchTarget
                        }
                        var out = batch
                        if (!firstSeen && batch.isFirst) {
                            firstSeen = true
                            out = annotate(out, firstBatchAnnotations)
                        }
                        if (batch.isLast && capNotice != null && rowCap != null && rowsStreamed >= rowCap) {
                            out = out.toBuilder().addMessages(capNotice).build()
                        }
                        // ⚑ES-1 — one merge, on the first batch and on the last (they may be the
                        // same batch). The worker's statement half is on the last one and is
                        // merged INTO, never over: the halves occupy disjoint field numbers.
                        if (batch.batchIndex == 0 || batch.isLast) {
                            out = withPlanHalf(out, planHalf, dispatchTarget)
                        }
                        emit(out)
                    }
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) throw t
                    log.warn("Query Run failed: {}", t.message, t)
                    emit(errorBatch("query_failed", t.message ?: "Unhandled Query error.", context))
                } finally {
                    activeRuns.decrementAndGet()
                }
            }
        return inner.tracedFlow(tracer, "query.run")
    }

    /**
     * Two-pass parse: Translator(target_schema=ER) → Validator pass 1 → Translator(source=REL_NODE, target_schema=DB) → Validator pass 2.
     *
     * Returns null after emitting an error batch via the surrounding flow's `emit`.
     */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<ResultBatch>.parseAndCache(
        request: RunRequest,
        context: PipelineContext,
        key: CacheKey,
    ): PlanNode? {
        // Step 1: Parse to ER tree.
        val erParseResult =
            retry.execute("translator.parse_to_rel.target_er") {
                translator.parse(
                    ParseRequest
                        .newBuilder()
                        .setSource(request.source)
                        .setSourceLanguage(request.sourceLanguage)
                        .setTargetSchema(SchemaCode.ER)
                        .setContext(context)
                        .build(),
                )
            }
        val erParsed =
            when (erParseResult) {
                is RetryOutcome.Success -> erParseResult.value
                is RetryOutcome.Failure -> {
                    emit(
                        errorBatch(
                            "translator_unavailable",
                            erParseResult.cause.message ?: "translator unreachable",
                            context,
                        ),
                    )
                    return null
                }
            }
        if (erParsed.messagesList.any { it.severity == Severity.ERROR }) {
            emit(errorBatch("translator_rejected", erParsed.messagesList.first().humanMessage, erParsed.context))
            return null
        }

        val cached =
            CachedPlan(
                erPlan = erParsed.plan,
                requiredParameters = erParsed.context.parametersList.toList(),
                predictedSchemaFingerprint = "",
                cachedAt = Instant.now(),
            )
        cache.record(key, cached)
        return erParsed.plan
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ResultBatch>.validateErPlanCompile(
        erPlan: PlanNode,
        context: PipelineContext,
        window: RowWindow,
    ): ValidateResponse? {
        val erValidatedResult =
            retry.execute("validator.validate.pass_1") {
                validator.validate(
                    ValidateRequest
                        .newBuilder()
                        .setPlan(erPlan)
                        .setContext(context)
                        .setOptions(validationOptions(window))
                        .build(),
                )
            }
        return when (erValidatedResult) {
            is RetryOutcome.Success -> erValidatedResult.value
            is RetryOutcome.Failure -> {
                emit(
                    errorBatch(
                        "validator_unavailable",
                        erValidatedResult.cause.message ?: "validator unreachable",
                        context,
                    ),
                )
                null
            }
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ResultBatch>.translateToDb(
        erPlan: PlanNode,
        context: PipelineContext,
    ): PlanNode? {
        val erPlanBytes = String(erPlan.toByteArray(), Charsets.ISO_8859_1)
        val dbParseResult =
            retry.execute("translator.parse_to_rel.target_db_from_rel_node") {
                translator.parse(
                    ParseRequest
                        .newBuilder()
                        .setSource(erPlanBytes)
                        .setSourceLanguage(Language.REL_NODE)
                        .setTargetSchema(SchemaCode.DB)
                        .setContext(context)
                        .build(),
                )
            }
        return when (dbParseResult) {
            is RetryOutcome.Success -> dbParseResult.value.plan
            is RetryOutcome.Failure -> {
                emit(
                    errorBatch(
                        "translator_unavailable",
                        dbParseResult.cause.message ?: "translator unreachable",
                        context,
                    ),
                )
                null
            }
        }
    }

    private suspend fun kotlinx.coroutines.flow.FlowCollector<ResultBatch>.validateDbPlanCompile(
        dbPlan: PlanNode,
        context: PipelineContext,
        window: RowWindow,
    ): ValidateResponse? {
        val dbValidatedResult =
            retry.execute("validator.validate.pass_2") {
                validator.validate(
                    ValidateRequest
                        .newBuilder()
                        .setPlan(dbPlan)
                        .setContext(context)
                        .setOptions(validationOptions(window))
                        .build(),
                )
            }
        return when (dbValidatedResult) {
            is RetryOutcome.Success -> dbValidatedResult.value
            is RetryOutcome.Failure -> {
                emit(
                    errorBatch(
                        "validator_unavailable",
                        dbValidatedResult.cause.message ?: "validator unreachable",
                        context,
                    ),
                )
                null
            }
        }
    }

    private suspend fun validateErPlanCompile(
        erPlan: PlanNode,
        context: PipelineContext,
        window: RowWindow,
    ): ValidateResponse? {
        val erValidatedResult =
            retry.execute("validator.validate.pass_1") {
                validator.validate(
                    ValidateRequest
                        .newBuilder()
                        .setPlan(erPlan)
                        .setContext(context)
                        .setOptions(validationOptions(window))
                        .build(),
                )
            }
        return when (erValidatedResult) {
            is RetryOutcome.Success -> erValidatedResult.value
            is RetryOutcome.Failure -> null
        }
    }

    private suspend fun validateDbPlanCompile(
        dbPlan: PlanNode,
        context: PipelineContext,
        window: RowWindow,
    ): ValidateResponse? {
        val dbValidatedResult =
            retry.execute("validator.validate.pass_2") {
                validator.validate(
                    ValidateRequest
                        .newBuilder()
                        .setPlan(dbPlan)
                        .setContext(context)
                        .setOptions(validationOptions(window))
                        .build(),
                )
            }
        return when (dbValidatedResult) {
            is RetryOutcome.Success -> dbValidatedResult.value
            is RetryOutcome.Failure -> null
        }
    }

    private suspend fun translateToDbPlain(
        erPlan: PlanNode,
        context: PipelineContext,
    ): ParseResponse? {
        val erPlanBytes = String(erPlan.toByteArray(), Charsets.ISO_8859_1)
        val dbParseResult =
            retry.execute("translator.parse_to_rel.target_db_from_rel_node") {
                translator.parse(
                    ParseRequest
                        .newBuilder()
                        .setSource(erPlanBytes)
                        .setSourceLanguage(Language.REL_NODE)
                        .setTargetSchema(SchemaCode.DB)
                        .setContext(context)
                        .build(),
                )
            }
        return when (dbParseResult) {
            is RetryOutcome.Success -> dbParseResult.value
            is RetryOutcome.Failure -> null
        }
    }

    override suspend fun compile(request: RunRequest): CompileResponse {
        val context = ensureCorrelationId(request.context)
        val window = request.rowWindow
        windowRefusal(window)?.let { reason ->
            return CompileResponse
                .newBuilder()
                .setContext(context)
                .addMessages(errorMessage("invalid_row_window", reason))
                .build()
        }
        val key = cacheKeyFor(request)
        val cached = if (request.bypassCache) null else cache.lookup(key)

        val resolution: SchemaResolution
        var requiredParameters: List<ParameterBinding> = emptyList()
        var predictedSchemaFingerprint = ""
        val erPlan: PlanNode

        if (cached != null) {
            resolution =
                SchemaResolution(
                    cached.effectiveSchema,
                    cached.detectionMessages,
                    isError = false,
                )
            requiredParameters = cached.requiredParameters
            predictedSchemaFingerprint = cached.predictedSchemaFingerprint
            when (cached.effectiveSchema) {
                SchemaCode.DB -> {
                    val physicalPlan = cached.physicalPlan
                    if (physicalPlan == null) {
                        return CompileResponse
                            .newBuilder()
                            .setContext(context)
                            .addMessages(
                                errorMessage(
                                    "cache_corrupted",
                                    "Cached DB plan missing physicalPlan",
                                ),
                            ).build()
                    }
                    val dbValidated =
                        validateDbPlanCompile(windowed(physicalPlan, window), context, window) ?: return CompileResponse
                            .newBuilder()
                            .setContext(context)
                            .addMessages(
                                errorMessage(
                                    "validator_unavailable",
                                    "Failed to validate cached DB plan",
                                ),
                            ).build()
                    return CompileResponse
                        .newBuilder()
                        .setPlan(dbValidated.plan)
                        .setContext(dbValidated.context)
                        .addAllRequiredParameters(requiredParameters)
                        .setPredictedSchemaFingerprint(predictedSchemaFingerprint)
                        .addAllMessages(cached.detectionMessages + dbValidated.compileMessages())
                        .build()
                }
                SchemaCode.ER,
                SchemaCode.SCHEMA_CODE_UNSPECIFIED,
                -> {
                    erPlan = cached.erPlan
                }
                else -> {
                    return CompileResponse
                        .newBuilder()
                        .setContext(context)
                        .addAllMessages(cached.detectionMessages)
                        .build()
                }
            }
        } else {
            resolution = resolveSchema(request, context)

            if (resolution.isError) {
                return CompileResponse
                    .newBuilder()
                    .setContext(context)
                    .addAllMessages(resolution.detectionMessages)
                    .build()
            }

            when (resolution.effectiveSchema) {
                SchemaCode.DB -> {
                    val dbParsed =
                        parseSingle(
                            request.source,
                            request.sourceLanguage,
                            SchemaCode.DB,
                            SchemaCode.DB,
                            context,
                        ) ?: return CompileResponse
                            .newBuilder()
                            .setContext(context)
                            .addMessages(
                                errorMessage(
                                    "translator_unavailable",
                                    "Failed to parse DB path",
                                ),
                            ).build()

                    if (dbParsed.messagesList.any { it.severity == Severity.ERROR }) {
                        return CompileResponse
                            .newBuilder()
                            .setContext(context)
                            .addAllMessages(dbParsed.messagesList)
                            .build()
                    }

                    val dbValidated =
                        validateDbPlanCompile(windowed(dbParsed.plan, window), context, window)
                            ?: return CompileResponse
                                .newBuilder()
                                .setContext(context)
                                .addMessages(
                                    errorMessage(
                                        "validator_unavailable",
                                        "Failed to validate DB path",
                                    ),
                                ).build()

                    requiredParameters = dbParsed.context.parametersList.toList()
                    predictedSchemaFingerprint = PredictedFingerprintComputer.compute(dbParsed.plan)

                    val cachedPlan =
                        CachedPlan(
                            erPlan = PlanNode.getDefaultInstance(),
                            requiredParameters = requiredParameters,
                            predictedSchemaFingerprint = predictedSchemaFingerprint,
                            cachedAt = Instant.now(),
                            effectiveSchema = SchemaCode.DB,
                            physicalPlan = dbParsed.plan,
                            detectionMessages = resolution.detectionMessages,
                        )
                    cache.record(key, cachedPlan)

                    return CompileResponse
                        .newBuilder()
                        .setPlan(dbValidated.plan)
                        .setContext(dbValidated.context)
                        .addAllRequiredParameters(requiredParameters)
                        .setPredictedSchemaFingerprint(predictedSchemaFingerprint)
                        .addAllMessages(resolution.detectionMessages + dbValidated.compileMessages())
                        .build()
                }
                SchemaCode.ER,
                SchemaCode.SCHEMA_CODE_UNSPECIFIED,
                -> {
                    val erParsed =
                        parseErFirst(request, context, key) ?: return CompileResponse
                            .newBuilder()
                            .setContext(context)
                            .addMessages(
                                errorMessage(
                                    "translator_unavailable",
                                    "Failed to parse ER path",
                                ),
                            ).build()

                    if (erParsed.messagesList.any { it.severity == Severity.ERROR }) {
                        return CompileResponse
                            .newBuilder()
                            .setContext(context)
                            .addAllMessages(erParsed.messagesList)
                            .build()
                    }

                    erPlan = erParsed.plan
                    requiredParameters = erParsed.context.parametersList.toList()

                    val erValidated =
                        validateErPlanCompile(windowed(erPlan, window), context, window) ?: return CompileResponse
                            .newBuilder()
                            .setContext(context)
                            .addMessages(
                                errorMessage(
                                    "validator_unavailable",
                                    "Failed to validate ER plan",
                                ),
                            ).build()

                    val dbParsed =
                        translateToDbPlain(erValidated.plan, context) ?: return CompileResponse
                            .newBuilder()
                            .setContext(context)
                            .addMessages(
                                errorMessage(
                                    "translator_unavailable",
                                    "Failed to translate to DB",
                                ),
                            ).build()

                    if (dbParsed.messagesList.any { it.severity == Severity.ERROR }) {
                        return CompileResponse
                            .newBuilder()
                            .setContext(context)
                            .addAllMessages(dbParsed.messagesList)
                            .build()
                    }

                    predictedSchemaFingerprint = PredictedFingerprintComputer.compute(dbParsed.plan)

                    val dbValidated =
                        validateDbPlanCompile(dbParsed.plan, context, window) ?: return CompileResponse
                            .newBuilder()
                            .setContext(context)
                            .addMessages(
                                errorMessage(
                                    "validator_unavailable",
                                    "Failed to validate DB plan",
                                ),
                            ).build()

                    val cachedPlan =
                        CachedPlan(
                            erPlan = erPlan,
                            requiredParameters = requiredParameters,
                            predictedSchemaFingerprint = predictedSchemaFingerprint,
                            cachedAt = Instant.now(),
                            effectiveSchema = SchemaCode.ER,
                            detectionMessages = resolution.detectionMessages,
                        )
                    cache.record(key, cachedPlan)

                    return CompileResponse
                        .newBuilder()
                        .setPlan(dbValidated.plan)
                        .setContext(dbValidated.context)
                        .addAllRequiredParameters(requiredParameters)
                        .setPredictedSchemaFingerprint(predictedSchemaFingerprint)
                        .addAllMessages(resolution.detectionMessages + dbValidated.compileMessages())
                        .build()
                }
                else -> {
                    return CompileResponse
                        .newBuilder()
                        .setContext(context)
                        .addAllMessages(resolution.detectionMessages)
                        .build()
                }
            }
        }

        val erValidated =
            validateErPlanCompile(windowed(erPlan, window), context, window) ?: return CompileResponse
                .newBuilder()
                .setContext(context)
                .addMessages(
                    errorMessage(
                        "validator_unavailable",
                        "Failed to validate ER plan",
                    ),
                ).build()

        val dbParsed =
            translateToDbPlain(erValidated.plan, context) ?: return CompileResponse
                .newBuilder()
                .setContext(context)
                .addMessages(
                    errorMessage(
                        "translator_unavailable",
                        "Failed to translate to DB",
                    ),
                ).build()

        if (dbParsed.messagesList.any { it.severity == Severity.ERROR }) {
            return CompileResponse
                .newBuilder()
                .setContext(context)
                .addAllMessages(dbParsed.messagesList)
                .build()
        }

        predictedSchemaFingerprint = PredictedFingerprintComputer.compute(dbParsed.plan)

        val dbValidated =
            validateDbPlanCompile(dbParsed.plan, context, window) ?: return CompileResponse
                .newBuilder()
                .setContext(context)
                .addMessages(
                    errorMessage(
                        "validator_unavailable",
                        "Failed to validate DB plan",
                    ),
                ).build()

        return CompileResponse
            .newBuilder()
            .setPlan(dbValidated.plan)
            .setContext(dbValidated.context)
            .addAllRequiredParameters(requiredParameters)
            .setPredictedSchemaFingerprint(predictedSchemaFingerprint)
            .addAllMessages(resolution.detectionMessages + dbValidated.messagesList)
            .build()
    }

    private suspend fun parseSingle(
        source: String,
        sourceLanguage: Language,
        sourceSchema: SchemaCode,
        targetSchema: SchemaCode,
        context: PipelineContext,
    ): ParseResponse? {
        val result =
            retry.execute("translator.parse_single") {
                translator.parse(
                    ParseRequest
                        .newBuilder()
                        .setSource(source)
                        .setSourceLanguage(sourceLanguage)
                        .setSourceSchema(sourceSchema)
                        .setTargetSchema(targetSchema)
                        .setContext(context)
                        .build(),
                )
            }
        return when (result) {
            is RetryOutcome.Success -> result.value
            is RetryOutcome.Failure -> null
        }
    }

    private suspend fun parseErFirst(
        request: RunRequest,
        context: PipelineContext,
        key: CacheKey,
    ): ParseResponse? {
        val erParseResult =
            retry.execute("translator.parse_to_rel.target_er") {
                translator.parse(
                    ParseRequest
                        .newBuilder()
                        .setSource(request.source)
                        .setSourceLanguage(request.sourceLanguage)
                        .setTargetSchema(SchemaCode.ER)
                        .setContext(context)
                        .build(),
                )
            }
        val erParsed =
            when (erParseResult) {
                is RetryOutcome.Success -> erParseResult.value
                is RetryOutcome.Failure -> return null
            }
        if (erParsed.messagesList.any { it.severity == Severity.ERROR }) {
            return erParsed
        }

        val requiredParameters = erParsed.context.parametersList.toList()
        cache.record(
            key,
            CachedPlan(
                erPlan = erParsed.plan,
                requiredParameters = requiredParameters,
                predictedSchemaFingerprint = "",
                cachedAt = Instant.now(),
                effectiveSchema = SchemaCode.ER,
                detectionMessages = emptyList(),
            ),
        )
        return erParsed
    }

    override suspend fun translate(request: TranslateRequest): TranslateResponse =
        translatorTranslate.translate(request)

    override suspend fun getStatus(request: GetStatusRequest): GetStatusResponse {
        val stats = cache.stats()
        return GetStatusResponse
            .newBuilder()
            .setReady(true)
            .setActiveRuns(activeRuns.get())
            .setCompiledCache(
                CacheStatus
                    .newBuilder()
                    .setEntries(stats.entries)
                    .setMaxEntries(stats.maxEntries)
                    .setHits(stats.hits)
                    .setMisses(stats.misses)
                    .setInvalidations(stats.invalidations)
                    .setEvictions(stats.evictions)
                    .setCurrentModelVersion(stats.currentModelVersion),
            ).setOverallStatus(OverallStatus.OK)
            .build()
    }

    private fun cacheKeyFor(request: RunRequest): CacheKey =
        CacheKey(
            modelVersion = request.context.modelVersion,
            sourceHash = CompiledPlanCache.sourceHash(request.source),
            sourceLanguage = request.sourceLanguage,
            paramSignature = CompiledPlanCache.paramSignature(request.context.parametersList),
        )

    /**
     * The caller's row window on the plan, as the ROOT LimitOffset the validator's TopN rule reads
     * and caps. Applied ONCE, to the first plan validated — the ER plan on the two-pass path (the
     * translator carries the node into the DB plan), the DB plan on the one-pass path. Wrapping the
     * DB plan again on the two-pass path would apply the offset twice. An unset window leaves the
     * plan exactly as it was.
     *
     * The window is deliberately NOT part of the compiled-plan cache key: the cache holds the
     * translator's output, before validation, and the window goes on top of it per request — so
     * every page of one query shares one compiled plan.
     */
    private fun windowed(
        plan: PlanNode,
        window: RowWindow,
    ): PlanNode {
        if (window.limit <= 0 && window.offset <= 0) return plan
        val node = LimitOffsetNode.newBuilder().setInput(plan)
        if (window.limit > 0) node.setLimit(window.limit)
        if (window.offset > 0) node.setOffset(window.offset)
        return PlanNode.newBuilder().setLimitOffset(node).build()
    }

    /**
     * Validation options for one request. `default_top_n` carries the rows the caller ASKED for
     * (0 = unstated); the validator decides how many it gets and warns when it cuts.
     */
    private fun validationOptions(window: RowWindow): ValidationOptions.Builder =
        ValidationOptions
            .newBuilder()
            .setApplySecurity(true)
            .setEnforceTopN(true)
            .setDefaultTopN(window.limit.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())

    private fun windowRefusal(window: RowWindow): String? =
        when {
            window.limit < 0 -> "row_window.limit must be >= 0, got ${window.limit}"
            window.offset < 0 -> "row_window.offset must be >= 0, got ${window.offset}"
            // The plan node carries int64, but every reader below narrows to int32: ttr-translator's
            // `PlanNodeDecoder.pushLimitOffset` calls `.toInt()`, so an offset of 2^31 reaches Calcite
            // as a NEGATIVE one — a wrong answer, where this refusal exists to name bad input.
            window.limit > Int.MAX_VALUE -> "row_window.limit must be <= ${Int.MAX_VALUE}, got ${window.limit}"
            window.offset > Int.MAX_VALUE -> "row_window.offset must be <= ${Int.MAX_VALUE}, got ${window.offset}"
            else -> null
        }

    /**
     * `compile` returns a PLAN and no rows, so validate's `top_n_applied` — which says an ANSWER was
     * cut — is a claim this surface cannot make: the enforcer raises it for every plan it bounds, i.e.
     * every plan without a caller-stated limit, so every compile answered *"Answer limited to N rows"*
     * having returned none. `run` states it only on a last batch that actually reached the cap; compile
     * states the same fact about the future RUN instead. It matters because this surface is LLM-facing
     * through query-mcp's `CompileTool`, and a warning on every call is one a model learns to ignore.
     */
    private fun ValidateResponse.compileMessages(): List<ResponseMessage> {
        val cap = rootLimit(plan)
        return messagesList.map { message ->
            if (message.code != TOP_N_APPLIED || cap == null) {
                message
            } else {
                message
                    .toBuilder()
                    .setHumanMessage(
                        "A row cap of $cap rows will apply when this plan runs; `compile` itself returns no rows.",
                    ).build()
            }
        }
    }

    /** The row bound a validated plan executes under, when its root states one. */
    private fun rootLimit(plan: PlanNode): Long? =
        plan
            .takeIf { it.nodeCase == PlanNode.NodeCase.LIMIT_OFFSET && it.limitOffset.hasLimit() }
            ?.limitOffset
            ?.limit

    private fun ValidateResponse.capNotices(): List<ResponseMessage> = messagesList.filter { it.code == TOP_N_APPLIED }

    private fun ensureCorrelationId(context: PipelineContext): PipelineContext =
        if (context.correlationId.isEmpty()) {
            context
                .toBuilder()
                .setCorrelationId(
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                ).build()
        } else {
            context
        }

    /**
     * Folds [planHalf] into whatever receipt [batch] already carries (⚑ES-1).
     *
     * A batch that arrives with the worker's statement half keeps every one of its fields — the
     * two halves are disjoint, so proto's own merge is the rule. A null half (the builder
     * refused, best-effort) leaves the batch exactly as it was: a receipt never fails a run.
     */
    private fun withPlanHalf(
        batch: ResultBatch,
        planHalf: ExecutionReceipt?,
        dispatchTarget: String,
    ): ResultBatch {
        val half =
            when {
                planHalf == null -> return batch
                dispatchTarget.isEmpty() -> planHalf
                else -> planHalf.toBuilder().setDispatchTarget(dispatchTarget).build()
            }
        val merged =
            ExecutionReceiptBuilder.merged(
                existing = if (batch.hasReceipt()) batch.receipt else null,
                half = half,
            ) ?: return batch
        return batch.toBuilder().setReceipt(merged).build()
    }

    private fun annotate(
        batch: ResultBatch,
        warnings: List<Warning>,
    ): ResultBatch {
        if (warnings.isEmpty()) return batch
        val ctx = batch.context.toBuilder()
        warnings.forEach { ctx.addWarnings(it) }
        return batch.toBuilder().setContext(ctx).build()
    }

    private fun warning(
        code: String,
        message: String,
    ): Warning =
        Warning
            .newBuilder()
            .setCode(code)
            .setMessage(message)
            .setSourceStage("run")
            .setSourceService("query")
            .build()

    private fun errorBatch(
        code: String,
        message: String,
        context: PipelineContext,
    ): ResultBatch =
        ResultBatch
            .newBuilder()
            .setIsFirst(true)
            .setIsLast(true)
            .setArrowIpc(ByteArray(0).toByteString())
            .setContext(context)
            .addMessages(errorMessage(code, message))
            .build()

    private fun errorMessage(
        code: String,
        message: String,
    ): ResponseMessage =
        ResponseMessage
            .newBuilder()
            .setSeverity(Severity.ERROR)
            .setCode(code)
            .setHumanMessage(message)
            .build()

    /** Suppress an unused-import lint for the language enum. */
    @Suppress("unused")
    private fun touchLanguageEnum() = Language.SQL

    /** Suppress an unused-import lint for [PlanNode]. */
    @Suppress("unused")
    private fun touchPlan() = PlanNode.getDefaultInstance()

    /** Convenience flowOf to keep imports tidy. */
    @Suppress("unused")
    private fun <T> single(value: T): Flow<T> = flowOf(value)

    private suspend fun resolveSchema(
        request: RunRequest,
        ctx: PipelineContext,
    ): SchemaResolution {
        if (request.sourceLanguage != Language.SQL) {
            val fallback = request.sourceSchema
            return SchemaResolution(fallback, emptyList(), isError = false)
        }
        val detectResp =
            retry.execute("translator.detect_schema") {
                translatorDetect.detect(
                    DetectSchemaRequest
                        .newBuilder()
                        .setSource(request.source)
                        .setSourceLanguage(request.sourceLanguage)
                        .setStatedSchema(request.sourceSchema)
                        .setContext(ctx)
                        .build(),
                )
            }
        return when (detectResp) {
            is RetryOutcome.Success -> {
                val resp = detectResp.value
                val isError =
                    resp.decision == SchemaDecision.AMBIGUOUS ||
                        resp.decision == SchemaDecision.UNKNOWN ||
                        resp.decision == SchemaDecision.MIXED
                SchemaResolution(resp.effectiveSchema, resp.messagesList, isError)
            }
            is RetryOutcome.Failure ->
                SchemaResolution(
                    SchemaCode.SCHEMA_CODE_UNSPECIFIED,
                    listOf(
                        errorMessage(
                            "detector_unavailable",
                            detectResp.cause.message ?: "translator unreachable",
                        ),
                    ),
                    isError = true,
                )
        }
    }

    private data class SchemaResolution(
        val effectiveSchema: SchemaCode,
        val detectionMessages: List<ResponseMessage>,
        val isError: Boolean,
    )

    companion object {
        private val log = LoggerFactory.getLogger(QueryServiceImpl::class.java)

        /** validate's `RuleEnforcer.TOP_N_APPLIED` — spelled here because query does not depend on validate. */
        private const val TOP_N_APPLIED = "top_n_applied"

        /** `ExecutionReceipt.effective_schema` values (contracts §1.1) — "ER" | "DB". */
        private const val SCHEMA_ER = "ER"
        private const val SCHEMA_DB = "DB"
    }
}
