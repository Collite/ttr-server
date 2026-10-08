// SPDX-License-Identifier: Apache-2.0
package org.tatrman.mcp.identity

/**
 * The OBO identity gate for an MCP door edge (kantheon-security §2 / §2.1). A pure
 * decision over the three identity sources so the security-critical fail-closed
 * behavior is unit-testable, independent of the MCP transport plumbing.
 *
 * Rules (in order):
 *   1. **No spoofing** — if a valid Bearer token is present AND a `user_id` arg
 *      disagrees with the token's identity, reject (`identity_conflict`). An agent
 *      must not present a token for one user and a `user_id` for another.
 *   2. **Fail closed** — when identity is required and none resolves (no Bearer,
 *      no `X-User-Id`, no `user_id` arg), reject (`missing_user_identity`). A
 *      service-account-shaped token with no user claim resolves to no identity and
 *      is rejected here: agents call with the user's OBO token, never service identity.
 *   3. Otherwise allow, carrying the resolved identity (may be null when identity is
 *      not required — the dev-network path).
 *
 * With a [BearerVerifier] (LR G2b), a presented Bearer token must verify FIRST: one that does not is
 * refused (`invalid_token`) and never falls through to the header or arg sources — a forged token plus
 * a `user_id` arg must not become the arg's identity. A door that verifies passes
 * [IdentityPolicy.TOKEN_ONLY] too, so the unauthenticated sources are not identities at all.
 */
object IdentityGate {
    sealed interface Decision {
        data class Reject(
            val code: String,
            val message: String,
        ) : Decision

        data class Allow(
            val identity: UserIdentity?,
        ) : Decision
    }

    fun decide(
        authorizationHeader: String?,
        userIdHeader: String?,
        argUserId: String?,
        requireIdentity: Boolean,
        policy: IdentityPolicy = IdentityPolicy.PERMISSIVE,
        verifier: BearerVerifier? = null,
    ): Decision {
        if (verifier != null) {
            val token = IdentityResolver.bearerTokenOf(authorizationHeader)
            val verdict = token?.let { verifier.verify(it) }
            if (verdict is Verification.Invalid) {
                return Decision.Reject(
                    code = "invalid_token",
                    message = "The bearer token was not accepted (${verdict.reason}).",
                )
            }
        }
        // DF-Q03: a valid token plus a conflicting user_id arg is a spoof attempt.
        val tokenIdentity = IdentityResolver.parseTokenOrNull(authorizationHeader)
        if (tokenIdentity != null && !argUserId.isNullOrBlank() && argUserId != tokenIdentity.id) {
            return Decision.Reject(
                code = "identity_conflict",
                message =
                    "Authorization token identity does not match user_id arg; " +
                        "remove user_id when a Bearer token is supplied.",
            )
        }
        val identity = IdentityResolver.resolve(authorizationHeader, userIdHeader, argUserId, policy)
        if (requireIdentity && identity == null) {
            return Decision.Reject(
                code = "missing_user_identity",
                message = "No user identity supplied (Authorization Bearer / X-User-Id / user_id arg).",
            )
        }
        return Decision.Allow(identity)
    }
}
