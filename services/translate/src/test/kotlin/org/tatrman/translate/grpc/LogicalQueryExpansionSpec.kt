// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translate.grpc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.model.StaticModelHandleProvider
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.ParseRequest
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translate.v1.TranslateRequest
import org.tatrman.translator.framework.HartlandStackModel

/**
 * LQ-T2·S2.2 (contract L-8) — `expand_logical_bodies` on the wire reaches the translator: with it, the ER half
 * expands an entity stacked on a logical query under a stamped boundary; without it, the plan is today's.
 */
class LogicalQueryExpansionSpec :
    StringSpec({
        val service = TranslatorServiceImpl(StaticModelHandleProvider(HartlandStackModel.stacked()))
        val sql = "SELECT shopper_revenue.last_name, shopper_revenue.total_revenue FROM shopper_revenue"

        fun parse(expand: Boolean): PlanNode {
            val response =
                kotlinx.coroutines.runBlocking {
                    service.parseToRelNode(
                        ParseRequest
                            .newBuilder()
                            .setSource(sql)
                            .setSourceLanguage(Language.SQL)
                            .setSourceSchema(SchemaCode.ER)
                            .setTargetSchema(SchemaCode.ER)
                            .setExpandLogicalBodies(expand)
                            .build(),
                    )
                }
            response.messagesList shouldBe emptyList()
            return response.plan
        }

        fun stamps(plan: PlanNode): List<String> =
            HartlandStackModel
                .nodes(plan)
                .filter {
                    it.hasProject() &&
                        it.project.hasExpandedFrom()
                }.map { it.project.expandedFrom.name }

        "ParseToRelNode — expand_logical_bodies = true: the stacked entity is a stamped boundary over its body" {
            val plan = parse(expand = true)
            stamps(plan) shouldBe listOf("shopper_revenue")
            HartlandStackModel.erScans(plan).map { it.name }.toSet() shouldBe setOf("sale", "shopper")
        }

        "ParseToRelNode — unset: today's plan, the stacked entity scanned" {
            val plan = parse(expand = false)
            stamps(plan) shouldBe emptyList()
            HartlandStackModel.erScans(plan).map { it.name } shouldBe listOf("shopper_revenue")
        }

        "Translate — the option is a no-op for a DB target: same SQL either way" {
            fun translate(expand: Boolean): String =
                kotlinx.coroutines.runBlocking {
                    service
                        .translate(
                            TranslateRequest
                                .newBuilder()
                                .setSource(sql)
                                .setSourceLanguage(Language.SQL)
                                .setTargetLanguage(Language.SQL)
                                .setSourceSchema(SchemaCode.ER)
                                .setTargetSchema(SchemaCode.DB)
                                .setTargetDialect(SqlDialect.MSSQL)
                                .setExpandLogicalBodies(expand)
                                .build(),
                        ).also { it.messagesList shouldBe emptyList() }
                        .output
                }
            translate(expand = true) shouldBe translate(expand = false)
        }
    })
