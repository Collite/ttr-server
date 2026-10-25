// SPDX-License-Identifier: Apache-2.0
package org.tatrman.health.status

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * FO-P5.S2.T1 — reads the model fingerprint (`model_version`) from metadata `GET /status`. Fails soft:
 * any error (metadata down, undeployed, malformed) yields null, so the status page still renders. Read-only
 * — a plain GET, no write path.
 */
class ModelFingerprintProbe(
    private val statusUrl: String,
    private val client: HttpClient,
) {
    private val logger = LoggerFactory.getLogger(ModelFingerprintProbe::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(): String? =
        try {
            val body = client.get(statusUrl).bodyAsText()
            json
                .parseToJsonElement(body)
                .jsonObject["model_version"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            logger.debug("model fingerprint probe failed for {}: {}", statusUrl, e.message)
            null
        }
}
