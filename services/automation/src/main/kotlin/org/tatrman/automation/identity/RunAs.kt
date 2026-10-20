// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.identity

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode

/** What a deployment says about identity, as the served bundle (S0.2) hands it over. */
data class DeploymentIdentity(
    val deployment: String,
    val program: String,
    val runAs: RunAsRef,
    /** The deployer, mapped by [AuthorMap] at /refresh (D-90). */
    val author: String,
)

/** A debug run (D-93) runs as the caller by default, or as the deployment's own `run_as` with a grant. */
enum class DebugAs { CALLER, DEPLOYMENT }

/**
 * Who a run runs as. [principal] is what the door sees as `user_id`. [credential] names the client credentials
 * whose token the door gets ([ServiceTokens]); null means the caller's own bearer is handed on. Both [caller] and
 * [runAs] go into the protocol (D-93).
 */
data class RunIdentity(
    val caller: Caller,
    val runAs: RunAsRef,
    val principal: String,
    val credential: String?,
)

/** Why `403 identity` (AU contracts §1.2). */
enum class IdentityRefusal(
    val wire: String,
) {
    NO_GRANT("no-grant"),
    UNMAPPED("unmapped"),
    TOKEN_EXCHANGE("token-exchange"),
}

/**
 * The identity rules of D-90…D-93 (16 §3.1–§3.4), checked when a run opens:
 * - a caller may always run **as caller** — the door then sees the caller's own bearer;
 * - running **as author** needs a grant, except for the deployer: the author holds an implicit grant to run
 *   their deployment as author (and then runs on their own bearer). Someone else running as a *person* author
 *   needs IdP token exchange, which the Server does not do (`token-exchange`; the delegated path is Platform's);
 *   a service-principal author — one a `def identity` declares — runs on that identity's credential;
 * - running **as a named identity** needs a grant, and the identity's credential must be mapped (`unmapped`);
 * - a debug run is as the caller, or with a grant as the deployment's `run_as`.
 * The grant is checked before the credential, so a caller without one learns nothing about the mapping.
 */
class RunAs(
    private val grants: GrantMap,
    identities: Collection<IdentityDecl>,
    private val tokens: ServiceTokens,
) {
    private val identities = identities.associateBy { it.name }
    private val byPrincipal = identities.associateBy { it.principal }

    fun resolve(
        caller: Caller,
        deployment: DeploymentIdentity,
        debug: DebugAs? = null,
    ): RunIdentity {
        val runAs = if (debug == DebugAs.CALLER) RunAsRef.Caller else deployment.runAs
        if (!permitted(caller, deployment, runAs)) {
            refuse(IdentityRefusal.NO_GRANT, "${caller.principal} may not run ${deployment.program} as ${runAs.wire}")
        }
        return when (runAs) {
            RunAsRef.Caller -> RunIdentity(caller, runAs, caller.principal, credential = null)
            RunAsRef.Author -> asAuthor(caller, deployment)
            is RunAsRef.Named -> {
                val identity =
                    identities[runAs.identity]
                        ?: refuse(IdentityRefusal.UNMAPPED, "identity ${runAs.identity} is not declared in the world")
                RunIdentity(caller, runAs, identity.principal, mapped(identity))
            }
        }
    }

    /** `granted` of the descriptor: may this caller run the deployment as its `run_as` (credentials aside). */
    fun granted(
        caller: Caller,
        deployment: DeploymentIdentity,
    ): Boolean = permitted(caller, deployment, deployment.runAs)

    private fun permitted(
        caller: Caller,
        deployment: DeploymentIdentity,
        runAs: RunAsRef,
    ): Boolean =
        when (runAs) {
            RunAsRef.Caller -> true
            RunAsRef.Author -> caller.principal == deployment.author || grants.allows(caller, deployment.program, runAs)
            is RunAsRef.Named -> grants.allows(caller, deployment.program, runAs)
        }

    private fun asAuthor(
        caller: Caller,
        deployment: DeploymentIdentity,
    ): RunIdentity {
        if (caller.principal == deployment.author) {
            return RunIdentity(caller, RunAsRef.Author, caller.principal, credential = null)
        }
        val service =
            byPrincipal[deployment.author]
                ?: refuse(
                    IdentityRefusal.TOKEN_EXCHANGE,
                    "the author ${deployment.author} is a person; running as them needs IdP token exchange",
                )
        return RunIdentity(caller, RunAsRef.Author, service.principal, mapped(service))
    }

    private fun mapped(identity: IdentityDecl): String {
        tokens.credential(identity.credential)
            ?: refuse(IdentityRefusal.UNMAPPED, "the credential of identity ${identity.name} is not configured")
        return identity.credential
    }

    private fun refuse(
        reason: IdentityRefusal,
        message: String,
    ): Nothing = throw AutomationException(ErrorCode.IDENTITY, message, buildJsonObject { put("reason", reason.wire) })
}

/**
 * The deployer of a bundle (D-90): the refresh commit's author mapped by `AUTOMATION_AUTHOR_MAP` — by e-mail,
 * else by name — or, unmapped, the commit's author string (the e-mail when there is one).
 */
class AuthorMap(
    private val map: Map<String, String>,
) {
    fun principalOf(
        email: String?,
        name: String?,
    ): String =
        email?.let { map[it] }
            ?: name?.let { map[it] }
            ?: email?.takeIf { it.isNotBlank() }
            ?: requireNotNull(name?.takeIf { it.isNotBlank() }) { "a commit without an author" }
}
