// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.ledger.Claim
import org.tatrman.automation.ledger.EngineColumns
import org.tatrman.automation.ledger.Ledger
import org.tatrman.automation.ledger.NoHeldGroup
import org.tatrman.automation.ledger.RUN_KIND_ANSWER
import org.tatrman.automation.ledger.RunOpen
import org.tatrman.automation.writer.Held
import java.util.UUID

/** S3.5: what [Open.answerTarget] accepted — the verified engine block and the answered key object. */
data class AnswerTarget(
    val ref: EngineRef,
    val key: JsonObject,
)

/**
 * S3.5 (contract 3): an answer run's decision — on its Open response, `GET /runs/{id}` and its protocol's `writes`
 * entry (with `answer: true`). [batch] is the original run's batch (the answer run has none, Found 3); [replayed]: the key
 * was decided before (by another answer or the dev route), this is that recorded decision and nothing applied again.
 */
@Serializable
data class AnswerOutcome(
    val original: String,
    val batch: String,
    val key: JsonObject,
    val decision: String,
    val by: String,
    val applied: Int,
    val skipped: Int,
    val failed: Int = 0,
    val unknown: Int,
    val replayed: Boolean = false,
)

/**
 * S3.5 (D-230, ⚑AU-S35-1; Amendment 2026-11-03 "the production entry is the answer run"; D-159 completed): an Open
 * carrying a verified `engine.answeredBy` is a **decision, not a run of the recipe**. It is checked like an Open
 * ([Open.answerTarget]), finds the **original run** — the newest `waiting` run of the recipe the engine opened in
 * `engine.instanceId` without a task — claims its own run (kind `answer`, the six engine columns, the `people` entry),
 * commits the answered `port.key`'s held group of the original run ([Held.commitKey]: D-99 first, `role.ops` not asked
 * of the engine, the person recorded) and closes, all at Open (Found 2: claimed `open`, closed in the decision's
 * completion transaction). It evaluates nothing and no executor is created. A refusal after the claim leaves the answer
 * run `failed` with its error (the audit trail; a failed run frees its idempotency key, so the engine's retry decides).
 *
 * The idempotency key is the caller's `idempotencyKey`, else the engine task the answer closes (one task, one answer),
 * namespaced by the caller and deployment: a retry of the same answer is `200` with the answer run's recorded outcome.
 */
