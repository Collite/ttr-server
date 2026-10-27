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

/** #273: bytes and validator are captured from one served source snapshot. */
fun Route.lexiconRoutes(registry: MetadataRegistry) {
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
        val catalogs = snapshot.servedLexicons()
        // A selector lets multi-source readers retain their primary/additive order (#209).
        val served = if (sourceId == null) catalogs.lastOrNull() else catalogs.find { it.sourceId == sourceId }
        if (served == null || served.archive == null) {
            return@get call.respondError(
                if (served?.issue != null) HttpStatusCode.ServiceUnavailable else HttpStatusCode.NotFound,
                if (served?.issue != null) "lexicon unavailable" else "lexicon not found",
            )
        }
        val etag = "\"${served.version}\""
        call.response.header(HttpHeaders.ETag, etag)
        if (call.request.headers[HttpHeaders.IfNoneMatch]?.split(',')?.any {
                val token = it.trim().removePrefix("W/")
                token == "*" || token.removeSurrounding("\"") == served.version
            } == true
        ) {
            call.response.status(HttpStatusCode.NotModified)
            return@get
        }
        call.respondBytes(Base64.getDecoder().decode(served.archive), ContentType.parse("application/zstd"))
    }
}
