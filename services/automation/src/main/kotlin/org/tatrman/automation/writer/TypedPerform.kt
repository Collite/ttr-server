// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.writer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.apply.ApplyPlanner
import org.tatrman.automation.apply.ApplyRow
import org.tatrman.automation.apply.DryRunAction
import org.tatrman.automation.apply.PlanPlacement
import org.tatrman.automation.apply.PreviewMetadata
import org.tatrman.automation.bundle.Bundle
import org.tatrman.automation.bundle.BundleLoader
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.identity.DeploymentIdentity
import org.tatrman.automation.identity.GrantMap
import org.tatrman.automation.identity.RunAs
import org.tatrman.automation.identity.ServiceTokens
import org.tatrman.automation.ledger.Claim
import org.tatrman.automation.ledger.Ledger
import org.tatrman.automation.ledger.ProtocolSection
import org.tatrman.automation.ledger.RUN_KIND_PERFORM
import org.tatrman.automation.ledger.RunOpen
import org.tatrman.automation.runner.duration
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * `POST /automation/deployments/{d}/actions/{a}/perform` (✅AU-P3-1, D-168): one action applied to one [key] without
 * a run's rows — the engine's timeout path (`perform hold {order, department}`). [params] carries the action's other
 * columns; [by] is who approved it (asserted; S3.2 verifies an engine-asserted person, D-159).
 */
@Serializable
data class TypedPerformRequest(
    val key: JsonObject,
    val params: JsonObject = JsonObject(emptyMap()),
    val by: String? = null,
    val idempotencyKey: String? = null,
    val origin: String? = null,
    val expiry: ApprovalExpiryRequest? = null,
)

@Serializable
data class TypedPerformRow(
    val seq: Int,
    val entity: String,
    val op: String,
    val status: String,
    val error: String? = null,
    val history: String? = null,
)

@Serializable
data class TypedPerformResponse(
    val runId: String,
    val action: String,
    val key: JsonObject,
    /** `applied` (every row) · `failed` · `unknown` (a request whose outcome is unknown — never resent). */
    val outcome: String,
    val rows: List<TypedPerformRow>,
    val protocolRef: String,
    val replayed: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val expiry: JsonObject? = null,
)

/**
 * The typed-action perform (S3.1 T6): the deployment's program's recipe pins the action (its coverage) and its
 * placement; [ApplyPlanner.effects] lowers the action on [TypedPerformRequest.key] to §4 rows, and [Apply] sends them
 * through the door as the deployment's `run_as` (with a grant), exactly as a close would. A `perform` run in the ledger
 * carries its own sealed protocol: `automation` (who, what) and `writes` (each journal step, then the outcome).
 * v1 performs `transition` sinks only (`hold`, `release`, `set_state` …); an action needing approval refuses without
 * `by` (`409 approval-required`).
 */
