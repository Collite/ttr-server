// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.engine

import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.tatrman.automation.bundle.DeployedProcess
import org.tatrman.automation.bundle.Fingerprints
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** A best-effort lookup as the authenticated reader, under one engine deadline. */
class DeploymentDefinitions(
    url: String,
    ca: String?,
    private val timeout: Duration,
    private val http: HttpClient = HttpEngineHistory.client(ca, timeout),
) {
    private val base = url.trimEnd('/')

    suspend fun idOf(
        process: DeployedProcess,
        bearer: String,
    ): String? {
        // Send the caller's bearer only to the configured engine, and only for that world's executor.
        if (process.engineUrl.trimEnd('/') != base) return null
        return withTimeoutOrNull(timeout.toMillis()) {
            try {
                findId(process, bearer)
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }

    private suspend fun findId(
        process: DeployedProcess,
        bearer: String,
    ): String? {
        val definitions =
            list(
                "process-definition",
                bearer,
                "key" to process.key,
                "resourceName" to process.resource,
            ) ?: return null
        if (definitions.isEmpty()) return null
        val deployments =
            list(
                "deployment",
                bearer,
                "name" to process.deploymentName,
                "source" to process.deploymentSource,
            ) ?: return null
        val deploymentIds =
            deployments
                .map { row ->
                    if (row.text("name") != process.deploymentName ||
                        row.text("source") != process.deploymentSource
                    ) {
                        return null
                    }
                    row.text("id")?.takeIf { it.isNotBlank() } ?: return null
                }.toSet()
        var match: String? = null
        for (definition in definitions) {
            if (definition.text("key") != process.key || definition.text("resource") != process.resource) return null
            val deploymentId = definition.text("deploymentId")?.takeIf { it.isNotBlank() } ?: return null
            if (deploymentId !in deploymentIds) continue
            val id = definition.text("id")?.takeIf { it.isNotBlank() && it != "." && it != ".." } ?: return null
            val segment = encode(id).replace("+", "%20")
            val resource = get("process-definition/$segment/xml", bearer) as? JsonObject ?: return null
            if (resource.text("id") != id) return null
            val xml = resource.text("bpmn20Xml") ?: return null
            if (Fingerprints.sha256(xml) == process.fingerprint) {
                if (match != null) return null
                match = id
            }
        }
        return match
    }

    private suspend fun list(
        path: String,
        bearer: String,
        vararg filters: Pair<String, String>,
    ): List<JsonObject>? {
        val params =
            (filters.toList() + listOf("withoutTenantId" to "true", "maxResults" to "1001"))
                .joinToString("&") { (key, value) -> "$key=${encode(value)}" }
        val rows = get("$path?$params", bearer) as? JsonArray ?: return null
        if (rows.size > 1000) return null
        return rows.map { (it as? JsonObject)?.takeIf { row -> row["tenantId"] == JsonNull } ?: return null }
    }

    private suspend fun get(
        path: String,
        bearer: String,
    ): JsonElement? {
        val request =
            HttpRequest
                .newBuilder(URI.create("$base/$path"))
                .timeout(timeout)
                .header("Authorization", "Bearer $bearer")
                .header("Accept", "application/json")
                .GET()
                .build()
        val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        return if (response.statusCode() == 200) Json.parseToJsonElement(response.body()) else null
    }

    private fun JsonObject.text(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8)
}
