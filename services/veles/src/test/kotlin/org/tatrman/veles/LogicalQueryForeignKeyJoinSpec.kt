// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles

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
import org.tatrman.veles.parse.QueryParseState
import org.tatrman.veles.parse.QueryParseWorker
import java.nio.file.Files

/**
 * LQ (contract L-3, review of the legacy L1) — a logical query whose bare JOIN rides an FK-only relation (no `join:`
 * pairs) is conditioned from the foreign key only at MAP_TO_PHYSICAL: at the ER level the joiner reports INFO
 * `JOIN_RELATION_WITHOUT_PAIRS`. With one foreign key between the tables that is a real ON; with two, the physical
 * joiner cannot choose (`JOIN_AMBIGUOUS_RELATIONS`) and every read of the stacked entity would be a Cartesian product.
 * The worker takes the body to the physical level once, and refuses the second.
 */
class LogicalQueryForeignKeyJoinSpec :
    StringSpec({
        fun db(secondKey: Boolean) =
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

            def fk fk_ss_customer { from: [db.dbo.store_sales.ss_customer_sk], to: [db.dbo.customer.c_customer_sk] }
            """.trimIndent() +
                if (secondKey) {
                    "\ndef fk fk_ss_billing { from: [db.dbo.store_sales.ss_billing_customer_sk], " +
                        "to: [db.dbo.customer.c_customer_sk] }\n"
                } else {
                    "\n"
                }

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

        fun model(secondKey: Boolean): Model {
            val root = Files.createTempDirectory("lq-fk")
            Files.writeString(root.resolve("db.ttrm"), db(secondKey))
            Files.writeString(root.resolve("er.ttrm"), er)
            val source = FileBasedSource("lq-fk", 100, LocalFsStorage("lq-fk", root))
            return ModelReconciler(ModelDescriptor(id = "lq-fk", name = "lq-fk")).reconcile(listOf(source.load())).model
        }

        fun parsed(model: Model): ParseStatus {
            val state = QueryParseState().also { it.reset(model.version.value, model.queries.keys) }
            QueryParseWorker().also { runBlocking { it.parseAll(model, state).join() } }.close()
            val query = model.queries.keys.single { it.schemaCode == SchemaCode.ER }
            return checkNotNull(state.get(model.version.value, query))
        }

        "a db query written with a comma join + WHERE is not a bare join: PARSED (the check is the logical lane's)" {
            val root = Files.createTempDirectory("lq-comma")
            Files.writeString(root.resolve("db.ttrm"), db(secondKey = false))
            Files.writeString(
                root.resolve("queries.ttrm"),
                "model query\n\ndef query comma_join {\n    sourceText: \"\"\"sql\n" +
                    "SELECT s.ss_ticket_number, c.c_customer_sk FROM store_sales s, customer c " +
                    "WHERE s.ss_billing_customer_sk = c.c_customer_sk\n\"\"\"\n}\n",
            )
            val source = FileBasedSource("lq-comma", 100, LocalFsStorage("lq-comma", root))
            val model = ModelReconciler(ModelDescriptor(id = "c", name = "c")).reconcile(listOf(source.load())).model
            val state = QueryParseState().also { it.reset(model.version.value, model.queries.keys) }
            QueryParseWorker().also { runBlocking { it.parseAll(model, state).join() } }.close()
            checkNotNull(
                state.get(model.version.value, model.queries.keys.single()),
            ).shouldBeInstanceOf<ParseStatus.ParseSuccess>()
        }

        "a logical query over an entity on a comma-join db query: PARSED (that join is the base query's)" {
            val root = Files.createTempDirectory("lq-comma-stacked")
            Files.writeString(root.resolve("db.ttrm"), db(secondKey = false))
            Files.writeString(
                root.resolve("queries.ttrm"),
                "model query\n\ndef query comma_join {\n    sourceText: \"\"\"sql\n" +
                    "SELECT s.ss_ticket_number, c.c_customer_sk FROM store_sales s, customer c " +
                    "WHERE s.ss_billing_customer_sk = c.c_customer_sk\n\"\"\"\n}\n",
            )
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
            val logical = model.queries.keys.single { it.schemaCode == SchemaCode.ER }
            checkNotNull(state.get(model.version.value, logical)).shouldBeInstanceOf<ParseStatus.ParseSuccess>()
        }

        "an FK-only relation over one foreign key: PARSED (the key conditions the join)" {
            parsed(model(secondKey = false)).shouldBeInstanceOf<ParseStatus.ParseSuccess>()
        }

        "an FK-only relation over two foreign keys between the same tables: logical_query_join_unresolved" {
            val message = parsed(model(secondKey = true)).shouldBeInstanceOf<ParseStatus.ParseFailure>().message
            message shouldStartWith "logical_query_join_unresolved: "
            message shouldContain "JOIN_AMBIGUOUS_RELATIONS"
        }
    })
