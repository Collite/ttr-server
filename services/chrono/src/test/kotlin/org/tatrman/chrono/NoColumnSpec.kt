// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono

import org.tatrman.grounding.v1.EntityKind
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import org.tatrman.grounding.v1.GroundingContext
import org.tatrman.grounding.v1.GroundingResult
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.chrono.grpc.ChronoGroundingService

/**
 * TS-1 (contracts §2) — recognising a phrase needs the reference date, applying it needs the model, and the
 * second missing must not throw away the first. A package that declares no date role
 * gets `OK` with the interval and a `ValueBinding`; `UNGROUNDABLE` is left for a span nothing was read in
 * and for #214's conversational `no_anchor`.
 */
class NoColumnSpec :
    StringSpec({
        // A package with entities but no date role anywhere: no event_date, no period table.
        val noRoles =
            FakeMetadataClient(
                listOf(
                    FakeMetadataClient.Obj("prodej", "Prodej", "rok", "attribute"),
                    FakeMetadataClient.Obj("prodej", "Prodej", "mesic", "attribute"),
                ),
            )

        fun request(
            span: String,
            locale: String,
            question: String = "",
            vararg prior: String,
        ): GroundRequest =
            GroundRequest
                .newBuilder()
                .setSpanText(span)
                .setQuestionText(question)
                .setKind(EntityKind.DATE_TIME)
                .setPackage("prodej")
                .setContext(
                    GroundingContext
                        .newBuilder()
                        .setReferenceDatetime("2026-11-01T12:00:00+01:00")
                        .setTimezone("Europe/Prague")
                        .setLocale(locale)
                        .addAllPriorQuestions(prior.toList()),
                ).build()

        val service = ChronoGroundingService(noRoles, llmFallback = null)

        "„loni“ with no date role → OK, the interval of 2025, values start/end, no SQL" {
            val r = service.ground(request("loni", "cs-CZ"))

            r.status shouldBe GroundResponse.Status.OK
            r.result.normalized.interval.start shouldBe "2025-01-01T00:00:00+01:00"
            r.result.normalized.interval.end shouldBe "2026-01-01T00:00:00+01:00"
            r.result.applicationCase shouldBe GroundingResult.ApplicationCase.VALUES
            r.result.values.parametersList
                .associate { it.name to it.value.datetimeValue } shouldBe
                mapOf("start" to "2025-01-01T00:00:00+01:00", "end" to "2026-01-01T00:00:00+01:00")
            r.result.sqlPreview shouldBe ""
            r.result.source shouldBe GroundingResult.Source.RULES
            r.result.explanation shouldContain "package 'prodej'"
            r.messagesList shouldBe emptyList()
        }

        "„minulý měsíc“ with no date role → October 2026, and p carries its period code" {
            val r = service.ground(request("minulý měsíc", "cs-CZ"))

            r.status shouldBe GroundResponse.Status.OK
            r.result.normalized.interval.start shouldBe "2026-10-01T00:00:00+02:00"
            r.result.normalized.interval.end shouldBe "2026-11-01T00:00:00+01:00"
            r.result.values.parametersList
                .map { it.name } shouldBe listOf("start", "end", "p")
            r.result.values.parametersList
                .single { it.name == "p" }
                .value.stringValue shouldBe "202610"
        }

        "„last year“ with no date role → OK with values, in English too" {
            val r = service.ground(request("last year", "en-US"))

            r.status shouldBe GroundResponse.Status.OK
            r.result.applicationCase shouldBe GroundingResult.ApplicationCase.VALUES
            r.result.normalized.interval.start shouldBe "2025-01-01T00:00:00+01:00"
        }

        // #214 stands: the conversational anchor is not the anchor column. A word with nothing in the conversation
        // to count from is refused whether or not the model declares a date column.
        "an anaphoric word with no conversational anchor stays UNGROUNDABLE no_anchor on a package with no column" {
            val r =
                service.ground(
                    request(
                        "the previous year",
                        "en-US",
                        "And the previous year?",
                        "Marketplace revenue in 2024 and in 2025",
                    ),
                )

            r.status shouldBe GroundResponse.Status.UNGROUNDABLE
            r.messagesList.single().code shouldBe "no_anchor"
        }

        "a span nothing is read in stays UNGROUNDABLE — that, not a missing column, is what the status means" {
            val r = service.ground(request("qwerty nonsense", "en-US"))

            r.status shouldBe GroundResponse.Status.UNGROUNDABLE
            r.messagesList.single().humanMessage shouldContain "could not recognize"
        }
    })
