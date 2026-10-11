// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.BatchLemmatizeRequest
import org.tatrman.nlp.v1.NlpOp
import org.tatrman.nlp.v1.NlpServiceGrpcKt
import org.tatrman.nlp.v1.StatusRequest
import java.util.concurrent.TimeUnit

/**
 * The lemma-axis's link to `nlp` — RG-P2.S1.T4 repoints it from the old
 * `POST /v1/analyze` HTTP path to RG-P1's **gRPC `BatchLemmatize`** (batched:
 * ONE rpc for N tokens, never per-string HTTP — the pilot's `nlp.enabled=false`
 * cause). Injectable so [NlpLemmatizer] is unit-testable without a channel.
 *
 * @return positional lemma lists — `results[i]` is the lemma tokens of `texts[i]`.
 */
interface NlpBatchClient {
    suspend fun batchLemmatize(
        texts: List<String>,
        language: String,
    ): List<List<String>>

    /**
     * UD-P3 — the named entities in [text], from nlp `Analyze` with the NER op alone, plus the
     * identity of the engine that found them. Null from a client that cannot ask (a test double
     * that only lemmatises): no profile, which is the behaviour before UD-P3.
     */
    suspend fun entities(
        text: String,
        language: String,
    ): NerResult? = null

    /**
     * UD-P3 — the identity of the NER nlp would run for [language] (`engine:model_version`), read
     * from its capability matrix; null when it cannot be read. A profile is recomputed when this
     * changes, and kept when it cannot be read.
     */
    suspend fun nerIdentity(language: String): String? = null

    fun close() {}
}

/** One NER answer: entities by character offset into the text asked about, and the model that found them. */
data class NerResult(
    val entities: List<NerSpan>,
    val model: String,
)

/** One entity: `[start, end)` into the asked text, the coarse label, and the fine code (`cnec:gu`, or ""). */
data class NerSpan(
    val start: Int,
    val end: Int,
    val label: String,
    val code: String,
)

/** gRPC-backed [NlpBatchClient] over `org.tatrman.nlp.v1.NlpService.BatchLemmatize`. */
class GrpcNlpBatchClient(
    host: String,
    port: Int,
    private val deadlineSeconds: Long = 5,
    // UD-P3 — a profile chunk is thousands of characters through the NER, not a word list through a
    // tagger, so it gets its own, longer deadline.
    private val entityDeadlineSeconds: Long = 60,
) : NlpBatchClient {
    private val channel: ManagedChannel =
        ManagedChannelBuilder
            .forAddress(host, port)
            .usePlaintext()
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(10, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .build()

    private val stub = NlpServiceGrpcKt.NlpServiceCoroutineStub(channel)

    override suspend fun batchLemmatize(
        texts: List<String>,
        language: String,
    ): List<List<String>> {
        if (texts.isEmpty()) return emptyList()
        val request =
            BatchLemmatizeRequest
                .newBuilder()
                .addAllTexts(texts)
                .setLanguage(language)
                .build()
        val response = stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).batchLemmatize(request)
        return response.resultsList.map { it.lemmasList.toList() }
    }

    override suspend fun entities(
        text: String,
        language: String,
    ): NerResult {
        val request =
            AnalyzeRequest
                .newBuilder()
                .setText(text)
                .setLanguage(language)
                .addOps(NlpOp.NER)
                .build()
        val response = stub.withDeadlineAfter(entityDeadlineSeconds, TimeUnit.SECONDS).analyze(request)
        val model =
            response.usedList
                .filter { it.op.equals(NlpOp.NER.name, ignoreCase = true) }
                .joinToString(",") { "${it.engine}:${it.model}" }
        return NerResult(
            response.entitiesList.map { e ->
                NerSpan(
                    e.charStart,
                    e.charEnd,
                    e.label,
                    e.normalizedValue.takeIf { it.startsWith(CODE_PREFIX) }.orEmpty(),
                )
            },
            model,
        )
    }

    override suspend fun nerIdentity(language: String): String? {
        val status =
            stub
                .withDeadlineAfter(
                    deadlineSeconds,
                    TimeUnit.SECONDS,
                ).getStatus(StatusRequest.getDefaultInstance())
        return status.capabilitiesList
            .filter { it.op == NlpOp.NER && it.language.equals(language, ignoreCase = true) }
            .map { "${it.engine}:${it.modelVersion}" }
            .sorted()
            .joinToString(",")
            .ifBlank { null }
    }

    override fun close() {
        channel.shutdown()
    }

    private companion object {
        /**
         * The fine code is kept only when it is a code (`cnec:gu`). `normalized_value` is also where
         * a date's ISO value and an amount's number ride, and a profile counted per value would be
         * one row per date in the vocabulary.
         */
        const val CODE_PREFIX = "cnec:"
    }
}
