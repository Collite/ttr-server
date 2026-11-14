// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.protocol

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.automation.LedgerTestDb
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.apply.ApplyPlanner
import org.tatrman.automation.apply.DryRunAction
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.identity.TokenKind
import org.tatrman.automation.ledger.Ledger
import org.tatrman.automation.ledger.ProtocolSection
import org.tatrman.automation.ledger.RunOpen
import java.time.Duration
import java.util.UUID

/** S3.1 T6 (D-148): `GET /runs/{id}/digest` over an H-1 morning run's journaled `notify` entries — rendered, never sent. */
class DigestComponentSpec :
    StringSpec({
        LedgerTestDb.ready()
        beforeEach { LedgerTestDb.reset() }
        val ledger = Ledger(Duration.ofMinutes(30))
        val maya = Caller("sub-maya", "maya", setOf("hartland-sales-managers"), TokenKind.PERSON, "x")

        fun notify(
            order: String,
            route: String,
            who: String,
            rows: Int,
        ) = DryRunAction(
            buildJsonObject { put("order_number", order) },
            route,
            "notify",
            ApplyPlanner.ENGINE_SINK,
            buildJsonObject {
                put("who", who)
                put(
                    "detail",
                    buildJsonObject {
                        put(
                            "refs",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("context", "shortfall")
                                        put("ref", "/automation/runs/r/routes/$route/details/shortfall?row=0")
                                        put("rowCount", rows)
                                        put("truncated", false)
                                    },
                                ),
                            ),
                        )
                        put("count", rows)
                        put("truncated", false)
                    },
                )
            },
            emptyList(),
            "would notify $who with $rows rows",
        )

        "the H-1 morning run's notify entries render per who, as text and HTML; other entries are not in it" {
            val run =
                ledger
                    .claimRun(
                        RunOpen("release_order", deployment = "release_morning", callerSubject = maya.sub),
                    ).runId
            listOf(
                notify("4711", "hold_sales", "role.sales_manager", 2),
                notify("4712", "hold_sales", "role.sales_manager", 1),
                notify("4713", "hold_ar", "role.ar_clerk", 3),
            ).forEach { ledger.appendProtocol(run, ProtocolSection.ACTIONS, it.payload()) }
            // A writer action of the same run is not part of the digest.
            ledger.appendProtocol(
                run,
                ProtocolSection.ACTIONS,
                DryRunAction(
                    buildJsonObject {
                        put("order_number", "4711")
                    },
                    "hold_sales",
                    "hold",
                    "transition",
                    buildJsonObject {},
                    listOf(1),
                ).payload(),
            )
            val digests = Digests(ledger) { null }
            val all = digests.digest(run, maya, null, "notify", "en")
            all.groups.map { Triple(it.who, it.entries, it.rows) } shouldBe
                listOf(Triple("role.ar_clerk", 1, 3), Triple("role.sales_manager", 2, 3))
            val sales = all.groups.last()
            sales.text shouldBe
                "notify — role.sales_manager\nRun $run\n" +
                "- order_number 4711 · hold_sales: would notify role.sales_manager with 2 rows (2 rows)\n" +
                "- order_number 4712 · hold_sales: would notify role.sales_manager with 1 rows (1 rows)"
            sales.html shouldContain "<h2>notify — role.sales_manager</h2>"
            sales.html shouldContain
                "<a href=\"/automation/runs/r/routes/hold_sales/details/shortfall?row=0\">detail</a>"
            sales.text shouldNotContain "hold " // the writer action stays out
            val one = digests.digest(run, maya, "role.ar_clerk", "notify", "cs")
            one.groups.single().text shouldContain "Běh $run"
            one.groups.single().text shouldContain "(3 řádků)"
            shouldThrow<AutomationException> { digests.digest(run, maya, "role.nobody", "notify", "en") }.code shouldBe
                ErrorCode.BAD_REQUEST
            // Owner-scoped like every run read.
            shouldThrow<AutomationException> {
                digests.digest(run, maya.copy(sub = "sub-sam"), null, "notify", "en")
            }.code shouldBe ErrorCode.IDENTITY
            shouldThrow<AutomationException> {
                digests.digest(
                    UUID.randomUUID(),
                    maya,
                    null,
                    "notify",
                    "de",
                )
            }.code shouldBe
                ErrorCode.BAD_REQUEST
        }

        "an HTML-shaped who or would text is escaped" {
            val run = ledger.claimRun(RunOpen("release_order", callerSubject = maya.sub)).runId
            ledger.appendProtocol(run, ProtocolSection.ACTIONS, notify("<b>1</b>", "r", "role.x", 0).payload())
            Digests(ledger) { null }
                .digest(run, maya, null, "notify", "en")
                .groups
                .single()
                .html shouldNotContain
                "<b>1</b>"
        }
    })
