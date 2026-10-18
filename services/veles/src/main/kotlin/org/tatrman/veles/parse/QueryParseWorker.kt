// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.parse

import org.tatrman.translate.v1.Language as TranslatorLanguage
import org.tatrman.plan.v1.QualifiedName as ProtoQualifiedName
import org.tatrman.plan.v1.SchemaCode as ProtoSchemaCode
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ParseStatus
import org.tatrman.ttr.metadata.model.SchemaCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.schemaCodeToToken
import org.tatrman.translator.framework.EntityMapping
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.translator.joiner.JoinerMessages
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.params.SqlParam
import org.tatrman.veles.grpc.toDomain
import org.tatrman.veles.grpc.toProto

/**
 * Section F (DF-M05) — background query-parse worker. On every model swap it
 * parses each query's source text against the new model (in-process, via
 * `query-translator`), with bounded parallelism, writing PARSED/FAILED into a
 * [QueryParseState]. Never blocks the swap path; a failing or throwing query
 * marks just that query FAILED and the rest continue.
 *
 * LQ (contracts L-2…L-4) — a query parses in the lane it is declared in: a logical query (`er.query.<name>`, over
 * entities) is stored at the ER level (the ER half's output: entity scans kept, bare joins conditioned), every other
 * query in the db lane as before; both strictly, so a body that reads the other lane — or both — is refused by name.
 * Each entity stacked on a parsed query then has its attribute bindings checked against the body's output columns.
 */
