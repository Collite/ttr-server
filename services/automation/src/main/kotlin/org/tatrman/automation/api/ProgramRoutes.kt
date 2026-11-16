// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.automation.bundle.Bundle
import org.tatrman.automation.bundle.Bundles
import org.tatrman.automation.bundle.Fingerprints
import org.tatrman.ttr.metadata.model.AutomationProgram

/** A program's manifest as served: the file's text and its strong ETag value (`sha256:<hex>`, unquoted). */
data class ProgramManifest(
    val text: String,
    val etag: String,
)

/**
 * `GET /automation/programs/{p}/manifest[?bundle=<id>]` (AU contracts §1.1; SC demand AS-1, D-209): the program's
 * recipe's `manifest.json` byte for byte as the bundle loaded it. Without `bundle` it reads the served bundle; with
 * it, the loaded bundle of that id (a preview's, or the served one's). Two programs on one recipe answer the same
 * body and ETag. `Cache-Control: no-store` — a refresh may swap the bundle at any time.
 */
fun Route.programRoutes(bundles: Bundles) {
    get("/automation/programs/{p}/manifest") {
        val bundle = bundleOf(bundles, call.request.queryParameters["bundle"])
        val manifest = programManifest(bundle, call.parameters["p"].orEmpty())
        call.response.header(HttpHeaders.ETag, "\"${manifest.etag}\"")
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(manifest.text, ContentType.Application.Json)
    }
}

/** The bundle a read names: the served one without an id, else the loaded bundle of that id (`404 bundle-unknown`). */
internal fun bundleOf(
    bundles: Bundles,
    id: String?,
): Bundle? {
    if (id == null) return bundles.served
    return bundles.served?.takeIf { it.id == id }
        ?: bundles.previews().firstOrNull { it.bundle.id == id }?.bundle
        ?: throw AutomationException(ErrorCode.BUNDLE_UNKNOWN, "no bundle $id is loaded")
}

/**
 * [name]'s manifest in [bundle]: `404 program-unknown` when the bundle (or nothing served) lacks the program;
 * `404 manifest-unavailable` when it has no recipe (`recipe-null` — Core refuses that today, the model allows it) or
 * its recipe has no bundle — or none with its manifest text — in this commit (`bundle-missing`).
 */
fun programManifest(
    bundle: Bundle?,
    name: String,
): ProgramManifest {
    val program =
        bundle?.programs?.get(name)
            ?: throw AutomationException(
                ErrorCode.PROGRAM_UNKNOWN,
                bundle?.let { "no program $name in bundle ${it.id}" } ?: "no program $name: no bundle is served",
            )
    return when (val found = lookup(bundle, program)) {
        is Lookup.Found -> ProgramManifest(found.text, Fingerprints.sha256(found.text))
        is Lookup.Unavailable ->
            throw AutomationException(
                ErrorCode.MANIFEST_UNAVAILABLE,
                found.message(name),
                buildJsonObject {
                    put("program", name)
                    put("reason", found.reason)
                    found.recipe?.let { put("recipe", it) }
                },
            )
    }
}

/** The descriptor's link to [p]'s manifest route; null exactly when that route answers `manifest-unavailable`. */
fun manifestLink(
    bundle: Bundle,
    p: AutomationProgram,
): String? = if (lookup(bundle, p) is Lookup.Found) "/automation/programs/${p.qname.name}/manifest" else null

/** Whether [bundle] can serve [p]'s manifest — the one check both the route and the descriptor's link read. */
private sealed interface Lookup {
    data class Found(
        val text: String,
    ) : Lookup

    data class Unavailable(
        val reason: String,
        val recipe: String?,
    ) : Lookup {
        fun message(program: String) =
            recipe?.let { "program $program's recipe $it has no bundle" } ?: "program $program names no recipe"
    }
}

private fun lookup(
    bundle: Bundle,
    p: AutomationProgram,
): Lookup {
    val recipe = p.recipe ?: return Lookup.Unavailable("recipe-null", null)
    val text = bundle.recipes[recipe]?.manifestText ?: return Lookup.Unavailable("bundle-missing", recipe)
    return Lookup.Found(text)
}
