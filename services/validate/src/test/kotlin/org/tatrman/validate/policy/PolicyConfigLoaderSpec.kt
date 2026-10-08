// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.policy

import com.typesafe.config.ConfigFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

class PolicyConfigLoaderSpec :
    StringSpec({

        fun cfg(hocon: String) = ConfigFactory.parseString(hocon)

        "loads a namespace-matched eq policy with a user-attr value" {
            val policies =
                PolicyConfigLoader.load(
                    cfg(
                        """
                        validate.policies = [
                          {
                            id = "tenant_isolation"
                            description = "tenant"
                            match { type = "namespace", schema = "db", namespace = "dbo" }
                            predicate { type = "eq", column = "tenant_id", value { kind = "user-attr", attribute = "tenant_id" } }
                          }
                        ]
                        """.trimIndent(),
                    ),
                )
            policies shouldHaveSize 1
            val p = policies.single()
            p.id shouldBe "tenant_isolation"
            p.description shouldBe "tenant"
            val match = p.tableMatch.shouldBeInstanceOf<TableMatcher.Namespace>()
            match.schemaCode shouldBe org.tatrman.plan.v1.SchemaCode.DB
            match.namespace shouldBe "dbo"
            val pred = p.predicate.shouldBeInstanceOf<PolicyPredicate.Eq>()
            pred.column shouldBe "tenant_id"
            pred.value.shouldBeInstanceOf<PolicyValue.UserAttribute>().attribute shouldBe "tenant_id"
        }

        "loads exact-match, literal values, IN, and AND/OR/NOT predicates" {
            val policies =
                PolicyConfigLoader.load(
                    cfg(
                        """
                        validate.policies = [
                          {
                            id = "complex"
                            match { type = "exact", qname = "db.dbo.orders" }
                            predicate {
                              type = "and"
                              left { type = "in", column = "status", values = [
                                { kind = "literal", value = "OPEN", literal-type = "text" },
                                { kind = "literal", value = "PAID", literal-type = "text" }
                              ] }
                              right {
                                type = "not"
                                child { type = "eq", column = "deleted", value = { kind = "literal", value = true, literal-type = "bool" } }
                              }
                            }
                          }
                        ]
                        """.trimIndent(),
                    ),
                )
            val p = policies.single()
            p.tableMatch
                .shouldBeInstanceOf<TableMatcher.Exact>()
                .qname.name shouldBe "orders"
            val and = p.predicate.shouldBeInstanceOf<PolicyPredicate.And>()
            val inPred = and.left.shouldBeInstanceOf<PolicyPredicate.In>()
            inPred.column shouldBe "status"
            inPred.values shouldHaveSize 2
            inPred.values[0].shouldBeInstanceOf<PolicyValue.Literal>().value shouldBe "OPEN"
            val notPred = and.right.shouldBeInstanceOf<PolicyPredicate.Not>()
            val eq = notPred.child.shouldBeInstanceOf<PolicyPredicate.Eq>()
            eq.value.shouldBeInstanceOf<PolicyValue.Literal>().value shouldBe true
        }

        "all-match policy" {
            val policies =
                PolicyConfigLoader.load(
                    cfg(
                        """validate.policies = [ { id = "org_wide", match { type = "all" }, predicate { type = "eq", column = "x", value { kind = "literal", value = 1, literal-type = "int" } } } ]""",
                    ),
                )
            policies.single().tableMatch.shouldBeInstanceOf<TableMatcher.All>()
        }

        "absent validate.policies → empty list (no error)" {
            PolicyConfigLoader.load(cfg("app {}")) shouldBe emptyList()
        }

        "unknown match type → PolicyConfigException naming the policy" {
            val ex =
                shouldThrow<PolicyConfigException> {
                    PolicyConfigLoader.load(
                        cfg(
                            """validate.policies = [ { id = "bad", match { type = "regex" }, predicate { type = "eq", column = "x", value { kind = "literal", value = 1 } } } ]""",
                        ),
                    )
                }
            ex.message!!.contains("bad") shouldBe true
        }

        "unknown predicate type → PolicyConfigException" {
            shouldThrow<PolicyConfigException> {
                PolicyConfigLoader.load(
                    cfg(
                        """validate.policies = [ { id = "p", match { type = "all" }, predicate { type = "like", column = "x", pattern = "%" } } ]""",
                    ),
                )
            }
        }

        "unknown value kind → PolicyConfigException" {
            shouldThrow<PolicyConfigException> {
                PolicyConfigLoader.load(
                    cfg(
                        """validate.policies = [ { id = "p", match { type = "all" }, predicate { type = "eq", column = "x", value { kind = "env-var", name = "X" } } } ]""",
                    ),
                )
            }
        }

        "parses roles and exempt-roles as lists" {
            val p =
                PolicyConfigLoader
                    .load(
                        cfg(
                            """
                            validate.policies = [
                              {
                                id = "dc-scope"
                                roles = ["scope-dc-5", "scope-dc-7"]
                                exempt-roles = ["data-all"]
                                match { type = "exact", qname = "db.dbo.inventory" }
                                predicate { type = "in", column = "inv_warehouse_sk", values = [ { kind = "literal", value = 5, literal-type = "int" } ] }
                              }
                            ]
                            """.trimIndent(),
                        ),
                    ).single()
            p.roles shouldBe listOf("scope-dc-5", "scope-dc-7")
            p.exemptRoles shouldBe listOf("data-all")
        }

        "absent roles and exempt-roles → empty lists (applies to everyone)" {
            val p =
                PolicyConfigLoader
                    .load(
                        cfg(
                            """validate.policies = [ { id = "p", match { type = "all" }, predicate { type = "eq", column = "c", value { kind = "literal", value = 1 } } } ]""",
                        ),
                    ).single()
            p.roles shouldBe emptyList<String>()
            p.exemptRoles shouldBe emptyList<String>()
        }

        "roles given as a single string, not a list → PolicyConfigException naming the policy" {
            val e =
                shouldThrow<PolicyConfigException> {
                    PolicyConfigLoader.load(
                        cfg(
                            """validate.policies = [ { id = "dc-scope", roles = "scope-dc-5", match { type = "all" }, predicate { type = "eq", column = "c", value { kind = "literal", value = 1 } } } ]""",
                        ),
                    )
                }
            e.message shouldContain "dc-scope"
            e.message shouldContain "roles"
        }

        "exempt-roles given as an object → PolicyConfigException naming the policy" {
            val e =
                shouldThrow<PolicyConfigException> {
                    PolicyConfigLoader.load(
                        cfg(
                            """validate.policies = [ { id = "p9", exempt-roles = { a = 1 }, match { type = "all" }, predicate { type = "eq", column = "c", value { kind = "literal", value = 1 } } } ]""",
                        ),
                    )
                }
            e.message shouldContain "p9"
            e.message shouldContain "exempt-roles"
        }

        "a blank role name → PolicyConfigException (it would gate on nothing anyone holds)" {
            shouldThrow<PolicyConfigException> {
                PolicyConfigLoader.load(
                    cfg(
                        """validate.policies = [ { id = "p", roles = [""], match { type = "all" }, predicate { type = "eq", column = "c", value { kind = "literal", value = 1 } } } ]""",
                    ),
                )
            }
        }

        // review-159 ⑩ — both fail silently at run time, so both fail at boot.
        "an empty role list → PolicyConfigException (an empty `roles` would gate every caller in)" {
            for (path in listOf("roles", "exempt-roles")) {
                shouldThrow<PolicyConfigException> {
                    PolicyConfigLoader.load(
                        cfg(
                            """validate.policies = [ { id = "p", $path = [], match { type = "all" }, predicate { type = "eq", column = "c", value { kind = "literal", value = 1 } } } ]""",
                        ),
                    )
                }.message shouldContain "'$path' is empty"
            }
        }

        "a role name with surrounding whitespace → PolicyConfigException (it would never match)" {
            shouldThrow<PolicyConfigException> {
                PolicyConfigLoader.load(
                    cfg(
                        """validate.policies = [ { id = "p", roles = [" scope-dc-5"], match { type = "all" }, predicate { type = "eq", column = "c", value { kind = "literal", value = 1 } } } ]""",
                    ),
                )
            }.message shouldContain "surrounding whitespace"
        }

        "subject-attribute parses; a blank one → PolicyConfigException" {
            val policy =
                PolicyConfigLoader
                    .load(
                        cfg(
                            """validate.policies = [ { id = "p", subject-attribute = "tenant_id", match { type = "all" }, predicate { type = "eq", column = "c", value { kind = "literal", value = 1 } } } ]""",
                        ),
                    ).single()
            policy.subjectAttribute shouldBe "tenant_id"
            shouldThrow<PolicyConfigException> {
                PolicyConfigLoader.load(
                    cfg(
                        """validate.policies = [ { id = "p", subject-attribute = " ", match { type = "all" }, predicate { type = "eq", column = "c", value { kind = "literal", value = 1 } } } ]""",
                    ),
                )
            }
        }

        "missing predicate block → PolicyConfigException" {
            shouldThrow<PolicyConfigException> {
                PolicyConfigLoader.load(cfg("""validate.policies = [ { id = "p", match { type = "all" } } ]"""))
            }
        }

        "the bundled application.conf parses into a working tenant-isolation policy" {
            // Validate ships a default policy store (policies/policies.conf, included by
            // application.conf) — the fold's HOCON policy store (Stage 3.2 T4).
            val policies = PolicyConfigLoader.load(ConfigFactory.load())
            policies shouldHaveSize 1
            policies.single().id shouldBe "tenant_isolation"
            // Gated on the tenant ITSELF, not on a role: every caller with a tenant is narrowed to it
            // whatever roles they hold, and a caller without one is not refused wholesale now that
            // an unresolvable attribute denies instead of skipping (ShippedPoliciesSpec evaluates it).
            policies.single().roles shouldBe emptyList()
            policies.single().subjectAttribute shouldBe "tenant_id"
            policies.single().appliesTo(Caller(listOf("analyst"), setOf("tenant_id", "user_id"))) shouldBe true
            policies.single().appliesTo(Caller(listOf("analyst"), setOf("user_id"))) shouldBe false
        }
    })
