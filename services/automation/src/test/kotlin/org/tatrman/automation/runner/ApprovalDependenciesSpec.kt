// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.*
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.*
import org.tatrman.automation.bundle.BundleFixture.edit
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.identity.ServiceTokens
import org.tatrman.automation.identity.TokenKind
import org.tatrman.automation.ledger.Claim
import org.tatrman.automation.ledger.RunOpen
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttrp.bundle.RecipeApprovalDependencies
import org.tatrman.ttrp.emit.recipe.RecipeActionDependencies
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.tatrman.ttr.metadata.model.RunAsRef as ModelRunAs

/** Released producer evidence → real loader/reconciler → governed Open, without a package-named checkout. */
class ApprovalDependenciesSpec :
    StringSpec({
        val resource = Path.of("src/test/resources/approval-evidence")
        val recipeName = "release_order"
        val deployment = "release_native_event"
        val caller = Caller("maya-id", "maya", setOf("hartland-sales-managers"), TokenKind.PERSON, "verified")
        val manifestPath = "automation/release_order/release_order.bundle/manifest.json"

        fun fixture(): Path = ApprovalFixture.copyTo(tempdir(prefix = "automation-bundles-").toPath().resolve("branch"))

        fun load(root: Path): Bundle {
            val b = BundleFixture.load(root)
            return b.copy(
                deployments =
                    b.deployments + (deployment to b.deployments.getValue(deployment).copy(runAs = ModelRunAs.Caller)),
            )
        }

        fun editRecipe(
            root: Path,
            edit: (JsonObject) -> JsonObject,
        ) {
            val file = root.resolve(manifestPath)
            val json = RecipeManifest.JSON.parseToJsonElement(file.readText()).jsonObject
            file.writeText(JsonObject(json + ("recipe" to edit(json.getValue("recipe").jsonObject))).toString())
        }

        fun editEvidence(
            root: Path,
            edit: (JsonObject) -> JsonObject,
        ) = editRecipe(root) { r ->
            JsonObject(r + ("approvalDependencies" to edit(r.getValue("approvalDependencies").jsonObject)))
        }

        fun open(
            b: Bundle,
            approved: ApprovalReader,
            claim: (RunOpen) -> Claim,
            request: OpenRequest? = null,
            principal: Caller = caller,
        ): OpenResult =
            Open(
                { ApprovalDependencies.prepare(b) },
                approved,
                ServiceTokens(emptyMap(), { error("no token exchange") }),
                claim,
                { null },
                { false },
                Duration.ofSeconds(60),
                Duration.ofMinutes(30),
                BundleFixture.clock,
            ).open(recipeName, request ?: OpenRequest(deployment, buildJsonObject { put("key", "4711") }), principal)

        fun approvals(b: Bundle): ApprovalReader {
            val fp =
                b.recipes
                    .getValue(recipeName)
                    .recipe.fingerprints
            val expected =
                mapOf(
                    ("recipe" to recipeName) to fp.text,
                    ("values" to recipeName) to fp.values,
                    ("program" to "release_on_placement") to b.programFps.getValue("release_on_placement"),
                    ("deployment" to deployment) to b.deploymentFps.getValue(deployment),
                )
            return ApprovalReader { kind, name, value -> expected[kind to name] == value }
        }

        fun refuses(
            b: Bundle,
            reason: String,
            approved: ApprovalReader = ApprovalReader { _, _, _ -> true },
            kind: CoverageKind = CoverageKind.MALFORMED,
        ) {
            val e =
                shouldThrow<AutomationException> {
                    open(b, approved, { error("refusal must precede atomic claim") })
                }
            e.code shouldBe ErrorCode.RECIPE_STALE
            e.message!! shouldContain reason
            e.detail!!
                .jsonObject
                .getValue("differs")
                .jsonArray shouldBe JsonArray(listOf(JsonPrimitive("text")))
            e.detail!!
                .jsonObject
                .getValue("approved")
                .jsonObject
                .getValue("text") shouldBe JsonNull
            e.detail!!
                .jsonObject
                .getValue("current")
                .jsonObject
                .getValue("text") shouldBe
                JsonPrimitive(
                    b.recipes
                        .getValue(recipeName)
                        .recipe.fingerprints.text,
                )
            val stale = Staleness.of(ApprovalDependencies.prepare(b), deployment, approved)
            stale.unapproved shouldBe emptyList()
            e.detail!!
                .jsonObject
                .getValue("coverage")
                .jsonPrimitive.content shouldBe kind.wire
            stale.coverage shouldBe mapOf(recipeName to kind)
        }

        "released text2 evidence approves qualified project and injected stdlib targets after relocation" {
            val root = fixture()
            root.toString().contains("/hartland/") shouldBe false
            val b = load(root)
            val r = b.recipes.getValue(recipeName).recipe
            r.approvalDependencies!!
                .jsonObject
                .getValue("version")
                .jsonPrimitive.int shouldBe 1
            r.approvalDependencies!!
                .jsonObject
                .getValue("actions")
                .jsonObject.keys shouldBe setOf("release", "hold", "audit_state")
            val request =
                OpenRequest(
                    deployment,
                    buildJsonObject { put("key", "4711") },
                    recipeFingerprint = r.fingerprints.text,
                    recipeValuesFingerprint = r.fingerprints.values,
                    programFingerprint = b.programFps.getValue("release_on_placement"),
                    deploymentFingerprint = b.deploymentFps.getValue(deployment),
                )
            var run: RunOpen? = null
            val result =
                open(b, approvals(b), {
                    run = it
                    Claim.Claimed(UUID.randomUUID(), startedAt = BundleFixture.LOADED_AT)
                }, request)
            result.httpStatus shouldBe 201
            result.approvalsBypassed shouldBe false
            result.context!!
                .previewMetadata!!
                .actions
                .getValue("audit_state")
                .columns
                .single()
                .type shouldBe
                "automation.obligation.state"
            (b.model.schemas.getValue("er") as ErSchema)
                .entities.values
                .single { it.qname.name == "order" }
                .qname.`package` shouldBe ""
            run!!.fpText shouldBe r.fingerprints.text
            Staleness.of(b, deployment, approvals(b)).stale shouldBe false
        }

        "editing defaults or target bounds under old text and matching approvals refuses before claim" {
            for (change in listOf("default", "target")) {
                val root = fixture()
                val old = load(root)
                if (change == "default") {
                    root.edit("automation/automation.ttrm") { it.replace("'released'", "'held'") }
                } else {
                    root.edit("model/er/automation-entities.ttrm") {
                        it.replace("type: text, isKey: true", "type: { type: text, length: 8 }, isKey: true")
                    }
                }
                val changed = load(root)
                changed.recipes
                    .getValue(recipeName)
                    .recipe.fingerprints shouldBe
                    old.recipes
                        .getValue(recipeName)
                        .recipe.fingerprints
                val staleAction = if (change == "target") "hold" else "release"
                refuses(changed, "stale for `$staleAction`", approvals(old), CoverageKind.STALE)
            }
        }

        "evidence is verified even after the default declaration is removed" {
            val root = fixture()
            root.edit("automation/automation.ttrm") {
                it.replace("    writer_defaults: { state: { expression: \"'released'\" } }\n", "")
            }
            refuses(load(root), "stale for `release`", kind = CoverageKind.STALE)
        }

        "missing evidence cannot inherit legacy approval and text2 cannot bind a tampered base" {
            val root = fixture()
            editRecipe(root) { JsonObject(it - "approvalDependencies") }
            refuses(load(root), "evidence is missing", kind = CoverageKind.MISSING)
            val other = fixture()
            editEvidence(other) { JsonObject(it + ("textBase" to JsonPrimitive("sha256:" + "0".repeat(64)))) }
            refuses(load(other), "not bound to recipe text/2")
        }

        "unsupported versions malformed hashes noncanonical imports and unknown packages refuse" {
            val edits =
                listOf<Pair<String, (JsonObject) -> JsonObject>>(
                    "unsupported approval evidence version 99" to { JsonObject(it + ("version" to JsonPrimitive(99))) },
                    "invalid approval evidence textBase" to { JsonObject(it + ("textBase" to JsonPrimitive("bad"))) },
                    "sorted and unique" to
                        {
                            JsonObject(
                                it +
                                    (
                                        "imports" to
                                            JsonArray(listOf(JsonPrimitive("hartland"), JsonPrimitive("hartland")))
                                    ),
                            )
                        },
                    "resolves to no loader package" to
                        { JsonObject(it + ("imports" to JsonArray(listOf(JsonPrimitive("unknown"))))) },
                )
            for ((reason, edit) in edits) {
                val root = fixture()
                editEvidence(root, edit)
                refuses(
                    load(root),
                    reason,
                    kind =
                        when (reason) {
                            "unsupported approval evidence version 99" -> CoverageKind.UNSUPPORTED
                            "resolves to no loader package" -> CoverageKind.UNRESOLVED
                            else -> CoverageKind.MALFORMED
                        },
                )
            }
        }

        "missing null or renamed evidence fields refuse only that recipe after version dispatch" {
            for (field in listOf("version", "imports", "textBase", "actions")) {
                for (nullValue in listOf(false, true)) {
                    val root = fixture()
                    editEvidence(
                        root,
                    ) { if (nullValue) JsonObject(it + (field to JsonNull)) else JsonObject(it - field) }
                    refuses(
                        load(root),
                        if (field ==
                            "version"
                        ) {
                            "unsupported approval evidence version"
                        } else {
                            "field `$field`"
                        },
                        kind = if (field == "version") CoverageKind.UNSUPPORTED else CoverageKind.MALFORMED,
                    )
                }
            }
            val root = fixture()
            editEvidence(root) {
                JsonObject(
                    mapOf(
                        "version" to JsonPrimitive(2),
                        "futureImports" to JsonArray(emptyList()),
                    ),
                )
            }
            val loaded = load(root)
            val b =
                loaded.copy(
                    deployments =
                        loaded.deployments + (
                            "decide_native_batch" to
                                loaded.deployments.getValue("decide_native_batch").copy(runAs = ModelRunAs.Caller)
                        ),
                )
            refuses(b, "unsupported approval evidence version 2", kind = CoverageKind.UNSUPPORTED)
            // The unrelated legacy recipe in the same refreshed commit remains independently usable.
            val result =
                Open(
                    { b },
                    ApprovalReader { _, _, _ -> true },
                    ServiceTokens(emptyMap(), { error("unused") }),
                    { Claim.Claimed(UUID.randomUUID(), startedAt = BundleFixture.LOADED_AT) },
                    { null },
                    { false },
                    Duration.ofSeconds(60),
                    Duration.ofMinutes(30),
                    BundleFixture.clock,
                ).open(
                    "decide_return",
                    OpenRequest(
                        "decide_native_batch",
                        buildJsonObject {
                            put("none", true)
                        },
                    ),
                    caller.copy(roles = setOf("hartland-ops")),
                )
            result.httpStatus shouldBe 201
        }

        "non-object evidence stays local to its recipe and null remains absent" {
            for (value in listOf<JsonElement>(JsonArray(emptyList()), JsonPrimitive("future"), JsonPrimitive(2))) {
                val root = fixture()
                editRecipe(root) { JsonObject(it + ("approvalDependencies" to value)) }
                val b = load(root)
                refuses(b, "approval evidence is not an object", kind = CoverageKind.UNSUPPORTED)
                val deploymentName = "decide_native_batch"
                val neighbor =
                    b.copy(
                        deployments =
                            b.deployments + (
                                deploymentName to b.deployments.getValue(deploymentName).copy(runAs = ModelRunAs.Caller)
                            ),
                    )
                Open(
                    { neighbor },
                    ApprovalReader { _, _, _ -> true },
                    ServiceTokens(emptyMap(), { error("unused") }),
                    { Claim.Claimed(UUID.randomUUID(), startedAt = BundleFixture.LOADED_AT) },
                    { null },
                    { false },
                    Duration.ofSeconds(60),
                    Duration.ofMinutes(30),
                    BundleFixture.clock,
                ).open("decide_return", OpenRequest(deploymentName, buildJsonObject { put("none", true) }), caller)
                    .httpStatus shouldBe 201
            }
            val root = fixture()
            editRecipe(root) { JsonObject(it + ("approvalDependencies" to JsonNull)) }
            refuses(load(root), "evidence is missing", kind = CoverageKind.MISSING)
        }

        "v1 extra fields and non-integer version spellings refuse explicitly" {
            val root = fixture()
            editEvidence(root) { JsonObject(it + ("coverage" to JsonPrimitive("physical"))) }
            refuses(load(root), "unknown approval evidence field `coverage`", kind = CoverageKind.MALFORMED)
            for (version in listOf(JsonPrimitive("1"), JsonPrimitive(1.0))) {
                val other = fixture()
                editEvidence(other) { JsonObject(it + ("version" to version)) }
                refuses(load(other), "version must be the integer 1", kind = CoverageKind.UNSUPPORTED)
            }
        }

        "unprepared recipes refuse before claim even in development mode" {
            val b = load(fixture())
            val current = b.recipes.getValue(recipeName)
            val unprepared = LoadedRecipe(current.name, current.manifest, current.recipe, current.statements)
            val pending = b.copy(recipes = b.recipes + (recipeName to unprepared))
            for (development in listOf(false, true)) {
                val e =
                    shouldThrow<AutomationException> {
                        Open(
                            { pending },
                            approvals(b),
                            ServiceTokens(emptyMap(), { error("unused") }),
                            { error("unprepared coverage must not claim") },
                            { null },
                            { false },
                            Duration.ofSeconds(60),
                            Duration.ofMinutes(30),
                            BundleFixture.clock,
                            approvalsOff = development,
                        ).open(recipeName, OpenRequest(deployment, buildJsonObject { put("key", "4711") }), caller)
                    }
                e.code shouldBe ErrorCode.RECIPE_STALE
                e.message shouldBe "approval coverage was not prepared"
            }
        }

        "legacy unresolved action does not claim a recorded scope" {
            val b = load(fixture())
            val legacy =
                b.recipes.getValue(recipeName).recipe.copy(
                    approvalDependencies = null,
                    wiring = listOf(ManifestWiring(action = "absent")),
                )
            val result = ApprovalDependencies.check(b, legacy)
            result.coverage shouldBe CoverageKind.UNRESOLVED
            result.refusal shouldBe
                "legacy wired action `absent` is not declared in the model; declare it or correct the wiring"
        }

        "project shadowing cannot silently rebind explicit stdlib evidence" {
            val root = fixture()
            root.edit("model/er/automation-entities.ttrm") {
                it +
                    "\ndef entity obligation { attributes: [def attribute state { type: int }] }\n"
            }
            // The current ER model loses package identity (#294), so reconciliation may drop the stdlib
            // entity with the same qname. Refuse that missing qualified target, never approve the project one.
            refuses(
                load(root),
                "unresolved action approval dependency `automation.obligation.state`",
                kind = CoverageKind.UNRESOLVED,
            )
        }

        "scoped verified action is pinned for execution even when an out-of-scope action shares its name" {
            val root = fixture()
            root.resolve("automation/other.ttrm").writeText(
                """package other
model automation
def action release {
 columns: [def column order { type: hartland.order.order_number }]
 sinks: { native: writer }
 writer_defaults: { order: { expression: "'wrong'" } }
 approval: none
}
""",
            )
            val b = load(root)
            val result =
                open(b, approvals(b), { Claim.Claimed(UUID.randomUUID(), startedAt = BundleFixture.LOADED_AT) })
            result.context!!
                .previewMetadata!!
                .actions
                .getValue("release")
                .columns.size shouldBe 5
            val withoutScope =
                b.recipes
                    .getValue(recipeName)
                    .recipe
                    .copy(approvalDependencies = null)
            val legacy = ApprovalDependencies.check(b, withoutScope)
            legacy.refusal!! shouldContain "declared in several packages; rename it or adopt evidence"
            legacy.coverage shouldBe CoverageKind.AMBIGUOUS
            Registry(ExecutorType.builtin()).cell(b, b.deployments.getValue(deployment)).status shouldBe Registry.OK
        }

        "a correctly rebound evidence map must still name exactly the wired actions" {
            val b = load(fixture())
            val loaded = b.recipes.getValue(recipeName)
            val e =
                RecipeManifest.JSON.decodeFromJsonElement<ManifestApprovalDependencies>(
                    loaded.recipe.approvalDependencies!!,
                )
            val changed = e.copy(actions = e.actions + ("unwired" to e.actions.getValue("release")))
            val text =
                RecipeActionDependencies.text(
                    RecipeApprovalDependencies(changed.version, changed.imports, changed.textBase, changed.actions),
                )
            val recipe =
                loaded.recipe.copy(
                    approvalDependencies = RecipeManifest.JSON.encodeToJsonElement(changed).jsonObject,
                    fingerprints = loaded.recipe.fingerprints.copy(text = text),
                )
            refuses(
                b.copy(recipes = b.recipes + (recipeName to loaded.copy(recipe = recipe))),
                "names differ from wiring",
            )
        }

        "a second visible action in the recorded scope refuses rather than choosing the first" {
            val root = fixture()
            root.resolve("automation/ambiguous.ttrm").writeText(
                """package hartland.extra
model automation
def action release { columns: [def column order { type: text }] sinks: { native: writer } approval: none }
""",
            )
            refuses(load(root), "ambiguous wired action `release`", kind = CoverageKind.AMBIGUOUS)
        }

        "ungranted callers receive identity refusal before coverage details" {
            val root = fixture()
            editEvidence(root) { JsonObject(mapOf("version" to JsonPrimitive(9))) }
            val b = BundleFixture.load(root)
            val e =
                shouldThrow<AutomationException> {
                    open(b, approvals(b), { error("must not claim") }, principal = caller.copy(roles = emptySet()))
                }
            e.code shouldBe ErrorCode.IDENTITY
        }

        "request consumers reuse the loaded resolution without consulting package maps" {
            val b = load(fixture())
            val cached = b.recipes.getValue(recipeName).coverage
            // Access to a package index after load would expose a second request-path resolution.
            val forbidden =
                object : Map<String, String> by emptyMap() {
                    override fun get(key: String): String = error("package lookup must happen only during load")
                }
            val pinned = b.copy(packages = forbidden)
            val result =
                Open(
                    { pinned },
                    approvals(b),
                    ServiceTokens(emptyMap(), { error("unused") }),
                    { Claim.Claimed(UUID.randomUUID(), startedAt = BundleFixture.LOADED_AT) },
                    { null },
                    { false },
                    Duration.ofSeconds(60),
                    Duration.ofMinutes(30),
                    BundleFixture.clock,
                ).open(recipeName, OpenRequest(deployment, buildJsonObject { put("key", "4711") }), caller)
            (result.context!!.previewMetadata!!.targets === cached.targets) shouldBe true
            Staleness.of(pinned, deployment, approvals(b)).stale shouldBe false
            val native = ExecutorType.builtin().getValue("native").copy(family = "external")
            Registry(
                mapOf("native" to native),
            ).cell(pinned, pinned.deployments.getValue(deployment)).status shouldBe
                Registry.OK
        }

        "exported text must still match after dependency verification" {
            val b = load(fixture())
            val e =
                shouldThrow<AutomationException> {
                    open(b, approvals(b), {
                        error("must not claim")
                    }, OpenRequest(deployment, buildJsonObject { put("key", "4711") }, recipeFingerprint = "old"))
                }
            e.code shouldBe ErrorCode.RECIPE_STALE
        }
    })
