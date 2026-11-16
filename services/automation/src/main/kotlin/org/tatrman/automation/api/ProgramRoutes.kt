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
 * its recipe has no bundle in this commit (`bundle-missing`).
 */
fun programManifest(
    bundle: Bundle?,
    name: String,
): ProgramManifest {
    val program =
        bundle?.programs?.get(name)
            ?: throw AutomationException(ErrorCode.PROGRAM_UNKNOWN, "no program $name is served")
    val recipe =
        program.recipe
            ?: throw unavailable(name, "recipe-null", null, "program $name names no recipe")
    val loaded =
        bundle.recipes[recipe]
            ?: throw unavailable(name, "bundle-missing", recipe, "program $name's recipe $recipe has no bundle")
    val text = checkNotNull(loaded.manifestText) { "recipe $recipe was loaded without its manifest text" }
    return ProgramManifest(text, Fingerprints.sha256(text))
}

/** The descriptor's link to [p]'s manifest route; null when that route would answer `manifest-unavailable`. */
fun manifestLink(
    bundle: Bundle,
    p: AutomationProgram,
): String? = p.recipe?.takeIf { it in bundle.recipes }?.let { "/automation/programs/${p.qname.name}/manifest" }

private fun unavailable(
    program: String,
    reason: String,
    recipe: String?,
    message: String,
) = AutomationException(
    ErrorCode.MANIFEST_UNAVAILABLE,
    message,
    buildJsonObject {
        put("program", program)
        put("reason", reason)
        recipe?.let { put("recipe", it) }
    },
)
