// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.identity

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.automation.api.describe
import org.tatrman.automation.bundle.BundleFixture
import org.tatrman.automation.bundle.BundleFixture.edit
import org.tatrman.automation.bundle.BundleSource

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

        "an undeclared grant role warns once at load and falls back only to literal realm roles" {
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
            val bundle = BundleFixture.load(root)
            bundle.warnings.filter { "grant role.legacy" in it } shouldBe
                listOf(
                    "world demo: grant role.legacy has no def role; using literal realm-role match",
                )
            val world = bundle.worlds.getValue("demo")
            val map = GrantMap(world.grants, world.identities, world.roles)
            map.allows(
                parsed(roles = listOf("legacy")),
                "morning_release",
                RunAsRef.Named("automation_decider"),
            ) shouldBe
                true
            map.allows(
                parsed(groups = listOf("legacy")),
                "morning_release",
                RunAsRef.Named("automation_decider"),
            ) shouldBe
                false
        }

        "declared non-keycloak membership is retained and cannot fall back to the role name" {
            val root = BundleFixture.copy()
            root.edit("automation/automation.ttrm") {
                it.replace("resolve: keycloak_group \"hartland-ops\"", "members: [\"bora\"]")
            }
            val world = BundleFixture.load(root).worlds.getValue("demo")
            world.roles.single { it.name == "ops" }.keycloakGroups shouldBe emptySet()
            GrantMap(world.grants, world.identities, world.roles)
                .allows(
                    parsed(roles = listOf("ops", "hartland-ops")),
                    "morning_release",
                    RunAsRef.Named("automation_decider"),
                ) shouldBe
                false
        }

        "the descriptor granted bit agrees with GrantMap for each caller and deployment" {
            val bundles = BundleFixture.bundles(BundleSource { BundleFixture.checkout(branch = it) })
            runBlocking { bundles.refresh() }
            val sources = BundleFixture.sources(bundles)
            val b = BundleFixture.load()
            for (c in listOf(
                parsed(listOf("hartland-ops")),
                parsed(groups = listOf("/hartland-ops")),
                parsed(listOf("ops")),
            )) {
                val descriptor = describe(sources, c)
                val h1 = descriptor.deployments.filter { it.name.startsWith("release_") }
                h1.size shouldBe 5
                for (d in h1) {
                    val deployment = b.deployments.getValue(d.name)
                    val world = b.worldOf(deployment)!!
                    d.granted shouldBe
                        GrantMap(world.grants, world.identities, world.roles)
                            .allows(c, deployment.program!!, RunAsRef.Named("automation_decider"))
                }
            }
        }
    })
