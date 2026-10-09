// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.config

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe

/**
 * LG-P1·S1·T1 — config loader/validator rules (contracts §2.1). Every rejection rule is exercised, the
 * validator is proven to return ALL errors (not first-fail), and the real migrated files load clean.
 */
class ConfigLoadingSpec :
    StringSpec({

        val providers =
            ProvidersConfig(
                providers =
                    mapOf(
                        "azure" to ProviderDef("openai-wire", "http://azure", AuthDef("api-key", null, "AZURE_KEY")),
                    ),
                retry = RetryConfig(3, 250, 4000, 15000),
                circuit = CircuitConfig(5, 30000),
                sse = SseConfig(15),
                cache = CacheConfig(true, "llm-gateway:chat:"),
            )

        fun chat(
            id: String,
            name: String,
            provider: String = "azure",
            aliases: List<String> = emptyList(),
            fallback: List<String> = emptyList(),
            pricing: Pricing? = Pricing(1.0, 1.0),
        ) = CatalogModel(id, name, aliases, provider, name, "chat", emptyList(), pricing, 0, fallback)

        fun validate(
            vararg models: CatalogModel,
            governance: GovernanceConfig = GovernanceConfig(),
        ) = ConfigValidator.validate(Catalog(models.toList()), providers, governance)

        fun errorsContain(
            errors: List<String>,
            needle: String,
        ) = errors.any { it.contains(needle) } shouldBe true

        // ── happy path: the real migrated files ─────────────────────────────────────────────────────
        "loads the migrated catalog/providers/governance with zero errors" {
            val cfg = ConfigLoader.loadFromResources()
            cfg.catalog.models.size shouldBe 7
            cfg.providers.providers.keys shouldContainAll listOf("azure", "openai", "gemini", "anthropic")
            // tier + rules.conf aliases folded onto concrete models (not duplicate rows)
            cfg.catalog.models
                .single { it.name == "claude-haiku-4-5" }
                .aliases shouldContainAll
                listOf("haiku", "claude-haiku")
            cfg.catalog.models.flatMap { it.aliases } shouldContainAll listOf("gpt-4", "fast", "sonnet")
        }

        "the packaged retry budget is 15 s, and LLM_GATEWAY_RETRY_WALL_CLOCK_BUDGET_MS overrides it" {
            val packaged = ConfigFactory.parseResources("providers.conf")
            providersFrom(packaged.resolve()).retry.wallClockBudgetMs shouldBe 15_000
            // `${?VAR}` looks the name up in the config before the environment, so a root key stands in
            // for the env var here (resolveWith trips typesafe-config on the file's other `${?…}` merges).
            val overridden =
                ConfigFactory
                    .parseMap(mapOf("LLM_GATEWAY_RETRY_WALL_CLOCK_BUDGET_MS" to 90_000))
                    .withFallback(packaged)
                    .resolve()
            providersFrom(overridden).retry.wallClockBudgetMs shouldBe 90_000
            // The override touches the budget only.
            providersFrom(overridden).retry.maxAttempts shouldBe 3
        }

        // ── rejection rules ─────────────────────────────────────────────────────────────────────────
        "rejects duplicate model ids" {
            errorsContain(validate(chat("dup", "a"), chat("dup", "b")), "duplicate model id: dup")
        }

        "rejects an alias colliding with a model name" {
            errorsContain(
                validate(chat("m1", "gpt-4o"), chat("m2", "other", aliases = listOf("gpt-4o"))),
                "collides with a model name",
            )
        }

        "rejects a fallback id that does not exist" {
            errorsContain(
                validate(chat("m1", "a", fallback = listOf("ghost"))),
                "fallback references unknown id: ghost",
            )
        }

        "rejects a chat model missing pricing.output" {
            errorsContain(validate(chat("m1", "a", pricing = Pricing(1.0, null))), "missing pricing.output")
        }

        "rejects a model referencing an unknown provider" {
            errorsContain(validate(chat("m1", "a", provider = "mystery")), "unknown provider: mystery")
        }

        "rejects a seeded key whose sha256 is not 64-hex (plaintext leak)" {
            val gov =
                GovernanceConfig(
                    teams = listOf(Team("golem", "golem/")),
                    keys = listOf(SeededKey("golem", "leaky", "ttrk-actual-plaintext-key")),
                )
            errorsContain(validate(chat("m1", "a"), governance = gov), "sha256 is not 64-hex")
        }

        // ── all-errors, not first-fail ──────────────────────────────────────────────────────────────
        "returns ALL errors at once, not first-fail" {
            val errors = validate(chat("m1", "a", provider = "mystery", pricing = Pricing(1.0, null)))
            errors.size shouldBeGreaterThanOrEqual 2 // unknown provider AND missing pricing.output
            errorsContain(errors, "unknown provider")
            errorsContain(errors, "missing pricing.output")
        }
    })
