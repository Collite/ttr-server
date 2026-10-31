// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.refresh

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tatrman.ttr.metadata.source.ModelStorage
import org.tatrman.ttr.metadata.source.StorageFile
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** A GET produces one immutable in-memory tree; list/read never revisit the network or extract files. */
internal class HttpTreeStorage(
    override val id: String,
    url: String,
    private val token: String? = null,
    private val subdirectory: String = "",
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
    private val maxBytes: Int = 32 * 1024 * 1024,
) : ModelStorage {
    private val uri =
        runCatching { URI(url) }.getOrElse {
            throw IllegalArgumentException("invalid_source_config: malformed HTTP url")
        }

    private data class Tree(
        val version: String,
        val etag: String?,
        val files: Map<String, ByteArray>,
    )

    private var tree: Tree? = null

    init {
        require(
            uri.scheme in setOf("http", "https") && uri.host != null && uri.rawUserInfo == null && uri.fragment == null,
        ) {
            "invalid_source_config: HTTP url requires http/https host without user-info or fragment"
        }
        require(token?.any { it == '\r' || it == '\n' } != true) { "invalid_source_config: invalid HTTP token" }
        require(connectTimeoutMs > 0 && readTimeoutMs > 0 && maxBytes in 1..(Int.MAX_VALUE - 1)) {
            "invalid_source_config: HTTP timeouts and max-bytes must be positive and bounded"
        }
    }

    override fun fetchVersion(): String {
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/x-tar+gzip, application/gzip, application/octet-stream")
        token?.takeIf { it.isNotBlank() }?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        tree?.etag?.let { connection.setRequestProperty("If-None-Match", it) }
        try {
            when (val status = connection.responseCode) {
                304 -> return tree?.version ?: error("http_source_304_without_cache")
                200 -> {
                    val body = connection.inputStream.use { it.readNBytes(maxBytes + 1) }
                    check(body.size <= maxBytes) { "http_source_response_too_large" }
                    val files = decode(body)
                    val version =
                        MessageDigest
                            .getInstance(
                                "SHA-256",
                            ).digest(body)
                            .joinToString("") { "%02x".format(it) }
                    tree = Tree(version, connection.getHeaderField("ETag")?.takeIf { it.isNotBlank() }, files)
                    return version
                }
                else -> error("http_source_status_$status")
            }
        } catch (e: Exception) {
            // Neither URL/query, bearer, upstream body nor archive names enter exception diagnostics.
            throw IllegalStateException(
                e.message?.takeIf { it.startsWith("http_source_") }
                    ?: "http_source_read_failed: ${e.javaClass.simpleName}",
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun decode(body: ByteArray): Map<String, ByteArray> {
        val files = linkedMapOf<String, ByteArray>()
        val paths = mutableSetOf<String>()
        var size = 0L
        var entries = 0
        // Bound tar metadata/padding as well as file bytes, before the archive reader allocates headers.
        val expanded = ExpandedLimit(GZIPInputStream(ByteArrayInputStream(body)), maxBytes.toLong() + 10_240_000)
        TarArchiveInputStream(expanded).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                check(++entries <= 10_000) { "http_source_too_many_entries" }
                check(entry.isCheckSumOK) { "http_source_invalid_tar_checksum" }
                val name = entry.name
                check(!name.startsWith('/') && !name.contains('\\') && name.split('/').none { it == ".." }) {
                    "http_source_unsafe_path"
                }
                val path = name.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
                check(path.isNotEmpty() || entry.isDirectory) { "http_source_unsafe_path" }
                check(entry.isDirectory || (entry.isFile && !entry.isLink && !entry.isSymbolicLink)) {
                    "http_source_unsafe_entry"
                }
                if (entry.isDirectory) continue
                check(paths.add(path)) { "http_source_duplicate_path" }
                size += entry.size
                check(entry.size >= 0 && size <= maxBytes) { "http_source_expanded_too_large" }
                val bytes = tar.readNBytes(entry.size.toInt())
                check(bytes.size.toLong() == entry.size) { "http_source_truncated_entry" }
                val relative = if (subdirectory.isEmpty()) path else path.removePrefix("$subdirectory/")
                if (subdirectory.isEmpty() || path.startsWith("$subdirectory/")) files[relative] = bytes
            }
            // Consume the gzip trailer too: corrupt CRC/truncation must not become a cached tree.
            val discard = ByteArray(8192)
            while (expanded.read(discard) != -1) { /* bounded drain */ }
        }
        return files.toMap()
    }

    override fun listFiles(
        extensions: List<String>,
        prefixes: List<String>,
    ): List<StorageFile> =
        tree
            ?.files
            .orEmpty()
            .entries
            .sortedBy { it.key }
            .filter { (path, _) ->
                val name = path.substringAfterLast('/')
                extensions.any { name.endsWith(".$it") } && (prefixes.isEmpty() || prefixes.any { name.startsWith(it) })
            }.map { (path, bytes) -> StorageFile("/$path", bytes.size.toLong(), Path.of("/")) }

    override fun read(file: StorageFile): String =
        checkNotNull(
            tree?.files?.get(file.path.removePrefix("/")),
        ) { "http_source_file_not_found" }.toString(Charsets.UTF_8)
}

/** Limit the entire inflated stream, including tar headers that aren't regular-file content. */
private class ExpandedLimit(
    input: InputStream,
    private var remaining: Long,
) : FilterInputStream(input) {
    override fun read(): Int = super.read().also { if (it != -1) used(1L) }

    override fun read(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ): Int =
        super.read(bytes, offset, minOf(length.toLong(), remaining + 1).toInt()).also {
            if (it >
                0
            ) {
                used(it.toLong())
            }
        }

    override fun skip(count: Long): Long = super.skip(minOf(count, remaining + 1)).also { used(it) }

    private fun used(count: Long) {
        remaining -= count
        check(remaining >= 0) { "http_source_expanded_too_large" }
    }
}
