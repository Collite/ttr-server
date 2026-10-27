// SPDX-License-Identifier: Apache-2.0
package org.tatrman.lexicon.transport

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * #273: HTTP is opt-in; an unset URL list preserves the mounted ConfigMap path.
 * URLs replace the entire local list, preserving primary/additive order. Paths remain the readers'
 * opaque identity/provenance keys; a remote key is never opened on the filesystem.
 */
class ArchiveInputs(
    localPaths: List<Path>,
    urls: List<String> = emptyList(),
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
            Path.of(url) to RemoteArchive(uri)
        }
    val paths: List<Path> = if (remotes.isEmpty()) localPaths else remotes.keys.toList()

    fun read(path: Path): ByteArray = remotes[path]?.read() ?: Files.readAllBytes(path)
}

private class RemoteArchive(
    private val uri: URI,
) {
    private var cached: ByteArray? = null
    private var etag: String? = null

    @Synchronized
    fun read(): ByteArray {
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
                    val bytes = connection.inputStream.use { it.readNBytes(MAX_ARCHIVE_BYTES + 1) }
                    if (length >= 0 &&
                        bytes.size.toLong() != length
                    ) {
                        throw IOException("Truncated lexicon archive from $uri")
                    }
                    if (bytes.size > MAX_ARCHIVE_BYTES) throw IOException("Lexicon archive exceeds 50 MiB from $uri")
                    // Decoding and last-good semantics remain with each consumer, exactly as for local files.
                    cached = bytes
                    etag = validator
                    return bytes
                }
                else -> throw IOException("Lexicon HTTP $status from $uri")
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val MAX_ARCHIVE_BYTES = 50 * 1024 * 1024
    }
}
