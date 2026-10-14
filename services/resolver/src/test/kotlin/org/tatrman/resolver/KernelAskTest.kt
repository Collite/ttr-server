// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.grpc.Status
import io.grpc.StatusException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.grounding.v1.ClarificationOption
import org.tatrman.grounding.v1.DateTimeInterval
import org.tatrman.grounding.v1.FilterRecipe
import org.tatrman.grounding.v1.GetStatusResponse
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import org.tatrman.grounding.v1.GroundingResult
import org.tatrman.grounding.v1.Normalized
import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.NerEntity
import org.tatrman.nlp.v1.StatusResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.client.GroundingClient
import org.tatrman.resolver.client.NlpClient
import org.tatrman.resolver.grpc.ResolverGrpcService
import org.tatrman.resolver.mcp.ResolveDoor
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.GroundingRung
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.pipeline.UniversalBinding
import org.tatrman.resolver.registry.DeclaredValue
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.DeclaredVocabularyEntry
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeOption
import org.tatrman.resolver.token.ResumePayload
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.AwaitingClarification
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.Option
import org.tatrman.resolver.v1.ResolveContext
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.resolver.v1.ResumeAnswer
import org.tatrman.resolver.v1.Span
import org.tatrman.resolver.v1.UniversalEntityType
import org.tatrman.resolver.v1.ValueKind
import java.util.Base64

/**
 * ttr-server#231 — **a grounding kernel's question reaches the user.**
 *
 * chrono answers `AWAITING_CLARIFICATION` when a date has two readings: a bare future month
 * („v listopadu“ in October is November of this year or of last), and since ttr-server#214 the
 * ambiguous „minulý rok / měsíc / čtvrtletí“. The resolver used to drop that question as "not
 * grounded", so a caller got a date with no range and nothing to ask, and could only guess.
 *
 * Now the kernel's question becomes the resolver's OWN `awaiting`, under its own signed resume
 * token — one HITL protocol per turn — and a pick is grounded on resume through the kernel's OK
 * path, so the value carries the interval AND the anchor attribution exactly as an unambiguous
 * date does.
 *
 * Hermetic: the real pipeline over in-memory nlp / fuzzy / kernel fakes.
 */
