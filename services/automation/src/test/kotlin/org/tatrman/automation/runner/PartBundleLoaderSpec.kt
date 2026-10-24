// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.automation.bundle.*
import java.nio.file.Files

class PartBundleLoaderSpec :
    StringSpec({
        "refresh loads and authenticates a part file as well as its output and combine file" {
            val root = BundleFixture.copy()
            val dir = root.resolve("automation/monitor_inbound/monitor_inbound.bundle")
            val real = realRecipe("monitor_inbound")
            for ((path, text) in real.statements) {
                Files.createDirectories(dir.resolve(path).parent)
                Files.writeString(dir.resolve(path), text)
            }
            Files.writeString(dir.resolve("manifest.json"), RecipeManifest.JSON.encodeToString(real.manifest))
            val loaded = BundleFixture.load(root).recipes.getValue("monitor_inbound")
            loaded.recipe.parts.size shouldBe real.recipe.parts.size
            loaded.statements.keys shouldBe real.statements.keys
            val part = loaded.recipe.parts.first()
            Files.writeString(dir.resolve(part.file), "SELECT 0")
            val e = shouldThrow<BundleInvalidException> { BundleFixture.load(root) }
            e.diagnostics.any { it.contains(part.file) && it.contains("sha256") } shouldBe true
        }
        "unknown part references and escaping paths refuse refresh" {
            val root = BundleFixture.copy()
            val dir = root.resolve("automation/monitor_inbound/monitor_inbound.bundle")
            val real = realRecipe("monitor_inbound")
            val island = real.manifest.islands.first()
            val output = island.outputs.first()
            val malformed =
                real.manifest.copy(
                    islands =
                        listOf(
                            island.copy(
                                outputs =
                                    listOf(
                                        output.copy(
                                            combine = output.combine!!.copy(parts = listOf("unknown")),
                                        ),
                                    ),
                            ),
                        ),
                    recipe =
                        real.recipe.copy(
                            parts =
                                listOf(
                                    real.recipe.parts
                                        .first()
                                        .copy(file = "../escaped.sql"),
                                ),
                        ),
                )
            Files.writeString(dir.resolve("manifest.json"), RecipeManifest.JSON.encodeToString(malformed))
            val e = shouldThrow<BundleInvalidException> { BundleFixture.load(root) }
            e.diagnostics.any { it.contains("unknown part unknown") } shouldBe true
            e.diagnostics.any { it.contains("../escaped.sql") } shouldBe true
        }
    })
