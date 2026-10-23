// SPDX-License-Identifier: Apache-2.0
package org.tatrman.health.checker

import org.tatrman.health.service.HealthCheckResult

class TcpHealthChecker(
    override val technology: String,
    private val host: String,
    private val port: Int,
    private val timeout: Long = 3000,
    private val probe: TcpProbe? = null,
) : HealthChecker {
    interface TcpProbe {
        fun createProbeBytes(): ByteArray

        fun isValidResponse(response: ByteArray): Boolean
    }

    override suspend fun check(): HealthCheckResult {
        val details = mapOf("host" to host, "port" to port.toString())
        return try {
            // The name lookup and the connect both run inside the timeout (Blocking.kt).
            tcpConnect(host, port, timeout) { socket ->
                val probeBytes = probe?.createProbeBytes()
                if (probeBytes == null) return@tcpConnect true
                socket.outputStream.write(probeBytes)
                socket.outputStream.flush()
                val response = ByteArray(1024).also { socket.inputStream.read(it) }
                probe.isValidResponse(response)
            }.let { valid ->
                when (valid) {
                    true -> HealthCheckResult.healthy(technology = technology, source = "tcp", details = details)
                    false ->
                        HealthCheckResult.unhealthy(
                            technology = technology,
                            source = "tcp",
                            error = "Invalid probe response",
                            details = details,
                        )
                    null ->
                        HealthCheckResult.unhealthy(
                            technology = technology,
                            source = "tcp",
                            error = "Connection timeout",
                            details = details,
                        )
                }
            }
        } catch (e: Exception) {
            val cause = (e as? java.util.concurrent.CompletionException)?.cause ?: e
            HealthCheckResult.unhealthy(
                technology = technology,
                source = "tcp",
                error = cause.message ?: cause::class.simpleName ?: "Connection failed",
                details = details,
            )
        }
    }
}

class RedisPingProbe : TcpHealthChecker.TcpProbe {
    override fun createProbeBytes(): ByteArray = "+PING\r\n".toByteArray()

    override fun isValidResponse(response: ByteArray): Boolean {
        val text = response.toString(Charsets.UTF_8)
        return text.contains("+PONG") || text.contains("PONG")
    }
}

class PostgresReadyProbe : TcpHealthChecker.TcpProbe {
    override fun createProbeBytes(): ByteArray = "".toByteArray()

    override fun isValidResponse(response: ByteArray): Boolean = true
}

class MssqlProbe : TcpHealthChecker.TcpProbe {
    override fun createProbeBytes(): ByteArray = byteArrayOf()

    override fun isValidResponse(response: ByteArray): Boolean = true
}
