// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.tatrman.automation.bundle.ApprovalReader
import org.tatrman.automation.bundle.Bundle
import org.tatrman.automation.bundle.BundleLoader
import org.tatrman.automation.bundle.Bundles
import org.tatrman.automation.bundle.Fingerprints
import org.tatrman.automation.bundle.LoadedRecipe
import org.tatrman.automation.bundle.ManifestRecipe
import org.tatrman.automation.bundle.ManifestVerdictSchema
import org.tatrman.automation.bundle.Registry
import org.tatrman.automation.bundle.RegistryCell
import org.tatrman.automation.bundle.Staleness
import org.tatrman.automation.bundle.VelesHandshake
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.identity.DeploymentIdentity
import org.tatrman.automation.identity.GrantMap
import org.tatrman.automation.identity.RunAs
import org.tatrman.automation.identity.caller
import org.tatrman.automation.identity.ServiceTokens
import org.tatrman.ttr.metadata.model.AutomationProgram
import org.tatrman.ttr.metadata.model.ProgramDelivery
import org.tatrman.ttr.metadata.model.WorldDeployment

/**
 * `GET /automation/` (AU contracts §1.1): what is served, and what may run where. The shape is frozen at S0.2 —
 * fields a later stage fills are present now as `null` / empty (`approved` until P2, `connectors` until S3.1),
 * so the Studio contract reads one shape throughout. [modelDrift] (T6) = Veles serves another commit of the
 * model than the served bundle's (null = not known). [version] is the run API's.
 */
@Serializable
data class Descriptor(
    val served: ServedBundle?,
    val latest: LatestCommit?,
    val previews: List<PreviewDescriptor>,
    val recipes: List<RecipeDescriptor>,
    val programs: List<ProgramDescriptor>,
    val deployments: List<DeploymentDescriptor>,
    val connectors: List<JsonObject>,
    val registry: List<RegistryCell>,
    val modelDrift: Boolean?,
    val warnings: List<String>,
    val paused: Boolean,
    val version: String,
)

@Serializable
data class LatestCommit(
    val commit: String,
)

@Serializable
data class PreviewDescriptor(
    val branch: String,
    val bundle: String,
    val commit: String,
    val idleSince: String,
)

@Serializable
data class RecipeDescriptor(
    val name: String,
    val mode: String?,
    val kinds: List<String>,
    val fingerprints: Fingerprint2,
    val verdictEntity: String?,
    val approved: Fingerprint2?,
)

@Serializable
data class Fingerprint2(
    val text: String,
    val values: String,
)

@Serializable
data class ProgramDescriptor(
    val name: String,
    val recipe: String?,
    val port: String?,
    val mode: String?,
    val asOf: String?,
    val schedule: String?,
    val deliver: JsonElement?,
    val fingerprint: String,
)

@Serializable
data class DeploymentDescriptor(
    val name: String,
    val program: String?,
    val process: String?,
    val target: String,
    val targetType: String?,
    val runAs: String,
    val trigger: String?,
    val dryRun: Boolean,
    val enabled: Boolean,
    val stale: Boolean,
    val granted: Boolean,
    val fingerprint: String,
)

/** `GET /automation/recipes/{r}`: the manifest's recipe object, its approvals (P2) and the verdict schema. */
@Serializable
data class RecipeDetail(
    val bundle: String,
    val recipe: ManifestRecipe,
    val approvals: List<JsonObject>,
    val verdictSchema: ManifestVerdictSchema?,
)

/** `GET /automation/deployments/{d}`: the deployment, which fingerprints lack an approval, its runs (S1.1 / S3.2). */
@Serializable
data class DeploymentDetail(
    val deployment: DeploymentDescriptor,
    val registry: RegistryCell,
    val unapproved: List<String>,
    val lastRun: JsonObject?,
    val nextRun: String?,
)

/** `POST /automation/refresh` → 200. [changed] = false when the commit was already loaded (a no-op). */
@Serializable
data class RefreshResponse(
    val bundle: String,
    val commit: String,
    val changed: Boolean,
)

const val RUN_API_VERSION = "v1"

/**
 * What the descriptor routes read: the registry of bundles and the rules that judge a deployment for a caller.
 * [refreshRoles] are the realm roles that may call `POST /refresh` (`automation.auth.refreshRoles`).
 */
class DescriptorSources(
    val bundles: Bundles,
    val registry: Registry,
    val approvals: ApprovalReader,
    val tokens: ServiceTokens,
    val veles: VelesHandshake?,
    val paused: () -> Boolean,
    val refreshRoles: Set<String>,
)

