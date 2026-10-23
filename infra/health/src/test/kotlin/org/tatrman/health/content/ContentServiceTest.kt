// SPDX-License-Identifier: Apache-2.0
package org.tatrman.health.content

import org.tatrman.health.config.FieldConfig
import org.tatrman.health.config.ProbeConfig
import org.tatrman.health.config.StatusConfig
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger
import java.time.Instant
import java.time.ZoneOffset

private val JSON = headersOf(HttpHeaders.ContentType, "application/json")

/** A clock the test moves by hand. */
private class TestClock(
    var now: Instant,
) : Clock() {
    override fun getZone() = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId?) = this

    override fun instant() = now
}

class ContentServiceTest :
    StringSpec({

        "a dotted path reads nested fields, array items and sizes" {
            val body =
                Json.parseToJsonElement(
                    """{"serving":{"loaded_at":"2026-10-09T10:12:00Z"},"workers":[{"n":"a"},{"n":"b"}],"x":null}""",
                )

            body.text("serving.loaded_at") shouldBe "2026-10-09T10:12:00Z"
            body.text("workers.1.n") shouldBe "b"
            body.text("workers#") shouldBe "2"
            body.text("serving#") shouldBe "1"
            body.at("missing.path") shouldBe null
            body.at("x") shouldBe null
        }

        "times are ISO-8601 whether the service sends ISO, an offset, epoch seconds or epoch millis" {
            isoTime(Json.parseToJsonElement("\"2026-10-09T10:12:00Z\"")) shouldBe "2026-10-09T10:12:00Z"
            isoTime(Json.parseToJsonElement("\"2026-10-09T12:12:00+02:00\"")) shouldBe "2026-10-09T10:12:00Z"
            isoTime(Json.parseToJsonElement("1791540720")) shouldBe "2026-10-09T10:12:00Z"
            isoTime(Json.parseToJsonElement("1791540720000")) shouldBe "2026-10-09T10:12:00Z"
            isoTime(Json.parseToJsonElement("1791540720.5")) shouldBe "2026-10-09T10:12:00Z"
            isoTime(Json.parseToJsonElement("\"yesterday\"")) shouldBe "yesterday"
        }

        "the generic adapter reads the configured paths and leaves out what the body lacks" {
            val body =
                Json.parseToJsonElement(
                    """{"status":"UP","commit":"a0dfb2a9","serving":{"loaded_at":1791540720},"object_count":214}""",
                )
            val config =
                StatusConfig(
                    url = "http://x/status",
                    at = "serving.loaded_at",
                    commit = "commit",
                    ok = "status",
                    okValues = listOf("up"),
                    summary =
                        listOf(
                            FieldConfig("objects", "object_count", "count"),
                            FieldConfig("queries", "queries_total", "count"),
                        ),
                )

            val content = GenericJson.read(body, config)

            content.ok shouldBe true
            content.at shouldBe "2026-10-09T10:12:00Z"
            content.commit shouldBe "a0dfb2a9"
            content.summary shouldContainExactly listOf(ContentPair("objects", "214", "count"))
        }

        "a body whose ok field is not an accepted value is not ok" {
            val config = StatusConfig(url = "u", ok = "status", okValues = listOf("ok"))

            GenericJson.read(Json.parseToJsonElement("""{"status":"failed"}"""), config).ok shouldBe false
            GenericJson.read(Json.parseToJsonElement("""{}"""), config).ok shouldBe false
            GenericJson.read(Json.parseToJsonElement("""{"status":"OK"}"""), config).ok shouldBe true
        }

        "a failed read is a content with the reason, never a failed answer" {
            val service =
                ContentService(
                    mapOf(
                        "missing" to StatusConfig(url = "http://missing/status"),
                        "html" to StatusConfig(url = "http://html/status"),
                        "slow" to StatusConfig(url = "http://slow/status", timeout = 50),
                        "fine" to StatusConfig(url = "http://fine/status", commit = "commit"),
                        "odd" to StatusConfig(url = "http://fine/status", adapter = "no-such-adapter"),
                    ),
                )
            service.replaceClient(
                HttpClient(
                    MockEngine { request ->
                        when (request.url.host) {
                            "missing" -> respond("", HttpStatusCode.NotFound)
                            "html" -> respond("<html>", HttpStatusCode.OK)
                            "slow" -> {
                                delay(1_000)
                                respond("{}", HttpStatusCode.OK, JSON)
                            }
                            else -> respond("""{"commit":"a0dfb2a"}""", HttpStatusCode.OK, JSON)
                        }
                    },
                ),
            )

            val all = service.all().technologies

            all.getValue("missing").ok shouldBe false
            all.getValue("missing").error shouldBe "status answered 404"
            all.getValue("html").error!! shouldContain "not JSON"
            all.getValue("slow").error shouldBe "status timed out after 50 ms"
            all.getValue("odd").error shouldBe "unknown adapter 'no-such-adapter'"
            all.getValue("fine").ok shouldBe true
            all.getValue("fine").commit shouldBe "a0dfb2a"
        }

        "the answer is kept for the TTL, then read again" {
            var reads = 0
            val clock = TestClock(Instant.parse("2026-10-09T10:00:00Z"))
            val service =
                ContentService(mapOf("veles" to StatusConfig(url = "http://veles/status")), clock = clock)
            service.replaceClient(
                HttpClient(
                    MockEngine {
                        reads++
                        respond("{}", HttpStatusCode.OK, JSON)
                    },
                ),
            )

            service.all()
            clock.now = clock.now.plusSeconds(29)
            service.all()
            reads shouldBe 1

            clock.now = clock.now.plusSeconds(2)
            service.all()
            reads shouldBe 2
        }

        "an answer with a failed read is kept for the failure TTL only" {
            // Atomic: the two reads run in parallel, and a plain `var` lost an increment in CI.
            val reads = AtomicInteger()
            var failing = true
            val clock = TestClock(Instant.parse("2026-10-09T10:00:00Z"))
            val service =
                ContentService(
                    mapOf(
                        "veles" to StatusConfig(url = "http://veles/status"),
                        "translate" to StatusConfig(url = "http://translate/status"),
                    ),
                    clock = clock,
                )
            service.replaceClient(
                HttpClient(
                    MockEngine { request ->
                        reads.incrementAndGet()
                        if (failing && request.url.host == "translate") {
                            respond("", HttpStatusCode.ServiceUnavailable)
                        } else {
                            respond("{}", HttpStatusCode.OK, JSON)
                        }
                    },
                ),
            )

            service
                .all()
                .technologies
                .getValue("translate")
                .ok shouldBe false
            clock.now = clock.now.plusSeconds(4)
            service.all()
            reads.get() shouldBe 2

            failing = false
            clock.now = clock.now.plusSeconds(2)
            service
                .all()
                .technologies
                .getValue("translate")
                .ok shouldBe true
            reads.get() shouldBe 4

            // All read: the full TTL again.
            clock.now = clock.now.plusSeconds(20)
            service.all()
            reads.get() shouldBe 4
        }

        "a figure the body carries empty is no figure" {
            val content =
                GenericJson.read(
                    Json.parseToJsonElement("""{"active_runs":0,"cache":{"entries":0,"current_model_version":""}}"""),
                    StatusConfig(
                        url = "http://x/status",
                        summary =
                            listOf(
                                FieldConfig("activeRuns", "active_runs", "count"),
                                FieldConfig("model", "cache.current_model_version", "text"),
                            ),
                    ),
                )

            content.summary.map { it.k } shouldContainExactly listOf("activeRuns")
        }

        "probes add one sub-row each: 2xx ready, another answer not ready, none down; tcp ready when it connects" {
            val listening = java.net.ServerSocket(0)
            val closed = java.net.ServerSocket(0).also { it.close() }
            try {
                val service =
                    ContentService(
                        mapOf(
                            "nlp" to
                                StatusConfig(
                                    url = "http://nlp/status",
                                    summary = listOf(FieldConfig("packs", "packs_loaded", "count")),
                                    probes =
                                        listOf(
                                            ProbeConfig("front", "http://front/readyz", tech = "nlp :7270"),
                                            ProbeConfig("stanza", "http://sync/status"),
                                            ProbeConfig("gone", "http://gone/readyz"),
                                            ProbeConfig(
                                                "Stanza",
                                                "tcp://127.0.0.1:${listening.localPort}",
                                                tech = "nlp-stanza",
                                            ),
                                            ProbeConfig("spaCy", "tcp://127.0.0.1:${closed.localPort}"),
                                        ),
                                ),
                        ),
                    )
                service.replaceClient(
                    HttpClient(
                        MockEngine { request ->
                            when (request.url.host) {
                                "front" -> respond("{}", HttpStatusCode.OK, JSON)
                                "sync" -> respond("{}", HttpStatusCode.ServiceUnavailable, JSON)
                                "gone" -> throw java.net.ConnectException("connection refused")
                                else -> respond("""{"packs_loaded":6}""", HttpStatusCode.OK, JSON)
                            }
                        },
                    ),
                )

                val content = service.all().technologies.getValue("nlp")

                content.summary.map { it.k to it.v } shouldContainExactly listOf("packs" to "6")
                content.children shouldBe
                    listOf(
                        ContentChild("front", "nlp :7270", "ready"),
                        ContentChild("stanza", null, "notReady"),
                        ContentChild("gone", null, "down"),
                        ContentChild("Stanza", "nlp-stanza", "ready"),
                        ContentChild("spaCy", null, "down"),
                    )
            } finally {
                listening.close()
            }
        }

        "a failed status read still shows its probes" {
            val service =
                ContentService(
                    mapOf(
                        "nlp" to
                            StatusConfig(
                                url = "http://nlp/status",
                                probes = listOf(ProbeConfig("front", "http://front/readyz")),
                            ),
                    ),
                )
            service.replaceClient(
                HttpClient(
                    MockEngine { request ->
                        if (request.url.host == "front") {
                            respond("{}", HttpStatusCode.OK, JSON)
                        } else {
                            respond("", HttpStatusCode.NotFound)
                        }
                    },
                ),
            )

            val content = service.all().technologies.getValue("nlp")

            content.error shouldBe "status answered 404"
            content.children shouldBe listOf(ContentChild("front", null, "ready"))
        }

        "services on one commit agree, whatever length they report it at" {
            val summary =
                ContentService.summarize(
                    mapOf(
                        "veles" to Content(ok = true, commit = "a0dfb2a", at = "2026-10-09T10:12:00Z"),
                        "translate" to Content(ok = true, commit = "a0dfb2a91c3e", at = "2026-10-09T10:14:00Z"),
                        "validate" to Content(ok = true),
                    ),
                )

            summary.agree shouldBe true
            summary.commit shouldBe "a0dfb2a91c3e"
            summary.byService shouldBe mapOf("veles" to "a0dfb2a", "translate" to "a0dfb2a91c3e")
            summary.lastRefresh shouldBe "2026-10-09T10:14:00Z"
        }

        "services on different commits do not agree and name no commit" {
            val summary =
                ContentService.summarize(
                    mapOf(
                        "veles" to Content(ok = true, commit = "a0dfb2a"),
                        "translate" to Content(ok = true, commit = "527d04f"),
                    ),
                )

            summary.agree shouldBe false
            summary.commit shouldBe null
        }

        "no commit reported at all is agreement about nothing" {
            val summary = ContentService.summarize(mapOf("opa" to Content(ok = true)))

            summary.agree shouldBe true
            summary.commit shouldBe null
            summary.byService shouldBe emptyMap()
        }
    })
