// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.engine

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** One row of the engine's `GET /history/task` (Camunda 7 REST, unchanged in CIB seven and Operaton). */
data class HistoricTask(
    val id: String,
    val processInstanceId: String?,
    val assignee: String?,
    val endTime: String?,
    val deleteReason: String?,
    val due: String? = null,
    val processDefinitionId: String? = null,
    val taskDefinitionKey: String? = null,
    val executionId: String? = null,
    val startTime: String? = null,
)

data class HistoricActivity(
    val id: String,
    val activityId: String?,
    val type: String?,
    val instanceId: String?,
    val definitionId: String?,
    val parentId: String?,
    val taskId: String?,
    val executionId: String?,
    val canceled: Boolean?,
    val start: String?,
    val end: String?,
)

data class HistoricVariable(
    val name: String?,
    val value: String?,
    val instanceId: String?,
    val taskId: String?,
    val activityInstanceId: String?,
)

data class HistoricProcess(
    val id: String?,
    val definitionId: String?,
    val businessKey: String?,
    val state: String?,
)

data class ExpiryHistory(
    val process: HistoricProcess?,
    val task: HistoricTask,
    val taskActivities: List<HistoricActivity>,
    val timerActivities: List<HistoricActivity>,
    val taskVariables: List<HistoricVariable>,
    val runVariables: List<HistoricVariable>,
)

/** The engine's task history, read as the caller (D-159: the bearer is the caller's, never a service identity). */
fun interface EngineHistory {
    suspend fun task(
        taskId: String,
        bearer: String,
    ): List<HistoricTask>

    suspend fun expiry(
        instanceId: String,
        taskId: String,
        timerActivityId: String,
        bearer: String,
    ): ExpiryHistory? = unreachable("no engine expiry history is configured")
}

/** No engine configured: an answer run cannot be verified, so it is refused, never passed. */
object NoEngine : EngineHistory {
    override suspend fun task(
        taskId: String,
        bearer: String,
    ): List<HistoricTask> = unreachable("no process engine is configured (automation.engine.url)")
}

/**
 * `GET {url}/history/task?taskId=…` with the caller's bearer, under one deadline ([timeout], 5 s by default). A
 * timeout, a refused connection or any non-200 answer is `503 engine-unreachable`: the check fails closed.
 * [ca] is a PEM bundle that signs the engine's TLS certificate (`automation.engine.ca`); unset = the JVM's trust.
 */
