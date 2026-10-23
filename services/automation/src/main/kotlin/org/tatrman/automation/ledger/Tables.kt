// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.ledger

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.json.booleanOrNull
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.json.jsonb

/**
 * The ledger's tables (AU contracts §2), one Exposed object per table of `V1__ledger.sql`. The SQL is the
 * schema's truth — these objects map it and never create it (Flyway does). Status columns stay `text`
 * here; their vocabularies are the CHECK constraints of the migration and the enums of [RunStatus].
 */
private val ledgerJson = Json { ignoreUnknownKeys = true }

// Generic JsonElement number serialization may pass parsed numeric literals through Double. Keep the
// original JSON lexemes at the JDBC boundary, and preserve raw numeric leaves for subsequent HTTP encoding.
private fun Table.json(name: String): Column<JsonElement> =
    jsonb(name, { it.toString() }, { exactNumbers(ledgerJson.parseToJsonElement(it)) })

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
private fun exactNumbers(value: JsonElement): JsonElement =
    when (value) {
        is JsonObject -> JsonObject(value.mapValues { exactNumbers(it.value) })
        is JsonArray -> JsonArray(value.map(::exactNumbers))
        is JsonPrimitive ->
            if (value == JsonNull || value.isString || value.booleanOrNull != null) {
                value
            } else {
                JsonUnquotedLiteral(value.content)
            }
    }

object Bundles : Table("automation.bundles") {
    val id = text("id")
    val kind = text("kind")
    val branch = text("branch").nullable()
    val commit = text("commit")
    val modelFp = text("model_fp").nullable()
    val loadedAt = timestampWithTimeZone("loaded_at")
    val lastUsedAt = timestampWithTimeZone("last_used_at").nullable()

    override val primaryKey = PrimaryKey(id)
}

object Runs : Table("automation.runs") {
    val id = javaUUID("id")
    val recipe = text("recipe")
    val program = text("program").nullable()
    val deployment = text("deployment").nullable()
    val bundle = text("bundle").nullable()
    val fpText = text("fp_text").nullable()
    val fpValues = text("fp_values").nullable()
    val fpProgram = text("fp_program").nullable()
    val fpDeployment = text("fp_deployment").nullable()
    val modelFp = text("model_fp").nullable()
    val port = json("port")
    val asOf = timestampWithTimeZone("as_of").nullable()
    val author = text("author").nullable()
    val runAs = text("run_as").nullable()
    val caller = text("caller").nullable()
    val callerSubject = text("caller_subject").nullable()
    val origin = text("origin").nullable()
    val status = text("status")
    val dryRun = bool("dry_run")
    val governance = text("governance")
    val idempotencyKey = text("idempotency_key").nullable()
    val replayOf = javaUUID("replay_of").nullable()
    val batchId = javaUUID("batch_id").nullable()
    val protocolRef = text("protocol_ref").nullable()
    val startedAt = timestampWithTimeZone("started_at")
    val endedAt = timestampWithTimeZone("ended_at").nullable()
    val budgetSync = long("budget_sync").nullable()
    val budgetBatch = long("budget_batch").nullable()
    val error = json("error").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** Internal dispatch journal; the queue is a delivery mechanism, this row fences execution. */
object RunEvaluations : Table("automation.run_evaluations") {
    val run = javaUUID("run")
    val owner = javaUUID("owner")
    val messageId = long("message_id")
    val claimedAt = timestampWithTimeZone("claimed_at").nullable()
    val archivedAt = timestampWithTimeZone("archived_at").nullable()

    override val primaryKey = PrimaryKey(run)
}

object RunOutputs : Table("automation.run_outputs") {
    val run = javaUUID("run")
    val island = text("island")
    val port = text("port")
    val rows = long("rows")
    val bytes = long("bytes")
    val path = text("path").nullable()
    val sha256 = text("sha256").nullable()
    val releasedAt = timestampWithTimeZone("released_at").nullable()

