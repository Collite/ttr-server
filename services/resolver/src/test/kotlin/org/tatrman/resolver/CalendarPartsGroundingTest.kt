// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.grounding.v1.GetStatusResponse
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.NerEntity
import org.tatrman.nlp.v1.StatusResponse
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.client.GroundingClient
import org.tatrman.resolver.client.NlpClient
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveContext

/**
 * The live shape (hartland, 2026-10-10): NameTag typed „v říjnu 2025“ as a month (`cnec:tm`) and a
 * year (`cnec:ty`), the resolver grounded each alone, and chrono read the bare month against the
 * reference date — October of the current year — beside a separate whole 2025. Asserted on what
 * the grounding kernel RECEIVES, through the real pipeline: the join only helps if the question's
 * text reaches it there.
 */
class CalendarPartsGroundingTest :
    StringSpec({

        "the pipeline grounds „říjnu 2025“ as one span, not a month and a year" {
            val q = "Které položky tam byly v říjnu 2025 vyprodané?"
            groundedSpans(q, date(q, "říjnu", "cnec:tm"), date(q, "2025", "cnec:ty")) shouldContainExactly
                listOf("říjnu 2025")
        }

        "a date NameTag joined itself reaches the kernel unchanged" {
            val q = "Vypiš všechny objednávky z tržiště z prosince 2025"
            groundedSpans(q, date(q, "prosince 2025", "cnec:T|B-tm")) shouldContainExactly listOf("prosince 2025")
        }

        "two years stay two groundings" {
            val q = "Porovnej tržby v letech 2024 a 2025"
            groundedSpans(q, date(q, "2024", "cnec:ty"), date(q, "2025", "cnec:ty")) shouldContainExactly
                listOf("2024", "2025")
        }
    }) {
    private companion object {
        fun date(
            q: String,
            text: String,
            code: String,
        ): NerEntity {
            val at = q.indexOf(text)
            check(at >= 0) { "'$text' is not in '$q'" }
            return NerEntity
                .newBuilder()
                .setText(text)
                .setCharStart(at)
                .setCharEnd(at + text.length)
                .setLabel("DATE")
                .setNormalizedValue(code)
                .setSourceEngine("nametag3")
                .build()
        }

        /** Resolve [q] over a parse holding only [entities]; return the span texts grounding was asked for. */
        fun groundedSpans(
            q: String,
            vararg entities: NerEntity,
        ): List<String> {
            val kernel = RecordingKernel()
            val pipeline =
                ResolverPipeline(
                    object : NlpClient {
                        override suspend fun analyze(request: AnalyzeRequest): AnalyzeResponse =
                            AnalyzeResponse
                                .newBuilder()
                                .setLanguage("cs")
                                .setDetectedLanguage("cs")
                                .addAllEntities(entities.toList())
                                .build()

                        override suspend fun getStatus(): StatusResponse =
                            StatusResponse.newBuilder().setReady(true).build()
                    },
                    NoMatches,
                    SnapshotRegistry(
                        StubRegistrySource(DeclaredVocabulary(), "snap-calendar"),
                        ResolverThresholds.LIVE,
                    ),
                    emptyMap(),
                    ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), "k1"),
                    grounding = kernel,
                    defaultPackage = "hartland",
                )
            runBlocking {
                pipeline.resolve(
                    ResolveRequest
                        .newBuilder()
                        .setConversationId("calendar-parts")
                        .setFresh(FreshQuestion.newBuilder().setText(q).setLocale("cs"))
                        .setContext(ResolveContext.newBuilder().setReferenceDatetime("2026-10-10T00:00:00Z"))
                        .build(),
                )
            }
            return kernel.seen.map { it.spanText }
        }

        object NoMatches : FuzzyClient {
            override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse {
                val builder = BatchMatchResponse.newBuilder()
                repeat(request.spansCount) { builder.addResults(FuzzyMatchResponse.getDefaultInstance()) }
                return builder.build()
            }

            override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()
        }

        class RecordingKernel : GroundingClient {
            val seen = mutableListOf<GroundRequest>()

            override suspend fun ground(request: GroundRequest): GroundResponse {
                seen += request
                return GroundResponse.newBuilder().setStatus(GroundResponse.Status.UNGROUNDABLE).build()
            }

            override suspend fun getStatus(): GetStatusResponse = GetStatusResponse.getDefaultInstance()
        }
    }
}
