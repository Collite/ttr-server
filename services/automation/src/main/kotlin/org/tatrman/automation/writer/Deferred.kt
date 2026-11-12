// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.writer

import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.apply.BatchRowStatus
import org.tatrman.automation.apply.PlanPlacement
import org.tatrman.automation.apply.PlanSource
import org.tatrman.automation.bundle.Bundle
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.identity.RunAsRef
import org.tatrman.automation.identity.RunIdentity
import org.tatrman.automation.identity.ServiceTokens
import org.tatrman.automation.ledger.RunNames
import org.tatrman.automation.runner.RunContext
import org.tatrman.ttr.metadata.model.Entity
import java.time.Instant
import java.util.UUID

/** What an apply after the run's own close needs from its Open: where, as whom, and the pinned placement. */
data class ApplyContext(
    val world: String,
    val identity: RunIdentity,
    val modelFp: String,
    val source: PlanSource,
    val entities: List<Entity>,
    val routes: List<String>,
)

/** Resolves the [ApplyContext] of a held commit or a revert (S2.3). */
fun interface DeferredContexts {
    suspend fun resolve(
        run: UUID,
        names: RunNames,
        decider: Caller,
    ): ApplyContext
}

/**
 * D-186 (Amendment 2026-11-12, ⚑AU-P2-9): a held commit or a revert happens after the run's close has released its
 * executor. The context pinned at Open is used while it is still held; otherwise it is rebuilt from the served bundle
 * **only when that is the run's own bundle** (`runs.bundle`), so the placement, entities and model are those the run
 * planned with — else `409 close-not-ready`. The identity is the run's `run_as` `def identity` and its configured
 * credential; a run that ran as its caller (or its author's person) has no credential the Server holds and refuses
 * `409 identity-not-resumable`.
 */
class BundleDeferredContexts(
    private val served: () -> Bundle?,
    private val tokens: ServiceTokens,
    private val live: suspend (UUID) -> RunContext? = { null },
) : DeferredContexts {
    override suspend fun resolve(
        run: UUID,
        names: RunNames,
        decider: Caller,
    ): ApplyContext {
        live(run)?.let { return it.applyContext() }
        val bundle = served() ?: notReady("no bundle is served")
        if (names.bundle != bundle.id) {
            notReady(
                "the run's bundle ${names.bundle} is no longer served (${bundle.id}); its placement cannot be re-pinned",
            )
        }
        val deployment =
            names.deployment?.let { bundle.deployments[it] } ?: notReady("the run's deployment is no longer served")
        val world = bundle.worldOf(deployment) ?: notReady("the run's deployment has no world")
        val recipe = bundle.recipes[names.recipe] ?: notReady("the run's recipe is no longer served")
        val declared =
            world.identities.firstOrNull { it.principal == names.runAs }
                ?: throw AutomationException(
                    ErrorCode.IDENTITY_NOT_RESUMABLE,
                    "the run ran as ${names.runAs}, which is not a declared identity; an apply after close needs a " +
                        "service identity's credential",
                )
        tokens.credential(declared.credential)
            ?: notReady("the credential of identity ${declared.name} is not configured")
        return ApplyContext(
            world.name,
            RunIdentity(decider, RunAsRef.Named(declared.name), declared.principal, declared.credential),
            bundle.modelFp,
            PlanPlacement.source(bundle, world.name, recipe),
            bundle.entities,
            recipe.recipe.roles
                ?.routes
                .orEmpty()
                .map { it.name },
        )
    }

    private fun notReady(message: String): Nothing = throw AutomationException(ErrorCode.CLOSE_NOT_READY, message)
}

/** The [ApplyContext] of a context pinned at Open. */
fun RunContext.applyContext(): ApplyContext {
    val metadata =
        previewMetadata
            ?: throw AutomationException(ErrorCode.CLOSE_NOT_READY, "preview metadata was not pinned at open")
    return ApplyContext(
        world,
        identity,
        modelFp,
        metadata.planSource
            ?: throw AutomationException(ErrorCode.CLOSE_NOT_READY, "apply placement was not pinned at open"),
        metadata.entities,
        recipe.recipe.roles
            ?.routes
            .orEmpty()
            .map { it.name },
    )
}

/** A row's journaled outcome as [Apply] reads it; a pristine `staged` row has none. */
internal fun BatchRowStatus.recorded(): RowOutcome? =
    if (status == "staged" && error == null) {
        null
    } else {
        RowOutcome(seq, status, error, prior, history, historyAt?.let(Instant::parse))
    }
