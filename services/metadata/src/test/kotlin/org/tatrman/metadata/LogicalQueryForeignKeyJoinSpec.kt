// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.ParseStatus
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.metadata.parse.QueryParseState
import org.tatrman.metadata.parse.QueryParseWorker
import java.nio.file.Files

/**
 * LQ (contract L-3, review of the legacy L1) — a logical query whose bare JOIN rides an FK-only relation (no `join:`
 * pairs) is conditioned from the foreign key only at MAP_TO_PHYSICAL: at the ER level the joiner reports INFO
 * `JOIN_RELATION_WITHOUT_PAIRS`. With one foreign key between the tables that is a real ON; with two, the physical
 * joiner cannot choose (`JOIN_AMBIGUOUS_RELATIONS`) and every read of the stacked entity would be a Cartesian product.
 * The worker takes the body to the physical level once, and refuses the second. A db query's joins are its author's
 * SQL (model joins do not apply in the db catalog): a comma join or a CROSS JOIN between tables no foreign key connects
 * draws the physical joiner's `JOIN_NO_RELATION` ("Cartesian product preserved") and changes nothing — not refused.
 */
class LogicalQueryForeignKeyJoinSpec :
    StringSpec({
        fun db(keys: Int) =
            """
            model db schema dbo

            def table store_sales {
                primaryKey: [ss_ticket_number],
                columns: [
                    def column ss_ticket_number { type: int, isKey: true },
                    def column ss_customer_sk { type: int, optional: true },
                    def column ss_billing_customer_sk { type: int, optional: true },
                    def column ss_net_paid { type: decimal, optional: true }
                ]
            }

            def table customer {
                primaryKey: [c_customer_sk],
                columns: [ def column c_customer_sk { type: int, isKey: true } ]
            }

            """.trimIndent() +
                (if (keys >= 1) FK_CUSTOMER else "") +
                (if (keys >= 2) FK_BILLING else "") +
                "\n"

        val er =
            """
            model er

            def entity sale {
                binding: { target: { table: db.dbo.store_sales } },
                attributes: [
                    def attribute ticket_number { type: int, isKey: true, binding: ss_ticket_number },
                    def attribute shopper_id { type: int, optional: true, binding: ss_customer_sk },
                    def attribute revenue { type: decimal, optional: true, binding: ss_net_paid }
                ]
            }

            def entity shopper {
                binding: { target: { table: db.dbo.customer } },
                attributes: [ def attribute shopper_id { type: int, isKey: true, binding: c_customer_sk } ]
            }

            def relation sale_shopper {
                from: er.entity.sale, to: er.entity.shopper,
                cardinality: { from: "0..*", to: "1" },
                binding: { fk: db.dbo.fk_ss_customer }
            }

            def query shopper_revenue {
                sourceText: ""${'"'}sql
            SELECT shopper.shopper_id, SUM(sale.revenue) AS revenue FROM sale JOIN shopper GROUP BY shopper.shopper_id
            ""${'"'}
            }
            """.trimIndent()

        fun model(
            keys: Int,
            moreEr: String = "",
        ): Model {
            val root = Files.createTempDirectory("lq-fk")
            Files.writeString(root.resolve("db.ttrm"), db(keys))
            Files.writeString(root.resolve("er.ttrm"), er + moreEr)
            val source = FileBasedSource("lq-fk", 100, LocalFsStorage("lq-fk", root))
            return ModelReconciler(ModelDescriptor(id = "lq-fk", name = "lq-fk")).reconcile(listOf(source.load())).model
        }

        /** Each logical query's status, by name. */
        fun statuses(model: Model): Map<String, ParseStatus> {
            val state = QueryParseState().also { it.reset(model.version.value, model.queries.keys) }
            QueryParseWorker().also { runBlocking { it.parseAll(model, state).join() } }.close()
            return model.queries.keys
                .filter { it.schemaCode == SchemaCode.ER }
                .associate { it.name to checkNotNull(state.get(model.version.value, it)) }
        }

        fun parsed(model: Model): ParseStatus = statuses(model).getValue("shopper_revenue")

        "db queries joining with a comma + WHERE or a CROSS JOIN, no foreign key between the tables: PARSED" {
            for (keys in listOf(0, 1)) {
                val root = Files.createTempDirectory("lq-comma")
                Files.writeString(root.resolve("db.ttrm"), db(keys))
                Files.writeString(root.resolve("queries.ttrm"), COMMA_AND_CROSS)
                val source = FileBasedSource("lq-comma", 100, LocalFsStorage("lq-comma", root))
                val model =
                    ModelReconciler(
                        ModelDescriptor(id = "c", name = "c"),
                    ).reconcile(listOf(source.load())).model
                val state = QueryParseState().also { it.reset(model.version.value, model.queries.keys) }
                QueryParseWorker().also { runBlocking { it.parseAll(model, state).join() } }.close()
                for (query in model.queries.keys) {
                    checkNotNull(state.get(model.version.value, query)).shouldBeInstanceOf<ParseStatus.ParseSuccess>()
                }
            }
        }

        "a logical query over an entity on a comma-join db query: PARSED (that join is the base query's)" {
            val root = Files.createTempDirectory("lq-comma-stacked")
            Files.writeString(root.resolve("db.ttrm"), db(keys = 0))
            Files.writeString(root.resolve("queries.ttrm"), COMMA_AND_CROSS)
            Files.writeString(
                root.resolve("er.ttrm"),
                "model er\n\ndef entity billed { binding: { target: { query: comma_join } }, attributes: [\n" +
                    "    def attribute ss_ticket_number { type: int, isKey: true },\n" +
                    "    def attribute c_customer_sk { type: int, optional: true } ] }\n\n" +
                    "def query billed_customers {\n    sourceText: \"\"\"sql\n" +
                    "SELECT billed.c_customer_sk FROM billed\n\"\"\"\n}\n",
            )
            val source = FileBasedSource("lq-cs", 100, LocalFsStorage("lq-cs", root))
            val model = ModelReconciler(ModelDescriptor(id = "cs", name = "cs")).reconcile(listOf(source.load())).model
            val state = QueryParseState().also { it.reset(model.version.value, model.queries.keys) }
            QueryParseWorker().also { runBlocking { it.parseAll(model, state).join() } }.close()
            for (query in model.queries.keys) {
                checkNotNull(state.get(model.version.value, query)).shouldBeInstanceOf<ParseStatus.ParseSuccess>()
            }
        }

        "an FK-only relation over one foreign key: PARSED (the key conditions the join)" {
            parsed(model(keys = 1)).shouldBeInstanceOf<ParseStatus.ParseSuccess>()
        }

        "an FK-only relation over two foreign keys between the same tables: logical_query_join_unresolved" {
            val message = parsed(model(keys = 2)).shouldBeInstanceOf<ParseStatus.ParseFailure>().message
            message shouldStartWith "logical_query_join_unresolved: "
            message shouldContain "JOIN_AMBIGUOUS_RELATIONS"
        }

        "a logical query that reads another saved query is checked the same: one foreign key PARSED, two refused" {
            // The reader parses in a later wave, against the handle that serves `shopper_ids` under its obj name; its
            // own bare JOIN still goes through the physical pass there, so two keys must refuse it, not fail silently.
            val one = statuses(model(keys = 1, moreEr = READS_SAVED))
            one.getValue("shopper_ids").shouldBeInstanceOf<ParseStatus.ParseSuccess>()
            one.getValue("sales_of_listed").shouldBeInstanceOf<ParseStatus.ParseSuccess>()
            val two = statuses(model(keys = 2, moreEr = READS_SAVED))
            two.getValue("shopper_ids").shouldBeInstanceOf<ParseStatus.ParseSuccess>()
            val message = two.getValue("sales_of_listed").shouldBeInstanceOf<ParseStatus.ParseFailure>().message
            message shouldStartWith "logical_query_join_unresolved: "
            message shouldContain "JOIN_AMBIGUOUS_RELATIONS"
        }

        "L-30 — a logical query read by another is a temporary entity: a bare JOIN rides the relation it inherits" {
            // `spend_per_shopper` passes sale.shopper_id through as its GROUP BY key, so it inherits the FK-only
            // relation sale → shopper; one that passes no attribute through inherits nothing and is refused as before.
            val statuses = statuses(model(keys = 1, moreEr = READS_AS_TEMPORARY))
            statuses.getValue("spend_per_shopper").shouldBeInstanceOf<ParseStatus.ParseSuccess>()
            statuses.getValue("big_spender_names").shouldBeInstanceOf<ParseStatus.ParseSuccess>()
            val message =
                statuses
                    .getValue(
                        "total_beside_shoppers",
                    ).shouldBeInstanceOf<ParseStatus.ParseFailure>()
                    .message
            message shouldStartWith "logical_query_join_unresolved: "
            message shouldContain "JOIN_NO_RELATION"
        }
    }) {
    private companion object {
        const val FK_CUSTOMER =
            "\ndef fk fk_ss_customer { from: [db.dbo.store_sales.ss_customer_sk], to: [db.dbo.customer.c_customer_sk] }"
        const val FK_BILLING =
            "\ndef fk fk_ss_billing { from: [db.dbo.store_sales.ss_billing_customer_sk], to: [db.dbo.customer.c_customer_sk] }"

        /**
         * A join-free logical query, and one that reads it by obj name beside a bare JOIN over the FK-only relation
         * `sale_shopper` — the join the physical pass conditions from one key and cannot from two.
         */
        const val READS_SAVED =
            "\n\ndef query shopper_ids {\n    sourceText: \"\"\"sql\n" +
                "SELECT shopper.shopper_id FROM shopper\n\"\"\"\n}\n\n" +
                "def query sales_of_listed {\n    sourceText: \"\"\"sql\n" +
                "SELECT sale.ticket_number FROM sale JOIN shopper " +
                "JOIN obj.query.shopper_ids i ON i.shopper_id = shopper.shopper_id\n\"\"\"\n}\n"

        /**
         * L-30: `big_spender_names` joins `shopper` to a logical query that passes `sale.shopper_id` through (it inherits
         * `sale_shopper`); `total_beside_shoppers` joins it to one that passes nothing through (no relation to inherit).
         */
        const val READS_AS_TEMPORARY =
            "\n\ndef query spend_per_shopper {\n    sourceText: \"\"\"sql\n" +
                "SELECT sale.shopper_id, SUM(sale.revenue) AS revenue FROM sale " +
                "GROUP BY sale.shopper_id\n\"\"\"\n}\n\n" +
                "def query spend_total {\n    sourceText: \"\"\"sql\n" +
                "SELECT SUM(sale.revenue) AS revenue FROM sale\n\"\"\"\n}\n\n" +
                "def query big_spender_names {\n    sourceText: \"\"\"sql\n" +
                "SELECT shopper.shopper_id, r.revenue FROM obj.query.spend_per_shopper r JOIN shopper " +
                "WHERE r.revenue > 100\n\"\"\"\n}\n\n" +
                "def query total_beside_shoppers {\n    sourceText: \"\"\"sql\n" +
                "SELECT shopper.shopper_id, t.revenue FROM obj.query.spend_total t JOIN shopper\n\"\"\"\n}\n"

        /** Two db queries whose joins are the author's: a comma join conditioned in WHERE, and a deliberate CROSS JOIN. */
        const val COMMA_AND_CROSS =
            "model query\n\ndef query comma_join {\n    sourceText: \"\"\"sql\n" +
                "SELECT s.ss_ticket_number, c.c_customer_sk FROM store_sales s, customer c " +
                "WHERE s.ss_billing_customer_sk = c.c_customer_sk\n\"\"\"\n}\n\n" +
                "def query cross_join {\n    sourceText: \"\"\"sql\n" +
                "SELECT s.ss_ticket_number, c.c_customer_sk FROM store_sales s CROSS JOIN customer c\n\"\"\"\n}\n"
    }
}
