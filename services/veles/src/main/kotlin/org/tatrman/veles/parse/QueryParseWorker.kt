// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.parse

import org.tatrman.translate.v1.Language as TranslatorLanguage
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ParseStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.params.SqlParam

/**
 * Section F (DF-M05) — background query-parse worker. On every model swap it
 * parses each query's source text against the new model (in-process, via
 * `query-translator`), with bounded parallelism, writing PARSED/FAILED into a
 * [QueryParseState]. Never blocks the swap path; a failing or throwing query
 * marks just that query FAILED and the rest continue.
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
            val translator = Translator(MetadataModelHandle(model))
            for (query in model.queries.values) {
                launch {
                    gate.withPermit {
                        state.set(model.version.value, query.qname, parseOne(translator, query))
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
                when (val r = translator.parseToRelNode(query.sourceText, lang, parameters = params)) {
                    is ParseResult.Success -> ParseStatus.ParseSuccess(r.plan.toByteArray())
                    is ParseResult.Failure -> ParseStatus.ParseFailure(message = "${r.code}: ${r.message}")
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

    fun close() {
        scope.cancel()
    }

    private companion object {
        private fun translatorLanguage(s: String): TranslatorLanguage? =
            when (s.uppercase().replace("-", "_")) {
                "SQL" -> TranslatorLanguage.SQL
                "TRANSFORMATION_DSL", "TRANSDSL", "TRANS_DSL" -> TranslatorLanguage.TRANSFORMATION_DSL
                "DATAFRAME_DSL", "DFDSL", "DF_DSL" -> TranslatorLanguage.DATAFRAME_DSL
                "REL_NODE", "RELNODE", "PLAN_NODE", "PLANNODE" -> TranslatorLanguage.REL_NODE
                else -> null
            }
    }
}