class KernelAskTest :
    StringSpec({

        "a kernel's AWAITING_CLARIFICATION becomes the resolver's awaiting, one option per kernel option" {
            val kernel = FakeChrono()
            val response = resolveFresh(pipeline(kernel), MONTH_Q)

            withClue("the question is asked, so the resolve is not a resolution") {
                response.hasAwaiting() shouldBe true
                response.hasResolution() shouldBe false
            }
            val options = response.awaiting.optionsList
            options.map { it.id } shouldContainExactly listOf("P:202611", "P:202511")
            withClue("labels pass through as the kernel wrote them") {
                options.map { it.label } shouldContainExactly listOf("listopad 2026", "listopad 2025")
            }
            val span = MONTH_Q.indexOf("listopadu")
            for (o in options) {
                withClue("each option names the span it answers") {
                    o.span shouldBe span(span, span + "listopadu".length, "listopadu")
                }
                o.objectKind shouldBe "period"
                // A period is not a member or a vocabulary target: nothing a member-pick path could
                // turn into an equality filter.
                o.targetRef shouldBe ""
                o.resolvedId shouldBe ""
                o.memberOf shouldBe ""
                o.grounding.kernel shouldBe "chrono"
                o.grounding.kind shouldBe "DATE"
            }
            withClue("each option keeps the kernel's interval") {
                options.map { it.grounding.normalizedValue } shouldContainExactly
                    listOf(NOV_2026, NOV_2025)
            }
            (response.awaiting.resumeToken.isNotBlank()) shouldBe true

            withClue("the lattice records the same fact as a G2 on the value the kernel asked about") {
                val value = response.resolutionState.valuesList.single { it.span.text == "listopadu" }
                value.kind shouldBe ValueKind.VALUE_KIND_GROUNDED
                val gap = response.resolutionState.gapsList.single { it.valueId == value.id }
                gap.kind shouldBe GapKind.GAP_KIND_G2_AMBIGUOUS
                gap.span.text shouldBe "listopadu"
            }
        }

        "resume grounds the picked option through the kernel's OK path — interval and anchor — and does not ask again" {
            val kernel = FakeChrono()
            val pipeline = pipeline(kernel)
            val asked = resolveFresh(pipeline, MONTH_Q, prior = listOf("Tržby z tržiště v roce 2025"))
            val askedRequest = kernel.seen.single()

            // The caller resumes LATER and from further on in the conversation: a newer reference date,
            // and prior questions that now include the question that was asked. Neither may reach the
            // kernel — chrono re-reads the span on resume, so it must read it exactly as it did when it
            // asked, or the id it is handed may name a different period (or none).
            val resumed =
                runBlocking {
                    pipeline.resolve(
                        resume(
                            asked.awaiting.resumeToken,
                            "P:202511",
                            reference = "2026-11-02T09:00:00Z",
                            prior = listOf(MONTH_Q, "Tržby z tržiště v roce 2025"),
                        ),
                    )
                }

            val pick = kernel.seen.drop(1).single()
            pick.clarificationAnswerId shouldBe "202511"
            withClue(
                "the resume call is the asking call plus the answer id — same span, question, conversation and date",
            ) {
                pick.toBuilder().clearClarificationAnswerId().build() shouldBe askedRequest
            }

            withClue("answered, so nothing is asked again") {
                resumed.hasAwaiting() shouldBe false
                resumed.resolutionState.gapsList.shouldBeEmpty()
            }
            val start = MONTH_Q.indexOf("listopadu")
            val binding = resumed.resolution.bindingsList.single()
            binding.span shouldBe span(start, start + "listopadu".length, "listopadu")
            binding.universal.entityType shouldBe UniversalEntityType.DATE
            binding.universal.normalizedValue shouldBe NOV_2025
            binding.universal.sourceEngine shouldBe "chrono"

            withClue("the value has the shape an OK grounding gives it in a fresh lattice") {
                val value = resumed.resolutionState.valuesList.single()
                value.span shouldBe binding.span
                value.kind shouldBe ValueKind.VALUE_KIND_GROUNDED
                value.grounding.kernel shouldBe "chrono"
                value.grounding.kind shouldBe "DATE"
                value.grounding.normalizedValue shouldBe NOV_2025
                value.attributionsList.map { it.attributeRef } shouldContainExactly
                    listOf("er.entity.date_dim.cal_date")
            }
        }

        "a pick the kernel will not ground is refused, never asked again and never answered without it" {
            // A kernel that asks AGAIN on resume (or does not know the id any more) cannot ground the
            // user's answer. A second question would loop; a resolution without the period would answer
            // a wider question than the one asked. A FAILED_PRECONDITION is the honest end.
            for (answer in listOf(FakeChrono.Answer.ASK_AGAIN, FakeChrono.Answer.UNGROUNDABLE)) {
                val kernel = FakeChrono()
                val pipeline = pipeline(kernel)
                val asked = resolveFresh(pipeline, MONTH_Q)
                kernel.onPick = answer
                val error =
                    shouldThrow<StatusException> {
                        runBlocking {
                            ResolverGrpcService(pipeline).resolve(resume(asked.awaiting.resumeToken, "P:202511"))
                        }
                    }
                error.status.code shouldBe Status.Code.FAILED_PRECONDITION
                error.status.description.orEmpty() shouldContain "listopadu"
            }
        }

        "a kernel that cannot be reached on resume is UNAVAILABLE, so the caller may retry" {
            val kernel = FakeChrono()
            val pipeline = pipeline(kernel)
            val asked = resolveFresh(pipeline, MONTH_Q)
            kernel.onPick = FakeChrono.Answer.THROW
            val error =
                shouldThrow<StatusException> {
                    runBlocking {
                        ResolverGrpcService(
                            pipeline,
                        ).resolve(resume(asked.awaiting.resumeToken, "P:202511"))
                    }
                }
            error.status.code shouldBe Status.Code.UNAVAILABLE
        }

        "when the gate also asks, the gate's question comes first and the kernel's is not lost on resume" {
            val kernel = FakeChrono()
            val pipeline = pipeline(kernel)
            val asked = resolveFresh(pipeline, BOTH_Q)

            val options = asked.awaiting.optionsList
            withClue("one awaiting, the gate's options first (where they always were), then the kernel's") {
                options.map { it.id } shouldContainExactly
                    listOf("M:er.warehouse.member#brno-dc", "M:er.warehouse.member#praha-dc", "P:202611", "P:202511")
                options.map { it.span.text } shouldContainExactly listOf("DC", "DC", "listopadu", "listopadu")
            }
            withClue("both questions are open in the lattice") {
                asked.resolutionState.gapsList
                    .filter { it.kind == GapKind.GAP_KIND_G2_AMBIGUOUS }
                    .map { it.span.text }
                    .toSet() shouldBe setOf("DC", "listopadu")
            }

            val token = asked.awaiting.resumeToken
            val member = runBlocking { pipeline.resolve(resume(token, "M:er.warehouse.member#brno-dc")) }
            member.resolution.bindingsList
                .single()
                .domain.resolvedId shouldBe "brno-dc"
            withClue("a member pin binds from the signed option: the kernel is not asked") {
                kernel.seen.size shouldBe 1
            }

            withClue("the SAME token still redeems the kernel's question: it was signed beside the gate's") {
                val period = runBlocking { pipeline.resolve(resume(token, "P:202511")) }
                period.resolution.bindingsList
                    .single()
                    .universal.normalizedValue shouldBe NOV_2025
                period.resolutionState.valuesList
                    .single()
                    .attributionsList
                    .single()
                    .attributeRef shouldBe "er.entity.date_dim.cal_date"
            }
        }

        "the door asks one question at a time: the first span's options, then the next under the same token" {
            val awaiting =
                AwaitingClarification
                    .newBuilder()
                    .addOptions(option("M:a", "Brno DC", span(38, 40, "DC")))
                    .addOptions(option("M:b", "Praha DC", span(38, 40, "DC")))
                    .addOptions(option("P:202611", "listopad 2026", span(18, 27, "listopadu")))
                    .addOptions(option("P:202511", "listopad 2025", span(18, 27, "listopadu")))
                    .setResumeToken("tok")
                    .build()
            val door = ResolveDoor { ResolveResponse.newBuilder().setAwaiting(awaiting).build() }
            val text =
                runBlocking {
                    door.call(
                        buildJsonObject {
                            put("conversation_id", JsonPrimitive("c"))
                            put("text", JsonPrimitive(BOTH_Q))
                        },
                    )
                }.content
                    .filterIsInstance<TextContent>()
                    .single()
                    .text
            val first = text.indexOf("„DC“")
            val second = text.indexOf("„listopadu“")
            withClue(text) {
                (first in 0 until second) shouldBe true
                text shouldContain "[M:a] Brno DC"
                text shouldContain "[P:202511] listopad 2025"
                text shouldContain "same resume_token"
            }
        }

        "a token with no kernel question is byte-for-byte what an older resolver minted" {
            val codec = ResumeTokenCodec(mapOf("k1" to ByteArray(32) { 7 }), "k1")
            val token =
                codec.sign(
                    ResumePayload(
                        conversationId = "c",
                        parseRef = "p",
                        options = listOf(ResumeOption("M:x#1", "X", resolvedId = "1", entityTypeRef = "er.x")),
                        issuedAt = 1,
                        keyId = "k1",
                        subject = "maya",
                    ),
                )
            val json = String(Base64.getUrlDecoder().decode(token.substringBefore('.')), Charsets.UTF_8)
            json shouldNotContain "kernel"
        }

        "a period the kernel had nothing to read against is named on the lattice, and nothing else changes" {
            // chrono (ttr-server#214) refuses an anaphoric period with no single period of its unit in the
            // conversation — "the previous year" after a question naming two years — with the message code
            // `no_anchor`. The value stays an ungrounded date, but a consumer must not read it against today,
            // so the core names it: one round-0 `ground-unanchored` entry per such value.
            val response = resolveFresh(pipeline(refusing("no_anchor")), MONTH_Q)
            val lattice = response.resolutionState
            val value = lattice.valuesList.single { it.span.text == "listopadu" }
            val entries = lattice.rungLogList.filter { it.action == "ground-unanchored" }
            entries.size shouldBe 1
            val entry = entries.single()
            entry.round shouldBe 0
            entry.rung shouldBe "core"
            entry.valueIdsList shouldContainExactly listOf(value.id)
            entry.mentionIdsList.shouldBeEmpty()
            withClue("the value itself is what it was: a date with no range, and no gap of its own") {
                value.kind shouldBe ValueKind.VALUE_KIND_GROUNDED
                value.grounding.normalizedValue shouldNotContain "/"
                lattice.gapsList.filter { it.valueId == value.id }.shouldBeEmpty()
                response.hasAwaiting() shouldBe false
            }
        }

        "a period the kernel refused for any other reason is not named" {
            val lattice = resolveFresh(pipeline(refusing("ungroundable")), MONTH_Q).resolutionState
            lattice.rungLogList.filter { it.action == "ground-unanchored" }.shouldBeEmpty()
        }

        "an AWAITING with fewer than two options the resolver could hand back is not a question" {
            // An option with no id cannot be resumed (chrono matches the answer by id), and a
            // question with one answer is not a question. Both stay exactly what they were before:
            // an ungrounded date.
            val oneOption =
                GroundResponse
                    .newBuilder()
                    .setStatus(GroundResponse.Status.AWAITING_CLARIFICATION)
                    .addOptions(kernelOption("202611", "listopad 2026", "2026-11-01T00:00:00Z", "2026-12-01T00:00:00Z"))
                    .addOptions(kernelOption("", "?", "2025-11-01T00:00:00Z", "2025-12-01T00:00:00Z"))
                    .build()
            val kernel =
                object : GroundingClient {
                    override suspend fun ground(request: GroundRequest) = oneOption

                    override suspend fun getStatus(): GetStatusResponse = GetStatusResponse.getDefaultInstance()
                }
            val outcome =
                runBlocking {
                    GroundingRung.groundAll(
                        kernel,
                        listOf(
                            UniversalBinding(0, 9, "listopadu", UniversalEntityType.DATE, "listopadu", "", "nametag3"),
                        ),
                        "q",
                        "hartland",
                        REFERENCE,
                    )
                }
            outcome.grounded.keys.shouldBeEmpty()
            outcome.asked.keys.shouldBeEmpty()
        }
    }) {
    private companion object {
        const val MONTH_Q = "Tržby z tržiště v listopadu"
        const val BOTH_Q = "Tržby z tržiště v listopadu ve skladu DC"
        const val REFERENCE = "2026-10-14T08:48:33Z"
        const val NOV_2026 = "2026-11-01T00:00:00Z/2026-12-01T00:00:00Z"
        const val NOV_2025 = "2025-11-01T00:00:00Z/2025-12-01T00:00:00Z"

        private val VOCAB =
            DeclaredVocabulary(
                entries =
                    listOf(
                        DeclaredVocabularyEntry(
                            category = "er.warehouse.member",
                            targetRef = "er.warehouse",
                            values = listOf(DeclaredValue("brno-dc", "Brno DC"), DeclaredValue("praha-dc", "Praha DC")),
                        ),
                    ),
            )

        fun pipeline(kernel: GroundingClient): ResolverPipeline =
            ResolverPipeline(
                WordsAndOneDate,
                DcIsTwoWarehouses,
                SnapshotRegistry(StubRegistrySource(VOCAB, "snap-kernel-ask"), ResolverThresholds.LIVE),
                siblings = emptyMap(),
                tokenCodec = ResumeTokenCodec(mapOf("k1" to ByteArray(32) { (it + 3).toByte() }), "k1"),
                grounding = kernel,
                defaultPackage = "hartland",
            )

        fun resolveFresh(
            pipeline: ResolverPipeline,
            q: String,
            prior: List<String> = emptyList(),
        ): ResolveResponse =
            runBlocking {
                pipeline.resolve(
                    ResolveRequest
                        .newBuilder()
                        .setConversationId("kernel-ask")
                        .setCallerSubject("maya")
                        .setFresh(FreshQuestion.newBuilder().setText(q).setLocale("cs"))
                        .setContext(
                            ResolveContext
                                .newBuilder()
                                .setReferenceDatetime(REFERENCE)
                                .setTenant("default")
                                .addAllPriorQuestions(prior),
                        ).build(),
                )
            }

        fun resume(
            token: String,
            optionId: String,
            reference: String = REFERENCE,
            prior: List<String> = emptyList(),
        ): ResolveRequest =
            ResolveRequest
                .newBuilder()
                .setConversationId("kernel-ask")
                .setCallerSubject("maya")
                .setResume(ResumeAnswer.newBuilder().setToken(token).setSelectedOptionId(optionId))
                .setContext(ResolveContext.newBuilder().setReferenceDatetime(reference).addAllPriorQuestions(prior))
                .build()

        fun span(
            start: Int,
            end: Int,
            text: String,
        ): Span =
            Span
                .newBuilder()
                .setStart(start)
                .setEnd(end)
                .setText(text)
                .build()

        fun option(
            id: String,
            label: String,
            span: Span,
        ): Option =
            Option
                .newBuilder()
                .setId(id)
                .setLabel(label)
                .setSpan(span)
                .build()

        /** A kernel that refuses every span `UNGROUNDABLE`, with one message carrying [code]. */
        fun refusing(code: String): GroundingClient =
            object : GroundingClient {
                override suspend fun ground(request: GroundRequest): GroundResponse =
                    GroundResponse
                        .newBuilder()
                        .setStatus(GroundResponse.Status.UNGROUNDABLE)
                        .addMessages(ResponseMessage.newBuilder().setCode(code).setHumanMessage("refused"))
                        .build()

                override suspend fun getStatus(): GetStatusResponse = GetStatusResponse.getDefaultInstance()
            }

        fun kernelOption(
            id: String,
            label: String,
            start: String,
            end: String,
        ): ClarificationOption =
            ClarificationOption
                .newBuilder()
                .setId(id)
                .setLabel(label)
                .setNormalized(
                    Normalized.newBuilder().setInterval(DateTimeInterval.newBuilder().setStart(start).setEnd(end)),
                ).build()

        /** Whitespace tokens (no dep parse: SpanProposal takes the n-gram floor), and NER typing „listopadu“ a date. */
        object WordsAndOneDate : NlpClient {
            override suspend fun analyze(request: AnalyzeRequest): AnalyzeResponse {
                val text = request.text
                val builder =
                    AnalyzeResponse
                        .newBuilder()
                        .setLanguage("cs")
                        .setDetectedLanguage("cs")
                        .setTraceId("kernel-ask-trace")
                var cursor = 0
                for (word in text.split(" ")) {
                    val start = text.indexOf(word, cursor)
                    val end = start + word.length
                    builder.addTokens(
                        Token
                            .newBuilder()
                            .setText(word)
                            .setLemma(word)
                            .setUpos("X")
                            .setDepHead(0)
                            .setCharStart(start)
                            .setCharEnd(end),
                    )
                    cursor = end
                }
                val at = text.indexOf("listopadu")
                if (at >= 0) {
                    builder.addEntities(
                        NerEntity
                            .newBuilder()
                            .setText("listopadu")
                            .setCharStart(at)
                            .setCharEnd(at + "listopadu".length)
                            .setLabel("DATE")
                            .setNormalizedValue("cnec:tm")
                            .setSourceEngine("nametag3"),
                    )
                }
                return builder.build()
            }

            override suspend fun getStatus(): StatusResponse = StatusResponse.newBuilder().setReady(true).build()
        }

        /** „DC“ is two warehouses, tied — the gate's own G2. Every other span matches nothing. */
        object DcIsTwoWarehouses : FuzzyClient {
            override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse {
                val builder = BatchMatchResponse.newBuilder()
                for (span in request.spansList) {
                    val r = FuzzyMatchResponse.newBuilder()
                    if (span.query == "DC") {
                        r.addMatches(member("brno-dc", "Brno DC", 0.80))
                        r.addMatches(member("praha-dc", "Praha DC", 0.79))
                    }
                    builder.addResults(r)
                }
                return builder.build()
            }

            override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()

            private fun member(
                id: String,
                label: String,
                score: Double,
            ): FuzzyMatch =
                FuzzyMatch
                    .newBuilder()
                    .setCandidateId(id)
                    .setCandidate(label)
                    .setScore(score)
                    .setCategory("er.warehouse.member")
                    .setSource(SourceTag.MEMBER)
                    .build()
        }

        /**
         * chrono as it answers live (hartland, 2026-10-14, reference in October): a bare future month
         * asks between this year's and last year's, and a resume by id is an OK carrying the interval
         * and the anchor column. The AWAITING carries no anchor — only an OK does.
         */
        class FakeChrono : GroundingClient {
            enum class Answer { GROUND, ASK_AGAIN, UNGROUNDABLE, THROW }

            val seen = mutableListOf<GroundRequest>()
            var onPick = Answer.GROUND

            override suspend fun ground(request: GroundRequest): GroundResponse {
                seen += request
                if (request.clarificationAnswerId.isEmpty()) return ask()
                return when (onPick) {
                    Answer.GROUND ->
                        when (request.clarificationAnswerId) {
                            "202511" -> ok("2025-11-01T00:00:00Z", "2025-12-01T00:00:00Z")
                            "202611" -> ok("2026-11-01T00:00:00Z", "2026-12-01T00:00:00Z")
                            else -> ungroundable()
                        }
                    Answer.ASK_AGAIN -> ask()
                    Answer.UNGROUNDABLE -> ungroundable()
                    Answer.THROW -> error("chrono is down")
                }
            }

            override suspend fun getStatus(): GetStatusResponse = GetStatusResponse.getDefaultInstance()

            private fun ask(): GroundResponse =
                GroundResponse
                    .newBuilder()
                    .setStatus(GroundResponse.Status.AWAITING_CLARIFICATION)
                    .addOptions(kernelOption("202611", "listopad 2026", "2026-11-01T00:00:00Z", "2026-12-01T00:00:00Z"))
                    .addOptions(kernelOption("202511", "listopad 2025", "2025-11-01T00:00:00Z", "2025-12-01T00:00:00Z"))
                    .build()

            private fun ungroundable(): GroundResponse =
                GroundResponse.newBuilder().setStatus(GroundResponse.Status.UNGROUNDABLE).build()

            private fun ok(
                start: String,
                end: String,
            ): GroundResponse =
                GroundResponse
                    .newBuilder()
                    .setStatus(GroundResponse.Status.OK)
                    .setResult(
                        GroundingResult
                            .newBuilder()
                            .setNormalized(
                                Normalized.newBuilder().setInterval(
                                    DateTimeInterval.newBuilder().setStart(start).setEnd(end),
                                ),
                            ).setFilter(
                                FilterRecipe.newBuilder().setAnchorColumn(
                                    // The wire shape quoted from hartland (see GroundingRungTest).
                                    QualifiedName
                                        .newBuilder()
                                        .setPackage("hartland")
                                        .setSchemaCode(SchemaCode.ER)
                                        .setNamespace("entity")
                                        .setName("date_dim.cal_date"),
                                ),
                            ).setConfidence(0.6f),
                    ).build()
        }
    }
}
