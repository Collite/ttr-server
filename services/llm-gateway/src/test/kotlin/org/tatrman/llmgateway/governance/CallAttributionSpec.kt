// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.governance

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldHaveLength
import io.ktor.http.headersOf

/**
 * LC-P0·S0.2 — the one read of the caller's attribution headers (LC contracts §2.1). The values are
 * caller-supplied: trimmed, blank ⇒ null (never an empty string on a row), capped at 512.
 */
class CallAttributionSpec :
    StringSpec({
        "the four headers map to the four fields" {
            CallAttribution.from(
                headersOf(
                    "X-Turn-Ref" to listOf("t1"),
                    "X-Call-Purpose" to listOf("compose-plan"),
                    "X-End-User-Subject" to listOf("sub-1"),
                    "X-Agent-Id" to listOf("golem-hartland"),
                ),
            ) shouldBe CallAttribution("t1", "compose-plan", "sub-1", "golem-hartland")
        }

        "no headers ⇒ all null — the row is still written, with NULLs" {
            CallAttribution.from(headersOf()) shouldBe CallAttribution()
        }

        "blank ⇒ null and surrounding whitespace is trimmed" {
            CallAttribution.from(
                headersOf(
                    "X-Turn-Ref" to listOf("  t1 "),
                    "X-Call-Purpose" to listOf("   "),
                    "X-Agent-Id" to listOf(""),
                ),
            ) shouldBe CallAttribution(turnRef = "t1")
        }

        "a value over 512 characters is truncated to 512, never refused" {
            val long = "p".repeat(2_000)
            val a =
                CallAttribution.from(
                    headersOf(
                        "X-Call-Purpose" to listOf(long),
                        "X-End-User-Subject" to listOf(long),
                    ),
                )
            a.purpose!!.shouldHaveLength(512)
            a.endUserSubject!!.shouldHaveLength(512)
        }
    })
