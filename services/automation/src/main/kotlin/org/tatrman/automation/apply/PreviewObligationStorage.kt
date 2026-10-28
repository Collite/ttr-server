// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.Bundle
import org.tatrman.ttr.metadata.model.WorldSchema
import org.tatrman.ttr.metadata.world.WorldResolution
import org.tatrman.ttr.metadata.world.WorldResolver
import org.tatrman.ttrp.resolve.LoadSourceResolver
import org.tatrman.ttrp.resolve.RecipePlacement

/** The compiler's world-overlay and placement rules, pinned at Open; never guess a storage/engine name. */
internal object PreviewObligationStorage {
    fun resolve(
        bundle: Bundle,
        worldName: String,
    ): String =
        bundle.prepared.obligationPlacements
            .computeIfAbsent(worldName) {
                try {
                    Result.success(place(bundle, worldName))
                } catch (e: AutomationException) {
                    Result.failure(e)
                }
            }.getOrThrow()

    private fun place(
        bundle: Bundle,
        worldName: String,
    ): String {
        val prepared = bundle.prepared
        val index = prepared.index
        val schema = bundle.model.schemas["world"] as? WorldSchema ?: unsupported()
        val declaration = schema.worlds.values.singleOrNull { it.qname.name == worldName } ?: unsupported()
        val entity = bundle.entities.singleOrNull { it.qname.dotted() == "er.entity.obligation" } ?: unsupported()
        val source = LoadSourceResolver.resolve(entity, index)?.takeIf { it.unbound } ?: unsupported()
        val world =
            (
                WorldResolver(
                    prepared.snapshot,
                    prepared.packageOf,
                ).resolve(declaration.qname) as? WorldResolution.Ok
            )?.world
                ?: unsupported()
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
