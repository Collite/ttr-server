// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.meta.v1.GetStatusRequest
import org.tatrman.plan.v1.PlanNode
import org.tatrman.translate.v1.Language
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ParseStatus
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.veles.grpc.MetadataServiceImpl
import org.tatrman.veles.grpc.PackageIndex
import org.tatrman.veles.parse.QueryParseState
import org.tatrman.veles.parse.QueryParseWorker
import org.tatrman.veles.parse.queryPathHandle
import kotlinx.coroutines.runBlocking

/**
 * LQ-T2·S1 (contracts L-2…L-4) — the metadata worker parses each saved query in the lane it is declared in, over
 * [LogicalQueryFixture].
 */
class LogicalQueryParseWorkerSpec :
    StringSpec({
        fun er(
            namespace: String,
            name: String,
        ) = QualifiedName(schemaCode = SchemaCode.ER, namespace = namespace, name = name)

        val shopperRevenue = er("query", "shopper_revenue")
        val mixed = er("query", "sale_paid_mixed")
        val withParameter = er("query", "shopper_revenue_over")
        val stackedEntity = er("entity", "shopper_revenue")
        val mismatchedEntity = er("entity", "shopper_spend")

        val model: Model by lazy { LogicalQueryFixture.model() }

        fun dbQuery(name: String): QualifiedName =
            model.queries.keys.single {
                it.name == name &&
                    it.schemaCode != SchemaCode.ER
            }

        val parsed: QueryParseState by lazy {
            QueryParseState().also { state ->
                state.reset(model.version.value, model.queries.keys)
                QueryParseWorker().also { runBlocking { it.parseAll(model, state).join() } }.close()
            }
        }

        fun status(qname: QualifiedName): ParseStatus =
            checkNotNull(parsed.get(model.version.value, qname)) {
                "no status for $qname"
            }

        fun scans(plan: PlanNode): List<PlanNode> =
            buildList {
                fun walk(n: PlanNode) {
                    if (n.hasScan() || n.hasTableScan()) add(n)
                    if (n.hasProject()) walk(n.project.input)
                    if (n.hasFilter()) walk(n.filter.input)
                    if (n.hasAggregate()) walk(n.aggregate.input)
                    if (n.hasSort()) walk(n.sort.input)
                    if (n.hasJoin()) {
                        walk(n.join.left)
                        walk(n.join.right)
                    }
                }
                walk(plan)
            }

        "the fixture declares a logical query in the er lane and two queries in the db lane" {
            model.queries.keys.filter { it.schemaCode == SchemaCode.ER } shouldContainExactlyInAnyOrder
                listOf(shopperRevenue, mixed, withParameter)
            dbQuery("store_revenue")
            dbQuery("shopper_revenue_db")
        }

        "L-3 — a logical query parses PARSED and is stored at the ER level: entity scans, the bare JOIN conditioned" {
            val ok = status(shopperRevenue).shouldBeInstanceOf<ParseStatus.ParseSuccess>()
            val plan = PlanNode.parseFrom(ok.canonicalFormProtoBytes)
            val read = scans(plan)
            read.none { it.hasTableScan() } shouldBe true
            read.map { it.scan.getObject().name } shouldContainExactlyInAnyOrder listOf("sale", "shopper")

            fun firstJoin(n: PlanNode): PlanNode? =
                when {
                    n.hasJoin() -> n
                    n.hasProject() -> firstJoin(n.project.input)
                    n.hasAggregate() -> firstJoin(n.aggregate.input)
                    n.hasFilter() -> firstJoin(n.filter.input)
                    else -> null
                }
            checkNotNull(firstJoin(plan)).join.hasCondition() shouldBe true
        }

        "a db-lane query stores exactly the plan the lenient parse stores (the worker before LQ)" {
            val q = dbQuery("store_revenue")
            val ok = status(q).shouldBeInstanceOf<ParseStatus.ParseSuccess>()
            val today =
                Translator(queryPathHandle(model))
                    .parseToRelNode(model.queries.getValue(q).sourceText, Language.SQL)
                    .shouldBeInstanceOf<ParseResult.Success>()
            ok.canonicalFormProtoBytes.contentEquals(today.plan.toByteArray()) shouldBe true
        }

        "L-2 — a body over entities declared in the db lane is lane_mismatch, naming the entities" {
            val failed = status(dbQuery("shopper_revenue_db")).shouldBeInstanceOf<ParseStatus.ParseFailure>()
            failed.message shouldStartWith "lane_mismatch: "
            failed.message shouldContain "sale"
            failed.message shouldContain "shopper"
        }

        "L-2 — an er-lane body reading a table is mixed_lanes, naming both sets" {
            val failed = status(mixed).shouldBeInstanceOf<ParseStatus.ParseFailure>()
            failed.message shouldStartWith "mixed_lanes: "
            failed.message shouldContain "sale"
            failed.message shouldContain "store_sales"
        }

        "L-2 / LQ-5 — a logical query with a parameter is logical_query_parameters" {
            val failed = status(withParameter).shouldBeInstanceOf<ParseStatus.ParseFailure>()
            failed.message shouldStartWith "logical_query_parameters: "
            failed.message shouldContain "threshold"
        }

        "L-4 — an entity binding a column its query lacks is entity_query_output_mismatch; the query stays PARSED" {
            status(shopperRevenue).shouldBeInstanceOf<ParseStatus.ParseSuccess>()
            val mismatches = checkNotNull(parsed.mappingMismatches(model.version.value))
            mismatches shouldContainKey mismatchedEntity
            mismatches shouldNotContainKey stackedEntity
            val message = mismatches.getValue(mismatchedEntity)
            message shouldStartWith "entity_query_output_mismatch: "
            message shouldContain "er.entity.shopper_spend"
            message shouldContain "spend"
            message shouldContain "spend_total"
            message shouldContain "er.query.shopper_revenue"
            // the body's outputs, so the author sees what there is to bind
            message shouldContain "shopper_id, last_name, current_household, revenue"
        }

        "L-4 — the stacked entity whose bindings all resolve is not reported (its renamed attribute included)" {
            parsed
                .mappingMismatches(model.version.value)!!
                .keys
                .filter { it.name == "shopper_revenue" }
                .shouldBeEmpty()
        }

        "GetStatus reports each mismatched mapping as a warning" {
            val registry = MetadataRegistry().also { it.swap(model, ModelGraph.build(model)) }
            val response =
                MetadataServiceImpl(registry = registry, parseState = parsed, packageIndex = PackageIndex())
                    .getStatus(GetStatusRequest.getDefaultInstance())
            val warning = response.messagesList.single { it.code == "entity_query_output_mismatch" }
            warning.humanMessage shouldContain "er.entity.shopper_spend"
        }
    })
