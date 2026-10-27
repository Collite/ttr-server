// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.read

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.tatrman.ttr.metadata.registry.RegistrySnapshot
import java.security.MessageDigest
import java.util.Base64

/** Stored in the existing immutable snapshot sidecars, so refresh publishes model and content together. */
internal const val TEMPLATE_SIDECAR_PREFIX = "ttr-server:templates:"

@Serializable
internal data class TemplateCatalog(
    val sourceId: String,
    val priority: Int,
    val reports: List<ServedReport> = emptyList(),
    val forms: List<JsonObject> = emptyList(),
    val issues: List<String> = emptyList(),
)

@Serializable
internal data class ServedReport(
    val summary: JsonObject,
    val yaml: String,
    val template: String,
    val sha256: String,
)

private data class CatalogRead(
    val sidecars: Map<String, String>,
    val catalogs: List<TemplateCatalog>,
)

@Volatile
private var lastCatalogRead: CatalogRead? = null

internal fun RegistrySnapshot.templateCatalogs(): List<TemplateCatalog> {
    // A bounded, derived cache: avoid decoding the workbooks on every list/status poll. Identity
    // ties it to this immutable snapshot; a racing old request can only cause a later cache miss.
    lastCatalogRead?.takeIf { it.sidecars === exportSidecars }?.let { return it.catalogs }
    val catalogs =
        exportSidecars.entries
            .filter { it.key.startsWith(TEMPLATE_SIDECAR_PREFIX) }
            .map { Json.decodeFromString<TemplateCatalog>(it.value) }
            .sortedWith(compareBy<TemplateCatalog> { it.priority }.thenBy { it.sourceId })
    lastCatalogRead = CatalogRead(exportSidecars, catalogs)
    return catalogs
}

/** YAML validity only: report schema, program validation and workbook rendering belong to studio-report. */
internal fun templateCatalog(
    sourceId: String,
    priority: Int,
    files: Map<String, ByteArray>,
): TemplateCatalog {
    val yamlMapper =
        ObjectMapper(YAMLFactory().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION))
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    val issues = mutableListOf<String>()

    fun document(path: String): Pair<String, JsonObject>? =
        try {
            val text = files.getValue(path).decodeToString(throwOnInvalidSequence = true)
            val node = yamlMapper.readTree(text)
            require(node != null && node.isObject) { "expected a YAML mapping" }
            text to (Json.parseToJsonElement(node.toString()) as JsonObject)
        } catch (_: Exception) {
            issues += "template $path: invalid YAML mapping"
            null
        }

    val reports =
        files.keys.sorted().mapNotNull { path ->
            val match = REPORT_PATH.matchEntire(path) ?: return@mapNotNull null
            val (yaml, fields) = document(path) ?: return@mapNotNull null
            val workbookPath = path.removeSuffix("report.yaml") + "template.xlsx"
            val workbook = files[workbookPath]
            if (workbook == null) {
                issues += "template $workbookPath: missing or not a regular file"
                return@mapNotNull null
            }
            val summary =
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(match.groupValues[1]),
                        "title" to (fields["title"] ?: JsonObject(emptyMap())),
                        "program" to (fields["program"] ?: JsonNull),
                        "params" to (fields["params"] ?: JsonArray(emptyList())),
                        "roles" to (fields["roles"] ?: JsonArray(emptyList())),
                        "refresh" to (fields["refresh"] ?: JsonNull),
                        "source" to JsonPrimitive(path),
                        "version" to (fields["version"] ?: JsonPrimitive(1)),
                    ),
                )
            ServedReport(
                summary,
                yaml,
                Base64.getEncoder().encodeToString(workbook),
                MessageDigest.getInstance("SHA-256").digest(workbook).joinToString("") { "%02x".format(it) },
            )
        }
    val forms =
        files.keys.sorted().mapNotNull { path ->
            val match = FORM_PATH.matchEntire(path) ?: return@mapNotNull null
            val (_, fields) = document(path) ?: return@mapNotNull null
            JsonObject(fields + ("target" to JsonPrimitive(match.groupValues[1])) + ("source" to JsonPrimitive(path)))
        }
    return TemplateCatalog(sourceId, priority, reports, forms, issues)
}

internal val REPORT_PATH = Regex("model/reports/([^/]+)/report\\.yaml")
internal val FORM_PATH = Regex("model/forms/(.+)\\.yaml")
