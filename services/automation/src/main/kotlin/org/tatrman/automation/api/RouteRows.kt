// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.tatrman.automation.identity.caller
import org.tatrman.automation.runner.PartRunner
import org.tatrman.automation.runner.RunContext
import org.tatrman.automation.runner.RunSessions
import org.tatrman.automation.runner.keyValues
import org.tatrman.automation.staging.StagedOutput

@Serializable
data class RoutePage(
    val rows: List<JsonObject>,
    val rowCount: Long,
    val ref: String,
    val truncated: Boolean,
)

private data class RouteSelection(
    val name: String,
    val detail: List<String>,
    val offset: Int,
    val limit: Int,
    val context: String? = null,
    val row: Int? = null,
)

/** Bounded reads of evaluated snapshots. Unmounted with the other run handlers pending runtime/lifecycle wiring. */
fun Route.routeRows(
    runs: RunSessions,
    displayRows: Int,
) {
    require(displayRows > 0)
    get("/automation/runs/{id}/routes/{route}") {
        val result =
            runs.read(call.runId(), call.caller(), { call.selectRoute(it, displayRows, false) }) { runner, page ->
                val outputs = runner.routeOutputs()
                val output = outputs.getValue(page.name)
                val table = runner.readStaged(output)
                val keys =
                    runner.context.recipe.recipe.key
                        .map { it.substringAfterLast('.') }
                val columns =
                    (keys + "verdict_reason").map { name ->
                        name to table.columns.indexOfFirst { it.name == name }
                    }
                val base = "/automation/runs/${runner.context.id}/routes/${page.name}"
                val rows =
                    table.rows.drop(page.offset).take(page.limit).mapIndexed { index, row ->
                        JsonObject(
                            columns.associate { (name, column) -> name to scalar(row[column]) } +
                                (
                                    "detail" to
                                        JsonObject(
                                            page.detail.associateWith { context ->
                                                JsonPrimitive("$base/details/$context?row=${page.offset + index}")
                                            },
                                        )
                                ),
                        )
                    }
                RoutePage(rows, output.output.rows, base, output.output.rows > rows.size)
            }
        call.respond(result)
    }
    get("/automation/runs/{id}/routes/{route}/details/{context}") {
        val result =
            runs.read(call.runId(), call.caller(), { call.selectRoute(it, displayRows, true) }) { runner, page ->
                val outputs = runner.routeOutputs()
                val output = outputs.getValue("${page.name}.${page.context}")
                val keys =
                    runner.context.recipe.recipe.key
                        .map { it.substringAfterLast('.') }
                val table = runner.readStaged(output)
                val selected =
                    if (page.row == null) {
                        table.rows
                    } else {
                        val routeKeys = runner.readStaged(outputs.getValue(page.name)).keyValues(keys)
                        val key = routeKeys.getOrNull(page.row)
                        val detailKeys = table.keyValues(keys)
                        table.rows.filterIndexed { index, _ -> detailKeys[index] == key }
                    }
                val rows =
                    selected.drop(page.offset).take(page.limit).map { row ->
                        JsonObject(
                            table.columns
                                .mapIndexed {
                                    index,
                                    column,
                                    ->
                                    column.name to scalar(row[index])
                                }.toMap(),
                        )
                    }
                val base = "/automation/runs/${runner.context.id}/routes/${page.name}/details/${page.context}"
                val ref = if (page.row == null) base else "$base?row=${page.row}"
                RoutePage(
                    rows,
                    selected.size.toLong(),
                    ref,
                    selected.size > rows.size || selected.any { it.last() == true },
                )
            }
        call.respond(result)
    }
}

private suspend fun PartRunner.routeOutputs(): Map<String, StagedOutput> {
    evaluate() // Require a durable successful decision, even when a part caller staged the ports first.
    return island(
        requireNotNull(
            context.recipe.recipe.roles
                ?.decision,
        ),
    ).associateBy { it.output.port }
}

/** Called inside the owner-checked selection, outside execution: bad client parameters never fail a run. */
private fun ApplicationCall.selectRoute(
    context: RunContext,
    displayRows: Int,
    details: Boolean,
): RouteSelection {
    val name = parameters["route"]
    val route =
        context.recipe.recipe.roles
            ?.routes
            ?.singleOrNull { it.name == name }
            ?: throw BadRequestException("unknown route")
    val detail = if (details) parameters["context"] else null
    if (details && detail !in route.detail) throw BadRequestException("unknown detail context")

    fun number(
        name: String,
        default: Int?,
        minimum: Int = 0,
        maximum: Int = Int.MAX_VALUE,
    ): Int? {
        val values = request.queryParameters.getAll(name) ?: return default
        val value = values.singleOrNull()?.toIntOrNull()
        if (value == null || value !in minimum..maximum) throw BadRequestException("invalid $name")
        return value
    }
    return RouteSelection(
        route.name,
        route.detail,
        number("offset", 0)!!,
        number("limit", displayRows, 1, displayRows)!!,
        detail,
        if (details) number("row", null) else null,
    )
}
