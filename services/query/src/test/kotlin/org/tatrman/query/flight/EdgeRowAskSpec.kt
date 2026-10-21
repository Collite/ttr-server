// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.asFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.apache.arrow.memory.RootAllocator
import org.tatrman.plan.v1.LimitOffsetNode
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.query.cache.CompiledPlanCache
import org.tatrman.query.client.DispatcherClient
import org.tatrman.query.client.TranslatorClient
import org.tatrman.query.client.TranslatorDetectClient
import org.tatrman.query.client.TranslatorTranslateClient
import org.tatrman.query.client.ValidatorClient
import org.tatrman.query.flight.FlightTestKit.RunningEdge
import org.tatrman.query.flight.FlightTestKit.readAll
import org.tatrman.query.grpc.QueryServiceImpl
import org.tatrman.query.retry.RetryPolicy
import org.tatrman.query.v1.RunRequest
import org.tatrman.translate.v1.DetectSchemaResponse
import org.tatrman.translate.v1.ParseResponse
import org.tatrman.translate.v1.SchemaDecision
import org.tatrman.translate.v1.TranslateResponse
import org.tatrman.validate.stages.RuleEnforcer
import org.tatrman.validate.v1.ValidateResponse
import java.time.Duration

/**
 * BI (C-10) — the row ask across the seam: the edge's statement, the real query service, and validate's real
 * [RuleEnforcer] behind the validate stub, as hartland configures it (default 200, `kantheon-bi-reader:10000`).
 * The dispatcher stub answers a 6 000-row table, cut by whatever limits the validated plan carries.
 */
class EdgeRowAskSpec :
    StringSpec({
        val allocator = RootAllocator()
        afterSpec { allocator.close() }

        val sourceRows = 6_000
        val enforcer =
            RuleEnforcer(serviceDefault = 200, maxByRole = RuleEnforcer.parseMaxByRole("kantheon-bi-reader:10000"))
        val table =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.DB)
                .setNamespace("dbo")
                .setName("catalog_sales")
                .build()
        val scan = PlanNode.newBuilder().setTableScan(TableScanNode.newBuilder().setTable(table)).build()

        // The translator's plan: the scan, under the statement's own `LIMIT n` when it has one.
        val parse =
            TranslatorClient { req ->
                val limit =
                    Regex("LIMIT (\\d+)")
                        .find(req.source)
                        ?.groupValues
                        ?.get(1)
                        ?.toLong()
                val plan =
                    if (limit == null) {
                        scan
                    } else {
                        PlanNode
                            .newBuilder()
                            .setLimitOffset(LimitOffsetNode.newBuilder().setInput(scan).setLimit(limit))
                            .build()
                    }
                ParseResponse
                    .newBuilder()
                    .setPlan(plan)
                    .setContext(req.context)
                    .build()
            }
        val detect =
            TranslatorDetectClient {
                DetectSchemaResponse
                    .newBuilder()
                    .setDecision(SchemaDecision.CONFIRMED)
                    .setEffectiveSchema(SchemaCode.DB)
                    .build()
            }
        val translate =
            TranslatorTranslateClient { req -> TranslateResponse.newBuilder().setContext(req.context).build() }
        val validate =
            ValidatorClient { req ->
                val out = enforcer.enforce(req.plan, req.options, roles = req.context.authRolesList)
                ValidateResponse
                    .newBuilder()
                    .setPlan(out.plan)
                    .setContext(req.context)
                    .addAllMessages(out.messages)
                    .build()
            }

        // The rows a plan's LIMIT chain lets through, in 1 000-row worker batches, then the empty tail.
        fun limitOf(plan: PlanNode): Long =
            if (plan.hasLimitOffset()) {
                minOf(
                    if (plan.limitOffset.hasLimit()) plan.limitOffset.limit else Long.MAX_VALUE,
                    limitOf(plan.limitOffset.input),
                )
            } else {
                Long.MAX_VALUE
            }
        val dispatch =
            DispatcherClient { req ->
                val rows = minOf(sourceRows.toLong(), limitOf(req.plan)).toInt()
                val batches =
                    (0 until rows).chunked(1_000).mapIndexed { i, ids ->
                        FlightTestKit.dataBatch(
                            i,
                            FlightTestKit.ipc(allocator, ids.map { it to "row $it" }),
                            ids.size,
                        )
                    }
                (batches + FlightTestKit.tail(batches.size, rowsTotal = rows.toLong())).asFlow()
            }
        val door =
            QueryServiceImpl(
                parse,
                detect,
                translate,
                validate,
                dispatch,
                CompiledPlanCache(100, Duration.ofMinutes(60)),
                RetryPolicy(maxAttempts = 1, initialBackoffMillis = 1, multiplier = 1.0, jitterPercent = 0),
            )
        val runner = StatementRunner { request: RunRequest -> door.run(request) }

        val reader = EdgeSession(userId = "marketa", roles = listOf("kantheon-area-hartland", "kantheon-bi-reader"))
        val viewer = EdgeSession(userId = "petr", roles = listOf("kantheon-area-hartland", "kantheon-scope-dc-5"))

        fun read(
            session: EdgeSession,
            sql: String,
        ): Pair<Int, JsonObject> =
            RunningEdge(runner, session = session).use { edge ->
                val info = edge.sql.execute(sql)
                val read = edge.sql.getStream(info.endpoints[0].ticket).use { it.readAll() }
                read.rows.size to Json.parseToJsonElement(read.closing!!).jsonObject
            }

        "a BI reader's LIMIT 5000 keeps its 5 000 rows, with no notice" {
            val (rows, closing) = read(reader, "SELECT * FROM db.dbo.catalog_sales LIMIT 5000")
            rows shouldBe 5_000
            closing["top_n_applied"]!!.jsonPrimitive.boolean shouldBe false
            closing["notices"]!!.jsonArray.shouldBeEmpty()
        }

        "a BI reader's statement without LIMIT gets its ceiling, not the default: the whole 6 000-row table" {
            val (rows, closing) = read(reader, "SELECT * FROM db.dbo.catalog_sales")
            rows shouldBe 6_000
            closing["top_n_applied"]!!.jsonPrimitive.boolean shouldBe false
        }

        "without the role, the same LIMIT 5000 gets exactly today's 200 rows and top_n_applied (requested 0)" {
            val (rows, closing) = read(viewer, "SELECT * FROM db.dbo.catalog_sales LIMIT 5000")
            rows shouldBe 200
            closing["top_n_applied"]!!.jsonPrimitive.boolean shouldBe true
            val notice = closing["notices"]!!.jsonArray.single().jsonObject
            notice["code"]!!.jsonPrimitive.content shouldBe "top_n_applied"
            notice["requested"]!!.jsonPrimitive.long shouldBe 0
            notice["applied"]!!.jsonPrimitive.long shouldBe 200
            notice["message"]!!.jsonPrimitive.content shouldBe
                "Answer limited to 200 rows by the row cap; the caller asked for as many rows as its ceiling allows."
        }
    })
