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
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.ScanNode
import org.tatrman.plan.v1.SchemaCode

/**
 * ttr-server#232 — a bare `JOIN` the model could not condition reaches validate's ER pass marked
 * (`JoinNode.condition_required`), and the translator refuses it on the DB re-entry of the plan validate hands back. A
 * pass that rebuilt the join from its parts would drop the mark, and the join would run as a Cartesian product. Both
 * of validate's plan rewrites, a row policy and a mask, keep it.
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

        "a mask keeps the join's condition_required" {
            val masked =
                ExpressionRewriter.rewriteColumnRefs(marked) { ref ->
                    Expression.newBuilder().setLiteral(Literal.newBuilder().setStringValue("***")).build().takeIf {
                        ref.name == "region"
                    }
                }
            masked.join.conditionRequired shouldBe true
        }
    })
