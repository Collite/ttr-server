// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.tatrman.chrono.FakeMetadataClient
import org.tatrman.chrono.recipe.RecipeBuilder
import org.tatrman.grounding.v1.GroundingResult
import java.time.LocalDate

/**
 * RG-P3.S2.T3/T4 — Q-18 fiscal/accounting quarter. A relative quarter span ("poslední fiskální
 * čtvrtletí", "toto fiskální čtvrtletí", "minulé účetní čtvrtletí") resolves to a `yyyyQn` period
 * relative to the injected reference; the recipe is a **JoinRecipe** selecting the quarter row when
 * the package declares a `yyyyQn` period table, and the **honest calendar-quarter FilterRecipe**
 * (datetime bounds) when it doesn't. These fail if the recognizer only knows fiscal-*year* words.
 */
class FiscalQuarterTest :
    StringSpec({
        val recognizer = DateRecognizer()
        val ref = LocalDate.of(2026, 5, 15) // Q2 2026

        // A period table keyed by a yyyyQn quarter code (start/end/code columns).
        val quarterTable =
            FakeMetadataClient(
                listOf(
                    FakeMetadataClient.Obj(
                        "cnc",
                        "AccountingPeriod",
                        "AccountingPeriod",
                        "entity",
                        semanticKind = "period_table",
                    ),
                    FakeMetadataClient.Obj("cnc", "AccountingPeriod", "start_date", "attribute", role = "period_start"),
                    FakeMetadataClient.Obj("cnc", "AccountingPeriod", "end_date", "attribute", role = "period_end"),
                    FakeMetadataClient.Obj(
                        "cnc",
                        "AccountingPeriod",
                        "period",
                        "attribute",
                        role = "period_code",
                        codeFormat = "yyyyQn",
                    ),
                    FakeMetadataClient.Obj("cnc", "Transaction", "date", "attribute", role = "event_date"),
                ),
            )
        val noTable =
            FakeMetadataClient(
                listOf(FakeMetadataClient.Obj("cnc", "Transaction", "date", "attribute", role = "event_date")),
            )

        // ----- recognizer: relative quarter → yyyyQn PERIOD code -----

        "'toto fiskální čtvrtletí' → this quarter (Q2 2026), yyyyQn code + calendar interval" {
            val r = recognizer.recognize("toto fiskální čtvrtletí", ref).shouldNotBeNull()
            r.kind shouldBe ChronoKind.PERIOD
            r.periodCode shouldBe "2026Q2"
            r.startInclusive shouldBe LocalDate.of(2026, 4, 1)
            r.endExclusive shouldBe LocalDate.of(2026, 7, 1)
        }

        "'poslední fiskální čtvrtletí' → last quarter (Q1 2026)" {
            val r = recognizer.recognize("poslední fiskální čtvrtletí", ref).shouldNotBeNull()
            r.periodCode shouldBe "2026Q1"
            r.startInclusive shouldBe LocalDate.of(2026, 1, 1)
        }

        "'minulé účetní čtvrtletí' also reads as last quarter (Q1 2026)" {
            recognizer.recognize("minulé účetní čtvrtletí", ref).shouldNotBeNull().periodCode shouldBe "2026Q1"
        }

        "'last fiscal quarter' (en) → Q1 2026" {
            recognizer.recognize("last fiscal quarter", ref).shouldNotBeNull().periodCode shouldBe "2026Q1"
        }

        "a quarter word with no scope is not a match (left for the fallback)" {
            recognizer.recognize("fiskální čtvrtletí", ref) shouldBe null
        }

        // ----- recipe: yyyyQn period table → JoinRecipe on the quarter row -----

        "quarter + a yyyyQn period table → JoinRecipe binding the quarter code" {
            val recognition = recognizer.recognize("poslední fiskální čtvrtletí", ref)!!
            val r =
                runBlocking {
                    RecipeBuilder(
                        quarterTable,
                    ).build("poslední fiskální čtvrtletí", recognition, "cnc", "Europe/Prague")
                }
            r.applicationCase shouldBe GroundingResult.ApplicationCase.JOIN
            r.join.parametersList
                .first { it.name == "p" }
                .value.stringValue shouldBe "2026Q1"
        }

        // ----- recipe: no period table → honest calendar-quarter FilterRecipe (datetime bounds) -----

        "quarter + no period table → calendar-quarter FilterRecipe with datetime bounds" {
            val recognition = recognizer.recognize("poslední fiskální čtvrtletí", ref)!!
            val r =
                runBlocking {
                    RecipeBuilder(
                        noTable,
                    ).build("poslední fiskální čtvrtletí", recognition, "cnc", "Europe/Prague")
                }
            r.applicationCase shouldBe GroundingResult.ApplicationCase.FILTER
            // datetime bounds, NOT period_start(...) — the translator can't lower a yyyyQn code.
            r.sqlPreview shouldContain "t.\"date\" >= {start} AND t.\"date\" < {end}"
            r.filter.parametersList
                .first { it.name == "start" }
                .value.datetimeValue
                .shouldContain("2026-01-01")
            r.filter.parametersList
                .first { it.name == "end" }
                .value.datetimeValue
                .shouldContain("2026-04-01")
        }
    })
