// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.tatrman.plan.v1.PlanNode
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ParseStatus
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.veles.parse.QueryParseState
import org.tatrman.veles.parse.QueryParseWorker

/**
 * LQ-T5 (design §8, contracts L-20…L-26) — the metadata worker parses a saved query that reads another saved query
 * as `obj.<namespace>.<name>` after the query it reads, and stores the reader's plan with that body inlined; it
 * refuses a cycle, an unknown or ambiguous reference, a reference to a failed query and one to a parametrized query.
 * Over [LogicalQueryFixture.refsModel].
 */
class LogicalQueryReferenceSpec :
    StringSpec({
        val model: Model by lazy { LogicalQueryFixture.refsModel() }

        fun query(
            name: String,
            schemaCode: SchemaCode = SchemaCode.ER,
        ): QualifiedName =
            model.queries.keys.single {
                it.name == name &&
                    (
                        if (schemaCode ==
                            SchemaCode.ER
                        ) {
                            it.schemaCode == SchemaCode.ER
                        } else {
                            it.schemaCode != SchemaCode.ER
                        }
                    )
            }

        val parsed: QueryParseState by lazy {
            QueryParseState().also { state ->
                state.reset(model.version.value, model.queries.keys)
                QueryParseWorker().also { runBlocking { it.parseAll(model, state).join() } }.close()
            }
        }

        fun status(qname: QualifiedName): ParseStatus =
            checkNotNull(parsed.get(model.version.value, qname)) { "no status for $qname" }

        fun failure(qname: QualifiedName): String = status(qname).shouldBeInstanceOf<ParseStatus.ParseFailure>().message

        fun scanNames(plan: PlanNode): List<String> =
            buildList {
                fun walk(n: PlanNode) {
                    if (n.hasScan()) add(n.scan.getObject().name)
                    if (n.hasTableScan()) add(n.tableScan.table.name)
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

        fun storedScans(qname: QualifiedName): List<String> =
            scanNames(
                PlanNode.parseFrom(
                    status(qname).shouldBeInstanceOf<ParseStatus.ParseSuccess>().canonicalFormProtoBytes,
                ),
            )

        "L-21/L-22 — a logical query reading a logical query parses after it, the body inlined (its entity scans)" {
            storedScans(query("big_shoppers")) shouldContainExactlyInAnyOrder listOf("sale", "shopper")
        }

        "two levels deep: each parses after the query it reads" {
            storedScans(query("biggest_shoppers")) shouldContainExactlyInAnyOrder listOf("sale", "shopper")
        }

        "a db query reading a db query parses over the tables its body reads" {
            storedScans(query("top_tickets", SchemaCode.DB)) shouldContainExactlyInAnyOrder listOf("store_sales")
        }

        "L-4 — an entity stacked on a query that reads another query is checked like any (no mismatch)" {
            (parsed.mappingMismatches(model.version.value) ?: emptyMap()) shouldNotContainKey
                QualifiedName(schemaCode = SchemaCode.ER, namespace = "entity", name = "big_shopper")
        }

        "L-21 — a reference cycle fails each query on it, naming the path" {
            for (name in listOf("loop_a", "loop_b")) {
                val message = failure(query(name))
                message shouldStartWith "query_reference_cycle: "
                message shouldContain "loop_a"
                message shouldContain "loop_b"
            }
        }

        "L-21 — a reference to no saved query: saved_query_unknown" {
            val message = failure(query("reads_unknown"))
            message shouldStartWith "saved_query_unknown: "
            message shouldContain "obj.query.nothing"
        }

        "L-21 — a reference to a query that failed: saved_query_failed, carrying its failure" {
            failure(query("broken_mixed")) shouldStartWith "mixed_lanes: "
            val message = failure(query("reads_broken"))
            message shouldStartWith "saved_query_failed: "
            message shouldContain "broken_mixed"
            message shouldContain "mixed_lanes"
        }

        "L-20 — two saved queries sharing one obj name: a reference is saved_query_ambiguous, naming both" {
            val message = failure(query("reads_dup"))
            message shouldStartWith "saved_query_ambiguous: "
            message shouldContain "er.query.dup"
            message shouldContain "query.dup"
            // the two `dup`s themselves read nothing and parse
            status(query("dup")).shouldBeInstanceOf<ParseStatus.ParseSuccess>()
            status(query("dup", SchemaCode.DB)).shouldBeInstanceOf<ParseStatus.ParseSuccess>()
        }

        "L-25 — reading a saved query that declares parameters: saved_query_parameters" {
            val message = failure(query("reads_parametrized", SchemaCode.DB))
            message shouldStartWith "saved_query_parameters: "
            message shouldContain "min_revenue"
        }

        "L-26 — a logical query reading a db query reads tables: lane_mismatch, the lane decided after inlining" {
            val message = failure(query("reads_db"))
            message shouldStartWith "lane_mismatch: "
            message shouldContain "store_sales"
        }
    })
