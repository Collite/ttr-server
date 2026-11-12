// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.writer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.Bundle
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.ledger.HeldDecision
import org.tatrman.automation.ledger.HeldGroup
import org.tatrman.automation.ledger.Ledger
import org.tatrman.automation.ledger.NoHeldGroup
import org.tatrman.automation.ledger.NoRunBatch
import org.tatrman.automation.ledger.NoSuchRun
import org.tatrman.automation.ledger.RunStatus
import java.time.Clock
import java.util.UUID

/**
 * S2.3 — the held groups of a waiting run (Amendment 2026-11-03 `hold`; D-60, D-97, D-99, D-100). The internal API
 * `commitHeld(batch, group, by)` / `rejectHeld(batch, group, by)` behind P2's dev routes; the production entry is the
 * answer run (P3·S3.2, D-159).
 *
 * A commit first checks D-99: the run's pinned fingerprints (text, values, program, deployment) must still be the served
 * ones, else `409 superseded` and nothing is applied (the group stays held: a reject still decides it). Then the
 * group's rows go through [Apply] — the same per-(storage, entity) door requests, compare-and-set and history as the
 * close — under the run's `run_as` ([DeferredContexts]); a row an earlier attempt recorded is never sent again. Each
 * decision is bounded by its own budget (now + the run's batch budget), not the run's original deadline, which has long
 * passed by the time a person answers (⚑ Found while doing, 2026-11-12). A reject applies nothing: the group's rows
 * become `skipped` (`rejected`). Decisions serialize per service; a decided group answers its recorded decision again.
 */
class Held(
    private val ledger: Ledger,
    private val apply: Apply,
    private val contexts: DeferredContexts,
    private val served: () -> Bundle?,
    private val clock: Clock,
) {
    private val lock = Mutex()

    companion object {
        /** The role an operator's held decision and revert require (Amendment 2026-11-03: `role.ops`). */
        const val OPS = "ops"
    }

    suspend fun commitHeld(
        run: UUID,
        group: JsonObject,
        by: Caller,
    ): HeldDecision =
        decide(run, group, by) { held ->
            superseded(held)?.let { throw it }
            val context = contexts.resolve(run, held.names, by)
            apply.apply(
                ApplyInput(
                    run,
                    context.world,
                    context.identity,
                    context.modelFp,
                    clock.instant().plus(held.budget),
                    held.rows,
                    held.actions,
                    context.source,
                    context.entities,
                    context.routes,
                    held.statuses.mapNotNull { it.recorded() }.associateBy { it.seq },
                ),
                journal(held.batch),
            )
            io { ledger.decideHeld(run, group, "commit", by.principal) }
        }

    suspend fun rejectHeld(
        run: UUID,
        group: JsonObject,
        by: Caller,
    ): HeldDecision = decide(run, group, by) { io { ledger.decideHeld(run, group, "reject", by.principal) } }

    private suspend fun decide(
        run: UUID,
        group: JsonObject,
        by: Caller,
        block: suspend (HeldGroup) -> HeldDecision,
    ): HeldDecision =
        withContext(NonCancellable) {
            lock.withLock {
                val held = io { ledger.heldGroup(run, group) }
                requireOps(by, held.names.deployment)
                if (held.statuses.any { it.decided != null }) {
                    // Already decided: its recorded answer, nothing applied or appended again.
                    return@withLock io { ledger.decideHeld(run, group, "commit", "-") }
                }
                if (held.status != RunStatus.WAITING || held.batchState != "held") {
                    throw AutomationException(
                        ErrorCode.CLOSE_NOT_READY,
                        "the run is not waiting on held groups (status ${held.status.wire}, batch ${held.batchState})",
                    )
                }
                block(held)
            }
        }

    /**
     * `role.ops` (D-138): membership in the run's world's `def role ops` — its Keycloak groups, as a realm role or a
     * group of the bearer — or `403 identity`. A world that declares no `ops` role lets nobody decide.
     */
    fun requireOps(
        caller: Caller,
        deployment: String?,
    ) {
        val bundle = served()
        val world = deployment?.let { bundle?.deployments?.get(it) }?.let { bundle?.worldOf(it) }
        val ops = world?.roles?.firstOrNull { it.name == OPS }
        if (ops == null || ops.keycloakGroups.none { it in caller.roles || it in caller.groups }) {
            throw AutomationException(
                ErrorCode.IDENTITY,
                "${caller.principal} may not decide held groups or revert this run: it needs role.$OPS",
                buildJsonObject { put("reason", "no-grant") },
            )
        }
    }

    /** D-99: the fingerprints the run was approved and opened under must still be the served ones. */
    private fun superseded(held: HeldGroup): AutomationException? {
        val bundle = served()
        val recipe =
            bundle
                ?.recipes
                ?.get(held.names.recipe)
                ?.recipe
                ?.fingerprints
        val current =
            mapOf(
                "text" to recipe?.text,
                "values" to recipe?.values,
                "program" to held.names.program?.let { bundle?.programFps?.get(it) },
                "deployment" to held.names.deployment?.let { bundle?.deploymentFps?.get(it) },
            )
        val pinned =
            mapOf(
                "text" to held.fingerprints.text,
                "values" to held.fingerprints.values,
                "program" to held.fingerprints.program,
                "deployment" to held.fingerprints.deployment,
            )
        val differs = current.keys.filter { current[it] != pinned[it] }
        if (differs.isEmpty()) return null
        return AutomationException(
            ErrorCode.SUPERSEDED,
            "the run's ${differs.joinToString()} fingerprint moved since it opened; the held group is not applied (D-99)",
            buildJsonObject {
                put("differs", JsonArray(differs.map(::JsonPrimitive)))
                put("approved", JsonObject(differs.associateWith { pinned[it]?.let(::JsonPrimitive) ?: JsonNull }))
                put("current", JsonObject(differs.associateWith { current[it]?.let(::JsonPrimitive) ?: JsonNull }))
            },
        )
    }

    private fun journal(batch: UUID) =
        object : ApplyJournal {
            override fun begin(seqs: List<Int>) = ledger.beginApply(batch, seqs)

            override fun release(seqs: List<Int>) = ledger.releaseApply(batch, seqs)

            override fun record(outcomes: List<RowOutcome>) = ledger.recordApply(batch, outcomes)
        }

    private suspend fun <T> io(block: () -> T): T =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (_: NoSuchRun) {
                throw AutomationException(ErrorCode.IDENTITY, "the run is not available to this caller")
            } catch (_: NoRunBatch) {
                throw AutomationException(ErrorCode.CLOSE_NOT_READY, "the run has no batch")
            } catch (e: NoHeldGroup) {
                throw AutomationException(ErrorCode.BAD_REQUEST, "the run holds no group ${e.group}")
            }
        }
}
