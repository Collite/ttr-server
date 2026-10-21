// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.stages

import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.AggregateCall
import org.tatrman.plan.v1.AggregateNode
import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.LimitOffsetNode
import org.tatrman.plan.v1.Literal
import org.tatrman.plan.v1.NamedExpression
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ProjectNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.security.v1.ColumnRule
import org.tatrman.validate.v1.ValidationOptions
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain as shouldContainStr

class RuleEnforcerSpec :
    StringSpec({
        val enforcer = RuleEnforcer(serviceDefault = 30)
        val customers =
            QualifiedName
                .newBuilder()
                .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                .setNamespace("dbo")
                .setName("customers")
                .build()
        val scan =
            PlanNode
                .newBuilder()
                .setTableScan(TableScanNode.newBuilder().setTable(customers))
                .build()

        // --- TopN (unchanged behaviour, now exposed via Result.plan) ---

        "enforce wraps an unbounded plan with default cap" {
            val out = enforcer.enforce(scan, options(enforce = true))
            out.rejected shouldBe false
            out.plan.hasLimitOffset() shouldBe true
            out.plan.limitOffset.limit shouldBe 30L
        }

        "enforce clamps a higher existing limit" {
            val withHighLimit =
                PlanNode
                    .newBuilder()
                    .setLimitOffset(LimitOffsetNode.newBuilder().setInput(scan).setLimit(1000))
                    .build()
            val out = enforcer.enforce(withHighLimit, options(enforce = true))
            out.plan.limitOffset.limit shouldBe 30L
        }

        "enforce leaves an equal-or-lower limit untouched" {
            val withLowLimit =
                PlanNode
                    .newBuilder()
                    .setLimitOffset(LimitOffsetNode.newBuilder().setInput(scan).setLimit(10))
                    .build()
            val out = enforcer.enforce(withLowLimit, options(enforce = true))
            out.plan shouldBe withLowLimit
        }

        "enforce respects ValidationOptions.default_top_n when smaller than service default" {
            val out = enforcer.enforce(scan, options(enforce = true, defaultTopN = 5))
            out.plan.limitOffset.limit shouldBe 5L
        }

        "enforce skips entirely when enforce_top_n = false" {
            val out = enforcer.enforce(scan, options(enforce = false))
            out.plan shouldBe scan
        }

        "enforce preserves existing offset when rewriting limit" {
            val withOffset =
                PlanNode
                    .newBuilder()
                    .setLimitOffset(
                        LimitOffsetNode
                            .newBuilder()
                            .setInput(scan)
                            .setLimit(1000)
                            .setOffset(50),
                    ).build()
            val out = enforcer.enforce(withOffset, options(enforce = true))
            out.plan.limitOffset.limit shouldBe 30L
            out.plan.limitOffset.offset shouldBe 50L
        }

        // --- the ceiling: default-top-n is what an unstated request gets, max-top-n what a stated
        //     one may reach; and a cap that bounds the plan says so ---

        val ceilinged = RuleEnforcer(serviceDefault = 200, serviceMax = 1000)

        "a caller may ask ABOVE the default, up to the ceiling, and a granted request is silent" {
            val out = ceilinged.enforce(scan, options(enforce = true, defaultTopN = 500))
            out.plan.limitOffset.limit shouldBe 500L
            out.messages shouldHaveSize 0
        }

        "above the ceiling the ceiling wins, and the answer says so" {
            val out = ceilinged.enforce(scan, options(enforce = true, defaultTopN = 5000))
            out.plan.limitOffset.limit shouldBe 1000L
            val warning = out.messages.single()
            warning.code shouldBe RuleEnforcer.TOP_N_APPLIED
            warning.severity shouldBe Severity.WARNING
            warning.humanMessage shouldContainStr "1000"
            warning.humanMessage shouldContainStr "5000"
        }

        "an unstated request gets the default, not the ceiling, and a warning that a cap was applied" {
            val out = ceilinged.enforce(scan, options(enforce = true))
            out.plan.limitOffset.limit shouldBe 200L
            out.messages.single().code shouldBe RuleEnforcer.TOP_N_APPLIED
        }

        "an unset ceiling IS the default — the older rule: a caller cannot ask for more" {
            val out = enforcer.enforce(scan, options(enforce = true, defaultTopN = 500))
            out.plan.limitOffset.limit shouldBe 30L
            out.messages.single().code shouldBe RuleEnforcer.TOP_N_APPLIED
        }

        "a caller's own window granted in full (limit = what it asked) raises no warning" {
            // query puts the caller's window on the plan root; granting it is not a cut.
            val window =
                PlanNode
                    .newBuilder()
                    .setLimitOffset(
                        LimitOffsetNode
                            .newBuilder()
                            .setInput(scan)
                            .setLimit(200)
                            .setOffset(400),
                    ).build()
            val out = ceilinged.enforce(window, options(enforce = true, defaultTopN = 200))
            out.plan shouldBe window
            out.messages shouldHaveSize 0
        }

        "a window above the ceiling is cut to it, keeps its offset, and warns" {
            val window =
                PlanNode
                    .newBuilder()
                    .setLimitOffset(
                        LimitOffsetNode
                            .newBuilder()
                            .setInput(scan)
                            .setLimit(5000)
                            .setOffset(400),
                    ).build()
            val out = ceilinged.enforce(window, options(enforce = true, defaultTopN = 5000))
            out.plan.limitOffset.limit shouldBe 1000L
            out.plan.limitOffset.offset shouldBe 400L
            out.messages.single().code shouldBe RuleEnforcer.TOP_N_APPLIED
        }

        "an offset-only window gets the cap as its limit, and the warning" {
            val window =
                PlanNode
                    .newBuilder()
                    .setLimitOffset(LimitOffsetNode.newBuilder().setInput(scan).setOffset(400))
                    .build()
            val out = ceilinged.enforce(window, options(enforce = true))
            out.plan.limitOffset.limit shouldBe 200L
            out.plan.limitOffset.offset shouldBe 400L
            out.messages.single().code shouldBe RuleEnforcer.TOP_N_APPLIED
        }

        "a plan already bounded below the cap is not a cap binding" {
            val bounded =
                PlanNode
                    .newBuilder()
                    .setLimitOffset(LimitOffsetNode.newBuilder().setInput(scan).setLimit(10))
                    .build()
            enforcer.enforce(bounded, options(enforce = true)).messages shouldHaveSize 0
        }

        // --- BI (C-10): a role raises the ceiling; the default is everyone's ---

        val byRole =
            RuleEnforcer(
                serviceDefault = 200,
                maxByRole = RuleEnforcer.parseMaxByRole("kantheon-bi-reader:10000"),
            )
        val biReader = listOf("default-roles-kantheon", "kantheon-bi-reader")

        "a caller with the BI role gets 10 000 when it asks 10 000, silently" {
            val out = byRole.enforce(scan, options(enforce = true, defaultTopN = 10_000), roles = biReader)
            out.plan.limitOffset.limit shouldBe 10_000L
            out.messages shouldHaveSize 0
        }

        "a caller without it asking 10 000 gets 200, and the warning" {
            val out =
                byRole.enforce(scan, options(enforce = true, defaultTopN = 10_000), roles = listOf("kantheon-area-x"))
            out.plan.limitOffset.limit shouldBe 200L
            out.messages.single().code shouldBe RuleEnforcer.TOP_N_APPLIED
        }

        "the default is unchanged for the role: asking nothing gets 200" {
            val out = byRole.enforce(scan, options(enforce = true), roles = biReader)
            out.plan.limitOffset.limit shouldBe 200L
            out.messages.single().code shouldBe RuleEnforcer.TOP_N_APPLIED
        }

        "the role's ceiling still caps: asking 50 000 gets 10 000, and the warning" {
            val out = byRole.enforce(scan, options(enforce = true, defaultTopN = 50_000), roles = biReader)
            out.plan.limitOffset.limit shouldBe 10_000L
            out.messages.single().humanMessage shouldContainStr "10000"
        }

        "a role only raises: the global max-top-n wins when it is higher, the most generous role when several" {
            val mixed =
                RuleEnforcer(
                    serviceDefault = 200,
                    serviceMax = 10_000,
                    maxByRole = RuleEnforcer.parseMaxByRole("low:500, high:20000"),
                )
            mixed
                .enforce(scan, options(enforce = true, defaultTopN = 10_000), roles = listOf("low"))
                .plan.limitOffset.limit shouldBe 10_000L
            mixed
                .enforce(scan, options(enforce = true, defaultTopN = 30_000), roles = listOf("low", "high"))
                .plan.limitOffset.limit shouldBe 20_000L
        }

        // The Flight edge's ask: the largest window, "as many rows as my ceiling allows".
        val edgeAsk = Int.MAX_VALUE

        "the edge's ask with the BI role gets the role's ceiling" {
            val out = byRole.enforce(scan, options(enforce = true, defaultTopN = edgeAsk), roles = biReader)
            out.plan.limitOffset.limit shouldBe 10_000L
            out.messages.single().humanMessage shouldBe
                "Answer limited to 10000 rows by the row cap; the caller asked for as many rows as its ceiling allows."
        }

        "the edge's ask without the role gets exactly the default" {
            val out =
                byRole.enforce(scan, options(enforce = true, defaultTopN = edgeAsk), roles = listOf("kantheon-area-x"))
            out.plan.limitOffset.limit shouldBe 200L
            out.messages.single().humanMessage shouldContainStr "as many rows as its ceiling allows"
        }

        "the edge's ask over a statement's own lower LIMIT keeps the LIMIT, silently" {
            val limited =
                PlanNode
                    .newBuilder()
                    .setLimitOffset(LimitOffsetNode.newBuilder().setInput(scan).setLimit(5_000))
                    .build()
            val out = byRole.enforce(limited, options(enforce = true, defaultTopN = edgeAsk), roles = biReader)
            out.plan.limitOffset.limit shouldBe 5_000L
            out.messages shouldHaveSize 0
        }

        "max-top-n-by-role: role:rows pairs; blank is none, a malformed entry is refused" {
            RuleEnforcer.parseMaxByRole("") shouldBe emptyMap()
            RuleEnforcer.parseMaxByRole(" a:1 , b:20 ") shouldBe mapOf("a" to 1, "b" to 20)
            // Split at the last `:`, so a role name may hold one.
            RuleEnforcer.parseMaxByRole("ns:bi-reader:10000") shouldBe mapOf("ns:bi-reader" to 10_000)
            listOf("a", "a:", ":5", "a:x", "a:0", "a:1,a:2").forEach { raw ->
                io.kotest.assertions.throwables
                    .shouldThrow<IllegalArgumentException> { RuleEnforcer.parseMaxByRole(raw) }
                    .message shouldContainStr "VALIDATE_MAX_TOP_N_BY_ROLE"
            }
        }

        "enforce_top_n = false never warns" {
            ceilinged.enforce(scan, options(enforce = false, defaultTopN = 5000)).messages shouldHaveSize 0
        }

        // --- DF-V01: column deny/mask enforcement ---

        "DENY on a referenced (table, column) rejects with column_denied ERROR" {
            val plan =
                PlanNode
                    .newBuilder()
                    .setProject(
                        ProjectNode
                            .newBuilder()
                            .setInput(scan)
                            .addExpressions(namedColumnRef("ssn"))
                            .addExpressions(namedColumnRef("name")),
                    ).build()
            val deny = denyRule(customers, "ssn", "pii_protection")

            val out = enforcer.enforce(plan, options(enforce = true), listOf(deny))

            out.rejected shouldBe true
            out.messages shouldHaveSize 1
            out.messages[0].severity shouldBe Severity.ERROR
            out.messages[0].code shouldBe "column_denied"
            out.messages[0].humanMessage shouldContainStr "ssn"
            out.messages[0].humanMessage shouldContainStr "db.dbo.customers"
            // The deny short-circuits; topN does NOT run (the response carries no plan).
            out.plan shouldBe plan
        }

        "DENY on a column the plan does NOT reference is a no-op" {
            val plan =
                PlanNode
                    .newBuilder()
                    .setProject(
                        ProjectNode
                            .newBuilder()
                            .setInput(scan)
                            .addExpressions(namedColumnRef("name")),
                    ).build()
            val deny = denyRule(customers, "ssn", "pii_protection")

            val out = enforcer.enforce(plan, options(enforce = true), listOf(deny))

            out.rejected shouldBe false
            // The DENY adds nothing; the one message is the row cap's, injected into an unbounded plan.
            out.messages.map { it.code } shouldBe listOf(RuleEnforcer.TOP_N_APPLIED)
        }

        "DENY scoped to a table NOT in the plan is a no-op" {
            val otherTable =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                    .setNamespace("dbo")
                    .setName("orders")
                    .build()
            val plan =
                PlanNode
                    .newBuilder()
                    .setProject(
                        ProjectNode
                            .newBuilder()
                            .setInput(scan)
                            .addExpressions(namedColumnRef("ssn")),
                    ).build()
            val deny = denyRule(otherTable, "ssn", "pii_protection")

            val out = enforcer.enforce(plan, options(enforce = true), listOf(deny))

            out.rejected shouldBe false
        }

        "MASK rewrites a ColumnRef leaf inside a Project expression" {
            val plan =
                PlanNode
                    .newBuilder()
                    .setProject(
                        ProjectNode
                            .newBuilder()
                            .setInput(scan)
                            .addExpressions(namedColumnRef("salary"))
                            .addExpressions(namedColumnRef("name")),
                    ).build()
            val mask = maskRule(customers, "salary", "salary_mask", maskLiteral("***"))

            val out = enforcer.enforce(plan, options(enforce = false), listOf(mask))

            out.rejected shouldBe false
            // After masking, the salary projection's inner expression is a literal, not a column_ref.
            val rewrittenProject = out.plan.project
            val salaryExpr = rewrittenProject.expressionsList.first { it.alias == "salary" }.expression
            salaryExpr.hasLiteral() shouldBe true
            salaryExpr.literal.stringValue shouldBe "***"
            // The non-masked column is untouched.
            val nameExpr = rewrittenProject.expressionsList.first { it.alias == "name" }.expression
            nameExpr.hasColumnRef() shouldBe true
            nameExpr.columnRef.name shouldBe "name"
        }

        "MASK rule without mask_expression withholds the column like a DENY — never served unmasked" {
            val plan =
                PlanNode
                    .newBuilder()
                    .setProject(
                        ProjectNode
                            .newBuilder()
                            .setInput(scan)
                            .addExpressions(namedColumnRef("salary")),
                    ).build()
            val maskNoExpr =
                ColumnRule
                    .newBuilder()
                    .setTable(customers)
                    .setColumn("salary")
                    .setAction(ColumnRule.Action.MASK)
                    .setRuleId("incomplete_mask")
                    .build()

            val out = enforcer.enforce(plan, options(enforce = false), listOf(maskNoExpr))

            out.rejected shouldBe true
            out.messages.map { it.code } shouldBe listOf("column_denied")
        }

        // review-159 ⑤ — an ER entity's column rules: the plan reads the entity through an ER scan.
        "a DENY on an entity attribute fires on an ER plan" {
            val customer =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(org.tatrman.plan.v1.SchemaCode.ER)
                    .setNamespace("entity")
                    .setName("Customer")
                    .build()
            val plan =
                PlanNode
                    .newBuilder()
                    .setProject(
                        ProjectNode
                            .newBuilder()
                            .setInput(
                                PlanNode
                                    .newBuilder()
                                    .setScan(
                                        org.tatrman.plan.v1.ScanNode
                                            .newBuilder()
                                            .setObject(customer),
                                    ),
                            ).addExpressions(namedColumnRef("ssn")),
                    ).build()
            val denySsn =
                ColumnRule
                    .newBuilder()
                    .setTable(customer)
                    .setColumn("ssn")
                    .setAction(ColumnRule.Action.DENY)
                    .setRuleId("pii")
                    .build()

            val out = enforcer.enforce(plan, options(enforce = false), listOf(denySsn))

            out.rejected shouldBe true
            out.messages.single().humanMessage shouldContainStr "'er.entity.Customer'"
        }

        "MASK warns when the column is referenced as a bare ColumnRef (group_keys etc.)" {
            // An Aggregate with the masked column as a group key — bare ColumnRef slot, can't be
            // rewritten with an arbitrary Expression (proto type mismatch).
            val plan =
                PlanNode
                    .newBuilder()
                    .setAggregate(
                        AggregateNode
                            .newBuilder()
                            .setInput(scan)
                            .addGroupKeys(ColumnRef.newBuilder().setName("salary"))
                            .addAggregates(
                                AggregateCall
                                    .newBuilder()
                                    .setFunction("count")
                                    .addArgs(ColumnRef.newBuilder().setName("id"))
                                    .setAlias("n"),
                            ),
                    ).build()
            val mask = maskRule(customers, "salary", "salary_mask", maskLiteral("***"))

            val out = enforcer.enforce(plan, options(enforce = false), listOf(mask))

            out.rejected shouldBe false
            out.messages.map { it.code } shouldContain "mask_skipped_bare_column_ref"
        }

        "DENY + TopN: rejection short-circuits before TopN wrapping" {
            val plan =
                PlanNode
                    .newBuilder()
                    .setProject(
                        ProjectNode
                            .newBuilder()
                            .setInput(scan)
                            .addExpressions(namedColumnRef("ssn")),
                    ).build()
            val deny = denyRule(customers, "ssn", "pii_protection")

            val out = enforcer.enforce(plan, options(enforce = true), listOf(deny))

            out.rejected shouldBe true
            // No LimitOffset wrapped on top — the plan slot is the unmodified input.
            out.plan shouldBe plan
        }

        "MASK + TopN: mask runs first, then TopN wraps the masked plan" {
            val plan =
                PlanNode
                    .newBuilder()
                    .setProject(
                        ProjectNode
                            .newBuilder()
                            .setInput(scan)
                            .addExpressions(namedColumnRef("salary")),
                    ).build()
            val mask = maskRule(customers, "salary", "salary_mask", maskLiteral("***"))

            val out = enforcer.enforce(plan, options(enforce = true), listOf(mask))

            out.rejected shouldBe false
            out.plan.hasLimitOffset() shouldBe true
            out.plan.limitOffset.limit shouldBe 30L
            out.plan.limitOffset.input.project.expressionsList[0]
                .expression
                .literal.stringValue shouldBe "***"
        }
    })

