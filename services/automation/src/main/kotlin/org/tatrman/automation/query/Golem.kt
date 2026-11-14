// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.query

import kotlinx.coroutines.future.await
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.ManifestColumn
import org.tatrman.automation.runner.Table
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * What one Golem turn answered: [verdict] `answered` with a [table] (TABLE envelopes only), `clarify` (a pending
 * clarification — never answered here, v1) or `refused` (the envelope's error code, or a failed turn). [turnId] and
 * [protocolRef] are the turn's own references for the run's `turns` protocol section.
 */
data class GolemAnswer(
    val verdict: QueryVerdict,
    val table: Table? = null,
    val truncated: Boolean = false,
    val turnId: String? = null,
    val protocolRef: JsonElement? = null,
    val code: String? = null,
    val text: String? = null,
)

/** The Golem lane's seam (D-169): the caller's [bearer] asks [agent]; the turn is an `automation` turn of [originRef]. */
fun interface Golem {
    suspend fun ask(
        bearer: String,
        agent: String,
        question: String,
        originRef: String,
        timeout: Duration,
    ): GolemAnswer
}

/** No iris-bff configured: the lane refuses (`502 golem-unavailable`); the named and inline lanes are unaffected. */
object NoGolem : Golem {
    override suspend fun ask(
        bearer: String,
        agent: String,
        question: String,
        originRef: String,
        timeout: Duration,
    ): GolemAnswer = throw AutomationException(ErrorCode.GOLEM_UNAVAILABLE, "no Golem (iris-bff) is configured")
}

/**
 * iris-bff's headless turn (kantheon#225, HE-P0 S0.3): `POST /v1/session` then the sync `POST /v1/chat/turn` with
 * `origin: automation` and `originRef` = the run id, as the caller (✅AU-P3-2, D-169) — no service identity for turns.
 * The answer is iris's last `envelope`/`error` event as proto-JSON; a TABLE's rows are the envelope's `contentJson`.
 */
class IrisGolem(
    baseUrl: String,
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
) : Golem {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun ask(
        bearer: String,
        agent: String,
        question: String,
        originRef: String,
        timeout: Duration,
    ): GolemAnswer {
        val session =
            post("/v1/session", "{}", bearer, timeout)
                .let { (status, body) ->
                    if (status !in 200..299) unavailable("iris-bff refused the session ($status)")
                    parse(body)["sessionId"]?.let { (it as? JsonPrimitive)?.contentOrNull }
                        ?: unavailable("iris-bff answered no sessionId")
                }
        val turn =
            buildJsonObject {
                put("sessionId", session)
                put("question", question)
                put("agentId", agent)
                put("origin", "automation")
                put("originRef", originRef)
            }
        val (status, body) = post("/v1/chat/turn", turn.toString(), bearer, timeout)
        if (status == 403) {
            throw AutomationException(
                ErrorCode.IDENTITY,
                "the Golem refused this caller",
                buildJsonObject {
                    put("reason", "golem-forbidden")
                },
            )
        }
        if (status == 204) unavailable("the Golem turn ended without an answer")
        if (status !in 200..299) unavailable("iris-bff refused the turn ($status)")
        return answer(parse(body))
    }

    internal fun answer(event: JsonObject): GolemAnswer {
        val turnId = event.string("turnId")
        event["error"]?.let { e ->
            val code = (e as? JsonObject)?.string("code") ?: "golem-turn-failed"
            return GolemAnswer(QueryVerdict.REFUSED, turnId = turnId, code = code)
        }
        val envelope = event["envelope"] as? JsonObject ?: unavailable("the Golem turn answered no envelope")
        val ref = (envelope["explanation"] as? JsonObject)?.get("protocolRef")
        val id = envelope.string("turnId") ?: turnId
        if (envelope["pendingClarification"] is JsonObject) {
            return GolemAnswer(QueryVerdict.CLARIFY, turnId = id, protocolRef = ref, text = envelope.string("text"))
        }
        envelope.string("errorCode")?.takeIf { it.isNotBlank() }?.let { code ->
            return GolemAnswer(
                QueryVerdict.REFUSED,
                turnId = id,
                protocolRef = ref,
                code = code,
                text = envelope.string("text"),
            )
        }
        val format = envelope["format"] as? JsonObject
        if (format?.string("kind") != "TABLE") {
            throw AutomationException(
                ErrorCode.GOLEM_NO_TABLE,
                "the Golem answered without a table",
                buildJsonObject {
                    put("turnId", id)
                    put("kind", format?.string("kind") ?: "PLAINTEXT")
                },
            )
        }
        val rows =
            envelope
                .string(
                    "contentJson",
                )?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() } as? JsonArray
                ?: unavailable("the Golem's table rows could not be read")
        val headers =
            ((format["table"] as? JsonObject)?.get("headers") as? JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.string("name") }
                ?.takeIf { it.isNotEmpty() }
                ?: rows.firstOrNull()?.let { (it as? JsonObject)?.keys?.toList() }
                ?: emptyList()
        val table = table(headers, rows.map { it as? JsonObject ?: unavailable("a Golem row is not an object") })
        val total = ((envelope["currentView"] as? JsonObject)?.get("totalRows") as? JsonPrimitive)?.longOrNull
        return GolemAnswer(
            QueryVerdict.ANSWERED,
            table = table,
            truncated = total != null && total > table.rows.size,
            turnId = id,
            protocolRef = ref,
        )
    }

    private fun table(
        headers: List<String>,
        rows: List<JsonObject>,
    ): Table {
        val values = rows.map { row -> headers.map { h -> scalar(row[h]) } }
        val columns =
            headers.mapIndexed { i, h ->
                val present = values.mapNotNull { it[i] }
                val type =
                    when {
                        present.isEmpty() -> "text"
                        present.all { it is Long } -> "int"
                        present.all { it is Long || it is Double } -> "float"
                        present.all { it is Boolean } -> "bool"
                        else -> "text"
                    }
                ManifestColumn(h, type)
            }
        val typed =
            values.map { r ->
                r.mapIndexed { i, v ->
                    when {
                        v == null -> null
                        columns[i].type == "float" -> (v as Number).toDouble()
                        columns[i].type == "text" && v !is String -> v.toString()
                        else -> v
                    }
                }
            }
        return Table(columns, typed)
    }

    private fun scalar(v: JsonElement?): Any? {
        val p = v as? JsonPrimitive ?: return v?.toString()
        if (p is kotlinx.serialization.json.JsonNull) return null
        if (p.isString) return p.content
        return p.booleanOrNull ?: p.longOrNull ?: p.doubleOrNull ?: p.content
    }

    private suspend fun post(
        path: String,
        body: String,
        bearer: String,
        timeout: Duration,
    ): Pair<Int, String> {
        val request =
            HttpRequest
                .newBuilder(URI.create(base + path))
                .timeout(timeout)
                .header("Authorization", "Bearer $bearer")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        val response =
            try {
                http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            } catch (e: java.net.http.HttpTimeoutException) {
                throw AutomationException(ErrorCode.BUDGET, "the Golem turn exceeded the query's budget")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                unavailable("iris-bff could not be reached")
            }
        return response.statusCode() to response.body()
    }

    private fun parse(body: String): JsonObject =
        runCatching {
            json
                .parseToJsonElement(
                    body,
                ).jsonObject
        }.getOrElse { unavailable("iris-bff answered no JSON object") }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private fun unavailable(message: String): Nothing = throw AutomationException(ErrorCode.GOLEM_UNAVAILABLE, message)
}
