// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.read

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings
import org.snakeyaml.engine.v2.schema.CoreSchema
import org.snakeyaml.engine.v2.exceptions.DuplicateKeyException
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException
import java.nio.charset.CharacterCodingException
import org.tatrman.ttr.metadata.registry.RegistrySnapshot
import java.security.MessageDigest
import java.util.Base64

/**
 * Deliberate transport until https://github.com/Collite/ttr-core/issues/314 supplies typed source content.
 * Each value is a whole per-source catalog, including base64 workbooks. Any code iterating exportSidecars
 * for an export bundle MUST skip this prefix; these entries are not public root export sidecars.
 */
internal const val TEMPLATE_SIDECAR_PREFIX = "ttr-server:templates:"

@Serializable
internal data class TemplateCatalog(
    val sourceId: String,
    val priority: Int,
    val version: String = "",
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

internal data class CatalogRead(
    val catalogs: List<TemplateCatalog>,
    val reportsById: Map<String, ServedReport>,
    val formsByTarget: Map<String, JsonObject>,
)

private data class CachedCatalogRead(
    val sidecars: Map<String, String>,
    val read: CatalogRead,
)

@Volatile
private var lastCatalogRead: CachedCatalogRead? = null

internal fun RegistrySnapshot.templateCatalogs(): List<TemplateCatalog> = templateContent().catalogs

internal fun RegistrySnapshot.templateContent(): CatalogRead {
    // A bounded, derived cache: avoid decoding the workbooks on every list/status poll. Identity
    // ties it to this immutable snapshot; a racing old request can only cause a later cache miss.
    lastCatalogRead?.takeIf { it.sidecars === exportSidecars }?.let { return it.read }
    val catalogs =
        exportSidecars.entries
            .filter { it.key.startsWith(TEMPLATE_SIDECAR_PREFIX) }
            .map { Json.decodeFromString<TemplateCatalog>(it.value) }
            .sortedWith(compareBy<TemplateCatalog> { it.priority }.thenBy { it.sourceId })
    val read =
        CatalogRead(
            catalogs,
            catalogs.flatMap { it.reports }.associateBy {
                it.summary
                    .getValue("id")
                    .jsonPrimitive.content
            },
            catalogs.flatMap { it.forms }.associateBy { it.getValue("target").jsonPrimitive.content },
        )
    lastCatalogRead = CachedCatalogRead(exportSidecars, read)
    return read
}

/** YAML validity only: report schema, program validation and workbook rendering belong to studio-report. */
internal fun templateCatalog(
    sourceId: String,
    priority: Int,
    files: Map<String, ByteArray>,
    version: String = "",
    fileIssues: Map<String, String> = emptyMap(),
): TemplateCatalog {
    // Match studio-report's ReportYaml loader without taking its POI/schema-validator dependency.
    val settings =
        LoadSettings
            .builder()
            .setAllowDuplicateKeys(false)
            .setMaxAliasesForCollections(0)
            .setSchema(CoreSchema())
            .build()
    val issues = fileIssues.toSortedMap().map { (path, cause) -> "template $sourceId:$path: $cause" }.toMutableList()

    fun document(path: String): Pair<String, JsonObject>? =
        try {
            val text = files.getValue(path).decodeToString(throwOnInvalidSequence = true)
            val root = Load(settings).loadFromString(text)
            require(root is Map<*, *>) { "not a mapping" }
            text to (root.toTemplateJson() as JsonObject)
        } catch (e: Exception) {
            issues += "template $sourceId:$path: ${yamlCause(e)}"
            null
        }

    val reports =
        files.keys.sorted().mapNotNull { path ->
            val match = REPORT_PATH.matchEntire(path) ?: return@mapNotNull null
            val (yaml, fields) = document(path) ?: return@mapNotNull null
            val workbookPath = path.removeSuffix("report.yaml") + "template.xlsx"
            val workbook = files[workbookPath]
            if (workbook == null) {
                if (workbookPath !in fileIssues) issues += "template $sourceId:$workbookPath: missing workbook"
                return@mapNotNull null
            }
            val summary =
                JsonObject(
                    mapOf(
                        "id" to JsonPrimitive(match.groupValues[1]),
                        "title" to (fields["title"]?.takeUnless { it is JsonNull } ?: JsonObject(emptyMap())),
                        "program" to (fields["program"] ?: JsonNull),
                        "params" to (fields["params"]?.takeUnless { it is JsonNull } ?: JsonArray(emptyList())),
                        "roles" to (fields["roles"]?.takeUnless { it is JsonNull } ?: JsonArray(emptyList())),
                        "refresh" to (fields["refresh"] ?: JsonNull),
                        "source" to JsonPrimitive(path),
                        "version" to (fields["version"]?.takeUnless { it is JsonNull } ?: JsonPrimitive(1)),
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
    return TemplateCatalog(sourceId, priority, version, reports, forms, issues)
}

internal val REPORT_PATH = Regex("model/reports/([^/]+)/report\\.yaml")
internal val FORM_PATH = Regex("model/forms/(.+)\\.yaml")

private fun Any?.toTemplateJson(): JsonElement =
    when (this) {
        null -> JsonNull
        is Map<*, *> -> JsonObject(entries.associate { (key, value) -> key.toString() to value.toTemplateJson() })
        is List<*> -> JsonArray(map { it.toTemplateJson() })
        is Double ->
            if (isFinite()) {
                JsonPrimitive(this)
            } else {
                JsonPrimitive(
                    when {
                        isNaN() -> ".nan"
                        this < 0 -> "-.inf"
                        else -> ".inf"
                    },
                )
            }
        is Number -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        else -> JsonPrimitive(toString())
    }

private fun yamlCause(e: Exception): String {
    val problem = (e as? MarkedYamlEngineException)?.problem ?: e.message.orEmpty()
    return when {
        e is CharacterCodingException -> "not UTF-8"
        e is DuplicateKeyException -> "duplicate key '${problem.removePrefix("found duplicate key ")}'"
        problem.contains("another document") -> "more than one document"
        problem.contains("aliases", ignoreCase = true) -> "aliases are not allowed"
        else -> problem.lineSequence().firstOrNull { it.isNotBlank() } ?: e.javaClass.simpleName
    }.lineSequence().first().trim()
}
