// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translate.grpc

import com.google.protobuf.kotlin.toByteString
import org.tatrman.meta.v1.GetQueryRequest
import org.tatrman.meta.v1.GetQueryResponse
import org.tatrman.meta.v1.ParseStatus as MetadataParseStatus
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.schemaCodeToToken
import org.tatrman.translate.v1.DetectSchemaRequest
import org.tatrman.translate.v1.DetectSchemaResponse
import org.tatrman.translate.v1.ExplainRequest
import org.tatrman.translate.v1.ExplainResponse
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.ParseRequest
import org.tatrman.translate.v1.ParseResponse
import org.tatrman.translate.v1.SchemaDecision
import org.tatrman.translate.v1.StageArtifact
import org.tatrman.translate.v1.SuggestionGroup
import org.tatrman.translate.v1.TranslateRequest
import org.tatrman.translate.v1.TranslateResponse
import org.tatrman.translate.v1.TranslateServiceGrpcKt
import org.tatrman.translate.v1.UnparseRequest
import org.tatrman.translate.v1.UnparseResponse
import org.slf4j.LoggerFactory
import org.tatrman.translate.model.ModelHandleProvider
import org.tatrman.translator.codec.transdsl.TransDslCodec
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.orchestrator.TranslateResult
import org.tatrman.translator.orchestrator.UnparseResult
import org.tatrman.translator.params.SqlParam
import org.tatrman.plan.v1.parseSchemaCode

/**
 * gRPC surface for the translator service.
 *
 * Each RPC:
 *   1. Captures the current [org.tatrman.translator.framework.ModelHandle] from [ModelHandleProvider]
 *      (per-request snapshot — see provider docs).
 *   2. Constructs a fresh [Translator] orchestrator from the library (rule #2).
 *   3. For TransDSL sources with a metadata client wired ([getQuery] non-null), pre-resolves any
 *      `query_ref` to its stored canonical [PlanNode] via the metadata service's `GetQuery`, and
 *      passes the resolution map to the parser. A `query_ref` that's missing / not yet parsed /
 *      failed → a structured error message rather than a silent empty subquery.
 *   4. Delegates to the library, mapping the sealed result types to the proto response shapes.
 *
 * Errors follow the platform pattern: gRPC status `OK` with a populated `messages = 99` field
 * carrying a structured [ResponseMessage]; gRPC status codes are reserved for transport failures.
 *
 * [getQuery] is injected so tests can supply a canned response without a real channel; in
 * production it's a thin wrapper over `VelesServiceCoroutineStub.getQuery(...)`. `null` (the
 * fixture / no-metadata path) disables `query_ref` resolution — `query_ref` sources then fall
 * back to the codec's placeholder behaviour.
 */