class QueryParseWorker(
    parallelism: Int = 4,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val logger = LoggerFactory.getLogger(QueryParseWorker::class.java)
    private val gate = Semaphore(parallelism.coerceAtLeast(1))

    /**
     * Enqueue a parse job per query in [model]; results land in [state]. Returns immediately.
     * The returned [Job] completes when every enqueued parse has finished (useful for tests).
     */
    fun parseAll(
        model: Model,
        state: QueryParseState,
    ): Job =
        scope.launch {
            if (model.queries.isEmpty()) return@launch
            val handle = queryPathHandle(model)
            val translator = Translator(handle)
            val stacked = stackedEntities(handle, model.queries.keys.mapTo(HashSet()) { it.toProto() })
            val version = model.version.value
            for (query in model.queries.values) {
                launch {
                    gate.withPermit {
                        val status = parseOne(translator, query)
                        state.set(version, query.qname, status)
                        if (status is ParseStatus.ParseSuccess) {
                            checkStackedEntities(handle, query.qname.toProto(), status, stacked, version, state)
                        }
                    }
                }
            }
        }

    private fun parseOne(
        translator: Translator,
        query: org.tatrman.ttr.metadata.model.Query,
    ): ParseStatus =
        try {
            val lang = translatorLanguage(query.sourceLanguage)
            if (lang == null) {
                ParseStatus.ParseFailure("Unsupported source language '${query.sourceLanguage}'")
            } else {
                // Pass the query's declared parameters so `parseToRelNode` runs the
                // ParameterBridge: `{name}` placeholders are rewritten to Calcite positional
                // `?` (pre-typed from the declared `type`). Without them the bridge is skipped
                // and Calcite chokes on `{` — the reason parametrized queries reported FAILED.
                // The runtime value is irrelevant at parse time, so bind `null`.
                val params = query.parameters.map { SqlParam(name = it.name, type = it.type, value = null) }
                // LQ (L-1, L-3) — the lane is the one the query is declared in: `er` for a logical query, stored
                // at the ER level; anything else (`db`, or no schema code) is a db query, stored physical.
                val lane = if (query.qname.schemaCode == SchemaCode.ER) ProtoSchemaCode.ER else ProtoSchemaCode.DB
                val result =
                    translator.parseToRelNode(
                        query.sourceText,
                        lang,
                        targetSchema = lane,
                        sourceSchema = lane,
                        parameters = params,
                        strictLane = true,
                    )
                when (result) {
                    is ParseResult.Success ->
                        unconditionedJoins(result)
                            ?: (
                                if (lane ==
                                    ProtoSchemaCode.ER
                                ) {
                                    physicalJoins(translator, query.sourceText, lang, params)
                                } else {
                                    null
                                }
                            )
                            ?: ParseStatus.ParseSuccess(result.plan.toByteArray())
                    is ParseResult.Failure -> ParseStatus.ParseFailure(message = "${result.code}: ${result.message}")
                }
            }
        } catch (e: Exception) {
            logger.warn(
                "Query parse threw for {}.{}.{}: {}",
                query.qname.schemaCode,
                query.qname.namespace,
                query.qname.name,
                e.message,
            )
            ParseStatus.ParseFailure("parse_exception: ${e.message}")
        }

    /**
     * LQ (L-3) — a saved query's bare JOIN that the model cannot condition is refused. The translator leaves such a
     * join unconditioned and warns (`JOIN_NO_RELATION`, `JOIN_AMBIGUOUS_RELATIONS`, `JOIN_KEY_NAME_COLLISION` — the
     * WARNING-severity joiner results); a question can carry that warning to its asker, but a stored body would
     * answer every reader with the Cartesian product, silently. An FK-only relation (`JOIN_RELATION_WITHOUT_PAIRS`,
     * INFO) is conditioned from its foreign key after MAP_TO_PHYSICAL and stays PARSED.
     */
    private fun unconditionedJoins(result: ParseResult.Success): ParseStatus.ParseFailure? {
        val unresolved = result.warnings.filter { JoinerMessages.severity(it) == JoinerMessages.Severity.WARNING }
        if (unresolved.isEmpty()) return null
        val reasons = unresolved.joinToString("; ") { "${JoinerMessages.code(it)}: ${JoinerMessages.text(it)}" }
        return ParseStatus.ParseFailure(
            "$LOGICAL_QUERY_JOIN_UNRESOLVED: $reasons — a saved query's joins must be conditioned: join entities " +
                "that one relation connects, or write the ON",
        )
    }

    /**
     * LQ (L-3) — the physical half of [unconditionedJoins] for a logical query. A bare JOIN over an FK-only relation
     * (no `join:` pairs) is INFO at the ER level and is conditioned from the foreign key only at MAP_TO_PHYSICAL — where
     * two foreign keys between the same tables leave it unconditioned (`JOIN_AMBIGUOUS_RELATIONS`). The body is taken
     * to the physical level once, here, so that is refused at parse time rather than served as a Cartesian product.
     * The stored plan stays the ER half's.
     */
    private fun physicalJoins(
        translator: Translator,
        source: String,
        lang: TranslatorLanguage,
        params: List<SqlParam>,
    ): ParseStatus.ParseFailure? {
        val physical =
            translator.parseToRelNode(
                source,
                lang,
                targetSchema = ProtoSchemaCode.DB,
                sourceSchema = ProtoSchemaCode.ER,
                parameters = params,
                strictLane = true,
            )
        return (physical as? ParseResult.Success)?.let { unconditionedJoins(it) }
    }

    /**
     * LQ (L-4) — for every entity stacked on [query] (mapped `ToQuery` onto it): each attribute must bind a column the
     * stored body outputs — its renamed column, or its own name when unbound, exactly the column MAP_TO_PHYSICAL's
     * boundary reads. A mismatch is recorded on the entity; the query stays PARSED.
     */
    private fun checkStackedEntities(
        handle: ModelHandle,
        query: ProtoQualifiedName,
        parsed: ParseStatus.ParseSuccess,
        stacked: Map<ProtoQualifiedName, List<ProtoQualifiedName>>,
        version: String,
        state: QueryParseState,
    ) {
        val entities = stacked[query] ?: return
        val outputs = Translator.outputColumnNames(PlanNode.parseFrom(parsed.canonicalFormProtoBytes))
        for (entity in entities) {
            val renames = handle.attributeColumnRenames(entity)
            val missing =
                handle
                    .attributes(entity)
                    .map { it.name to (renames[it.name] ?: it.name) }
                    .filter { (_, column) -> column !in outputs }
            if (missing.isEmpty()) continue
            val message =
                "${Translator.ENTITY_QUERY_OUTPUT_MISMATCH}: entity '${entity.dotted()}' binds " +
                    missing.joinToString { (attribute, column) -> "attribute '$attribute' to column '$column'" } +
                    ", which query '${query.dotted()}' does not output (its columns: ${outputs.joinToString()})"
            logger.warn("{}", message)
            state.setMappingMismatch(version, entity.toDomain(), message)
        }
    }

    fun close() {
        scope.cancel()
    }

    private companion object {
        /** LQ (L-3) — a saved query's bare JOIN the model cannot condition (see [unconditionedJoins]). */
        const val LOGICAL_QUERY_JOIN_UNRESOLVED = "logical_query_join_unresolved"

        private fun translatorLanguage(s: String): TranslatorLanguage? =
            when (s.uppercase().replace("-", "_")) {
                "SQL" -> TranslatorLanguage.SQL
                "TRANSFORMATION_DSL", "TRANSDSL", "TRANS_DSL" -> TranslatorLanguage.TRANSFORMATION_DSL
                "DATAFRAME_DSL", "DFDSL", "DF_DSL" -> TranslatorLanguage.DATAFRAME_DSL
                "REL_NODE", "RELNODE", "PLAN_NODE", "PLANNODE" -> TranslatorLanguage.REL_NODE
                else -> null
            }

        /**
         * The entities mapped `ToQuery`, by the query of [queries] whose body they read — resolved as MAP_TO_PHYSICAL
         * resolves it: the query the mapping names, else the same name with no schema code.
         */
        private fun stackedEntities(
            handle: ModelHandle,
            queries: Set<ProtoQualifiedName>,
        ): Map<ProtoQualifiedName, List<ProtoQualifiedName>> =
            handle
                .namespaces(ProtoSchemaCode.ER)
                .flatMap { handle.entities(ProtoSchemaCode.ER, it).keys }
                .mapNotNull { entity ->
                    val named =
                        (handle.entityMapping(entity) as? EntityMapping.ToQuery)?.query ?: return@mapNotNull null
                    val schemaless = named.toBuilder().setSchemaCode(ProtoSchemaCode.SCHEMA_CODE_UNSPECIFIED).build()
                    when {
                        named in queries -> named to entity
                        schemaless in queries -> schemaless to entity
                        else -> null
                    }
                }.groupBy({ it.first }, { it.second })

        private fun ProtoQualifiedName.dotted(): String =
            listOf(schemaCodeToToken(schemaCode), namespace, name).filter { it.isNotEmpty() }.joinToString(".")
    }
}
