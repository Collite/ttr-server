// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.stages

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FunctionCall
import org.tatrman.plan.v1.JoinNode
import org.tatrman.plan.v1.JoinType
import org.tatrman.plan.v1.Literal
import org.tatrman.plan.v1.NamedExpression
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ProjectNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.ScanNode
import org.tatrman.plan.v1.SchemaCode

/**
 * ttr-server#232 — a bare `JOIN` the model could not condition reaches validate's ER pass marked
 * (`JoinNode.condition_required`), and the translator refuses it on the DB re-entry of the plan validate hands back. A
 * pass that rebuilt the join from its parts would drop the mark, and the join would run as a Cartesian product. Both
 * of validate's plan rewrites, a row policy and a mask, keep it. A mask gives a join with no condition none: an empty
 * one reads as a condition, and the re-entry fails to decode it instead of refusing the join.
 */
class ConditionRequiredSurvivalSpec :
    StringSpec({
        fun qname(name: String): QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.ER)
                .setNamespace("entity")
                .setName(name)
                .build()

        fun scan(name: String): PlanNode =
            PlanNode
                .newBuilder()
                .setScan(
                    ScanNode
                        .newBuilder()
                        .setObject(qname(name))
                        .addOutputColumns(ColumnRef.newBuilder().setName("region")),
                ).build()

        val marked =
            PlanNode
                .newBuilder()
                .setJoin(
                    JoinNode
                        .newBuilder()
                        .setJoinType(JoinType.INNER)
                        .setLeft(scan("date_dim"))
                        .setRight(scan("item"))
                        .setConditionRequired(true),
                ).build()

        "a row policy on one side keeps the join's condition_required" {
            val emea = Expression.newBuilder().setLiteral(Literal.newBuilder().setStringValue("EMEA"))
            val predicate =
                Expression
                    .newBuilder()
                    .setFunction(
                        FunctionCall
                            .newBuilder()
                            .setOperation("eq")
                            .addOperands(Expression.newBuilder().setColumnRef(ColumnRef.newBuilder().setName("region")))
                            .addOperands(emea),
                    ).setResultType("bool")
                    .build()
            val secured = PlanWalker.wrapScans(marked, predicate) { it == qname("item") }
            secured.join.right.hasFilter() shouldBe true
            secured.join.conditionRequired shouldBe true
        }

        val stars = Expression.newBuilder().setLiteral(Literal.newBuilder().setStringValue("***")).build()
        val region = Expression.newBuilder().setColumnRef(ColumnRef.newBuilder().setName("region")).build()

        fun mask(plan: PlanNode): PlanNode =
            ExpressionRewriter.rewriteColumnRefs(plan) { ref -> stars.takeIf { ref.name == "region" } }

        "a mask over a marked join rewrites what reads the column, and the join keeps its mark and no condition" {
            val plan =
                PlanNode
                    .newBuilder()
                    .setProject(
                        ProjectNode
                            .newBuilder()
                            .setInput(marked)
                            .addExpressions(NamedExpression.newBuilder().setExpression(region).setAlias("region")),
                    ).build()
            val masked = mask(plan)
            masked.project.expressionsList
                .single()
                .expression shouldBe stars
            masked.project.input.join.conditionRequired shouldBe true
            masked.project.input.join
                .hasCondition() shouldBe false
        }

        "a mask still rewrites a join's own condition" {
            val equal =
                Expression
                    .newBuilder()
                    .setFunction(
                        FunctionCall
                            .newBuilder()
                            .setOperation("eq")
                            .addOperands(region)
                            .addOperands(region),
                    ).setResultType("bool")
                    .build()
            val conditioned =
                marked
                    .toBuilder()
                    .setJoin(
                        marked.join
                            .toBuilder()
                            .setConditionRequired(false)
                            .setCondition(equal),
                    ).build()
            mask(conditioned)
                .join.condition.function.operandsList shouldBe listOf(stars, stars)
        }
    })
