// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono

import org.tatrman.grounding.v1.EntityKind
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import org.tatrman.grounding.v1.GroundingContext
import org.tatrman.grounding.v1.GroundingResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.tatrman.chrono.grpc.ChronoGroundingService

/**
 * TS-5 / contracts §2.5 — the new clarifications of TS-6 at the service: „poslední měsíc“ asks between the last
 * thirty days and the calendar month before (ids `trailing` / `calendar`), and a yearless half-year later than the
 * reference's asks between this year and last (ids are the `yyyyHn` codes). Labels are in the request's language.
 */
class TrailingClarificationSpec :
    StringSpec({
        fun request(
            span: String,
            locale: String,
            answerId: String = "",
            reference: String = "2026-11-01T12:00:00+01:00",
        ): GroundRequest =
            GroundRequest
                .newBuilder()
                .setSpanText(span)
                .setKind(EntityKind.DATE_TIME)
                .setPackage("cnc")
                .setClarificationAnswerId(answerId)
                .setContext(
                    GroundingContext
                        .newBuilder()
                        .setReferenceDatetime(reference)
                        .setTimezone("Europe/Prague")
                        .setLocale(locale),
                ).build()

        val service = ChronoGroundingService(FakeMetadataClient.accounting("cnc"), llmFallback = null)

        "„poslední měsíc“ asks: trailing first, calendar second, labelled in Czech" {
            val r = service.ground(request("poslední měsíc", "cs-CZ"))

            r.status shouldBe GroundResponse.Status.AWAITING_CLARIFICATION
            r.optionsList.map { it.id } shouldBe listOf("trailing", "calendar")
            r.optionsList.map { it.label } shouldBe listOf("posledních 30 dní", "říjen 2026")
            // A-TS-1: exactly thirty days, ending with the reference day — the count the label says.
            r.optionsList[0]
                .normalized.interval.start shouldStartWith "2026-10-03"
            r.optionsList[0]
                .normalized.interval.end shouldStartWith "2026-11-02"
            r.optionsList[1]
                .normalized.interval.start shouldStartWith "2026-10-01"
        }

        "'the last week' asks in English, the calendar option naming its week" {
            val r = service.ground(request("the last week", "en-US"))

            r.optionsList.map { it.id } shouldBe listOf("trailing", "calendar")
            r.optionsList.map { it.label } shouldBe listOf("the last 7 days", "the week of October 19, 2026")
        }

        "picking `calendar` grounds the calendar month before" {
            val r = service.ground(request("poslední měsíc", "cs-CZ", answerId = "calendar"))

            r.status shouldBe GroundResponse.Status.OK
            r.result.normalized.interval.start shouldStartWith "2026-10-01"
            r.result.normalized.interval.end shouldStartWith "2026-11-01"
        }

        "picking `trailing` grounds the last thirty days with datetime bounds" {
            val r = service.ground(request("poslední měsíc", "cs-CZ", answerId = "trailing"))

            r.status shouldBe GroundResponse.Status.OK
            r.result.applicationCase shouldBe GroundingResult.ApplicationCase.FILTER
            r.result.normalized.interval.start shouldStartWith "2026-10-03"
            r.result.normalized.interval.end shouldStartWith "2026-11-02"
        }

        "a yearless half-year later than the reference's asks, ids are the yyyyHn codes" {
            val r = service.ground(request("2. pololetí", "cs-CZ", reference = "2026-05-15T12:00:00+02:00"))

            r.status shouldBe GroundResponse.Status.AWAITING_CLARIFICATION
            r.optionsList.map { it.id } shouldBe listOf("2026H2", "2025H2")
            r.optionsList.map { it.label } shouldBe listOf("2. pololetí 2026", "2. pololetí 2025")
            val picked = service.ground(request("2. pololetí", "cs-CZ", "2025H2", "2026-05-15T12:00:00+02:00"))
            picked.result.normalized.interval.start shouldStartWith "2025-07-01"
            // A half-year never joins a period table: it filters on datetime bounds.
            picked.result.applicationCase shouldBe GroundingResult.ApplicationCase.FILTER
        }
    })
