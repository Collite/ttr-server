// SPDX-License-Identifier: Apache-2.0
package org.tatrman.health.checker

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/** A blocking call (a name lookup, a connect) cannot outlive the timeout it runs under. */
class BlockingTest :
    StringSpec({

        "a block that outlasts the timeout answers null on time" {
            val started = System.nanoTime()
            blockingWithin(100) {
                Thread.sleep(3_000)
                "late"
            }.shouldBeNull()
            (System.nanoTime() - started) / 1_000_000 shouldBeLessThan 1_000L
        }

        "a block within the timeout answers its value" {
            blockingWithin(1_000) { "on time" } shouldBe "on time"
        }

        "a TCP check of a closed port is unhealthy with the reason, not an exception" {
            val port = java.net.ServerSocket(0).use { it.localPort }
            val result = TcpHealthChecker("db", "127.0.0.1", port, timeout = 1_000).check()

            result.status shouldBe "unhealthy"
            (result.error ?: "").isNotBlank() shouldBe true
        }
    })
