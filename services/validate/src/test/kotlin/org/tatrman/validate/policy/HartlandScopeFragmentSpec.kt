// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.policy

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.security.v1.EvaluatePoliciesRequest
import java.io.File

/**
 * LR C-5·4 — the DC-scope policy set, read from where a pod reads it: the validate ConfigMap as the
 * umbrella chart RENDERS it into `helm/tatrman-server/golden/config-fragment.yaml` (the golden CI
 * keeps current), include line and all. A spec over a hand-copied HOCON string would pass while the
 * shipped example drifted into something validate refuses at boot.
 */
class HartlandScopeFragmentSpec :
    StringSpec({
        val overlay = renderedValidateOverlay()
        val config = ConfigFactory.parseString(overlay).resolve()
        val policies = PolicyConfigLoader.load(config)
        val engine = PolicyEngine(PolicyRegistry(policies))

        val scoped =
            mapOf(
                "inventory" to "inv_warehouse_sk",
                "warehouse" to "w_warehouse_sk",
                "catalog_sales" to "cs_warehouse_sk",
                "web_sales" to "ws_warehouse_sk",
                "channel_sales" to "warehouse_sk",
            )

        suspend fun evaluate(
            table: String,
            vararg roles: String,
        ) = engine.evaluatePolicies(
            EvaluatePoliciesRequest
                .newBuilder()
                .setPlan(scan(table))
                .setContext(PipelineContext.newBuilder().setUserId("petr").addAllAuthRoles(roles.toList()))
                .build(),
        )

        "the rendered file keeps every default: its first statement is the classpath include" {
            overlay.lines().first { it.isNotBlank() && !it.trimStart().startsWith("#") } shouldBe
                """include classpath("application.conf")"""
            config.getString("validate.security-bypass.admin-role") shouldBe "query-platform-admin"
        }

        "it replaces the default policy set with the five dc-scope entries" {
            policies shouldHaveSize 5
            policies.map { it.id }.toSet() shouldBe setOf("dc-scope")
            policies.map { it.roles }.toSet() shouldBe setOf(listOf("kantheon-scope-dc-5"))
        }

        "a DC-5 caller gets `<warehouse column> IN (5)` on each scoped table" {
            for ((table, column) in scoped) {
                val resp = evaluate(table, "analyst", "kantheon-scope-dc-5")
                resp.messagesList shouldHaveSize 0
                val predicate =
                    resp.predicatesList
                        .single()
                        .predicate.function
                predicate.operation shouldBe "in"
                predicate.operandsList[0].columnRef.name shouldBe column
                predicate.operandsList[1].literal.intValue shouldBe 5L
            }
        }

        "a caller without the role is not restricted on any of them" {
            for (table in scoped.keys) {
                val resp = evaluate(table, "analyst")
                resp.predicatesList shouldHaveSize 0
                resp.messagesList shouldHaveSize 0
            }
        }

        "store sales, items and customers stay open to the DC caller" {
            for (table in listOf("store_sales", "store", "item", "customer", "date_dim")) {
                evaluate(table, "kantheon-scope-dc-5").predicatesList shouldHaveSize 0
            }
        }
    })

private fun scan(table: String): PlanNode =
    PlanNode
        .newBuilder()
        .setTableScan(
            TableScanNode.newBuilder().setTable(
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(SchemaCode.DB)
                    .setNamespace("dbo")
                    .setName(table),
            ),
        ).build()

/** `overlay.conf` from the golden's validate ConfigMap, de-indented — the file the pod mounts. */
private fun renderedValidateOverlay(): String {
    val golden =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "helm/tatrman-server/golden/config-fragment.yaml") }
            .first { it.isFile }
    val doc =
        golden
            .readText()
            .split("\n---\n")
            .single { "kind: ConfigMap" in it && "name: validate-config-fragment" in it }
    return doc
        .lines()
        .dropWhile { it.trim() != "overlay.conf: |" }
        .drop(1)
        .joinToString("\n") { it.removePrefix("    ") }
}