    override val primaryKey = PrimaryKey(run, island, port)
}

object Tasks : Table("automation.tasks") {
    val id = javaUUID("id")
    val run = javaUUID("run").nullable()
    val recipe = text("recipe").nullable()
    val key = json("key").nullable()
    val kind = text("kind")
    val action = text("action").nullable()
    val connector = text("connector").nullable()
    val targetRole = text("target_role").nullable()
    val targetPerson = text("target_person").nullable()
    val about = text("about").nullable()
    val options = json("options").nullable()
    val detail = json("detail").nullable()
    val groupKey = json("group_key").nullable()
    val correlation = text("correlation").nullable()
    val status = text("status")
    val due = timestampWithTimeZone("due").nullable()
    val answer = json("answer").nullable()
    val answeredBy = text("answered_by").nullable()
    val answeredAt = timestampWithTimeZone("answered_at").nullable()
    val dryRun = bool("dry_run")
    val origin = text("origin").nullable()
    val approval = json("approval").nullable()
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

object Windows : Table("automation.windows") {
    val id = javaUUID("id")
    val recipe = text("recipe")
    val program = text("program").nullable()
    val by = json("by")
    val openedAt = timestampWithTimeZone("opened_at")
    val closesAt = timestampWithTimeZone("closes_at").nullable()
    val eventCount = integer("event_count")
    val run = javaUUID("run").nullable()
    val status = text("status")

    override val primaryKey = PrimaryKey(id)
}

object WindowEvents : Table("automation.window_events") {
    val window = javaUUID("window")
    val seq = integer("seq")
    val key = json("key").nullable()
    val payloadRef = text("payload_ref").nullable()
    val receivedAt = timestampWithTimeZone("received_at")

    override val primaryKey = PrimaryKey(window, seq)
}

object Deliveries : Table("automation.deliveries") {
    val id = javaUUID("id")
    val run = javaUUID("run").nullable()
    val program = text("program").nullable()
    val action = text("action").nullable()
    val groupKey = json("group_key").nullable()
    val period = text("period").nullable()
    val rowsRef = text("rows_ref").nullable()
    val status = text("status")
    val digestId = javaUUID("digest_id").nullable()
    val connector = text("connector").nullable()
    val deliveredAt = timestampWithTimeZone("delivered_at").nullable()
    val dryRun = bool("dry_run")

    override val primaryKey = PrimaryKey(id)
}

object Callbacks : Table("automation.callbacks") {
    val id = javaUUID("id")
    val deployment = text("deployment")
    val url = text("url")
    val secretRef = text("secret_ref").nullable()
    val kinds = array<String>("kinds", TextColumnType())
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(id)
}

object Approvals : Table("automation.approvals") {
    val id = javaUUID("id")
    val objectKind = text("object_kind")
    val obj = text("object")
    val fpText = text("fp_text").nullable()
    val fpValues = text("fp_values").nullable()
    val fp = text("fp").nullable()
    val kind = text("kind")
    val approver = text("approver")
    val at = timestampWithTimeZone("at")
    val note = text("note").nullable()
    val task = javaUUID("task").nullable()
    val status = text("status")

    override val primaryKey = PrimaryKey(id)
}

object Batches : Table("automation.batches") {
    val id = javaUUID("id")
    val run = javaUUID("run")
    val state = text("state")
    val rows = integer("rows")
    val committedBy = text("committed_by").nullable()
    val committedAt = timestampWithTimeZone("committed_at").nullable()
    val revOf = javaUUID("rev_of").nullable()

    override val primaryKey = PrimaryKey(id)
}

object BatchRows : Table("automation.batch_rows") {
    val batch = javaUUID("batch")
    val seq = integer("seq")
    val entity = text("entity")
    val op = text("op")
    val key = json("key").nullable()
    val row = json("row").nullable()
    val groupKey = json("group_key").nullable()
    val status = text("status")
    val error = text("error").nullable()

    override val primaryKey = PrimaryKey(batch, seq)
}

object RunProtocol : Table("automation.run_protocol") {
    val run = javaUUID("run")
    val section = text("section")
    val seq = integer("seq")
    val payload = json("payload")
    val at = timestampWithTimeZone("at")

    override val primaryKey = PrimaryKey(run, section, seq)
}

/** Every ledger table, in an order that respects their foreign keys (a test reads each one back). */
val LEDGER_TABLES: List<Table> =
    listOf(
        Bundles,
        Runs,
        RunOutputs,
        RunEvaluations,
        Tasks,
        Windows,
        WindowEvents,
        Deliveries,
        Callbacks,
        Approvals,
        Batches,
        BatchRows,
        RunProtocol,
    )