private fun options(
    enforce: Boolean,
    defaultTopN: Int = 0,
): ValidationOptions =
    ValidationOptions
        .newBuilder()
        .setEnforceTopN(enforce)
        .setDefaultTopN(defaultTopN)
        .build()

private fun namedColumnRef(name: String): NamedExpression =
    NamedExpression
        .newBuilder()
        .setExpression(
            Expression
                .newBuilder()
                .setColumnRef(ColumnRef.newBuilder().setName(name)),
        ).setAlias(name)
        .build()

private fun denyRule(
    table: QualifiedName,
    column: String,
    ruleId: String,
): ColumnRule =
    ColumnRule
        .newBuilder()
        .setTable(table)
        .setColumn(column)
        .setAction(ColumnRule.Action.DENY)
        .setRuleId(ruleId)
        .build()

private fun maskRule(
    table: QualifiedName,
    column: String,
    ruleId: String,
    mask: Expression,
): ColumnRule =
    ColumnRule
        .newBuilder()
        .setTable(table)
        .setColumn(column)
        .setAction(ColumnRule.Action.MASK)
        .setMaskExpression(mask)
        .setRuleId(ruleId)
        .build()

private fun maskLiteral(value: String): Expression =
    Expression
        .newBuilder()
        .setLiteral(Literal.newBuilder().setStringValue(value).setType("text"))
        .setResultType("text")
        .build()
