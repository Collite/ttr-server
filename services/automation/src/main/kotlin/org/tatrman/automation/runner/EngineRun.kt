// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.engine.EngineHistory
import org.tatrman.automation.engine.NoEngine
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.identity.IdentityRefusal
import org.tatrman.automation.identity.isEngine

/**
 * The `engine` block of `POST /runs` (contracts "Amendment 2026-11-01", D-150/D-159): the process instance a run
 * belongs to, and — for an answer run — the user task and the person the engine says answered it. All of it is
 * data: [answeredBy] is only believed once the engine's own task history names the same assignee.
 */
@Serializable
data class EngineRef(
    val instanceId: String,
    val businessKey: String,
    val deploymentId: String,
    val taskId: String? = null,
    val answeredBy: String? = null,
)

/** What an open records of its engine block: the ref, and whether the asserted person was verified (null: none). */
data class EngineRun(
    val ref: EngineRef,
    val personVerified: Boolean?,
    /** The history row the verification read (`assignee`, `endTime`, …); null when nothing was asserted. */
    val evidence: JsonObject? = null,
) {
    /** protocol/v1 `people`: who answered — asserted vs verified (D-159). Null when the run asserts no person. */
    fun identityEntry(): JsonObject? =
        ref.answeredBy?.let { asserted ->
            buildJsonObject {
                put("event", "identity")
                put("asserted", asserted)
                put("verified", personVerified == true)
                put("source", "engine:history/task")
                put("taskId", ref.taskId)
                put("instanceId", ref.instanceId)
                evidence?.let { put("history", it) }
            }
        }

    fun protocol(): JsonObject =
        buildJsonObject {
            put("instanceId", ref.instanceId)
            put("businessKey", ref.businessKey)
            put("deploymentId", ref.deploymentId)
            ref.taskId?.let { put("taskId", it) }
            ref.answeredBy?.let { put("answeredBy", it) }
            personVerified?.let { put("personVerified", it) }
        }
}

/**
 * S3.2 T3 (D-159): the answer-run check, done before Open (outside its claim lock, so a slow engine never holds other
 * opens) and therefore before the D-99 fingerprint check. An answer ([EngineRef.answeredBy]) needs its task:
 * `GET /history/task?taskId=` as the caller must show exactly that task, in [EngineRef.instanceId], assigned to the
 * asserted person — else `403 identity · unverified-answer` and nothing is claimed. The engine unreachable is
 * `503 engine-unreachable`, never a pass.
 * ✅R280-B: an answer is accepted from the engine client only ([engineClient], a SERVICE token): anyone else who can
 * read the task history could otherwise name any assignee — refused before the history call. An engine block that
 * asserts nobody stays open to any caller with the grant.
 */
class EngineRuns(
    private val history: EngineHistory = NoEngine,
    private val engineClient: String = "process-engine",
) {
    suspend fun verify(
        ref: EngineRef?,
        caller: Caller,
    ): EngineRun? {
        ref ?: return null
        listOf(ref.instanceId, ref.businessKey, ref.deploymentId).forEach {
            if (it.isBlank()) {
                throw AutomationException(
                    ErrorCode.ENGINE,
                    "engine.instanceId, businessKey and deploymentId are required",
                )
            }
        }
        if (ref.taskId?.isBlank() == true || ref.answeredBy?.isBlank() == true) {
            throw AutomationException(ErrorCode.ENGINE, "engine.taskId and engine.answeredBy cannot be blank")
        }
        val asserted = ref.answeredBy ?: return EngineRun(ref, personVerified = null)
        if (!caller.isEngine(engineClient)) unverified("an answer run is opened by the process engine only", ref)
        val taskId = ref.taskId ?: unverified("an answer names the engine task it answers (engine.taskId)", ref)
        val task =
            history.task(taskId, caller.token).singleOrNull { it.id == taskId }
                ?: unverified("the engine has no history of task $taskId", ref)
        if (task.processInstanceId !=
            ref.instanceId
        ) {
            unverified("task $taskId belongs to another process instance", ref)
        }
        if (task.assignee != asserted) unverified("task $taskId was not assigned to $asserted", ref)
        return EngineRun(
            ref,
            personVerified = true,
            evidence =
                buildJsonObject {
                    put("assignee", task.assignee)
                    put("endTime", task.endTime?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
                    task.deleteReason?.let { put("deleteReason", it) }
                },
        )
    }

    companion object {
        fun unverified(
            message: String,
            ref: EngineRef,
        ): Nothing =
            throw AutomationException(
                ErrorCode.IDENTITY,
                message,
                buildJsonObject {
                    put("reason", IdentityRefusal.UNVERIFIED_ANSWER.wire)
                    ref.taskId?.let { put("taskId", it) }
                    put("asserted", ref.answeredBy)
                },
            )
    }
}
