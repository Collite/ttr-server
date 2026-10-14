// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono

import org.tatrman.grounding.v1.EntityKind
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundingContext
import org.tatrman.grounding.v1.GroundingResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tatrman.chrono.grpc.ChronoGroundingService
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A8.1 golden corpus — one parameterized case per row of `corpus/chrono/cases.json`, run through the
 * full [ChronoGroundingService.ground] pipeline (recognizer → discovery → recipe) over the in-memory
 * [FakeMetadataClient]. Expectations are property-based (status / application / periodCode /
 * sql_preview fragments) rather than full-GroundingResult-JSON equality — the latter is brittle
 * against incidental field churn. Reference clock is pinned per case (default 2026-05-15 noon).
 *
 * Seeded across every rule family; grow toward the ≥40-case DoD (A8.8, ≥90% rules-path) as the
 * recognizer widens. All cases are RULES-path today (no LLM fallback in the harness).
 */
class ChronoCorpusSpec :
    StringSpec({
        loadCorpus().forEach { case ->
            "corpus/${case.name}: \"${case.span}\"" {
                val client =
                    if (case.periodTable) {
                        FakeMetadataClient.accounting(case.pkg)
                    } else {
                        FakeMetadataClient(
                            listOf(
                                FakeMetadataClient.Obj(
                                    case.pkg,
                                    "Transaction",
                                    "date",
                                    "attribute",
                                    role = "event_date",
                                ),
                                FakeMetadataClient.Obj(case.pkg, "Transaction", "due", "attribute", role = "due_date"),
                            ),
                        )
                    }
                val service = ChronoGroundingService(client, llmFallback = null)
                val response = service.ground(request(case))

                response.status.name shouldBe case.expect.status
                if (case.expect.status == "OK") {
                    val result = response.result
                    case.expect.application?.let { result.applicationCase.name shouldBe it }
                    case.expect.source?.let { result.source.name shouldBe it }
                    case.expect.periodCode?.let { paramValue(result, "p") shouldBe it }
                    case.expect.sqlContains.forEach { result.sqlPreview shouldContain it }
                    case.expect.intervalStart?.let { result.normalized.interval.start shouldStartWith it }
                }
                if (case.expect.optionIds.isNotEmpty()) {
                    response.optionsList.map { it.id } shouldBe case.expect.optionIds
                }
            }
        }
    })

private fun request(case: CorpusCase): GroundRequest {
    val refIso =
        LocalDate
            .parse(case.reference)
            .atTime(12, 0)
            .atZone(ZoneId.of(case.timezone))
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    return GroundRequest
        .newBuilder()
        .setSpanText(case.span)
        .setQuestionText(case.question)
        .setKind(EntityKind.DATE_TIME)
        .setPackage(case.pkg)
        .setClarificationAnswerId(case.answerId)
        .setContext(
            GroundingContext
                .newBuilder()
                .setReferenceDatetime(refIso)
                .setTimezone(case.timezone)
                .setLocale(case.locale)
                .addAllPriorQuestions(case.priorQuestions),
        ).build()
}

/** The `p` (or any named) parameter's string value from whichever recipe the result carries. */
private fun paramValue(
    result: GroundingResult,
    name: String,
): String? {
    val params =
        when (result.applicationCase) {
            GroundingResult.ApplicationCase.JOIN -> result.join.parametersList
            GroundingResult.ApplicationCase.FILTER -> result.filter.parametersList
            else -> emptyList()
        }
    return params.firstOrNull { it.name == name }?.value?.stringValue
}

private fun loadCorpus(): List<CorpusCase> {
    val json = object {}.javaClass.getResource("/corpus/chrono/cases.json")!!.readText()
    return Json { ignoreUnknownKeys = true }.decodeFromString(json)
}

@Serializable
private data class CorpusCase(
    val name: String,
    val span: String,
    val reference: String = "2026-05-15",
    val timezone: String = "Europe/Prague",
    val pkg: String = "cnc",
    val periodTable: Boolean = true,
    // #214: the conversation the span is asked in, and a clarification answer to resume with.
    val question: String = "",
    val priorQuestions: List<String> = emptyList(),
    val answerId: String = "",
    val locale: String = "",
    val expect: Expect,
)

@Serializable
private data class Expect(
    val status: String,
    val application: String? = null,
    val source: String? = null,
    val periodCode: String? = null,
    val sqlContains: List<String> = emptyList(),
    /** The date (YYYY-MM-DD) the grounded interval starts on. */
    val intervalStart: String? = null,
    /** The ids of a clarification's options, in order. */
    val optionIds: List<String> = emptyList(),
)
