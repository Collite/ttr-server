// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.engine

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.Executors

/** S3.2 T3 (D-159): the engine's `GET /history/task` as the caller; anything but a 200 array fails closed (503). */
class HttpEngineHistorySpec :
    StringSpec({
        fun serve(
            status: Int,
            body: String,
            pauseMs: Long = 0,
            seen: MutableList<Pair<String, String?>> = mutableListOf(),
        ): HttpServer =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                executor = Executors.newCachedThreadPool()
                createContext("/engine-rest/history/task") { exchange ->
                    seen += exchange.requestURI.rawQuery to exchange.requestHeaders.getFirst("Authorization")
                    Thread.sleep(pauseMs)
                    val bytes = body.toByteArray()
                    exchange.sendResponseHeaders(status, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                start()
            }

        fun HttpServer.url() = "http://127.0.0.1:${address.port}/engine-rest/"

        "a 200 array reads as tasks; the caller's bearer and the task id travel, encoded" {
            val seen = mutableListOf<Pair<String, String?>>()
            val server =
                serve(
                    200,
                    """[{"id":"t 1","processInstanceId":"pi-1","assignee":"maya","endTime":"2026-11-14T06:00:00.000+0000",
                        "deleteReason":"completed","name":"Decide"}]""",
                    seen = seen,
                )
            try {
                HttpEngineHistory(server.url()).task("t 1", "maya-token") shouldBe
                    listOf(HistoricTask("t 1", "pi-1", "maya", "2026-11-14T06:00:00.000+0000", "completed"))
                seen.single() shouldBe ("taskId=t+1" to "Bearer maya-token")
            } finally {
                server.stop(0)
            }
        }

        "a non-200, a non-array body and a slow engine are 503 engine-unreachable, never a pass" {
            for ((status, body) in listOf(401 to "{}", 500 to """{"type":"RestException"}""", 200 to "{}")) {
                val server = serve(status, body)
                try {
                    shouldThrow<AutomationException> {
                        HttpEngineHistory(server.url()).task("t", "b")
                    }.code shouldBe ErrorCode.ENGINE_UNREACHABLE
                } finally {
                    server.stop(0)
                }
            }
            val slow = serve(200, "[]", pauseMs = 2_000)
            try {
                shouldThrow<AutomationException> {
                    HttpEngineHistory(slow.url(), timeout = Duration.ofMillis(300)).task("t", "b")
                }.code shouldBe ErrorCode.ENGINE_UNREACHABLE
            } finally {
                slow.stop(0)
            }
            shouldThrow<AutomationException> {
                HttpEngineHistory("http://127.0.0.1:1/engine-rest").task("t", "b")
            }.code shouldBe ErrorCode.ENGINE_UNREACHABLE
            shouldThrow<AutomationException> { NoEngine.task("t", "b") }.code shouldBe ErrorCode.ENGINE_UNREACHABLE
        }
    })
