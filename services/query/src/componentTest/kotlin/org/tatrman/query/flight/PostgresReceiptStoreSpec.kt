// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant

/**
 * BI (contracts C-6) — `bi_receipts` on a real Postgres (`postgres:18` = the major the clusters' CNPG images run):
 * the service's own Flyway configuration migrates it into schema `query`, every column round-trips with its type
 * (`text[]` roles, `jsonb` security_applied, `timestamptz`), and the read route's questions are answered newest first.
 */
class PostgresReceiptStoreSpec :
    StringSpec({
        val pg =
            PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
                .withDatabaseName("query")
                .withUsername("query")
                .withPassword("query")
                .also { it.start() }
        afterSpec { pg.stop() }
        val dataSource =
            PGSimpleDataSource().apply {
                setURL(pg.jdbcUrl)
                user = "query"
                password = "query"
            }

        fun receipt(
            id: String,
            user: String = "petr",
            ts: Instant = Instant.parse("2026-10-21T10:15:30.123Z"),
        ) = Receipt(
            correlationId = id,
            ts = ts,
            userId = user,
            roles = listOf("kantheon-area-hartland", "kantheon-scope-dc-5"),
            world = "hartland_cz",
            connectionId = "pg-hartland-cz",
            client = "jdbc",
            statement = "SELECT sk, warehouse_name FROM er.entity.warehouse",
            executedSql = "SELECT \"sk\", \"warehouse_name\" FROM \"dbo\".\"warehouse\" WHERE \"sk\" IN (5)",
            securityApplied =
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("rule_id", "dc_scope")
                            put("predicate_summary", "WHERE warehouse IN (your DCs)")
                            put("table", "db.dbo.warehouse")
                        },
                    )
                },
            rowsTotal = 1,
            topNApplied = true,
            status = "ok",
            errorCode = "",
            notices =
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("code", "top_n_applied")
                            put("severity", "WARNING")
                            put("message", "Answer limited to 200 rows by the row cap.")
                            put("requested", 0)
                            put("applied", 200)
                        },
                    )
                },
        )

        "Flyway migrates bi_receipts into schema query, once: the table (V1), then its notices (V2)" {
            PostgresReceiptStore.migrate(dataSource) shouldBe 2
            PostgresReceiptStore.migrate(dataSource) shouldBe 0
            dataSource.connection.use { c ->
                val sql = "SELECT table_schema FROM information_schema.tables WHERE table_name = 'bi_receipts'"
                c.createStatement().executeQuery(sql).use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) } shouldContainExactly listOf("query")
                }
            }
        }

        "every C-6 column round-trips, types included; a repeated correlation id is written once" {
            val store = PostgresReceiptStore(dataSource)
            val written = receipt("c-1")
            store.insert(written)
            store.insert(written.copy(status = "failed"))
            store.find(ReceiptQuery(correlationId = "c-1")) shouldContainExactly listOf(written)
        }

        "a row written without notices (before V2) reads as none" {
            val store = PostgresReceiptStore(dataSource)
            dataSource.connection.use { c ->
                c.createStatement().executeUpdate(
                    "INSERT INTO query.bi_receipts (correlation_id, ts, user_id, client, statement, status) " +
                        "VALUES ('old-1', now(), 'petr', 'jdbc', 'SELECT 1', 'ok')",
                )
            }
            store.find(ReceiptQuery(correlationId = "old-1")).single().notices shouldBe buildJsonArray { }
        }

        "by user since an instant, newest first, at most the limit" {
            val store = PostgresReceiptStore(dataSource)
            val base = Instant.parse("2026-10-20T08:00:00Z")
            (1..4).forEach { i -> store.insert(receipt("m-$i", user = "marketa", ts = base.plusSeconds(i * 60L))) }
            store.insert(receipt("p-9", user = "petr", ts = base.plusSeconds(600)))
            store
                .find(ReceiptQuery(userId = "marketa", since = base.plusSeconds(120), limit = 2))
                .map { it.correlationId } shouldContainExactly listOf("m-4", "m-3")
            store.find(ReceiptQuery(userId = "marketa")).size shouldBe 4
        }
    })
