// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.identity

/**
 * `run_as` of a `def deployment` and `as` of a `def grant` (D-90): the caller's own bearer, the deployment's
 * author, or a named service principal of `schema world`.
 */
sealed interface RunAsRef {
    val wire: String

    data object Caller : RunAsRef {
        override val wire = "caller"
    }

    data object Author : RunAsRef {
        override val wire = "author"
    }

    data class Named(
        val identity: String,
    ) : RunAsRef {
        override val wire get() = "identity.$identity"
    }

    companion object {
        private const val NAMED = "identity."

        fun parse(text: String): RunAsRef =
            when {
                text == "caller" -> Caller
                text == "author" -> Author
                text.startsWith(NAMED) && text.length > NAMED.length -> Named(text.removePrefix(NAMED))
                else -> throw IllegalArgumentException("run_as '$text' is not caller | author | identity.<name>")
            }
    }
}

/** `who:` of a `def grant` — a realm role, a user, a named identity calling as itself, or anyone (`who: *`). */
sealed interface Who {
    data class Role(
        val name: String,
    ) : Who

    data class User(
        val name: String,
    ) : Who

    data class Identity(
        val name: String,
    ) : Who

    /** `who: *` — every verified caller (review-114: `ttr-metadata` reads `*`/`"*"` as `GrantWho.Anyone`). */
    data object Anyone : Who

    companion object {
        fun parse(text: String): Who {
            if (text == "*") return Anyone
            val dot = text.indexOf('.')
            require(dot > 0 && dot < text.length - 1) { "grant who '$text' is not role.<r> | user.<u> | identity.<i>" }
            val name = text.substring(dot + 1)
            return when (text.substring(0, dot)) {
                "role" -> Role(name)
                "user" -> User(name)
                "identity" -> Identity(name)
                else -> throw IllegalArgumentException("grant who '$text' is not role.<r> | user.<u> | identity.<i>")
            }
        }
    }
}

/**
 * `def identity <name> { principal, credential }` — a service principal; [credential] names its client
 * credentials (`TTR_CONN_<NAME>`, D-36's rule: by name, never the value).
 */
data class IdentityDecl(
    val name: String,
    val principal: String,
    val credential: String,
)

/** A model role's binding; an unsupported membership is retained as an empty resolved set. */
data class RoleDecl(
    val name: String,
    val keycloakGroups: Set<String>,
    val resolveKind: String? = null,
)

/** `def grant { who, program, as }` — [program] `*` is every program. */
data class GrantDecl(
    val who: Who,
    val program: String,
    val runAs: RunAsRef,
) {
    companion object {
        const val ANY_PROGRAM = "*"
    }
}

/**
 * The run-as map (D-91): may this caller run this program as that identity. Only the explicit grants live
 * here; "the caller may always run as caller" and the deployer's implicit grant are [RunAs]'s rules. The
 * Platform may replace the source of the grants, never this check's shape (16 §3.2).
 */
class GrantMap(
    private val grants: List<GrantDecl>,
    identities: Collection<IdentityDecl>,
    roles: Collection<RoleDecl> = emptyList(),
) {
    private val roles = roles.associateBy { it.name }
    private val identities = identities.associateBy { it.name }

    fun allows(
        caller: Caller,
        program: String,
        runAs: RunAsRef,
    ): Boolean =
        grants.any { grant ->
            grant.runAs == runAs &&
                (grant.program == GrantDecl.ANY_PROGRAM || grant.program == program) &&
                matches(grant.who, caller)
        }

    private fun matches(
        who: Who,
        caller: Caller,
    ): Boolean =
        when (who) {
            is Who.Role ->
                roles[who.name]?.let { role ->
                    role.keycloakGroups.any { it in caller.roles || it in caller.groups }
                } ?: (who.name in caller.roles)
            is Who.User -> caller.kind == TokenKind.PERSON && (who.name == caller.principal || who.name == caller.sub)
            is Who.Identity -> caller.kind == TokenKind.SERVICE && identities[who.name]?.principal == caller.principal
            Who.Anyone -> true
        }
}
