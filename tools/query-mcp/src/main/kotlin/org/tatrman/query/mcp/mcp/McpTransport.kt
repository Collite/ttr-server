// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.mcp.mcp

import io.ktor.server.application.Application
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import org.slf4j.LoggerFactory
import org.tatrman.query.mcp.QueryMcpConfig
import org.tatrman.mcp.identity.BearerVerifier
import org.tatrman.mcp.identity.IdentityGate
import org.tatrman.query.mcp.identityPolicyOf
import org.tatrman.mcp.identity.RequestContext

private val logger = LoggerFactory.getLogger("query-mcp.transport")

/**
 * Mounts the StreamableHTTP MCP transport with [registry]'s tools.
 *
 * Cancellation: when the StreamableHTTP transport disconnects, the SDK
 * cancels the coroutine running the in-flight handler; that cancellation
 * propagates to upstream gRPC streams via the standard Flow collector
 * cancellation pathway (see `tools/query-mcp/.../upstream/UpstreamClients.kt`).
 *
 * @param requestContext per-call thread-local stash — populated by an upstream
 *   Ktor interceptor with `Authorization` and `X-User-Id` headers; tools
 *   read it to resolve identity. Threading-wise this is a ThreadLocal so it
 *   matches the pattern used by the existing erp-data-mcp service.
 * @param verifier LR G2b — when set, the gate verifies the bearer (and trusts nothing else:
 *   [identityPolicyOf]); the HTTP interceptor has already answered 401 for one that fails.
 */
fun Application.installQueryMcp(
    cfg: QueryMcpConfig,
    registry: ToolRegistry,
    requestContext: RequestContext,
    verifier: BearerVerifier? = null,
) {
    val policy = identityPolicyOf(verifier)
    mcpStreamableHttp {
        val server =
            Server(
                serverInfo = Implementation(name = "query-mcp", version = "0.1.0"),
                options =
                    ServerOptions(
                        capabilities =
                            ServerCapabilities(
                                tools = ServerCapabilities.Tools(listChanged = false),
                            ),
                    ),
            )

        for (tool in registry.all()) {
            server.addTool(
                name = tool.name,
                description = tool.description,
                inputSchema = tool.inputSchema,
                outputSchema = tool.outputSchema,
            ) { request ->
                val authHeader = requestContext.authHeader.get()
                val userIdHeader = requestContext.userIdHeader.get()
                val argUserId =
                    runCatching {
                        request.params.arguments
                            ?.let { args ->
                                (args as? kotlinx.serialization.json.JsonObject)
                                    ?.get("user_id")
                                    ?.let { el ->
                                        (el as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNullIfNotString()
                                    }
                            }
                    }.getOrNull()
                // The OBO identity gate (kantheon-security §2/§2.1) — pure decision in
                // IdentityGate so the fail-closed behavior is unit-tested. Rejects spoofing
                // (token vs user_id conflict) and, when identity is required, missing identity
                // (incl. a service-account token with no user claim).
                when (
                    val decision =
                        IdentityGate.decide(
                            authHeader,
                            userIdHeader,
                            argUserId,
                            cfg.security.requireIdentity,
                            policy = policy,
                            verifier = verifier,
                        )
                ) {
                    is IdentityGate.Decision.Reject -> {
                        logger.warn("Identity gate rejected tool '{}': {}", tool.name, decision.code)
                        return@addTool buildErrorResult(
                            toolName = tool.name,
                            code = decision.code,
                            message = decision.message,
                        )
                    }
                    is IdentityGate.Decision.Allow -> tool.execute(request, decision.identity)
                }
            }
        }

        logger.info(
            "MCP transport bound at default path with {} tools: {}",
            registry.all().size,
            registry.all().joinToString { it.name },
        )
        server
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullIfNotString(): String? =
    if (isString) content else null
