// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.ManifestColumn
import org.tatrman.automation.bundle.BundleFixture
import org.tatrman.automation.bundle.legacyPreviewMetadata
import org.tatrman.automation.runner.BoundValue
import org.tatrman.automation.runner.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

class PreviewExpressionsSpec :
    StringSpec({
        val input =
            Table(
                listOf(
                    ManifestColumn("id", "int"),
                    ManifestColumn("amount", "decimal"),
                    ManifestColumn("name", "text"),
                ),
                listOf(
                    listOf(9007199254740993L, BigDecimal("4.560000000000000001"), "Sales"),
                    listOf(2L, null, null),
                    listOf(1L, BigDecimal("1.00"), "AR"),
                ),
            )
        val bindings = mapOf("increment" to BoundValue("decimal", BigDecimal("1.000000000000000001")))

        fun project(vararg expressions: Pair<String, String>) =
            PreviewExpressions.project(linkedMapOf(*expressions), input, bindings)

        "scalar projections retain authored argument order, route order, exact numbers and nulls" {
            val result = project("key" to "id", "sum" to "amount + :increment", "label" to "name")
            result.columns.map { it.name } shouldBe listOf("key", "sum", "label")
            result.rows.map { it[0] } shouldBe listOf(9007199254740993L, 2L, 1L)
            (result.rows[0][1] as BigDecimal).compareTo(BigDecimal("5.560000000000000002")) shouldBe 0
            result.rows[1] shouldBe listOf(2L, null, null)
        }
        "case expressions and boolean null tests are scalar values" {
            val result =
                project(
                    "label" to "case when name is null then \"missing\" else name end",
                    "missing" to "amount is null",
                )
            result.rows shouldBe listOf(listOf("Sales", false), listOf("missing", true), listOf("AR", false))
        }
        "subtraction multiplication and explicit text casts use compiler semantics" {
            val result = project("count" to "(id - 1) * 2", "label" to "cast(id as text)")
            result.rows.first() shouldBe listOf(18014398509481984L, "9007199254740993")
            result.rows.last() shouldBe listOf(0L, "1")
        }
        "constant literals and nulls repeat once per route row without double conversion" {
            val result =
                project(
                    "large" to "9223372036854775808",
                    "fraction" to "4.560000000000000001",
                    "none" to "null",
                )
            result.rows.size shouldBe 3
            result.rows.forEach { row ->
                (row[0] as BigDecimal).compareTo(BigDecimal("9223372036854775808")) shouldBe 0
                (row[1] as BigDecimal).compareTo(BigDecimal("4.560000000000000001")) shouldBe 0
                row[2] shouldBe null
            }
        }
        "date expressions use pinned UTC as-of without consulting the clock" {
            val result =
                PreviewExpressions.project(
                    mapOf("day" to "cast(:as_of as date)"),
                    input,
                    mapOf("as_of" to BoundValue("datetime", Instant.parse("2026-10-24T23:30:00Z"))),
                )
            result.rows shouldBe List(3) { listOf(LocalDate.parse("2026-10-24")) }
        }
        "invalid syntax types names functions aggregates and division refuse without bound data" {
            for (expression in listOf(
                "missing",
                "_au_condition_3 + 1",
                ":missing + 1",
                "amount +",
                "id; select 1",
                "name + 1",
                "sum(id)",
                "read_csv(\"/secret\")",
                "amount / 0",
                "id = name",
            )) {
                val error = shouldThrow<AutomationException> { project("value" to expression) }
                error.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
                error.message shouldBe "unsupported action expression"
            }
        }
        "execution errors and non-finite scalar results refuse without leaking data" {
            shouldThrow<AutomationException> { project("value" to "cast(name as int)") }.message shouldBe
                "unsupported action expression"
            val floats = Table(listOf(ManifestColumn("value", "float")), listOf(listOf(Double.MAX_VALUE)))
            shouldThrow<AutomationException> {
                PreviewExpressions.project(mapOf("value" to "value * value"), floats, emptyMap())
            }.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
        }

        "empty routes still validate scalar expressions and retain result schema" {
            val empty = input.copy(rows = emptyList())
            val result = PreviewExpressions.project(mapOf("value" to "amount + 1"), empty, bindings)
            result.rows shouldBe emptyList()
            result.columns.single().name shouldBe "value"
            shouldThrow<AutomationException> {
                PreviewExpressions.project(mapOf("value" to "unknown"), empty, bindings)
            }.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
        }
        "bound SQL-shaped text stays data and unreferenced opaque columns stay outside DuckDB" {
            val text = "Sales'; select read_csv('/secret'); -- ? :other"
            val opaque =
                input.copy(
                    columns = input.columns + ManifestColumn("opaque", "object"),
                    rows =
                        input.rows.map {
                            it +
                                mapOf("secret" to 1)
                        },
                )
            PreviewExpressions
                .project(
                    mapOf("value" to ":text"),
                    opaque,
                    mapOf("text" to BoundValue("text", text)),
                ).rows shouldBe
                List(3) { listOf(text) }
        }
        "no scalar arguments still retain one empty argument record per selected row" {
            project().rows shouldBe List(3) { emptyList() }
        }
        // S2.8 (D-239, ttr-server#443): the refusal names its wiring and cause; the log carries the redacted cause.
        val site = PreviewSite("run-s28", "#2:way.hold->release", "release")
        val secret = "SECRET-VALUE-7"
        val secrets = bindings + ("code" to BoundValue("text", secret))

        fun refusal(
            vararg expressions: Pair<String, String>,
            table: Table = input,
        ): AutomationException =
            shouldThrow<AutomationException> {
                PreviewExpressions.project(linkedMapOf(*expressions), table, secrets, site = site)
            }

        fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.content

        fun AutomationException.public(): String = envelope().toString()

        fun captured(block: () -> Unit): List<ILoggingEvent> {
            val logger = LoggerFactory.getLogger(PreviewRefusals::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            try {
                block()
            } finally {
                logger.detachAppender(appender)
            }
            return appender.list.toList()
        }

        "(a) a column the staged table lacks names the wiring, action, expression and cause, never SQL or data" {
            val error = refusal("key" to "id", "value" to "missing_column = :code")
            error.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
            error.message shouldBe "unsupported action expression"
            val detail = error.detail as JsonObject
            detail.text("wiring") shouldBe "#2:way.hold->release"
            detail.text("action") shouldBe "release"
            detail.text("kind") shouldBe "expression"
            detail.text("expression") shouldBe "value"
            detail.text("cause") shouldBe "TTRP-EXP-001"
            detail.keys shouldBe setOf("wiring", "action", "kind", "expression", "cause")
            for (leak in listOf(secret, "SELECT", "select", "au_condition", "FROM", "missing_column")) {
                error.public() shouldNotContain leak
            }
        }
        "(b) an unknown function has the same shape and a different cause" {
            val missing = refusal("value" to "missing_column").detail as JsonObject
            val error = refusal("value" to "no_such_function(id)")
            val detail = error.detail as JsonObject
            detail.keys shouldBe missing.keys
            detail.text("wiring") shouldBe "#2:way.hold->release"
            detail.text("expression") shouldBe "value"
            detail.text("cause") shouldBe "TTRP-FN-001"
            detail.text("cause") shouldNotBe missing.text("cause")
            error.public() shouldNotContain "no_such_function"
        }
        "an execution error names the exception class, and a bound value never reaches the public error" {
            val error = refusal("key" to "id", "value" to "cast(:code as int)")
            val detail = error.detail as JsonObject
            detail.text("expression") shouldBe "value"
            detail.text("cause") shouldBe "SQLException"
            error.public() shouldNotContain secret
        }
        "(c) a condition names its kind and the wiring" {
            val error =
                shouldThrow<AutomationException> {
                    PreviewExpressions.select("missing_column > :code", input, secrets, site = site)
                }
            error.message shouldBe "unsupported action condition"
            val detail = error.detail as JsonObject
            detail.text("kind") shouldBe "condition"
            detail.text("wiring") shouldBe "#2:way.hold->release"
            detail.text("action") shouldBe "release"
            detail.text("expression") shouldBe "when"
            detail.text("cause") shouldBe "TTRP-EXP-001"
        }
        "(d) one WARN line per refusal names the run and wiring and carries the cause with bound values as ?" {
            val events = captured { refusal("key" to "id", "value" to "cast(:code as int)") }
            events.size shouldBe 1
            val line = events.single()
            line.level shouldBe Level.WARN
            val text = line.formattedMessage
            text shouldContain "run=run-s28"
            text shouldContain "wiring=#2:way.hold->release"
            text shouldContain "cause=SQLException: "
            text shouldNotContain secret
            text shouldContain "?"
            // The redaction is by the bound values, not a pattern: the rest of the engine's message survives.
            text shouldContain "INT32"
        }
        "(d) a nested refusal through the wiring values logs once" {
            val ask = legacyPreviewMetadata(BundleFixture.load()).actions.getValue("ask")
            val events =
                captured {
                    shouldThrow<AutomationException> {
                        PreviewWiringValues.project(
                            PreviewWiringValues.prepare(mapOf("value" to "missing_column + 1"), ask, site),
                            input,
                            secrets,
                            roleNames = emptySet(),
                            site = site,
                        )
                    }.let { (it.detail as JsonObject).text("expression") shouldBe "value" }
                }
            events.size shouldBe 1
        }

        "generated projection aliases do not mask source columns or reorder rows" {
            val collision = Table(listOf(ManifestColumn("_au_value_0", "int")), listOf(listOf(8L), listOf(7L)))
            PreviewExpressions.project(mapOf("key" to "_au_value_0 + 1"), collision, emptyMap()).rows shouldBe
                listOf(listOf(9L), listOf(8L))
        }
    })
