// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.grpc

import com.google.protobuf.util.JsonFormat
import org.tatrman.grounding.v1.ClarificationOption
import org.tatrman.grounding.v1.DateTimeInterval
import org.tatrman.grounding.lexicon.GroundingSliceSource
import org.tatrman.grounding.v1.GetStatusRequest
import org.tatrman.grounding.v1.GetStatusResponse
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import org.tatrman.grounding.v1.GroundingContext
import org.tatrman.grounding.v1.GroundingResult
import org.tatrman.grounding.v1.GroundingServiceGrpcKt
import org.tatrman.grounding.v1.Normalized
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.slf4j.LoggerFactory
import org.tatrman.chrono.client.GatewayResponseFormat
import org.tatrman.chrono.client.LlmGatewayClient
import org.tatrman.chrono.discover.SemanticDiscovery
import org.tatrman.chrono.obs.ChronoMetrics
import org.tatrman.chrono.recipe.RecipeBuilder
import org.tatrman.chrono.recognize.ChronoReading
import org.tatrman.chrono.recognize.ChronoRecognition
import org.tatrman.chrono.recognize.DateRecognizer
import java.time.LocalDate
import java.time.Month
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * `services/chrono` — the DATE_TIME grounding service (feature-grounding A8). Deterministic
 * recognition (A8.3) + recipe assembly (A8.4). A8.6 outcomes: ambiguity → AWAITING_CLARIFICATION
 * (+ deterministic `clarification_answer_id` resume); below-threshold / unrecognized → the
 * llm-gateway fallback ([llmFallback], `source: LLM`); hard misses → UNGROUNDABLE.
 *
 * `reference_datetime` is ALWAYS taken from the request context — this class never reads a clock.
 */
