// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.engine

import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
)

/** The engine's task history, read as the caller (D-159: the bearer is the caller's, never a service identity). */
fun interface EngineHistory {
    suspend fun task(
        taskId: String,
        bearer: String,
    ): List<HistoricTask>
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
    ): List<HistoricTask> {
        val request =
            HttpRequest
                .newBuilder(URI.create("$base/history/task?taskId=${URLEncoder.encode(taskId, Charsets.UTF_8)}"))
                .timeout(timeout)
                .header("Authorization", "Bearer $bearer")
                .header("Accept", "application/json")
                .GET()
                .build()
        val response =
            try {
                withTimeoutOrNull(timeout.toMillis()) {
                    http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
                } ?: unreachable("the engine's task history did not answer within $timeout")
            } catch (e: AutomationException) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: java.net.http.HttpTimeoutException) {
                unreachable("the engine's task history did not answer within $timeout")
            } catch (e: Exception) {
                unreachable("the engine could not be reached")
            }
        if (response.statusCode() != 200) unreachable("the engine's task history answered ${response.statusCode()}")
        val rows =
            runCatching { json.parseToJsonElement(response.body()) as? JsonArray }.getOrNull()
                ?: unreachable("the engine's task history is not a JSON array")
        return rows.map { row ->
            val o = row as? JsonObject ?: unreachable("a task history row is not an object")

            fun text(name: String) = (o[name] as? JsonPrimitive)?.contentOrNull
            HistoricTask(
                id = text("id") ?: unreachable("a task history row has no id"),
                processInstanceId = text("processInstanceId"),
                assignee = text("assignee"),
                endTime = text("endTime"),
                deleteReason = text("deleteReason"),
            )
        }
    }

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
