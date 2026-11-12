// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.tatrman.chrono.grpc.ChronoGroundingService
import org.tatrman.chrono.recognize.ChronoKind
import org.tatrman.chrono.recognize.DateRecognizer
import org.tatrman.chrono.scan.SpanScanner
import org.tatrman.grounding.v1.EntityKind
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import org.tatrman.grounding.v1.GroundingContext
import org.tatrman.grounding.v1.GroundingResult
import org.tatrman.grounding.v1.ScanRequest
import java.time.LocalDate

/** S0.5 / review-244 Y1a: one inclusive-years interval, shared by Ground and Scan. */
class YearRangesSpec :
    StringSpec({
        val reference = LocalDate.of(2026, 11, 1)
        val recognizer = DateRecognizer()
        val scanner = SpanScanner()
        val forms =
            listOf(
                "2024-2025",
                "2024–2025",
                "2024—2025",
                "2024 - 2025",
                "2024 – 2025",
                "2024 — 2025",
                "2024 do 2025",
                "2024 až 2025",
                "2024 az 2025",
                "od 2024 do 2025",
                "2024 to 2025",
                "2024 until 2025",
                "2024 through 2025",
                "from 2024 to 2025",
            )
        for (phrase in forms) {
            "inclusive endpoint years: $phrase" {
                val r = recognizer.recognize(phrase, reference).shouldNotBeNull()
                r.startInclusive shouldBe LocalDate.of(2024, 1, 1)
                r.endExclusive shouldBe LocalDate.of(2026, 1, 1)
                r.kind shouldBe ChronoKind.CALENDAR_YEAR
                r.confidence shouldBe 0.9
                r.periodCode.shouldBeNull()
                r.alternatives shouldBe emptyList()
            }
            "Scan keeps the complete range and UTF-16 offsets: $phrase" {
                val text = "📈 Tržby $phrase pro Brno"
                val spans = scanner.scan(text, reference)
                spans.map { it.text } shouldBe listOf(phrase)
                val span = spans.single()
                span.start shouldBe text.indexOf(phrase)
                span.end shouldBe span.start + phrase.length
                text.substring(span.start, span.end) shouldBe phrase
                span.confidence shouldBe 0.9
            }
        }

        for ((phrase, start, end) in listOf(
            Triple("2024-2024", "2024-01-01", "2025-01-01"),
            Triple("1900-2999", "1900-01-01", "3000-01-01"),
        )) {
            "equal endpoints and plausible-year limits: $phrase" {
                val r = recognizer.recognize(phrase, reference).shouldNotBeNull()
                r.startIso shouldBe start
                r.endIso shouldBe end
                scanner.scan(phrase, reference).map { it.text } shouldBe listOf(phrase)
            }
        }

        for (phrase in listOf(
            "2025-2024",
            "2025 do 2024",
            "from 2025 to 2024",
            "1899-2025",
            "2024-3000",
            "2024-25",
            "2024 - 25",
            "12024-2025",
            "2024-20250",
            "doklad 120250",
            ".2024-2025",
            ",2024-2025",
            "/2024-2025",
            "-2024-2025",
            "2024-2025.",
            "2024-2025,",
            "2024-2025/",
            "2024-2025-",
            "2024 - 2025.0",
            "0.2024 - 2025",
            "2024-2025-2026",
            "fiscal year 2024/2025",
            "Q2 2024-2025",
            "H1 2024-2025",
            "March 2024-2025",
            "od Q2 2025",
            "do Q2 2025",
        )) {
            "a rejected complete range or period gives no salvaged part: $phrase" {
                recognizer.read(phrase, reference).shouldBeNull()
                scanner.scan("Tržby $phrase pro Brno", reference) shouldBe emptyList()
            }
        }

        for (phrase in listOf("2024 and 2025", "2024 a 2025")) {
            "a pair stays two independent Scan years: $phrase" {
                recognizer.read(phrase, reference).shouldBeNull()
                scanner.scan(phrase, reference).map { it.text } shouldBe listOf("2024", "2025")
            }
        }
        "do 2025 alone retains the whole-year reading" {
            val r = recognizer.recognize("do 2025", reference).shouldNotBeNull()
            r.startIso shouldBe "2025-01-01"
            r.endIso shouldBe "2026-01-01"
            scanner.scan("do 2025", reference).map { it.text } shouldBe listOf("2025")
        }

        "a full ISO date keeps its existing reading, a year-month stays unread" {
            val r = recognizer.recognize("2024-12-15", reference).shouldNotBeNull()
            r.startIso shouldBe "2024-12-15"
            r.endIso shouldBe "2024-12-16"
            recognizer.read("2024-12", reference).shouldBeNull()
            scanner.scan("2024-12", reference) shouldBe emptyList()
        }

        val context =
            GroundingContext
                .newBuilder()
                .setReferenceDatetime("2026-11-01T12:00:00+01:00")
                .setTimezone("Europe/Prague")
                .setLocale("cs-CZ")
                .build()
        for ((name, metadata, application) in listOf(
            Triple("no date role", FakeMetadataClient(emptyList()), GroundingResult.ApplicationCase.VALUES),
            Triple("period table", FakeMetadataClient.accounting("cnc"), GroundingResult.ApplicationCase.FILTER),
            Triple(
                "date-less period table",
                FakeMetadataClient(
                    listOf(
                        FakeMetadataClient.Obj("cnc", "Period", "Period", "entity", semanticKind = "period_table"),
                        FakeMetadataClient.Obj("cnc", "Period", "start", "attribute", role = "period_start"),
                        FakeMetadataClient.Obj("cnc", "Period", "end", "attribute", role = "period_end"),
                        FakeMetadataClient.Obj("cnc", "Period", "code", "attribute", role = "period_code"),
                    ),
                ),
                GroundingResult.ApplicationCase.VALUES,
            ),
        )) {
            "Ground and Scan use datetime bounds with $name" {
                val service = ChronoGroundingService(metadata, llmFallback = null)
                val question = "📈 Tržby od 2024 do 2025 pro Brno"
                val span =
                    service
                        .scan(
                            ScanRequest
                                .newBuilder()
                                .setText(question)
                                .setPackage("cnc")
                                .setContext(context)
                                .build(),
                        ).spansList
                        .single()
                span.text shouldBe "od 2024 do 2025"
                question.substring(span.start, span.end) shouldBe span.text
                val request =
                    GroundRequest
                        .newBuilder()
                        .setSpanText(span.text)
                        .setQuestionText(question)
                        .setKind(EntityKind.DATE_TIME)
                        .setPackage("cnc")
                        .setContext(context)
                        .build()
                val response = service.ground(request)
                response.status shouldBe GroundResponse.Status.OK
                val r = response.result
                r.source shouldBe GroundingResult.Source.RULES
                r.normalized.interval.start shouldBe "2024-01-01T00:00:00+01:00"
                r.normalized.interval.end shouldBe "2026-01-01T00:00:00+01:00"
                r.applicationCase shouldBe application
                val params = if (r.hasValues()) r.values.parametersList else r.filter.parametersList
                params.associate { it.name to it.value.datetimeValue } shouldBe
                    mapOf(
                        "start" to "2024-01-01T00:00:00+01:00",
                        "end" to "2026-01-01T00:00:00+01:00",
                    )
                r.sqlPreview shouldBe if (r.hasValues()) "" else "t.\"date\" >= {start} AND t.\"date\" < {end}"
                service.ground(request) shouldBe response
            }
        }
    })
