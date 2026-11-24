// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.writer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.engine.EngineHistory
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.identity.isEngine
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** D-267: the task occurrence identifies the timer occurrence within the task's own enclosing scope. */
@Serializable
data class ApprovalExpiryRequest(
    val originalRunId: String,
    val action: String,
    val key: JsonObject,
    val instanceId: String,
    val taskId: String,
    val timerActivityId: String,
)

internal data class VerifiedExpiry(
    val request: ApprovalExpiryRequest,
    val original: UUID,
    val order: JsonPrimitive,
    val businessKey: String,
    val active: Boolean,
    val taskStartedAt: Instant,
    val timerOccurrence: String,
    val endedAt: Instant,
) {
    fun evidence(): JsonObject =
        buildJsonObject {
            put("originalRunId", original.toString())
            put("action", request.action)
            put("key", request.key)
            put("instanceId", request.instanceId)
            put("taskId", request.taskId)
            put("timerActivityId", request.timerActivityId)
            put("timerOccurrenceId", timerOccurrence)
            put("timerEndedAt", endedAt.toString())
            put("source", "engine:history")
        }
}

class ApprovalExpiry(
    private val history: EngineHistory,
    private val engineClient: String,
) {
    internal suspend fun verify(
        caller: Caller,
        action: String,
        request: TypedPerformRequest,
        expiry: ApprovalExpiryRequest,
    ): VerifiedExpiry {
        if (!caller.isEngine(engineClient)) unverifiedExpiry("approval expiry requires the process engine client")
        val original = runCatching { UUID.fromString(expiry.originalRunId) }.getOrNull()
        val order = request.key["order"] as? JsonPrimitive
        if (
            original == null ||
            order == null ||
            request.key.size != 1 ||
            request.params != buildJsonObject { put("department", "Sales") } ||
            expiry.key != buildJsonObject { put("order_number", order) } ||
            request.by != null ||
            (request.origin != null && request.origin != "engine") ||
            action != "hold" ||
            expiry.action != "release_confirmed" ||
            expiry.timerActivityId != "s41_release_confirmed_lapsed" ||
            expiry.instanceId.isBlank() ||
            expiry.taskId.isBlank()
        ) {
            unverifiedExpiry("expiry must bind the release confirmation and Sales hold for the same order")
        }

        val proof =
            history.expiry(expiry.instanceId, expiry.taskId, expiry.timerActivityId, caller.token)
                ?: unverifiedExpiry("the engine has no matching task history")
        val task = proof.task
        val process = proof.process
        val businessKey = process?.businessKey
        val taskStart = engineTime(task.startTime)
        val due = engineTime(task.due)
        val taskEnd = engineTime(task.endTime)
        val definition = task.processDefinitionId
        val taskActivity =
            proof.taskActivities.singleOrNull {
                it.taskId == expiry.taskId &&
                    it.activityId == task.taskDefinitionKey &&
                    it.executionId == task.executionId
            } ?: unverifiedExpiry("the engine has no unique activity for the approval task")
        val parent = taskActivity.parentId
        val timer =
            proof.timerActivities.singleOrNull { it.parentId == parent }
                ?: unverifiedExpiry("the engine has no unique timer occurrence in the approval's scope")
        val timerStart = engineTime(timer.start)
        val timerEnd = engineTime(timer.end)
        if (
            task.id != expiry.taskId ||
            task.processInstanceId != expiry.instanceId ||
            definition.isNullOrBlank() ||
            process?.id != expiry.instanceId ||
            process.definitionId != definition ||
            businessKey.isNullOrBlank() ||
            task.deleteReason != "deleted" ||
            due == null ||
            taskStart == null ||
            taskEnd == null ||
            taskEnd < maxOf(due, taskStart) ||
            parent.isNullOrBlank() ||
            taskActivity.type != "userTask" ||
            taskActivity.canceled != true ||
            taskActivity.instanceId != expiry.instanceId ||
            taskActivity.definitionId != definition ||
            engineTime(taskActivity.end) == null ||
            timer.activityId != expiry.timerActivityId ||
            timer.type != "boundaryTimer" ||
            timer.canceled != false ||
            timer.instanceId != expiry.instanceId ||
            timer.definitionId != definition ||
            timer.id.isBlank() ||
            timerStart == null ||
            timerEnd == null ||
            timerStart < taskEnd ||
            timerEnd < timerStart
        ) {
            unverifiedExpiry("the engine history does not show this approval canceled by its due timer")
        }

        fun taskVariable(name: String): String? =
            proof.taskVariables
                .singleOrNull {
                    it.name == name &&
                        it.taskId == expiry.taskId &&
                        it.instanceId == expiry.instanceId &&
                        it.activityInstanceId == taskActivity.id
                }?.value

        val run =
            proof.runVariables
                .singleOrNull {
                    it.name == "ttrRunId" && it.instanceId == expiry.instanceId && it.taskId == null
                }?.value
        if (run != original.toString() ||
            taskVariable("ttr_action") != expiry.action ||
            taskVariable("ttr_about") != order.content
        ) {
            unverifiedExpiry("the task history does not bind the original run, action and order")
        }
        return VerifiedExpiry(
            expiry,
            original,
            order,
            businessKey,
            process.state == "ACTIVE",
            taskStart,
            timer.id,
            timerEnd,
        )
    }

    private fun engineTime(value: String?): Instant? =
        value?.let { raw ->
            runCatching { Instant.parse(raw) }.getOrElse {
                runCatching { OffsetDateTime.parse(raw, ENGINE_TIME).toInstant() }.getOrNull()
            }
        }

    private companion object {
        val ENGINE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXX")
    }
}

internal fun unverifiedExpiry(message: String): Nothing =
    throw AutomationException(
        ErrorCode.IDENTITY,
        message,
        buildJsonObject { put("reason", "unverified-expiry") },
    )
