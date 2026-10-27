// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.Bundle
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.WorldSchema
import org.tatrman.ttr.metadata.registry.RegistrySnapshot
import org.tatrman.ttr.metadata.world.WorldResolution
import org.tatrman.ttr.metadata.world.WorldResolver
import org.tatrman.ttrp.resolve.LoadSourceResolver
import org.tatrman.ttrp.resolve.ModelIndex
import org.tatrman.ttrp.resolve.RecipePlacement

/** The compiler's world-overlay and placement rules, pinned at Open; never guess a storage/engine name. */
internal object PreviewObligationStorage {
    fun resolve(
        bundle: Bundle,
        worldName: String,
    ): String {
        val snapshot = RegistrySnapshot(bundle.model, ModelGraph.build(bundle.model), bundle.loadedAt, emptyList())
        val index = ModelIndex(snapshot) { obj -> bundle.packages[obj.sourceFile].orEmpty() }
        val schema = bundle.model.schemas["world"] as? WorldSchema ?: unsupported()
        val declaration = schema.worlds.values.singleOrNull { it.qname.name == worldName } ?: unsupported()
        val entity = bundle.entities.singleOrNull { it.qname.dotted() == "er.entity.obligation" } ?: unsupported()
        val source = LoadSourceResolver.resolve(entity, index)?.takeIf { it.unbound } ?: unsupported()
        // This lookup places only an unbound stdlib entity: hosts do not participate in its placement.
        // Project them away in this private resolver input. The public overlay/placement rules remain
        // authoritative without invoking WorldResolver's legacy checkout-path-based hosts validation.
        // The pinned model and the world's actual storage declarations are never changed.
        val placementModel =
            bundle.model.copy(
                schemas =
                    bundle.model.schemas + (
                        "world" to
                            schema.copy(
                                worlds =
                                    schema.worlds.mapValues { (_, world) ->
                                        world.copy(storages = world.storages.map { it.copy(hosts = emptyList()) })
                                    },
                            )
                    ),
            )
        val placementSnapshot =
            RegistrySnapshot(placementModel, ModelGraph.build(placementModel), bundle.loadedAt, emptyList())
        val world =
            (WorldResolver(placementSnapshot).resolve(declaration.qname) as? WorldResolution.Ok)?.world ?: unsupported()
        val placements = RecipePlacement.storages(source, world, index)
        if (RecipePlacement.failure(source, world, placements) != null) unsupported()
        return placements.single()
    }

    private fun unsupported(): Nothing =
        throw AutomationException(
            ErrorCode.PREVIEW_UNSUPPORTED,
            "obligation storage is missing or ambiguous in the pinned world",
        )
}
