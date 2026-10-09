// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.stages

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.NamedExpression
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ProjectNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.framework.ModelColumn
import org.tatrman.translator.framework.ModelEntity
import org.tatrman.translator.framework.ModelForeignKey
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.translator.framework.ModelRelation
import org.tatrman.translator.framework.ModelSavedQuery
import org.tatrman.translator.framework.ModelTable
import org.tatrman.translator.framework.SavedQueryBody
import org.tatrman.translator.framework.SurfaceType
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.orchestrator.UnparseResult
import org.tatrman.validate.client.LocalPolicyClient
import org.tatrman.validate.policy.Policy
import org.tatrman.validate.policy.PolicyEngine
import org.tatrman.validate.policy.PolicyPredicate
import org.tatrman.validate.policy.PolicyRegistry
import org.tatrman.validate.policy.PolicyValue
import org.tatrman.validate.policy.TableMatcher

private val catalogSales: QualifiedName =
    QualifiedName
        .newBuilder()
        .setSchemaCode(SchemaCode.DB)
        .setNamespace("dbo")
        .setName("catalog_sales")
        .build()

/** The physical table as the model declares it — what the translator resolves a scan against. */
private class CatalogSalesModel : ModelHandle {
    private val table =
        ModelTable(
            catalogSales,
            listOf(
                ModelColumn("cs_item_sk", SurfaceType.INT),
                ModelColumn("cs_warehouse_sk", SurfaceType.INT),
                ModelColumn("cs_quantity", SurfaceType.INT),
                ModelColumn("cs_ext_sales_price", SurfaceType.FLOAT),
            ),
        )

    override fun tables(
        schemaCode: SchemaCode,
        namespace: String,
    ): Map<QualifiedName, ModelTable> =
        if (schemaCode == SchemaCode.DB &&
            namespace == "dbo"
        ) {
            mapOf(catalogSales to table)
        } else {
            emptyMap()
        }

    override fun columns(tableQname: QualifiedName): List<ModelColumn> =
        if (tableQname ==
            catalogSales
        ) {
            table.columns
        } else {
            emptyList()
        }

    override fun foreignKeys(): List<ModelForeignKey> = emptyList()

    override fun entities(
        schemaCode: SchemaCode,
        namespace: String,
    ): Map<QualifiedName, ModelEntity> = emptyMap()

    override fun attributes(entityQname: QualifiedName) = emptyList<Nothing>()

    override fun relations(): List<ModelRelation> = emptyList()

    override fun entityMapping(entityQname: QualifiedName) = null

    override fun savedQueries(
        schemaCode: SchemaCode,
        namespace: String,
    ): Map<QualifiedName, ModelSavedQuery> = emptyMap()

    override fun savedQueryBody(queryQname: QualifiedName): SavedQueryBody = error("none")

    override fun currentVersion(): String = "aliased-scan-v0"

    override fun namespaces(schemaCode: SchemaCode): Set<String> =
        if (schemaCode ==
            SchemaCode.DB
        ) {
            setOf("dbo")
        } else {
            emptySet()
        }
}

private fun col(
    name: String,
    alias: String = "",
    type: String = "int",
): ColumnRef =
    ColumnRef
        .newBuilder()
        .setName(name)
        .setAlias(alias)
        .setType(type)
        .build()

/**
 * The DB plan an ENTITY query becomes: MAP_TO_PHYSICAL puts the ER attribute names on the scan as
 * output-column aliases (`cs_warehouse_sk AS warehouse`), and everything above the scan reads the
 * aliases. This is the live shape of „marketplace revenue by distribution centre“ on hartland.
 */