/** Authenticated: mounted under the bearer check — the caller is known to every route here. */
fun Route.descriptorRoutes(sources: DescriptorSources) {
    get("/automation/") {
        call.respond(describe(sources, call.caller()))
    }
    get("/automation/recipes/{r}") {
        val bundle = sources.bundles.served
        val r = call.parameters["r"].orEmpty()
        val recipe = bundle?.recipes?.get(r) ?: throw AutomationException(ErrorCode.RECIPE, "no recipe $r is served")
        call.respond(
            RecipeDetail(
                bundle.id,
                recipe.recipe,
                approvals = emptyList(),
                verdictSchema = recipe.recipe.verdictSchema,
            ),
        )
    }
    get("/automation/deployments/{d}") {
        val bundle = sources.bundles.served
        val d = call.parameters["d"].orEmpty()
        val deployment =
            bundle?.deployments?.get(d) ?: throw AutomationException(ErrorCode.DEPLOYMENT, "no deployment $d is served")
        val staleness = withContext(Dispatchers.IO) { Staleness.of(bundle, d, sources.approvals) }
        call.respond(
            DeploymentDetail(
                deployment =
                    deploymentOf(
                        bundle,
                        deployment,
                        runAsByWorld(bundle, sources),
                        call.caller(),
                        staleness.stale,
                    ),
                registry = sources.registry.cell(bundle, deployment),
                unapproved = staleness.unapproved,
                lastRun = null,
                nextRun = null,
            ),
        )
    }
    post("/automation/refresh") {
        // A refresh swaps what every deployment runs, and a preview clones a branch: an operator's or the CI's call,
        // not any verified caller's.
        val caller = call.caller()
        if (caller.roles.none { it in sources.refreshRoles }) {
            throw AutomationException(
                ErrorCode.REFRESH_FORBIDDEN,
                "${caller.principal} may not refresh: it needs one of the roles ${sources.refreshRoles.sorted()}",
            )
        }
        val outcome = sources.bundles.refresh(call.request.queryParameters["branch"])
        call.respond(RefreshResponse(outcome.bundle.id, outcome.bundle.commit, outcome.changed))
    }
}

suspend fun describe(
    sources: DescriptorSources,
    caller: Caller,
): Descriptor {
    val bundles = sources.bundles
    val bundle = bundles.served
    // Once per request, not per deployment: the approvals in one query, a RunAs per world.
    val approvals =
        bundle?.let {
            withContext(Dispatchers.IO) {
                sources.approvals.snapshot(it.recipes.keys + it.programs.keys + it.deployments.keys)
            }
        } ?: sources.approvals
    val runAs = bundle?.let { runAsByWorld(it, sources) }.orEmpty()
    return Descriptor(
        served = bundle?.let { ServedBundle(it.id, it.commit, it.modelFp, it.loadedAt.toString()) },
        latest = bundles.latestCommit?.let { LatestCommit(it) },
        previews =
            bundles.previews().map {
                PreviewDescriptor(it.bundle.branch, it.bundle.id, it.bundle.commit, it.lastUsedAt.toString())
            },
        recipes =
            bundle
                ?.recipes
                ?.values
                ?.map { recipeOf(it) }
                .orEmpty(),
        programs =
            bundle
                ?.programs
                ?.values
                ?.map { programOf(bundle, it) }
                .orEmpty(),
        deployments =
            bundle
                ?.deployments
                ?.values
                ?.map { d ->
                    deploymentOf(bundle, d, runAs, caller, Staleness.of(bundle, d.qname.name, approvals).stale)
                }.orEmpty(),
        connectors = emptyList(),
        registry = bundle?.let { sources.registry.grid(it) }.orEmpty(),
        modelDrift = bundle?.let { sources.veles?.drift(it.commit) },
        warnings = bundle?.warnings.orEmpty(),
        paused = sources.paused(),
        version = RUN_API_VERSION,
    )
}

private fun recipeOf(r: LoadedRecipe): RecipeDescriptor =
    RecipeDescriptor(
        name = r.name,
        mode = r.recipe.mode,
        kinds = r.recipe.kinds,
        fingerprints = Fingerprint2(r.recipe.fingerprints.text, r.recipe.fingerprints.values),
        verdictEntity = r.recipe.verdictSchema?.entity,
        approved = null,
    )

private fun programOf(
    bundle: Bundle,
    p: AutomationProgram,
): ProgramDescriptor =
    ProgramDescriptor(
        name = p.qname.name,
        recipe = p.recipe,
        port = p.port,
        mode = p.mode,
        asOf = p.asOf,
        schedule = p.schedule,
        deliver =
            when (val d = p.deliver) {
                ProgramDelivery.Immediate -> JsonPrimitive("immediate")
                is ProgramDelivery.Digest ->
                    buildJsonObject {
                        putJsonObject("digest") {
                            put("by", d.by)
                            put("per", d.per)
                        }
                    }
                null -> null
            },
        fingerprint = bundle.programFps.getValue(p.qname.name),
    )

/** Each world's identity rules: its own grants and identities (review-114 — never another world's). */
private fun runAsByWorld(
    bundle: Bundle,
    sources: DescriptorSources,
): Map<String, RunAs> =
    bundle.worlds.mapValues { (_, w) -> RunAs(GrantMap(w.grants, w.identities), w.identities, sources.tokens) }

private fun deploymentOf(
    bundle: Bundle,
    d: WorldDeployment,
    runAsByWorld: Map<String, RunAs>,
    caller: Caller,
    stale: Boolean,
): DeploymentDescriptor {
    val runAs = bundle.worldOf(d)?.let { runAsByWorld[it.name] }
    val granted =
        runAs != null &&
            bundle.programsOf(d).ifEmpty { listOf("") }.all { program ->
                runAs.granted(
                    caller,
                    DeploymentIdentity(d.qname.name, program, BundleLoader.runAs(d.runAs), bundle.author),
                )
            }
    return DeploymentDescriptor(
        name = d.qname.name,
        program = d.program,
        process = d.process,
        target = d.target,
        targetType = bundle.targetType(d),
        runAs = Fingerprints.runAsWire(d.runAs),
        trigger = Fingerprints.triggerWire(d.trigger),
        dryRun = d.dryRun,
        enabled = d.enabled,
        stale = stale,
        granted = granted,
        fingerprint = bundle.deploymentFps.getValue(d.qname.name),
    )
}
