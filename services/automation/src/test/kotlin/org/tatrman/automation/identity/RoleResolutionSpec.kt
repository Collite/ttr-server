// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.identity

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.automation.api.describe
import org.tatrman.automation.bundle.BundleFixture
import org.tatrman.automation.bundle.BundleFixture.edit
import org.tatrman.automation.bundle.BundleInvalidException
import org.tatrman.automation.bundle.BundleLoader
import org.tatrman.automation.bundle.BundleSource
import java.util.Base64

/** D-138 / P1X S0: the model binding, rather than its role name, authorizes a caller. */
class RoleResolutionSpec :
    StringSpec({
        fun parsed(
            roles: List<String> = emptyList(),
            groups: List<String>? = null,
        ): Caller {
            val token =
                JWT
                    .create()
                    .withSubject("sub-bora")
                    .withClaim("preferred_username", "bora")
                    .withClaim("realm_access", mapOf("roles" to roles))
                    .apply { groups?.let { withClaim("groups", it) } }
                    .sign(Algorithm.none())
            return Caller.of(token)!!
        }

        "the loaded binding grants a realm role or normalized group, but never the model name alone" {
            val b = BundleFixture.load()
            val world = b.worlds.getValue("demo")
            world.roles.single { it.name == "ops" }.keycloakGroups shouldBe setOf("hartland-ops")
            val map = GrantMap(world.grants, world.identities, world.roles)
            val asDecider = RunAsRef.Named("automation_decider")
            map.allows(parsed(roles = listOf("hartland-ops")), "morning_release", asDecider) shouldBe true
            val groupCaller = parsed(groups = listOf("/hartland-ops"))
            groupCaller.groups shouldBe setOf("hartland-ops")
            map.allows(groupCaller, "morning_release", asDecider) shouldBe true
            map.allows(parsed(roles = listOf("ops")), "morning_release", asDecider) shouldBe false
            map.allows(parsed(groups = listOf("/ops")), "morning_release", asDecider) shouldBe false
        }

        "an undeclared grant role refuses the bundle with its role and world file" {
            val root = BundleFixture.copy()
            root.edit("model/worlds/demo.ttrm") {
                it.replace(
                    "    def grant { who: role.ops,",
                    """
                    def grant { who: role.legacy, program: morning_release, as: identity.automation_decider }
                    def grant { who: role.legacy, program: release_on_placement, as: identity.automation_decider }
                    def grant { who: role.ops,
                    """.trimIndent(),
                )
            }
            val refusal = shouldThrow<BundleInvalidException> { BundleFixture.load(root) }
            refusal.diagnostics.any {
                "ttr/automation-unknown-reference: grant: who names no role 'legacy'" in it &&
                    "model/worlds/demo.ttrm:" in it
            } shouldBe true
        }

        "declared non-keycloak membership is retained and cannot fall back to the role name" {
            val root = BundleFixture.copy()
            root.edit("automation/automation.ttrm") {
                it.replace("resolve: keycloak_group \"hartland-ops\"", "members: [\"bora\"]")
            }
            val bundle = BundleFixture.load(root)
            val warning =
                "world demo: grant role.ops: def role ops declares no keycloak_group, " +
                    "so it authorizes no grant (D-138)"
            bundle.warnings.filter { "declares no keycloak_group" in it } shouldBe listOf(warning)
            BundleFixture.load().warnings.none { "declares no keycloak_group" in it } shouldBe true
            val world = bundle.worlds.getValue("demo")
            world.roles.single { it.name == "ops" }.keycloakGroups shouldBe emptySet()
            GrantMap(world.grants, world.identities, world.roles)
                .allows(
                    parsed(roles = listOf("ops", "hartland-ops")),
                    "morning_release",
                    RunAsRef.Named("automation_decider"),
                ) shouldBe
                false
        }

        "a group binding normalizes one leading slash and preserves the full subgroup path" {
            val root = BundleFixture.copy()
            root.edit("automation/automation.ttrm") {
                it.replace("keycloak_group \"hartland-ops\"", "keycloak_group \"/hartland-ops\"")
            }
            val world = BundleFixture.load(root).worlds.getValue("demo")
            val map = GrantMap(world.grants, world.identities, world.roles)
            val asDecider = RunAsRef.Named("automation_decider")
            map.allows(parsed(roles = listOf("hartland-ops")), "morning_release", asDecider) shouldBe true
            map.allows(parsed(groups = listOf("/hartland-ops")), "morning_release", asDecider) shouldBe true

            root.edit("automation/automation.ttrm") {
                it.replace("keycloak_group \"/hartland-ops\"", "keycloak_group \"ops\"")
            }
            val simple = BundleFixture.load(root).worlds.getValue("demo")
            GrantMap(simple.grants, simple.identities, simple.roles)
                .allows(parsed(groups = listOf("/other/ops")), "morning_release", asDecider) shouldBe false

            root.edit("automation/automation.ttrm") {
                it.replace("keycloak_group \"ops\"", "keycloak_group \"/other/ops\"")
            }
            val subgroup = BundleFixture.load(root).worlds.getValue("demo")
            GrantMap(subgroup.grants, subgroup.identities, subgroup.roles)
                .allows(parsed(groups = listOf("/other/ops")), "morning_release", asDecider) shouldBe true
        }

        "odd groups claim items are ignored without losing valid groups" {
            fun withGroups(groupsJson: String): Caller {
                val encoder = Base64.getUrlEncoder().withoutPadding()
                val header = encoder.encodeToString("""{"alg":"none"}""".toByteArray())
                val payload = encoder.encodeToString("""{"sub":"sub-bora","groups":$groupsJson}""".toByteArray())
                return Caller.of("$header.$payload.")!!
            }
            withGroups("""[{"name":"x"}, null, "/hartland-ops"]""").groups shouldBe setOf("hartland-ops")
            withGroups(""""hartland-ops"""").groups shouldBe emptySet()
        }

        "the descriptor granted bit agrees with GrantMap for each caller and deployment" {
            val bundles = BundleFixture.bundles(BundleSource { BundleFixture.checkout(branch = it) })
            bundles.refresh()
            val sources = BundleFixture.sources(bundles)
            val b = BundleFixture.load()
            for ((c, grantedCount) in listOf(
                parsed(listOf("hartland-ops")) to 5,
                parsed(groups = listOf("/hartland-ops")) to 5,
                parsed(listOf("ops")) to 0,
            )) {
                val descriptor = describe(sources, c)
                val h1 = descriptor.deployments.filter { it.name.startsWith("release_") }
                h1.size shouldBe 5
                h1.count { it.granted } shouldBe grantedCount
                for (d in h1) {
                    val deployment = b.deployments.getValue(d.name)
                    val world = b.worldOf(deployment)!!
                    d.granted shouldBe
                        GrantMap(world.grants, world.identities, world.roles)
                            .allows(c, deployment.program!!, BundleLoader.runAs(deployment.runAs))
                }
            }
        }
    })
