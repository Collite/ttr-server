// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.tatrman.chrono.client.GatewayResponseFormat
import org.tatrman.chrono.client.LlmGatewayClient
import org.tatrman.chrono.grpc.ChronoGroundingService
import org.tatrman.grounding.v1.EntityKind
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import org.tatrman.grounding.v1.GroundingContext

/**
 * #214 at the service: „minulý …“ whose two readings differ is `AWAITING_CLARIFICATION` with the deictic
 * and the anaphoric reading as its two options. Each option's id is its period code (`yyyy`, `yyyyMM`,
 * `yyyyQn`), its label names the period in the request's language, and `clarification_answer_id` resumes to
 * the option picked. An anaphoric word with no anchor is UNGROUNDABLE, says why, and is never handed to the
 * LLM fallback (which could read it against the reference date).
 */
class PeriodClarificationSpec :
    StringSpec({
        val ref = "2026-10-14T12:00:00Z"

        fun request(
            span: String,
            question: String,
            vararg prior: String,
            locale: String = "cs",
            answerId: String = "",
        ): GroundRequest =
            GroundRequest
                .newBuilder()
                .setSpanText(span)
                .setQuestionText(question)
                .setKind(EntityKind.DATE_TIME)
                .setPackage("cnc")
                .setContext(
                    GroundingContext
                        .newBuilder()
                        .setReferenceDatetime(ref)
                        .setTimezone("UTC")
                        .setLocale(locale)
                        .addAllPriorQuestions(prior.toList()),
                ).apply { if (answerId.isNotEmpty()) clarificationAnswerId = answerId }
                .build()

        fun service(llm: LlmGatewayClient? = null) =
            ChronoGroundingService(FakeMetadataClient.accounting("cnc"), llmFallback = llm)

        val year = arrayOf("A minulý rok?", "Tržby z tržiště v roce 2025")
        val month = arrayOf("A minulý měsíc?", "Tržby z tržiště v březnu 2025")
        val quarter = arrayOf("A minulé čtvrtletí?", "Tržby z tržiště ve 2. čtvrtletí 2025")

        "„minulý rok“ after 2025 asks: 2025 (deictic) or 2024 (anaphoric), ids are the years" {
            val r = service().ground(request("minulý rok", year[0], year[1]))
            r.status shouldBe GroundResponse.Status.AWAITING_CLARIFICATION
            r.optionsList.map { it.id } shouldBe listOf("2025", "2024")
            r.optionsList.map { it.label } shouldBe listOf("2025", "2024")
            r.optionsList[1]
                .normalized.interval.start shouldStartWith "2024-01-01"
            r.optionsList[1]
                .normalized.interval.end shouldStartWith "2025-01-01"
        }

        "„minulý měsíc“ after March 2025 asks September 2026 or February 2025, labelled in Czech" {
            val r = service().ground(request("minulý měsíc", month[0], month[1]))
            r.status shouldBe GroundResponse.Status.AWAITING_CLARIFICATION
            r.optionsList.map { it.id } shouldBe listOf("202609", "202502")
            r.optionsList.map { it.label } shouldBe listOf("září 2026", "únor 2025")
        }

        "„minulé čtvrtletí“ after Q2 2025 asks Q3 2026 or Q1 2025" {
            val cs = service().ground(request("minulé čtvrtletí", quarter[0], quarter[1]))
            cs.status shouldBe GroundResponse.Status.AWAITING_CLARIFICATION
            cs.optionsList.map { it.id } shouldBe listOf("2026Q3", "2025Q1")
            cs.optionsList.map { it.label } shouldBe listOf("3. čtvrtletí 2026", "1. čtvrtletí 2025")
        }

        "the labels name the period in English for any other locale" {
            service()
                .ground(request("minulé čtvrtletí", quarter[0], quarter[1], locale = "en"))
                .optionsList
                .map { it.label } shouldBe listOf("Q3 2026", "Q1 2025")
            service()
                .ground(request("minulý měsíc", month[0], month[1], locale = "en-US"))
                .optionsList
                .map { it.label } shouldBe listOf("September 2026", "February 2025")
        }

        "the answer id resumes to the reading picked, and does not ask again" {
            for ((answer, start) in listOf("2024" to "2024-01-01", "2025" to "2025-01-01")) {
                val r = service().ground(request("minulý rok", year[0], year[1], answerId = answer))
                r.status shouldBe GroundResponse.Status.OK
                r.result.normalized.interval.start shouldStartWith start
                r.optionsList.shouldBeEmpty()
            }
            service()
                .ground(request("minulý měsíc", month[0], month[1], answerId = "202502"))
                .result.normalized.interval.start shouldStartWith "2025-02-01"
            service()
                .ground(request("minulé čtvrtletí", quarter[0], quarter[1], answerId = "2025Q1"))
                .result.normalized.interval.start shouldStartWith "2025-01-01"
        }

        "a day in „minulý rok“ asks between the two days, with day ids" {
            val question = arrayOf("A 15. března minulého roku?", "Tržby z tržiště v roce 2025")
            val cs = service().ground(request("15. března minulého roku", question[0], question[1]))
            cs.status shouldBe GroundResponse.Status.AWAITING_CLARIFICATION
            cs.optionsList.map { it.id } shouldBe listOf("20250315", "20240315")
            cs.optionsList.map { it.label } shouldBe listOf("15. 3. 2025", "15. 3. 2024")
            service()
                .ground(request("15. března minulého roku", question[0], question[1], locale = "en"))
                .optionsList
                .map { it.label } shouldBe listOf("March 15, 2025", "March 15, 2024")
            service()
                .ground(request("15. března minulého roku", question[0], question[1], answerId = "20240315"))
                .result.normalized.interval.start shouldStartWith "2024-03-15"
        }

        "an answer id that is neither reading is UNGROUNDABLE" {
            service()
                .ground(request("minulý rok", year[0], year[1], answerId = "2023"))
                .status shouldBe GroundResponse.Status.UNGROUNDABLE
        }

        "„minulý rok“ whose readings agree grounds without asking" {
            val r = service().ground(request("minulý rok", "Tržby z tržiště za minulý rok"))
            r.status shouldBe GroundResponse.Status.OK
            r.result.normalized.interval.start shouldStartWith "2025-01-01"
        }

        "the deictic and the anaphoric words ground without asking" {
            val loni = service().ground(request("loni", "A loni?", "Tržby z tržiště v roce 2025"))
            loni.status shouldBe GroundResponse.Status.OK
            loni.result.normalized.interval.start shouldStartWith "2025-01-01"
            val before =
                service().ground(
                    request("the year before", "And the year before?", "Marketplace revenue in 2025", locale = "en"),
                )
            before.status shouldBe GroundResponse.Status.OK
            before.result.normalized.interval.start shouldStartWith "2024-01-01"
        }

        "an anaphoric word with no anchor is UNGROUNDABLE, says why, and never reaches the LLM fallback" {
            val gateway = RecordingGateway()
            val r =
                service(gateway).ground(
                    request(
                        "the previous year",
                        "And the previous year?",
                        "Marketplace revenue in 2024 and in 2025",
                        locale = "en",
                    ),
                )
            r.status shouldBe GroundResponse.Status.UNGROUNDABLE
            val message = r.messagesList.single()
            message.code shouldBe "no_anchor"
            message.humanMessage shouldContain "the previous year"
            message.humanMessage shouldContain "2024"
            message.humanMessage shouldContain "2025"
            gateway.called.shouldBeNull()
        }
    })

private class RecordingGateway : LlmGatewayClient {
    var called: String? = null

    override suspend fun chat(
        model: String,
        system: String,
        user: String,
        responseFormat: GatewayResponseFormat?,
    ): String {
        called = user
        return "{}"
    }

    override fun close() {}
}
