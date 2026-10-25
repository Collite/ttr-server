// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.bundle

import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttr.metadata.model.AutomationSchema
import org.tatrman.ttr.metadata.registry.RegistrySnapshot
import org.tatrman.ttrp.bundle.RecipeApprovalDependencies
import org.tatrman.ttrp.emit.recipe.RecipeActionDependencies
import org.tatrman.ttrp.resolve.ActionResolution
import org.tatrman.ttrp.resolve.AutomationScope
import org.tatrman.ttrp.resolve.ModelIndex
import org.tatrman.ttrp.resolve.TargetResolution

/** Reconstruct and verify approval coverage against the same immutable metadata used by this run. */
object ApprovalDependencies {
    data class Checked(
        val actions: Map<String, AutomationAction> = emptyMap(),
        val refusal: String? = null,
    )

    fun check(
        bundle: Bundle,
        recipe: ManifestRecipe,
        allowUncovered: Boolean = false,
    ): Checked {
        val evidence =
            recipe.approvalDependencies?.let {
                RecipeApprovalDependencies(it.version, it.imports, it.textBase, it.actions)
            }
        evidence?.let { RecipeActionDependencies.validate(it)?.let { bad -> return Checked(refusal = bad.reason) } }
        val scope =
            evidence?.let {
                val snapshot =
                    RegistrySnapshot(bundle.model, ModelGraph.build(bundle.model), bundle.loadedAt, emptyList())
                ModelIndex(snapshot, bundle.packageOf).automationScope(
                    it.imports.map { raw -> ModelIndex.importScope(raw.split('.'), raw) },
                )
            }
        scope?.missingImports?.firstOrNull()?.let {
            return Checked(refusal = "recorded import `$it.*` resolves to no loader package in the pinned model")
        }
        val groups =
            (bundle.model.schemas["automation"] as? AutomationSchema)?.actions.orEmpty().groupBy {
                it.qname.name
            }
        val actions = linkedMapOf<String, AutomationAction>()
        for (name in recipe.wiring.map { it.action }.distinct()) {
            val resolution = scope?.action(name) ?: AutomationScope.resolveAction(name, groups[name].orEmpty())
            // Keep legacy action lookup aligned with execution; the complete model still detects collisions.
            val action =
                when (resolution) {
                    is ActionResolution.Resolved ->
                        if (scope ==
                            null
                        ) {
                            bundle.actions[name] ?: resolution.action
                        } else {
                            resolution.action
                        }
                    is ActionResolution.Unresolved -> return Checked(
                        refusal = "unresolved wired action `$name` in the pinned approval scope",
                    )
                    is ActionResolution.Ambiguous -> return Checked(
                        refusal = "ambiguous wired action `$name` in the pinned approval scope",
                    )
                }
            actions[name] = action
        }
        if (evidence == null &&
            (allowUncovered || actions.values.none(AutomationScope::hasDefaults))
        ) {
            return Checked(actions)
        }
        val result =
            RecipeActionDependencies.verify(evidence, recipe.fingerprints.text, actions) { ref ->
                scope?.target(ref) ?: TargetResolution.Unresolved(ref)
            }
        val reason =
            when (result) {
                RecipeActionDependencies.Verification.Ok -> null
                is RecipeActionDependencies.Verification.Malformed -> result.reason
                is RecipeActionDependencies.Verification.Stale ->
                    "action approval dependencies are stale for `${result.action}`; rebuild the recipe bundle"
                is RecipeActionDependencies.Verification.Unresolved ->
                    "unresolved action approval dependency `${result.ref}` in the pinned recipe scope"
                is RecipeActionDependencies.Verification.Ambiguous ->
                    "ambiguous action approval dependency `${result.ref}` in the pinned recipe scope"
            }
        return Checked(actions, reason)
    }
}
