// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.stages

import com.google.protobuf.Message
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.framework.EntityMapping
import org.tatrman.translator.framework.HartlandJoinModel
import org.tatrman.translator.framework.InMemoryModelHandle
import org.tatrman.translator.framework.ModelAttribute
import org.tatrman.translator.framework.ModelEntity
import org.tatrman.translator.framework.ModelRelation
import org.tatrman.translator.framework.ModelSavedQuery
import org.tatrman.translator.framework.SavedQueryBody
import org.tatrman.translator.framework.SurfaceType
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.orchestrator.UnparseResult
import org.tatrman.validate.client.LocalPolicyClient
import org.tatrman.validate.policy.Policy
import org.tatrman.validate.policy.PolicyEngine
import org.tatrman.validate.policy.PolicyPredicate
import org.tatrman.validate.policy.PolicyRegistry
import org.tatrman.validate.policy.PolicyValue
import org.tatrman.validate.policy.TableMatcher

/**
 * LQ C-2 (project `ttr-translator/logical-queries/design.md §5`), the ttr world: row policies reach the
 * physical scans inside an inlined logical query.
 *
 * The translator (`org.tatrman:ttr-translator`, its `HartlandJoinModel` fixture) parses a logical query over
 * entities with `target = ER`, an entity is stacked on that stored body (`EntityMapping.ToQuery`), and an
 * ordinary query over the stacked entity is translated — the plan Validate receives. The policy engine is the
 * real one (in-process), with table- or entity-keyed policies; the secured plan is unparsed to MSSQL.
 */
class LogicalQuerySecuritySpec :
    StringSpec({
        val storeSales = HartlandJoinModel.db("store_sales")
        val customer = HartlandJoinModel.db("customer")
        val household = HartlandJoinModel.db("household_demographics")

        "C-2a — a policy on a table read only inside the inlined body wraps that scan, exactly once" {
            val applied = Lq.secure(Lq.db(Lq.SHOPPER_REVENUE), Lq.policy("p_sales", storeSales, "store_id"))
            applied.applied.map { it.ruleId } shouldBe listOf("p_sales")
            val filters = Lq.policyFilters(applied.plan, "store_id")
            filters.size shouldBe 1
            filters
                .single()
                .filter.input.tableScan.table shouldBe storeSales
            // inside the body: under its GROUP BY
            Lq.nodes(applied.plan).count { it.hasAggregate() && Lq.policyFilters(it, "store_id").size == 1 } shouldBe 1
            val sql = Lq.mssql(applied.plan)
            sql shouldContain "WHERE [store_id] = 5"
            (sql.indexOf("[store_id] = 5") < sql.indexOf("GROUP BY")) shouldBe true
        }

        "C-2b — a renamed-key table inside the body: Filter on the physical scan under the alias Project" {
            val applied = Lq.secure(Lq.db(Lq.POTENTIAL_REVENUE), Lq.policy("p_hd", household, "hd_demo_sk"))
            val filters = Lq.policyFilters(applied.plan, "hd_demo_sk")
            filters.size shouldBe 1
            filters
                .single()
                .filter.input.tableScan.outputColumnsCount shouldBe 0
            Lq.mssql(applied.plan) shouldContain "[hd_demo_sk] = 5"
        }

        "C-2c — a table read inside the body AND outside it: each scan wrapped once" {
            val applied = Lq.secure(Lq.db(Lq.REVENUE_BY_COUNTRY), Lq.policy("p_cust", customer, "shopper_id"))
            Lq.nodes(applied.plan).count { it.hasTableScan() && it.tableScan.table == customer } shouldBe 2
            val filters = Lq.policyFilters(applied.plan, "shopper_id")
            filters.size shouldBe 2
            filters.none { it.filter.input.hasFilter() } shouldBe true
            applied.applied.map { it.ruleId } shouldBe listOf("p_cust")
        }

        "C-2d — an ENTITY-keyed policy on an entity inside the body: not reachable on either pass today" {
            val sale = HartlandJoinModel.er("sale")
            val policy = Lq.policy("p_sale_entity", sale, "store_id")
            // ER pass (target = ER): the plan scans only the stacked entity — `sale` is inside its unexpanded body.
            val erPlan = Lq.er(Lq.SHOPPER_REVENUE)
            Lq.nodes(erPlan).mapNotNull { if (it.hasScan()) it.scan.getObject().name else null } shouldBe
                listOf("shopper_revenue")
            Lq.secure(erPlan, policy).applied.shouldBeEmpty()
            // DB pass (target = DB): the body is inlined, but as tables — an entity key matches nothing.
            Lq.secure(Lq.db(Lq.SHOPPER_REVENUE), policy).applied.shouldBeEmpty()
            // The same policy on a query that names `sale` directly does fire (control).
            Lq.secure(Lq.er("SELECT sale.revenue FROM sale"), policy).applied.map { it.ruleId } shouldBe
                listOf("p_sale_entity")
        }
    })

private object Lq {
    const val REVENUE_SQL: String =
        "SELECT shopper.shopper_id, shopper.last_name, shopper.current_household, SUM(sale.revenue) AS revenue " +
            "FROM sale JOIN shopper " +
            "GROUP BY shopper.shopper_id, shopper.last_name, shopper.current_household"

    const val POTENTIAL_SQL: String =
        "SELECT household.buy_potential, SUM(sale.revenue) AS revenue " +
            "FROM sale JOIN shopper JOIN household GROUP BY household.buy_potential"

    const val SHOPPER_REVENUE: String =
        "SELECT shopper_revenue.last_name, shopper_revenue.revenue FROM shopper_revenue"

    const val POTENTIAL_REVENUE: String =
        "SELECT potential_revenue.buy_potential, potential_revenue.revenue FROM potential_revenue"

