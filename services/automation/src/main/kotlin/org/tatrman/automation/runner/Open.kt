// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.*
import org.tatrman.automation.identity.*
import org.tatrman.automation.ledger.*
import org.tatrman.ttr.metadata.model.AutomationDuration
import org.tatrman.ttr.metadata.model.AutomationProgram
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** AU contracts §1.2. Optional exported fingerprints must match even in development approvals-off mode. */
@Serializable
data class OpenRequest(
    val deployment: String,
    val port: JsonObject,
    val asOf: String? = null,
    val bundle: String? = null,
    val dryRun: Boolean? = null,
    val idempotencyKey: String? = null,
    val origin: String? = null,
    val recipeFingerprint: String? = null,
    val recipeValuesFingerprint: String? = null,
    val programFingerprint: String? = null,
    val deploymentFingerprint: String? = null,
)

@Serializable
data class OpenResponse(
    val runId: String,
    val replayed: Boolean,
    val status: String? = null,
    val location: String? = null,
    val protocolRef: String? = null,
)

data class OpenResult(
    val httpStatus: Int,
    val response: OpenResponse,
    /** Only a successful new claim may create an executor. Replays and live handles never run again. */
    val context: RunContext? = null,
    val approvalsBypassed: Boolean = false,
)

/**
 * Open validates an immutable bundle snapshot before its atomic ledger claim. It does no reads through the door.
 * P1 always records dry-run; async evaluation and protocol/lifecycle are the following P1 stages.
 * [approvalsOff] is accepted only by the startup config's explicit development gate.
 */
