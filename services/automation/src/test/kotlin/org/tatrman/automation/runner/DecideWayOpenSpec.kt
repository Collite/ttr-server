// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.automation.bundle.ApprovalReader
import org.tatrman.automation.bundle.BundleFixture
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.identity.ServiceTokens
import org.tatrman.automation.identity.TokenKind
import org.tatrman.automation.ledger.Claim
import java.time.Duration
import java.util.UUID
import org.tatrman.ttr.metadata.model.RunAsRef as ModelRunAs

/**
 * ⚑AU-D3-2 (S3.1): `decide_return`'s `decide_way` context names two entity types (`return_request_line`,
 * `return_request`). D3 left it unopenable ("unsupported native writer mapping or value" at Open); the stage's
 * placement takes a transition's target from its `transition:` entity, not from every keyed column.
 */
class DecideWayOpenSpec :
    StringSpec({
        "decide_return opens on the 20-shape fixture: decide_way's second entity is context, not a write target" {
            val loaded = BundleFixture.load()
            val b =
                loaded.copy(
                    deployments =
                        loaded.deployments +
                            (
                                "decide_native_batch" to
                                    loaded.deployments.getValue("decide_native_batch").copy(runAs = ModelRunAs.Caller)
                            ),
                )
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
                    OpenRequest("decide_native_batch", buildJsonObject { put("none", true) }),
                    Caller("maya-id", "maya", setOf("hartland-ops"), TokenKind.PERSON, "verified"),
                )
            result.httpStatus shouldBe 201
        }

        "review-273 F9: a package-qualified transition owner resolves by the same rule as its unqualified key columns" {
            val loaded = BundleFixture.load()
            val line = loaded.entities.single { it.qname.name == "return_request_line" }
            val qualified = "${line.qname.`package`}.return_request_line.state"
            val recipe = loaded.recipes.getValue("decide_return")
            val action = recipe.coverage.actions.getValue("decide_way")
            val b =
                loaded.copy(
                    deployments =
                        loaded.deployments +
                            (
                                "decide_native_batch" to
                                    loaded.deployments.getValue("decide_native_batch").copy(runAs = ModelRunAs.Caller)
                            ),
                    recipes =
                        loaded.recipes +
                            (
                                "decide_return" to
                                    recipe.copy(
                                        coverage =
                                            recipe.coverage.copy(
                                                actions =
                                                    recipe.coverage.actions +
                                                        (
                                                            "decide_way" to
                                                                action.copy(
                                                                    transition =
                                                                        action.transition!!.copy(
                                                                            attribute = qualified,
                                                                        ),
                                                                )
                                                        ),
                                            ),
                                    )
                            ),
                )
            org.tatrman.automation.apply.PreviewWriter
                .targetEntities(
                    b.recipes
                        .getValue("decide_return")
                        .coverage.actions
                        .getValue("decide_way"),
                    b.entities,
                ).map { it.qname.name } shouldBe listOf("return_request_line")
        }
    })
