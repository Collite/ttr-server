// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.query

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.Bundle
import org.tatrman.automation.bundle.BundleLoader
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.identity.DeploymentIdentity
import org.tatrman.automation.identity.GrantMap
import org.tatrman.automation.identity.RunAs
import org.tatrman.automation.identity.RunIdentity
import org.tatrman.automation.identity.ServiceTokens
import org.tatrman.automation.ledger.Claim
import org.tatrman.automation.ledger.Ledger
import org.tatrman.automation.ledger.ProtocolSection
import org.tatrman.automation.ledger.RUN_KIND_QUERY
import org.tatrman.automation.ledger.RunOpen
import org.tatrman.automation.runner.BoundValue
import org.tatrman.automation.runner.Door
import org.tatrman.automation.runner.DoorQuery
import org.tatrman.automation.runner.Table
import org.tatrman.automation.runner.duration
import org.tatrman.ttr.metadata.model.Query
import org.tatrman.automation.identity.RunAsRef
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.UUID

@Serializable
data class GolemRequest(
    val agent: String,
    val question: String,
)

/**
 * `POST /automation/query` (D-146, D-128; n8n-nodes contracts §2.4 + the `variables` block of the 2026-11-01
 * amendment). Exactly one lane: [query] (a `def query`, by name or qname), [statement] (SQL over ER entities,
 * `{name}` parameters) or [golem] (a question to an agent). [deployment] only chooses who it runs as (its `run_as`,
 * with a grant) and its world; without it the caller runs it in [world].
 */
@Serializable
data class QueryRequest(
    val query: String? = null,
    val statement: String? = null,
    val golem: GolemRequest? = null,
    val parameters: JsonObject = JsonObject(emptyMap()),
    val variables: JsonObject? = null,
    val world: String? = null,
    val storage: String? = null,
    val deployment: String? = null,
    val asOf: String? = null,
    val limit: Int? = null,
    val idempotencyKey: String? = null,
    val origin: String? = null,
)

@Serializable
data class QueryColumn(
    val name: String,
    val type: String,
)

