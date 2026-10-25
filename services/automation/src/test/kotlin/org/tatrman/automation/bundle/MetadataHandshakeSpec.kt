// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.bundle

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * AU-server S0.2 T6 — does Veles serve the model the bundle was built against: the served commit against Veles's
 * source versions (`GetStatus.sources[].version` — a git source's commit). A drift is a warning (`modelDrift`),
 * an unreachable Veles is "not known" (null), never a refusal.
 */
class VelesHandshakeSpec :
    StringSpec({
        val commit = "c0ffee"

        fun handshake(
            versions: Map<String, String>?,
            source: String? = null,
        ) = VelesHandshake({ versions }, source)

        "Veles at the bundle's commit: no drift; at another one: drift" {
            handshake(mapOf("builtin-stock" to "stock", "hartland" to commit)).drift(commit) shouldBe false
            handshake(mapOf("builtin-stock" to "stock", "hartland" to "beef")).drift(commit) shouldBe true
        }

        "a configured source is the one compared; a source Veles does not have is not known" {
            handshake(mapOf("a" to commit, "hartland" to "beef"), source = "hartland").drift(commit) shouldBe true
            handshake(mapOf("a" to "beef", "hartland" to commit), source = "hartland").drift(commit) shouldBe false
            handshake(mapOf("a" to commit), source = "hartland").drift(commit) shouldBe null
        }

        "Veles unreachable: not known" {
            handshake(null).drift(commit) shouldBe null
        }

        "the answer is cached for the ttl, then asked again" {
            var calls = 0
            var now = Instant.parse("2026-10-19T06:00:00Z")
            val clock =
                object : Clock() {
                    override fun getZone() = ZoneOffset.UTC

                    override fun withZone(zone: java.time.ZoneId?) = this

                    override fun instant() = now
                }
            val h =
                VelesHandshake({
                    calls++
                    mapOf("hartland" to commit)
                }, null, Duration.ofSeconds(30), clock)
            h.drift(commit)
            h.drift(commit)
            calls shouldBe 1
            now = now.plusSeconds(31)
            h.drift(commit)
            calls shouldBe 2
        }
    })
