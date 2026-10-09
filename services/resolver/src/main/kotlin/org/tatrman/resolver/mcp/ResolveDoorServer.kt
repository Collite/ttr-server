// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.mcp

import io.ktor.server.application.Application
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import org.slf4j.LoggerFactory
import shared.ktor.mcp.McpRequestHeaders
import shared.ktor.mcp.safeMcpTool

/**
 * Mounts the `resolve.bind:v1` StreamableHTTP MCP door on this Ktor application
 * (RG-P6.S1.T2). The call's `Authorization` / `X-User-Id` arrive as [McpRequestHeaders], which the
 * application installs with `installMcpRequestHeaders()`; the tool body reads them, runs the
 * fail-closed OBO gate ([ResolveDoorHandler]), and — only on allow — resolves.
 * Every call is `safeMcpTool`-wrapped so a timeout / thrown exception becomes a
 * `CallToolResult(isError=true)` rather than a broken stream.
 */
fun Application.installResolveDoor(
    door: ResolveDoor,
    handler: ResolveDoorHandler,
    toolTimeoutMs: Long = 20_000L,
) {
    val logger = LoggerFactory.getLogger("resolver.door")
    mcpStreamableHttp {
        Server(
            serverInfo = Implementation(name = "resolver", version = "0.1.0"),
            options =
                ServerOptions(
                    capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
                ),
        ).also { server ->
            server.addTool(
                name = door.tool.name,
                description = door.tool.description ?: "",
                inputSchema = door.tool.inputSchema,
            ) { request ->
                // The headers of THIS call, off its own coroutine. (They used to be snapshotted
                // from a thread-local here on the belief that this lambda still ran on the
                // interceptor's thread; it does not — the request coroutine has already suspended
                // on the way in, reading the body — so the snapshot could be empty or another
                // caller's. See McpRequestHeaders.)
                val headers = McpRequestHeaders.current()
                safeMcpTool(RESOLVE_TOOL_NAME, toolTimeoutMs) { req ->
                    handler.handle(req.arguments, headers.authorization, headers.userId)
                }(request)
            }
            logger.info("resolve door bound: tool '{}' (streamable HTTP)", door.tool.name)
        }
    }
}
