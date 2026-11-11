// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.Bundle
import org.tatrman.automation.bundle.LoadedRecipe
import org.tatrman.ttr.metadata.model.WorldSchema
import org.tatrman.ttr.metadata.world.WorldResolution
import org.tatrman.ttr.metadata.world.WorldResolver
import org.tatrman.ttr.metadata.world.writable
import org.tatrman.ttrp.resolve.LoadSourceResolver
import org.tatrman.ttrp.resolve.RecipePlacement

/** Compiler placement over pinned writer targets, cached (including refusals) before Open claims a run. */
internal object PlanPlacement {
    fun source(
        bundle: Bundle,
        worldName: String,
        recipe: LoadedRecipe,
    ): PlanSource =
        bundle.prepared.planSources
            .computeIfAbsent(worldName to recipe.recipe.name) {
                try {
                    Result.success(bundle.prepared.placePlan(bundle, worldName, recipe))
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }.getOrThrow()

    internal fun place(
        bundle: Bundle,
        worldName: String,
        recipe: LoadedRecipe,
    ): PlanSource {
        val prepared = bundle.prepared
        val index = prepared.index
        val declaration =
            (bundle.model.schemas["world"] as? WorldSchema)
                ?.worlds
                ?.values
                ?.singleOrNull { it.qname.name == worldName } ?: unsupported("world")
        val world =
            (WorldResolver(prepared.snapshot, prepared.packageOf).resolve(declaration.qname) as? WorldResolution.Ok)
                ?.world ?: unsupported("world")

        fun storage(source: org.tatrman.ttrp.resolve.LoadSource): String {
            val placements = RecipePlacement.storages(source, world, index)
            RecipePlacement.failure(source, world, placements)?.let { unsupported(it) }
            return placements.singleOrNull() ?: unsupported(source.objectQname)
        }
        val schema = recipe.recipe.verdictSchema
        val catalogEntities =
            schema
                ?.let { listOfNotNull(it.entity, it.detail, it.current) }
                .orEmpty()
                .map { it.removePrefix("er.entity.") }
                .toSet()
        val targets =
            recipe.coverage.actions
                .filterValues { it.sinks[Bundle.NATIVE] in setOf("writer", "transition") }
                .flatMap { (name, action) ->
                    recipe.coverage.targets[name]
                        ?.values
                        ?.map { it.entity }
                        ?: PreviewWriter.targetEntities(action, bundle.entities)
                }.distinctBy { it.qname }
                .filter { it.qname.name !in catalogEntities && it.qname.name != "obligation" }
        val storages =
            targets.associate { entity ->
                val source =
                    LoadSourceResolver.resolve(entity, index, allowUnbound = true) ?: unsupported(entity.qname.dotted())
                entity.qname.dotted() to storage(source)
            }
        val ledger =
            schema
                ?.let {
                    val name = it.entity.removePrefix("er.entity.")
                    storage(LoadSourceResolver.unbound("er.entity.$name", name, emptyList()))
                }.orEmpty()
        val obligation =
            if (recipe.coverage.actions.values
                    .any { it.sinks[Bundle.NATIVE] == "obligation" }
            ) {
                PreviewObligationStorage.resolve(bundle, worldName)
            } else {
                null
            }
        // S2.2: a transition's `state_history` row has its own unbound placement, pinned here with the others.
        // Absent (no storage declares it writable) is not a refusal: the apply records `history-failed` instead.
        val history =
            if (recipe.coverage.actions.values
                    .any { it.transition != null }
            ) {
                runCatching {
                    storage(LoadSourceResolver.unbound("er.entity.state_history", "state_history", emptyList()))
                }.getOrNull()
            } else {
                null
            }
        // ⚑AU-P2-4: the writer's own `not-writable` check reads the world's `writable:` per storage (the door cannot).
        val writable = world.storages.associate { it.qname.name to it.writable }
        return PlanSource(schema, storages, ledger, obligation, history, writable)
    }

    private fun unsupported(detail: String): Nothing =
        throw AutomationException(
            ErrorCode.PREVIEW_UNSUPPORTED,
            "apply storage is missing or ambiguous in the pinned world: $detail",
        )
}
