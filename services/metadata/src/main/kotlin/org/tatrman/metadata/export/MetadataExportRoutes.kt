// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.export

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.tatrman.ttr.metadata.export.*
import org.tatrman.ttr.metadata.export.GraphDotExporter
import org.tatrman.ttr.metadata.export.ModelToDefinitions
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.slf4j.LoggerFactory
import org.tatrman.ttr.parser.loader.TtrLoader
import org.tatrman.ttr.writer.TtrRenderer
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

private val log = LoggerFactory.getLogger("org.tatrman.metadata.export.MetadataExportRoutes")

private val CONTENT_TYPE_TAR_GZ = ContentType.parse("application/x-tar+gzip")

fun Route.metadataExportRoutes(registry: MetadataRegistry) {
    get("/model/export") {
        val snap = registry.read()
        if (snap == null) {
            call.respondText(
                """{"error":"model not loaded"}""",
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
            return@get
        }

        val params = call.request.queryParameters
        val allowed = setOf("model", "format", "profile", "package", "area", "since", "validate")
        val request =
            try {
                require(params.names().all { it in allowed }) { "Unknown export parameter" }
                require(params.names().all { params.getAll(it)?.size == 1 }) { "Repeated export parameter" }

                fun selectors(key: String): Set<String>? =
                    params[key]?.split(",")?.toSet()?.also {
                        require(it.none(String::isBlank)) { "Empty $key selector" }
                    }
                ExportRequest(
                    models = selectors("model") ?: setOf("world"),
                    format = params["format"] ?: "ttr",
                    profile = params["profile"],
                    packages = selectors("package"),
                    areas = selectors("area"),
                    since = params["since"],
                    previousIds = params["since"]?.let { snap.recentIdSets[it] },
                ).also { Exporter.validate(it) }
            } catch (error: IllegalArgumentException) {
                call.respondText(
                    errorBody("xm/bad-request", "message" to error.message.orEmpty()),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@get
            }
        if (request.since != null && request.previousIds == null) {
            call.respondText(
                errorBody("xm/since-unknown", "since" to request.since.orEmpty()),
                ContentType.Application.Json,
                HttpStatusCode.Conflict,
            )
            return@get
        }
        val etag = snap.model.version.value
        call.response.header(HttpHeaders.ETag, etag)
        val projection = params.names().any { it != "validate" }
        if (!projection && matchesEtag(call.request.headers[HttpHeaders.IfNoneMatch], etag)) {
            call.response.status(HttpStatusCode.NotModified)
            return@get
        }
        if (projection) {
            val result =
                try {
                    Exporter().export(
                        snap.definitions,
                        snap.graph,
                        request.copy(
                            connections = readExportConnections(snap.exportSidecars["connections.toml"]),
                            renames = snap.exportSidecars["renames.yaml"]?.let(Rename::read).orEmpty(),
                        ),
                    )
                } catch (error: Exception) {
                    log.error("Metadata projection failed", error)
                    call.respondText(
                        errorBody("xm/export-failed"),
                        ContentType.Application.Json,
                        HttpStatusCode.InternalServerError,
                    )
                    return@get
                }
            // Conditional success must never hide cannot-carry or reserved-format errors.
            if ((result is ExportResult.Single || result is ExportResult.Bundle) &&
                matchesEtag(call.request.headers[HttpHeaders.IfNoneMatch], etag)
            ) {
                call.response.status(HttpStatusCode.NotModified)
                return@get
            }
            when (result) {
                is ExportResult.Single -> {
                    val contentType =
                        if (request.format ==
                            "xmi"
                        ) {
                            ContentType.Application.Xml
                        } else {
                            ContentType.parse(result.contentType)
                        }
                    call.respondBytes(result.bytes, contentType, HttpStatusCode.OK)
                }
                is ExportResult.Bundle ->
                    call.respondBytes(
                        buildTarGz(result.files),
                        CONTENT_TYPE_TAR_GZ,
                        HttpStatusCode.OK,
                    )
                is ExportResult.CannotCarry -> {
                    val model =
                        request.models.sorted().first {
                            LossinessLedger.cannotCarry(
                                it,
                                request.format,
                            ) != null
                        }
                    call.respondText(
                        errorBody(
                            "xm/format-cannot-carry",
                            "model" to model,
                            "format" to request.format,
                            "lossiness" to result.lossinessId,
                        ),
                        ContentType.Application.Json,
                        HttpStatusCode.UnprocessableEntity,
                    )
                }
                ExportResult.NotBuilt ->
                    call.respondText(
                        errorBody("xm/format-not-built", "format" to request.format),
                        ContentType.Application.Json,
                        HttpStatusCode.NotImplemented,
                    )
                ExportResult.SinceUnknown ->
                    call.respondText(
                        errorBody(
                            "xm/since-unknown",
                            "since" to request.since.orEmpty(),
                        ),
                        ContentType.Application.Json,
                        HttpStatusCode.Conflict,
                    )
            }
            return@get
        }

        val validate = call.request.queryParameters["validate"] != "false"
        val bundle = ModelToDefinitions.convert(snap.model)

        if (validate) {
            val validationErrors =
                bundle.files.flatMap { file ->
                    val content =
                        TtrRenderer.renderFile(
                            file.schemaCode,
                            file.namespace,
                            file.definitions,
                            file.packageName,
                            file.imports,
                        )
                    val result = TtrLoader.parseString(content, fileLabel = file.filename)
                    result.errors.map { it.toString() }
                }
            if (validationErrors.isNotEmpty()) {
                log.error("TTR export self-validation failed:\n{}", validationErrors.joinToString("\n"))
                val body = JsonObject(mapOf("errors" to JsonArray(validationErrors.map { JsonPrimitive(it) })))
                call.respondText(body.toString(), ContentType.Application.Json, HttpStatusCode.InternalServerError)
                return@get
            }
        }

        val tarball = buildTarGz(bundle)
        val filename = "model-${bundle.modelId}-${bundle.modelVersion}.tar.gz"

        call.response.header(HttpHeaders.ETag, etag)
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"$filename\"")
        call.respondBytes(tarball, CONTENT_TYPE_TAR_GZ, HttpStatusCode.OK)
    }

    // Phase 09 B4 / DF-M15 — Graphviz DOT export of the current ModelGraph snapshot. Returns
    // text/vnd.graphviz which downstream tools (`dot -Tsvg`, online viewers) consume directly.
    get("/model/export/dot") {
        val snap = registry.read()
        if (snap == null) {
            call.respondText(
                """{"error":"model not loaded"}""",
                ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
            return@get
        }
        val etag = snap.model.version.value
        val ifNoneMatch = call.request.headers[HttpHeaders.IfNoneMatch]
        if (matchesEtag(ifNoneMatch, etag)) {
            call.response.header(HttpHeaders.ETag, etag)
            call.response.status(HttpStatusCode.NotModified)
            return@get
        }
        val dot = GraphDotExporter.export(snap.graph)
        call.response.header(HttpHeaders.ETag, etag)
        call.respondText(dot, ContentType.parse("text/vnd.graphviz"), HttpStatusCode.OK)
    }
}

private fun errorBody(
    error: String,
    vararg fields: Pair<String, String>,
): String =
    JsonObject(
        linkedMapOf("error" to JsonPrimitive(error)) +
            fields.associate {
                it.first to JsonPrimitive(it.second)
            },
    ).toString()

private fun matchesEtag(
    header: String?,
    version: String,
): Boolean =
    header?.split(",")?.any {
        val token = it.trim().removePrefix("W/")
        token == "*" || token.removeSurrounding("\"") == version
    } == true

private fun buildTarGz(bundle: ExportBundle): ByteArray =
    buildTarGz(
        bundle.files.map { file ->
            val path =
                if (file.packageName !=
                    null
                ) {
                    "${bundle.modelId}/${file.packageName}/${file.filename}"
                } else {
                    "${bundle.modelId}/${file.filename}"
                }
            ExportResult.Single(
                path,
                "text/plain",
                TtrRenderer
                    .renderFile(
                        file.schemaCode,
                        file.namespace,
                        file.definitions,
                        file.packageName,
                        file.imports,
                    ).toByteArray(Charsets.UTF_8),
            )
        },
    )

private fun buildTarGz(files: List<ExportResult.Single>): ByteArray {
    val baos = ByteArrayOutputStream()
    GZIPOutputStream(baos).use { gzip ->
        TarArchiveOutputStream(gzip).use { archive ->
            archive.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
            for (file in files) {
                val entry = TarArchiveEntry(file.name)
                entry.size = file.bytes.size.toLong()
                entry.modTime = java.util.Date(0)
                archive.putArchiveEntry(entry)
                archive.write(file.bytes)
                archive.closeArchiveEntry()
            }
        }
    }
    return baos.toByteArray()
}
