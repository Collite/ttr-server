// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.protobuf.util.JsonFormat
import io.grpc.ServerBuilder
import io.grpc.Status
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import org.tatrman.grounding.v1.GroundingServiceGrpcKt
import org.tatrman.grounding.v1.ScanRequest
import org.tatrman.grounding.v1.ScanResponse
import org.tatrman.grounding.v1.ValueBinding
import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Capability
import org.tatrman.nlp.v1.NerEntity
import org.tatrman.nlp.v1.NlpOp
import org.tatrman.nlp.v1.StatusResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.client.GrpcGroundingClient
import org.tatrman.resolver.client.NlpClient
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.pipeline.QuoteScanner
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.ResolveContext
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.resolver.v1.UniversalEntityType
import org.tatrman.resolver.v1.ValueKind

/** Replayed Scan/Ground wire answers, through the resolver's real client and pipeline; no chrono. */
class ScanSeamTest :
    StringSpec({
        for (fixture in fixtures().take(5)) {
            "replayed ${fixture.id} yields the DATE universal and cal_date interval" {
                withKernel(fixture) { kernel, client ->
                    val out = resolve(fixture, client)
                    val span = fixture.scan.spansList.single()
                    val universal =
                        out.resolution.bindingsList
                            .single { it.hasUniversal() }
                            .universal
                    universal.entityType shouldBe UniversalEntityType.DATE
                    universal.rawText shouldBe span.text
                    val value = out.resolutionState.valuesList.single { it.span.text == span.text }
                    value.span.start shouldBe span.start
                    value.span.end shouldBe span.end
                    value.span.text shouldBe span.text
                    value.kind shouldBe ValueKind.VALUE_KIND_GROUNDED
                    val interval =
                        fixture.ground
                            .getValue(span.text)
                            .result.normalized.interval
                    value.grounding.normalizedValue shouldBe "${interval.start}/${interval.end}"
                    value.attributionsList.map { it.attributeRef } shouldContainExactly
                        listOf("er.entity.date_dim.cal_date")
                    out.resolutionState.mentionsList
                        .filter { it.span.start < span.end && span.start < it.span.end }
                        .shouldBeEmpty()
                    out.resolutionState.gapsList
                        .filter { it.span.start < span.end && span.start < it.span.end }
                        .shouldBeEmpty()
                    kernel.scans.size shouldBe 1
                    kernel.grounds.map { it.spanText } shouldContainExactly listOf(span.text)
                }
            }
        }

        "an overlapping NER DATE stands and is grounded once" {
            val f = fixtures().first()
            val ner = entity(f, "minulý měsíc")
            withKernel(f) { kernel, client ->
                val out = resolve(f, client, listOf(ner))
                out.parse.entitiesList shouldContainExactly listOf(ner)
                kernel.grounds.map { it.spanText } shouldContainExactly listOf(ner.text)
            }
        }

        "a partly overlapping Scan span is dropped without replacing the NER DATE" {
            val f = fixtures().first()
            val ner = entity(f, "měsíc")
            withKernel(f) { kernel, client ->
                val out = resolve(f, client, listOf(ner))
                out.parse.entitiesList shouldContainExactly listOf(ner)
                kernel.grounds.map { it.spanText } shouldContainExactly listOf("měsíc")
            }
        }

        "an adjacent scanned DATE is added beside the NER DATE" {
            val f = fixtures().first().copy(text = "2025minulý měsíc")
            val scan = shifted(f.scan, 4)
            withKernel(f.copy(scan = scan)) { kernel, client ->
                val out = resolve(f, client, listOf(entity(f, "2025")))
                out.parse.entitiesList.map { it.text } shouldContainExactly listOf("2025", "minulý měsíc")
                kernel.grounds.map { it.spanText } shouldContainExactly listOf("2025", "minulý měsíc")
            }
        }

        for ((label, normalized) in listOf(
            "NUMBER" to "",
            "CARDINAL" to "",
            "ORDINAL" to "",
            "PERCENT" to "",
            "MISC" to "",
            "MISC" to "cnec:nc",
            "no" to "",
            // Raw CNEC wins over a contradictory coarse label.
            "DATE" to "cnec:n_",
            "MONEY" to "",
        )) {
            "numeric or MONEY NER $label/$normalized owns overlap with a longer Scan year range" {
                val f = rangeFixture("😀 Revenue 2024-2025")
                f.scan.spansList
                    .single()
                    .text shouldBe "2024-2025"
                val ner = entity(f, "2025", label, normalized)
                withKernel(f) { kernel, client ->
                    val out = resolve(f, client, listOf(ner))
                    out.parse.entitiesList shouldContainExactly listOf(ner)
                    // Drop the WHOLE range, with no fragment or scan-derived Ground on its number.
                    kernel.grounds.shouldBeEmpty()
                    kernel.scans.size shouldBe 1
                }
            }
        }

        "a Scan date adjacent to a numeric NER span survives after an emoji" {
            val base = fixtures().first()
            val f = base.copy(text = "😀 2025minulý měsíc", scan = shifted(base.scan, 7))
            val ner = entity(f, "2025", "CARDINAL")
            withKernel(f) { kernel, client ->
                val out = resolve(f, client, listOf(ner))
                out.parse.entitiesList.map { it.text } shouldContainExactly listOf("2025", "minulý měsíc")
                out.parse.entitiesList
                    .last()
                    .charStart shouldBe ner.charEnd
                kernel.grounds.map { it.spanText } shouldContainExactly listOf("minulý měsíc")
            }
        }

        for ((label, normalized) in listOf("MISC" to "", "MISC" to "cnec:op", "NUMBER" to "cnec:op")) {
            "domain MISC $label/$normalized does not claim numeric NER precedence" {
                val base = fixtures().first()
                val ner = entity(base, "měsíc", label, normalized)
                withKernel(base) { kernel, client ->
                    val out = resolve(base, client, listOf(ner))
                    out.parse.entitiesList.map { it.text } shouldContainExactly listOf("minulý měsíc", "měsíc")
                    kernel.grounds.map { it.spanText } shouldContainExactly listOf("minulý měsíc")
                }
            }
        }

        for (status in listOf(Status.UNAVAILABLE, Status.INTERNAL)) {
            "Scan ${status.code} leaves NER spans only and records scan_failed without retry" {
                val f = fixtures().first()
                withKernel(f, status) { kernel, client ->
                    val ner = entity(f, "měsíc")
                    val out = resolve(f, client, listOf(ner))
                    out.parse.entitiesList shouldContainExactly listOf(ner)
                    out.resolutionState.rungLogList.count { it.action == "scan_failed" } shouldBe 1
                    kernel.scans.size shouldBe 1
                    kernel.grounds.map { it.spanText } shouldContainExactly listOf("měsíc")
                }
            }
        }

        "an error Scan response keeps only NER entities and records scan_failed" {
            val base = fixtures().first()
            val f =
                base.copy(
                    scan =
                        base.scan
                            .toBuilder()
                            .addMessages(
                                ResponseMessage.newBuilder().setSeverity(Severity.ERROR).setCode("invalid_reference"),
                            ).build(),
                )
            val ner = entity(f, "měsíc")
            withKernel(f) { kernel, client ->
                val out = resolve(f, client, listOf(ner))
                out.parse.entitiesList shouldContainExactly listOf(ner)
                out.resolutionState.rungLogList.count { it.action == "scan_failed" } shouldBe 1
                kernel.scans.size shouldBe 1
            }
        }

        "a values result keeps the interval with a blank anchor column" {
            val f = fixtures().first()
            val phrase =
                f.scan.spansList
                    .single()
                    .text
            val response = f.ground.getValue(phrase).toBuilder()
            response.result =
                response.result
                    .toBuilder()
                    .setValues(ValueBinding.getDefaultInstance())
                    .build()
            withKernel(f.copy(ground = mapOf(phrase to response.build()))) { _, client ->
                for (ner in listOf(emptyList(), listOf(entity(f, phrase)))) {
                    val value = resolve(f, client, ner).resolutionState.valuesList.single { it.span.text == phrase }
                    value.grounding.normalizedValue shouldBe "2026-09-01T00:00:00Z/2026-10-01T00:00:00Z"
                    value.kind shouldBe ValueKind.VALUE_KIND_GROUNDED
                    value.attributionsList.shouldBeEmpty()
                }
            }
        }

        "Scan and Ground carry the same locale reference prior questions and package override" {
            val f = fixtures().first()
            withKernel(f) { kernel, client ->
                resolve(f, client, pkg = "other-package")
                val scan = kernel.scans.single()
                val ground = kernel.grounds.single()
                scan.text shouldBe f.text
                scan.`package` shouldBe "other-package"
                scan.context shouldBe ground.context
                scan.context.locale shouldBe f.locale
                scan.context.referenceDatetime shouldBe REFERENCE
                scan.context.priorQuestionsList shouldContainExactly PRIOR
                ground.questionText shouldBe f.text
            }
        }

        "without NER capability neither Scan nor Ground takes words from the mention path" {
            val f = fixtures().first()
            withKernel(f) { kernel, client ->
                resolve(f, client, nerServed = false)
                    .resolutionState.valuesList
                    .filter { it.grounding.kind == "DATE" }
                    .shouldBeEmpty()
                kernel.scans.shouldBeEmpty()
                kernel.grounds.shouldBeEmpty()
            }
        }

        "without a grounding client the NER parse stands" {
            val f = fixtures().first()
            val ner = entity(f, "měsíc")
            resolve(f, null, listOf(ner)).parse.entitiesList shouldContainExactly listOf(ner)
        }

        "quoted time words are blanked before Scan while Ground retains the original question" {
            val base = fixtures().last()
            val f =
                base.copy(
                    text = "Revenue \"loni\" last year",
                    scan =
                        shifted(
                            base.scan
                                .toBuilder()
                                .clearSpans()
                                .addSpans(base.scan.spansList.last())
                                .build(),
                            15,
                        ),
                )
            withKernel(f) { kernel, client ->
                val out = resolve(f, client)
                kernel.scans.single().text shouldBe "Revenue        last year"
                kernel.grounds.single().questionText shouldBe f.text
                out.parse.entitiesList.map { it.text } shouldContainExactly listOf("last year")
            }
        }

        for (f in fixtures().takeLast(2)) {
            "P7 ${f.id} retains both operands and context in the interpretation input" {
                withKernel(f) { kernel, client ->
                    val out = resolve(f, client)
                    val values = out.resolutionState.valuesList
                    values.map { it.span.text } shouldContainExactly f.scan.spansList.map { it.text }
                    values.map { it.span.start to it.span.end } shouldContainExactly
                        f.scan.spansList.map { it.start to it.end }
                    values.map { it.grounding.normalizedValue } shouldContainExactly
                        f.scan.spansList.map {
                            val interval =
                                f.ground
                                    .getValue(it.text)
                                    .result.normalized.interval
                            "${interval.start}/${interval.end}"
                        }
                    out.resolutionState.parse shouldBe out.parse
                    out.parse.tokensList shouldContainExactly f.tokens
                    kernel.scans.size shouldBe 1
                    kernel.grounds.map { it.spanText } shouldContainExactly f.scan.spansList.map { it.text }
                    kernel.grounds.forEach {
                        it.questionText shouldBe f.text
                        it.context.priorQuestionsList shouldContainExactly PRIOR
                        it.context.referenceDatetime shouldBe REFERENCE
                    }
                }
            }
        }

        "Scan UTF-16 offsets become parse code-point offsets after an emoji" {
            val base = fixtures().first()
            val f =
                base.copy(
                    text = "😀 " + base.text,
                    scan =
                        shifted(
                            base.scan,
                            base.scan.spansList
                                .single()
                                .start + 3,
                        ),
                )
            withKernel(f) { _, client ->
                val value = resolve(f, client).resolutionState.valuesList.single { it.span.text == "minulý měsíc" }
                value.span.start shouldBe base.scan.spansList
                    .single()
                    .start + 2
                value.span.end shouldBe base.scan.spansList
                    .single()
                    .end + 2
                value.span.text shouldBe "minulý měsíc"
            }
        }

        "NER overlap is compared in parse coordinates after an emoji" {
            val base = fixtures().first()
            val f =
                base.copy(
                    text = "😀 " + base.text,
                    scan =
                        shifted(
                            base.scan,
                            base.scan.spansList
                                .single()
                                .start + 3,
                        ),
                )
            val ner = entity(f, "minulý měsíc")
            withKernel(f) { kernel, client ->
                resolve(f, client, listOf(ner)).parse.entitiesList shouldContainExactly listOf(ner)
                kernel.grounds.size shouldBe 1
            }
        }
    }) {
    private companion object {
        const val REFERENCE = "2026-10-10T00:00:00Z"
        val PRIOR = listOf("Revenue in 2025", "Revenue in 2024")

        data class Fixture(
            val id: String,
            val text: String,
            val locale: String,
            val scan: ScanResponse,
            val ground: Map<String, GroundResponse>,
            val tokens: List<Token>,
        )

        fun fixtures(): List<Fixture> {
            val input = checkNotNull(ScanSeamTest::class.java.getResourceAsStream("/time-scan/replay.json"))
            return input.use { stream ->
                ObjectMapper().readTree(stream).map { row ->
                    Fixture(
                        row["id"].asText(),
                        row["text"].asText(),
                        row["locale"].asText(),
                        ScanResponse
                            .newBuilder()
                            .also {
                                JsonFormat.parser().merge(
                                    row["scan"].toString(),
                                    it,
                                )
                            }.build(),
                        row["ground"].fields().asSequence().associate { (span, answer) ->
                            span to
                                GroundResponse
                                    .newBuilder()
                                    .also {
                                        JsonFormat.parser().merge(
                                            answer.toString(),
                                            it,
                                        )
                                    }.build()
                        },
                        row["tokens"].map { token ->
                            Token.newBuilder().also { JsonFormat.parser().merge(token.toString(), it) }.build()
                        },
                    )
                }
            }
        }

        fun rangeFixture(text: String): Fixture {
            val input = checkNotNull(ScanSeamTest::class.java.getResourceAsStream("/time-scan/numeric-ner-replay.json"))
            val row = input.use { ObjectMapper().readTree(it).single { row -> row["text"].asText() == text } }
            val base = fixtures().last()
            return base.copy(
                text = text,
                scan =
                    ScanResponse
                        .newBuilder()
                        .also {
                            JsonFormat.parser().merge(row["scan"].toString(), it)
                        }.build(),
                tokens = QuoteScanner.tokens(text, AnalyzeResponse.getDefaultInstance()),
            )
        }

        fun shifted(
            scan: ScanResponse,
            start: Int,
        ): ScanResponse =
            scan
                .toBuilder()
                .clearSpans()
                .addSpans(
                    scan.spansList.single().toBuilder().setStart(start).setEnd(
                        start +
                            scan.spansList
                                .single()
                                .text.length,
                    ),
                ).build()

        fun entity(
            f: Fixture,
            text: String,
            label: String = "DATE",
            normalized: String = "",
        ): NerEntity {
            val start = f.text.indexOf(text)
            return NerEntity
                .newBuilder()
                .setText(text)
                .setLabel(label)
                .setNormalizedValue(normalized)
                .setSourceEngine("nametag3")
                .setCharStart(
                    f.text.codePointCount(0, start),
                ).setCharEnd(f.text.codePointCount(0, start + text.length))
                .build()
        }

        class Kernel(
            val fixture: Fixture,
            val failure: Status?,
        ) : GroundingServiceGrpcKt.GroundingServiceCoroutineImplBase() {
            val scans = mutableListOf<ScanRequest>()
            val grounds = mutableListOf<GroundRequest>()

            override suspend fun scan(request: ScanRequest): ScanResponse {
                scans += request
                failure?.let { throw it.asRuntimeException() }
                return fixture.scan
            }

            override suspend fun ground(request: GroundRequest): GroundResponse {
                grounds += request
                return fixture.ground[request.spanText]
                    ?: GroundResponse.newBuilder().setStatus(GroundResponse.Status.UNGROUNDABLE).build()
            }
        }

        fun withKernel(
            f: Fixture,
            failure: Status? = null,
            test: (Kernel, GrpcGroundingClient) -> Unit,
        ) {
            val kernel = Kernel(f, failure)
            val server =
                ServerBuilder
                    .forPort(0)
                    .addService(kernel)
                    .build()
                    .start()
            try {
                GrpcGroundingClient("127.0.0.1", server.port).use { test(kernel, it) }
            } finally {
                server.shutdownNow()
            }
        }

        fun resolve(
            f: Fixture,
            kernel: GrpcGroundingClient?,
            entities: List<NerEntity> = emptyList(),
            nerServed: Boolean = true,
            pkg: String = "",
        ): ResolveResponse {
            val tokens =
                f.tokens.takeIf { ts ->
                    ts.all {
                        it.charEnd <= f.text.length &&
                            f.text.substring(it.charStart, it.charEnd) == it.text
                    }
                }
                    ?: QuoteScanner.tokens(f.text, AnalyzeResponse.getDefaultInstance())
            val nlp =
                object : NlpClient {
                    override suspend fun analyze(request: AnalyzeRequest): AnalyzeResponse =
                        AnalyzeResponse
                            .newBuilder()
                            .setLanguage(
                                f.locale.substringBefore('-'),
                            ).setDetectedLanguage(
                                f.locale.substringBefore('-'),
                            ).addAllEntities(entities)
                            .addAllTokens(tokens)
                            .build()

                    override suspend fun getStatus(): StatusResponse =
                        StatusResponse
                            .newBuilder()
                            .setReady(true)
                            .also {
                                if (nerServed) {
                                    it.addCapabilities(
                                        Capability
                                            .newBuilder()
                                            .setLanguage(
                                                f.locale.substringBefore('-'),
                                            ).setOp(NlpOp.NER),
                                    )
                                }
                            }.build()
                }
            val fuzzy =
                object : FuzzyClient {
                    override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse =
                        BatchMatchResponse
                            .newBuilder()
                            .also {
                                repeat(
                                    request.spansCount,
                                ) { _ -> it.addResults(FuzzyMatchResponse.getDefaultInstance()) }
                            }.build()

                    override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()
                }
            val pipeline =
                ResolverPipeline(
                    nlp,
                    fuzzy,
                    SnapshotRegistry(StubRegistrySource(DeclaredVocabulary(), "scan-fixture"), ResolverThresholds.LIVE),
                    emptyMap(),
                    ResumeTokenCodec(
                        mapOf(
                            "k1" to
                                ByteArray(32) {
                                    it.toByte()
                                },
                        ),
                        "k1",
                    ),
                    grounding = kernel,
                    defaultPackage = "hartland",
                )
            return runBlocking {
                pipeline.resolve(
                    ResolveRequest
                        .newBuilder()
                        .setConversationId("scan-seam")
                        .setFresh(FreshQuestion.newBuilder().setText(f.text).setLocale(f.locale))
                        .setContext(
                            ResolveContext
                                .newBuilder()
                                .setReferenceDatetime(
                                    REFERENCE,
                                ).setPackage(pkg)
                                .addAllPriorQuestions(PRIOR),
                        ).build(),
                )
            }
        }
    }
}