class HttpEngineHistory(
    url: String,
    ca: String? = null,
    private val timeout: Duration = Duration.ofSeconds(5),
    private val http: HttpClient = client(ca, timeout),
) : EngineHistory {
    private val base = url.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun task(
        taskId: String,
        bearer: String,
    ): List<HistoricTask> =
        read("task", bearer, "taskId" to taskId).map { row ->
            HistoricTask(
                id = row.text("id") ?: unreachable("a task history row has no id"),
                processInstanceId = row.text("processInstanceId"),
                assignee = row.text("assignee"),
                endTime = row.text("endTime"),
                deleteReason = row.text("deleteReason"),
                due = row.text("due"),
                processDefinitionId = row.text("processDefinitionId"),
                taskDefinitionKey = row.text("taskDefinitionKey"),
                executionId = row.text("executionId"),
                startTime = row.text("startTime"),
            )
        }

    override suspend fun expiry(
        instanceId: String,
        taskId: String,
        timerActivityId: String,
        bearer: String,
    ): ExpiryHistory? {
        val result =
            withTimeoutOrNull(timeout.toMillis()) {
                // Box an absent result separately from a timeout.
                Result.success(readExpiry(instanceId, taskId, timerActivityId, bearer))
            } ?: unreachable("the engine's expiry history did not answer within $timeout")
        return result.getOrThrow()
    }

    private suspend fun readExpiry(
        instanceId: String,
        taskId: String,
        timerActivityId: String,
        bearer: String,
    ): ExpiryHistory? {
        val task = task(taskId, bearer).singleOrNull() ?: return null
        val execution = task.executionId
        if (task.id != taskId || task.processInstanceId != instanceId || execution.isNullOrBlank()) return null
        return coroutineScope {
            val taskActivities =
                async {
                    read(
                        "activity-instance",
                        bearer,
                        "processInstanceId" to instanceId,
                        "executionId" to execution,
                        "activityType" to "userTask",
                        "maxResults" to "1001",
                    ).map(::activity)
                }
            val timers =
                async {
                    read(
                        "activity-instance",
                        bearer,
                        "processInstanceId" to instanceId,
                        "activityId" to timerActivityId,
                        "maxResults" to "1001",
                    ).map(::activity)
                }
            val taskVariables = async { variables(bearer, "taskIdIn" to taskId) }
            val runVariables =
                async { variables(bearer, "processInstanceId" to instanceId, "variableName" to "ttrRunId") }
            val process =
                async {
                    read("process-instance", bearer, "processInstanceId" to instanceId, "maxResults" to "2")
                        .singleOrNull()
                        ?.let {
                            HistoricProcess(
                                it.text("id"),
                                it.text("processDefinitionId"),
                                it.text("businessKey"),
                                it.text("state"),
                            )
                        }
                }
            ExpiryHistory(
                process.await(),
                task,
                taskActivities.await(),
                timers.await(),
                taskVariables.await(),
                runVariables.await(),
            )
        }
    }

    private suspend fun variables(
        bearer: String,
        vararg query: Pair<String, String>,
    ): List<HistoricVariable> =
        read(
            "variable-instance",
            bearer,
            *query,
            "deserializeValues" to "false",
            "includeDeleted" to "true",
            "maxResults" to "1001",
        ).map { row ->
            HistoricVariable(
                row.text("name"),
                row.text("value")?.takeIf { row.text("type") == "String" },
                row.text("processInstanceId"),
                row.text("taskId"),
                row.text("activityInstanceId"),
            )
        }

    private fun activity(row: JsonObject): HistoricActivity =
        HistoricActivity(
            row.text("id") ?: unreachable("an activity history row has no id"),
            row.text("activityId"),
            row.text("activityType"),
            row.text("processInstanceId"),
            row.text("processDefinitionId"),
            row.text("parentActivityInstanceId"),
            row.text("taskId"),
            row.text("executionId"),
            (row["canceled"] as? JsonPrimitive)?.booleanOrNull,
            row.text("startTime"),
            row.text("endTime"),
        )

    private suspend fun read(
        resource: String,
        bearer: String,
        vararg query: Pair<String, String>,
    ): List<JsonObject> {
        val params =
            query.joinToString("&") { (name, value) ->
                "$name=${URLEncoder.encode(value, Charsets.UTF_8)}"
            }
        val request =
            HttpRequest
                .newBuilder(URI.create("$base/history/$resource?$params"))
                .timeout(timeout)
                .header("Authorization", "Bearer $bearer")
                .header("Accept", "application/json")
                .GET()
                .build()
        val response =
            try {
                withTimeoutOrNull(timeout.toMillis()) {
                    http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
                } ?: unreachable("the engine's $resource history did not answer within $timeout")
            } catch (e: AutomationException) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.net.http.HttpTimeoutException) {
                unreachable("the engine's $resource history did not answer within $timeout")
            } catch (e: Exception) {
                unreachable("the engine could not be reached")
            }
        if (response.statusCode() !=
            200
        ) {
            unreachable("the engine's $resource history answered ${response.statusCode()}")
        }
        val rows =
            runCatching { json.parseToJsonElement(response.body()) as? JsonArray }.getOrNull()
                ?: unreachable("the engine's $resource history is not a JSON array")
        if (rows.size > 1000) unreachable("the engine's $resource history exceeded the verification limit")
        return rows.map { it as? JsonObject ?: unreachable("a $resource history row is not an object") }
    }

    private fun JsonObject.text(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull

    companion object {
        fun client(
            ca: String?,
            timeout: Duration,
        ): HttpClient =
            HttpClient
                .newBuilder()
                .connectTimeout(timeout)
                .apply { ca?.let { sslContext(sslContext(Path.of(it))) } }
                .build()

        /** A TLS context that trusts exactly the certificates of a PEM bundle. */
        fun sslContext(pem: Path): SSLContext {
            val certificates =
                Files.newInputStream(pem).use { CertificateFactory.getInstance("X.509").generateCertificates(it) }
            require(certificates.isNotEmpty()) { "automation.engine.ca holds no certificate" }
            val store =
                KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                    load(null, null)
                    certificates.forEachIndexed { i, c -> setCertificateEntry("engine-ca-$i", c) }
                }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            return SSLContext.getInstance("TLS").apply { init(null, trust.trustManagers, null) }
        }
    }
}

internal fun unreachable(message: String): Nothing = throw AutomationException(ErrorCode.ENGINE_UNREACHABLE, message)
