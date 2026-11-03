// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recipe

import org.tatrman.grounding.v1.GroundingResult
import org.tatrman.plan.v1.JoinType
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.tatrman.chrono.FakeMetadataClient
import org.tatrman.chrono.recognize.DateRecognizer
import java.time.LocalDate

/**
 * A8.4 — recognizer → recipe, one case per recipe shape. Recognitions come from the real
 * [DateRecognizer] (integration), metadata from [FakeMetadataClient]. sql_preview structure is
 * asserted here; A8.5 adds the Translator round-trip.
 */
class RecipeBuilderSpec :
    StringSpec({
        val ref = LocalDate.of(2026, 5, 15)
        val tz = "Europe/Prague"
        val recognizer = DateRecognizer()
        val tableBacked = RecipeBuilder(FakeMetadataClient.accounting("cnc"))

        "table-backed 'May 2026' → JoinRecipe on AccountingPeriod with code 202605" {
            val rec = recognizer.recognize("May 2026", ref).shouldNotBeNull()
            val r = tableBacked.build("May 2026", rec, "cnc", tz)

            r.applicationCase shouldBe GroundingResult.ApplicationCase.JOIN
            val join = r.join
            join.entity.name shouldBe "AccountingPeriod"
            join.joinType shouldBe JoinType.INNER
            join.suggestedAlias shouldBe "ap"
            join.onCondition.function.operation shouldBe "and"
            join.onCondition.function
                .getOperands(0)
                .function.operation shouldBe "ge"
            join.onCondition.function
                .getOperands(1)
                .function.operation shouldBe "lt" // exclusive end
            join.filter.function.operation shouldBe "eq"
            val p = join.parametersList.single()
            p.name shouldBe "p"
            p.type shouldBe "text"
            p.value.stringValue shouldBe "202605"

            r.sqlPreview shouldContain "JOIN \"AccountingPeriod\" AS ap"
            r.sqlPreview shouldContain "t.\"date\" >= ap.\"start_date\""
            r.sqlPreview shouldContain "t.\"date\" < ap.\"end_date\""
            r.sqlPreview shouldContain "ap.\"period\" = {p}"
            r.source shouldBe GroundingResult.Source.RULES
            r.normalized.interval.start shouldContain "2026-05-01"
            r.normalized.interval.end shouldContain "2026-06-01"
        }

        "calendar-aligned 'May 2026' (no period table) → FilterRecipe over period_start/period_end" {
            val noTable =
                RecipeBuilder(
                    FakeMetadataClient(
                        listOf(
                            FakeMetadataClient.Obj("cnc", "Transaction", "date", "attribute", role = "event_date"),
                        ),
                    ),
                )
            val rec = recognizer.recognize("May 2026", ref).shouldNotBeNull()
            val r = noTable.build("May 2026", rec, "cnc", tz)

            r.applicationCase shouldBe GroundingResult.ApplicationCase.FILTER
            r.sqlPreview shouldContain "period_start({p})"
            r.sqlPreview shouldContain "period_end({p})"
            r.filter.parametersList
                .single()
                .value.stringValue shouldBe "202605"
            r.filter.anchorColumn.name shouldBe "date"
        }

        "plain interval 'yesterday' → FilterRecipe with datetime bounds" {
            val rec = recognizer.recognize("yesterday", ref).shouldNotBeNull()
            val r = tableBacked.build("yesterday", rec, "cnc", tz)

            r.applicationCase shouldBe GroundingResult.ApplicationCase.FILTER
            r.filter.parametersList.map { it.name } shouldBe listOf("start", "end")
            r.filter.parametersList[0]
                .value.datetimeValue shouldContain "2026-05-14"
            r.filter.parametersList[1]
                .value.datetimeValue shouldContain "2026-05-15" // exclusive next day
            r.sqlPreview shouldContain "t.\"date\" >= {start}"
            r.sqlPreview shouldContain "t.\"date\" < {end}"
        }

        // The hartland regression, at the recipe layer: "…revenues for 2025 by month?". A bare year
        // has no period code, so it must take the interval branch and land on the anchor column —
        // even against an estate that DOES declare a period table, which `tableBacked` does.
        "bare '2025' → FilterRecipe on the anchor column, not a period-table join" {
            val rec = recognizer.recognize("2025", ref).shouldNotBeNull()
            val r = tableBacked.build("2025", rec, "cnc", tz)

            r.applicationCase shouldBe GroundingResult.ApplicationCase.FILTER
            r.filter.anchorColumn.name shouldBe "date"
            r.filter.parametersList.map { it.name } shouldBe listOf("start", "end")
            r.filter.parametersList[0]
                .value.datetimeValue shouldContain "2025-01-01"
            // Exclusive end — the whole point of the half-open contract (§1.1).
            r.filter.parametersList[1]
                .value.datetimeValue shouldContain "2026-01-01"
            r.sqlPreview shouldContain "t.\"date\" >= {start}"
            r.sqlPreview shouldContain "t.\"date\" < {end}"
            r.normalized.interval.start shouldContain "2025-01-01"
            r.normalized.interval.end shouldContain "2026-01-01"
        }

        "explicit DUE target anchors the recipe on the due_date column" {
            val rec = recognizer.recognize("due in May", ref).shouldNotBeNull()
            val r = tableBacked.build("due in May", rec, "cnc", tz)
            r.sqlPreview shouldContain "t.\"due\" >= ap.\"start_date\""
        }

        // TS-1 (contracts §2.3): a package that declares no date role still gets the interval it asked
        // about — as values, with no column and no SQL, because where they go is the caller's.
        val noRoles = RecipeBuilder(FakeMetadataClient(emptyList()))

        "no anchor column, no period table: 'loni' → ValueBinding start/end, no SQL, the package named" {
            val rec = recognizer.recognize("loni", LocalDate.of(2026, 11, 1)).shouldNotBeNull()
            val r = noRoles.build("loni", rec, "prodej", tz)

            r.applicationCase shouldBe GroundingResult.ApplicationCase.VALUES
            r.values.parametersList.map { it.name } shouldBe listOf("start", "end")
            r.values.parametersList.map { it.type } shouldBe listOf("datetime", "datetime")
            r.values.parametersList[0]
                .value.datetimeValue shouldBe "2025-01-01T00:00:00+01:00"
            r.values.parametersList[1]
                .value.datetimeValue shouldBe "2026-01-01T00:00:00+01:00" // exclusive
            r.normalized.interval.start shouldBe "2025-01-01T00:00:00+01:00"
            r.normalized.interval.end shouldBe "2026-01-01T00:00:00+01:00"
            r.sqlPreview shouldBe ""
            r.explanation shouldBe
                "interval computed; no anchor date column declared in package 'prodej' — placement is the caller's"
            r.source shouldBe GroundingResult.Source.RULES
            r.confidence shouldBe rec.confidence.toFloat()
        }

        "no anchor column, a period recognition: the values also carry p as the period code" {
            val month = noRoles.build("May 2026", recognizer.recognize("May 2026", ref).shouldNotBeNull(), "cnc", tz)
            month.applicationCase shouldBe GroundingResult.ApplicationCase.VALUES
            month.values.parametersList.map { it.name } shouldBe listOf("start", "end", "p")
            month.values.parametersList[2].type shouldBe "text"
            month.values.parametersList[2]
                .value.stringValue shouldBe "202605" // the default yyyyMM code_format

            // A quarter keeps its own yyyyQn code (Q-18).
            val quarterRec = recognizer.recognize("poslední fiskální čtvrtletí", ref).shouldNotBeNull()
            val quarter = noRoles.build("poslední fiskální čtvrtletí", quarterRec, "cnc", tz)
            quarter.applicationCase shouldBe GroundingResult.ApplicationCase.VALUES
            quarter.values.parametersList
                .single { it.name == "p" }
                .value.stringValue shouldBe "2026Q1"
            quarter.normalized.interval.start shouldContain "2026-01-01"
        }

        // Issue #140 — a period-coded fact with NO date column (accounting ledger keyed only by
        // period): the recipe binds the period code directly (`code = {p}`), no fact anchor needed.
        val datelessLedger =
            RecipeBuilder(
                FakeMetadataClient(
                    listOf(
                        FakeMetadataClient.Obj(
                            "ucet",
                            "UcetniObdobi",
                            "UcetniObdobi",
                            "entity",
                            semanticKind = "period_table",
                        ),
                        FakeMetadataClient.Obj(
                            "ucet",
                            "UcetniObdobi",
                            "PrvniDen",
                            "attribute",
                            role = "period_start",
                        ),
                        FakeMetadataClient.Obj("ucet", "UcetniObdobi", "PoslDen", "attribute", role = "period_end"),
                        FakeMetadataClient.Obj(
                            "ucet",
                            "UcetniObdobi",
                            "UCET_OBD",
                            "attribute",
                            role = "period_code",
                            codeFormat = "yyyy.MM",
                        ),
                        // No event_date / posting_date anywhere → anchorColumn is null.
                    ),
                ),
            )

        "date-less period-coded fact 'minulý měsíc' → FilterRecipe code = {p} (reformatted per code_format)" {
            val ref2 = LocalDate.of(2026, 7, 10)
            val rec = recognizer.recognize("minulý měsíc", ref2).shouldNotBeNull()
            rec.periodCode shouldBe "202606" // relative month now carries a period code

            val r = datelessLedger.build("minulý měsíc", rec, "ucet", tz)
            r.applicationCase shouldBe GroundingResult.ApplicationCase.FILTER
            r.filter.condition.function.operation shouldBe "eq"
            r.filter.anchorColumn.name shouldBe "UCET_OBD"
            val p = r.filter.parametersList.single()
            p.name shouldBe "p"
            p.type shouldBe "text"
            p.value.stringValue shouldBe "2026.06" // yyyyMM 202606 → yyyy.MM
            r.sqlPreview shouldContain "= {p}"
            r.normalized.interval.start shouldContain "2026-06-01"
            r.normalized.interval.end shouldContain "2026-07-01"
        }

        // The period-code route needs a period code: a year carries none (it must not route through the
        // period table), so on a date-less ledger it is the values arm, not the code filter.
        "date-less period-coded fact, a phrase with no period code ('loni') → ValueBinding, not a code filter" {
            val rec = recognizer.recognize("loni", ref).shouldNotBeNull()
            rec.periodCode shouldBe null
            val r = datelessLedger.build("loni", rec, "ucet", tz)
            r.applicationCase shouldBe GroundingResult.ApplicationCase.VALUES
            r.values.parametersList.map { it.name } shouldBe listOf("start", "end")
            r.normalized.interval.start shouldContain "2025-01-01"
        }

        // TS-6: a half-year (yyyyHn) has no period-table row and no catalog function — it never joins and
        // never binds a monthly code column; with a date column it is datetime bounds, without one values.
        "half-year with a period table → datetime-bounds FilterRecipe, not a join" {
            val rec = recognizer.recognize("1. pololetí 2026", ref).shouldNotBeNull()
            val r = tableBacked.build("1. pololetí 2026", rec, "cnc", tz)
            r.applicationCase shouldBe GroundingResult.ApplicationCase.FILTER
            r.sqlPreview shouldContain "t.\"date\" >= {start} AND t.\"date\" < {end}"
            r.filter.parametersList.map { it.name } shouldBe listOf("start", "end")
            r.normalized.interval.end shouldContain "2026-07-01"
        }

        "half-year with no column → values carrying p = yyyyHn; on a date-less ledger too, never its code filter" {
            val rec = recognizer.recognize("1. pololetí 2026", ref).shouldNotBeNull()
            for ((builder, pkg) in listOf(noRoles to "cnc", datelessLedger to "ucet")) {
                val r = builder.build("1. pololetí 2026", rec, pkg, tz)
                r.applicationCase shouldBe GroundingResult.ApplicationCase.VALUES
                r.values.parametersList
                    .single { it.name == "p" }
                    .value.stringValue shouldBe "2026H1"
            }
        }

        // review-215 R1: a period code meets a period table only in the table's own grain. A quarter against a
        // monthly table (the usual one) matches no row: it takes datetime bounds, or values with no column.
        val quarterly =
            RecipeBuilder(
                FakeMetadataClient(
                    listOf(
                        FakeMetadataClient.Obj("cnc", "Quarter", "Quarter", "entity", semanticKind = "period_table"),
                        FakeMetadataClient.Obj("cnc", "Quarter", "start_date", "attribute", role = "period_start"),
                        FakeMetadataClient.Obj("cnc", "Quarter", "end_date", "attribute", role = "period_end"),
                        FakeMetadataClient.Obj(
                            "cnc",
                            "Quarter",
                            "period",
                            "attribute",
                            role = "period_code",
                            codeFormat = "yyyyQn",
                        ),
                        FakeMetadataClient.Obj("cnc", "Transaction", "date", "attribute", role = "event_date"),
                    ),
                ),
            )
        val q2 = recognizer.recognize("2. čtvrtletí 2026", ref).shouldNotBeNull()

        "a quarter against a monthly period table, with a date column → datetime bounds, no join" {
            val r = tableBacked.build("2. čtvrtletí 2026", q2, "cnc", tz)
            r.applicationCase shouldBe GroundingResult.ApplicationCase.FILTER
            r.sqlPreview shouldBe "t.\"date\" >= {start} AND t.\"date\" < {end}"
            r.sqlPreview shouldNotContain "ap."
            r.normalized.interval.start shouldContain "2026-04-01"
            r.normalized.interval.end shouldContain "2026-07-01"
        }

        "a quarter against a quarterly period table → the join, as Q-18 built it" {
            val r = quarterly.build("2. čtvrtletí 2026", q2, "cnc", tz)
            r.applicationCase shouldBe GroundingResult.ApplicationCase.JOIN
            r.join.parametersList
                .single()
                .value.stringValue shouldBe "2026Q2"
        }

        "a quarter against a monthly period table, no date column → values with p = yyyyQn, not the code filter" {
            val r = datelessLedger.build("2. čtvrtletí 2026", q2, "ucet", tz)
            r.applicationCase shouldBe GroundingResult.ApplicationCase.VALUES
            r.values.parametersList
                .single { it.name == "p" }
                .value.stringValue shouldBe "2026Q2"
            r.sqlPreview shouldBe ""
        }

        "a month against a quarterly period table → the catalog functions, never a join on the quarter code" {
            val rec = recognizer.recognize("May 2026", ref).shouldNotBeNull()
            val r = quarterly.build("May 2026", rec, "cnc", tz)
            r.applicationCase shouldBe GroundingResult.ApplicationCase.FILTER
            r.sqlPreview shouldNotContain "ap."
            r.sqlPreview shouldContain "period_start({p})"
            r.filter.parametersList
                .single()
                .value.stringValue shouldBe "202605"
        }

        "date-less period-coded fact explicit 'období 202605' → FilterRecipe code = {p}" {
            val rec = recognizer.recognize("období 202605", ref).shouldNotBeNull()
            val r = datelessLedger.build("období 202605", rec, "ucet", tz)
            r.applicationCase shouldBe GroundingResult.ApplicationCase.FILTER
            r.filter.parametersList
                .single()
                .value.stringValue shouldBe "2026.05"
        }
    })
