// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.observability

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.llmgateway.admin.AdminAuth.Identity

/**
 * LC-1 (LC contracts §2.3) — who reads which prompt-log rows, as a pure decision over a verified token.
 * The same matrix runs through the wire, against Postgres, in `PromptLogsRoutesSpec`.
 */
class PromptLogAccessSpec :
    StringSpec({
        val admin = "llm-gateway-admin"
        val inspect = "llm-gateway-inspect"

        fun access(identity: Identity) = PromptLogAccess.of(identity, admin, inspect)

        "the admin role reads every row" {
            access(Identity.Verified("ops", setOf(admin, "default-roles"))) shouldBe PromptLogAccess.AllRows
        }
        "the inspect role reads every row" {
            access(Identity.Verified("ops", setOf(inspect))) shouldBe PromptLogAccess.AllRows
        }
        "a realm JWT with neither role reads its own subject's rows" {
            access(Identity.Verified("sub-dan", setOf("default-roles"))) shouldBe PromptLogAccess.OwnRows("sub-dan")
        }
        "a verified token that names no subject owns nothing — an empty answer, not every NULL-subject row" {
            access(Identity.Verified(null, emptySet())) shouldBe PromptLogAccess.NoRows
        }
        "no token or an unverifiable one (a gateway API key is not a realm JWT) is unauthenticated" {
            access(Identity.NoToken) shouldBe PromptLogAccess.Unauthenticated
            access(Identity.Invalid) shouldBe PromptLogAccess.Unauthenticated
        }
        "role names are configuration, not literals" {
            PromptLogAccess.of(Identity.Verified("ops", setOf("custom-inspect")), admin, "custom-inspect") shouldBe
                PromptLogAccess.AllRows
            PromptLogAccess.of(Identity.Verified("ops", setOf(inspect)), admin, "custom-inspect") shouldBe
                PromptLogAccess.OwnRows("ops")
        }
    })
