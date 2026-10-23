// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.ktor.server.plugins.BadRequestException
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.tatrman.automation.identity.caller
import org.tatrman.automation.runner.RunContext
import org.tatrman.automation.runner.RunSessions
import org.tatrman.automation.staging.StagedOutput
import java.util.UUID

@Serializable
data class OutputPreview(
    val rows: List<JsonObject>,
    val rowCount: Long,
    val ref: String,
    @SerialName("class") val contextClass: String? = null,
    val keyed: Boolean,
    val truncated: Boolean,
)

/** Multi-port islands retain each port's schema and reference; single-port replies keep the v1 flat shape. */
@Serializable
data class IslandPreview(
    val outputs: Map<String, OutputPreview>,
)

/**
 * Authenticated, bounded read handlers. Kept unmounted until the production runtime and lifecycle are wired.
 * `parts/candidates` and `parts/decision` resolve roles; contexts and islands resolve exact manifest names.
 * Storage parts are internal shared inputs and never become public part names.
 */
fun Route.runReadRoutes(
    runs: RunSessions,
    displayRows: Int,
) {
    require(displayRows > 0) { "displayRows must be positive" }

    fun select(
        context: RunContext,
        name: String,
        role: Boolean,
    ): String {
        val recipe = context.recipe
        val roles = recipe.recipe.roles
        val island =
            if (role) {
                when (name) {
                    "candidates" -> roles?.candidates
                    "decision" -> roles?.decision
                    else -> roles?.contexts?.singleOrNull { it.name == name }?.name
                }
            } else {
                name
            }
        if (island == null || recipe.manifest.islands.none { it.name == island }) {
            throw BadRequestException("unknown ${if (role) "part" else "island"}")
        }
        return island
    }

    for (kind in listOf("parts", "islands")) {
        post("/automation/runs/{id}/$kind/{name}") {
            val id = call.runId()
            val name = call.parameters["name"].orEmpty()
            val result =
                runs.read(id, call.caller(), { select(it, name, kind == "parts") }) { runner, island ->
                    val recipe = runner.context.recipe
                    val contextClass =
                        recipe.recipe.roles
                            ?.contexts
                            ?.singleOrNull { it.name == island }
                            ?.contextClass
                    val manifest = recipe.manifest.islands.single { it.name == island }
                    var remaining = displayRows
                    runner.island(island).associate { output ->
                        val keyed = manifest.outputs.single { it.port == output.output.port }.keyed
                        val preview = output.preview(remaining, contextClass, keyed)
                        remaining -= preview.rows.size
                        output.output.port to preview
                    }
                }
            if (result.size == 1) call.respond(result.values.single()) else call.respond(IslandPreview(result))
        }
    }
}

private fun StagedOutput.preview(
    limit: Int,
    contextClass: String?,
    keyed: Boolean,
): OutputPreview =
    OutputPreview(
        table.rows.take(limit).map { row ->
            JsonObject(table.columns.mapIndexed { index, column -> column.name to scalar(row[index]) }.toMap())
        },
        output.rows,
        ref,
        contextClass,
        keyed = keyed,
        truncated = output.rows > limit,
    )

@OptIn(ExperimentalSerializationApi::class)
internal fun scalar(value: Any?): JsonElement =
    when (value) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(value)
        is java.math.BigDecimal, is java.math.BigInteger -> JsonUnquotedLiteral(value.toString())
        is Number -> {
            require(value !is Double || value.isFinite()) { "non-finite output number" }
            require(value !is Float || value.isFinite()) { "non-finite output number" }
            JsonPrimitive(value)
        }
        else -> JsonPrimitive(value.toString())
    }

internal fun io.ktor.server.application.ApplicationCall.runId(): UUID =
    try {
        UUID.fromString(parameters["id"]).also {
            if (it.toString() != parameters["id"]?.lowercase()) throw IllegalArgumentException()
        }
    } catch (_: IllegalArgumentException) {
        throw BadRequestException("run id must be a UUID")
    }
