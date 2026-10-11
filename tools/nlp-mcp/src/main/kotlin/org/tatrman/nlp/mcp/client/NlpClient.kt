// SPDX-License-Identifier: Apache-2.0
package org.tatrman.nlp.mcp.client

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import org.slf4j.LoggerFactory
import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.Mode
import org.tatrman.nlp.v1.NlpOp
import org.tatrman.nlp.v1.NlpServiceGrpcKt
import java.io.Closeable
import java.util.concurrent.TimeUnit

data class NlpToken(
    val text: String,
    val charStart: Int,
    val charEnd: Int,
    val lemma: String,
    val upos: String,
    val xpos: String,
    val feats: Map<String, String>,
    val depHead: Int,
    val depRelation: String,
)

data class NlpSpan(
    val charStart: Int,
    val charEnd: Int,
)

data class NlpEntity(
    val text: String,
    val label: String,
    val charStart: Int,
    val charEnd: Int,
    val normalizedValue: String,
    val sourceEngine: String,
)

data class NlpMessage(
    val severity: String,
    val code: String,
    val message: String,
)

/** S-1 model-identity echo (contracts §1 `used[]`). */
data class NlpEngineVersion(
    val op: String,
    val engine: String,
    val model: String,
    val modelVersion: String,
)

data class NlpAnalyzeResult(
    val language: String,
    val languageConfidence: Double,
    val engineUsed: String,
    val tokens: List<NlpToken>,
    val sentences: List<NlpSpan>,
    val paragraphs: List<NlpSpan>,
    val entities: List<NlpEntity>,
    val traceId: String,
    val elapsedMs: Long,
    val messages: List<NlpMessage>,
    val used: List<NlpEngineVersion> = emptyList(),
)

/**
 * gRPC client for `org.tatrman.nlp.v1.NlpService` (RG-P1.S1 — gRPC is the
 * service contract; the nlp REST endpoint is a dev/health mirror). Migrated
 * from the forked predecessor-platform `POST /v1/analyze` HTTP client. Owns its
 * [ManagedChannel]; call [close] on shutdown. Mirrors fuzzy-mcp's
 * `FuzzyGrpcClient`.
 */
class NlpClient(
    host: String,
    port: Int,
    private val deadlineSeconds: Long = 30,
) : Closeable {
    private val logger = LoggerFactory.getLogger(NlpClient::class.java)

    private val channel: ManagedChannel =
        ManagedChannelBuilder
            .forAddress(host, port)
            .usePlaintext()
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(10, TimeUnit.SECONDS)
            .keepAliveWithoutCalls(true)
            .build()

    private val stub = NlpServiceGrpcKt.NlpServiceCoroutineStub(channel)

    suspend fun analyze(
        text: String,
        language: String = "",
        ops: Set<String>,
        mode: String = "NORMAL",
        engineHints: Map<String, String> = emptyMap(),
    ): NlpAnalyzeResult {
        val request =
            AnalyzeRequest
                .newBuilder()
                .setText(text)
                .setLanguage(language)
                .setMode(runCatching { Mode.valueOf(mode) }.getOrDefault(Mode.NORMAL))
                .apply {
                    ops.forEach { op ->
                        runCatching { NlpOp.valueOf(op) }.getOrNull()?.let { addOps(it) }
                    }
                    engineHints.forEach { (k, v) -> putEngineHints(k, v) }
                }.build()

        val response =
            try {
                stub.withDeadlineAfter(deadlineSeconds, TimeUnit.SECONDS).analyze(request)
            } catch (e: Exception) {
                throw NlpClientException(
                    "Nlp service gRPC call failed: ${e.javaClass.simpleName}: ${e.message}",
                    e,
                )
            }

        return NlpAnalyzeResult(
            language = response.language.ifBlank { response.detectedLanguage },
            languageConfidence = response.languageConfidence,
            engineUsed = response.engineUsed,
            tokens =
                response.tokensList.map {
                    NlpToken(
                        text = it.text,
                        charStart = it.charStart,
                        charEnd = it.charEnd,
                        lemma = it.lemma,
                        upos = it.upos,
                        xpos = it.xpos,
                        feats = it.featsMap,
                        depHead = it.depHead,
                        depRelation = it.depRelation,
                    )
                },
            sentences = response.sentencesList.map { NlpSpan(it.charStart, it.charEnd) },
            paragraphs = response.paragraphsList.map { NlpSpan(it.charStart, it.charEnd) },
            entities =
                response.entitiesList.map {
                    NlpEntity(
                        text = it.text,
                        label = it.label,
                        charStart = it.charStart,
                        charEnd = it.charEnd,
                        normalizedValue = it.normalizedValue,
                        sourceEngine = it.sourceEngine,
                    )
                },
            traceId = response.traceId,
            elapsedMs = response.elapsedMs,
            messages =
                response.messagesList.map {
                    NlpMessage(severity = it.severity.name, code = it.code, message = it.humanMessage)
                },
            used =
                response.usedList.map {
                    NlpEngineVersion(op = it.op, engine = it.engine, model = it.model, modelVersion = it.modelVersion)
                },
        )
    }

    override fun close() {
        channel.shutdown()
    }
}

class NlpClientException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
