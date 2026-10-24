// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.bundle

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path

/** External wire compatibility, independently of the compiler's strict, release-coupled internal reader. */
class RecipeManifestSpec :
    StringSpec({
        val root = Path.of("src/test/resources/runner/hartland")
        val names =
            listOf("decide_return", "monitor_inbound", "release_order", "reorder_proposals", "settle", "winback")

        for (name in names) {
            "the external reader preserves every published recipe field in the real $name bundle" {
                val directory = root.resolve("$name.bundle")
                val text = Files.readString(directory.resolve("manifest.json"))
                val wire =
                    Json
                        .parseToJsonElement(text)
                        .jsonObject
                        .getValue("recipe")
                        .jsonObject
                val manifest = RecipeManifest.parse(text)
                val recipe = requireNotNull(manifest.recipe)
                recipe.name shouldBe name
                // This walks the independently emitted JSON, rather than restating the DTO's field list.
                // In particular, dropping fingerprints.labels or tests must fail this compatibility gate.
                assertPublishedFields(wire, RecipeManifest.JSON.encodeToJsonElement(recipe))
                recipe.tests.isNotEmpty() shouldBe true
                recipe.tests.forEach { Files.isRegularFile(directory.resolve(it)) shouldBe true }
                recipe.fingerprints.labels.startsWith("sha256:") shouldBe true
            }
        }

        "absent or null optional fields keep the external reader's defaults for older bundles" {
            val absent = RecipeManifest.parse("""{"schemaVersion":3,"recipe":{"name":"old"}}""")
            val nullable =
                RecipeManifest.parse(
                    """{"schemaVersion":3,"ttrpVersion":null,"islands":null,"files":null,
                       "recipe":{"name":"old","tests":null,"parts":null,"windows":null,
                                 "roles":null,"fingerprints":{"text":null,"values":null,"labels":null}}}""",
                )
            nullable shouldBe absent
            requireNotNull(nullable.recipe).tests shouldBe emptyList()
            requireNotNull(nullable.recipe).fingerprints shouldBe ManifestFingerprints()
        }

        "unknown additions in nested typed metadata do not break the external reader" {
            val text = Files.readString(root.resolve("monitor_inbound.bundle/manifest.json"))
            val original = RecipeManifest.parse(text)
            val extended = withFutureFields(Json.parseToJsonElement(text))
            RecipeManifest.parse(extended.toString()) shouldBe original
        }

        "future and absent metadata coexist without converting a null recipe into an executable one" {
            val manifest = RecipeManifest.parse("""{"schemaVersion":3,"recipe":null,"future":{"value":1}}""")
            manifest.recipe shouldBe null
            shouldThrow<SerializationException> { RecipeManifest.parse("""{"recipe":{"name":"no-version"}}""") }
            shouldThrow<SerializationException> { RecipeManifest.parse("""{"schemaVersion":3,"recipe":{}}""") }
            shouldThrow<SerializationException> {
                RecipeManifest.parse("""{"schemaVersion":3,"recipe":{"name":"bad","tests":[12]}}""")
            }
        }

        "a labels-only edit stays visible without invalidating approvals or querying a labels approval" {
            val bundle = BundleFixture.load()
            val original = bundle.recipes.getValue("release_order")
            val recipe =
                original.recipe.copy(
                    fingerprints = original.recipe.fingerprints.copy(labels = "sha256:new-labels"),
                )
            val updated = bundle.copy(recipes = bundle.recipes + (original.name to original.copy(recipe = recipe)))
            val asked = mutableListOf<Triple<String, String, String>>()
            val approvals =
                ApprovalReader { kind, obj, fp ->
                    asked += Triple(kind, obj, fp)
                    true
                }
            Staleness.of(updated, "release_native_event", approvals).stale shouldBe false
            asked.map { it.first } shouldBe listOf("recipe", "values", "program", "deployment")
            asked.first().third shouldBe original.recipe.fingerprints.text
            asked[1].third shouldBe original.recipe.fingerprints.values
            updated.recipes
                .getValue(original.name)
                .recipe.fingerprints.labels shouldBe "sha256:new-labels"
            // Text and value edits still ask for their own current approval.
            for (kind in listOf("recipe", "values")) {
                Staleness
                    .of(
                        updated,
                        "release_native_event",
                        ApprovalReader {
                            k,
                            _,
                            _,
                            ->
                            k != kind
                        },
                    ).unapproved shouldBe
                    listOf("$kind release_order")
            }
        }
    })

private fun assertPublishedFields(
    expected: JsonElement,
    actual: JsonElement,
) {
    when (expected) {
        is JsonObject ->
            expected.forEach { (name, value) ->
                // Compiler optional nulls may become default values in an external reader.
                if (value != JsonNull) assertPublishedFields(value, actual.jsonObject.getValue(name))
            }
        is JsonArray -> {
            actual.jsonArray.size shouldBe expected.size
            expected.zip(actual.jsonArray).forEach { (a, b) -> assertPublishedFields(a, b) }
        }
        else -> actual shouldBe expected
    }
}

private fun withFutureFields(value: JsonElement): JsonElement =
    when (value) {
        // files and wiring columns are authored maps; windows/verdict columns are opaque JSON, not DTOs.
        is JsonObject ->
            JsonObject(
                value.mapValues { (name, child) ->
                    if (name in setOf("files", "columns", "windows")) child else withFutureFields(child)
                } + ("futureField" to JsonPrimitive(true)),
            )
        is JsonArray -> JsonArray(value.map(::withFutureFields))
        else -> value
    }
