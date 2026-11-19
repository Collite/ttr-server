// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.*
import org.tatrman.automation.runner.*
import org.tatrman.ttr.metadata.model.AutomationDuration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class PreviewWorkingDaysDueSpec :
    StringSpec({
        val bundle = BundleFixture.load(CalendarFixture.add(BundleFixture.copy()))
        val expect = legacyPreviewMetadata(bundle).actions.getValue("expect")
        val recipe =
            bundle.recipes.getValue("release_order").recipe.copy(
                objectsRead = listOf("er.entity.date_dim"),
                wiring =
                    listOf(
                        ManifestWiring(listOf("way.hold"), "expect", mapOf("due" to "working_days(:settlement)")),
                    ),
            )
        // Obtain the same governed plan as the existing count path; the new due trigger is tested separately.
        val count =
            recipe.copy(
                wiring =
                    listOf(
                        ManifestWiring(
                            listOf("way.hold"),
                            "expect",
                            mapOf(
                                "due" to "working_days(between: start, and: :as_of)",
                            ),
                        ),
                    ),
            )
        val plan = PreviewCalendar.plan(bundle, "demo", count, mapOf("expect" to expect))!!
        val snapshot =
            CalendarSnapshot(
                plan,
                Table(
                    plan.columns,
                    (16..30)
                        .map { day ->
                            val date = LocalDate.of(2026, 11, day)
                            listOf(date, date.dayOfWeek.value <= 5 && day != 17)
                        }.reversed(), // source order is not a date-order guarantee
                ),
            )
        val site = PreviewSite("working-days-due", "#0:way.hold->expect", "expect")
        val input = Table(emptyList(), listOf(emptyList()))
        val bindings =
            mapOf(
                "as_of" to BoundValue("datetime", Instant.parse("2026-11-16T12:00:00Z")),
                "settlement" to BoundValue("working_days", 5L),
            )

        fun due(
            source: String? = "working_days(:settlement)",
            calendar: CalendarSnapshot? = snapshot,
            values: Map<String, BoundValue> = bindings,
            action: org.tatrman.ttr.metadata.model.AutomationAction = expect,
        ): JsonElement =
            PreviewWiringValues
                .project(
                    PreviewWiringValues.prepare(source?.let { mapOf("due" to it) }.orEmpty(), action, site),
                    input,
                    values,
                    mapOf("due" to "date"),
                    emptySet(),
                    calendar,
                    site,
                    ZoneId.of("Europe/Prague"),
                ).single()
                .getValue("due")

        "S2.9b T1a: the fifth working day after Monday skips the holiday and weekend" {
            due() shouldBe JsonPrimitive("2026-11-24")
            due("5 working_days") shouldBe JsonPrimitive("2026-11-24")
            due("working_days(5)") shouldBe JsonPrimitive("2026-11-24")
            due(null, action = expect.copy(due = AutomationDuration(5.0, "working_days"))) shouldBe
                JsonPrimitive("2026-11-24")
        }
        "S2.9b T1b: zero and one both name the first strictly later working day" {
            due("working_days(0)") shouldBe JsonPrimitive("2026-11-18")
            due("working_days(1)") shouldBe JsonPrimitive("2026-11-18")
        }
        "S2.9b T1c: the world's local date rolls at 23:30Z" {
            due(
                "working_days(1)",
                values =
                    bindings + ("as_of" to BoundValue("datetime", Instant.parse("2026-11-18T23:30:00Z"))),
            ) shouldBe
                JsonPrimitive("2026-11-20")
        }
        "S2.9b T1d: insufficient horizon is one named refusal at due" {
            val short =
                snapshot.copy(
                    table =
                        snapshot.table.copy(
                            rows =
                                snapshot.table.rows.filter {
                                    (it.first() as LocalDate).dayOfMonth <=
                                        20
                                },
                        ),
                )
            for (calendar in listOf(short, snapshot.copy(table = snapshot.table.copy(rows = emptyList())))) {
                val error = shouldThrow<AutomationException> { due(calendar = calendar) }
                error.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
                error.detail shouldBe
                    buildJsonObject {
                        put("wiring", site.wiring)
                        put("action", "expect")
                        put("kind", "expression")
                        put("expression", "due")
                        put("cause", "calendar-horizon")
                    }
            }
            shouldThrow<AutomationException> {
                due(values = bindings + ("settlement" to BoundValue("int", Long.MAX_VALUE)))
            }.detail!!.jsonObject["cause"] shouldBe JsonPrimitive("calendar-horizon")
        }
        "S2.9b T1e: working-days due triggers a governed calendar and cannot authorize an undeclared read" {
            PreviewCalendar.plan(bundle, "demo", recipe, mapOf("expect" to expect)) shouldBe plan
            shouldThrow<AutomationException> {
                PreviewCalendar.plan(bundle, "demo", recipe.copy(objectsRead = emptyList()), mapOf("expect" to expect))
            }.code shouldBe ErrorCode.OUT_OF_SCOPE_READ
            val defaulted = recipe.copy(wiring = recipe.wiring.map { it.copy(columns = emptyMap()) })
            PreviewCalendar.plan(
                bundle,
                "demo",
                defaulted,
                mapOf(
                    "expect" to expect.copy(due = AutomationDuration(5.0, "working_days")),
                ),
            ) shouldBe
                plan
        }
        "D-254: mixed duration cases in either branch order remain named refusals" {
            for (source in listOf(
                "case when true then days(2) else working_days(5) end",
                "case when true then working_days(5) else days(2) end",
            )) {
                val error = shouldThrow<AutomationException> { due(source, calendar = null) }
                error.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
                error.detail!!.jsonObject["expression"] shouldBe JsonPrimitive("due")
                error.detail!!.jsonObject["cause"] shouldBe JsonPrimitive("TtrpEmitException")
            }
        }
    })
