// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonObject
import org.tatrman.automation.identity.caller
import org.tatrman.automation.runner.RunContext
import org.tatrman.automation.runner.RunSessions
import org.tatrman.automation.runner.Table

private data class OutputSelection(
    val island: String,
    val port: String,
    val keyed: Boolean,
    val format: String,
    val offset: Int,
    val limit: Int,
)

private data class OutputDownload(
    val preview: OutputPreview,
    val bytes: ByteArray?,
    val format: String,
)

/** Owner-checked immutable output reads. Unmounted pending P1 runtime/lifecycle integration. */
fun Route.outputRoutes(
    runs: RunSessions,
    displayRows: Int,
) {
    require(displayRows > 0)
    get("/automation/runs/{id}/outputs/{output}") {
        val result =
            runs.read(call.runId(), call.caller(), { call.selectOutput(it, displayRows) }) { runner, page ->
                // Decision snapshots must pass route/detail membership checks and durable journalling first.
                if (page.island ==
                    runner.context.recipe.recipe.roles
                        ?.decision
                ) {
                    runner.evaluate()
                }
                val output = runner.island(page.island).single { it.output.port == page.port }
                val data = runner.stagedData(output)
                val table = data.table
                val rows = if (page.format == "arrow") table.rows else table.rows.drop(page.offset).take(page.limit)
                val marker =
                    table.columns.indexOfFirst {
                        it.name == "truncated" &&
                            it.type in
                            setOf(
                                "bool",
                                "boolean",
                            )
                    }
                val truncated = table.rows.size > rows.size || (marker >= 0 && table.rows.any { it[marker] == true })
                val preview =
                    OutputPreview(
                        if (page.format == "json") {
                            rows.map { row ->
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
                        } else {
                            emptyList()
                        },
                        output.output.rows,
                        output.ref,
                        runner.context.recipe.recipe.roles
                            ?.contexts
                            ?.singleOrNull { it.name == page.island }
                            ?.contextClass,
                        page.keyed,
                        truncated,
                    )
                OutputDownload(
                    preview,
                    when (page.format) {
                        "arrow" -> data.arrow
                        "csv" -> outputCsv(table.copy(rows = rows), truncated).toByteArray(Charsets.UTF_8)
                        else -> null
                    },
                    page.format,
                )
            }
        if (result.format == "json") {
            call.respond(result.preview)
        } else {
            val bytes = requireNotNull(result.bytes)
            call.response.header("X-Row-Count", result.preview.rowCount)
            call.response.header("X-Truncated", result.preview.truncated.toString())
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment
                    .withParameter(
                        ContentDisposition.Parameters.FileName,
                        "${call.runId()}.${result.format}",
                    ).toString(),
            )
            val type =
                if (result.format == "arrow") {
                    ContentType("application", "vnd.apache.arrow.stream")
                } else {
                    ContentType.Text.CSV.withCharset(Charsets.UTF_8)
                }
            call.respondOutputStream(type, contentLength = bytes.size.toLong()) { write(bytes) }
        }
    }
}

/** Match full manifest handles; splitting on a dot would misresolve dotted detail ports/island names. */
private fun ApplicationCall.selectOutput(
    context: RunContext,
    displayRows: Int,
): OutputSelection {
    val matches =
        context.recipe.manifest.islands.flatMap { island ->
            island.outputs.filter { "${island.name}.${it.port}" == parameters["output"] }.map { island.name to it }
        }
    val (island, output) = matches.singleOrNull() ?: throw BadRequestException("unknown or ambiguous output")

    fun number(
        name: String,
        default: Int,
        minimum: Int,
        maximum: Int,
    ): Int {
        val values = request.queryParameters.getAll(name) ?: return default
        val value = values.singleOrNull()?.toIntOrNull()
        if (value == null || value !in minimum..maximum) throw BadRequestException("invalid $name")
        return value
    }
    val formats = request.queryParameters.getAll("format")
    val format = if (formats == null) "json" else formats.singleOrNull()
    if (format !in setOf("arrow", "json", "csv")) throw BadRequestException("invalid format")
    if (format == "arrow" && listOf("offset", "limit").any { request.queryParameters.contains(it) }) {
        throw BadRequestException("Arrow downloads do not accept pagination")
    }
    return OutputSelection(
        island,
        output.port,
        output.keyed,
        format!!,
        number("offset", 0, 0, Int.MAX_VALUE),
        number("limit", displayRows, 1, displayRows),
    )
}

/** UTF-8 BOM, explicit comma separator, CRLF and quoted fields, including headers and embedded newlines. */
internal fun outputCsv(
    table: Table,
    truncated: Boolean,
): String =
    buildString {
        fun field(value: Any?): String {
            var text = value?.toString().orEmpty()
            // Only text needs formula neutralization: signed numeric values keep their numeric representation.
            if (value is String &&
                (
                    text.firstOrNull() in listOf('\t', '\r', '\n') ||
                        text.trimStart { it.isWhitespace() || it == '\uFEFF' }.firstOrNull() in
                        listOf('=', '+', '-', '@')
                )
            ) {
                text = "'$text"
            }
            return "\"${text.replace("\"", "\"\"")}\""
        }
        append("\uFEFFsep=,\r\n")
        append(table.columns.joinToString(",") { field(it.name) }).append("\r\n")
        table.rows.forEach { row -> append(row.joinToString(",") { field(it) }).append("\r\n") }
        if (truncated) append("# truncated\r\n")
    }
