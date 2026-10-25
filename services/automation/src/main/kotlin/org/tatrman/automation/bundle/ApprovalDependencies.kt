// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.bundle

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
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

@Serializable
enum class CoverageKind(
    val wire: String,
) {
    @SerialName("missing")
    MISSING("missing"),

    @SerialName("malformed")
    MALFORMED("malformed"),

    @SerialName("unsupported")
    UNSUPPORTED("unsupported"),

    @SerialName("unresolved")
    UNRESOLVED("unresolved"),

    @SerialName("ambiguous")
    AMBIGUOUS("ambiguous"),

    @SerialName("stale")
    STALE("stale"),
}

/** Resolve once at load; approval and execution consume these same pinned objects. */
object ApprovalDependencies {
    private val evidenceJson = Json

    fun unprepared() = Checked(refusal = "approval coverage was not prepared", coverage = CoverageKind.MISSING)

    data class Checked(
        val actions: Map<String, AutomationAction> = emptyMap(),
        /** Wired action → authored target ref → the exact result used by the verifier. */
        val targets: Map<String, Map<String, TargetResolution.Resolved>> = emptyMap(),
        val refusal: String? = null,
        val coverage: CoverageKind? = null,
        val defaultsWithoutEvidence: Boolean = false,
    )

    fun prepare(bundle: Bundle): Bundle {
        val resolver = Resolver(bundle)
        return bundle.copy(
            entities =
                (bundle.model.schemas["er"] as? org.tatrman.ttr.metadata.model.ErSchema)
                    ?.entities
                    ?.values
                    .orEmpty()
                    .toList(),
            recipes =
                bundle.recipes.mapValues { (_, recipe) ->
                    recipe.copy(coverage = resolver.check(recipe.recipe))
                },
        )
    }

    /** Explicit recomputation seam for constructing changed immutable snapshots, not a request-path check. */
    fun check(
        bundle: Bundle,
        recipe: ManifestRecipe,
    ): Checked = Resolver(bundle).check(recipe)

