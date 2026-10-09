// SPDX-License-Identifier: Apache-2.0
package shared.ktor.mcp

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.request.header
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The identity-bearing headers of ONE inbound HTTP request — `Authorization` and `X-User-Id` —
 * carried on that request's coroutine, so an MCP tool handler reads the headers of the call it is
 * serving and of no other.
 *
 * **Why a coroutine context element and not a thread-local.** The MCP SDK runs a tool handler inline
 * on the request's coroutine (POST route → `handlePostRequest` → `Protocol.onRequest` → the handler),
 * but that coroutine suspends on the way: reading the body, a bearer check that hops to
 * `Dispatchers.IO`, the handler's own `withTimeout`. It may resume on any worker thread. A value
 * stashed on the thread the interceptor happened to run on is then either gone (the gate answers
 * `missing_user_identity` for a caller who sent a token) or — worse — the value another in-flight
 * request left on the thread this one resumed on, i.e. a tool call evaluated under someone else's
 * identity. A context element travels with the coroutine and its children, and nowhere else.
 *
 * Read it with [current] from inside the handler; [installMcpRequestHeaders] puts it there.
 */
class McpRequestHeaders(
    val authorization: String?,
    val userId: String?,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<McpRequestHeaders> {
        private val NONE = McpRequestHeaders(authorization = null, userId = null)

        /**
         * The headers of the request the calling coroutine serves. Outside one (none installed) both
         * are null — which every door's identity gate reads as "no identity", so it fails closed.
         */
        suspend fun current(): McpRequestHeaders = currentCoroutineContext()[Key] ?: NONE
    }

    /** Never prints a credential — this element ends up in coroutine debug dumps. */
    override fun toString(): String =
        "McpRequestHeaders(authorization=${presence(authorization)}, userId=${presence(userId)})"

    private fun presence(value: String?): String = if (value == null) "absent" else "present"
}

/**
 * Runs the rest of every call's pipeline — the MCP transport and the tool handler it calls inline —
 * inside an [McpRequestHeaders] holding that call's `Authorization` and `X-User-Id`. Install it on
 * the application that mounts the MCP routes; its order among other `Plugins`-phase interceptors does
 * not matter, since the element rides the coroutine through any dispatcher hop they make.
 */
fun Application.installMcpRequestHeaders() {
    intercept(ApplicationCallPipeline.Plugins) {
        val headers =
            McpRequestHeaders(
                authorization = context.request.header("Authorization"),
                userId = context.request.header("X-User-Id"),
            )
        withContext(headers) { proceed() }
    }
}
