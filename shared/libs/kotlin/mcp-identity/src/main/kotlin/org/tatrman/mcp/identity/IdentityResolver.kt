// SPDX-License-Identifier: Apache-2.0
package org.tatrman.mcp.identity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/**
 * Resolves caller identity from one of three sources, in priority order:
 *
 *  1. `Authorization: Bearer <jwt>` — preferred for production. Extracts the
 *     `preferred_username` claim, falling back to `sub`. Roles come from
 *     `realm_access.roles` (Keycloak convention).
 *  2. `X-User-Id` request header — service-to-service shortcut.
 *  3. Tool-arg `user_id` — trusted-network shortcut.
 *
 * **This decodes the JWT without verifying it.** Verification is [BearerVerifier]'s, run first by
 * [IdentityGate.decide] when the door passes one (LR G2b): a door that does not verify must sit behind
 * something that does, or anyone who reaches it can claim any user and any realm role.
 */
object IdentityResolver {
    private val parser =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    /**
     * @param authorizationHeader value of `Authorization` request header (may be null)
     * @param userIdHeader value of `X-User-Id` request header (may be null)
     * @param argUserId `user_id` field from MCP tool args (may be null)
     * @param policy which sources are trusted (RG-P6 review A); defaults to the
     *   dev-network [IdentityPolicy.PERMISSIVE]. A production door passes
     *   [IdentityPolicy.TOKEN_ONLY] so header / arg / `admin:`-prefix identities are
     *   ignored and only an OBO Bearer token counts.
     * @return resolved identity or null when nothing usable was provided
     */
    fun resolve(
        authorizationHeader: String?,
        userIdHeader: String?,
        argUserId: String?,
        policy: IdentityPolicy = IdentityPolicy.PERMISSIVE,
    ): UserIdentity? {
        val fromToken = authorizationHeader?.let { tryParseToken(it, policy) }
        if (fromToken != null) return fromToken
        if (policy.allowHeaderSource) {
            val fromHeader = userIdHeader?.takeIf { it.isNotBlank() }
            if (fromHeader != null) {
                return UserIdentity(
                    id = fromHeader,
                    roles = rolesFromIdConvention(fromHeader, policy.allowAdminConvention),
                    source = IdentitySource.HEADER,
                )
            }
        }
        if (policy.allowArgSource) {
            val fromArg = argUserId?.takeIf { it.isNotBlank() }
            if (fromArg != null) {
                return UserIdentity(
                    id = fromArg,
                    roles = rolesFromIdConvention(fromArg, policy.allowAdminConvention),
                    source = IdentitySource.EXPLICIT,
                )
            }
        }
        return null
    }

    /**
     * Public token-only parser used by the transport to detect a token-vs-arg identity conflict
     * before falling into the priority-order [resolve] path (DF-Q03). Returns null on absent /
     * malformed / non-Bearer headers — caller treats null as "no token in play".
     */
    fun parseTokenOrNull(authorizationHeader: String?): UserIdentity? =
        authorizationHeader?.let { tryParseToken(it, IdentityPolicy.PERMISSIVE) }

    /** The token of an `Authorization: Bearer <token>` header; null for no header or another scheme. */
    fun bearerTokenOf(authorizationHeader: String?): String? {
        val header = authorizationHeader ?: return null
        return header
            .removePrefix("Bearer ")
            .removePrefix("bearer ")
            .trim()
            .takeIf { it.isNotEmpty() && it != header.trim() }
    }

    private fun tryParseToken(
        header: String,
        policy: IdentityPolicy,
    ): UserIdentity? {
        val token = bearerTokenOf(header) ?: return null
        val parts = token.split(".")
        if (parts.size < 2) return null
        val payloadJson =
            runCatching {
                val decoded = Base64.getUrlDecoder().decode(parts[1].padEnd(((parts[1].length + 3) / 4) * 4, '='))
                parser.parseToJsonElement(String(decoded, Charsets.UTF_8))
            }.getOrNull() ?: return null
        if (payloadJson !is JsonObject) return null
        val id =
            payloadJson["preferred_username"]?.asStringOrNull()
                ?: payloadJson["sub"]?.asStringOrNull()
                ?: return null
        val roles =
            buildSet {
                addAll(rolesFromIdConvention(id, policy.allowAdminConvention))
                payloadJson["realm_access"]?.let { ra ->
                    if (ra is JsonObject) {
                        ra["roles"]?.let { rolesEl ->
                            runCatching { rolesEl.jsonArray }.getOrNull()?.forEach { role ->
                                role.asStringOrNull()?.let { add(it) }
                            }
                        }
                    }
                }
            }
        return UserIdentity(id = id, roles = roles, source = IdentitySource.TOKEN)
    }

    /**
     * Local-dev admin shortcut: a user_id prefixed `admin:` is treated as an admin and emits both
     * `admin` (legacy / [UserIdentity.isAdmin]) and `query-platform-admin` (the DF-V02 canonical
     * role the query-mcp validator gates `apply_security = false` on — a consumer-specific
     * convention preserved from the pre-lift edge; a future change can parameterize per-consumer
     * admin roles). Production callers carry these roles from the JWT's `realm_access.roles`.
     * Gated by [allowAdminConvention]: a [IdentityPolicy.TOKEN_ONLY] door passes false so a
     * username prefix can NEVER self-grant admin (RG-P6 review A).
     */
    private fun rolesFromIdConvention(
        userId: String,
        allowAdminConvention: Boolean,
    ): Set<String> =
        if (allowAdminConvention && userId.startsWith("admin:")) {
            setOf("admin", "query-platform-admin")
        } else {
            emptySet()
        }

    private fun JsonElement.asStringOrNull(): String? =
        runCatching {
            val p = jsonPrimitive
            if (p is JsonPrimitive && p.isString) p.content else null
        }.getOrNull()
}
