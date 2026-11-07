// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.scan

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.chrono.recognize.ChronoReading
import org.tatrman.chrono.recognize.ChronoRecognition
import org.tatrman.chrono.recognize.DateRecognizer
import java.time.LocalDate

/**
 * TS-P0 S0.3 T13 (review-215 P4, planner lists of rounds 1–5) — the spans the review found along the way, as `Scan`
 * fixtures: `scan/planner-spans.json`. Each text gives the spans its row names, each read as the row names it (a
 * period code, `start/end`, `ask` and the other options, `unanchored`), or no span: the ranges of P1, the shapes of
 * R3/R4/R7, RR2/RR3, P5's periods side by side, RR6's comparisons, RR7, RR8, RR10, round 4's flaws and ⚑R215-W's
 * readings TS-P0 leaves unread. A row that changes is a decision, not a refresh: the planner's lists say why.
 */
class PlannerSpansSpec :
    FunSpec({
        val scanner = SpanScanner()
        val recognizer = DateRecognizer()
        val rows =
            Json
                .parseToJsonElement(object {}.javaClass.getResource("/scan/planner-spans.json")!!.readText())
                .jsonArray
                .map { it.jsonObject }

        fun key(r: ChronoRecognition): String =
            (r.periodCode ?: "${r.startInclusive}/${r.endExclusive}") +
                if (r.alternatives.isEmpty()) {
                    ""
                } else {
                    " ask " +
                        r.alternatives.joinToString(",") { it.optionId ?: it.periodCode ?: "${it.startInclusive}" }
                }

        for (row in rows) {
            val text = row["text"]!!.jsonPrimitive.content
            val round = row["round"]!!.jsonPrimitive.int
            val reference = LocalDate.parse(row["reference"]!!.jsonPrimitive.content)
            val prior = row["prior"]!!.jsonArray.map { it.jsonPrimitive.content }
            val expected =
                row["spans"]!!.jsonArray.map {
                    it.jsonObject["text"]!!.jsonPrimitive.content to it.jsonObject["reading"]!!.jsonPrimitive.content
                }
            test("round $round: $text${if (prior.isEmpty()) "" else " (after: ${prior.joinToString()})"}") {
                val found =
                    scanner.scan(text, reference, priorQuestions = prior).map { span ->
                        val reading =
                            when (val r = recognizer.read(span.text, reference, context = listOf(text) + prior)) {
                                is ChronoReading.Recognized -> key(r.recognition)
                                is ChronoReading.Unanchored -> "unanchored"
                                null -> "null"
                            }
                        span.text to reading
                    }
                withClue(text) { found shouldBe expected }
            }
        }
    })
