// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.grounding.v1.DateTimeInterval
import org.tatrman.grounding.v1.EntityKind
import org.tatrman.grounding.v1.FilterRecipe
import org.tatrman.grounding.v1.GetStatusResponse
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import org.tatrman.grounding.v1.GroundingContext
import org.tatrman.grounding.v1.GroundingResult
import org.tatrman.grounding.v1.Normalized
import org.tatrman.grounding.v1.ScanResponse
import org.tatrman.grounding.v1.TimeSpan
import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Capability
import org.tatrman.nlp.v1.NerEntity
import org.tatrman.nlp.v1.NlpOp
import org.tatrman.nlp.v1.StatusResponse
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.client.GroundingClient
import org.tatrman.resolver.client.NlpClient
import org.tatrman.resolver.client.ScanCapability
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.pipeline.TimeScan
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * OL W1a·S1 — the `scan` capability (contracts OL-C1, OL-C2). The resolver calls `Scan` only while the grounder's
 * `GetStatus` says `capabilities["scan"] = "true"`; otherwise it is silent: no call, no `scan_failed`, no per-turn log.
 */
class ScanCapabilityTest :
    StringSpec({

        // ----- (a) a grounder without `scan` -----

        "(a) a grounder without scan: Scan is never called, no scan_failed, nothing logged per turn" {
            val kernel = Kernel(status = GetStatusResponse.getDefaultInstance())
            val pipeline = pipeline(kernel)
            val logs = capture(ScanCapability::class.java.name, TimeScan::class.java.name)
            val warnings = capture("org.tatrman.resolver", level = Level.WARN)
            try {
                val turns = (1..3).map { resolve(pipeline) to logs.list.size }
                kernel.scans shouldBe 0
                turns.forEach { (out, _) ->
                    out.resolutionState.rungLogList.none { it.action == "scan_failed" } shouldBe true
                    out.parse.entitiesList.none { it.sourceEngine == "chrono.scan" } shouldBe true
                }
                // the first turn learns the state (one INFO line); the later turns add nothing
                turns.map { it.second } shouldContainExactly listOf(1, 1, 1)
                logs.list.single().level shouldBe Level.INFO
                logs.list.single().formattedMessage shouldContain "not available"
                warnings.list.shouldBeEmpty()
            } finally {
                logs.detach()
                warnings.detach()
            }
        }

        for (value in listOf("false", "TRUE", "1", "")) {
            "(a) capabilities[scan] = \"$value\" is not the key: Scan is not called" {
                val kernel = Kernel(status = status(value))
                val out = resolve(pipeline(kernel))
                kernel.scans shouldBe 0
                out.resolutionState.rungLogList.none { it.action == "scan_failed" } shouldBe true
            }
        }

        // ----- (b) a grounder with `scan` -----

        "(b) a grounder with scan=true: today's behaviour — Scan once per turn, its span added and grounded" {
            val kernel = Kernel(status = status("true"))
            val out = resolve(pipeline(kernel))
            kernel.scans shouldBe 1
            out.parse.entitiesList
                .filter { it.sourceEngine == "chrono.scan" }
                .map { it.text } shouldContainExactly listOf(PHRASE)
            kernel.grounds shouldContainExactly listOf(PHRASE)
            out.resolutionState.rungLogList.none { it.action == "scan_failed" } shouldBe true
        }

        "(b) with scan=true a failed Scan still records scan_failed" {
            val kernel = Kernel(status = status("true"), scanFails = true)
            val out = resolve(pipeline(kernel))
            kernel.scans shouldBe 1
            out.resolutionState.rungLogList.count { it.action == "scan_failed" } shouldBe 1
        }

        // ----- (c) GetStatus fails -----

        "(c) GetStatus fails: no scan until the TTL passes, then it is read again" {
            var now = 0L
            var reads = 0
            var fail = true
            val capability =
                ScanCapability(60.seconds, nowNanos = { now }) {
                    reads++
                    if (fail) error("UNAVAILABLE") else status("true")
                }
            runBlocking {
                capability.available() shouldBe false
                fail = false
                now += 59.seconds.inWholeNanoseconds
                capability.available() shouldBe false
                reads shouldBe 1
                now += 1.seconds.inWholeNanoseconds
                capability.available() shouldBe true
                reads shouldBe 2
                capability.available() shouldBe true
                reads shouldBe 2
            }
        }

        "(c) a GetStatus slower than the timeout counts as failed" {
            val capability =
                ScanCapability(60.seconds, timeout = 50.milliseconds) {
                    delay(5_000)
                    status("true")
                }
            runBlocking { capability.available() shouldBe false }
        }

        "(c) through the pipeline: a failing GetStatus means no Scan and no scan_failed" {
            val kernel = Kernel(status = null)
            val out = resolve(pipeline(kernel))
            kernel.scans shouldBe 0
            out.resolutionState.rungLogList.none { it.action == "scan_failed" } shouldBe true
        }

        // ----- (d) one INFO line per change of the known state -----

        "(d) one INFO line when the known state changes, none while it holds" {
            var now = 0L
            var answer: GetStatusResponse? = GetStatusResponse.getDefaultInstance()
            val capability =
                ScanCapability(60.seconds, nowNanos = { now }) { answer ?: error("UNAVAILABLE") }
            val logs = capture(ScanCapability::class.java.name)
            try {
                runBlocking {
                    fun tick() {
                        now += 60.seconds.inWholeNanoseconds
                    }
                    capability.available() // unknown → no scan
                    tick()
                    capability.available() // no scan → no scan
                    answer = status("true")
                    tick()
                    capability.available() // → scan
                    tick()
                    capability.available() // scan → scan
                    answer = null
                    tick()
                    capability.available() // failed → no scan
                    tick()
                    capability.available() // failed again → no scan
                }
                logs.list.map { it.level }.toSet() shouldBe setOf(Level.INFO)
                logs.list shouldHaveSize 3
                logs.list[0].formattedMessage shouldContain "not available"
                logs.list[1].formattedMessage shouldStartWith "grounder scan capability: available"
                logs.list[2].formattedMessage shouldContain "GetStatus failed"
            } finally {
                logs.detach()
            }
        }

        // ----- T5: the resolver over a grounder that only grounds -----

        "T5 over a GroundOnlyKernel a year is grounded through Ground, with no Scan and no warning" {
            val kernel = YearOnly()
            val q = "Tržby v roce 2025"
            val year =
                NerEntity
                    .newBuilder()
                    .setText("2025")
                    .setLabel("DATE")
                    .setNormalizedValue("cnec:ty")
                    .setSourceEngine("nametag3")
                    .setCharStart(q.indexOf("2025"))
                    .setCharEnd(q.indexOf("2025") + 4)
                    .build()
            val warnings = capture("org.tatrman.resolver", level = Level.WARN)
            try {
                val out = resolve(pipeline(kernel, listOf(year)), q)
                kernel.scans shouldBe 0
                kernel.grounds shouldContainExactly listOf("2025")
                // The result, not only the call: an UNGROUNDABLE answer would pass the lines above.
                out.resolution.bindingsList
                    .single { it.hasUniversal() }
                    .universal.entityType shouldBe UniversalEntityType.DATE
                val value = out.resolutionState.valuesList.single { it.span.text == "2025" }
                value.kind shouldBe ValueKind.VALUE_KIND_GROUNDED
                value.grounding.normalizedValue shouldBe "2025-01-01T00:00:00Z/2026-01-01T00:00:00Z"
                value.attributionsList.map { it.attributeRef } shouldContainExactly
                    listOf("er.entity.date_dim.cal_date")
                out.resolutionState.rungLogList.none { it.action == "scan_failed" } shouldBe true
                warnings.list.shouldBeEmpty()
            } finally {
                warnings.detach()
            }
        }
    }) {
    private companion object {
        const val TEXT = "Tržby za minulý měsíc"
        const val PHRASE = "minulý měsíc"

        fun status(scan: String): GetStatusResponse =
            GetStatusResponse
                .newBuilder()
                .setService("chrono")
                .putCapabilities("scan", scan)
                .build()

        /** A grounder over [TEXT]: Scan finds [PHRASE]; `status = null` makes GetStatus fail. */
        class Kernel(
            private val status: GetStatusResponse?,
            private val scanFails: Boolean = false,
        ) : GroundingClient {
            var scans = 0
            val grounds = mutableListOf<String>()

            override suspend fun scan(
                text: String,
                context: GroundingContext,
                pkg: String,
            ): ScanResponse {
                scans++
                if (scanFails) error("UNAVAILABLE")
                val start = text.indexOf(PHRASE)
                return ScanResponse
                    .newBuilder()
                    .addSpans(
                        TimeSpan
                            .newBuilder()
                            .setStart(start)
                            .setEnd(start + PHRASE.length)
                            .setText(PHRASE)
                            .setKind(EntityKind.DATE_TIME),
                    ).build()
            }

            override suspend fun ground(request: GroundRequest): GroundResponse {
                grounds += request.spanText
                return GroundResponse.newBuilder().setStatus(GroundResponse.Status.UNGROUNDABLE).build()
            }

            override suspend fun getStatus(): GetStatusResponse = status ?: error("UNAVAILABLE")
        }

        /** The existing ground-only fixture shape: chrono on a four-digit year, as live; it declares nothing. */
        class YearOnly : GroundOnlyKernel {
            var scans = 0
            val grounds = mutableListOf<String>()

            override suspend fun scan(
                text: String,
                context: GroundingContext,
                pkg: String,
            ): ScanResponse {
                scans++
                return super.scan(text, context, pkg)
            }

            override suspend fun ground(request: GroundRequest): GroundResponse {
                grounds += request.spanText
                val year =
                    request.spanText.trim().toIntOrNull()
                        ?: return GroundResponse.newBuilder().setStatus(GroundResponse.Status.UNGROUNDABLE).build()
                return GroundResponse
                    .newBuilder()
                    .setStatus(GroundResponse.Status.OK)
                    .setResult(
                        GroundingResult
                            .newBuilder()
                            .setNormalized(
                                Normalized.newBuilder().setInterval(
                                    DateTimeInterval
                                        .newBuilder()
                                        .setStart("$year-01-01T00:00:00Z")
                                        .setEnd("${year + 1}-01-01T00:00:00Z"),
                                ),
                            ).setFilter(
                                FilterRecipe.newBuilder().setAnchorColumn(
                                    QualifiedName
                                        .newBuilder()
                                        .setPackage("hartland")
                                        .setSchemaCode(SchemaCode.ER)
                                        .setNamespace("entity")
                                        .setName("date_dim.cal_date"),
                                ),
                            ),
                    ).build()
            }

            override suspend fun getStatus(): GetStatusResponse = GetStatusResponse.getDefaultInstance()
        }

        fun pipeline(
            kernel: GroundingClient,
            entities: List<NerEntity> = emptyList(),
        ): ResolverPipeline =
            ResolverPipeline(
                object : NlpClient {
                    override suspend fun analyze(request: AnalyzeRequest): AnalyzeResponse =
                        AnalyzeResponse
                            .newBuilder()
                            .setLanguage("cs")
                            .setDetectedLanguage("cs")
                            .addAllEntities(entities)
                            .build()

                    override suspend fun getStatus(): StatusResponse =
                        StatusResponse
                            .newBuilder()
                            .setReady(true)
                            .addCapabilities(Capability.newBuilder().setLanguage("cs").setOp(NlpOp.NER))
                            .build()
                },
                object : FuzzyClient {
                    override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse {
                        val builder = BatchMatchResponse.newBuilder()
                        repeat(request.spansCount) { builder.addResults(FuzzyMatchResponse.getDefaultInstance()) }
                        return builder.build()
                    }

                    override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()
                },
                SnapshotRegistry(StubRegistrySource(DeclaredVocabulary(), "scan-capability"), ResolverThresholds.LIVE),
                emptyMap(),
                ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), "k1"),
                grounding = kernel,
                defaultPackage = "hartland",
            )

        fun resolve(
            pipeline: ResolverPipeline,
            text: String = TEXT,
        ): ResolveResponse =
            runBlocking {
                pipeline.resolve(
                    ResolveRequest
                        .newBuilder()
                        .setConversationId("scan-capability")
                        .setFresh(FreshQuestion.newBuilder().setText(text).setLocale("cs"))
                        .setContext(ResolveContext.newBuilder().setReferenceDatetime("2026-11-16T00:00:00Z"))
                        .build(),
                )
            }

        class Captured(
            private val appender: ListAppender<ILoggingEvent>,
            private val loggers: List<Pair<Logger, Level?>>,
            private val level: Level,
        ) {
            // A child logger's events reach this appender whatever the parent's level is (additivity) — filter here.
            val list: List<ILoggingEvent> get() = appender.list.filter { it.level.isGreaterOrEqual(level) }

            fun detach() =
                loggers.forEach { (logger, previous) ->
                    logger.detachAppender(appender)
                    logger.level = previous
                }
        }

        /** Capture [names]' events at [level] and above; the loggers are set to [level] meanwhile. */
        fun capture(
            vararg names: String,
            level: Level = Level.INFO,
        ): Captured {
            val ctx = LoggerFactory.getILoggerFactory() as LoggerContext
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val loggers =
                names.map { name ->
                    val logger = ctx.getLogger(name)
                    (logger to logger.level).also {
                        logger.level = level
                        logger.addAppender(appender)
                    }
                }
            return Captured(appender, loggers, level)
        }
    }
}
