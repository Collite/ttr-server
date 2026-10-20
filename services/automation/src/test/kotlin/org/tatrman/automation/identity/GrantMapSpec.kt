// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.identity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** The hartland world's grants (`17` §2) read as the map evaluates them: ops → any program; Maya → one. */
class GrantMapSpec :
    StringSpec({
        val decider = RunAsRef.Named("automation_decider")
        val identities = listOf(IdentityDecl("automation_decider", "svc-hartland-automation", "TTR_CONN_AUTOMATION"))
        val hartland =
            GrantMap(
                listOf(
                    GrantDecl(
                        Who.parse("role.ops"),
                        GrantDecl.ANY_PROGRAM,
                        RunAsRef.parse("identity.automation_decider"),
                    ),
                    GrantDecl(Who.parse("role.sales_manager"), "release_on_placement", decider),
                    GrantDecl(Who.parse("role.process_owner"), GrantDecl.ANY_PROGRAM, decider),
                ),
                identities,
            )

        val ops = caller("olga", setOf("ops"))
        val maya = caller("maya", setOf("sales_manager"))
        val owner = caller("pavel", setOf("process_owner"))
        val sales = caller("sam", setOf("sales"))

        "ops may run any program as the decider" {
            for (program in listOf("morning_release", "release_on_placement", "decide_catch_up", "settle_daily")) {
                hartland.allows(ops, program, decider) shouldBe true
            }
        }

        "Maya (sales_manager) may run release_on_placement as the decider, and nothing else" {
            hartland.allows(maya, "release_on_placement", decider) shouldBe true
            hartland.allows(maya, "morning_release", decider) shouldBe false
            hartland.allows(maya, "decide_on_request", decider) shouldBe false
        }

        "the process owner's wildcard covers the debug runs 'as the recipe runs' (D-93)" {
            hartland.allows(owner, "decide_on_request", decider) shouldBe true
        }

        "a role without a grant runs nothing as the decider" {
            hartland.allows(sales, "release_on_placement", decider) shouldBe false
        }

        "a grant names one identity: as the decider is not as the author, nor as another identity" {
            hartland.allows(ops, "morning_release", RunAsRef.Author) shouldBe false
            hartland.allows(ops, "morning_release", RunAsRef.Named("other")) shouldBe false
        }

        "user.<u> matches a person by principal or sub; identity.<i> matches that service principal only" {
            val map =
                GrantMap(
                    listOf(
                        GrantDecl(Who.User("maya"), "p", RunAsRef.Author),
                        GrantDecl(Who.Identity("automation_decider"), "p", RunAsRef.Author),
                    ),
                    identities,
                )
            map.allows(maya, "p", RunAsRef.Author) shouldBe true
            map.allows(caller("svc-hartland-automation", kind = TokenKind.SERVICE), "p", RunAsRef.Author) shouldBe true
            // A person who happens to be called like the service principal is not the service principal.
            map.allows(caller("svc-hartland-automation"), "p", RunAsRef.Author) shouldBe false
            // A service account called like a user is not that user.
            map.allows(caller("maya", kind = TokenKind.SERVICE), "p", RunAsRef.Author) shouldBe false
        }

        "the text forms parse; anything else is refused" {
            RunAsRef.parse("caller") shouldBe RunAsRef.Caller
            RunAsRef.parse("author") shouldBe RunAsRef.Author
            RunAsRef.parse("identity.x") shouldBe RunAsRef.Named("x")
            RunAsRef.Named("x").wire shouldBe "identity.x"
            Who.parse("user.maya") shouldBe Who.User("maya")
            Who.parse("*") shouldBe Who.Anyone
            shouldThrow<IllegalArgumentException> { RunAsRef.parse("identity.") }
            shouldThrow<IllegalArgumentException> { RunAsRef.parse("obo") }
            shouldThrow<IllegalArgumentException> { Who.parse("group.ops") }
            shouldThrow<IllegalArgumentException> { Who.parse("ops") }
        }

        "`who: *` grants every verified caller — a person or a service — that program as that identity, no other" {
            val anyone = GrantMap(listOf(GrantDecl(Who.Anyone, "decide_on_request", RunAsRef.Author)), identities)
            anyone.allows(sales, "decide_on_request", RunAsRef.Author) shouldBe true
            anyone.allows(caller("svc-x", kind = TokenKind.SERVICE), "decide_on_request", RunAsRef.Author) shouldBe true
            anyone.allows(sales, "settle_daily", RunAsRef.Author) shouldBe false
            anyone.allows(sales, "decide_on_request", decider) shouldBe false
        }
    })