    const val REVENUE_BY_COUNTRY: String =
        "SELECT shopper.birth_country, SUM(shopper_revenue.revenue) AS revenue " +
            "FROM shopper_revenue JOIN shopper GROUP BY shopper.birth_country"

    private fun erQuery(name: String): QualifiedName =
        QualifiedName
            .newBuilder()
            .setSchemaCode(SchemaCode.ER)
            .setNamespace("query")
            .setName(name)
            .build()

    private val shopperRevenue =
        ModelEntity(
            HartlandJoinModel.er("shopper_revenue"),
            listOf(
                ModelAttribute("shopper_id", SurfaceType.INT, nullable = false, isKey = true),
                ModelAttribute("last_name", SurfaceType.TEXT),
                ModelAttribute("current_household", SurfaceType.INT),
                ModelAttribute("revenue", SurfaceType.FLOAT),
            ),
        )

    private val potentialRevenue =
        ModelEntity(
            HartlandJoinModel.er("potential_revenue"),
            listOf(ModelAttribute("buy_potential", SurfaceType.TEXT), ModelAttribute("revenue", SurfaceType.FLOAT)),
        )

    private val revenueShopper =
        ModelRelation(
            fromEntity = shopperRevenue.qname,
            toEntity = HartlandJoinModel.er("shopper"),
            joinPairs =
                listOf(
                    HartlandJoinModel.attr("shopper_revenue", "shopper_id") to
                        HartlandJoinModel.attr("shopper", "shopper_id"),
                ),
        )

    private fun parseEr(sql: String): PlanNode {
        val parsed =
            Translator(HartlandJoinModel.handle())
                .parseToRelNode(sql, Language.SQL, targetSchema = SchemaCode.ER, sourceSchema = SchemaCode.ER)
        check(parsed is ParseResult.Success) { "logical query did not parse: $parsed" }
        return parsed.plan
    }

    val model: InMemoryModelHandle by lazy {
        val revenueQ = erQuery("shopper_revenue")
        val potentialQ = erQuery("potential_revenue")
        InMemoryModelHandle(
            tables = HartlandJoinModel.tables,
            entities = HartlandJoinModel.entities + shopperRevenue + potentialRevenue,
            relations = HartlandJoinModel.relations + revenueShopper,
            entityMappings =
                HartlandJoinModel.entityMappings +
                    (shopperRevenue.qname to EntityMapping.ToQuery(revenueQ)) +
                    (potentialRevenue.qname to EntityMapping.ToQuery(potentialQ)),
            savedQueries = listOf(ModelSavedQuery(revenueQ), ModelSavedQuery(potentialQ)),
            savedQueryBodies =
                mapOf(
                    revenueQ to SavedQueryBody(parseEr(REVENUE_SQL), emptyList(), emptyList()),
                    potentialQ to SavedQueryBody(parseEr(POTENTIAL_SQL), emptyList(), emptyList()),
                ),
            attributeRenames = HartlandJoinModel.renames,
        )
    }

    private fun parse(
        sql: String,
        target: SchemaCode,
    ): PlanNode =
        Translator(model)
            .parseToRelNode(sql, Language.SQL, targetSchema = target, sourceSchema = SchemaCode.ER)
            .shouldBeInstanceOf<ParseResult.Success>()
            .plan

    /** The DB-level plan (pass 2): MAP_TO_PHYSICAL has inlined every stacked body. */
    fun db(sql: String): PlanNode = parse(sql, SchemaCode.DB).also { p -> nodes(p).none { it.hasScan() } shouldBe true }

    /** The ER-level plan (pass 1). */
    fun er(sql: String): PlanNode = parse(sql, SchemaCode.ER)

    fun policy(
        id: String,
        table: QualifiedName,
        column: String,
    ): Policy =
        Policy(
            id = id,
            tableMatch = TableMatcher.Exact(table),
            predicate = PolicyPredicate.Eq(column, PolicyValue.Literal(5, "int")),
        )

    suspend fun secure(
        plan: PlanNode,
        policy: Policy,
    ): SecurityApplier.Applied =
        SecurityApplier(LocalPolicyClient(PolicyEngine(PolicyRegistry(listOf(policy)))))
            .applied(plan, PipelineContext.getDefaultInstance())

    fun mssql(plan: PlanNode): String {
        val out =
            Translator(model)
                .unparseFromRelNode(plan, Language.SQL, SqlDialect.MSSQL)
                .shouldBeInstanceOf<UnparseResult.Success>()
                .output
        System.getenv("LQ_DUMP_DIR")?.takeIf { it.isNotBlank() }?.let { dir ->
            java.io.File(dir).mkdirs()
            java.io.File(dir, "c2-ttr-${out.hashCode().toUInt()}.sql").writeText(out)
        }
        return out
    }

    /** Every PlanNode of [m], by reflection over every message field. */
    fun nodes(m: Message): List<PlanNode> =
        buildList {
            if (m is PlanNode) add(m)
            for ((field, value) in m.allFields) {
                when {
                    field.isRepeated -> (value as List<*>).filterIsInstance<Message>().forEach { addAll(nodes(it)) }
                    value is Message -> addAll(nodes(value))
                }
            }
        }

    /** Filters whose condition names [column] — the policy filters (the plans carry no other filter on it). */
    fun policyFilters(
        plan: PlanNode,
        column: String,
    ): List<PlanNode> = nodes(plan).filter { it.hasFilter() && mentions(it.filter.condition, column) }

    private fun mentions(
        e: Expression,
        column: String,
    ): Boolean =
        when {
            e.hasColumnRef() -> e.columnRef.name == column
            e.hasFunction() -> e.function.operandsList.any { mentions(it, column) }
            e.hasCast() -> mentions(e.cast.value, column)
            else -> false
        }
}
