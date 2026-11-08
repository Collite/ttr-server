// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.stages

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.ColumnValue
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.Literal
import org.tatrman.plan.v1.NamedExpression
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ProjectNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.RowWrite
import org.tatrman.plan.v1.RowWriteOp
import org.tatrman.plan.v1.RowWrites
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.plan.v1.UnionNode
import org.tatrman.security.v1.ColumnRule
import org.tatrman.validate.v1.ValidationOptions

class RowWritesCompatibilitySpec :
    StringSpec({
        val table =
            QualifiedName
                .newBuilder()
                .setSchemaCode(
                    SchemaCode.DB,
                ).setNamespace("public")
                .setName("orders")
                .build()
        val column = Expression.newBuilder().setColumnRef(ColumnRef.newBuilder().setName("state")).build()
        val masked = Expression.newBuilder().setLiteral(Literal.newBuilder().setStringValue("hidden")).build()
        val writes =
            PlanNode
                .newBuilder()
                .setRowWrites(
                    RowWrites.newBuilder().addRows(
                        RowWrite
                            .newBuilder()
                            .setTarget(table)
                            .setOp(RowWriteOp.UPDATE)
                            .addValues(ColumnValue.newBuilder().setName("state").setValue(masked))
                            .setWhere(column)
                            .addReadBefore("state"),
                    ),
                ).build()

        "row writes are leaves for scan walking, column collection and expression rewriting" {
            PlanWalker.nodes(writes) shouldBe listOf(writes)
            PlanWalker.scannedTables(writes) shouldBe emptySet()
            PlanWalker.writeTargets(writes) shouldBe emptySet()
            (PlanWalker.wrapScans(writes) { error("row writes are not scans") } === writes) shouldBe true
            ColumnUsage.tableQnames(writes) shouldBe emptySet()
            ColumnUsage.columnNames(writes) shouldBe emptySet()
            (ExpressionRewriter.rewriteColumnRefs(writes) { error("row writes are leaves") } === writes) shouldBe true
        }

        "mask bare-column detection treats row writes as a leaf beside a masked read" {
            val read =
                PlanNode
                    .newBuilder()
                    .setProject(
                        ProjectNode
                            .newBuilder()
                            .setInput(PlanNode.newBuilder().setTableScan(TableScanNode.newBuilder().setTable(table)))
                            .addExpressions(NamedExpression.newBuilder().setExpression(column)),
                    ).build()
            val plan = PlanNode.newBuilder().setUnion(UnionNode.newBuilder().addInputs(writes).addInputs(read)).build()
            val rule =
                ColumnRule
                    .newBuilder()
                    .setTable(table)
                    .setColumn("state")
                    .setAction(ColumnRule.Action.MASK)
                    .setMaskExpression(masked)
                    .setRuleId("d1-mask")
                    .build()
            val result =
                RuleEnforcer().enforce(
                    plan,
                    ValidationOptions.newBuilder().setEnforceTopN(false).build(),
                    listOf(rule),
                )

            result.rejected shouldBe false
            result.messages shouldBe emptyList()
            result.plan.union.inputsList[0] shouldBe writes
            result.plan.union.inputsList[1]
                .project.expressionsList
                .single()
                .expression shouldBe masked
        }
    })
