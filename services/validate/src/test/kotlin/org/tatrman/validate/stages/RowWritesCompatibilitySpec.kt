// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.stages

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.plan.v1.*
import org.tatrman.security.v1.ColumnRule
import org.tatrman.security.v1.EvaluatePoliciesResponse
import org.tatrman.validate.client.SecurityClient
import org.tatrman.validate.client.StaticMetadataClient
import org.tatrman.validate.grpc.ValidateServiceImpl
import org.tatrman.validate.policy.*
import org.tatrman.validate.v1.ValidateRequest
import org.tatrman.validate.v1.ValidationOptions

class RowWritesCompatibilitySpec :
    StringSpec({
        val table =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.DB)
                .setNamespace("dbo")
                .setName("orders")
                .build()
        val column = Expression.newBuilder().setColumnRef(ColumnRef.newBuilder().setName("state")).build()
        val literal = Expression.newBuilder().setLiteral(Literal.newBuilder().setStringValue("held")).build()

        fun write(slot: String) =
            PlanNode
                .newBuilder()
                .setRowWrites(
                    RowWrites.newBuilder().addRows(
                        RowWrite.newBuilder().setTarget(table).setOp(RowWriteOp.UPDATE).apply {
                            when (slot) {
                                "values" -> addValues(ColumnValue.newBuilder().setName("state").setValue(literal))
                                "where" ->
                                    setWhere(
                                        Expression.newBuilder().setFunction(
                                            FunctionCall
                                                .newBuilder()
                                                .setOperation("eq")
                                                .addOperands(column)
                                                .addOperands(literal),
                                        ),
                                    )
                                "read_before" -> addReadBefore("state")
                            }
                        },
                    ),
                ).build()

        fun request(
            plan: PlanNode,
            user: String = "writer",
        ) = ValidateRequest
            .newBuilder()
            .setPlan(plan)
            .setContext(PipelineContext.newBuilder().setUserId(user).addAuthRoles("reader"))
            .setOptions(ValidationOptions.newBuilder().setApplySecurity(true).setEnforceTopN(true))
            .build()

        fun service(client: SecurityClient) =
            ValidateServiceImpl(
                SecurityApplier(client),
                RuleEnforcer(),
                LlmGuard(enabled = false),
                StaticMetadataClient(""),
            )
        "row write targets participate in policy checks but are never wrapped as scans" {
            val plan = write("values")
            PlanWalker.writeTargets(plan) shouldBe setOf(table)
            PlanWalker.scannedTables(plan) shouldBe emptySet()
            PlanWalker.wrapScans(plan) { error("write target cannot be filtered") } shouldBe plan
            val policy =
                Policy(
                    "narrow",
                    TableMatcher.Exact(table),
                    PolicyPredicate.Eq("tenant", PolicyValue.Literal("one", "text")),
                    roles = listOf("reader"),
                )
            val engine = PolicyEngine(PolicyRegistry(listOf(policy)))
            val response = service(SecurityClient { engine.evaluatePolicies(it) }).validate(request(plan))
            response.hasPlan() shouldBe false
            response.messagesList.first().code shouldBe "write_under_row_policy"
            val bypass =
                request(plan)
                    .toBuilder()
                    .setContext(request(plan).context.toBuilder().addAuthRoles("query-platform-admin"))
                    .setOptions(ValidationOptions.newBuilder().setApplySecurity(false))
                    .build()
            service(SecurityClient { engine.evaluatePolicies(it) })
                .validate(bypass)
                .messagesList
                .first()
                .code shouldBe "write_under_row_policy"

            val permitted =
                service(SecurityClient { engine.evaluatePolicies(it) }).validate(
                    request(
                        plan,
                    ).toBuilder()
                        .setContext(
                            PipelineContext.newBuilder().setUserId("writer").addAuthRoles("exempt"),
                        ).build(),
                )
            permitted.plan shouldBe plan
        }
        "DENY and MASK on values, where or read_before refuse rather than mask a write" {
            for (slot in listOf("values", "where", "read_before")) {
                for (action in listOf(ColumnRule.Action.DENY, ColumnRule.Action.MASK)) {
                    val rule =
                        ColumnRule
                            .newBuilder()
                            .setTable(
                                table,
                            ).setColumn("state")
                            .setAction(action)
                            .setMaskExpression(literal)
                            .build()
                    val response =
                        service(
                            SecurityClient {
                                EvaluatePoliciesResponse.newBuilder().addColumnRules(rule).build()
                            },
                        ).validate(request(write(slot)))
                    response.hasPlan() shouldBe false
                    response.messagesList.first().code shouldBe "write_under_column_rule"
                }
            }
        }
        "an unrelated column rule leaves a write intact, with no read TopN wrapper" {
            val rule =
                ColumnRule
                    .newBuilder()
                    .setTable(
                        table,
                    ).setColumn("another")
                    .setAction(ColumnRule.Action.DENY)
                    .build()
            val response =
                service(
                    SecurityClient {
                        EvaluatePoliciesResponse.newBuilder().addColumnRules(rule).build()
                    },
                ).validate(request(write("values")))
            response.plan shouldBe write("values")
        }
        "missing identity is refused before metadata or policy work" {
            val response =
                service(
                    SecurityClient {
                        error("anonymous writes cannot reach policies")
                    },
                ).validate(request(write("values"), ""))
            response.hasPlan() shouldBe false
            response.messagesList.single().code shouldBe "identity"
        }
    })
