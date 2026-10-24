// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.provider

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.llmgateway.config.ConfigLoader

/**
 * LG-P3·S1·T5 — the alias live-contract proof (CQ-1). Runs the CURRENT kantheon caller reality
 * (`llm-client` / `GolemModels` tier aliases + the migrated `rules.conf` aliases) through the resolver
 * over the REAL packaged `catalog.yaml`, asserting each lands on the same concrete model 1.x lands on. If a
 * catalog edit ever breaks an alias a live caller depends on, this spec fails — not production.
 */
class RoutingParitySpec :
    StringSpec({

        val catalog = ConfigLoader.loadFromResources().catalog.models
        val resolver = ModelResolver(catalog)

        fun landsOn(
            name: String?,
            tags: List<String> = emptyList(),
        ): String? = (resolver.resolve(name, tags, RequestedType.CHAT) as? Resolution.Resolved)?.model?.name

        // name → concrete model, exactly as 1.x models.yaml + rules.conf resolved them
        val aliasContract =
            listOf(
                "haiku" to "claude-haiku-4-5", // Themis CHEAP / Golem GolemModels.Cheap
                "claude-haiku" to "claude-haiku-4-5", // Golem cheap alias
                "sonnet" to "claude-sonnet-4-6", // Themis FAST
                "gpt-4" to "gpt-4o", // rules.conf: gpt-4 → gpt-4o
                // Generic tier keys mini/fast route to the one Azure baseline model (catalog azure-gpt-41,
                // aliases: [mini, fast]); gpt-4o-mini stays reachable by name. Updated from the 1.x rules.conf
                // target (gpt-4o-mini) to match that remap.
                "fast" to "gpt-4.1",
                "mini" to "gpt-4.1",
                // `deep` is the smart tier's (Bora, 2026-10-24): Golem composes the query on `deep`, while mini/fast
                // stay on the chat deployment.
                "deep" to "gpt-6.1-sol",
                "gpt-4o" to "gpt-4o", // literal (tier-2) still works
                "claude-sonnet-4-6" to "claude-sonnet-4-6", // literal
            )

        aliasContract.forEach { (requested, expected) ->
            "alias live contract: '$requested' → $expected (CQ-1)" {
                landsOn(requested) shouldBe expected
            }
        }

        "tag live contract: model_tags=[tatrman] (no name) → gpt-4.1" {
            landsOn(null, listOf("tatrman")) shouldBe "gpt-4.1"
        }

        "the smart tier: 'smart' and model_tags=[smart] → gpt-6.1-sol, a reasoning model (2026-10-24)" {
            landsOn("smart") shouldBe "gpt-6.1-sol"
            landsOn(null, listOf("smart")) shouldBe "gpt-6.1-sol"
            val sol = catalog.single { it.name == "gpt-6.1-sol" }
            // the tatrman deployment, unless AZURE_OPENAI_SMART_DEPLOYMENT says otherwise
            sol.upstream shouldBe "gpt-6.1-sol"
            sol.reasoning shouldBe true // Azure refuses max_tokens for it
        }

        "embeddings: text-embedding-3-small is served by the tatrman deployment of that name" {
            val m =
                (
                    resolver.resolve(
                        "text-embedding-3-small",
                        emptyList(),
                        RequestedType.EMBEDDING,
                    ) as Resolution.Resolved
                ).model
            m.upstream shouldBe "text-embedding-3-small"
            resolver
                .resolve(
                    "text-embedding-3-small",
                    emptyList(),
                    RequestedType.CHAT,
                ).shouldBeInstanceOf<Resolution.Invalid>()
        }

        "case-insensitive parity: 'HAIKU' and 'GPT-4' resolve identically to lowercase (1.x equalsIgnoreCase)" {
            landsOn("HAIKU") shouldBe "claude-haiku-4-5"
            landsOn("GPT-4") shouldBe "gpt-4o"
        }
    })