@Serializable
data class QueryRunRef(
    val id: String,
    val protocolRef: String,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class QueryResponse(
    val columns: List<QueryColumn>,
    val rows: List<JsonObject>,
    val rowCount: Int,
    val truncated: Boolean,
    val variables: JsonObject,
    val verdict: String,
    val run: QueryRunRef,
    val warnings: List<String> = emptyList(),
    @EncodeDefault val replayed: Boolean = false,
    val text: String? = null,
)

/** 200 = answered or replayed, 202 = the key's run is still live (§1.2). */
data class QueryOutcome(
    val status: Int,
    val response: QueryResponse,
)

data class QueryLimits(
    val defaultLimit: Int = 1000,
    val maxLimit: Int = 10_000,
    val maxTableRows: Int = Variables.DEFAULT_MAX_TABLE_ROWS,
    val budget: Duration = Duration.ofSeconds(60),
)

/**
 * The governed query step (S3.1 T2). Every lane runs under the caller (or the deployment's granted `run_as`), is a
 * `query` run in the ledger with a one-shot protocol record — `automation` (who, where), `islands` (the lane, the
 * statement as executed, row count, timing, the variables with exact decimals), `turns` for a Golem lane — and is
 * idempotent per caller-supplied key: a closed key replays its recorded answer without its rows.
 */
class QueryStep(
    private val served: () -> Bundle?,
    private val door: Door,
    private val golem: Golem,
    private val ledger: Ledger,
    private val tokens: ServiceTokens,
    private val doorRoutes: Set<String>,
    private val paused: () -> Boolean,
    private val limits: QueryLimits = QueryLimits(),
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun run(
        request: QueryRequest,
        caller: Caller,
    ): QueryOutcome {
        if (paused()) throw AutomationException(ErrorCode.PAUSED, "automation is paused")
        val lanes = listOfNotNull(request.query, request.statement, request.golem)
        if (lanes.size != 1) statement("name exactly one of query, statement or golem")
        if (request.asOf != null && request.asOf != "now") {
            throw AutomationException(ErrorCode.AS_OF, "a query reads the current data; asOf is not supported")
        }
        val limit = request.limit ?: limits.defaultLimit
        if (limit !in 1..limits.maxLimit) bad("limit must be in 1..${limits.maxLimit}")
        if (request.idempotencyKey != null && request.idempotencyKey.isBlank()) bad("idempotencyKey cannot be blank")
        val specs = Variables.parse(request.variables)
        val bundle = served() ?: throw AutomationException(ErrorCode.QUERY, "no bundle is served")
        val deployment =
            request.deployment?.let { name ->
                bundle.deployments[name]
                    ?: throw AutomationException(ErrorCode.DEPLOYMENT, "no deployment $name is served")
            }
        if (deployment != null && !deployment.enabled) {
            throw AutomationException(ErrorCode.DISABLED, "deployment ${request.deployment} is disabled")
        }
        val world =
            deployment?.let { bundle.worldOf(it)?.name ?: error("loaded deployment has no world") }
                ?: request.world?.also { if (it !in bundle.worlds) bad("no world $it is served") }
                ?: bundle.worlds.keys.singleOrNull()
                ?: bad("name a world: the bundle serves ${bundle.worlds.keys.sorted()}")
        if (deployment != null && request.world != null && request.world != world) {
            bad("deployment ${request.deployment} belongs to world $world")
        }
        val identity = identity(bundle, deployment?.let { request.deployment!! }, caller)
        val lane = lane(bundle, request)
        val storage = if (lane is Lane.Golem) null else storage(world, request.storage)
        val budget =
            deployment
                ?.let { bundle.programs[it.program]?.budgetSync }
                ?.duration()
                ?: limits.budget
        val opening =
            buildJsonObject {
                put("kind", RUN_KIND_QUERY)
                put("lane", lane.name)
                put("world", world)
                storage?.let { put("storage", it) }
                request.deployment?.let { put("deployment", it) }
                put("caller", caller.principal)
                put("runAs", identity.principal)
                put("bundle", bundle.id)
                put("modelFp", bundle.modelFp)
            }
        val claim =
            withContext(Dispatchers.IO) {
                ledger.claimRun(
                    RunOpen(
                        recipe = lane.subject,
                        program = deployment?.program,
                        deployment = request.deployment,
                        bundle = bundle.id,
                        modelFp = bundle.modelFp,
                        port = buildJsonObject { put("parameters", request.parameters) },
                        author = bundle.author,
                        runAs = identity.principal,
                        caller = caller.principal,
                        callerSubject = caller.sub,
                        origin = request.origin ?: "query",
                        idempotencyKey = request.idempotencyKey?.let { key(caller, it) },
                        budgetSync = budget,
                        kind = RUN_KIND_QUERY,
                        openingProtocol = opening,
                    ),
                )
            }
        val run = claim.runId
        val ref = QueryRunRef(run.toString(), "/automation/runs/$run/protocol")
        when (claim) {
            is Claim.Replay -> return QueryOutcome(200, replay(run, caller, ref))
            is Claim.Running ->
                return QueryOutcome(
                    202,
                    QueryResponse(emptyList(), emptyList(), 0, false, JsonObject(emptyMap()), "running", ref),
                )
            is Claim.Claimed -> Unit
        }
        val started = clock.millis()
        try {
            val answer = execute(lane, run, world, storage, bundle, identity, caller, limit, budget)
            val derived = Variables.derive(specs, answer.table, answer.truncated, answer.verdict, limits.maxTableRows)
            val table = answer.table
            val rows = table?.let(::rows).orEmpty()
            val islands =
                buildJsonObject {
                    put("lane", lane.name)
                    lane.describe(this)
                    put("parameters", request.parameters)
                    answer.executed?.let { put("statement", it) }
                    answer.route?.let { put("route", it) }
                    put("verdict", answer.verdict.wire)
                    put("rowCount", rows.size)
                    put("truncated", answer.truncated)
                    put("ms", clock.millis() - started)
                    putJsonArray("warnings") { answer.warnings.sorted().forEach { add(JsonPrimitive(it)) } }
                    put("variables", derived.exact)
                    put("answer", derived.values)
                    table?.let { t -> putJsonArray("columns") { t.columns.forEach { add(column(it.name, it.type)) } } }
                }
            val entries =
                buildList {
                    add(ProtocolSection.ISLANDS to islands)
                    answer.turn?.let { add(ProtocolSection.TURNS to it) }
                }
            withContext(Dispatchers.IO) { ledger.finishAdHocRun(run, entries) }
            return QueryOutcome(
                200,
                QueryResponse(
                    table?.columns?.map { QueryColumn(it.name, it.type) }.orEmpty(),
                    rows,
                    rows.size,
                    answer.truncated,
                    derived.values,
                    answer.verdict.wire,
                    ref,
                    answer.warnings.sorted(),
                    text = answer.text,
                ),
            )
        } catch (e: AutomationException) {
            fail(run, lane, e.code.code, clock.millis() - started)
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            fail(run, lane, "cancelled", clock.millis() - started)
            throw e
        } catch (e: Exception) {
            fail(run, lane, ErrorCode.INTERNAL.code, clock.millis() - started)
            throw e
        }
    }

    private suspend fun fail(
        run: UUID,
        lane: Lane,
        code: String,
        ms: Long,
    ) {
        val entry =
            buildJsonObject {
                put("lane", lane.name)
                lane.describe(this)
                put("error", code)
                put("ms", ms)
            }
        withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            runCatching {
                ledger.finishAdHocRun(
                    run,
                    listOf(ProtocolSection.ISLANDS to entry),
                    JsonObject(
                        mapOf(
                            "code" to JsonPrimitive(code),
                        ),
                    ),
                )
            }
        }
    }

    private suspend fun replay(
        run: UUID,
        caller: Caller,
        ref: QueryRunRef,
    ): QueryResponse {
        val document = withContext(Dispatchers.IO) { ledger.readProtocol(run, caller.sub) }
        val islands =
            document.sections["islands"]
                ?.lastOrNull()
                ?.payload as? JsonObject
                ?: throw AutomationException(ErrorCode.CLOSE_NOT_READY, "the key's run recorded no answer")
        val columns =
            (islands["columns"] as? JsonArray)
                ?.mapNotNull { c ->
                    (c as? JsonObject)?.let { QueryColumn(it.text("name") ?: "", it.text("type") ?: "text") }
                }.orEmpty()
        return QueryResponse(
            columns,
            emptyList(),
            (islands["rowCount"] as? JsonPrimitive)?.longOrNull?.toInt() ?: 0,
            (islands["truncated"] as? JsonPrimitive)?.booleanOrNull ?: false,
            islands["answer"] as? JsonObject ?: JsonObject(emptyMap()),
            islands.text("verdict") ?: QueryVerdict.ANSWERED.wire,
            ref,
            (islands["warnings"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
            replayed = true,
        )
    }

    private data class Answer(
        val table: Table?,
        val truncated: Boolean,
        val verdict: QueryVerdict,
        val warnings: Set<String> = emptySet(),
        val executed: String? = null,
        val route: String? = null,
        val turn: JsonObject? = null,
        val text: String? = null,
    )

    private suspend fun execute(
        lane: Lane,
        run: UUID,
        world: String,
        storage: String?,
        bundle: Bundle,
        identity: RunIdentity,
        caller: Caller,
        limit: Int,
        budget: Duration,
    ): Answer =
        when (lane) {
            is Lane.Golem -> {
                val question = lane.question
                val answer = golem.ask(caller.token, lane.agent, question, run.toString(), budget)
                Answer(
                    table = answer.table,
                    truncated = answer.truncated,
                    verdict = answer.verdict,
                    executed = question,
                    turn =
                        buildJsonObject {
                            put("agent", lane.agent)
                            put("origin", "automation")
                            put("originRef", run.toString())
                            answer.turnId?.let { put("turnId", it) }
                            answer.protocolRef?.let { put("protocolRef", it) }
                            put("verdict", answer.verdict.wire)
                            answer.code?.let { put("code", it) }
                        },
                    text = answer.text,
                )
            }
            is Lane.Sql -> {
                val result =
                    door.query(
                        DoorQuery(
                            run,
                            lane.name,
                            world,
                            requireNotNull(storage),
                            lane.sql,
                            lane.bindings,
                            identity,
                            bundle.modelFp,
                            limit,
                            budget,
                        ),
                    )
                Answer(result.table, result.truncated, QueryVerdict.ANSWERED, result.warnings, lane.sql, result.world)
            }
        }

    private sealed interface Lane {
        val name: String
        val subject: String

        fun describe(into: kotlinx.serialization.json.JsonObjectBuilder)

        data class Sql(
            override val name: String,
            override val subject: String,
            val sql: String,
            val bindings: Map<String, BoundValue>,
            val qname: String?,
        ) : Lane {
            override fun describe(into: kotlinx.serialization.json.JsonObjectBuilder) {
                qname?.let { into.put("query", it) }
            }
        }

        data class Golem(
            val agent: String,
            val question: String,
        ) : Lane {
            override val name = "golem"
            override val subject = "golem:$agent"

            override fun describe(into: kotlinx.serialization.json.JsonObjectBuilder) {
                into.put("agent", agent)
            }
        }
    }

    private fun lane(
        bundle: Bundle,
        request: QueryRequest,
    ): Lane {
        request.query?.let { name ->
            val query = named(bundle, name)
            if (!query.sourceLanguage.equals("sql", ignoreCase = true)) {
                statement("query ${query.qname.dotted()} is ${query.sourceLanguage}, not SQL over entities")
            }
            return Lane.Sql(
                "query",
                query.qname.dotted(),
                query.sourceText,
                bind(query, request.parameters),
                query.qname.dotted(),
            )
        }
        request.statement?.let { sql ->
            if (sql.isBlank()) statement("the statement is empty")
            return Lane.Sql(
                "statement",
                "statement",
                sql,
                request.parameters.mapValues { (n, v) ->
                    inferred(n, v)
                },
                null,
            )
        }
        val golem = requireNotNull(request.golem)
        if (golem.agent.isBlank() || golem.question.isBlank()) statement("golem needs an agent and a question")
        // C-35: `{param}` slots are filled as text; an unfilled slot is the caller's mistake, not the Golem's.
        val question =
            SLOT.replace(golem.question) { m ->
                val value =
                    request.parameters[m.groupValues[1]] ?: statement("parameter ${m.groupValues[1]} is not given")
                (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
            }
        return Lane.Golem(golem.agent, question)
    }

    private fun named(
        bundle: Bundle,
        name: String,
    ): Query {
        val queries = bundle.model.queries.values
        return queries.singleOrNull { it.qname.dotted() == name }
            ?: queries.filter { it.qname.name == name }.let { found ->
                when (found.size) {
                    1 -> found.single()
                    0 -> throw AutomationException(ErrorCode.QUERY, "no query $name is served")
                    else -> statement("query $name is ambiguous: ${found.map { it.qname.dotted() }.sorted()}")
                }
            }
    }

    /** Binds the request's parameters to the query's declared ones; a declared default fills an omitted value. */
    private fun bind(
        query: Query,
        given: JsonObject,
    ): Map<String, BoundValue> {
        val declared = query.parameters.associateBy { it.name }
        (given.keys - declared.keys).firstOrNull()?.let {
            statement(
                "query ${query.qname.dotted()} has no parameter $it",
            )
        }
        return declared.mapValues { (name, p) ->
            val raw = given[name] ?: p.defaultValue?.let(::JsonPrimitive) ?: statement("parameter $name is required")
            BoundValue(p.type, typed(name, p.type, raw))
        }
    }

    private fun typed(
        name: String,
        type: String,
        raw: JsonElement,
    ): Any? {
        if (raw is JsonNull) return null
        val p = raw as? JsonPrimitive ?: statement("parameter $name is not a scalar")
        return when (type.lowercase()) {
            "int", "integer", "bigint" ->
                p.longOrNull ?: p.contentOrNull?.toLongOrNull()
                    ?: statement("parameter $name is an integer")
            "float", "double", "decimal", "numeric" ->
                p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull() ?: statement("parameter $name is a number")
            "bool", "boolean" -> p.booleanOrNull ?: statement("parameter $name is a boolean")
            else -> p.content
        }
    }

    private fun inferred(
        name: String,
        raw: JsonElement,
    ): BoundValue {
        if (raw is JsonNull) return BoundValue("text", null)
        val p = raw as? JsonPrimitive ?: statement("parameter $name is not a scalar")
        return when {
            p.isString -> BoundValue("text", p.content)
            p.booleanOrNull != null -> BoundValue("bool", p.booleanOrNull)
            p.longOrNull != null -> BoundValue("int", p.longOrNull)
            else -> BoundValue("float", p.doubleOrNull)
        }
    }

    /**
     * The door route of the read: the named [storage], or the world's only routed storage that is not the ledger's
     * (`automation_*`). Never a guess between two.
     */
    private fun storage(
        world: String,
        storage: String?,
    ): String {
        val routed = doorRoutes.filter { it.startsWith("$world/") }.map { it.substringAfter('/') }
        if (storage != null) {
            if (storage !in routed) {
                throw AutomationException(
                    ErrorCode.DOOR_REFUSED,
                    "no door route for $world/$storage",
                    buildJsonObject { put("code", "storage-unmapped") },
                )
            }
            return storage
        }
        return routed.filterNot { it.startsWith("automation") }.singleOrNull()
            ?: bad("name a storage: world $world routes ${routed.sorted()}")
    }

    private fun identity(
        bundle: Bundle,
        deployment: String?,
        caller: Caller,
    ): RunIdentity {
        if (deployment == null) return RunIdentity(caller, RunAsRef.Caller, caller.principal, null)
        val d = bundle.deployments.getValue(deployment)
        val world = bundle.worldOf(d) ?: error("loaded deployment has no world")
        return RunAs(GrantMap(world.grants, world.identities, world.roles), world.identities, tokens).resolve(
            caller,
            DeploymentIdentity(deployment, d.program, BundleLoader.runAs(d.runAs), bundle.author),
        )
    }

    private fun rows(table: Table): List<JsonObject> =
        table.rows.map { row ->
            JsonObject(
                table.columns.withIndex().associate { (i, c) ->
                    c.name to Variables.json(row[i], exact = false)
                },
            )
        }

    private fun column(
        name: String,
        type: String,
    ) = buildJsonObject {
        put("name", name)
        put("type", type)
    }

    private fun key(
        caller: Caller,
        key: String,
    ): String {
        // The ledger's key index is global: namespace the caller's key, as Open does.
        val material =
            Json.encodeToString(
                JsonArray.serializer(),
                JsonArray(listOf(JsonPrimitive(caller.sub), JsonPrimitive("query-v1"), JsonPrimitive(key))),
            )
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return "query:" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun statement(message: String): Nothing = throw AutomationException(ErrorCode.STATEMENT, message)

    private fun bad(message: String): Nothing = throw AutomationException(ErrorCode.BAD_REQUEST, message)

    private companion object {
        val SLOT = Regex("\\{([A-Za-z_][A-Za-z0-9_]*)}")
    }
}
