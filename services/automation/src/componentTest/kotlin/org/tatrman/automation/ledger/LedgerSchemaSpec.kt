// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.ledger

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tatrman.automation.LedgerTestDb
import org.tatrman.automation.migrateLedger
import org.tatrman.automation.queue.LedgerQueue
import org.tatrman.automation.queue.Pgmq
import java.time.Duration

/**
 * AU contracts §2 against a real Postgres: Flyway migrates a clean database (ledger · pgmq · queues · V4's D-98
 * index · V5 dispatch · V6 verified subject), every table carries its columns, the Exposed objects map them, the D-46/D-98 indexes exist
 * (D-98's with NULLs equal), the four queues exist, and `run_protocol` refuses every change but an INSERT (D-110).
 */
class LedgerSchemaSpec :
    StringSpec({
        val db = LedgerTestDb.ready()

        fun <T> rows(
            sql: String,
            read: (java.sql.ResultSet) -> T,
        ): List<T> =
            transaction {
                exec(sql) { rs -> buildList { while (rs.next()) add(read(rs)) } } ?: emptyList()
            }

        "Flyway applies V1–V7 on a clean database; a second migrate applies nothing" {
            LedgerTestDb.firstMigration shouldBe 7
            rows(
                "SELECT version, success FROM automation.flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank",
            ) {
                it.getString(1) to it.getBoolean(2)
            } shouldContainExactly (1..7).map { it.toString() to true }
            migrateLedger(db) shouldBe 0
        }

        "every table of §2 exists with the contract's columns" {
            val actual =
                rows(
                    "SELECT table_name, column_name FROM information_schema.columns WHERE table_schema = 'automation'",
                ) {
                    it.getString(1) to it.getString(2)
                }.groupBy({ it.first }, { it.second })
            val missing =
                CONTRACT_COLUMNS.flatMap { (table, columns) ->
                    columns.filterNot { it in actual[table].orEmpty() }.map { "$table.$it" }
                }
            missing.shouldBeEmpty()
        }

        "every Exposed object maps a real table and real columns, and reads back" {
            val actual =
                rows(
                    "SELECT table_name, column_name FROM information_schema.columns WHERE table_schema = 'automation'",
                ) {
                    "automation.${it.getString(1)}" to it.getString(2)
                }.groupBy({ it.first }, { it.second })
            for (table in LEDGER_TABLES) {
                actual[table.tableName].orEmpty() shouldContainAll table.columns.map { it.name.trim('"') }
                transaction { table.selectAll().limit(1).toList() }
            }
        }

        "the exactly-one and one-open-task indexes are partial uniques; the task reads are indexed" {
            val indexes =
                rows("SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'automation'") {
                    it.getString(1) to it.getString(2)
                }.toMap()
            indexes.getValue("runs_idempotency_key_claimed").let {
                it shouldContain "UNIQUE"
                it shouldContain "WHERE"
            }
            indexes.getValue("tasks_one_open").let {
                it shouldContain "UNIQUE"
                it shouldContain "(recipe, key, action)"
                it shouldContain "NULLS NOT DISTINCT"
            }
            indexes.keys shouldContainAll listOf("tasks_for_person", "tasks_open_due", "runs_abandoned")
        }

        "D-98 holds with a NULL in the triple: a second open set-level ask is refused; recipe-less tasks may pile up" {
            LedgerTestDb.reset()

            fun sql(value: String?) = value?.let { "'$it'" } ?: "NULL"

            fun open(
                recipe: String?,
                key: String?,
                action: String?,
            ) = transaction {
                exec(
                    "INSERT INTO automation.tasks (id, recipe, key, kind, action, status) VALUES (gen_random_uuid(), " +
                        "${sql(recipe)}, ${sql(key)}::jsonb, 'ask', ${sql(action)}, 'open')",
                )
            }
            open("release_order", null, "ask_sales")
            shouldThrow<ExposedSQLException> { open("release_order", null, "ask_sales") }.message shouldContain
                "tasks_one_open"
            open("release_order", "{\"order\": 1}", null)
            shouldThrow<ExposedSQLException> { open("release_order", "{\"order\": 1}", null) }
            open(null, null, null)
            open(null, null, null)
            rows("SELECT count(*) FROM automation.tasks WHERE status = 'open'") { it.getLong(1) } shouldBe listOf(4L)
        }

        "the four queues exist, and the startup check finds nothing to create" {
            Pgmq().listQueues() shouldContainAll LedgerQueue.entries.map { it.queueName }
            Pgmq().ensure().shouldBeEmpty()
        }

        "run_protocol: INSERT only — UPDATE, DELETE and TRUNCATE are refused; retention may delete when it opts in" {
            LedgerTestDb.reset()
            val ledger = Ledger(Duration.ofMinutes(30))
            val run = ledger.claimRun(RunOpen(recipe = "release_order")).runId
            ledger.appendProtocol(run, ProtocolSection.AUTOMATION, JsonObject(emptyMap()))

            for (sql in listOf(
                "UPDATE automation.run_protocol SET payload = '{}'::jsonb",
                "DELETE FROM automation.run_protocol",
                "TRUNCATE automation.run_protocol",
            )) {
                shouldThrow<ExposedSQLException> { transaction { exec(sql) } }.message shouldContain "INSERT-only"
            }
            transaction {
                exec("SET LOCAL automation.retention = 'on'")
                exec("DELETE FROM automation.run_protocol")
            }
            rows("SELECT count(*) FROM automation.run_protocol") { it.getLong(1) } shouldBe listOf(0L)
        }
    }) {
    companion object {
        /** AU contracts §2, column for column (+ `tasks.recipe`, which D-98's unique needs). */
        val CONTRACT_COLUMNS =
            mapOf(
                "runs" to
                    listOf(
                        "id",
                        "recipe",
                        "program",
                        "deployment",
                        "bundle",
                        "fp_text",
                        "fp_values",
                        "fp_program",
                        "fp_deployment",
                        "model_fp",
                        "port",
                        "as_of",
                        "author",
                        "run_as",
                        "caller",
                        "caller_subject",
                        "origin",
                        "status",
                        "dry_run",
                        "governance",
                        "idempotency_key",
                        "replay_of",
                        "batch_id",
                        "protocol_ref",
                        "started_at",
                        "ended_at",
                        "budget_sync",
                        "budget_batch",
                        "error",
                    ),
                "run_evaluations" to listOf("run", "owner", "message_id", "claimed_at", "archived_at"),
                "run_outputs" to listOf("run", "island", "port", "rows", "bytes", "path", "sha256", "released_at"),
                "tasks" to
                    listOf(
                        "id",
                        "run",
                        "recipe",
                        "key",
                        "kind",
                        "action",
                        "connector",
                        "target_role",
                        "target_person",
                        "about",
                        "options",
                        "detail",
                        "group_key",
                        "correlation",
                        "status",
                        "due",
                        "answer",
                        "answered_by",
                        "answered_at",
                        "dry_run",
                        "origin",
                        "approval",
                        "created_at",
                    ),
                "windows" to
                    listOf("id", "recipe", "program", "by", "opened_at", "closes_at", "event_count", "run", "status"),
                "window_events" to listOf("window", "seq", "key", "payload_ref", "received_at"),
                "deliveries" to
                    listOf(
                        "id",
                        "run",
                        "program",
                        "action",
                        "group_key",
                        "period",
                        "rows_ref",
                        "status",
                        "digest_id",
                        "connector",
                        "delivered_at",
                        "dry_run",
                    ),
                "callbacks" to listOf("id", "deployment", "url", "secret_ref", "kinds", "created_at"),
                "approvals" to
                    listOf(
                        "id",
                        "object_kind",
                        "object",
                        "fp_text",
                        "fp_values",
                        "fp",
                        "kind",
                        "approver",
                        "at",
                        "note",
                        "task",
                        "status",
                    ),
                "run_protocol" to listOf("run", "section", "seq", "payload", "at"),
                "batches" to listOf("id", "run", "state", "rows", "committed_by", "committed_at", "rev_of"),
                "batch_rows" to listOf("batch", "seq", "entity", "op", "key", "row", "group_key", "status", "error"),
                "bundles" to listOf("id", "kind", "branch", "commit", "model_fp", "loaded_at", "last_used_at"),
            )
    }
}
