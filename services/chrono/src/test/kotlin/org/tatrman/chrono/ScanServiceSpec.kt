// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader
import org.tatrman.chrono.grpc.ChronoGroundingService
import org.tatrman.chrono.obs.ChronoMetrics
import org.tatrman.common.v1.Severity
import org.tatrman.grounding.v1.EntityKind
import org.tatrman.grounding.v1.GetStatusRequest
import org.tatrman.grounding.v1.GroundingContext
import org.tatrman.grounding.v1.ScanRequest

/**
 * TS-P0 S0.3 T4, T6 (contracts §3) — `Scan` on the service: the context checked as for `Ground`, the spans as
 * `TimeSpan`s, `chrono_scan_total{spans}` and its latency, and a pure function of its input (§3.7).
 */
class ScanServiceSpec :
    StringSpec({
        fun request(
            text: String,
            reference: String = "2026-11-01T12:00:00+01:00",
            vararg prior: String,
        ): ScanRequest =
            ScanRequest
                .newBuilder()
                .setText(text)
                .setPackage("prodej")
                .setContext(
                    GroundingContext
                        .newBuilder()
                        .setReferenceDatetime(reference)
                        .setTimezone("Europe/Prague")
                        .setLocale("cs-CZ")
                        .addAllPriorQuestions(prior.toList()),
                ).build()

        val service = ChronoGroundingService(FakeMetadataClient(emptyList()), llmFallback = null)

        "the DONE question gives one span, „minulý měsíc“ at [19, 31)" {
            val r = service.scan(request("Tržby z tržiště za minulý měsíc pro Brno DC"))
            r.spansList.size shouldBe 1
            val span = r.spansList.single()
            span.start shouldBe 19
            span.end shouldBe 31
            span.text shouldBe "minulý měsíc"
            span.kind shouldBe EntityKind.DATE_TIME
            span.confidence shouldBe 0.9f
            span.trigger shouldBe "relative"
            r.messagesList.shouldBeEmpty()
        }

        "with no reference date there are no spans, and a message says why" {
            val r = service.scan(request("Tržby za minulý měsíc", reference = ""))
            r.spansList.shouldBeEmpty()
            r.messagesList.single().let {
                it.code shouldBe "reference_required"
                it.severity shouldBe Severity.ERROR
            }
        }

        "the earlier questions give an anaphoric period something to read against" {
            // Alone it reads against the reference date; after a question about Q3 2025, against that quarter.
            service
                .scan(request("A předchozí čtvrtletí?"))
                .spansList
                .single()
                .confidence shouldBe 0.9f
            service
                .scan(request("A předchozí čtvrtletí?", prior = arrayOf("Tržby za 3. čtvrtletí 2025")))
                .spansList
                .single()
                .let {
                    it.text shouldBe "předchozí čtvrtletí"
                    it.confidence shouldBe 0.85f
                }
            // After a question whose quarter chrono cannot place, it is still a time phrase: unanchored.
            service
                .scan(request("And the previous quarter?", prior = arrayOf("Revenue plan for Q2 next year")))
                .spansList
                .single()
                .let {
                    it.text shouldBe "previous quarter"
                    it.trigger shouldBe "conversation"
                }
        }

        "a quoted literal the caller blanked gives nothing" {
            service.scan(request("Kolik objednal zákazník \"    \" letos?")).spansList.map { it.text } shouldBe
                listOf("letos")
            service.scan(request("Kolik objednal zákazník \"    \"?")).spansList.shouldBeEmpty()
        }

        "Scan is a pure function of its request: the same bytes twice, and GetStatus unchanged (T6)" {
            val before = service.getStatus(GetStatusRequest.getDefaultInstance())
            val req =
                request("Tržby za 1. a 2. čtvrtletí, minulý měsíc a letos v Brně", prior = arrayOf("Tržby v roce 2025"))
            val first = service.scan(req).toByteArray()
            val second = service.scan(req).toByteArray()
            second.contentEquals(first) shouldBe true
            ChronoGroundingService(FakeMetadataClient(emptyList()), llmFallback = null)
                .scan(req)
                .toByteArray()
                .contentEquals(first) shouldBe true
            val after = service.getStatus(GetStatusRequest.getDefaultInstance())
            after.capabilitiesMap["lexicon_slice"] shouldBe before.capabilitiesMap["lexicon_slice"]
            after.capabilitiesMap["lexicon_slice_terms"] shouldBe before.capabilitiesMap["lexicon_slice_terms"]
        }

        "chrono_scan_total counts the calls by how many spans they found, and the latency is recorded" {
            val reader = InMemoryMetricReader.create()
            val provider = SdkMeterProvider.builder().registerMetricReader(reader).build()
            val metered =
                ChronoGroundingService(
                    FakeMetadataClient(emptyList()),
                    llmFallback = null,
                    metrics = ChronoMetrics(provider.get("chrono")),
                )
            metered.scan(request("Tržby za minulý měsíc"))
            metered.scan(request("Tržby v Brně"))
            metered.scan(request("Revenue this quarter and last month, and today"))
            val metrics = reader.collectAllMetrics()
            val spans =
                metrics
                    .first { it.name == "chrono_scan_total" }
                    .longSumData.points
                    .associate { it.attributes.get(AttributeKey.stringKey("spans")) to it.value }
            spans shouldBe mapOf("1" to 1L, "0" to 1L, "3+" to 1L)
            metrics.any { it.name == "chrono_scan_latency_ms" } shouldBe true
        }
    })
