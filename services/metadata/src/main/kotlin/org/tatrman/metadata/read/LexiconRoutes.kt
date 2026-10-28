// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.read

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import java.util.Base64
import org.tatrman.metadata.export.matchesEtag

/** #273: bytes and validator are captured from one served source snapshot. */
fun Route.lexiconRoutes(
    registry: MetadataRegistry,
    configuredSourceIds: Set<String>,
) {
    get("/model/lexicon.tar.zst") {
        val snapshot = registry.read() ?: return@get call.respondModelNotLoaded()
        val sourceId = call.request.queryParameters["source"]
        if (call.request.queryParameters
                .names()
                .any { it != "source" } ||
            call.request.queryParameters
                .getAll("source")
                ?.size
                ?.let { it != 1 } == true ||
            sourceId == ""
        ) {
            return@get call.respondError(HttpStatusCode.BadRequest, "invalid source selector")
        }
        if (sourceId == null && configuredSourceIds.size > 1) {
            return@get call.respondError(
                HttpStatusCode.BadRequest,
                "lexicon source selector required: ?source=<one of ${configuredSourceIds.sorted().joinToString(", ")}>",
            )
        }
        val selected = sourceId ?: configuredSourceIds.singleOrNull()
        if (selected !in configuredSourceIds) {
            return@get call.respondError(HttpStatusCode.NotFound, "unknown lexicon source")
        }
        val served =
            snapshot.servedLexicons().find { it.sourceId == selected }
                ?: return@get call.respondError(HttpStatusCode.ServiceUnavailable, "lexicon source not loaded")
        if (served.archive == null) {
            return@get call.respondError(
                if (served.issue != null) HttpStatusCode.ServiceUnavailable else HttpStatusCode.NotFound,
                if (served.issue != null) "lexicon unavailable" else "lexicon not found",
            )
        }
        val validator = "${served.version}.${served.blobId}"
        val etag = "\"$validator\""
        call.response.header(HttpHeaders.ETag, etag)
        if (matchesEtag(call.request.headers[HttpHeaders.IfNoneMatch], validator)) {
            call.response.status(HttpStatusCode.NotModified)
            return@get
        }
        call.respondBytes(Base64.getDecoder().decode(served.archive), ContentType.parse("application/zstd"))
    }
}