class TypedPerform(
    private val served: () -> Bundle?,
    private val apply: Apply,
    private val ledger: Ledger,
    private val tokens: ServiceTokens,
    private val syncDefault: Duration,
    private val clock: Clock = Clock.systemUTC(),
    /** ✅R273-A: a paused service refuses a typed perform like Open and the query step (`503 paused`). */
    private val paused: () -> Boolean = { false },
    private val expiryVerifier: ApprovalExpiry? = null,
    private val held: Held? = null,
) {
    suspend fun perform(
        deploymentName: String,
        actionName: String,
        request: TypedPerformRequest,
        caller: Caller,
    ): TypedPerformResponse {
        if (paused()) throw AutomationException(ErrorCode.PAUSED, "automation is paused")
        val bundle = served() ?: throw AutomationException(ErrorCode.DEPLOYMENT, "no bundle is served")
        val deployment =
            bundle.deployments[deploymentName]
                ?: throw AutomationException(ErrorCode.DEPLOYMENT, "no deployment $deploymentName is served")
        if (!deployment.enabled) throw AutomationException(ErrorCode.DISABLED, "deployment $deploymentName is disabled")
        val world = bundle.worldOf(deployment) ?: error("loaded deployment has no world")
        val program = bundle.programs[deployment.program] ?: error("loaded deployment has no program")
        val recipe =
            program.recipe?.let { bundle.recipes[it] }
                ?: throw AutomationException(ErrorCode.RECIPE, "deployment $deploymentName's recipe is not served")
        val action =
            recipe.coverage.actions[actionName]
                ?: throw AutomationException(ErrorCode.ACTION, "recipe ${recipe.name} wires no action $actionName")
        val sink = action.sinks[Bundle.NATIVE]
        if (sink != "transition") {
            throw AutomationException(
                ErrorCode.BAD_REQUEST,
                "action $actionName is performed by ${sink ?: "the engine"}; a typed perform applies transitions only (v1)",
            )
        }
        if (action.approval == "required" && request.by.isNullOrBlank()) {
            throw AutomationException(
                ErrorCode.APPROVAL_REQUIRED,
                "action $actionName needs an approval: name who approved it in `by`",
                buildJsonObject {
                    put("action", actionName)
                    action.byRole?.let { put("by", it) }
                },
            )
        }
        if (request.idempotencyKey != null && request.idempotencyKey.isBlank()) bad("idempotencyKey cannot be blank")
        val expiry =
            request.expiry?.let {
                val verifier =
                    expiryVerifier
                        ?: throw AutomationException(ErrorCode.ENGINE_UNREACHABLE, "no engine history is configured")
                verifier.verify(caller, actionName, request, it)
            }
        val columns = action.columns.associateBy { it.name }
        val args = LinkedHashMap<String, JsonElement>()
        // review-273 F2: key and params are read apart — a column in both is refused, never overridden by params.
        for (part in listOf(request.key, request.params)) {
            for ((name, value) in part) {
                if (name !in columns) bad("action $actionName has no column $name")
                if (name in args) bad("column $name is given twice")
                args[name] = value
            }
        }
        val identity =
            RunAs(GrantMap(world.grants, world.identities, world.roles), world.identities, tokens).resolve(
                caller,
                DeploymentIdentity(
                    deploymentName,
                    deployment.program,
                    BundleLoader.runAs(deployment.runAs),
                    bundle.author,
                ),
            )
        val source = PlanPlacement.source(bundle, world.name, recipe)
        val rows = mutableListOf<ApplyRow>()
        try {
            ApplyPlanner(clock).effects(
                action,
                args,
                PreviewMetadata(recipe.coverage.actions, bundle.entities, recipe.coverage.targets),
                recipe.coverage.targets[actionName],
                rows,
            )
        } catch (e: AutomationException) {
            throw AutomationException(ErrorCode.BAD_REQUEST, e.message ?: "the action cannot be applied to this key")
        } catch (e: IllegalArgumentException) {
            throw AutomationException(ErrorCode.BAD_REQUEST, e.message ?: "the action cannot be applied to this key")
        }
        val budget = program.budgetSync?.duration() ?: syncDefault
        val opening =
            buildJsonObject {
                put("kind", RUN_KIND_PERFORM)
                put("action", actionName)
                put("recipe", recipe.name)
                put("deployment", deploymentName)
                put("key", request.key)
                put("params", request.params)
                request.by?.let { put("by", it) }
                put("caller", caller.principal)
                put("runAs", identity.principal)
                put("bundle", bundle.id)
                put("modelFp", bundle.modelFp)
            }
        val expiryEvidence = expiry?.evidence()
        val open =
            RunOpen(
                recipe = actionName,
                program = deployment.program,
                deployment = deploymentName,
                bundle = bundle.id,
                modelFp = bundle.modelFp,
                port = buildJsonObject { put("key", request.key) },
                author = bundle.author,
                runAs = identity.principal,
                caller = caller.principal,
                callerSubject = caller.sub,
                origin = request.origin ?: "engine",
                idempotencyKey =
                    if (expiry ==
                        null
                    ) {
                        request.idempotencyKey?.let { key(caller, deploymentName, actionName, it) }
                    } else {
                        null
                    },
                budgetSync = budget,
                kind = RUN_KIND_PERFORM,
                openingProtocol =
                    if (expiryEvidence ==
                        null
                    ) {
                        opening
                    } else {
                        JsonObject(opening + ("expiry" to expiryEvidence))
                    },
            )

        if (expiry != null && rows.singleOrNull { it.op == "transition" }?.to != JsonPrimitive("held")) {
            unverifiedExpiry("the Sales fallback must perform a hold transition")
        }
        val write: suspend (Claim) -> TypedPerformResponse = { claim ->
            val input =
                ApplyInput(
                    claim.runId,
                    world.name,
                    identity,
                    bundle.modelFp,
                    clock.instant().plus(budget),
                    rows,
                    listOf(
                        DryRunAction(
                            request.key,
                            "perform",
                            actionName,
                            sink,
                            JsonObject(args),
                            rows.map { it.seq },
                            dryRun = false,
                        ),
                    ),
                    source,
                    bundle.entities,
                    recipe.recipe.roles
                        ?.routes
                        .orEmpty()
                        .map { it.name },
                    units = listOf(rows.map { it.seq }),
                )
            complete(claim, input, request.key, caller, actionName, expiryEvidence, bundle.id)
        }
        return if (expiry == null) {
            write(withContext(Dispatchers.IO) { ledger.claimRun(open) })
        } else {
            requireNotNull(held).performExpiry(expiry, open, recipe.name, write)
        }
    }

    private suspend fun complete(
        claim: Claim,
        input: ApplyInput,
        key: JsonObject,
        caller: Caller,
        action: String,
        expiry: JsonObject?,
        bundle: String,
    ): TypedPerformResponse {
        val run = claim.runId
        val ref = "/automation/runs/$run/protocol"
        when (claim) {
            is Claim.Replay -> return replay(run, caller, action, key, ref, expiry)
            is Claim.Running -> {
                if (expiry == null) {
                    throw AutomationException(ErrorCode.CLOSE_NOT_READY, "the key's perform run $run is still running")
                }
                val pinned = withContext(Dispatchers.IO) { ledger.findRun(run) }?.open
                if (pinned?.bundle != bundle ||
                    pinned.modelFp != input.modelFp ||
                    pinned.runAs != input.identity.principal
                ) {
                    throw AutomationException(
                        ErrorCode.SUPERSEDED,
                        "the pending expiry hold's bundle or identity has changed",
                    )
                }
            }
            is Claim.Claimed -> Unit
        }
        val journal = ProtocolJournal(ledger, run, recoverable = expiry != null)
        val outcomes =
            try {
                val recorded =
                    if (claim is Claim.Running) {
                        withContext(Dispatchers.IO) { journal.recorded(caller.sub) }
                    } else {
                        emptyMap()
                    }
                // D-267 requires proof that this hold wrote the order. A readback can match another run's write.
                // Apply resumes from acknowledged outcomes and leaves ambiguous writes staged without resending them.
                apply.apply(input.copy(recorded = recorded), journal)
            } catch (e: Exception) {
                if (expiry == null) {
                    withContext(Dispatchers.IO + NonCancellable) {
                        runCatching {
                            val code = (e as? AutomationException)?.code?.code ?: ErrorCode.INTERNAL.code
                            ledger.finishAdHocRun(
                                run,
                                listOf(ProtocolSection.WRITES to buildJsonObject { put("error", code) }),
                                JsonObject(mapOf("code" to JsonPrimitive(code))),
                            )
                        }
                    }
                }
                throw e
            }
        val answer =
            input.rows.map { row ->
                val o = outcomes[row.seq]
                TypedPerformRow(
                    row.seq,
                    row.entity,
                    row.op,
                    o?.status ?: "staged",
                    o?.error,
                    o?.history,
                )
            }
        val outcome =
            when {
                answer.all { it.status == "applied" } -> "applied"
                answer.any { it.error == RowOutcome.UNKNOWN || it.error == RowOutcome.IN_FLIGHT } -> "unknown"
                else -> "failed"
            }
        if (expiry != null && outcome == "unknown") {
            throw AutomationException(
                ErrorCode.EXPIRY_HOLD_PENDING,
                "the verified expiry's hold has an unresolved Door write",
                buildJsonObject { put("runId", run.toString()) },
            )
        }
        val summary =
            buildJsonObject {
                put("event", "performed")
                put("action", action)
                put("key", key)
                put("outcome", outcome)
                putJsonArray("rows") {
                    answer.forEach { r ->
                        add(
                            buildJsonObject {
                                put("seq", r.seq)
                                put("entity", r.entity)
                                put("op", r.op)
                                put("status", r.status)
                                r.error?.let { put("error", it) }
                                r.history?.let { put("history", it) }
                            },
                        )
                    }
                }
            }
        withContext(Dispatchers.IO + NonCancellable) {
            ledger.finishAdHocRun(run, listOf(ProtocolSection.WRITES to summary))
        }
        return TypedPerformResponse(run.toString(), action, key, outcome, answer, ref, expiry = expiry)
    }

    private suspend fun replay(
        run: UUID,
        caller: Caller,
        action: String,
        key: JsonObject,
        ref: String,
        expiry: JsonObject?,
    ): TypedPerformResponse {
        val writes =
            withContext(Dispatchers.IO) { ledger.readProtocol(run, caller.sub) }
                .sections["writes"]
                ?.map { it.payload }
                ?.filterIsInstance<JsonObject>()
                ?.lastOrNull { it["event"] == JsonPrimitive("performed") }
                ?: throw AutomationException(ErrorCode.CLOSE_NOT_READY, "the key's perform run recorded no outcome")
        val rows =
            (writes["rows"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().map {
                TypedPerformRow(
                    (it["seq"] as JsonPrimitive).content.toInt(),
                    (it["entity"] as JsonPrimitive).content,
                    (it["op"] as JsonPrimitive).content,
                    (it["status"] as JsonPrimitive).content,
                    (it["error"] as? JsonPrimitive)?.content,
                    (it["history"] as? JsonPrimitive)?.content,
                )
            }
        return TypedPerformResponse(
            run.toString(),
            action,
            // review-273 F6: the key the run performed on, never the replaying request's.
            writes["key"] as? JsonObject ?: key,
            (writes["outcome"] as JsonPrimitive).content,
            rows,
            ref,
            replayed = true,
            expiry = expiry,
        )
    }

    private fun key(
        caller: Caller,
        deployment: String,
        action: String,
        key: String,
    ): String =
        org.tatrman.automation.ledger.idempotencyKey(
            "perform",
            JsonArray(listOf(caller.sub, deployment, action, "perform-v1", key).map(::JsonPrimitive)),
        )

    private fun bad(message: String): Nothing = throw AutomationException(ErrorCode.BAD_REQUEST, message)
}

/**
 * The perform run's journal is its protocol: every in-flight mark, release and outcome is a `writes` entry, durable
 * before the next Door request leaves. D-267 uses those same entries to resume the one linked hold run.
 * Legacy performs still use their existing claim/replay rules.
 */
private class ProtocolJournal(
    private val ledger: Ledger,
    private val run: UUID,
    private val recoverable: Boolean,
) : ApplyJournal {
    override fun begin(
        seqs: List<Int>,
        request: String,
    ) {
        append(
            buildJsonObject {
                put("event", "begin")
                put("request", request)
                putJsonArray("seqs") { seqs.forEach { add(JsonPrimitive(it)) } }
            },
        )
    }

    override fun release(seqs: List<Int>) {
        append(
            buildJsonObject {
                put("event", "release")
                putJsonArray("seqs") { seqs.forEach { add(JsonPrimitive(it)) } }
            },
        )
    }

    override fun record(outcomes: List<RowOutcome>) {
        append(
            buildJsonObject {
                put("event", "outcome")
                putJsonArray("rows") {
                    outcomes.forEach { o ->
                        add(
                            buildJsonObject {
                                put("seq", o.seq)
                                put("status", o.status)
                                put("error", o.error?.let(::JsonPrimitive) ?: JsonNull)
                                o.history?.let { put("history", it) }
                                o.prior?.let { put("prior", it) }
                                if (recoverable) o.committedAt?.let { put("committedAt", it.toString()) }
                            },
                        )
                    }
                }
            },
        )
    }

    fun recorded(subject: String): Map<Int, RowOutcome> =
        buildMap {
            val writes = ledger.readProtocol(run, subject).sections["writes"].orEmpty()
            for (entry in writes) {
                val payload = entry.payload.jsonObject
                when (payload["event"]?.jsonPrimitive?.content) {
                    "begin" -> {
                        val request = payload.getValue("request").jsonPrimitive.content
                        for (seq in payload.getValue("seqs").jsonArray.map { it.jsonPrimitive.int }) {
                            put(seq, RowOutcome(seq, "staged", RowOutcome.IN_FLIGHT, request = request))
                        }
                    }
                    "release" -> payload.getValue("seqs").jsonArray.forEach { remove(it.jsonPrimitive.int) }
                    "outcome" -> {
                        for (value in payload.getValue("rows").jsonArray) {
                            val row = value.jsonObject
                            val seq = row.getValue("seq").jsonPrimitive.int
                            put(
                                seq,
                                RowOutcome(
                                    seq,
                                    row.getValue("status").jsonPrimitive.content,
                                    row["error"]?.jsonPrimitive?.contentOrNull,
                                    row["prior"] as? JsonObject,
                                    row["history"]?.jsonPrimitive?.contentOrNull,
                                    row["committedAt"]?.jsonPrimitive?.contentOrNull?.let(java.time.Instant::parse),
                                    get(seq)?.request,
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun append(payload: JsonObject) {
        ledger.appendProtocol(run, ProtocolSection.WRITES, payload)
    }
}
