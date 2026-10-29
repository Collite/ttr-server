// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.*
import org.tatrman.automation.bundle.BundleFixture.edit
import org.tatrman.automation.runner.*
import java.time.Instant
import java.time.LocalDate

class PreviewCalendarSpec :
    StringSpec({
        val bundle = BundleFixture.load(CalendarFixture.add(BundleFixture.copy()))
        val action = bundle.actions.getValue("notify_customer")
        val recipe =
            bundle.recipes.getValue("release_order").recipe.copy(
                wiring =
                    listOf(
                        ManifestWiring(
                            listOf("way.release"),
                            "notify_customer",
                            mapOf(
                                "template" to "age(since: placed)",
                            ),
                        ),
                    ),
                objectsRead = listOf("er.entity.date_dim"),
            )
        val plan = PreviewCalendar.plan(bundle, "demo", recipe, mapOf("notify_customer" to action))!!
        val calendar =
            Table(
                listOf(ManifestColumn("cal_date", "date"), ManifestColumn("working_day", "bool")),
                (23..28).map { day -> listOf(LocalDate.of(2026, 10, day), day !in setOf(24, 25, 27)) },
            )
        val snapshot = CalendarSnapshot(plan, calendar)
        val table =
            Table(
                listOf(ManifestColumn("placed", "date")),
                listOf(
                    listOf(LocalDate.of(2026, 10, 23)),
                    listOf(LocalDate.of(2026, 10, 23)),
                    listOf(null),
                    listOf(LocalDate.of(2026, 10, 28)),
                    listOf(LocalDate.of(2026, 10, 29)),
                ),
            )
        val bindings = mapOf("as_of" to BoundValue("datetime", Instant.parse("2026-10-28T12:00:00Z")))

        "declared packages pin a bound calendar to its actual logical storage" {
            plan.entity shouldBe "er.entity.date_dim"
            plan.storage shouldBe "erp_db"
            plan.columns.map { it.name } shouldBe listOf("cal_date", "working_day")
            PreviewCalendar.plan(bundle, "demo", recipe.copy(wiring = emptyList()), emptyMap()) shouldBe null
            shouldThrow<AutomationException> {
                PreviewCalendar.plan(
                    bundle,
                    "demo",
                    recipe.copy(objectsRead = emptyList()),
                    mapOf(
                        "notify_customer" to action,
                    ),
                )
            }.code shouldBe ErrorCode.OUT_OF_SCOPE_READ
            shouldThrow<AutomationException> {
                PreviewCalendar.plan(
                    bundle.copy(packages = emptyMap()),
                    "demo",
                    recipe,
                    mapOf(
                        "notify_customer" to action,
                    ),
                )
            }.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
        }
        "age does not require a binding for the unused working-day flag" {
            val root = CalendarFixture.add(BundleFixture.copy())
            root.edit("model/binding/calendar.ttrm") { source ->
                source.lines().filterNot { "calendar_working_map" in it }.joinToString("\n")
            }
            val unbound = BundleFixture.load(root)
            PreviewCalendar
                .plan(
                    unbound,
                    "demo",
                    recipe,
                    mapOf("notify_customer" to action),
                )!!
                .columns
                .map { it.name } shouldBe
                listOf("cal_date")
            val working =
                recipe.copy(
                    wiring =
                        listOf(
                            ManifestWiring(
                                listOf("way.release"),
                                "notify_customer",
                                mapOf("template" to "working_days(between: placed, and: :as_of)"),
                            ),
                        ),
                )
            shouldThrow<AutomationException> {
                PreviewCalendar.plan(unbound, "demo", working, mapOf("notify_customer" to action))
            }.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
        }

        "an event-date attribute named working_day is selected only once for age" {
            val root = CalendarFixture.add(BundleFixture.copy())
            for (name in listOf("model/er/calendar.ttrm", "model/binding/calendar.ttrm")) {
                root.edit(name) { it.replace("working_day", "is_working").replace("cal_date", "working_day") }
            }
            val renamed = BundleFixture.load(root)
            val selected = PreviewCalendar.plan(renamed, "demo", recipe, mapOf("notify_customer" to action))!!
            selected.columns shouldBe listOf(ManifestColumn("working_day", "date"))
            val dates = Table(selected.columns, calendar.rows.map { listOf(it.first()) })
            PreviewExpressions
                .project(
                    mapOf("n" to "age(since: placed)"),
                    table,
                    bindings,
                    calendar = CalendarSnapshot(selected, dates),
                ).rows shouldBe
                listOf(listOf(5L), listOf(5L), listOf(null), listOf(0L), listOf(0L))
        }

        "nested wiring and conditions plan the same calendar once per immutable bundle and world" {
            val nested =
                recipe.copy(
                    wiring =
                        listOf(
                            ManifestWiring(
                                listOf("way.release"),
                                "notify_customer",
                                mapOf("template" to "hold { value: age(since: placed) }"),
                            ),
                        ),
                )
            (PreviewCalendar.plan(bundle, "demo", nested, mapOf("notify_customer" to action)) === plan) shouldBe true
            val condition =
                recipe.copy(
                    wiring =
                        listOf(
                            ManifestWiring(
                                listOf("way.release"),
                                "notify_customer",
                                mapOf("template" to "'text'"),
                                whenCondition = "age(since: placed) > 1",
                            ),
                        ),
                )
            (PreviewCalendar.plan(bundle, "demo", condition, mapOf("notify_customer" to action)) === plan) shouldBe true
            bundle.prepared.calendarPlans.size shouldBe 1
        }

        "compiler calendar graph preserves boundaries repeated rows NULL and zero ranges" {
            val result =
                PreviewExpressions.project(
                    linkedMapOf(
                        "age" to "age(since: placed)",
                        "working" to "working_days(between: placed, and: :as_of)",
                    ),
                    table,
                    bindings,
                    calendar = snapshot,
                )
            result.rows shouldBe
                listOf(listOf(5L, 2L), listOf(5L, 2L), listOf(null, null), listOf(0L, 0L), listOf(0L, 0L))
            PreviewConditions.select(
                "working_days(between: placed, and: :as_of) >= 2",
                table,
                bindings,
                snapshot,
            ) shouldBe
                setOf(0, 1)
        }
        "empty routes validate and empty calendars yield zero" {
            PreviewExpressions
                .project(
                    mapOf("n" to "age(since: placed)"),
                    table.copy(rows = emptyList()),
                    bindings,
                    calendar = snapshot,
                ).rows shouldBe
                emptyList()
            PreviewExpressions
                .project(
                    mapOf("n" to "age(since: placed)"),
                    table,
                    bindings,
                    calendar = snapshot.copy(table = calendar.copy(rows = emptyList())),
                ).rows shouldBe
                listOf(listOf(0L), listOf(0L), listOf(null), listOf(0L), listOf(0L))
        }
        "private names avoid case variants and compiler helper inputs refuse" {
            val source =
                Table(
                    table.columns +
                        listOf(ManifestColumn("_AU_CALENDAR_2", "text"), ManifestColumn("_AU_CONDITION_0", "date")),
                    table.rows.map { it + listOf("original", it.first()) },
                )
            PreviewExpressions
                .project(
                    linkedMapOf(
                        "count" to "age(since: placed)",
                        "working" to "working_days(between: _AU_CONDITION_0, and: :as_of)",
                        "kept" to "_AU_CALENDAR_2",
                    ),
                    source,
                    bindings,
                    calendar = snapshot,
                ).rows shouldBe
                listOf(
                    listOf(5L, 2L, "original"),
                    listOf(5L, 2L, "original"),
                    listOf(null, null, "original"),
                    listOf(0L, 0L, "original"),
                    listOf(0L, 0L, "original"),
                )
            val reserved = table.copy(columns = listOf(ManifestColumn("_TTRP_N_VALUE_0", "date")))
            shouldThrow<AutomationException> {
                PreviewExpressions.project(
                    mapOf("n" to "cast(age(since: _TTRP_N_VALUE_0) as text)"),
                    reserved,
                    bindings,
                    calendar = snapshot,
                )
            }.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
        }

        "private calendar result names cannot satisfy an unknown authored column" {
            shouldThrow<AutomationException> {
                PreviewExpressions.project(
                    linkedMapOf("count" to "age(since: placed)", "unknown" to "_au_calendar_2"),
                    table,
                    bindings,
                    calendar = snapshot,
                )
            }.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
        }

        "invalid names types missing pins and unsupported column duration arithmetic refuse" {
            for (expression in listOf(
                "age(bogus: placed)",
                "age(since: placed, and: 12)",
                "working_days(between: placed)",
                "placed + days(2)",
            )) {
                shouldThrow<AutomationException> {
                    PreviewExpressions.project(mapOf("n" to expression), table, bindings, calendar = snapshot)
                }.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
            }
            shouldThrow<AutomationException> {
                PreviewExpressions.project(mapOf("n" to "age(since: placed)"), table, bindings)
            }.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
        }
    })
