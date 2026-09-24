// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.observability

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.selectAll
import shared.libs.db.common.DatabaseConnection
import java.time.OffsetDateTime

/**
 * Exposed mapping for `prompt_logs` (V1 + V3 + V5). Read-only by design: the write
 * path is [PromptLogWriter]'s hand-rolled INSERT, which is deliberately a single
 * statement on a background channel, and giving it a second door here would
 * invite a synchronous writer onto the request path.
 *
 * Only the columns the inspect surface returns are mapped; the 1.x duplicates
 * (`model_name`, `provider`) and the FTS `tsv` are left out.
 */
internal object PromptLogs : Table("prompt_logs") {
    val id = long("id")
    val turnRef = text("turn_ref").nullable()
    val traceId = text("trace_id").nullable()
    val requestedModel = text("requested_model").nullable()
    val servedModel = text("served_model").nullable()
    val servedProvider = text("served_provider").nullable()
    val fallbackFrom = text("fallback_from").nullable()
    val cached = bool("cached")
    val tokensPrompt = integer("tokens_prompt").nullable()
    val tokensCompletion = integer("tokens_completion").nullable()
    val durationMs = long("duration_ms").nullable()
    val ttfbMs = long("ttfb_ms").nullable()
    val costUsd = decimal("cost_usd", 12, 6).nullable()
    val status = text("status").nullable()

    // Nullable to match the DDL: V1 gives `created_at` a DEFAULT, not a NOT NULL.
    // The writer omits the column and lets the default fire, but a legacy 1.x row
    // with an explicit NULL would make a non-null mapping throw and take the WHOLE
    // page to a 500 (review-079 R12).
    val createdAt = timestampWithTimeZone("created_at").nullable()
    val promptText = text("prompt_text").nullable()
    val responseText = text("response_text").nullable()

    // V5 (LC §2.1) — the caller's attribution. NULL on every row written before V5.
    val purpose = text("purpose").nullable()
    val endUserSubject = text("end_user_subject").nullable()
    val agentId = text("agent_id").nullable()

    override val primaryKey = PrimaryKey(id)
}

/** A `prompt_logs` row projection for the inspect surface. */
data class PromptLogRow(
    val id: Long,
    val turnRef: String?,
    val traceId: String?,
    val requestedModel: String?,
    val servedModel: String?,
    val servedProvider: String?,
    val fallbackFrom: String?,
    val cached: Boolean,
    val tokensPrompt: Int?,
    val tokensCompletion: Int?,
    val durationMs: Long?,
    val ttfbMs: Long?,
    val costUsd: Double?,
    val status: String?,
    val createdAt: OffsetDateTime?,
    val promptText: String?,
    val responseText: String?,
    val purpose: String? = null,
    val endUserSubject: String? = null,
    val agentId: String? = null,
)

/**
 * Read side of `prompt_logs` — the inspect surface's only DB access.
 *
 * **Correlation keys only.** There is deliberately no "list recent" or free
 * date-range query: a caller must name a `turn_ref` or a `trace_id` it already
 * holds. That keeps the endpoint an *inspect* tool rather than a bulk export of
 * every prompt and completion the estate has ever seen, which is what an
 * unfiltered listing of this table would be.
 *
 * **Per-row scope (LC-1).** [find] takes an explicit [RowScope]. [RowScope.OwnedBy] narrows the answer IN
 * SQL to rows whose `end_user_subject` equals it — never filtered in memory, so a page limit can never be
 * spent on rows the reader was not allowed to see, and rows with a NULL subject never match (they are
 * role-only). The scope has no default and no nullable form: a caller holding a nullable subject cannot
 * widen a read to every row by passing it through (review-100).
 */
class PromptLogRepo(
    private val db: DatabaseConnection,
) {
    /** Whose rows a read may return (⚑LC-1). */
    sealed interface RowScope {
        /** A role-holder (`llm-gateway-admin` / `llm-gateway-inspect`): every row for the key. */
        data object All : RowScope

        /** A subject reading their own rows: `end_user_subject = subject`, in SQL. */
        data class OwnedBy(
            val subject: String,
        ) : RowScope
    }

    fun find(
        turnRef: String?,
        traceId: String?,
        limit: Int,
        scope: RowScope,
    ): List<PromptLogRow> {
        if (turnRef.isNullOrBlank() && traceId.isNullOrBlank()) return emptyList()
        return db.query {
            PromptLogs
                .selectAll()
                .where {
                    // **turn_ref wins when both are supplied.** ORing them looked
                    // generous and was a leak: a trace that covers more than one turn
                    // would return the sibling turns' rows — prompt and completion
                    // bodies included — to a caller who named one turn. One turn is
                    // one trace today, so this was latent; it stops being latent the
                    // moment a session-level trace exists, which is exactly what the
                    // PT arc's OTel phase was building toward (review-079 R8).
                    //
                    // The trace id remains a first-class key on its own, for callers
                    // that hold a trace and no turn.
                    val byKey =
                        when {
                            !turnRef.isNullOrBlank() -> PromptLogs.turnRef eq turnRef
                            else -> PromptLogs.traceId eq traceId
                        }
                    when (scope) {
                        RowScope.All -> byKey
                        is RowScope.OwnedBy -> byKey and (PromptLogs.endUserSubject eq scope.subject)
                    }
                }
                // Oldest first: a turn's calls read in the order they happened. By `created_at` (the one PG
                // clock, stamped at insert), NOT by id: ids are reserved per replica in blocks (review-100
                // F3), so across replicas id order is not call order. The id breaks ties.
                .orderBy(PromptLogs.createdAt to SortOrder.ASC_NULLS_LAST, PromptLogs.id to SortOrder.ASC)
                .limit(limit)
                .map { it.toRow() }
        }
    }

    private fun ResultRow.toRow(): PromptLogRow =
        PromptLogRow(
            id = this[PromptLogs.id],
            turnRef = this[PromptLogs.turnRef],
            traceId = this[PromptLogs.traceId],
            requestedModel = this[PromptLogs.requestedModel],
            servedModel = this[PromptLogs.servedModel],
            servedProvider = this[PromptLogs.servedProvider],
            fallbackFrom = this[PromptLogs.fallbackFrom],
            cached = this[PromptLogs.cached],
            tokensPrompt = this[PromptLogs.tokensPrompt],
            tokensCompletion = this[PromptLogs.tokensCompletion],
            durationMs = this[PromptLogs.durationMs],
            ttfbMs = this[PromptLogs.ttfbMs],
            costUsd = this[PromptLogs.costUsd]?.toDouble(),
            status = this[PromptLogs.status],
            createdAt = this[PromptLogs.createdAt],
            promptText = this[PromptLogs.promptText],
            responseText = this[PromptLogs.responseText],
            purpose = this[PromptLogs.purpose],
            endUserSubject = this[PromptLogs.endUserSubject],
            agentId = this[PromptLogs.agentId],
        )
}

