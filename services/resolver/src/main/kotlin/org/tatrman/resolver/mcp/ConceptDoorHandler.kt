// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.mcp

import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.JsonObject
import org.tatrman.mcp.identity.IdentityPolicy

/**
 * The cn doors' fail-closed edge (CN-P5 S5.1 T7; architecture §8: "all four door families are OBO-gated like
 * resolve") — the same gate as [ResolveDoorHandler], with the same [policy]: a spoofing attempt or, with
 * [requireIdentity] on, a call with no resolvable identity never reaches the model. The resolved subject is what a
 * frame's resume tokens are checked against ([ConceptDoor]).
 */
class ConceptDoorHandler(
    val door: ConceptDoor,
    private val requireIdentity: Boolean,
    private val policy: IdentityPolicy = IdentityPolicy.PERMISSIVE,
) {
    suspend fun handle(
        tool: String,
        args: JsonObject?,
        authorizationHeader: String?,
        userIdHeader: String?,
    ): CallToolResult =
        gated(args, authorizationHeader, userIdHeader, requireIdentity, policy) { subject ->
            door.call(tool, args, callerSubject = subject)
        }
}
