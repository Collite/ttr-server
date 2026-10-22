// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.bundle

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A recipe bundle's `manifest.json` (ttr-core AU `contracts.md` §5.1, `schemaVersion: 3`) — the fields this
 * service reads, aligned with grammar/v0.17.4 (including D-136 parts/combines). This external DTO remains
 * independent of the compiler's strict internal reader; adoption of the published emitter DTO is tracked in S1.1.
 * The reader is LENIENT, as an external reader of the manifest must be
 * (ttr-core contracts §6's reader policy): a key it does not know is skipped and a `null` where it expects a
 * default takes the default, so a toolchain that adds a field never breaks a deployed Server.
 */
@Serializable
data class RecipeManifest(
    val schemaVersion: Int,
    val ttrpVersion: Int = 1,
    val toolchain: String = "",
    val program: String = "",
    val world: ManifestWorld? = null,
    val islands: List<ManifestIsland> = emptyList(),
    val waves: List<List<String>> = emptyList(),
    val connections: List<String> = emptyList(),
    val recipe: ManifestRecipe? = null,
    val files: Map<String, String> = emptyMap(),
) {
    companion object {
        const val SCHEMA_VERSION = 3

        val JSON =
            Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
                explicitNulls = false
                encodeDefaults = true
            }

        fun parse(text: String): RecipeManifest = JSON.decodeFromString(serializer(), text)
    }
}

@Serializable
data class ManifestWorld(
    val qname: String,
    val fingerprint: String = "",
)

/** One island; [outputs] = one statement file per OUT port (a decision's routes are islands too, §5.2). */
@Serializable
data class ManifestIsland(
    val name: String,
    val engine: String = "",
    val file: String = "",
    val sha256: String = "",
    val outputs: List<ManifestOutput> = emptyList(),
    val params: List<String> = emptyList(),
    val schema: String = "er",
)

@Serializable
data class ManifestOutput(
    val port: String,
    val file: String,
    val sha256: String = "",
    val columns: List<ManifestColumn> = emptyList(),
    val params: List<String> = emptyList(),
    val keyed: Boolean = false,
    val storage: String? = null,
    val combine: ManifestCombine? = null,
)

/** The `recipe` object of manifest v3 (§5.1). */
@Serializable
data class ManifestRecipe(
    val name: String,
    val key: List<String> = emptyList(),
    val asOf: String = "now",
    val document: String? = null,
    val parameters: List<ManifestParameter> = emptyList(),
    val roles: ManifestRoles? = null,
    val wiring: List<ManifestWiring> = emptyList(),
    val kinds: List<String> = emptyList(),
    val mode: String? = null,
    val windows: List<JsonObject> = emptyList(),
    val verdictSchema: ManifestVerdictSchema? = null,
    val objectsRead: List<String> = emptyList(),
    val fingerprints: ManifestFingerprints = ManifestFingerprints(),
    val protocol: String? = null,
    val bindings: List<ManifestBinding> = emptyList(),
    val parts: List<ManifestPart> = emptyList(),
)

@Serializable
data class ManifestParameter(
    val name: String,
    val type: String = "",
    val value: String? = null,
)

@Serializable
data class ManifestRoles(
    val candidates: String? = null,
    val contexts: List<ManifestContext> = emptyList(),
    val decision: String? = null,
    val routes: List<ManifestRoute> = emptyList(),
    val switchMode: String? = null,
    val elseRoute: String? = null,
)

@Serializable
data class ManifestContext(
    val name: String,
    @SerialName("class") val contextClass: String? = null,
    val kind: String? = null,
)

@Serializable
data class ManifestRoute(
    val name: String,
    val because: String? = null,
    val detail: List<String> = emptyList(),
    val turnFed: Boolean = false,
)

/** `verdict.release -> release { order: order_number }` — [columns] maps the action's columns to values. */
@Serializable
data class ManifestWiring(
    val routes: List<String> = emptyList(),
    val action: String,
    val columns: Map<String, String> = emptyMap(),
    @SerialName("when") val whenCondition: String? = null,
)

@Serializable
data class ManifestVerdictSchema(
    val entity: String,
    val columns: List<JsonObject> = emptyList(),
    val detail: String? = null,
    val current: String? = null,
)

/** D-13/D-60: `text` = the logic, `values` = the parameter values (a value change re-approves `values` only). */
@Serializable
data class ManifestFingerprints(
    val text: String = "",
    val values: String = "",
)

@Serializable
data class ManifestColumn(
    val name: String,
    val type: String,
)

@Serializable
data class ManifestPart(
    val name: String,
    val storage: String,
    val engine: String = "",
    val file: String,
    val sha256: String = "",
    val columns: List<ManifestColumn> = emptyList(),
    val params: List<String> = emptyList(),
    val keyed: Boolean = false,
    val schema: String = "er",
)

@Serializable
data class ManifestCombine(
    val file: String,
    val sha256: String = "",
    val parts: List<String> = emptyList(),
    val params: List<String> = emptyList(),
    val engine: String = "duckdb",
    val dialect: String = "duckdb",
)

@Serializable
data class ManifestBinding(
    val name: String,
    val type: String,
    val source: String,
    val derive: ManifestDerive? = null,
)

@Serializable
data class ManifestDerive(
    val base: String,
    val op: String,
    val amount: String,
    val unit: String,
)
