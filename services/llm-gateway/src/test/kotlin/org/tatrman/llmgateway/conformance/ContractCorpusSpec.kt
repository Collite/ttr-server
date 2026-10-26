// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.conformance

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe

/**
 * LG-P0·S2·T3 — well-formedness of the git-tracked consumer corpus. Guards the DATA the LG-P6
 * contract diff depends on: a thin/synthetic corpus that "diffs clean" is a false pass (design
 * §Risks), so every consumer×shape must be present and complete, and the known hard hits the SQ-2
 * sweep surfaced (docwh-pipeline/iris field-surface reads) must stay recorded.
 */
class ContractCorpusSpec :
    StringSpec({

        "the corpus covers at least every consumer x shape (>= 6)" {
            ContractCorpus.load().size shouldBeGreaterThanOrEqual 6
        }

        "every corpus entry is complete and consistent" {
            ContractCorpus.load().forEach { e ->
                withClue("${e.consumer}/${e.shape}") {
                    e.method shouldBe "POST"
                    (e.path.startsWith("/v1/") || e.path.startsWith("/api/v1/")) shouldBe true
                    e.requestBody.containsKey("model") shouldBe true
                    e.readsResponseFields.shouldNotBeEmpty()
                    (e.origin == "reconstructed-from-source" || e.origin == "captured") shouldBe true
                    e.sources.shouldNotBeEmpty()
                }
            }
        }

        "the SQ-2 field-surface hard hits stay recorded (docwh-pipeline + iris)" {
            val entries = ContractCorpus.load()

            val pipeline = entries.single { it.consumer.startsWith("docwh-pipeline") }
            withClue("docwh-pipeline must be recorded as reading top-level content") {
                pipeline.readsResponseFields shouldContainAll listOf("content")
            }

            val iris = entries.single { it.consumer.startsWith("iris") }
            withClue("iris must be recorded as reading the Responses/conversation field surface") {
                iris.readsResponseFields shouldContainAll listOf("output[]", "status", "conversationId")
            }
        }
    })
