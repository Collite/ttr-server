// SPDX-License-Identifier: Apache-2.0
package org.tatrman.grounding.mcp

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.grounding.mcp.client.ScanCapability
import org.tatrman.grounding.v1.GetStatusResponse
import kotlin.time.Duration.Companion.seconds

/** OL-C4 — grounding-mcp reads chrono's `scan` capability as the resolver does (OL-C2): kept for a TTL, re-read after. */
class ScanCapabilityTest :
    StringSpec({
        "a failed GetStatus is no scan until the TTL passes, then it is read again" {
            var now = 0L
            var reads = 0
            var fail = true
            val capability =
                ScanCapability(60.seconds, nowNanos = { now }) {
                    reads++
                    if (fail) {
                        error(
                            "UNAVAILABLE",
                        )
                    } else {
                        GetStatusResponse.newBuilder().putCapabilities("scan", "true").build()
                    }
                }
            capability.available() shouldBe false
            fail = false
            now += 59.seconds.inWholeNanoseconds
            capability.available() shouldBe false
            reads shouldBe 1
            now += 1.seconds.inWholeNanoseconds
            capability.available() shouldBe true
            reads shouldBe 2
        }
    })