class Open(
    private val served: () -> Bundle?,
    private val approvals: ApprovalReader,
    private val tokens: ServiceTokens,
    private val claim: (RunOpen) -> Claim,
    private val protocolRef: (UUID) -> String?,
    private val paused: () -> Boolean,
    private val syncDefault: Duration,
    private val batchDefault: Duration,
    private val clock: Clock = Clock.systemUTC(),
    private val approvalsOff: Boolean = false,
    private val detailLimit: Int = 200,
) {
    fun open(
        recipeName: String,
        request: OpenRequest,
        caller: Caller,
    ): OpenResult {
        if (paused()) throw AutomationException(ErrorCode.PAUSED, "automation is paused")
        // Preview opens arrive with P4; silently running a preview request on served would mislabel the evidence.
        if (request.bundle != null) throw AutomationException(ErrorCode.BUNDLE, "preview opens are not supported yet")
        val bundle = served()
        val recipe =
            bundle?.recipes?.get(recipeName)
                ?: throw AutomationException(ErrorCode.RECIPE, "no recipe $recipeName is served")
        val d =
            bundle.deployments[request.deployment]
                ?: throw AutomationException(ErrorCode.DEPLOYMENT, "no deployment ${request.deployment} is served")
        if (!d.enabled) throw AutomationException(ErrorCode.DISABLED, "deployment ${request.deployment} is disabled")
        val programName =
            bundle.programsOf(d).singleOrNull { bundle.programs[it]?.recipe == recipeName }
                ?: portRefused("deployment does not select exactly one program for recipe $recipeName")
        val program = bundle.programs.getValue(programName)
        val world = bundle.worldOf(d) ?: error("loaded deployment has no world")
        val identity =
            RunAs(GrantMap(world.grants, world.identities), world.identities, tokens).resolve(
                caller,
                DeploymentIdentity(request.deployment, programName, BundleLoader.runAs(d.runAs), bundle.author),
                if (d.debug) DebugAs.CALLER else null,
            )
        val coverage = recipe.coverage
        coverage.refusal?.takeUnless { approvalsOff && coverage.defaultsWithoutEvidence }?.let { reason ->
            throw AutomationException(
                ErrorCode.RECIPE_STALE,
                reason,
                buildJsonObject {
                    put("differs", JsonArray(listOf(JsonPrimitive("text"))))
                    // Invalid coverage cannot establish an approved text, even if a ledger row matches the manifest.
                    putJsonObject("approved") { put("text", JsonNull) }
                    putJsonObject("current") { put("text", recipe.recipe.fingerprints.text) }
                    put("reason", reason)
                    put("coverage", requireNotNull(coverage.coverage).wire)
                },
            )
        }
        val pinnedActions = coverage.actions
        val fps = recipe.recipe.fingerprints
        val programFp = bundle.programFps.getValue(programName)
        val deploymentFp = bundle.deploymentFps.getValue(request.deployment)
        val current =
            mapOf(
                "text" to fps.text,
                "values" to fps.values,
                "program" to programFp,
                "deployment" to deploymentFp,
            )
        val supplied =
            mapOf(
                "text" to request.recipeFingerprint,
                "values" to request.recipeValuesFingerprint,
                "program" to request.programFingerprint,
                "deployment" to request.deploymentFingerprint,
            )
        stale(
            current,
            supplied
                .filter { (kind, value) ->
                    value != null && value != current[kind]
                }.keys
                .toList(),
            supplied,
        )
        if (!approvalsOff) {
            val snapshot = approvals.snapshot(listOf(recipeName, programName, request.deployment))
            val objects =
                mapOf(
                    "text" to ("recipe" to recipeName),
                    "values" to ("values" to recipeName),
                    "program" to ("program" to programName),
                    "deployment" to ("deployment" to request.deployment),
                )
            val missing =
                current.keys.filter { kind ->
                    val (approvalKind, obj) = objects.getValue(kind)
                    !snapshot.approved(approvalKind, obj, current.getValue(kind))
                }
            stale(current, missing, objects.mapValues { (_, pair) -> snapshot.latestApproved(pair.first, pair.second) })
        }
        val step =
            d.process?.let {
                world.processes
                    .getValue(it)
                    .steps
                    .single { s -> s.program == programName }
            }
        val port = OpenPort.resolve(bundle, recipe, program.copy(port = step?.port ?: program.port), request.port)
        val asOf = resolveAsOf(program, request.asOf, port.eventAt)
        val bindings = RecipeBindings.resolve(recipe.recipe, asOf, overrides(recipe, program))
        require(bindings.keys.none { it.startsWith("_au_port_") }) { "reserved port binding prefix in recipe" }
        val sync = program.budgetSync?.duration() ?: syncDefault
        val batch = program.budgetBatch?.duration() ?: batchDefault
        require(!sync.isNegative && !sync.isZero) { "invalid sync budget" }
        val context =
            RunContext(
                UUID(0, 0),
                recipe,
                world.name,
                bundle.modelFp,
                identity,
                bindings,
                port.keys,
                d.readCeiling ?: 100_000,
                batch,
                allowRestrictedView = d.allowRestrictedView,
                detailLimit = detailLimit,
                detailLimits = detailLimits(recipe.recipe, pinnedActions, detailLimit),
                syncBudget = sync,
                obligationStorage =
                    if (pinnedActions.values.any {
                            it.sinks[Bundle.NATIVE] == "obligation" &&
                                it.transition != null
                        }
                    ) {
                        org.tatrman.automation.apply.PreviewObligationStorage
                            .resolve(bundle, world.name)
                    } else {
                        null
                    },
                previewMetadata =
                    org.tatrman.automation.apply.PreviewMetadata(
                        pinnedActions,
                        bundle.entities,
                        coverage.targets,
                    ),
            )
        if (port.keys != null &&
            port.keys.size > context.readCeiling
        ) {
            portRefused("port exceeds the deployment read ceiling")
        }
        val run =
            RunOpen(
                recipe = recipeName,
                program = programName,
                deployment = request.deployment,
                bundle = bundle.id,
                fpText = fps.text,
                fpValues = fps.values,
                fpProgram = programFp,
                fpDeployment = deploymentFp,
                modelFp = bundle.modelFp,
                port = request.port,
                asOf = asOf,
                author = bundle.author,
                runAs = identity.principal,
                caller = caller.principal,
                callerSubject = caller.sub,
                origin = request.origin,
                dryRun = true,
                governed = true,
                budgetSync = sync,
                budgetBatch = batch,
                idempotencyKey = idempotency(bundle, request, caller, current, asOf, recipeName, programName, port),
                openingProtocol =
                    buildJsonObject {
                        put("event", "opened")
                        put("recipe", recipeName)
                        put("program", programName)
                        put("deployment", request.deployment)
                        put(
                            "fps",
                            buildJsonObject {
                                put("text", fps.text)
                                put("values", fps.values)
                                put("program", programFp)
                                put("deployment", deploymentFp)
                            },
                        )
                        put("author", bundle.author)
                        put("port", request.port)
                        put("origin", request.origin)
                        put("bundle", bundle.id)
                        put("modelFp", bundle.modelFp)
                        put("asOf", asOf.toString())
                        put("caller", caller.principal)
                        put("runAs", identity.principal)
                        put("dryRun", true)
                        put("allowRestrictedView", d.allowRestrictedView)
                        put("readCeiling", context.readCeiling)
                        put("detailLimit", context.detailLimit)
                        put("detailLimits", JsonObject(context.detailLimits.mapValues { JsonPrimitive(it.value) }))
                        put("budgetSyncMs", sync.toMillis())
                        put("budgetBatchMs", batch.toMillis())
                        put("approvals", if (approvalsOff) "development-off" else "required")
                    },
            )
        val claimed = claim(run)
        val id = claimed.runId
        val location = "/automation/runs/$id"
        return when (claimed) {
            is Claim.Replay -> OpenResult(200, OpenResponse(id.toString(), true, protocolRef = protocolRef(id)))
            is Claim.Running ->
                OpenResult(
                    202,
                    OpenResponse(id.toString(), false, status = claimed.status.wire, location = location),
                )
            is Claim.Claimed ->
                OpenResult(
                    201,
                    OpenResponse(id.toString(), false, "open", location),
                    context.copy(id = id, startedAt = claimed.startedAt),
                    approvalsOff,
                )
        }
    }

    private fun resolveAsOf(
        program: AutomationProgram,
        requested: String?,
        eventAt: Instant?,
    ): Instant {
        if (requested != null && requested != "now") return parseAsOf(requested)
        val policy = requested ?: program.asOf ?: "run"
        return when (policy) {
            "now", "run" -> clock.instant()
            "event" -> eventAt ?: clock.instant()
            else -> parseAsOf(policy)
        }
    }

    private fun parseAsOf(value: String): Instant =
        try {
            Instant.parse(value)
        } catch (_: java.time.DateTimeException) {
            throw AutomationException(ErrorCode.AS_OF, "asOf must be now or an instant with a UTC offset")
        }

    private fun overrides(
        recipe: LoadedRecipe,
        program: AutomationProgram,
    ): Map<String, BoundValue> =
        program.overrides.associate { (name, value) ->
            val parameter = recipe.recipe.parameters.single { it.name == name }
            // The same literal reader as recipe defaults, including exact decimals and declared duration units.
            val single =
                recipe.recipe.copy(
                    parameters = listOf(parameter.copy(value = Fingerprints.render(value))),
                    bindings = emptyList(),
                )
            name to RecipeBindings.resolve(single, Instant.EPOCH).getValue(name)
        }

    private fun idempotency(
        bundle: Bundle,
        request: OpenRequest,
        caller: Caller,
        fps: Map<String, String>,
        asOf: Instant,
        recipeName: String,
        programName: String,
        port: OpenPort,
    ): String {
        if (request.idempotencyKey != null &&
            request.idempotencyKey.isBlank()
        ) {
            portRefused("idempotencyKey cannot be blank")
        }
        val key =
            request.idempotencyKey?.let { JsonPrimitive(it) } ?: buildJsonObject {
                put("fingerprints", JsonObject(fps.mapValues { JsonPrimitive(it.value) }))
                put("deployment", request.deployment)
                put("recipe", recipeName)
                put("program", programName)
                put("port", port.canonical())
                put("asOf", asOf.toString())
                put("modelFp", bundle.modelFp)
            }
        // The ledger index is global. Namespace caller-supplied keys so another principal cannot claim a handle.
        val material =
            JsonArray(
                listOf(JsonPrimitive(caller.sub), JsonPrimitive(request.deployment), JsonPrimitive("open-v1"), key),
            ).toString()
        return "open:" +
            MessageDigest
                .getInstance(
                    "SHA-256",
                ).digest(material.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }

    private fun stale(
        current: Map<String, String>,
        differs: List<String>,
        approved: Map<String, String?>,
    ) {
        if (differs.isEmpty()) return
        val recipeDiff = differs.filter { it == "text" || it == "values" }
        val group =
            if (recipeDiff.isNotEmpty()) {
                recipeDiff
            } else {
                listOf(
                    if ("program" in
                        differs
                    ) {
                        "program"
                    } else {
                        "deployment"
                    },
                )
            }
        val detail =
            buildJsonObject {
                put("differs", JsonArray(group.map(::JsonPrimitive)))
                put("approved", JsonObject(group.associateWith { approved[it]?.let(::JsonPrimitive) ?: JsonNull }))
                put("current", JsonObject(group.associateWith { JsonPrimitive(current.getValue(it)) }))
            }
        if (recipeDiff.isNotEmpty()) {
            throw AutomationException(
                ErrorCode.RECIPE_STALE,
                "recipe fingerprint differs",
                detail,
            )
        }
        if ("program" in
            differs
        ) {
            throw AutomationException(ErrorCode.PROGRAM_STALE, "program fingerprint differs", detail)
        }
        throw AutomationException(ErrorCode.DEPLOYMENT_STALE, "deployment fingerprint differs", detail)
    }
}

private fun AutomationDuration.duration(): Duration {
    val millis =
        when (unit) {
            "ms", "millisecond", "milliseconds" -> amount
            "s", "second", "seconds" -> amount * 1000
            "m", "minute", "minutes" -> amount * 60_000
            "h", "hour", "hours" -> amount * 3_600_000
            "d", "day", "days" -> amount * 86_400_000
            else -> error("unsupported run budget unit $unit")
        }
    require(millis.isFinite() && millis >= 1 && millis < Long.MAX_VALUE.toDouble()) { "invalid run budget" }
    return Duration.ofMillis(millis.toLong())
}
