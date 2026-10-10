// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.grounding.v1.GetStatusResponse
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import org.tatrman.grounding.v1.GroundingResult
import org.tatrman.grounding.v1.Normalized
import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Capability
import org.tatrman.nlp.v1.NerEntity
import org.tatrman.nlp.v1.NlpOp
import org.tatrman.nlp.v1.StatusResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.client.GroundingClient
import org.tatrman.resolver.client.NlpClient
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.RelativePeriods
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.ResolveContext
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ValueKind

/**
 * A period stated relative to today is typed a date, so chrono grounds it.
 *
 * The live shape (hartland CZ world, 2026-10-10): „Tržby z tržiště za minulý měsíc pro Brno DC“.
 * NameTag typed nothing in *minulý měsíc*, so the words became an unbound FILTER mention (G1), the
 * ladder carried the gap as a note, and the fast path answered Brno DC's all-time total.
 */
class RelativePeriodsTest :
    StringSpec({

        // ── which words are a relative period ─────────────────────────────────────────────────

        fun found(q: String): List<String> = RelativePeriods.find(q).map { (s, e) -> q.substring(s, e) }

        "a scope word beside a period noun, in the case the sentence puts it" {
            found("Tržby z tržiště za minulý měsíc pro Brno DC") shouldContainExactly listOf("minulý měsíc")
            found("Kolik jsme prodali v minulém měsíci?") shouldContainExactly listOf("minulém měsíci")
            found("Tržby z webu tohoto měsíce") shouldContainExactly listOf("tohoto měsíce")
            found("Vratky v tomto roce") shouldContainExactly listOf("tomto roce")
            found("Tržby tento týden") shouldContainExactly listOf("tento týden")
            found("Tržby za předminulý měsíc") shouldContainExactly listOf("předminulý měsíc")
            found("Tržby za letošní rok") shouldContainExactly listOf("letošní rok")
        }

        "a year said in one word — adverb or adjective" {
            found("Jak se vyvíjely tržby z tržiště letos po měsících?") shouldContainExactly listOf("letos")
            found("loňské tržby z tržiště") shouldContainExactly listOf("loňské")
            found("Tržby v minulém roce a předloni") shouldContainExactly listOf("minulém roce", "předloni")
            found("Kolik vratek bylo vloni?") shouldContainExactly listOf("vloni")
        }

        "without diacritics, and in capitals" {
            found("trzby z trziste za minuly mesic") shouldContainExactly listOf("minuly mesic")
            found("TRŽBY ZA MINULÝ MĚSÍC") shouldContainExactly listOf("MINULÝ MĚSÍC")
        }

        "en, where NER did not type it" {
            found("Marketplace revenue last month for Brno DC") shouldContainExactly listOf("last month")
        }

        "a bare period noun is a breakdown or a trigger word, never a period" {
            found("Tržby z tržiště v roce 2025 po měsících").shouldBeEmpty()
            found("Tržby z tržiště po čtvrtletích").shouldBeEmpty()
        }

        "„poslední měsíc“ is left alone — it is as often the trailing thirty days" {
            found("Tržby za poslední měsíc").shouldBeEmpty()
        }

        "a scope word with no period noun after it is not a period" {
            found("Ukaž minulé objednávky").shouldBeEmpty()
            found("Tento produkt se neprodává").shouldBeEmpty()
        }

        // ── into the parse ────────────────────────────────────────────────────────────────────

        "added as a DATE entity, in span order beside NER's own" {
            val q = "Tržby z tržiště za minulý měsíc pro Brno DC"
            val brno = entity(q, "Brno DC", "LOCATION", "cnec:gu", "nametag3")
            val out = RelativePeriods.augment(AnalyzeResponse.newBuilder().addEntities(brno).build(), q)
            out.entitiesList.map { it.text } shouldContainExactly listOf("minulý měsíc", "Brno DC")
            val added = out.entitiesList.first()
            added.label shouldBe "DATE"
            added.sourceEngine shouldBe RelativePeriods.SOURCE_ENGINE
            added.charStart shouldBe q.indexOf("minulý")
            added.charEnd shouldBe q.indexOf(" pro")
        }

        "a span NER already typed is not typed twice" {
            val q = "Tržby z tržiště za minulý měsíc"
            val ner = entity(q, "minulý měsíc", "DATE", "cnec:T", "nametag3")
            val parse = AnalyzeResponse.newBuilder().addEntities(ner).build()
            RelativePeriods.augment(parse, q) shouldBe parse
        }

        // ── through the real pipeline ─────────────────────────────────────────────────────────

        "the kernel is asked about „minulý měsíc“, and the words leave no unbound FILTER gap" {
            val kernel = AnsweringKernel()
            val state = resolve(R1, r1Tokens(), kernel, nerServed = true)

            kernel.seen.map { it.spanText } shouldContainExactly listOf("minulý měsíc")
            kernel.seen
                .single()
                .context.referenceDatetime shouldBe "2026-10-10T00:00:00Z"

            val value = state.valuesList.single { it.span.text == "minulý měsíc" }
            value.kind shouldBe ValueKind.VALUE_KIND_GROUNDED
            value.grounding.normalizedValue shouldBe "2026-09-01T00:00:00Z/2026-10-01T00:00:00Z"
            value.attributionsList.map { it.attributeRef } shouldContainExactly listOf("er.entity.date_dim.cal_date")

            state.mentionsList.filter { overlapsMinulyMesic(it.span.start, it.span.end) }.shouldBeEmpty()
            state.gapsList.filter { overlapsMinulyMesic(it.span.start, it.span.end) }.shouldBeEmpty()
        }

        "without NER the parse is left as it came — a span taken from the mentions would reach nothing" {
            val kernel = AnsweringKernel()
            val state = resolve(R1, r1Tokens(), kernel, nerServed = false)
            kernel.seen.shouldBeEmpty()
            state.valuesList.filter { it.span.text == "minulý měsíc" }.shouldBeEmpty()
        }
    }) {
    private companion object {
        const val R1 = "Tržby z tržiště za minulý měsíc pro Brno DC"

        fun overlapsMinulyMesic(
            start: Int,
            end: Int,
        ): Boolean {
            val s = R1.indexOf("minulý")
            val e = s + "minulý měsíc".length
            return start < e && s < end
        }

        fun entity(
            q: String,
            text: String,
            label: String,
            code: String,
            engine: String,
        ): NerEntity {
            val at = q.indexOf(text)
            check(at >= 0) { "'$text' is not in '$q'" }
            return NerEntity
                .newBuilder()
                .setText(text)
                .setLabel(label)
                .setNormalizedValue(code)
                .setCharStart(at)
                .setCharEnd(at + text.length)
                .setSourceEngine(engine)
                .build()
        }

        /** The MorphoDiTa + UDPipe shape of [R1]: `měsíc` is an `nmod` of `Tržby`, `minulý` its `amod`. */
        fun r1Tokens(): List<Token> {
            fun tok(
                text: String,
                lemma: String,
                upos: String,
                head: Int,
                rel: String,
            ): Token {
                val at = R1.indexOf(text)
                return MhMembers.tok(text, at, at + text.length, lemma, upos, head, rel)
            }
            return listOf(
                tok("Tržby", "tržba", "NOUN", 0, "root"),
                tok("z", "z", "ADP", 3, "case"),
                tok("tržiště", "tržiště", "NOUN", 1, "nmod"),
                tok("za", "za", "ADP", 6, "case"),
                tok("minulý", "minulý", "ADJ", 6, "amod"),
                tok("měsíc", "měsíc", "NOUN", 1, "nmod"),
                tok("pro", "pro", "ADP", 8, "case"),
                tok("Brno", "Brno", "PROPN", 1, "nmod"),
                tok("DC", "DC", "PROPN", 8, "flat"),
            )
        }

        fun resolve(
            q: String,
            tokens: List<Token>,
            kernel: GroundingClient,
            nerServed: Boolean,
        ): ResolutionState {
            val pipeline =
                ResolverPipeline(
                    object : NlpClient {
                        override suspend fun analyze(request: AnalyzeRequest): AnalyzeResponse =
                            AnalyzeResponse
                                .newBuilder()
                                .setLanguage("cs")
                                .setDetectedLanguage("cs")
                                .addAllTokens(tokens)
                                .build()

                        override suspend fun getStatus(): StatusResponse {
                            val status = StatusResponse.newBuilder().setReady(true)
                            if (nerServed) {
                                status.addCapabilities(
                                    Capability
                                        .newBuilder()
                                        .setLanguage("cs")
                                        .setOp(NlpOp.NER)
                                        .setEngine("nametag3"),
                                )
                            }
                            return status.build()
                        }
                    },
                    NoMatches,
                    SnapshotRegistry(
                        StubRegistrySource(DeclaredVocabulary(), "snap-relative"),
                        ResolverThresholds.LIVE,
                    ),
                    emptyMap(),
                    ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), "k1"),
                    grounding = kernel,
                    defaultPackage = "hartland",
                )
            return runBlocking {
                pipeline
                    .resolve(
                        ResolveRequest
                            .newBuilder()
                            .setConversationId("relative-periods")
                            .setFresh(FreshQuestion.newBuilder().setText(q).setLocale("cs"))
                            .setContext(ResolveContext.newBuilder().setReferenceDatetime("2026-10-10T00:00:00Z"))
                            .build(),
                    ).resolutionState
            }
        }

        object NoMatches : FuzzyClient {
            override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse {
                val builder = BatchMatchResponse.newBuilder()
                repeat(request.spansCount) { builder.addResults(FuzzyMatchResponse.getDefaultInstance()) }
                return builder.build()
            }

            override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()
        }

        /** chrono's answer for *minulý měsíc* at 2026-10-10: September 2026 on the calendar's event date. */
        class AnsweringKernel : GroundingClient {
            val seen = mutableListOf<GroundRequest>()

            override suspend fun ground(request: GroundRequest): GroundResponse {
                seen += request
                return GroundResponse
                    .newBuilder()
                    .setStatus(GroundResponse.Status.OK)
                    .setResult(
                        GroundingResult
                            .newBuilder()
                            .setNormalized(
                                Normalized
                                    .newBuilder()
                                    .setInterval(
                                        org.tatrman.grounding.v1.DateTimeInterval
                                            .newBuilder()
                                            .setStart("2026-09-01T00:00:00Z")
                                            .setEnd("2026-10-01T00:00:00Z"),
                                    ),
                            ).setFilter(
                                org.tatrman.grounding.v1.FilterRecipe
                                    .newBuilder()
                                    .setAnchorColumn(
                                        org.tatrman.plan.v1.QualifiedName
                                            .newBuilder()
                                            .setSchemaCode(org.tatrman.plan.v1.SchemaCode.ER)
                                            .setNamespace("entity")
                                            .setName("date_dim.cal_date"),
                                    ),
                            ),
                    ).build()
            }

            override suspend fun getStatus(): GetStatusResponse = GetStatusResponse.getDefaultInstance()
        }
    }
}
