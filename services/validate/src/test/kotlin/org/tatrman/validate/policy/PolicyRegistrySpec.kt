// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.policy

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode

/**
 * The role gate: a policy with `roles` applies only to a caller holding at least one of them, and
 * a policy never applies to a caller holding one of its `exempt-roles` — even when that caller
 * also holds a gating role. A policy that names neither applies to everyone, as before.
 */
class PolicyRegistrySpec :
    StringSpec({
        val inventory =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.DB)
                .setNamespace("dbo")
                .setName("inventory")
                .build()

        fun policy(
            id: String,
            roles: List<String> = emptyList(),
            exemptRoles: List<String> = emptyList(),
            columnRules: List<ColumnRule> = emptyList(),
        ) = Policy(
            id = id,
            tableMatch = TableMatcher.Exact(inventory),
            predicate = PolicyPredicate.In("inv_warehouse_sk", listOf(PolicyValue.Literal(5, "int"))),
            roles = roles,
            exemptRoles = exemptRoles,
            columnRules = columnRules,
        )

        "a role-gated policy applies to a caller holding the role" {
            val registry = PolicyRegistry(listOf(policy("dc-scope", roles = listOf("scope-dc-5"))))
            registry.policiesFor(inventory, rolesOnly("analyst", "scope-dc-5")).map { it.id } shouldBe
                listOf("dc-scope")
        }

        "a role-gated policy does not apply to a caller without the role" {
            val registry = PolicyRegistry(listOf(policy("dc-scope", roles = listOf("scope-dc-5"))))
            registry.policiesFor(inventory, rolesOnly("analyst")).shouldBeEmpty()
            registry.policiesFor(inventory, rolesOnly()).shouldBeEmpty()
        }

        "holding any one of several gating roles is enough" {
            val registry = PolicyRegistry(listOf(policy("dc-scope", roles = listOf("scope-dc-5", "scope-dc-7"))))
            registry.policiesFor(inventory, rolesOnly("scope-dc-7")).map { it.id } shouldBe listOf("dc-scope")
        }

        "an exempt role wins over a gating role the caller also holds" {
            val registry =
                PolicyRegistry(
                    listOf(policy("dc-scope", roles = listOf("scope-dc-5"), exemptRoles = listOf("data-all"))),
                )
            registry.policiesFor(inventory, rolesOnly("scope-dc-5", "data-all")).shouldBeEmpty()
        }

        "a policy with no roles applies to everyone — the semantics before the gate" {
            val registry = PolicyRegistry(listOf(policy("everyone")))
            registry.policiesFor(inventory, rolesOnly()).map { it.id } shouldBe listOf("everyone")
            registry.policiesFor(inventory, rolesOnly("analyst")).map { it.id } shouldBe listOf("everyone")
        }

        "a policy with only exempt roles applies to everyone except their holders" {
            val registry = PolicyRegistry(listOf(policy("all-but-auditors", exemptRoles = listOf("auditor"))))
            registry.policiesFor(inventory, rolesOnly("analyst")).map { it.id } shouldBe listOf("all-but-auditors")
            registry.policiesFor(inventory, rolesOnly("auditor")).shouldBeEmpty()
        }

        "role names match exactly — no case folding, no substring" {
            val registry = PolicyRegistry(listOf(policy("dc-scope", roles = listOf("scope-dc-5"))))
            registry.policiesFor(inventory, rolesOnly("SCOPE-DC-5", "scope-dc-50", "scope-dc")).shouldBeEmpty()
        }

        "column rules follow their policy's gate" {
            val deny = ColumnRule("inv_quantity_on_hand", ColumnAction.Deny)
            val registry =
                PolicyRegistry(listOf(policy("dc-scope", roles = listOf("scope-dc-5"), columnRules = listOf(deny))))
            registry.columnRulesFor(inventory, rolesOnly("scope-dc-5")).map { it.second } shouldBe listOf(deny)
            registry.columnRulesFor(inventory, rolesOnly("analyst")).shouldBeEmpty()
        }
    })