    private class Resolver(
        private val bundle: Bundle,
    ) {
        private val index by lazy {
            ModelIndex(
                RegistrySnapshot(bundle.model, ModelGraph.build(bundle.model), bundle.loadedAt, emptyList()),
            ) { obj -> bundle.packages[obj.sourceFile].orEmpty() }
        }
        private val groups by lazy {
            (bundle.model.schemas["automation"] as? AutomationSchema)?.actions.orEmpty().groupBy { it.qname.name }
        }

        fun check(recipe: ManifestRecipe): Checked {
            fun refuse(
                kind: CoverageKind,
                reason: String,
            ) = Checked(refusal = reason, coverage = kind)
            val raw =
                when (val value = recipe.approvalDependencies) {
                    null, JsonNull -> null
                    is JsonObject -> value
                    else -> return refuse(CoverageKind.UNSUPPORTED, "approval evidence is not an object")
                }
            var evidence: RecipeApprovalDependencies? = null
            if (raw != null) {
                val version = raw["version"] as? JsonPrimitive
                if (version == null || version.isString || version.intOrNull == null) {
                    return refuse(
                        CoverageKind.UNSUPPORTED,
                        "unsupported approval evidence version: version must be the integer 1",
                    )
                }
                if (version.intOrNull != 1) {
                    return refuse(CoverageKind.UNSUPPORTED, "unsupported approval evidence version ${version.content}")
                }
                (raw.keys - setOf("version", "imports", "textBase", "actions")).sorted().firstOrNull()?.let {
                    return refuse(CoverageKind.MALFORMED, "unknown approval evidence field `$it`")
                }
                val valid =
                    mapOf(
                        "imports" to
                            ((raw["imports"] as? JsonArray)?.all { it is JsonPrimitive && it.isString } == true),
                        "textBase" to ((raw["textBase"] as? JsonPrimitive)?.isString == true),
                        "actions" to
                            (
                                (raw["actions"] as? JsonObject)?.values?.all {
                                    it is JsonPrimitive && it.isString
                                } == true
                            ),
                    )
                valid.entries.firstOrNull { !it.value }?.let {
                    return refuse(
                        CoverageKind.MALFORMED,
                        "approval evidence field `${it.key}` is missing, null or invalid",
                    )
                }
                val decoded = evidenceJson.decodeFromJsonElement<ManifestApprovalDependencies>(raw)
                evidence =
                    RecipeApprovalDependencies(decoded.version, decoded.imports, decoded.textBase, decoded.actions)
                RecipeActionDependencies.validate(evidence)?.let { return refuse(CoverageKind.MALFORMED, it.reason) }
            }
            val scope =
                evidence?.let {
                    index.automationScope(it.imports.map { raw -> ModelIndex.importScope(raw.split('.'), raw) })
                }
            scope?.missingImports?.firstOrNull()?.let {
                return refuse(
                    CoverageKind.UNRESOLVED,
                    "recorded import `$it.*` resolves to no loader package in the pinned model",
                )
            }
            val actions = linkedMapOf<String, AutomationAction>()
            for (name in recipe.wiring.map { it.action }.distinct()) {
                when (
                    val resolution =
                        scope?.action(name) ?: AutomationScope.resolveAction(name, groups[name].orEmpty())
                ) {
                    is ActionResolution.Resolved -> actions[name] = resolution.action
                    is ActionResolution.Unresolved -> return refuse(
                        CoverageKind.UNRESOLVED,
                        if (scope ==
                            null
                        ) {
                            "legacy wired action `$name` is not declared in the model; declare it or correct the wiring"
                        } else {
                            "unresolved wired action `$name` in the pinned approval scope"
                        },
                    )
                    is ActionResolution.Ambiguous -> return refuse(
                        CoverageKind.AMBIGUOUS,
                        if (scope ==
                            null
                        ) {
                            "action name `$name` is declared in several packages; rename it or adopt evidence"
                        } else {
                            "ambiguous wired action `$name` in the pinned approval scope"
                        },
                    )
                }
            }
            if (evidence == null) {
                return if (actions.values.none(AutomationScope::hasDefaults)) {
                    Checked(actions)
                } else {
                    Checked(
                        actions = actions,
                        refusal = "action approval evidence is missing",
                        coverage = CoverageKind.MISSING,
                        defaultsWithoutEvidence = true,
                    )
                }
            }
            val resolved = linkedMapOf<String, TargetResolution>()
            val result =
                RecipeActionDependencies.verify(evidence, recipe.fingerprints.text, actions) { ref ->
                    resolved.getOrPut(ref) { requireNotNull(scope).target(ref) }
                }
            val refusal =
                when (result) {
                    RecipeActionDependencies.Verification.Ok -> null
                    is RecipeActionDependencies.Verification.Malformed -> refuse(CoverageKind.MALFORMED, result.reason)
                    is RecipeActionDependencies.Verification.Stale ->
                        refuse(
                            CoverageKind.STALE,
                            "action approval dependencies are stale for `${result.action}`; rebuild the recipe bundle",
                        )
                    is RecipeActionDependencies.Verification.Unresolved ->
                        refuse(
                            CoverageKind.UNRESOLVED,
                            "unresolved action approval dependency `${result.ref}` in the pinned recipe scope",
                        )
                    is RecipeActionDependencies.Verification.Ambiguous ->
                        refuse(
                            CoverageKind.AMBIGUOUS,
                            "ambiguous action approval dependency `${result.ref}` in the pinned recipe scope",
                        )
                }
            if (refusal != null) return refusal
            return Checked(
                actions,
                actions.mapValues { (_, action) ->
                    AutomationScope.targetRefs(action).associateWith { ref ->
                        resolved.getValue(ref) as TargetResolution.Resolved
                    }
                },
            )
        }
    }
}
