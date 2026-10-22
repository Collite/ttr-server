// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.automation.bundle.*
import java.math.BigDecimal
import java.time.Instant

class RecipeBindingsSpec :
    StringSpec({
        "resolve decimal parameters and derived date bindings in dependency order" {
            val recipe =
                ManifestRecipe(
                    "r",
                    parameters =
                        listOf(
                            ManifestParameter("tolerance", "decimal", "0.02"),
                            ManifestParameter("lookback", "days", "365"),
                        ),
                    bindings =
                        listOf(
                            ManifestBinding(
                                "prior_week",
                                "datetime",
                                "derived",
                                ManifestDerive("prior_year", "minus", "7", "days"),
                            ),
                            ManifestBinding(
                                "prior_year",
                                "datetime",
                                "derived",
                                ManifestDerive("as_of", "minus", "lookback", "days"),
                            ),
                        ),
                )
            val values = RecipeBindings.resolve(recipe, Instant.parse("2026-11-02T08:00:00Z"))
            values.getValue("tolerance").value shouldBe BigDecimal("0.02")
            values.getValue("prior_year").value shouldBe Instant.parse("2025-11-02T08:00:00Z")
            values.getValue("prior_week").value shouldBe Instant.parse("2025-10-26T08:00:00Z")
        }
        "cycles, unknown overrides and incompatible override types refuse before a door read" {
            val recipe =
                ManifestRecipe(
                    "r",
                    parameters = listOf(ManifestParameter("n", "int", "1")),
                    bindings =
                        listOf(
                            ManifestBinding(
                                "loop",
                                "datetime",
                                "derived",
                                ManifestDerive("loop", "minus", "1", "days"),
                            ),
                        ),
                )
            val now = Instant.now()
            shouldThrow<IllegalArgumentException> { RecipeBindings.resolve(recipe, now) }
            shouldThrow<IllegalArgumentException> {
                RecipeBindings.resolve(
                    recipe.copy(bindings = emptyList()),
                    now,
                    mapOf(
                        "unknown" to BoundValue("int", 1L),
                    ),
                )
            }
            shouldThrow<IllegalArgumentException> {
                RecipeBindings.resolve(
                    recipe.copy(bindings = emptyList()),
                    now,
                    mapOf(
                        "n" to BoundValue("text", "one"),
                    ),
                )
            }
        }
    })
