// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.e2e

import com.google.protobuf.kotlin.toByteString
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.NamedExpression
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ProjectNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.ScanNode
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.query.cache.CompiledPlanCache
import org.tatrman.query.client.DispatcherClient
import org.tatrman.query.client.TranslatorClient
import org.tatrman.query.client.TranslatorDetectClient
import org.tatrman.query.client.TranslatorTranslateClient
import org.tatrman.query.client.ValidatorClient
import org.tatrman.query.grpc.QueryServiceImpl
import org.tatrman.query.retry.RetryPolicy
import org.tatrman.query.v1.RunRequest
import org.tatrman.translate.v1.DetectSchemaResponse
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.ParseRequest
import org.tatrman.translate.v1.ParseResponse
import org.tatrman.translate.v1.SchemaDecision
import org.tatrman.validate.v1.ValidateResponse
import java.time.Duration

/**
 * LQ-T2·S2.2 (contract L-8) — the query service's ER half asks the translator for EXPAND_LOGICAL_BODIES, so the plan
 * Validator pass 1 secures is the expanded one (an entity stacked on a logical query → a boundary Project stamped
 * `expanded_from` over the query's body). The DB re-entry does not ask: it closes the boundary on its own.
 */
class LogicalQueryExpansionE2ESpec :
    StringSpec({
        fun qname(
            schema: SchemaCode,
            namespace: String,
            name: String,
        ): QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(schema)
                .setNamespace(namespace)
                .setName(name)
                .build()

        val stacked = qname(SchemaCode.ER, "entity", "shopper_revenue")

        // What the translator returns for the ER half with the option on: the boundary over the body's entity scan.
        val expandedEr =
            PlanNode
                .newBuilder()
                .setProject(
                    ProjectNode
                        .newBuilder()
                        .setExpandedFrom(stacked)
                        .addExpressions(
                            NamedExpression
                                .newBuilder()
                                .setExpression(
                                    Expression.newBuilder().setColumnRef(ColumnRef.newBuilder().setName("revenue")),
                                ).setAlias("total_revenue"),
                        ).setInput(
                            PlanNode.newBuilder().setScan(
                                ScanNode
                                    .newBuilder()
                                    .setObject(qname(SchemaCode.ER, "entity", "sale"))
                                    .addOutputColumns(ColumnRef.newBuilder().setName("revenue")),
                            ),
                        ),
                ).build()
        val physical =
            PlanNode
                .newBuilder()
                .setTableScan(
                    TableScanNode
                        .newBuilder()
                        .setTable(qname(SchemaCode.DB, "dbo", "store_sales"))
                        .addOutputColumns(ColumnRef.newBuilder().setName("ss_net_paid")),
                ).build()

        val dispatcher =
            DispatcherClient { _ ->
                flowOf(
                    org.tatrman.worker.v1.ResultBatch
                        .newBuilder()
                        .setIsFirst(true)
                        .setIsLast(true)
                        .setArrowIpc(ByteArray(0).toByteString())
                        .build(),
                )
            }
        val detect =
            TranslatorDetectClient {
                DetectSchemaResponse
                    .newBuilder()
                    .setDecision(SchemaDecision.CONFIRMED)
                    .setEffectiveSchema(SchemaCode.ER)
                    .build()
            }

        class Harness(
            detected: SchemaCode = SchemaCode.ER,
            erPlan: PlanNode = expandedEr,
        ) {
            val parses = mutableListOf<ParseRequest>()
            val validated = mutableListOf<PlanNode>()
            val service =
                QueryServiceImpl(
                    TranslatorClient { req ->
                        synchronized(parses) { parses += req }
                        ParseResponse
                            .newBuilder()
                            .setPlan(if (req.targetSchema == SchemaCode.ER) erPlan else physical)
                            .setContext(req.context)
                            .build()
                    },
                    if (detected == SchemaCode.ER) {
                        detect
                    } else {
                        TranslatorDetectClient {
                            DetectSchemaResponse
                                .newBuilder()
                                .setDecision(SchemaDecision.CONFIRMED)
                                .setEffectiveSchema(detected)
                                .build()
                        }
                    },
                    TranslatorTranslateClient { throw IllegalStateException("translate not used") },
                    ValidatorClient { req ->
                        synchronized(validated) { validated += req.plan }
                        ValidateResponse
                            .newBuilder()
                            .setPlan(req.plan)
                            .setContext(req.context)
                            .build()
                    },
                    dispatcher,
                    CompiledPlanCache(100, Duration.ofMinutes(60)),
                    RetryPolicy(maxAttempts = 1, initialBackoffMillis = 1, multiplier = 1.0, jitterPercent = 0),
                )
        }

        val request =
            RunRequest
                .newBuilder()
                .setSource("SELECT shopper_revenue.last_name, shopper_revenue.total_revenue FROM shopper_revenue")
                .setSourceLanguage(Language.SQL)
                .setContext(PipelineContext.newBuilder().setUserId("u1").setModelVersion("v1"))
                .build()

        "Run — the ER-half parse asks for the expansion; the REL_NODE re-entry to DB does not" {
            val h = Harness()
            h.service.run(request).toList()
            h.parses.map { it.targetSchema to it.expandLogicalBodies } shouldBe
                listOf(SchemaCode.ER to true, SchemaCode.DB to false)
            h.parses[1].sourceLanguage shouldBe Language.REL_NODE
            // pass 1 secured the expanded plan
            h.validated.first() shouldBe expandedEr
        }

        "Run — an expanded ER plan is not cached: its body would freeze in it; each run recompiles" {
            val h = Harness()
            h.service.run(request).toList()
            h.service.run(request).toList()
            h.parses.count { it.targetSchema == SchemaCode.ER } shouldBe 2
            h.validated.filter { it.hasProject() && it.project.hasExpandedFrom() }.size shouldBe 2
        }

        "Run — a plan with no stacked entity is cached as before" {
            val plain = expandedEr.project.input
            val h = Harness(erPlan = plain)
            h.service.run(request).toList()
            h.service.run(request).toList()
            h.parses.count { it.targetSchema == SchemaCode.ER } shouldBe 1
        }

        "the DB-source path (pass 2 only) is unchanged: one DB parse, no expansion" {
            val h = Harness(detected = SchemaCode.DB)
            h.service.run(request.toBuilder().setSource("SELECT ss_net_paid FROM store_sales").build()).toList()
            h.parses.map { it.targetSchema to it.expandLogicalBodies } shouldBe listOf(SchemaCode.DB to false)
        }

        // LQ (L-28): translate inlines a saved query a text reads by name, so a cached plan would freeze its body.
        "Run — a text that reads a saved query is not cached, ER or DB path; an ordinary one still is" {
            val plain = expandedEr.project.input
            val er = Harness(erPlan = plain)
            val reader = request.toBuilder().setSource("SELECT r.revenue FROM obj.query.shopper_revenue r").build()
            er.service.run(reader).toList()
            er.service.run(reader).toList()
            er.parses.count { it.targetSchema == SchemaCode.ER } shouldBe 2

            val db = Harness(detected = SchemaCode.DB)
            val dbReader = request.toBuilder().setSource("SELECT r.revenue FROM obj.dbo.store_revenue r").build()
            db.service.run(dbReader).toList()
            db.service.run(dbReader).toList()
            db.parses.count { it.targetSchema == SchemaCode.DB } shouldBe 2

            val control = Harness(detected = SchemaCode.DB)
            val ordinary = request.toBuilder().setSource("SELECT ss_net_paid FROM store_sales").build()
            control.service.run(ordinary).toList()
            control.service.run(ordinary).toList()
            control.parses.count { it.targetSchema == SchemaCode.DB } shouldBe 1
        }

        "readsSavedQuery — every spelling of an obj reference, and nothing else" {
            fun reads(sql: String) = QueryServiceImpl.readsSavedQuery(request.toBuilder().setSource(sql).build())
            for (sql in listOf(
                "SELECT * FROM obj.query.x",
                "SELECT * FROM OBJ.query.x",
                "SELECT * FROM \"obj\".\"query\".\"x\"",
                "SELECT * FROM [obj].[query].[x]",
                "SELECT * FROM obj . dbo . x",
            )) {
                reads(sql) shouldBe true
            }
            for (sql in listOf("SELECT objective FROM t", "SELECT x FROM sobj.y", "SELECT t.object_id FROM t")) {
                reads(sql) shouldBe false
            }
            QueryServiceImpl.readsSavedQuery(
                request
                    .toBuilder()
                    .setSource("x")
                    .setSourceLanguage(Language.REL_NODE)
                    .build(),
            ) shouldBe false
        }

        "Compile — the ER-half parse asks for the expansion too" {
            val h = Harness()
            h.service.compile(request)
            h.parses.first { it.targetSchema == SchemaCode.ER }.expandLogicalBodies shouldBe true
            h.parses.filter { it.targetSchema == SchemaCode.DB }.all { !it.expandLogicalBodies } shouldBe true
        }
    })