class TranslatorServiceImpl(
    private val modelProvider: ModelHandleProvider,
    private val getQuery: (suspend (GetQueryRequest) -> GetQueryResponse)? = null,
) : TranslateServiceGrpcKt.TranslateServiceCoroutineImplBase() {
    override suspend fun parseToRelNode(request: ParseRequest): ParseResponse {
        val builder = ParseResponse.newBuilder().setContext(request.context)
        val queryRefs =
            try {
                resolveQueryRefs(request.source, request.sourceLanguage)
            } catch (e: QueryRefResolutionException) {
                return builder.addMessages(errorMessage(e.code, e.message ?: "query_ref resolution failed")).build()
            }
        val handle = modelProvider.current()
        val result =
            Translator(handle).parseToRelNode(
                source = request.source,
                sourceLanguage = request.sourceLanguage,
                targetSchema = request.targetSchema,
                queryRefs = queryRefs,
                sourceSchema = request.sourceSchema,
                parameters = toSqlParams(request.context),
                expandLogicalBodies = request.expandLogicalBodies,
            )
        return when (result) {
            is ParseResult.Success -> builder.setPlan(result.plan).build()
            is ParseResult.Failure -> {
                // Phase 08 C2 / DF-V07 — enrich validation_failed messages with
                // scoped suggestions or cross-schema hints.
                val enrichedMessage =
                    if (result.code == "validation_failed") {
                        val activeSchema =
                            if (request.sourceSchema !=
                                org.tatrman.plan.v1.SchemaCode.SCHEMA_CODE_UNSPECIFIED
                            ) {
                                request.sourceSchema
                            } else {
                                request.targetSchema
                            }
                        runCatching {
                            org.tatrman.translator.suggest.SuggestingMessage.enrich(
                                result.message,
                                handle,
                                activeSchema,
                                if (activeSchema == org.tatrman.plan.v1.SchemaCode.DB) "dbo" else "entity",
                            )
                        }.getOrElse { result.message }
                    } else {
                        result.message
                    }
                builder.addMessages(errorMessage(result.code, enrichedMessage)).build()
            }
        }
    }

    override suspend fun unparseFromRelNode(request: UnparseRequest): UnparseResponse {
        val translator = Translator(modelProvider.current())
        val result =
            translator.unparseFromRelNode(
                plan = request.plan,
                targetLanguage = request.targetLanguage,
                targetDialect = request.targetDialect,
                optimize =
                    if (request.hasField(
                            UnparseRequest.getDescriptor().findFieldByNumber(4),
                        )
                    ) {
                        request.optimize
                    } else {
                        true
                    },
                parameters = request.context.parametersList,
            )
        return when (result) {
            is UnparseResult.Success -> {
                // Replace the context's parameters with the positional (one-per-`?`) sequence the
                // worker binds 1:1 — a name used twice yields two `?` but a single named binding, so
                // the distinct list would leave the later position unset. Empty (non-SQL target /
                // param-less query) → fuzzy the context unchanged.
                val context =
                    if (result.parameters.isEmpty()) {
                        request.context
                    } else {
                        request.context
                            .toBuilder()
                            .clearParameters()
                            .addAllParameters(result.parameters)
                            .build()
                    }
                UnparseResponse
                    .newBuilder()
                    .setContext(context)
                    .setOutput(result.output)
                    .build()
            }
            is UnparseResult.Failure ->
                UnparseResponse
                    .newBuilder()
                    .setContext(request.context)
                    .addMessages(errorMessage(result.code, result.message))
                    .build()
        }
    }

    override suspend fun translate(request: TranslateRequest): TranslateResponse {
        val builder = TranslateResponse.newBuilder().setContext(request.context)
        val queryRefs =
            try {
                resolveQueryRefs(request.source, request.sourceLanguage)
            } catch (e: QueryRefResolutionException) {
                return builder.addMessages(errorMessage(e.code, e.message ?: "query_ref resolution failed")).build()
            }
        val result =
            Translator(modelProvider.current()).translate(
                source = request.source,
                sourceLanguage = request.sourceLanguage,
                targetLanguage = request.targetLanguage,
                targetSchema = request.targetSchema,
                targetDialect = request.targetDialect,
                queryRefs = queryRefs,
                sourceSchema = request.sourceSchema,
                parameters = toSqlParams(request.context),
                expandLogicalBodies = request.expandLogicalBodies,
            )
        return when (result) {
            is TranslateResult.Success -> builder.setOutput(result.output).build()
            is TranslateResult.Failure -> builder.addMessages(errorMessage(result.code, result.message)).build()
        }
    }

    override suspend fun explain(request: ExplainRequest): ExplainResponse {
        val translator = Translator(modelProvider.current())
        val (sourceLanguage, sourceText, finalKind) =
            when (request.reqCase) {
                ExplainRequest.ReqCase.PARSE ->
                    Triple(request.parse.sourceLanguage, request.parse.source, "parse")
                ExplainRequest.ReqCase.TRANSLATE ->
                    Triple(request.translate.sourceLanguage, request.translate.source, "translate")
                ExplainRequest.ReqCase.UNPARSE -> {
                    log.warn("Explain(unparse) requested — Phase 1.4 wraps as 'unparse-only' debug")
                    Triple(Language.REL_NODE, "", "unparse")
                }
                else ->
                    return ExplainResponse
                        .newBuilder()
                        .addMessages(
                            errorMessage(
                                "explain_request_empty",
                                "Explain request must set one of {parse, unparse, translate}",
                            ),
                        ).build()
            }

        val r = translator.explain(source = sourceText, sourceLanguage = sourceLanguage)
        val builder = ExplainResponse.newBuilder()
        r.stages.forEach {
            builder.addStages(StageArtifact.newBuilder().setStageCode(it.code).setCanonicalForm(it.summary))
        }
        r.finalOutput?.let { builder.setFinalOutput(it.toByteArray().toByteString()) }
        r.finalError?.let { builder.addMessages(errorMessage("explain_failed_during_$finalKind", it)) }
        return builder.build()
    }

    @Suppress("argument-list-wrapping", "max-line-length")
    override suspend fun detectSourceSchema(request: DetectSchemaRequest): DetectSchemaResponse {
        val handle = modelProvider.current()
        val result =
            org.tatrman.translator.detect.SchemaDetector.detect(
                source = request.source,
                sourceLanguage = request.sourceLanguage,
                statedSchema = request.statedSchema,
                model = handle,
            )

        val builder =
            DetectSchemaResponse
                .newBuilder()
                .setEffectiveSchema(result.effectiveSchema)
                .setDecision(mapDecision(result.decision))
                .addAllReferencedTables(result.referencedTables)

        for ((unknownName, candidates) in result.suggestions) {
            builder.addSuggestions(
                SuggestionGroup
                    .newBuilder()
                    .setUnknownName(unknownName)
                    .addAllCandidates(candidates)
                    .build(),
            )
        }

        when (result.decision) {
            org.tatrman.translator.detect.SchemaDecision.AUTODETECTED -> {
                builder.addMessages(
                    infoMessage(
                        "schema_autodetected",
                        "Autodetected ${schemaCodeToToken(result.effectiveSchema)} schema from query identifiers.",
                    ),
                )
            }
            org.tatrman.translator.detect.SchemaDecision.CORRECTED -> {
                builder.addMessages(
                    warnMessage(
                        "schema_corrected",
                        "source_schema was ${schemaCodeToToken(
                            result.statedSchema,
                        )}, but identifiers resolve to ${schemaCodeToToken(
                            result.effectiveSchema,
                        )}; using ${schemaCodeToToken(result.effectiveSchema)}.",
                    ),
                )
            }
            org.tatrman.translator.detect.SchemaDecision.AMBIGUOUS -> {
                val schemas =
                    result.perTableSchemas.values
                        .flatten()
                        .toSortedSet()
                val tables = result.referencedTables.joinToString()
                builder.addMessages(
                    errorMessage(
                        "schema_ambiguous",
                        "Could not determine schema: $tables exist in multiple schemas (${schemas.joinToString {
                            schemaCodeToToken(
                                it,
                            )
                        }}). Set source_schema explicitly.",
                    ),
                )
            }
            org.tatrman.translator.detect.SchemaDecision.UNKNOWN -> {
                val tables = result.unknownTables.joinToString()
                builder.addMessages(
                    errorMessage(
                        "schema_object_unknown",
                        "Unknown object(s): $tables. Did you mean: ${result.suggestions.flatMap {
                            it.candidates
                        }.distinct().joinToString()}?",
                    ),
                )
            }
            org.tatrman.translator.detect.SchemaDecision.MIXED -> {
                val tableSchemas =
                    result.perTableSchemas.entries.joinToString { (t, s) ->
                        "$t:${s.joinToString("/") { schemaCodeToToken(it) }}"
                    }
                builder.addMessages(
                    errorMessage(
                        "schema_mixed",
                        "Query mixes objects from multiple schemas ($tableSchemas); mixed-schema queries are not supported.",
                    ),
                )
            }
            org.tatrman.translator.detect.SchemaDecision.CONFIRMED,
            org.tatrman.translator.detect.SchemaDecision.NOT_APPLICABLE,
            -> {
                // No message
            }
        }

        return builder.build()
    }

    private fun mapDecision(decision: org.tatrman.translator.detect.SchemaDecision): SchemaDecision =
        when (decision) {
            org.tatrman.translator.detect.SchemaDecision.CONFIRMED -> SchemaDecision.CONFIRMED
            org.tatrman.translator.detect.SchemaDecision.AUTODETECTED -> SchemaDecision.AUTODETECTED
            org.tatrman.translator.detect.SchemaDecision.CORRECTED -> SchemaDecision.CORRECTED
            org.tatrman.translator.detect.SchemaDecision.AMBIGUOUS -> SchemaDecision.AMBIGUOUS
            org.tatrman.translator.detect.SchemaDecision.UNKNOWN -> SchemaDecision.UNKNOWN
            org.tatrman.translator.detect.SchemaDecision.MIXED -> SchemaDecision.MIXED
            org.tatrman.translator.detect.SchemaDecision.NOT_APPLICABLE -> SchemaDecision.NOT_APPLICABLE
        }

    /**
     * For TransDSL sources with a metadata client wired: fetch the canonical [PlanNode] for every
     * `query_ref` in [source] and return them as a map for the TransDSL parser. `null` for non-
     * TransDSL sources, when no metadata client is configured, or when the TransDSL has no
     * `query_ref`s. Throws [QueryRefResolutionException] if a referenced query can't be resolved.
     */
    private suspend fun resolveQueryRefs(
        source: String,
        sourceLanguage: Language,
    ): Map<String, PlanNode>? {
        val getQuery = this.getQuery ?: return null
        if (sourceLanguage != Language.TRANSFORMATION_DSL) return null
        val refs =
            try {
                TransDslCodec.queryRefsInJson(source)
            } catch (_: Exception) {
                // Malformed TransDSL JSON — let the parser produce the structured parse error.
                return null
            }
        if (refs.isEmpty()) return null
        val resolved = LinkedHashMap<String, PlanNode>(refs.size)
        for (ref in refs) resolved[ref] = resolveOne(ref, getQuery)
        return resolved
    }

    private suspend fun resolveOne(
        ref: String,
        getQuery: suspend (GetQueryRequest) -> GetQueryResponse,
    ): PlanNode {
        val qname = parseQueryRefQname(ref)
        val resp =
            try {
                getQuery(
                    GetQueryRequest
                        .newBuilder()
                        .setQualifiedName(qname)
                        .setIncludeCanonicalForm(true)
                        .build(),
                )
            } catch (e: Exception) {
                throw QueryRefResolutionException(
                    "metadata_unavailable",
                    "Could not reach the metadata service to resolve query_ref '$ref': ${e.message}",
                )
            }
        val codes = resp.messagesList.map { it.code }
        if ("metadata_not_ready" in codes) {
            throw QueryRefResolutionException(
                "metadata_not_ready",
                "Metadata service has no model yet; cannot resolve query_ref '$ref'",
            )
        }
        if ("object_not_found" in codes) {
            throw QueryRefResolutionException("query_ref_not_found", "query_ref '$ref' is not a known stored query")
        }
        return when (resp.parseStatus) {
            MetadataParseStatus.PARSE_STATUS_PARSED ->
                if (resp.hasCanonicalForm()) {
                    resp.canonicalForm
                } else {
                    throw QueryRefResolutionException(
                        "query_ref_no_canonical_form",
                        "query_ref '$ref' is parsed but the metadata service returned no canonical form",
                    )
                }
            MetadataParseStatus.PARSE_STATUS_PENDING ->
                throw QueryRefResolutionException(
                    "query_ref_pending",
                    "query_ref '$ref' is still being parsed; retry shortly",
                )
            MetadataParseStatus.PARSE_STATUS_FAILED ->
                throw QueryRefResolutionException(
                    "query_ref_parse_failed",
                    "query_ref '$ref' failed to parse: ${resp.parseErrorMessage}",
                )
            else ->
                throw QueryRefResolutionException("query_ref_unparsed", "query_ref '$ref' has no usable parse state")
        }
    }

    /**
     * Parse a `query_ref` string into a [QualifiedName]. Format `schema.namespace.name`; with two
     * segments the schema is empty; with one segment only the name is set. (Stored-query qnames
     * use the dotted form; this mirrors how they're keyed in the metadata model.)
     */
    private fun parseQueryRefQname(ref: String): QualifiedName {
        val parts = ref.split('.')
        val (schema, namespace, name) =
            when {
                parts.size >= 3 -> Triple(parts[0], parts[1], parts.drop(2).joinToString("."))
                parts.size == 2 -> Triple("", parts[0], parts[1])
                else -> Triple("", "", ref)
            }
        return QualifiedName
            .newBuilder()
            .setSchemaCode(parseSchemaCode(schema) ?: org.tatrman.plan.v1.SchemaCode.SCHEMA_CODE_UNSPECIFIED)
            .setNamespace(namespace)
            .setName(name)
            .build()
    }

    /**
     * Convert the `PipelineContext.parameters` bindings into the library's [SqlParam] form so the
     * orchestrator can rewrite `{name}` → `?` and pre-type each placeholder during parse. Empty
     * (the common free-SQL / preview path) leaves the verbatim-SQL behaviour untouched.
     */
    private fun toSqlParams(context: org.tatrman.plan.v1.PipelineContext): List<SqlParam> =
        context.parametersList.map { b ->
            SqlParam(name = b.name, type = b.type, value = protoValueToAny(b.value))
        }

    private fun protoValueToAny(value: org.tatrman.plan.v1.Value): Any? =
        when {
            value.isNull -> null
            else ->
                when (value.vCase) {
                    org.tatrman.plan.v1.Value.VCase.STRING_VALUE -> value.stringValue
                    org.tatrman.plan.v1.Value.VCase.INT_VALUE -> value.intValue
                    org.tatrman.plan.v1.Value.VCase.FLOAT_VALUE -> value.floatValue
                    org.tatrman.plan.v1.Value.VCase.BOOL_VALUE -> value.boolValue
                    org.tatrman.plan.v1.Value.VCase.DATETIME_VALUE -> value.datetimeValue
                    org.tatrman.plan.v1.Value.VCase.V_NOT_SET, null -> null
                }
        }

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

    private fun infoMessage(
        code: String,
        message: String,
    ): ResponseMessage =
        ResponseMessage
            .newBuilder()
            .setSeverity(Severity.INFO)
            .setCode(code)
            .setHumanMessage(message)
            .build()

    private fun warnMessage(
        code: String,
        message: String,
    ): ResponseMessage =
        ResponseMessage
            .newBuilder()
            .setSeverity(Severity.WARNING)
            .setCode(code)
            .setHumanMessage(message)
            .build()

    private class QueryRefResolutionException(
        val code: String,
        message: String,
    ) : RuntimeException(message)

    companion object {
        private val log = LoggerFactory.getLogger(TranslatorServiceImpl::class.java)
    }
}
