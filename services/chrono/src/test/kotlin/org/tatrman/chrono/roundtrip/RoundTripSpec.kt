// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.roundtrip

import org.tatrman.grounding.v1.GroundingResult
import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.plan.v1.Value
import org.tatrman.translate.v1.Language
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContainIgnoringCase
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.chrono.FakeMetadataClient
import org.tatrman.chrono.recipe.RecipeBuilder
import org.tatrman.chrono.recognize.DateRecognizer
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.params.SqlParam
import java.time.LocalDate

/**
 * A8.5 — the derived-not-duplicated proof. Every recipe's `sql_preview`, wrapped into a full
 * `SELECT 1 FROM Sale AS t [JOIN …] WHERE …`, must parse + validate against the model through the
 * real Translator (`parseToRelNode`), and the parsed plan must still carry the recipe's condition
 * (e.g. the `period_start` catalog call). Proves the SQL text and the plan.v1 Expression tree agree.
 */
class RoundTripSpec :
    StringSpec({
        val translator = Translator(GroundingModelHandle.saleModel())
        val recognizer = DateRecognizer()
        val ref = LocalDate.of(2026, 5, 15)

        fun fake(withPeriodTable: Boolean): FakeMetadataClient {
            val rows =
                mutableListOf(
                    FakeMetadataClient.Obj("cnc", "Sale", "date", "attribute", role = "event_date"),
                    FakeMetadataClient.Obj("cnc", "Sale", "due", "attribute", role = "due_date"),
                )
            if (withPeriodTable) {
                rows +=
                    listOf(
                        FakeMetadataClient.Obj(
                            "cnc",
                            "AccountingPeriod",
                            "AccountingPeriod",
                            "entity",
                            semanticKind = "period_table",
                        ),
                        FakeMetadataClient.Obj(
                            "cnc",
                            "AccountingPeriod",
                            "start_date",
                            "attribute",
                            role = "period_start",
                        ),
                        FakeMetadataClient.Obj("cnc", "AccountingPeriod", "end_date", "attribute", role = "period_end"),
                        FakeMetadataClient.Obj(
                            "cnc",
                            "AccountingPeriod",
                            "period",
                            "attribute",
                            role = "period_code",
                            codeFormat = "yyyyMM",
                        ),
                    )
            }
            return FakeMetadataClient(rows)
        }

        fun toSqlParam(pb: ParameterBinding): SqlParam {
            val v = pb.value
            val value: Any? =
                when (v.vCase) {
                    Value.VCase.STRING_VALUE -> v.stringValue
                    Value.VCase.DATETIME_VALUE -> v.datetimeValue
                    Value.VCase.INT_VALUE -> v.intValue
                    Value.VCase.FLOAT_VALUE -> v.floatValue
                    Value.VCase.BOOL_VALUE -> v.boolValue
                    else -> null
                }
            return SqlParam(pb.name, pb.type, value)
        }

        suspend fun roundTrip(
            span: String,
            withPeriodTable: Boolean,
        ): ParseResult {
            val builder = RecipeBuilder(fake(withPeriodTable))
            val g = builder.build(span, recognizer.recognize(span, ref)!!, "cnc", "Europe/Prague")
            val (sql, params) =
                when (g.applicationCase) {
                    GroundingResult.ApplicationCase.JOIN ->
                        "SELECT 1 FROM Sale AS t ${g.sqlPreview}" to g.join.parametersList.map(::toSqlParam)
                    GroundingResult.ApplicationCase.FILTER ->
                        "SELECT 1 FROM Sale AS t WHERE ${g.sqlPreview}" to g.filter.parametersList.map(::toSqlParam)
                    else -> error("unexpected application ${g.applicationCase}")
                }
            return translator.parseToRelNode(sql, Language.SQL, parameters = params)
        }

        "table-backed period JoinRecipe sql_preview parses + validates against the model" {
            roundTrip("May 2026", withPeriodTable = true).shouldBeInstanceOf<ParseResult.Success>()
        }

        "calendar-aligned period FilterRecipe round-trips; the plan still carries period_start" {
            val r = roundTrip("May 2026", withPeriodTable = false)
            r.shouldBeInstanceOf<ParseResult.Success>()
            r.plan.toString().shouldContainIgnoringCase("period_start")
        }

        "plain-interval FilterRecipe sql_preview parses + validates" {
            roundTrip("yesterday", withPeriodTable = true).shouldBeInstanceOf<ParseResult.Success>()
        }

        "explicit-DUE recipe (anchors on the due column) round-trips" {
            roundTrip("due in May", withPeriodTable = true).shouldBeInstanceOf<ParseResult.Success>()
        }
    })