class AnswerRuns(
    private val policy: Open,
    private val ledger: Ledger,
    private val held: Held,
) {
    companion object {
        /** The v1 decision (⚑AU-S35-2): an answer approves. */
        const val APPROVE = "approve"

        private val json = Json { ignoreUnknownKeys = true }
    }

    suspend fun open(
        recipe: String,
        request: OpenRequest,
        caller: Caller,
        verified: EngineRun,
    ): OpenResult {
        val target = io { policy.answerTarget(recipe, request, verified) }
        val ref = target.ref
        // Contract 1: read before the claim, so a refused answer names it; a same-key retry still replays (the claim).
        val original = io { ledger.originalRun(ref.instanceId, recipe, request.deployment) }
        val pinned = original?.let { io { ledger.findRun(it) } }?.open
        val claim =
            io {
                ledger.claimRun(
                    RunOpen(
                        recipe = recipe,
                        program = pinned?.program,
                        deployment = request.deployment,
                        bundle = pinned?.bundle,
                        fpText = pinned?.fpText,
                        fpValues = pinned?.fpValues,
                        fpProgram = pinned?.fpProgram,
                        fpDeployment = pinned?.fpDeployment,
                        modelFp = pinned?.modelFp,
                        port = request.port,
                        runAs = pinned?.runAs,
                        caller = caller.principal,
                        callerSubject = caller.sub,
                        origin = request.origin,
                        dryRun = false,
                        idempotencyKey = idempotency(request, caller, target),
                        budgetSync = pinned?.budgetSync,
                        budgetBatch = pinned?.budgetBatch,
                        kind = RUN_KIND_ANSWER,
                        engine =
                            EngineColumns(
                                ref.instanceId,
                                ref.businessKey,
                                ref.deploymentId,
                                ref.taskId,
                                ref.answeredBy,
                                verified.personVerified,
                            ),
                        identityProtocol = verified.identityEntry(),
                        openingProtocol =
                            buildJsonObject {
                                put("event", "opened")
                                put("kind", RUN_KIND_ANSWER)
                                put("recipe", recipe)
                                put("deployment", request.deployment)
                                put("port", request.port)
                                put("engine", verified.protocol())
                                put("original", original?.toString())
                                put("origin", request.origin)
                                put("caller", caller.principal)
                            },
                    ),
                )
            }
        val id = claim.runId
        val location = "/automation/runs/$id"
        return when (claim) {
            is Claim.Replay ->
                OpenResult(
                    200,
                    OpenResponse(
                        id.toString(),
                        true,
                        status = "closed",
                        location = location,
                        protocolRef = "$location/protocol",
                        dryRun = false,
                        answer = io { ledger.answerOf(id) }?.let { json.decodeFromJsonElement<AnswerOutcome>(it) },
                    ),
                )
            is Claim.Running ->
                OpenResult(
                    202,
                    OpenResponse(id.toString(), false, status = claim.status.wire, location = location, dryRun = false),
                )
            is Claim.Claimed -> decide(id, original, target, caller)
        }
    }

    private suspend fun decide(
        id: UUID,
        original: UUID?,
        target: AnswerTarget,
        caller: Caller,
    ): OpenResult =
        withContext(NonCancellable) {
            val by = requireNotNull(target.ref.answeredBy)
            val outcome =
                try {
                    original ?: throw unmatched(
                        "engine instance ${target.ref.instanceId} has no waiting run of this recipe to answer",
                        buildJsonObject {
                            put("reason", "no-waiting-run")
                            put("instanceId", target.ref.instanceId)
                        },
                    )
                    val decision =
                        try {
                            held.commitKey(original, target.key, caller, by, id)
                        } catch (_: NoHeldGroup) {
                            throw unmatched(
                                "run $original holds no rows of key ${target.key}",
                                buildJsonObject {
                                    put("reason", "no-held-key")
                                    put("run", original.toString())
                                    put("key", target.key)
                                },
                            )
                        }
                    AnswerOutcome(
                        original.toString(),
                        decision.batch,
                        target.key,
                        decision.decision,
                        // This answer's verified person; a replayed decision's own decider stays in the rows' `decided`.
                        by,
                        decision.applied,
                        decision.discarded,
                        decision.failed,
                        decision.unknown,
                        decision.replayed,
                    )
                } catch (e: Exception) {
                    withContext(Dispatchers.IO) { ledger.failExecution(id, e) }
                    throw e
                }
            val recorded =
                io {
                    ledger.finishAnswer(
                        id,
                        JsonObject(
                            mapOf("answer" to JsonPrimitive(true)) +
                                Json.encodeToJsonElement(outcome) as JsonObject,
                        ),
                    )
                }
            val answer = json.decodeFromJsonElement<AnswerOutcome>(recorded)
            val location = "/automation/runs/$id"
            OpenResult(
                // Contract 2: a key decided before answers `200` with its recorded decision (`answer.replayed`).
                if (answer.replayed) 200 else 201,
                OpenResponse(
                    id.toString(),
                    false,
                    status = "closed",
                    location = location,
                    protocolRef = "$location/protocol",
                    dryRun = false,
                    answer = answer,
                ),
            )
        }

    private fun unmatched(
        message: String,
        detail: JsonObject,
    ) = AutomationException(ErrorCode.ANSWER_UNMATCHED, message, detail)

    /** The caller's key, else the engine task the answer closes (and the key it answers), namespaced like [Open]'s. */
    private fun idempotency(
        request: OpenRequest,
        caller: Caller,
        target: AnswerTarget,
    ): String =
        org.tatrman.automation.ledger.idempotencyKey(
            "open",
            JsonArray(
                listOf(
                    JsonPrimitive(caller.sub),
                    JsonPrimitive(request.deployment),
                    JsonPrimitive("answer-v1"),
                    request.idempotencyKey?.let(::JsonPrimitive) ?: buildJsonObject {
                        put("instanceId", target.ref.instanceId)
                        put("taskId", target.ref.taskId)
                        put("key", target.key)
                    },
                ),
            ),
        )

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
