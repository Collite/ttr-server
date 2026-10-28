// SPDX-License-Identifier: Apache-2.0
package org.tatrman.lexicon.transport

import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.NoSuchFileException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * #273: HTTP is opt-in; an unset URL list preserves the mounted ConfigMap path.
 * URLs replace the entire local list, preserving primary/additive order. Paths remain the readers'
 * opaque identity/provenance keys; a remote key is never opened on the filesystem.
 * Body reads enforce a 30 s deadline measured from request start; connect/headers are not bounded
 * by it. One body read may overrun it by up to the 5 s read timeout.
 */
class ArchiveInputs(
    localPaths: List<Path>,
    urls: List<String> = emptyList(),
    downloadDeadlineMillis: Long = 30_000,
) {
    private val remotes =
        urls.map { it.trim() }.filter { it.isNotEmpty() }.distinct().associate { url ->
            val uri = URI(url)
            require(
                uri.scheme in setOf("http", "https") &&
                    !uri.host.isNullOrBlank() &&
                    uri.userInfo == null &&
                    uri.fragment == null,
            ) {
                "Lexicon archive URL must be HTTP(S), without credentials or a fragment"
            }
            Path.of(url) to RemoteArchive(uri, downloadDeadlineMillis)
        }
    val isRemote: Boolean get() = remotes.isNotEmpty()

    val paths: List<Path> = if (isRemote) remotes.keys.toList() else localPaths

    fun location(path: Path): String = remotes[path]?.location ?: path.toString()

    fun read(path: Path): ByteArray = remotes[path]?.read() ?: Files.readAllBytes(path)
}

private class RemoteArchive(
    private val uri: URI,
    private val downloadDeadlineMillis: Long,
) {
    val location: String get() = uri.toString()

    init {
        require(downloadDeadlineMillis > 0) { "Download deadline must be positive" }
    }

    private var cached: ByteArray? = null
    private var etag: String? = null

    @Synchronized
    fun read(): ByteArray {
        val started = System.nanoTime()
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 3000
        connection.readTimeout = 5000
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        etag?.let { connection.setRequestProperty("If-None-Match", it) }
        try {
            when (val status = connection.responseCode) {
                304 -> return cached ?: throw IOException("Lexicon HTTP 304 without a cached archive from $uri")
                200 -> {
                    val validator =
                        connection.getHeaderField("ETag")?.takeIf { it.isNotBlank() }
                            ?: throw IOException("Lexicon HTTP 200 without ETag from $uri")
                    val length = connection.contentLengthLong
                    if (length > MAX_ARCHIVE_BYTES) throw IOException("Lexicon archive exceeds 50 MiB from $uri")
                    val bytes = connection.inputStream.use { readBody(it, connection, started, MAX_ARCHIVE_BYTES) }
                    if (length >= 0 &&
                        bytes.size.toLong() != length
                    ) {
                        throw IOException("Truncated lexicon archive from $uri")
                    }
                    // Decoding and last-good semantics remain with each consumer, exactly as for local files.
                    cached = bytes
                    etag = validator
                    return bytes
                }
                404 -> {
                    // Only the route's explicit absence is quiet; typos/proxy errors remain loud.
                    val body =
                        connection.errorStream?.use {
                            readBody(
                                it,
                                connection,
                                started,
                                4096,
                            ).toString(Charsets.UTF_8)
                        }
                    val error =
                        runCatching {
                            (Json.parseToJsonElement(body.orEmpty()) as? JsonObject)?.get("error") as? JsonPrimitive
                        }.getOrNull()
                    if (error?.isString == true &&
                        error.content == "lexicon not found"
                    ) {
                        throw NoSuchFileException(uri.toString())
                    }
                    throw IOException("Lexicon HTTP 404 from $uri")
                }
                else -> throw IOException("Lexicon HTTP $status from $uri")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readBody(
        input: InputStream,
        connection: HttpURLConnection,
        started: Long,
        limit: Int,
    ): ByteArray {
        fun checkDeadline() {
            if ((System.nanoTime() - started) / 1_000_000 >= downloadDeadlineMillis) {
                connection.disconnect()
                throw IOException("Lexicon download exceeded ${downloadDeadlineMillis / 1000.0} s from $uri")
            }
        }
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        while (true) {
            checkDeadline()
            val count = input.read(chunk, 0, minOf(chunk.size, limit + 1 - output.size()))
            checkDeadline()
            if (count < 0) break
            output.write(chunk, 0, count)
            if (output.size() > limit) throw IOException("Lexicon response exceeds $limit bytes from $uri")
        }
        return output.toByteArray()
    }

    private companion object {
        const val MAX_ARCHIVE_BYTES = 50 * 1024 * 1024
    }
}
