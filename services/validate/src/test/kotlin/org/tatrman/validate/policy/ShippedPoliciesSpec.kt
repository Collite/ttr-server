// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.policy

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.security.v1.EvaluatePoliciesRequest

/**
 * The policy set validate SHIPS (`policies/policies.conf`, through `application.conf`), evaluated as
 * a deployment without an override runs it. The fixtures in [DefaultPolicies] are ungated on purpose;
 * this spec is the one that would notice the shipped file stop isolating a tenant.
 */
class ShippedPoliciesSpec :
    StringSpec({
        val engine = PolicyEngine(PolicyRegistry(PolicyConfigLoader.load(ConfigFactory.load())))
        val orders =
            PlanNode
                .newBuilder()
                .setTableScan(
                    TableScanNode.newBuilder().setTable(
                        QualifiedName
                            .newBuilder()
                            .setSchemaCode(SchemaCode.DB)
                            .setNamespace("dbo")
                            .setName("orders"),
                    ),
                ).build()

        suspend fun evaluate(
            userId: String,
            vararg roles: String,
        ) = engine.evaluatePolicies(
            EvaluatePoliciesRequest
                .newBuilder()
                .setPlan(orders)
                .setContext(PipelineContext.newBuilder().setUserId(userId).addAllAuthRoles(roles.toList()))
                .build(),
        )

        "a caller with a tenant is narrowed to it, whatever roles it holds" {
            for (roles in listOf(emptyArray(), arrayOf("analyst"), arrayOf("analyst", "reporting"))) {
                val resp = evaluate("acme:alice", *roles)
                resp.messagesList.none { it.severity == Severity.ERROR } shouldBe true
                resp.predicatesList shouldHaveSize 1
                resp.predicatesList
                    .single()
                    .predicate.function.operandsList[1]
                    .literal.stringValue shouldBe "acme"
            }
        }

        "a caller without a tenant is not tenant isolation's subject — neither narrowed nor refused" {
            val resp = evaluate("alice", "analyst")
            resp.messagesList.none { it.severity == Severity.ERROR } shouldBe true
            resp.predicatesList shouldHaveSize 0
        }
    })
