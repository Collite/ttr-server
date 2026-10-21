// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

import com.typesafe.config.Config

/**
 * BI (contracts C-1) — the Flight SQL edge's settings, from `application.conf`'s `flight` block.
 *
 * The edge is opt-in ([enabled]) and binds [bind] explicitly: `127.0.0.1` unless a deployment says
 * otherwise, because the door's own gRPC port has no authentication and the edge is the only BI
 * entry that verifies a caller. A chart behind the gateway sets `0.0.0.0`.
 *
 * Statements run through the door's own `Run` over a loopback channel to [loopbackHost]:[loopbackPort]
 * (BI-0 memo §4): the edge is a client of the door exactly as any other caller is, and every
 * statement is logged by the door's incoming-call interceptor.
 */
data class FlightConfig(
    val enabled: Boolean,
    val port: Int,
    val bind: String,
    val loopbackHost: String,
    val loopbackPort: Int,
    /** A started statement nobody fetched is cancelled after this long. */
    val pendingTtlSeconds: Long = 60,
    /** How long a catalog snapshot is trusted before Veles is asked for its model version again. */
    val catalogTtlSeconds: Long = 30,
    val maxMessageSizeBytes: Int = 32 * 1024 * 1024,
    val auth: Auth = Auth(),
) {
    /**
     * C-2 — how callers are verified. [issuer] is the realm the edge trusts (required: an edge that cannot verify
     * does not start); [jwksUri] defaults to Keycloak's `<issuer>/protocol/openid-connect/certs`. Password login is
     * the direct-access grant on [passwordClient] at [tokenUrl] (default `<issuer>/protocol/openid-connect/token`);
     * an empty [passwordClient] turns it off, leaving bearer tokens only.
     */
    data class Auth(
        val issuer: String = "",
        val jwksUri: String = "",
        val audience: String = "",
        val tokenUrl: String = "",
        val passwordClient: String = "jdbc",
        val passwordClientSecret: String = "",
    ) {
        val effectiveJwksUri: String get() = jwksUri.ifBlank { "${issuer.trimEnd('/')}/protocol/openid-connect/certs" }

        val effectiveTokenUrl: String get() =
            tokenUrl.ifBlank {
                "${issuer.trimEnd(
                    '/',
                )}/protocol/openid-connect/token"
            }
    }

    companion object {
        const val DEFAULT_PORT = 7308

        fun fromConfig(config: Config): FlightConfig {
            val grpcPort = config.getInt("grpc.port")
            val loopback = config.optString("flight.loopback").orEmpty()
            val (host, port) =
                if (loopback.isBlank()) {
                    "127.0.0.1" to grpcPort
                } else {
                    loopback.substringBeforeLast(':') to loopback.substringAfterLast(':').toInt()
                }
            return FlightConfig(
                enabled = config.optBoolean("flight.enabled") ?: false,
                port = config.optInt("flight.port") ?: DEFAULT_PORT,
                bind = config.optString("flight.bind")?.ifBlank { null } ?: "127.0.0.1",
                loopbackHost = host,
                loopbackPort = port,
                pendingTtlSeconds = config.optLong("flight.pending-ttl-seconds") ?: 60,
                catalogTtlSeconds = config.optLong("flight.catalog-ttl-seconds") ?: 30,
                maxMessageSizeBytes = config.getInt("grpc.max-message-size-bytes"),
                auth =
                    Auth(
                        issuer = config.optString("flight.auth.issuer").orEmpty().trim(),
                        jwksUri = config.optString("flight.auth.jwks-uri").orEmpty().trim(),
                        audience = config.optString("flight.auth.audience").orEmpty().trim(),
                        tokenUrl = config.optString("flight.auth.token-url").orEmpty().trim(),
                        passwordClient = config.optString("flight.auth.password-client")?.trim() ?: "jdbc",
                        passwordClientSecret = config.optString("flight.auth.password-client-secret").orEmpty(),
                    ),
            )
        }

        private fun Config.optString(path: String): String? = if (hasPath(path)) getString(path) else null

        private fun Config.optBoolean(path: String): Boolean? = if (hasPath(path)) getBoolean(path) else null

        private fun Config.optInt(path: String): Int? = if (hasPath(path)) getInt(path) else null

        private fun Config.optLong(path: String): Long? = if (hasPath(path)) getLong(path) else null
    }
}