/** Default page size for the inspect surface when the caller names none. */
const val PROMPT_LOGS_DEFAULT_LIMIT: Int = 50

/**
 * Hard ceiling on the inspect page size. A caller asking for more gets this —
 * the endpoint is for correlating one turn's calls, and an unbounded page would
 * turn a debug tool into a bulk export of prompt and completion bodies.
 */
const val PROMPT_LOGS_MAX_LIMIT: Int = 200

/**
 * Clamp a caller-supplied `limit` into the allowed band.
 *
 * Absent or unparseable → [PROMPT_LOGS_DEFAULT_LIMIT]; anything above the
 * ceiling → [PROMPT_LOGS_MAX_LIMIT]; zero or negative → 1. Extracted from the
 * route so the rule is unit-testable without a database.
 */
fun clampPromptLogLimit(raw: Int?): Int = (raw ?: PROMPT_LOGS_DEFAULT_LIMIT).coerceIn(1, PROMPT_LOGS_MAX_LIMIT)

/**
 * Wire shape of one row (PT contracts §5). camelCase, matching the BFF DTO
 * convention on the far side.
 *
 * Bodies (`promptText` / `responseText`) are returned RAW — to a role-holder only ([includeBodies]).
 * Redaction is the assembler's job (PT-20/21) because only it knows the reader's profile — and a
 * gateway that pre-digested them would make the consumer's redaction floor
 * unauditable, since it could no longer see what it was supposed to remove. A subject reading their
 * own rows gets them as JSON null (review-100 F4, ruled 2026-09-24): raw bodies carry golem's whole
 * system prompt, and the only path to them is the role-gated, floor-redacted `full` profile
 * (architecture §7) — a direct call to this endpoint must not be a second, unredacted one.
 *
 * `purpose` / `agentId` (LC §2.3) are always present (JSON null on a pre-V5 row). `endUserSubject` is
 * written ONLY for a role-holder ([includeSubject]): a subject reading their own rows does not need to be
 * told who they are, and nobody else reads a row by subject.
 */
fun PromptLogRow.toJson(
    includeSubject: Boolean = false,
    includeBodies: Boolean = true,
): kotlinx.serialization.json.JsonObject =
    kotlinx.serialization.json.buildJsonObject {
        put("id", kotlinx.serialization.json.JsonPrimitive(id.toString()))
        put("turnRef", kotlinx.serialization.json.JsonPrimitive(turnRef))
        put("traceId", kotlinx.serialization.json.JsonPrimitive(traceId))
        put("requestedModel", kotlinx.serialization.json.JsonPrimitive(requestedModel))
        put("servedModel", kotlinx.serialization.json.JsonPrimitive(servedModel))
        put("servedProvider", kotlinx.serialization.json.JsonPrimitive(servedProvider))
        put("fallbackFrom", kotlinx.serialization.json.JsonPrimitive(fallbackFrom))
        put("cached", kotlinx.serialization.json.JsonPrimitive(cached))
        put("tokensPrompt", kotlinx.serialization.json.JsonPrimitive(tokensPrompt))
        put("tokensCompletion", kotlinx.serialization.json.JsonPrimitive(tokensCompletion))
        put("durationMs", kotlinx.serialization.json.JsonPrimitive(durationMs))
        put("ttfbMs", kotlinx.serialization.json.JsonPrimitive(ttfbMs))
        put("costUsd", kotlinx.serialization.json.JsonPrimitive(costUsd))
        put("status", kotlinx.serialization.json.JsonPrimitive(status))
        put("createdAt", kotlinx.serialization.json.JsonPrimitive(createdAt?.toString()))
        put("promptText", kotlinx.serialization.json.JsonPrimitive(promptText.takeIf { includeBodies }))
        put("responseText", kotlinx.serialization.json.JsonPrimitive(responseText.takeIf { includeBodies }))
        put("purpose", kotlinx.serialization.json.JsonPrimitive(purpose))
        put("agentId", kotlinx.serialization.json.JsonPrimitive(agentId))
        if (includeSubject) put("endUserSubject", kotlinx.serialization.json.JsonPrimitive(endUserSubject))
    }
