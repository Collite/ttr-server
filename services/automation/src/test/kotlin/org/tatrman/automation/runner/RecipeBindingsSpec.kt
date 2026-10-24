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
        "published minute derivations retain exact amounts and original pinned time" {
            val recipe =
                ManifestRecipe(
                    "r",
                    bindings =
                        listOf(
                            ManifestBinding(
                                "soon",
                                "datetime",
                                "derived",
                                ManifestDerive("as_of", "plus", "15", "minutes"),
                            ),
                        ),
                )
            RecipeBindings.resolve(recipe, Instant.parse("2026-10-24T12:30:00Z")).getValue("soon").value shouldBe
                Instant.parse("2026-10-24T12:45:00Z")
        }
        "date bindings and subday derivations retain date versus timestamp types" {
            val recipe =
                ManifestRecipe(
                    "r",
                    parameters = listOf(ManifestParameter("start", "date", "2024-01-31")),
                    bindings =
                        listOf(
                            ManifestBinding("month", "date", "derived", ManifestDerive("start", "plus", "1", "months")),
                            ManifestBinding(
                                "hour",
                                "timestamp",
                                "derived",
                                ManifestDerive("start", "plus", "1", "hours"),
                            ),
                        ),
                )
            val result = RecipeBindings.resolve(recipe, Instant.EPOCH)
            result.getValue("month").value shouldBe java.time.LocalDate.parse("2024-02-29")
            result.getValue("hour").value shouldBe java.time.LocalDateTime.parse("2024-01-31T01:00:00")
        }
        "published datetime binding tags normalize date parameters before chained derivations" {
            val recipe =
                ManifestRecipe(
                    "r",
                    parameters = listOf(ManifestParameter("start", "date", "2024-01-31")),
                    bindings =
                        listOf(
                            ManifestBinding(
                                "month",
                                "datetime",
                                "derived",
                                ManifestDerive("start", "plus", "1", "months"),
                            ),
                            ManifestBinding(
                                "later",
                                "datetime",
                                "derived",
                                ManifestDerive("month", "plus", "15", "minutes"),
                            ),
                        ),
                )
            val result = RecipeBindings.resolve(recipe, Instant.EPOCH)
            result.getValue("month").value shouldBe Instant.parse("2024-02-29T00:00:00Z")
            result.getValue("later").value shouldBe Instant.parse("2024-02-29T00:15:00Z")
        }
        "fractional wrong-type overflowing and calendar duration amounts refuse instead of truncating" {
            val base = mapOf("as_of" to BoundValue("datetime", Instant.EPOCH))
            for (number in listOf(
                BoundValue("decimal", BigDecimal("1.5")),
                BoundValue("int", 1.5),
                BoundValue("text", "2"),
            )) {
                shouldThrow<Exception> {
                    RecipeBindings.derive(ManifestDerive("as_of", "plus", "n", "days"), base + ("n" to number))
                }
            }
            shouldThrow<IllegalArgumentException> {
                RecipeBindings.derive(ManifestDerive("as_of", "plus", "2", "working_days"), base)
            }
            shouldThrow<ArithmeticException> {
                RecipeBindings.derive(ManifestDerive("as_of", "minus", Long.MIN_VALUE.toString(), "days"), base)
            }
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
