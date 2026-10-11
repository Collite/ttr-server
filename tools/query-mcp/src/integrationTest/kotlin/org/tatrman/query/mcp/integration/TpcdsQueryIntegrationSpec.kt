// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.mcp.integration

import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.runBlocking
import org.tatrman.testkit.integration.RequiresContext
import org.tatrman.testkit.integration.RequiresContextExtension
import org.tatrman.testkit.integration.contextHandle

/**
 * WS-C2 T1 — the **`tpcds-query`** showcase (Goals 2 + 4): the four TPC-DS curated shapes driven
 * through query-mcp `query` over the **real** forked chain on the **Postgres** worker —
 * query-mcp → Query → Translate → Validate → Dispatch → **Postgres** → `tpc-ds-1g` on `test-pg` — asserted
 * against the **deterministic SF1 oracle**. This turns the manual 2026-07-07 MP-2 smoke into a
 * repeatable integration spec; it is the convergence demo that lands MP-4.
 *
 * Gated by `@RequiresContext("tpcds-query")` — compiles + skips until olymp stands the context up
 * (C2 T2: services query/query-mcp/translate/validate/dispatch/postgres/veles + platform `test-pg`,
 * with Postgres pointed at `pg-tpcds` and Veles serving the TPC-DS model).
 *
 * ## The oracle (SF1, year 2002 — as proven live in the MP-2 run)
 *  | query                   | shape                   | rows |
 *  |-------------------------|-------------------------|------|
 *  | store_sales_by_month    | join + group-by         |  12  | ← 12 calendar months (structural)
 *  | top_items_by_revenue    | join + agg + ORDER/LIMIT |  30  | ← distinct items sold in 2002 (< LIMIT 100)
 *  | customer_running_total  | window (OVER/PARTITION) |  30  |
 *  | channel_revenue_cte     | CTE + UNION ALL         |   3  | ← the 3 channels store/catalog/web (structural)
 *
 * The `{year}` parameter is inlined as the literal `2002` (the `query` tool takes raw SQL, no
 * parameter map); `row_limit` is set well above every count so the oracle isn't clipped. The
 * `pg-tpcds` connection carries no tenant column (`requires-tenant-id=false`), so this exercises the
 * no-RLS analytical path — the identity is a plain analyst bearer.
 */
@RequiresContext("tpcds-query")
@ApplyExtension(RequiresContextExtension::class)
@Tags("integration")
class TpcdsQueryIntegrationSpec :
    StringSpec({

        val analyst = unsignedJwt("alice", roles = listOf("analyst"))

        // The four curated shapes, verbatim from the Veles tpcds fixture
        // (`services/veles/src/test/resources/model-tpcds/tpcds/db.ttr`),
        // with `{year}` inlined to the oracle year 2002.
        val storeSalesByMonth =
            """
            SELECT d.d_year, d.d_moy, SUM(ss.ss_sales_price) AS total_sales
            FROM store_sales ss
            JOIN date_dim d ON ss.ss_sold_date_sk = d.d_date_sk
            WHERE d.d_year = 2002
            GROUP BY d.d_year, d.d_moy
            ORDER BY d.d_year, d.d_moy
            """.trimIndent()

        val topItemsByRevenue =
            """
            SELECT i.i_item_id, SUM(ss.ss_net_paid) AS revenue
            FROM store_sales ss
            JOIN item i ON ss.ss_item_sk = i.i_item_sk
            JOIN date_dim d ON ss.ss_sold_date_sk = d.d_date_sk
            WHERE d.d_year = 2002
            GROUP BY i.i_item_id
            ORDER BY revenue DESC
            LIMIT 100
            """.trimIndent()

        val customerRunningTotal =
            """
            SELECT c.c_customer_id, d.d_date,
                   SUM(ss.ss_net_paid) OVER (PARTITION BY c.c_customer_sk ORDER BY d.d_date) AS running_total
            FROM store_sales ss
            JOIN customer c ON ss.ss_customer_sk = c.c_customer_sk
            JOIN date_dim d ON ss.ss_sold_date_sk = d.d_date_sk
            WHERE d.d_year = 2002
            """.trimIndent()

        val channelRevenueCte =
            """
            WITH all_sales AS (
                SELECT ss_sold_date_sk AS sold_date_sk, ss_net_paid AS net_paid, 'store' AS channel FROM store_sales
                UNION ALL
                SELECT cs_sold_date_sk AS sold_date_sk, cs_net_paid AS net_paid, 'catalog' AS channel FROM catalog_sales
                UNION ALL
                SELECT ws_sold_date_sk AS sold_date_sk, ws_net_paid AS net_paid, 'web' AS channel FROM web_sales
            )
            SELECT a.channel, SUM(a.net_paid) AS revenue
            FROM all_sales a
            JOIN date_dim d ON a.sold_date_sk = d.d_date_sk
            WHERE d.d_year = 2002
            GROUP BY a.channel
            ORDER BY revenue DESC
            """.trimIndent()

        suspend fun query(sql: String) = contextHandle().callQuery(sqlQueryArgs(sql, rowLimit = 1000), analyst)

        // Surface the full error envelope (code + body) on a non-ok result, so a failing live run
        // names the pipeline error (detection_failed / validate / translate / execute …) instead of
        // a bare `expected false but was true`.
        fun CallToolResult.assertOk() =
            withClue({ "query failed — code=${firstMessageCode()} body=${bodyText().take(800)}" }) {
                isError shouldBe false
                ok() shouldBe true
            }

        "store_sales_by_month (join + group-by) returns the 12 monthly rows for 2002" {
            val res = runBlocking { query(storeSalesByMonth) }
            res.assertOk()
            res.rowCount() shouldBe 12
            res.columnNames() shouldContain "total_sales"
        }

        "top_items_by_revenue (join + agg + ORDER/LIMIT) returns the SF1 item count for 2002" {
            val res = runBlocking { query(topItemsByRevenue) }
            res.assertOk()
            res.rowCount() shouldBe 30
        }

        "customer_running_total (window function) returns the SF1 running-total rows for 2002" {
            val res = runBlocking { query(customerRunningTotal) }
            res.assertOk()
            res.rowCount() shouldBe 30
        }

        "channel_revenue_cte (CTE + UNION ALL) returns exactly the three channels for 2002" {
            val res = runBlocking { query(channelRevenueCte) }
            res.assertOk()
            res.rowCount() shouldBe 3
            // The 3 channels are structural — every channel is present in the result body.
            val body = res.bodyText()
            body.contains("store") shouldBe true
            body.contains("catalog") shouldBe true
            body.contains("web") shouldBe true
        }
    })
