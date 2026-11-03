// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

import org.tatrman.automation.bundle.Bundle
import org.tatrman.automation.bundle.LoadedRecipe
import org.tatrman.ttr.metadata.model.WorldSchema
import org.tatrman.ttr.metadata.world.WorldResolution
import org.tatrman.ttr.metadata.world.WorldResolver
import org.tatrman.ttr.metadata.world.writable

/** Transaction grouping over already resolved action targets; no action expressions are resolved again. */
internal object PlanPlacement {
    fun source(
        bundle: Bundle,
        worldName: String,
        recipe: LoadedRecipe,
    ): PlanSource {
        val prepared = bundle.prepared
        val declaration =
            (bundle.model.schemas.getValue("world") as WorldSchema).worlds.values.single {
                it.qname.name ==
                    worldName
            }
        val world =
            (
                WorldResolver(
                    prepared.snapshot,
                    prepared.packageOf,
                ).resolve(declaration.qname) as WorldResolution.Ok
            ).world
        val targets =
            bundle.entities.associate { entity ->
                entity.qname.dotted() to
                    entity.attributes.map {
                        org.tatrman.ttrp.resolve.TargetResolution
                            .Resolved(entity, it)
                    }
            }
        val storages =
            targets
                .mapNotNull { (entity, resolved) ->
                    val refs =
                        resolved.flatMap {
                            listOf(
                                it.attribute.qname.dotted(),
                                "${it.entity.qname.name}.${it.attribute.qname.name.substringAfterLast('.')}",
                            )
                        } +
                            entity
                    val placements =
                        world.storages.filter { storage ->
                            storage.writable.any { pattern ->
                                refs.any { ref ->
                                    val regex = pattern.split('*').joinToString(".*") { Regex.escape(it) }.toRegex()
                                    regex.matches(ref) ||
                                        regex.matches(ref.removePrefix("er.entity.")) ||
                                        regex.matches(ref.substringAfterLast('.'))
                                }
                            }
                        }
                    placements.singleOrNull()?.let { entity to it.qname.name }
                }.toMap()
        val schema = recipe.recipe.verdictSchema
        val ledger =
            if (schema != null) {
                val name = schema.entity.removePrefix("er.entity.")
                val source =
                    org.tatrman.ttrp.resolve.LoadSourceResolver
                        .unbound("er.entity.$name", name, emptyList())
                org.tatrman.ttrp.resolve.RecipePlacement
                    .storages(source, world, prepared.index)
                    .singleOrNull()
                    ?: throw org.tatrman.automation.api.AutomationException(
                        org.tatrman.automation.api.ErrorCode.PREVIEW_UNSUPPORTED,
                        "verdict storage is missing or ambiguous in the pinned world",
                    )
            } else if (recipe.recipe.wiring.isNotEmpty()) {
                PreviewObligationStorage.resolve(bundle, worldName)
            } else {
                ""
            }
        return PlanSource(schema, storages, ledger)
    }
}
