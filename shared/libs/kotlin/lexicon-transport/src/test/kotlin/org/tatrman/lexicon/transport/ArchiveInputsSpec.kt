// SPDX-License-Identifier: Apache-2.0
package org.tatrman.lexicon.transport

import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files

class ArchiveInputsSpec :
    StringSpec({
        "conditional HTTP reuses exact bytes on 304 and retries outages with the previous ETag" {
            val requests = mutableListOf<String?>()
            var version = "\"commit1\""
            var bytes = byteArrayOf(1, 2, -1)
            var status = 200
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/model/lexicon.tar.zst") { request ->
                val validator = request.requestHeaders.getFirst("If-None-Match")
                requests.add(validator)
                request.responseHeaders.add("ETag", version)
                val code = if (status == 200 && validator == version) 304 else status
                request.sendResponseHeaders(code, if (code == 200) bytes.size.toLong() else -1)
                if (code == 200) request.responseBody.write(bytes)
                request.close()
            }
            server.start()
            try {
                val inputs =
                    ArchiveInputs(
                        emptyList(),
                        listOf("http://127.0.0.1:${server.address.port}/model/lexicon.tar.zst?source=world"),
                    )
                val key = inputs.paths.single()
                val first = inputs.read(key)
                first.toList() shouldBe bytes.toList()
                inputs.read(key) shouldBeSameInstanceAs first
                status = 503
                shouldThrow<IOException> { inputs.read(key) }
                status = 200
                inputs.read(key) shouldBeSameInstanceAs first
                version = "\"commit2\""
                bytes = byteArrayOf(4, 5)
                inputs.read(key).toList() shouldBe bytes.toList()
                inputs.read(key).toList() shouldBe bytes.toList()
                requests shouldBe
                    listOf(null, "\"commit1\"", "\"commit1\"", "\"commit1\"", "\"commit1\"", "\"commit2\"")
            } finally {
                server.stop(0)
            }
        }

        "missing validators, unseeded 304 and redirects fail without replacing the HTTP cache" {
            var status = 304
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { request ->
                request.responseHeaders.add("Location", "/elsewhere")
                request.sendResponseHeaders(status, -1)
                request.close()
            }
            server.start()
            try {
                val inputs = ArchiveInputs(emptyList(), listOf("http://127.0.0.1:${server.address.port}/"))
                listOf(304, 200, 302).forEach {
                    status = it
                    shouldThrow<IOException> { inputs.read(inputs.paths.single()) }
                }
            } finally {
                server.stop(0)
            }
        }

        "truncated or oversized HTTP responses are not cached under their ETag" {
            var length = 10L
            var complete = false
            val validators = mutableListOf<String?>()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { request ->
                validators.add(request.requestHeaders.getFirst("If-None-Match"))
                request.responseHeaders.add("ETag", "\"same\"")
                request.sendResponseHeaders(200, length)
                try {
                    if (complete) {
                        request.responseBody.write(ByteArray(length.toInt()))
                    } else {
                        request.responseBody.write(byteArrayOf(1))
                    }
                } finally {
                    try {
                        request.close()
                    } catch (_: IOException) {
                    }
                }
            }
            server.start()
            try {
                val inputs = ArchiveInputs(emptyList(), listOf("http://127.0.0.1:${server.address.port}/"))
                val key = inputs.paths.single()
                shouldThrow<IOException> { inputs.read(key) }
                length = 50L * 1024 * 1024 + 1
                shouldThrow<IOException> { inputs.read(key) }
                length = 2
                complete = true
                inputs.read(key).size shouldBe 2
                validators shouldBe listOf(null, null, null)
            } finally {
                server.stop(0)
            }
        }

        "only explicit archive absence is quiet; unknown sources and malformed 404s remain errors" {
            var body = "{\"error\":\"lexicon not found\"}"
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { request ->
                val bytes = body.toByteArray()
                request.sendResponseHeaders(404, bytes.size.toLong())
                request.responseBody.use { it.write(bytes) }
                request.close()
            }
            server.start()
            try {
                val url = "http://127.0.0.1:${server.address.port}/"
                val inputs = ArchiveInputs(emptyList(), listOf(url))
                inputs.location(inputs.paths.single()) shouldBe url
                shouldThrow<java.nio.file.NoSuchFileException> { inputs.read(inputs.paths.single()) }
                body = "{\"error\":\"unknown lexicon source\"}"
                shouldThrow<IOException> { inputs.read(inputs.paths.single()) }.javaClass shouldBe
                    IOException::class.java
                body = "not json"
                shouldThrow<IOException> { inputs.read(inputs.paths.single()) }.javaClass shouldBe
                    IOException::class.java
            } finally {
                server.stop(0)
            }
        }

        "total deadline cuts off a trickling body and preserves the previous ETag and bytes" {
            var trickle = false
            val validators = mutableListOf<String?>()
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { request ->
                validators.add(request.requestHeaders.getFirst("If-None-Match"))
                try {
                    if (trickle) {
                        request.responseHeaders.add("ETag", "\"new\"")
                        request.sendResponseHeaders(200, 0)
                        repeat(20) {
                            request.responseBody.write(1)
                            request.responseBody.flush()
                            Thread.sleep(100)
                        }
                    } else if (validators.last() != null) {
                        request.sendResponseHeaders(304, -1)
                    } else {
                        request.responseHeaders.add("ETag", "\"old\"")
                        request.sendResponseHeaders(200, 1)
                        request.responseBody.write(7)
                    }
                } catch (_: IOException) {
                } finally {
                    request.close()
                }
            }
            server.start()
            try {
                val inputs = ArchiveInputs(emptyList(), listOf("http://127.0.0.1:${server.address.port}/"), 500)
                val key = inputs.paths.single()
                val first = inputs.read(key)
                trickle = true
                val started = System.nanoTime()
                shouldThrow<IOException> { inputs.read(key) }.message!! shouldBe
                    "Lexicon download exceeded 0.5 s from http://127.0.0.1:${server.address.port}/"
                ((System.nanoTime() - started) / 1_000_000 < 1100) shouldBe true
                trickle = false
                inputs.read(key) shouldBeSameInstanceAs first
                validators shouldBe listOf(null, "\"old\"", "\"old\"")
            } finally {
                server.stop(0)
            }
        }

        "unset URLs read mounted files while explicit URLs replace the whole local list" {
            val path = Files.createTempFile("lexicon-local", ".zst")
            try {
                Files.write(path, byteArrayOf(8))
                val local = ArchiveInputs(listOf(path))
                local.paths shouldBe listOf(path)
                local.read(path).toList() shouldBe listOf<Byte>(8)
                val remote = ArchiveInputs(listOf(path), listOf("http://localhost:1/model/lexicon.tar.zst"))
                remote.paths.contains(path) shouldBe false
                shouldThrow<IOException> { remote.read(remote.paths.single()) }
            } finally {
                Files.delete(path)
            }
        }

        "invalid remote configurations are rejected rather than silently falling back to mounted content" {
            listOf(
                "file:///tmp/lexicon",
                "http:/missing-host",
                "https://user:pass@example.com/a",
                "https://example.com/a#fragment",
            ).forEach {
                shouldThrow<IllegalArgumentException> { ArchiveInputs(emptyList(), listOf(it)) }
            }
        }
    })