class ChronoGroundingService(
    private val discovery: SemanticDiscovery,
    private val llmFallback: LlmGatewayClient?,
    private val llmModel: String = "claude-haiku-4-5",
    private val confidenceThreshold: Double = 0.6,
    private val metrics: ChronoMetrics = ChronoMetrics.noop(),
    /**
     * RV-P1.6 T4 (RV-42) — the `ground:chrono` trigger slice. Optional by construction: the
     * default source never loads anything, which is the pre-RV service (own rules, no estate
     * vocabulary) and the supported deployment for an estate with no compiled lexicon.
     */
    private val triggers: GroundingSliceSource = GroundingSliceSource.disabled("chrono"),
) : GroundingServiceGrpcKt.GroundingServiceCoroutineImplBase() {
    private val logger = LoggerFactory.getLogger(ChronoGroundingService::class.java)
    private val recognizer = DateRecognizer()
    private val recipeBuilder = RecipeBuilder(discovery)

    override suspend fun ground(request: GroundRequest): GroundResponse {
        // System.nanoTime is a monotonic duration timer, not a wall clock — grounding still reads
        // reference_datetime only from the request (A8.7 observability).
        val startNanos = System.nanoTime()
        val response = groundInternal(request)
        val source = if (response.hasResult()) response.result.source.name else "NONE"
        metrics.recordGround(
            outcome = response.status.name,
            source = source,
            latencyMs = (System.nanoTime() - startNanos) / 1_000_000.0,
        )
        logger.debug(
            "chrono ground span='{}' pkg='{}' → outcome={} source={}",
            request.spanText,
            request.getPackage(),
            response.status.name,
            source,
        )
        return response
    }

    private suspend fun groundInternal(request: GroundRequest): GroundResponse {
        val reference =
            parseReference(request.context)
                ?: return ungroundable("reference_datetime is required (chrono never reads a clock)")

        val reading =
            // RV-P1.6: the slice is narrowed to the request's language — a term carries the
            // `lang` its author declared, and an English trigger has no business firing on a
            // Czech question. A blank locale narrows nothing.
            recognizer.read(
                request.spanText,
                reference,
                triggers.current().forLang(request.context.locale),
                // A date that names no year leans on the question it is in, then on the user's
                // earlier questions — "po celý měsíc říjen" after "v říjnu 2025" is October 2025 —
                // and an anaphoric period word reads against them — "the previous year" after
                // "Marketplace revenue in 2025" is 2024 (#214).
                context = listOf(request.questionText) + request.context.priorQuestionsList,
            ) ?: return fallback(request, "could not recognize a time expression in '${request.spanText}'")
        val recognition =
            when (reading) {
                is ChronoReading.Recognized -> reading.recognition
                // Not the LLM fallback: it reads the same conversation and could read the word against
                // the reference date, which is exactly what an unanchored word must not be (#214).
                is ChronoReading.Unanchored -> return ungroundable(reading.reason, code = "no_anchor")
            }

        val resolved: ChronoRecognition =
            when {
                // HITL resume: the caller echoes a prior option id — pick it deterministically.
                request.clarificationAnswerId.isNotEmpty() ->
                    pickCandidate(recognition, request.clarificationAnswerId)
                        ?: return ungroundable("unknown clarification_answer_id '${request.clarificationAnswerId}'")
                // Ambiguous span → ask, don't guess.
                recognition.alternatives.isNotEmpty() ->
                    return awaitingClarification(recognition, request.context.timezone, request.context.locale)
                else -> recognition
            }

        if (resolved.confidence < confidenceThreshold) {
            return fallback(
                request,
                "recognition confidence ${resolved.confidence} below threshold $confidenceThreshold",
            )
        }

        val result =
            recipeBuilder.build(request.spanText, resolved, request.getPackage(), request.context.timezone)
                ?: return ungroundable("no anchor date column in package '${request.getPackage()}'")
        return GroundResponse
            .newBuilder()
            .setStatus(GroundResponse.Status.OK)
            .setResult(result)
            .build()
    }

    override suspend fun getStatus(request: GetStatusRequest): GetStatusResponse {
        val metadataOk = runCatching { discovery.probeReady() }.getOrDefault(false)
        return GetStatusResponse
            .newBuilder()
            .setReady(metadataOk)
            .setService("chrono")
            .putCapabilities("metadata", if (metadataOk) "ok" else "down")
            .putCapabilities("llm_fallback", (llmFallback != null).toString())
            // RV-39/S-1: which vocabulary answered. Empty means no slice is serving, which is a
            // legitimate deployment — the capability map is the existing echo channel, so this
            // needs no wire change (J-v2).
            .putCapabilities("lexicon_slice", triggers.current().version)
            .putCapabilities(
                "lexicon_slice_terms",
                triggers
                    .current()
                    .terms.size
                    .toString(),
            ).build()
    }

    // ----- clarification (A8.6) -----

    private fun awaitingClarification(
        recognition: ChronoRecognition,
        timezone: String,
        locale: String,
    ): GroundResponse {
        val zone = zoneOf(timezone)
        val options =
            (listOf(recognition) + recognition.alternatives).map { candidate ->
                ClarificationOption
                    .newBuilder()
                    .setId(codeOf(candidate))
                    .setLabel(labelFor(candidate, locale))
                    .setNormalized(normalized(candidate, zone))
                    .build()
            }
        return GroundResponse
            .newBuilder()
            .setStatus(GroundResponse.Status.AWAITING_CLARIFICATION)
            .addAllOptions(options)
            .build()
    }

    /** Resolve a clarification answer back to its candidate (alternatives cleared so it can't re-ask). */
    private fun pickCandidate(
        recognition: ChronoRecognition,
        answerId: String,
    ): ChronoRecognition? =
        (listOf(recognition) + recognition.alternatives)
            .firstOrNull { codeOf(it) == answerId }
            ?.copy(alternatives = emptyList())

    /**
     * A clarification option's id: the candidate's period code — `yyyyMM` for a month, `yyyyQn` for a
     * quarter — or, for a candidate that carries none, the code of the calendar span it covers: `yyyy` for a
     * whole year, `yyyyMMdd` for a day (#214). A year interval carries no period code on purpose (it must not
     * route through a period table), so the id is made here, not on the recognition.
     */
    private fun codeOf(c: ChronoRecognition): String {
        c.periodCode?.let { return it }
        val start = c.startInclusive
        return when {
            start.dayOfYear == 1 && c.endExclusive == start.plusYears(1) -> "%04d".format(start.year)
            c.endExclusive == start.plusDays(1) -> "%04d%02d%02d".format(start.year, start.monthValue, start.dayOfMonth)
            else -> ""
        }
    }

    /**
     * A clarification option's label: the period it names, in the request's language (#214) — "2025",
     * "February 2025", "Q1 2025", "March 15, 2025"; in Czech „2025“, „únor 2025“, „1. čtvrtletí 2025“,
     * „15. 3. 2025“. The id carries the code; the label is for the person asked.
     */
    private fun labelFor(
        c: ChronoRecognition,
        locale: String,
    ): String {
        val czech = locale.trim().lowercase().startsWith("cs")
        val code = codeOf(c)
        val start = c.startInclusive
        return when {
            YEAR_CODE.matches(code) -> code
            QUARTER_CODE.matches(code) -> {
                val q = code.last()
                if (czech) "$q. čtvrtletí ${start.year}" else "Q$q ${start.year}"
            }
            MONTH_CODE.matches(code) -> "${monthName(start.monthValue, czech)} ${start.year}"
            DAY_CODE.matches(code) ->
                if (czech) {
                    "${start.dayOfMonth}. ${start.monthValue}. ${start.year}"
                } else {
                    "${monthName(start.monthValue, czech = false)} ${start.dayOfMonth}, ${start.year}"
                }
            else -> "${c.startIso} – ${c.endIso}"
        }
    }

    private fun monthName(
        month: Int,
        czech: Boolean,
    ): String = if (czech) CZECH_MONTHS[month - 1] else Month.of(month).getDisplayName(TextStyle.FULL, Locale.ENGLISH)

    private fun normalized(
        c: ChronoRecognition,
        zone: ZoneId,
    ): Normalized =
        Normalized
            .newBuilder()
            .setInterval(
                DateTimeInterval
                    .newBuilder()
                    .setStart(iso(c.startInclusive, zone))
                    .setEnd(iso(c.endExclusive, zone)),
            ).build()

    // ----- llm-gateway fallback (A8.6) -----

    private suspend fun fallback(
        request: GroundRequest,
        reason: String,
    ): GroundResponse {
        val client = llmFallback ?: return ungroundable(reason)
        return try {
            val raw =
                client.chat(
                    model = llmModel,
                    system = LLM_SYSTEM_PROMPT,
                    user = llmUserPrompt(request),
                    responseFormat = GatewayResponseFormat("json_object"),
                )
            parseLlmResult(raw)
                ?.let {
                    GroundResponse
                        .newBuilder()
                        .setStatus(GroundResponse.Status.OK)
                        .setResult(it.toBuilder().setSource(GroundingResult.Source.LLM))
                        .build()
                } ?: ungroundable("$reason; llm fallback returned an invalid GroundingResult")
        } catch (e: Exception) {
            logger.warn("chrono llm fallback failed for span '{}': {}", request.spanText, e.message)
            return ungroundable("$reason; llm fallback failed")
        }
    }

    /**
     * Parse the gateway's JSON into a GroundingResult and structurally validate it. Full Translator
     * round-trip re-validation (A8.5) is deferred here on purpose: the service holds no ModelHandle,
     * and Golem's free-SQL tier already post-parses the recipe against the plan (contracts §3).
     */
    private fun parseLlmResult(raw: String): GroundingResult? {
        val result =
            runCatching {
                val builder = GroundingResult.newBuilder()
                JsonFormat.parser().ignoringUnknownFields().merge(raw, builder)
                builder.build()
            }.getOrElse {
                logger.warn("chrono llm fallback: unparseable GroundingResult JSON: {}", it.message)
                return null
            }
        val structurallyValid =
            result.hasNormalized() &&
                result.applicationCase != GroundingResult.ApplicationCase.APPLICATION_NOT_SET &&
                result.sqlPreview.isNotEmpty()
        return result.takeIf { structurallyValid }
    }

    private fun llmUserPrompt(request: GroundRequest): String =
        buildString {
            appendLine("Span: \"${request.spanText}\"")
            appendLine("Question: \"${request.questionText}\"")
            // The rules lean a yearless date on these; the fallback reads the same conversation.
            request.context.priorQuestionsList.forEach { appendLine("Earlier question: \"$it\"") }
            appendLine("reference_datetime: ${request.context.referenceDatetime}")
            appendLine("timezone: ${request.context.timezone}")
            append("package: ${request.getPackage()}")
        }

    // ----- helpers -----

    /** reference_datetime (ISO-8601 with offset) → LocalDate in the request timezone. Null when absent/unparseable. */
    private fun parseReference(ctx: GroundingContext): LocalDate? {
        val raw = ctx.referenceDatetime.trim()
        if (raw.isEmpty()) return null
        val zone = zoneOf(ctx.timezone)
        return runCatching { OffsetDateTime.parse(raw).atZoneSameInstant(zone).toLocalDate() }.getOrNull()
            ?: runCatching { LocalDate.parse(raw) }.getOrNull()
    }

    private fun zoneOf(timezone: String): ZoneId =
        runCatching { ZoneId.of(timezone.ifEmpty { "UTC" }) }.getOrDefault(ZoneOffset.UTC)

    private fun iso(
        date: LocalDate,
        zone: ZoneId,
    ): String = date.atStartOfDay(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private fun ungroundable(
        message: String,
        code: String = "ungroundable",
    ): GroundResponse =
        GroundResponse
            .newBuilder()
            .setStatus(GroundResponse.Status.UNGROUNDABLE)
            .addMessages(
                ResponseMessage
                    .newBuilder()
                    .setSeverity(Severity.INFO)
                    .setCode(code)
                    .setHumanMessage(message),
            ).build()

    private companion object {
        val YEAR_CODE = Regex("""\d{4}""")
        val QUARTER_CODE = Regex("""\d{4}Q[1-4]""")
        val MONTH_CODE = Regex("""\d{6}""")
        val DAY_CODE = Regex("""\d{8}""")

        /** The Czech month names, nominative, as a label names a month on its own („únor 2025“). */
        val CZECH_MONTHS =
            listOf(
                "leden",
                "únor",
                "březen",
                "duben",
                "květen",
                "červen",
                "červenec",
                "srpen",
                "září",
                "říjen",
                "listopad",
                "prosinec",
            )

        const val LLM_SYSTEM_PROMPT =
            "You ground a time expression to a JSON GroundingResult (org.tatrman.grounding.v1). " +
                "Return ONLY the JSON object: a `normalized.interval` {start,end} (ISO-8601, end EXCLUSIVE), " +
                "a `filter` recipe (condition + parameters + anchorColumn), `sqlPreview` (Calcite SQL, " +
                "double-quoted identifiers, named params {name}), `confidence` 0..1, and a one-line `explanation`."
    }
}
