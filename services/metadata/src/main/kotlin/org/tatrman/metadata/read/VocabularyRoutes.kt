// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.read

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.Json
import org.tatrman.metadata.parse.QueryParseState
import org.tatrman.metadata.scope.TagScope
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.MatchMethods
import org.tatrman.ttr.metadata.registry.MetadataRegistry

/** Authored entity vocabulary only; member values and SQL plans remain ListMemberVocabularies. */
fun Route.vocabularyRoutes(
    registry: MetadataRegistry,
    parseState: QueryParseState? = null,
) {
    get("/model/vocabulary") {
        val params = call.request.queryParameters
        val raw = params.getAll("tags")?.flatMap { it.split(',') }
        val tags = runCatching { TagScope.normalize(raw.orEmpty()) }.getOrNull()
        if (params.contains("agent") || tags == null) {
            val error =
                if (params.contains(
                        "agent",
                    )
                ) {
                    "agent scoping belongs to the caller"
                } else {
                    "tags must contain nonblank values"
                }
            return@get call.respondError(HttpStatusCode.BadRequest, error)
        }
        val snap = registry.read() ?: return@get call.respondModelNotLoaded()
        val scope =
            TagScope.resolve(snap.model, tags, {
                parseState?.get(snap.model.version.value, it.qname)
                    ?: it.parseStatus
            })
        val body =
            buildJsonObject {
                put("model_version", snap.model.version.value)
                putJsonArray("tags") { tags.forEach { add(it) } }
                putJsonArray("entities") {
                    scope.objects.filterIsInstance<Entity>().forEach { entity ->
                        addJsonObject {
                            put("qname", entity.qname.dotted())
                            put("name", entity.qname.name)
                            put("description", entity.description)
                            putJsonArray(
                                "aliases",
                            ) { (entity.aliases + entity.search.aliases).distinct().sorted().forEach { add(it) } }
                            putJsonArray("tags") {
                                entity.tags
                                    .distinct()
                                    .sorted()
                                    .forEach { add(it) }
                            }
                            putJsonArray("attributes") {
                                entity.attributes.sortedBy { it.qname.dotted() }.forEach { attr ->
                                    addJsonObject {
                                        put("qname", attr.qname.dotted())
                                        put("name", attr.qname.name.substringAfterLast('.'))
                                        put("description", attr.description)
                                        putJsonArray(
                                            "aliases",
                                        ) {
                                            attr.search.aliases
                                                .distinct()
                                                .sorted()
                                                .forEach { add(it) }
                                        }
                                        put("indexed", attr.search.indexed)
                                        put("fuzzy", MatchMethods.isPartial(attr.search.matchMethod))
                                        put(
                                            "match_method",
                                            attr.search.matchMethod
                                                ?: if (attr.search.indexed) MatchMethods.DEFAULT else "",
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                putJsonArray("warnings") {
                    scope.warnings.forEach { warning ->
                        addJsonObject {
                            put("code", warning.code)
                            put("message", warning.message)
                            put("source_file", warning.sourceFile)
                        }
                    }
                }
            }
        call.respondText(body.toString(), io.ktor.http.ContentType.Application.Json)
    }
}
