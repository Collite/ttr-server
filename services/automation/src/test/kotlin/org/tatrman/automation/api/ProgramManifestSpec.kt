// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.automation.bundle.BundleFixture
import org.tatrman.automation.bundle.BundleFixture.edit
import org.tatrman.automation.bundle.BundleSource
import org.tatrman.automation.bundle.Bundles
import org.tatrman.automation.identity.AUTOMATION_AUTH
import org.tatrman.automation.identity.AutomationBearerProvider
import org.tatrman.automation.identity.CallerVerifier
import org.tatrman.automation.identity.TestRealm
import org.tatrman.mcp.identity.HttpJwksKeySource
import org.tatrman.mcp.identity.JwksBearerVerifier
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * AU-server S3.4 T1 — `GET /automation/programs/{p}/manifest[?bundle=<id>]` (SC demand AS-1, D-209): the program's
 * recipe's `manifest.json` byte for byte as the bundle loaded it, `ETag` = `"sha256:<hex>"` of those bytes,
 * `Cache-Control: no-store`; the descriptor's `programs[]` link to it (`manifest`, null when there is none).
 */
class ProgramManifestSpec :
    StringSpec({
        val realm = TestRealm()
        afterSpec { realm.close() }

        val releaseOrder = "automation/release_order/release_order.bundle/manifest.json"
        val previewCommit = "fedcba9876543210fedcba9876543210fedcba98"

        fun sha256(bytes: ByteArray): String =
            "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** The served master is [served]; any other branch is [preview] at [previewCommit]. */
        fun registry(
            served: Path = BundleFixture.root,
            preview: Path? = null,
        ): Bundles {
            val bundles =
                BundleFixture.bundles(
                    BundleSource { branch ->
                        if (branch == "master") {
                            BundleFixture.checkout(served, branch = branch)
                        } else {
                            BundleFixture.checkout(preview!!, commit = previewCommit, branch = branch)
                        }
                    },
                    allowed = listOf("flow-*"),
                )
            runBlocking { bundles.refresh() }
            return bundles
        }

        fun ApplicationTestBuilder.service(bundles: Bundles) {
            application {
                install(ContentNegotiation) { json() }
                install(StatusPages) { installAutomationErrors() }
                val callers =
                    CallerVerifier(
                        JwksBearerVerifier(HttpJwksKeySource(realm.jwksUrl), TestRealm.ISSUER, TestRealm.AUDIENCE),
                    )
                install(Authentication) { AutomationBearerProvider.register(this, callers) }
                routing { authenticate(AUTOMATION_AUTH) { descriptorRoutes(BundleFixture.sources(bundles)) } }
            }
        }

        suspend fun ApplicationTestBuilder.get(
            path: String,
            bundle: String? = null,
        ): HttpResponse =
            client.get(path) {
                header(HttpHeaders.Authorization, "Bearer ${realm.person("maya", listOf("hartland-sales-managers"))}")
                bundle?.let { parameter("bundle", it) }
            }

        fun errorOf(text: String): JsonObject =
            Json
                .parseToJsonElement(text)
                .jsonObject
                .getValue("error")
                .jsonObject

        fun descriptorPrograms(text: String): Map<String, JsonObject> =
            Json
                .parseToJsonElement(text)
                .jsonObject
                .getValue("programs")
                .jsonArray
                .associate {
                    it.jsonObject
                        .getValue("name")
                        .jsonPrimitive.content to it.jsonObject
                }

        "(a) a served program answers its recipe's manifest.json byte for byte, ETag = sha256 of the bytes, no-store" {
            val file = Files.readAllBytes(BundleFixture.root.resolve(releaseOrder))
            testApplication {
                service(registry())
                val r = get("/automation/programs/morning_release/manifest")
                r.status shouldBe HttpStatusCode.OK
                r.bodyAsBytes().contentEquals(file) shouldBe true
                r.headers[HttpHeaders.ETag] shouldBe "\"${sha256(file)}\""
                r.headers[HttpHeaders.CacheControl] shouldBe "no-store"
                r.headers[HttpHeaders.ContentType]!!.substringBefore(';') shouldBe "application/json"
                // Two programs on one recipe: the same body, the same ETag (D-209 (2)).
                val sibling = get("/automation/programs/release_on_placement/manifest")
                sibling.status shouldBe HttpStatusCode.OK
                sibling.bodyAsBytes().contentEquals(file) shouldBe true
                sibling.headers[HttpHeaders.ETag] shouldBe r.headers[HttpHeaders.ETag]
                // Another recipe, another manifest.
                val settle =
                    Files.readAllBytes(
                        BundleFixture.root.resolve("automation/settle/settle.bundle/manifest.json"),
                    )
                get("/automation/programs/settle_daily/manifest").bodyAsBytes().contentEquals(settle) shouldBe true
            }
        }

        "(a) verbatim: keys the Server's reader skips and the file's own layout reach the client unchanged" {
            val root = BundleFixture.copy()
            // A compact, reordered print with a key no reader knows: a re-serialisation would change every byte of it.
            root.edit(releaseOrder) { text ->
                val manifest = Json.parseToJsonElement(text).jsonObject
                val reordered =
                    JsonObject(
                        mapOf("zFlowHint" to JsonPrimitive("kept")) +
                            manifest.entries.reversed().associate { it.toPair() },
                    )
                reordered.toString() + "\n\n"
            }
            val file = Files.readAllBytes(root.resolve(releaseOrder))
            testApplication {
                service(registry(served = root))
                val r = get("/automation/programs/morning_release/manifest")
                r.status shouldBe HttpStatusCode.OK
                r.bodyAsBytes().contentEquals(file) shouldBe true
                r.headers[HttpHeaders.ETag] shouldBe "\"${sha256(file)}\""
            }
        }

        "(b) ?bundle=<preview id> answers that bundle's manifest; the served id answers the served one" {
            val preview = BundleFixture.copy()
            preview.edit(releaseOrder) { it.trimEnd() + "\n " }
            val served = Files.readAllBytes(BundleFixture.root.resolve(releaseOrder))
            val previewed = Files.readAllBytes(preview.resolve(releaseOrder))
            (served.contentEquals(previewed)) shouldBe false
            testApplication {
                val bundles = registry(preview = preview)
                val id = runBlocking { bundles.refresh("flow-x") }.bundle.id
                id shouldBe "preview:flow-x@${previewCommit.take(12)}"
                service(bundles)
                val r = get("/automation/programs/morning_release/manifest", bundle = id)
                r.status shouldBe HttpStatusCode.OK
                r.bodyAsBytes().contentEquals(previewed) shouldBe true
                r.headers[HttpHeaders.ETag] shouldBe "\"${sha256(previewed)}\""
                val plain = get("/automation/programs/morning_release/manifest")
                plain.bodyAsBytes().contentEquals(served) shouldBe true
                val byServedId = get("/automation/programs/morning_release/manifest", bundle = bundles.served!!.id)
                byServedId.status shouldBe HttpStatusCode.OK
                byServedId.bodyAsBytes().contentEquals(served) shouldBe true
            }
        }

        "(c) an unknown program is 404 program-unknown; an unknown bundle id is 404 bundle-unknown" {
            testApplication {
                service(registry())
                val program = get("/automation/programs/nope/manifest")
                program.status shouldBe HttpStatusCode.NotFound
                errorOf(program.bodyAsText())["code"] shouldBe JsonPrimitive("program-unknown")
                val bundle =
                    get("/automation/programs/morning_release/manifest", bundle = "preview:flow-y@000000000000")
                bundle.status shouldBe HttpStatusCode.NotFound
                errorOf(bundle.bodyAsText())["code"] shouldBe JsonPrimitive("bundle-unknown")
            }
        }

        "(c) nothing served: a program is 404 program-unknown" {
            testApplication {
                service(BundleFixture.bundles(BundleSource { error("never") }))
                val r = get("/automation/programs/morning_release/manifest")
                r.status shouldBe HttpStatusCode.NotFound
                errorOf(r.bodyAsText())["code"] shouldBe JsonPrimitive("program-unknown")
            }
        }

        "(d) a program whose recipe has no bundle: 404 manifest-unavailable (bundle-missing), descriptor link null" {
            // An undeployed program may name a recipe whose bundle is not built yet (a load warning, not a refusal).
            val root = BundleFixture.copy()
            root.edit("automation/programs.ttrm") {
                it + "\ndef program ghost_daily { recipe: ghost_recipe, port: none, mode: batch, as_of: run }\n"
            }
            testApplication {
                val bundles = registry(served = root)
                bundles.served!!.programs.keys shouldContain "ghost_daily"
                service(bundles)
                val missing = get("/automation/programs/ghost_daily/manifest")
                missing.status shouldBe HttpStatusCode.NotFound
                val error = errorOf(missing.bodyAsText())
                error["code"] shouldBe JsonPrimitive("manifest-unavailable")
                error["detail"]!!.jsonObject["reason"] shouldBe JsonPrimitive("bundle-missing")
                error["detail"]!!.jsonObject["recipe"] shouldBe JsonPrimitive("ghost_recipe")

                val programs = descriptorPrograms(get("/automation/").bodyAsText())
                programs.getValue("ghost_daily")["manifest"] shouldBe JsonNull
                programs.getValue("morning_release")["manifest"] shouldBe
                    JsonPrimitive("/automation/programs/morning_release/manifest")
            }
        }

        "(d) a program with recipe: null is 404 manifest-unavailable (recipe-null); its descriptor link is null" {
            // No fixture has one, and none can load: Core refuses a program without `recipe:`
            // (`ttr/automation-missing-property`, a refusing finding). The model allows it, so the bundle is built
            // here.
            val served = BundleFixture.load()
            val morning = served.programs.getValue("morning_release")
            val adhoc = morning.copy(qname = morning.qname.copy(name = "adhoc_count"), recipe = null)
            val bundle = served.copy(programs = served.programs + ("adhoc_count" to adhoc))
            val refusal = shouldThrow<AutomationException> { programManifest(bundle, "adhoc_count") }
            refusal.code shouldBe ErrorCode.MANIFEST_UNAVAILABLE
            refusal.code.status shouldBe HttpStatusCode.NotFound
            refusal.detail!!.jsonObject["reason"] shouldBe JsonPrimitive("recipe-null")
            manifestLink(bundle, adhoc) shouldBe null
            manifestLink(bundle, morning) shouldBe "/automation/programs/morning_release/manifest"
        }

        "(d) a recipe loaded without its manifest text: no link; manifest-unavailable, not a 500" {
            // Only a Bundle built outside BundleLoader can hold one; the link and the route must still agree.
            val served = BundleFixture.load()
            val release = served.recipes.getValue("release_order")
            val bundle = served.copy(recipes = served.recipes + ("release_order" to release.copy(manifestText = null)))
            val morning = bundle.programs.getValue("morning_release")
            manifestLink(bundle, morning) shouldBe null
            val refusal = shouldThrow<AutomationException> { programManifest(bundle, "morning_release") }
            refusal.code shouldBe ErrorCode.MANIFEST_UNAVAILABLE
            refusal.detail!!.jsonObject["reason"] shouldBe JsonPrimitive("bundle-missing")
            refusal.detail!!.jsonObject["recipe"] shouldBe JsonPrimitive("release_order")
            manifestLink(bundle, bundle.programs.getValue("settle_daily")) shouldBe
                "/automation/programs/settle_daily/manifest"
        }

        "(c) program-unknown names the bundle it looked in" {
            val served = BundleFixture.load()
            val inServed = shouldThrow<AutomationException> { programManifest(served, "nope") }
            inServed.message!! shouldContain served.id
            val noneServed = shouldThrow<AutomationException> { programManifest(null, "nope") }
            noneServed.message!! shouldContain "no bundle is served"
        }

        "the descriptor's programs[] link every hartland program to its manifest route" {
            testApplication {
                service(registry())
                val programs = descriptorPrograms(get("/automation/").bodyAsText())
                programs.size shouldBe 8
                programs.forEach { (name, p) ->
                    p["manifest"] shouldBe
                        JsonPrimitive("/automation/programs/$name/manifest")
                }
            }
        }

        "(e) no bearer: the same 401 as recipes/{r}; a tampered one too" {
            testApplication {
                service(registry())
                val recipe = client.get("/automation/recipes/release_order")
                val manifest = client.get("/automation/programs/morning_release/manifest")
                recipe.status shouldBe HttpStatusCode.Unauthorized
                manifest.status shouldBe recipe.status
                errorOf(manifest.bodyAsText())["code"] shouldBe errorOf(recipe.bodyAsText())["code"]
                manifest.headers[HttpHeaders.ETag] shouldBe null
                val forgedRecipe =
                    client.get("/automation/recipes/release_order") {
                        header(HttpHeaders.Authorization, "Bearer not-a-token")
                    }
                val forged =
                    client.get("/automation/programs/morning_release/manifest") {
                        header(HttpHeaders.Authorization, "Bearer not-a-token")
                    }
                forgedRecipe.status shouldBe HttpStatusCode.Unauthorized
                forged.status shouldBe forgedRecipe.status
                errorOf(forged.bodyAsText())["code"] shouldBe errorOf(forgedRecipe.bodyAsText())["code"]
                forged.headers[HttpHeaders.ETag] shouldBe null
            }
        }
    })
