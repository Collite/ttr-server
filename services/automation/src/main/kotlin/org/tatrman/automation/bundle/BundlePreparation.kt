// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.bundle

import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ModelObject
import org.tatrman.ttr.metadata.registry.RegistrySnapshot
import org.tatrman.ttrp.resolve.ModelIndex
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Immutable model preparation shared by coverage, placement and stdlib schema checks. */
class BundlePreparation internal constructor(
    private val model: Model,
    private val packages: Map<String, String>,
    private val loadedAt: Instant,
    /** Instance-scoped placement boundary; production always uses the public compiler adapter. */
    internal val placeObligation: (Bundle, String) -> String =
        org.tatrman.automation.apply.PreviewObligationStorage::place,
) {
    internal fun matches(
        model: Model,
        packages: Map<String, String>,
        loadedAt: Instant,
    ): Boolean = this.model === model && this.packages === packages && this.loadedAt == loadedAt

    internal val snapshot by lazy { RegistrySnapshot(model, ModelGraph.build(model), loadedAt, emptyList()) }
    internal val packageOf: (ModelObject) -> String = { obj -> packages[obj.sourceFile].orEmpty() }
    internal val index by lazy { ModelIndex(snapshot, packageOf) }
    internal val calendarPlans = ConcurrentHashMap<String, Result<org.tatrman.automation.apply.CalendarPlan>>()
    internal val obligationPlacements = ConcurrentHashMap<String, Result<String>>()
    internal val warnedObligationPlacements: MutableSet<String> = ConcurrentHashMap.newKeySet()
}