private fun entityQueryPlan(): PlanNode {
    val scan =
        PlanNode
            .newBuilder()
            .setTableScan(
                TableScanNode
                    .newBuilder()
                    .setTable(catalogSales)
                    .addOutputColumns(col("cs_item_sk", "item"))
                    .addOutputColumns(col("cs_warehouse_sk", "warehouse"))
                    .addOutputColumns(col("cs_ext_sales_price", "ext_sales_price", "float")),
            ).build()

    fun ref(name: String) = Expression.newBuilder().setColumnRef(ColumnRef.newBuilder().setName(name)).build()
    return PlanNode
        .newBuilder()
        .setProject(
            ProjectNode
                .newBuilder()
                .setInput(scan)
                .addExpressions(NamedExpression.newBuilder().setExpression(ref("warehouse")).setAlias("warehouse"))
                .addExpressions(
                    NamedExpression.newBuilder().setExpression(ref("ext_sales_price")).setAlias("ext_sales_price"),
                ),
        ).build()
}

/**
 * LR P3b S5, found live on hartland 2026-10-09 — a row policy on an ALIASED scan.
 *
 * The policy names the physical column (`cs_warehouse_sk IN (5)`), as a policy must: it is written
 * against tables. The wrap placed its filter directly above the scan, where the translator has already
 * renamed every column to its alias, so turning the plan into SQL failed —
 * `field [cs_warehouse_sk] not found; input fields are: [item, warehouse, …]` — and every query a
 * DC-scoped caller asked through the entity layer was `translator_failed`. The receipt-less refusal
 * was invisible in the unit specs because they wrap bare scans.
 *
 * The check runs the REAL translator over the wrapped plan: the decode is the one place the column is
 * resolved, and a structural assertion alone would pass a plan the translator cannot read.
 */
class AliasedScanWrapSpec :
    StringSpec({
        val dcScope =
            Policy(
                id = "dc-scope",
                tableMatch = TableMatcher.Exact(catalogSales),
                predicate = PolicyPredicate.In("cs_warehouse_sk", listOf(PolicyValue.Literal(5, "int"))),
                roles = listOf("kantheon-scope-dc-5"),
            )
        val applier = SecurityApplier(LocalPolicyClient(PolicyEngine(PolicyRegistry(listOf(dcScope)))))
        val sam =
            PipelineContext
                .newBuilder()
                .setUserId("sam")
                .addAllAuthRoles(listOf("kantheon-area-hartland", "kantheon-scope-dc-5"))
                .build()
        val translator = Translator(CatalogSalesModel())

        fun sqlOf(plan: PlanNode): String =
            translator
                .unparseFromRelNode(plan, Language.SQL, SqlDialect.POSTGRESQL, optimize = true)
                .shouldBeInstanceOf<UnparseResult.Success>()
                .output

        "the unwrapped entity plan unparses — the fixture is the live shape, not a broken one" {
            sqlOf(entityQueryPlan()) shouldContain "\"cs_warehouse_sk\""
        }

        "a policy on an aliased scan unparses, filtering the PHYSICAL column under the aliases" {
            val wrapped = applier.applied(entityQueryPlan(), sam)
            wrapped.applied.map { it.ruleId } shouldBe listOf("dc-scope")
            val sql = sqlOf(wrapped.plan)
            sql shouldContain Regex("\"cs_warehouse_sk\" (= 5|IN \\(5\\))")
            // Everything above still reads the aliases: the projection keeps its names.
            sql shouldContain "AS \"warehouse\""
        }

        "the row the plan returns is unchanged in shape: same fields, same order" {
            val wrapped = applier.applied(entityQueryPlan(), sam).plan
            val above = wrapped.project.input
            above.hasProject() shouldBe true
            above.project.expressionsList.map { it.alias } shouldBe listOf("item", "warehouse", "ext_sales_price")
            above.project.input.hasFilter() shouldBe true
            above.project.input.filter.input.tableScan.table shouldBe catalogSales
        }

        "a caller the policy does not gate gets the plan untouched" {
            val maya =
                sam
                    .toBuilder()
                    .clearAuthRoles()
                    .addAuthRoles("kantheon-area-hartland")
                    .build()
            applier.applied(entityQueryPlan(), maya).plan shouldBe entityQueryPlan()
        }
    })
