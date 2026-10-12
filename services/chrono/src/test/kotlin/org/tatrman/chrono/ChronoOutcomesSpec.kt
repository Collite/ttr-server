// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono

import org.tatrman.grounding.v1.EntityKind
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import org.tatrman.grounding.v1.GroundingContext
import org.tatrman.grounding.v1.GroundingResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.tatrman.chrono.client.GatewayResponseFormat
import org.tatrman.chrono.client.LlmGatewayClient
import org.tatrman.chrono.grpc.ChronoGroundingService

/**
 * A8.6 outcomes — AWAITING_CLARIFICATION on ambiguity, deterministic `clarification_answer_id`
 * resume, and the llm-gateway below-threshold / unrecognized fallback (with `source: LLM`).
 */
class ChronoOutcomesSpec :
    StringSpec({
        val ref = "2026-05-15T12:00:00+02:00"
        val tz = "Europe/Prague"

        fun request(
            span: String,
            answerId: String = "",
            question: String = "",
            priorQuestions: List<String> = emptyList(),
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
                        .setTimezone(tz)
                        .addAllPriorQuestions(priorQuestions),
                ).apply { if (answerId.isNotEmpty()) clarificationAnswerId = answerId }
                .build()

        "a bare future month → AWAITING_CLARIFICATION with this-year + last-year options" {
            val svc = ChronoGroundingService(FakeMetadataClient.accounting("cnc"), llmFallback = null)
            val r = svc.ground(request("December"))
            r.status shouldBe GroundResponse.Status.AWAITING_CLARIFICATION
            r.optionsList.map { it.id } shouldBe listOf("202612", "202512")
            r.optionsList[0].label shouldBe "December 2026 (202612)"
            r.optionsList[1]
                .normalized.interval.start
                .startsWith("2025-12-01") shouldBe true
        }

        "clarification_answer_id resumes deterministically to the chosen year" {
            val svc = ChronoGroundingService(FakeMetadataClient.accounting("cnc"), llmFallback = null)
            val r = svc.ground(request("December", answerId = "202512"))
            r.status shouldBe GroundResponse.Status.OK
            r.result.applicationCase shouldBe GroundingResult.ApplicationCase.JOIN
            r.result.join.parametersList
                .single()
                .value.stringValue shouldBe "202512"
        }

        "an unknown clarification_answer_id → UNGROUNDABLE" {
            val svc = ChronoGroundingService(FakeMetadataClient.accounting("cnc"), llmFallback = null)
            svc.ground(request("December", answerId = "209912")).status shouldBe GroundResponse.Status.UNGROUNDABLE
        }

        "unrecognized span with no fallback client → UNGROUNDABLE" {
            val svc = ChronoGroundingService(FakeMetadataClient.accounting("cnc"), llmFallback = null)
            svc.ground(request("qwerty nonsense")).status shouldBe GroundResponse.Status.UNGROUNDABLE
        }

        "unrecognized span with a fallback client → OK, source = LLM" {
            val gateway = FakeLlmGateway(VALID_LLM_RESULT)
            val svc = ChronoGroundingService(FakeMetadataClient.accounting("cnc"), llmFallback = gateway)
            val r = svc.ground(request("three weeks after Easter"))
            r.status shouldBe GroundResponse.Status.OK
            r.result.source shouldBe GroundingResult.Source.LLM
            gateway.lastUserPrompt!!.contains("three weeks after Easter") shouldBe true
        }

        "a fallback client returning invalid JSON → UNGROUNDABLE (not a crash)" {
            val svc =
                ChronoGroundingService(FakeMetadataClient.accounting("cnc"), llmFallback = FakeLlmGateway("not json"))
            svc.ground(request("three weeks after Easter")).status shouldBe GroundResponse.Status.UNGROUNDABLE
        }

        "a bare month leans on the year its question names — no clarification, that year's period" {
            val svc = ChronoGroundingService(FakeMetadataClient.accounting("cnc"), llmFallback = null)
            val r =
                svc.ground(
                    request("December", question = "Which products sold out in December 2025? — all of December"),
                )
            r.status shouldBe GroundResponse.Status.OK
            r.result.join.parametersList
                .single()
                .value.stringValue shouldBe "202512"
        }

        "a bare month in a follow-up leans on the conversation's earlier questions" {
            val svc = ChronoGroundingService(FakeMetadataClient.accounting("cnc"), llmFallback = null)
            val r =
                svc.ground(
                    request(
                        "December",
                        question = "And in December?",
                        priorQuestions = listOf("Store sales by city in 2025"),
                    ),
                )
            r.status shouldBe GroundResponse.Status.OK
            r.result.join.parametersList
                .single()
                .value.stringValue shouldBe "202512"
        }

        "the LLM fallback reads the same conversation the rules lean on" {
            val gateway = FakeLlmGateway(VALID_LLM_RESULT)
            val svc = ChronoGroundingService(FakeMetadataClient.accounting("cnc"), llmFallback = gateway)
            svc.ground(
                request(
                    "three weeks after Easter",
                    question = "And then?",
                    priorQuestions = listOf("Store sales in 2025"),
                ),
            )
            gateway.lastUserPrompt!!.contains("Earlier question: \"Store sales in 2025\"") shouldBe true
        }

        "D-T4: a rules-hit with a gateway present stays RULES and never calls the LLM" {
            val gateway = FakeLlmGateway(VALID_LLM_RESULT)
            val svc = ChronoGroundingService(FakeMetadataClient.accounting("cnc"), llmFallback = gateway)
            val r = svc.ground(request("yesterday"))
            r.status shouldBe GroundResponse.Status.OK
            r.result.source shouldBe GroundingResult.Source.RULES
            gateway.lastUserPrompt.shouldBeNull() // fallback fires only on a rules-miss / low-confidence
        }
    })

private class FakeLlmGateway(
    private val response: String,
) : LlmGatewayClient {
    var lastUserPrompt: String? = null

    override suspend fun chat(
        model: String,
        system: String,
        user: String,
        responseFormat: GatewayResponseFormat?,
    ): String {
        lastUserPrompt = user
        return response
    }

    override fun close() {}
}

// Minimal structurally-valid GroundingResult (proto JSON): normalized + a filter + sql_preview.
private const val VALID_LLM_RESULT = """
{
  "normalized": { "interval": { "start": "2026-05-01T00:00:00+02:00", "end": "2026-06-01T00:00:00+02:00" } },
  "filter": {},
  "sqlPreview": "t.\"date\" >= {start} AND t.\"date\" < {end}",
  "confidence": 0.5,
  "explanation": "LLM-grounded to May 2026."
}
"""
