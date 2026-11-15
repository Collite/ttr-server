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
import java.time.Duration

/**
 * AU contracts §2 against a real Postgres: Flyway migrates a clean database (V1–V12; V12 is D-142's removals), every
 * table carries its columns, the Exposed objects map them, the D-46 index and the dispatch claim's index exist, the
 * removed tables and the queue schema are gone, and `run_protocol` refuses every change but an INSERT (D-110).
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

        "Flyway applies V1–V12 on a clean database; a second migrate applies nothing" {
            LedgerTestDb.firstMigration shouldBe 12
            rows(
                "SELECT version, success FROM automation.flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank",
            ) {
                it.getString(1) to it.getBoolean(2)
            } shouldContainExactly (1..12).map { it.toString() to true }
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

        "the exactly-one index is a partial unique; the abandonment sweep and the dispatch claim are indexed" {
            val indexes =
                rows("SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'automation'") {
                    it.getString(1) to it.getString(2)
                }.toMap()
            indexes.getValue("runs_idempotency_key_claimed").let {
                it shouldContain "UNIQUE"
                it shouldContain "WHERE"
            }
            indexes.getValue("run_evaluations_ready") shouldContain "(owner, enqueued_at)"
            indexes.keys shouldContainAll listOf("runs_abandoned", "program_windows_pkey")
        }

        "D-142 (V12): tasks, windows, window_events, deliveries, callbacks and the queue schema are gone" {
            rows(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'automation' " +
                    "AND table_name IN ('tasks', 'windows', 'window_events', 'deliveries', 'callbacks')",
            ) { it.getString(1) }.shouldBeEmpty()
            rows("SELECT nspname FROM pg_namespace WHERE nspname = 'pgmq'") { it.getString(1) }.shouldBeEmpty()
            rows(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'automation.approvals'::regclass AND contype = 'f'",
            ) { it.getString(1) }.shouldBeEmpty()
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
        /** AU contracts §2, column for column (S3.2: the engine-run columns and `program_windows`). */
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
                        "engine_instance_id",
                        "business_key",
                        "engine_task_id",
                        "asserted_person",
                        "person_verified",
                        "engine_deployment_id",
                    ),
                "run_evaluations" to
                    listOf("run", "owner", "enqueued_at", "visible_at", "claimed_at", "archived_at"),
                "program_windows" to listOf("program", "window", "last_to", "run", "advanced_at"),
                "run_outputs" to listOf("run", "island", "port", "rows", "bytes", "path", "sha256", "released_at"),
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
