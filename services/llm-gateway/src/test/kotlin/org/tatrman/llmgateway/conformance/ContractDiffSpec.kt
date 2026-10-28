// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.conformance

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json

/**
 * LG-P0·S2·T5 — self-test of the contract-diff engine, with NO live gateway. Proves: identical
 * envelopes diff clean (the "1.x vs 1.x = zero deltas" gate), volatile identity and model-generated
 * text are ignored, and the migration wrinkles the diff MUST catch (usage-name rename, dropped
 * fields, shape changes) are caught.
 */
class ContractDiffSpec :
    StringSpec({

        fun j(s: String) = Json.parseToJsonElement(s)

        val base =
            """
            {"id":"chatcmpl-1","object":"chat.completion","created":111,"model":"gpt-4o",
             "choices":[{"index":0,"message":{"role":"assistant","content":"Hello there"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15,"cost":0.001}}
            """.trimIndent()

        "identical envelopes diff clean (1.x vs 1.x = zero deltas)" {
            ContractDiff.diff(j(base), j(base)) shouldBe emptyList()
        }

        "volatile identity and model-generated text are ignored" {
            val other =
                """
                {"id":"chatcmpl-999","object":"chat.completion","created":222,"model":"gpt-4o",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"A completely different answer"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15,"cost":0.001}}
                """.trimIndent()
            ContractDiff.diff(j(base), j(other)) shouldBe emptyList()
        }

        "catches the usage-name rename wrinkle (input_tokens/output_tokens -> prompt_tokens/completion_tokens)" {
            val oneX = """{"usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15,"cost":0.001}}"""
            val twoX = """{"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15,"cost":0.001}}"""
            val deltas = ContractDiff.diff(j(oneX), j(twoX))
            deltas.map { "${it.kind} ${it.path}" } shouldContainExactlyInAnyOrder
                listOf(
                    "KEY_REMOVED $.usage.input_tokens",
                    "KEY_REMOVED $.usage.output_tokens",
                    "KEY_ADDED $.usage.prompt_tokens",
                    "KEY_ADDED $.usage.completion_tokens",
                )
        }

        "catches a dropped non-standard field (docwh-pipeline/iris top-level content presence)" {
            val oneX = """{"choices":[{"message":{"content":"x"}}],"content":"top-level dup"}"""
            val twoX = """{"choices":[{"message":{"content":"x"}}]}"""
            val deltas = ContractDiff.diff(j(oneX), j(twoX))
            deltas shouldHaveSize 1
            deltas.first().kind shouldBe ContractDiff.Delta.Kind.KEY_REMOVED
            deltas.first().path shouldBe "$.content"
        }

        "catches an array-shape change" {
            val oneX = """{"choices":[{"index":0},{"index":1}]}"""
            val twoX = """{"choices":[{"index":0}]}"""
            val deltas = ContractDiff.diff(j(oneX), j(twoX))
            deltas.map { it.kind } shouldContain ContractDiff.Delta.Kind.ARRAY_SIZE
        }

        "report renders the clean line when there are no deltas" {
            ContractDiff.report("hebe-chat", emptyList()) shouldContain "✅ no contractual deltas"
        }
    })
