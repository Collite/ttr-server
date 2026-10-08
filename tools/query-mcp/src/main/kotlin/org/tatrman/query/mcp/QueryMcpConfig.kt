// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.mcp

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

/** Resolved configuration block for the query-mcp service. */
data class QueryMcpConfig(
    val serverPort: Int,
    val mcpTransport: String,
    val mcpPath: String,
    val upstream: Upstream,
    val limits: Limits,
    val security: Security,
    val toolTimeoutsMs: Map<String, Long>,
    /** DF-ME02-CACHE — TTL for in-process caching of attribute decorations in GrpcMetadataClient. */
    val metadataDecorationCacheTtlSeconds: Long = 30L,
) {
    data class GrpcEndpoint(
        val host: String,
        val port: Int,
        val deadlineSeconds: Long,
    )

    data class Upstream(
        val queryRunner: GrpcEndpoint,
        val translator: GrpcEndpoint,
        val validator: GrpcEndpoint,
        val metadata: GrpcEndpoint,
    )

    data class Limits(
        val rowLimitDefault: Int,
        val rowLimitMax: Int,
        val requestTimeoutSeconds: Long,
        val maxMessageBytes: Int,
    )

    data class Security(
        val requireIdentity: Boolean,
        /** LR G2b — verify the bearer's RS256 signature, `iss` and `exp` before trusting any claim. */
        val verifySignature: Boolean = false,
        val issuer: String = "",
        /** Blank = `<issuer>/protocol/openid-connect/certs`. */
        val jwksUri: String = "",
        /** Blank = `aud` is not checked. */
        val audience: String = "",
    )

    companion object {
        fun load(config: Config = ConfigFactory.load()): QueryMcpConfig {
            val root = config.getConfig("query-mcp")
            return QueryMcpConfig(
                serverPort = config.getString("server.port").toInt(),
                mcpTransport = root.getString("mcp.transport"),
                mcpPath = root.getString("mcp.path"),
                upstream =
                    Upstream(
                        queryRunner = endpointOf(root.getConfig("upstream.query")),
                        translator = endpointOf(root.getConfig("upstream.translate")),
                        validator = endpointOf(root.getConfig("upstream.validate")),
                        metadata = endpointOf(root.getConfig("upstream.veles")),
                    ),
                limits =
                    Limits(
                        rowLimitDefault = root.getInt("limits.row-limit-default"),
                        rowLimitMax = root.getInt("limits.row-limit-max"),
                        requestTimeoutSeconds = root.getLong("limits.request-timeout-seconds"),
                        maxMessageBytes = root.getInt("limits.max-message-bytes"),
                    ),
                security =
                    Security(
                        requireIdentity = root.getBoolean("security.require-identity"),
                        verifySignature = root.getBoolean("security.verify-signature"),
                        issuer = root.getString("security.issuer"),
                        jwksUri = root.getString("security.jwks-uri"),
                        audience = root.getString("security.audience"),
                    ),
                toolTimeoutsMs =
                    root
                        .getConfig("tool-timeouts")
                        .entrySet()
                        .associate {
                            it.key to
                                it.value
                                    .unwrapped()
                                    .toString()
                                    .toLong()
                        },
                metadataDecorationCacheTtlSeconds =
                    if (root.hasPath("upstream.veles.decoration-cache-ttl-seconds")) {
                        root.getLong("upstream.veles.decoration-cache-ttl-seconds")
                    } else {
                        30L
                    },
            )
        }

        private fun endpointOf(c: Config): GrpcEndpoint =
            GrpcEndpoint(
                host = c.getString("host"),
                port = c.getString("port").toInt(),
                deadlineSeconds = c.getLong("deadline-seconds"),
            )
    }
}
