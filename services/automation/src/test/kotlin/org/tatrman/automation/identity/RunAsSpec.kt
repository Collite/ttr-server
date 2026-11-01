// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.identity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode

/** D-90…D-93 (16 §3.1–§3.4) — the identity a run opens with, or `403 identity { reason }`. */
class RunAsSpec :
    StringSpec({
        val decider = IdentityDecl("automation_decider", "svc-hartland-automation", "TTR_CONN_AUTOMATION")
        val unmapped = IdentityDecl("mailer", "svc-hartland-mailer", "TTR_CONN_MAILER")
        val identities = listOf(decider, unmapped)
        val grants =
            GrantMap(
                listOf(
                    GrantDecl(Who.Role("ops"), GrantDecl.ANY_PROGRAM, RunAsRef.Named("automation_decider")),
                    GrantDecl(Who.Role("ops"), GrantDecl.ANY_PROGRAM, RunAsRef.Named("mailer")),
                    GrantDecl(Who.Role("ops"), GrantDecl.ANY_PROGRAM, RunAsRef.Author),
                    GrantDecl(Who.Role("process_owner"), GrantDecl.ANY_PROGRAM, RunAsRef.Named("automation_decider")),
                ),
                identities,
                listOf(
                    RoleDecl("ops", setOf("hartland-ops")),
                    RoleDecl("process_owner", setOf("hartland-process-owners")),
                ),
            )
        val tokens =
            ServiceTokens(mapOf("AUTOMATION" to "svc-hartland-automation:s3cret"), fetch = { error("no fetch here") })
        val runAs = RunAs(grants, identities, tokens)

        fun deployment(
            runAs: RunAsRef,
            author: String = "pavel",
        ) = DeploymentIdentity("release_native_event", "release_on_placement", runAs, author)

        val ops = caller("olga", setOf("hartland-ops"))
        val owner = caller("pavel", setOf("hartland-process-owners"))
        val maya = caller("maya", setOf("hartland-sales-managers"))

        fun refusal(block: () -> Unit): String {
            val e = shouldThrow<AutomationException> { block() }
            e.code shouldBe ErrorCode.IDENTITY
            return e.detail!!
                .jsonObject["reason"]!!
                .jsonPrimitive.content
        }

        "run_as: caller is always allowed, and the door sees the caller's own bearer" {
            val r = runAs.resolve(maya, deployment(RunAsRef.Caller))
            r shouldBe RunIdentity(maya, RunAsRef.Caller, "maya", credential = null)
        }

        "run_as: identity.x with a grant runs as the identity's principal on its credential" {
            val r = runAs.resolve(ops, deployment(RunAsRef.Named("automation_decider")))
            r.principal shouldBe "svc-hartland-automation"
            r.credential shouldBe "TTR_CONN_AUTOMATION"
            r.caller shouldBe ops
        }

        "run_as: identity.x without a grant → 403 identity · no-grant" {
            refusal { runAs.resolve(maya, deployment(RunAsRef.Named("automation_decider"))) } shouldBe "no-grant"
        }

        "the deployer's implicit grant is for `author` only — not for a named identity" {
            refusal {
                runAs.resolve(
                    caller("dora"),
                    deployment(RunAsRef.Named("automation_decider"), author = "dora"),
                )
            } shouldBe
                "no-grant"
        }

        "run_as: identity.x whose TTR_CONN_<cred> is unset → 403 identity · unmapped (after the grant)" {
            refusal { runAs.resolve(ops, deployment(RunAsRef.Named("mailer"))) } shouldBe "unmapped"
            // No grant comes first: a caller without one learns nothing about the mapping.
            refusal { runAs.resolve(maya, deployment(RunAsRef.Named("mailer"))) } shouldBe "no-grant"
        }

        "a credential that is not id:secret (a database URL under the identity's name) is unmapped too" {
            val wrong =
                RunAs(
                    grants,
                    identities,
                    ServiceTokens(
                        mapOf("AUTOMATION" to "postgresql://automation:pw@pg:5432/automation"),
                        fetch = { "" },
                    ),
                )
            refusal { wrong.resolve(ops, deployment(RunAsRef.Named("automation_decider"))) } shouldBe "unmapped"
        }

        "run_as: author — the deployer runs it on their own bearer, no grant needed" {
            val r = runAs.resolve(owner, deployment(RunAsRef.Author, author = "pavel"))
            r shouldBe RunIdentity(owner, RunAsRef.Author, "pavel", credential = null)
        }

        "run_as: author — anyone else needs a grant (→ no-grant)" {
            refusal { runAs.resolve(maya, deployment(RunAsRef.Author, author = "pavel")) } shouldBe "no-grant"
        }

        "run_as: author — granted, but the author is a person: token exchange is the Platform's (→ token-exchange)" {
            refusal { runAs.resolve(ops, deployment(RunAsRef.Author, author = "pavel")) } shouldBe "token-exchange"
        }

        "run_as: author — granted, and the author is a declared service principal: its credential" {
            val r = runAs.resolve(ops, deployment(RunAsRef.Author, author = "svc-hartland-automation"))
            r.principal shouldBe "svc-hartland-automation"
            r.credential shouldBe "TTR_CONN_AUTOMATION"
        }

        "a debug run is as the caller by default (D-93) — no grant needed, the caller's own rows" {
            val r = runAs.resolve(maya, deployment(RunAsRef.Named("automation_decider")), DebugAs.CALLER)
            r shouldBe RunIdentity(maya, RunAsRef.Caller, "maya", credential = null)
        }

        "a debug run 'as the recipe runs' needs the grant (the process owner has it; Maya has not)" {
            runAs
                .resolve(
                    owner,
                    deployment(RunAsRef.Named("automation_decider")),
                    DebugAs.DEPLOYMENT,
                ).principal shouldBe
                "svc-hartland-automation"
            refusal {
                runAs.resolve(
                    maya,
                    deployment(RunAsRef.Named("automation_decider")),
                    DebugAs.DEPLOYMENT,
                )
            } shouldBe
                "no-grant"
        }

        "granted (the descriptor's, per caller) follows the same rule, credentials aside" {
            runAs.granted(ops, deployment(RunAsRef.Named("mailer"))) shouldBe true
            runAs.granted(maya, deployment(RunAsRef.Named("automation_decider"))) shouldBe false
            runAs.granted(maya, deployment(RunAsRef.Caller)) shouldBe true
            runAs.granted(owner, deployment(RunAsRef.Author, author = "pavel")) shouldBe true
        }

        "the author map: by e-mail, else by name, else the commit's own author string" {
            val map = AuthorMap(mapOf("ops@hartland.example" to "svc-hartland-automation", "Pavel Owner" to "pavel"))
            map.principalOf("ops@hartland.example", "Ops Bot") shouldBe "svc-hartland-automation"
            map.principalOf("pavel@hartland.example", "Pavel Owner") shouldBe "pavel"
            map.principalOf("new@hartland.example", "Someone New") shouldBe "new@hartland.example"
            map.principalOf(null, "Someone New") shouldBe "Someone New"
        }
    })
