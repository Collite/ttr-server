// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.observability

import org.tatrman.llmgateway.admin.AdminAuth

/**
 * Which prompt-log rows a reader may see (LC-1, LC contracts §2.3) — decided from a verified realm JWT
 * alone, before any query runs:
 *
 * | bearer                                   | access                                             |
 * |------------------------------------------|----------------------------------------------------|
 * | realm JWT with the admin role            | [AllRows]                                          |
 * | realm JWT with the inspect role (new)    | [AllRows]                                          |
 * | realm JWT with neither                   | [OwnRows] — `end_user_subject = sub`, in SQL       |
 * | realm JWT with neither and no `sub`      | [NoRows] — nothing can be theirs                   |
 * | gateway API key / no bearer / bad token  | [Unauthenticated] → 401                            |
 *
 * A reader who is not allowed a row is answered **200 with the filtered list, never 403**: a 403 on a
 * turn someone else owns would tell the reader that turn exists (PT A-6's existence-oracle reasoning).
 * Rows with a NULL subject — written before LC, or by a caller that set no context — match no subject
 * and are therefore visible to the two roles only.
 */
sealed interface PromptLogAccess {
    data object Unauthenticated : PromptLogAccess

    data object AllRows : PromptLogAccess

    data class OwnRows(
        val subject: String,
    ) : PromptLogAccess

    data object NoRows : PromptLogAccess

    companion object {
        fun of(
            identity: AdminAuth.Identity,
            adminRole: String,
            inspectRole: String,
        ): PromptLogAccess =
            when (identity) {
                AdminAuth.Identity.NoToken, AdminAuth.Identity.Invalid -> Unauthenticated
                is AdminAuth.Identity.Verified ->
                    when {
                        adminRole in identity.roles || inspectRole in identity.roles -> AllRows
                        identity.subject != null -> OwnRows(identity.subject)
                        else -> NoRows
                    }
            }
    }
}

/** The inspect role's default name — beside `admin.role`'s `llm-gateway-admin` (⚑LC-1, ⚑LC-3). */
const val DEFAULT_INSPECT_ROLE: String = "llm-gateway-inspect"
