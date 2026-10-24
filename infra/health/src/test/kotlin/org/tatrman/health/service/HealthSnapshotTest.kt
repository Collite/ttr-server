// SPDX-License-Identifier: Apache-2.0
package org.tatrman.health.service

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.tatrman.health.config.HealthConfig
import org.tatrman.health.config.TechnologyConfig
import java.net.ServerSocket
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

private class MovingClock(
    var now: Instant,
) : Clock() {
    override fun getZone() = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?) = this

    override fun instant() = now
}

/** The rollup fans out once per [HealthCheckService] snapshot TTL, however many callers ask. */
class HealthSnapshotTest :
    StringSpec({

        "one fan-out serves every caller within the TTL, concurrent ones included; the next one after it" {
            val accepted = AtomicInteger()
            ServerSocket(0).use { server ->
                val acceptor =
                    Thread {
                        runCatching { while (true) server.accept().use { accepted.incrementAndGet() } }
                    }.apply {
                        isDaemon = true
                        start()
                    }
                val clock = MovingClock(Instant.parse("2026-10-23T10:00:00Z"))
                val service =
                    HealthCheckService(
                        HealthConfig(
                            technologies =
                                mapOf(
                                    "db" to
                                        TechnologyConfig(
                                            type = "tcp",
                                            host = "127.0.0.1",
                                            port = server.localPort,
                                            timeout = 2_000,
                                        ),
                                ),
                        ),
                        snapshotTtl = Duration.ofSeconds(15),
                        clock = clock,
                    )

                coroutineScope { repeat(5) { launch { service.checkAllHealth() } } }
                service.checkAllHealth(threshold = 0).summary.healthy shouldBe 1
                Thread.sleep(200)
                accepted.get() shouldBe 1

                clock.now = clock.now.plusSeconds(16)
                service.checkAllHealth()
                Thread.sleep(200)
                accepted.get() shouldBe 2
                acceptor.interrupt()
            }
        }
    })
