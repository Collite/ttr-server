// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.grpc

import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FunctionCall
import org.tatrman.plan.v1.LimitOffsetNode
import org.tatrman.plan.v1.Literal
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.NamedExpression
import org.tatrman.plan.v1.ProjectNode
import org.tatrman.security.v1.ColumnRule
import org.tatrman.security.v1.EvaluatePoliciesResponse
import org.tatrman.security.v1.TablePredicate
import org.tatrman.validate.v1.ValidateRequest
import org.tatrman.validate.v1.ValidationOptions
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.validate.client.GatewayResponseFormat
import org.tatrman.validate.client.LlmGatewayClient
import org.tatrman.validate.client.LlmGatewayException
import org.tatrman.validate.client.MetadataClient
import org.tatrman.validate.client.SecurityClient
import org.tatrman.validate.client.StaticMetadataClient
import org.tatrman.validate.roles.BearerRoleSource
import org.tatrman.validate.roles.RoleSource
import org.tatrman.validate.roles.RoleSourceUnavailableException
import org.tatrman.validate.roles.WhoisRoleLookup
import org.tatrman.validate.roles.WhoisRoleSource
import org.tatrman.validate.stages.LlmGuard
import org.tatrman.validate.stages.RuleEnforcer
import org.tatrman.validate.stages.SecurityApplier

class ValidatorServiceImplSpec :
    StringSpec({
        val customers = qname("db", "dbo", "customers")
        val tenant = eq(columnRef("tenant_id"), intLit(42))

        fun service(
            client: SecurityClient,
            metadataVersion: String = "v-test",
            llmEnabled: Boolean = false,
            metadataClient: MetadataClient = StaticMetadataClient(metadataVersion),
            roleSource: RoleSource = BearerRoleSource(),
        ): ValidateServiceImpl =
            ValidateServiceImpl(
                securityApplier = SecurityApplier(client),
                ruleEnforcer = RuleEnforcer(serviceDefault = 30),
                llmGuard = LlmGuard(enabled = llmEnabled),
                metadataClient = metadataClient,
                roleSource = roleSource,
            )

        // A metadata client that mimics an unreachable metadata service.
        val downMetadata =
            object : MetadataClient {
                private fun boom(): Nothing =
                    throw StatusRuntimeException(Status.UNAVAILABLE.withDescription("io exception"))

                override suspend fun getSnapshot(ifNoneMatch: String) = boom()

                override suspend fun currentVersion(): String = boom()

                override suspend fun probeReady(): Boolean = false
            }

        val matchingPolicy =
            SecurityClient { _ ->
                EvaluatePoliciesResponse
                    .newBuilder()
                    .addPredicates(
                        TablePredicate
                            .newBuilder()
                            .setTable(customers)
                            .setPredicate(tenant)
                            .setRuleId("tenant_isolation"),
                    ).build()
            }

        "validate fails hard with UNAVAILABLE when metadata is down" {
            val ex =
                shouldThrow<StatusRuntimeException> {
                    service(matchingPolicy, metadataClient = downMetadata).validate(
                        ValidateRequest
                            .newBuilder()
                            .setPlan(scan(customers))
                            .setContext(PipelineContext.newBuilder().setUserId("u1"))
                            .setOptions(ValidationOptions.newBuilder().setApplySecurity(true))
                            .build(),
                    )
                }
            ex.status.code shouldBe Status.Code.UNAVAILABLE
            (ex.status.description?.contains("metadata") ?: false) shouldBe true
        }

        "validate fails hard with UNAVAILABLE when sql-security is down" {
            val downSecurity =
                SecurityClient { _ ->
                    throw StatusRuntimeException(Status.UNAVAILABLE.withDescription("io exception"))
                }
            val ex =
                shouldThrow<StatusRuntimeException> {
                    service(downSecurity).validate(
                        ValidateRequest
                            .newBuilder()
                            .setPlan(scan(customers))
                            .setContext(PipelineContext.newBuilder().setUserId("u1").setModelVersion("v-test"))
                            .setOptions(ValidationOptions.newBuilder().setApplySecurity(true))
                            .build(),
                    )
                }
            ex.status.code shouldBe Status.Code.UNAVAILABLE
            (ex.status.description?.contains("sql-security") ?: false) shouldBe true
        }

        "validate wraps security and enforces TopN end-to-end" {
            val resp =
                service(matchingPolicy).validate(
                    ValidateRequest
                        .newBuilder()
                        .setPlan(scan(customers))
                        .setContext(PipelineContext.newBuilder().setUserId("u1").setModelVersion("v-test"))
                        .setOptions(
                            ValidationOptions
                                .newBuilder()
                                .setApplySecurity(true)
                                .setEnforceTopN(true),
                        ).build(),
                )
            resp.plan.hasLimitOffset() shouldBe true
            resp.plan.limitOffset.limit shouldBe 30L
            resp.plan.limitOffset.input
                .hasFilter() shouldBe true
            resp.securityAppliedList.size shouldBe 1
            resp.securityAppliedList[0].ruleId shouldBe "tenant_isolation"
        }

        // Pins Review 010 §6 + Review 009 §2/§3: full ER security path. SecurityApplier must
        // call `wrapScans` (handles `ScanNode` of `SchemaCode.ER`); the resulting plan must
        // contain a `FilterNode` wrapping the `ScanNode`. Regresses if SecurityApplier reverts
        // to `wrapTableScans` (DB-only) or `wrapScans` stops recognising the ER schema_code.
        "validate applies an ER-layer policy to a Scan(ER, ...) plan" {
            val customerEntity = qname("er", "entity", "customer")
            val regionPredicate =
                eq(
                    columnRef("region"),
                    Expression
                        .newBuilder()
                        .setLiteral(Literal.newBuilder().setStringValue("EMEA").setType("text"))
                        .setResultType("text")
                        .build(),
                )
            val erPolicyClient =
                SecurityClient { _ ->
                    EvaluatePoliciesResponse
                        .newBuilder()
                        .addPredicates(
                            TablePredicate
                                .newBuilder()
                                .setTable(customerEntity)
                                .setPredicate(regionPredicate)
                                .setRuleId("er_customer_region_isolation"),
                        ).build()
                }

            val resp =
                service(erPolicyClient).validate(
                    ValidateRequest
                        .newBuilder()
                        .setPlan(erScan(customerEntity))
                        .setContext(PipelineContext.newBuilder().setUserId("u1").setModelVersion("v-test"))
                        .setOptions(
                            ValidationOptions
                                .newBuilder()
                                .setApplySecurity(true)
                                .setEnforceTopN(false),
                        ).build(),
                )

            // After SecurityApplier+wrapScans: Filter(condition=region=EMEA, input=Scan(ER, customer)).
            resp.plan.hasFilter() shouldBe true
            resp.plan.filter.condition shouldBe regionPredicate
            resp.plan.filter.input
                .hasScan() shouldBe true
            resp.plan.filter.input.scan
                .getObject() shouldBe customerEntity
            resp.securityAppliedList.size shouldBe 1
            resp.securityAppliedList[0].ruleId shouldBe "er_customer_region_isolation"
        }

        "validate rejects mixed-layer trees with validator_mixed_layer_tree" {
            val customerEntity = qname("er", "entity", "customer")
            val mixedPlan =
                PlanNode
                    .newBuilder()
                    .setJoin(
                        org.tatrman.plan.v1.JoinNode
                            .newBuilder()
                            .setJoinType(org.tatrman.plan.v1.JoinType.INNER)
                            .setLeft(erScan(customerEntity))
                            .setRight(scan(customers)),
                    ).build()

            // No security policies fire on a rejected mixed-layer plan.
            val noPolicies = SecurityClient { _ -> EvaluatePoliciesResponse.getDefaultInstance() }
            val resp =
                service(noPolicies).validate(
                    ValidateRequest
                        .newBuilder()
                        .setPlan(mixedPlan)
                        .setContext(PipelineContext.newBuilder().setUserId("u1").setModelVersion("v-test"))
                        .setOptions(
                            ValidationOptions
                                .newBuilder()
                                .setApplySecurity(true)
                                .setEnforceTopN(false),
                        ).build(),
                )

            resp.messagesList.any { it.code == "validator_mixed_layer_tree" && it.severity == Severity.ERROR } shouldBe
                true
        }

        "non-admin caller cannot bypass security and gets a warning" {
            val resp =
                service(matchingPolicy).validate(
                    ValidateRequest
                        .newBuilder()
                        .setPlan(scan(customers))
                        .setContext(PipelineContext.newBuilder().setUserId("u1").setModelVersion("v-test"))
                        .setOptions(ValidationOptions.newBuilder().setApplySecurity(false).setEnforceTopN(true))
                        .build(),
                )
            resp.messagesList.any { it.code == "security_bypass_denied" } shouldBe true
            resp.securityAppliedList.size shouldBe 1
        }

        "caller with the admin role bypasses security (DF-V02)" {
            val resp =
                service(matchingPolicy).validate(
                    ValidateRequest
                        .newBuilder()
                        .setPlan(scan(customers))
                        .setContext(
                            PipelineContext
                                .newBuilder()
                                .setUserId("ops")
                                .setModelVersion("v-test")
                                .addAuthRoles("query-platform-admin"),
                        ).setOptions(ValidationOptions.newBuilder().setApplySecurity(false).setEnforceTopN(true))
                        .build(),
                )
            resp.securityAppliedList.size shouldBe 0
            resp.plan.hasLimitOffset() shouldBe true
        }

        "caller with a different (non-admin) role still gets security_bypass_denied" {
            val resp =
                service(matchingPolicy).validate(
                    ValidateRequest
                        .newBuilder()
                        .setPlan(scan(customers))
                        .setContext(
                            PipelineContext
                                .newBuilder()
                                .setUserId("u1")
                                .setModelVersion("v-test")
                                .addAuthRoles("analyst"),
                        ).setOptions(ValidationOptions.newBuilder().setApplySecurity(false).setEnforceTopN(true))
                        .build(),
                )
            resp.messagesList.any { it.code == "security_bypass_denied" } shouldBe true
            resp.securityAppliedList.size shouldBe 1
        }

        // ── Fork Stage 5.3 — optional whois role enrichment (additive) ──────────────────────────

        fun lookupGranting(vararg roles: String): WhoisRoleLookup =
            object : WhoisRoleLookup {
                override suspend fun rolesFor(keycloakUserId: String): List<String> = roles.toList()
            }

        val downLookup =
            object : WhoisRoleLookup {
                override suspend fun rolesFor(keycloakUserId: String): List<String> =
                    throw RoleSourceUnavailableException("whois unreachable")
            }

        // T4 — a role granted ONLY via whois (absent from the bearer) reaches the RLS/admin path:
        // the apply_security=false bypass is honoured in whois mode but denied in bearer mode for
        // the very same request.
        "whois mode: an admin role present only in whois honours the bypass" {
            val resp =
                service(
                    matchingPolicy,
                    roleSource = WhoisRoleSource(lookupGranting("query-platform-admin")),
                ).validate(
                    ValidateRequest
                        .newBuilder()
                        .setPlan(scan(customers))
                        .setContext(
                            PipelineContext
                                .newBuilder()
                                .setUserId("ops")
                                .setModelVersion("v-test")
                                .addAuthRoles("analyst"), // bearer lacks the admin role
                        ).setOptions(ValidationOptions.newBuilder().setApplySecurity(false).setEnforceTopN(true))
                        .build(),
                )
            resp.messagesList.any { it.code == "security_bypass_denied" } shouldBe false
            resp.securityAppliedList.size shouldBe 0
        }

        "bearer mode (default): the same non-admin bearer is denied the bypass" {
            val resp =
                service(matchingPolicy).validate(
                    ValidateRequest
                        .newBuilder()
                        .setPlan(scan(customers))
                        .setContext(
                            PipelineContext
                                .newBuilder()
                                .setUserId("ops")
                                .setModelVersion("v-test")
                                .addAuthRoles("analyst"),
                        ).setOptions(ValidationOptions.newBuilder().setApplySecurity(false).setEnforceTopN(true))
                        .build(),
                )
            resp.messagesList.any { it.code == "security_bypass_denied" } shouldBe true
        }

        // T5 — whois being down in whois mode fails closed: a Rule-6 error and NO plan (never a
        // wider role set, never a silent bearer-only fallthrough).
        "whois mode: an unavailable role source fails closed with a Rule-6 error and no plan" {
            val resp =
                service(matchingPolicy, roleSource = WhoisRoleSource(downLookup)).validate(
                    ValidateRequest
                        .newBuilder()
                        .setPlan(scan(customers))
                        .setContext(PipelineContext.newBuilder().setUserId("ops").setModelVersion("v-test"))
                        .setOptions(ValidationOptions.newBuilder().setApplySecurity(true).setEnforceTopN(true))
                        .build(),
                )
            resp.messagesList.any { it.code == "role_source_unavailable" } shouldBe true
            resp.hasPlan() shouldBe false
        }

        "schema-version mismatch surfaces as a warning, not an error" {
            val resp =
                service(matchingPolicy, metadataVersion = "v-current").validate(
                    ValidateRequest
                        .newBuilder()
                        .setPlan(scan(customers))
                        .setContext(PipelineContext.newBuilder().setUserId("u1").setModelVersion("v-old"))
                        .setOptions(ValidationOptions.newBuilder().setEnforceTopN(true).setApplySecurity(true))
                        .build(),
                )
            resp.context.warningsList.any { it.code == "model_version_mismatch_minor" } shouldBe true
        }

        "missing model_version surfaces a model_version_missing warning" {
            val resp =
                service(matchingPolicy).validate(
                    ValidateRequest
                        .newBuilder()
                        .setPlan(scan(customers))
                        .setContext(PipelineContext.newBuilder().setUserId("u1"))
                        .setOptions(ValidationOptions.newBuilder().setEnforceTopN(true).setApplySecurity(true))
                        .build(),
                )
            resp.context.warningsList.any { it.code == "model_version_missing" } shouldBe true
        }

        "respects ValidationOptions.default_top_n when enforcing" {
            val resp =
                service(matchingPolicy).validate(
                    ValidateRequest
                        .newBuilder()
                        .setPlan(scan(customers))
                        .setContext(PipelineContext.newBuilder().setUserId("u1").setModelVersion("v-test"))
                        .setOptions(
                            ValidationOptions
                                .newBuilder()
                                .setApplySecurity(true)
                                .setEnforceTopN(true)
                                .setDefaultTopN(5),
                        ).build(),
                )
            resp.plan.limitOffset.limit shouldBe 5L
        }

        "leaves an existing low limit untouched and still applies security" {
            val plan =
                PlanNode
                    .newBuilder()
                    .setLimitOffset(LimitOffsetNode.newBuilder().setInput(scan(customers)).setLimit(10))
                    .build()
            val resp =
                service(matchingPolicy).validate(
                    ValidateRequest
                        .newBuilder()
                        .setPlan(plan)
                        .setContext(PipelineContext.newBuilder().setUserId("u1").setModelVersion("v-test"))
                        .setOptions(ValidationOptions.newBuilder().setEnforceTopN(true).setApplySecurity(true))
                        .build(),
                )
            resp.plan.limitOffset.limit shouldBe 10L
            resp.plan.limitOffset.input
                .hasFilter() shouldBe true
        }

        // ----- Phase 2.4 — workspace-rooted plans skip security -----

        "workspace-rooted plan skips security and emits security_skipped_for_workspace warning" {
            val plan =
                PlanNode
                    .newBuilder()
                    .setWorkspaceRef(
                        org.tatrman.plan.v1.WorkspaceRef
                            .newBuilder()
                            .setWorkspaceName("q1"),
                    ).build()
            val resp =
                service(matchingPolicy).validate(
                    ValidateRequest
                        .newBuilder()
                        .setPlan(plan)
                        .setContext(PipelineContext.newBuilder().setUserId("u1").setModelVersion("v-test"))
                        .setOptions(ValidationOptions.newBuilder().setApplySecurity(true).setEnforceTopN(false))
                        .build(),
                )
            resp.securityAppliedList.size shouldBe 0
            resp.context.warningsList.any { it.code == "security_skipped_for_workspace" } shouldBe true
            // Plan structure preserved (no FilterNode wrap).
            resp.plan.hasWorkspaceRef() shouldBe true
        }

        "llm_guard skeleton emits a warning when enabled and requested" {
            val resp =
                service(matchingPolicy, llmEnabled = true).validate(
                    ValidateRequest
                        .newBuilder()
                        .setPlan(scan(customers))
                        .setContext(PipelineContext.newBuilder().setUserId("u1").setModelVersion("v-test"))
                        .setOptions(
                            ValidationOptions
                                .newBuilder()
                                .setApplySecurity(true)
                                .setEnforceTopN(true)
                                .setLlmGuard(true),
                        ).build(),
                )
            resp.messagesList.any { it.code == "llm_guard_skeleton" } shouldBe true
        }

        // --- DF-V01: column rule enforcement through the full pipeline ---

        "validate rejects with column_denied when sql-security returns a DENY rule for a referenced column" {
            val denyClient =
                SecurityClient { _ ->
                    EvaluatePoliciesResponse
                        .newBuilder()
                        .addColumnRules(
                            ColumnRule
                                .newBuilder()
                                .setTable(customers)
                                .setColumn("ssn")
                                .setAction(ColumnRule.Action.DENY)
                                .setRuleId("pii_protection"),
                        ).build()
                }
            val planTouchingSsn =
                PlanNode
                    .newBuilder()
                    .setProject(
                        ProjectNode
                            .newBuilder()
                            .setInput(scan(customers))
                            .addExpressions(
                                NamedExpression
                                    .newBuilder()
                                    .setExpression(columnRef("ssn"))
                                    .setAlias("ssn"),
                            ),
                    ).build()

            val resp =
                service(denyClient).validate(
                    ValidateRequest
                        .newBuilder()
                        .setPlan(planTouchingSsn)
                        .setContext(PipelineContext.newBuilder().setUserId("u1").setModelVersion("v-test"))
                        .setOptions(
                            ValidationOptions
                                .newBuilder()
                                .setApplySecurity(true)
                                .setEnforceTopN(true),
                        ).build(),
                )
            // Rejection: no plan on the response, ERROR message with the column name.
            resp.hasPlan() shouldBe false
            val denied = resp.messagesList.first { it.code == "column_denied" }
            denied.severity shouldBe Severity.ERROR
            (denied.humanMessage.contains("ssn") && denied.humanMessage.contains("customers")) shouldBe true
        }

        // --- LR C-5·2: a policy denial rejects the whole request ---

        val denyingPolicyEngine =
            SecurityClient { _ ->
                EvaluatePoliciesResponse
                    .newBuilder()
                    .addMessages(message(Severity.WARNING, "unknown_table"))
                    .addMessages(message(Severity.ERROR, "access_denied"))
                    .addMessages(message(Severity.ERROR, "policy_unresolvable_attribute"))
                    .build()
            }

        "a policy denial rejects the request: no plan, nothing applied, access_denied FIRST" {
            val resp =
                service(denyingPolicyEngine).validate(
                    ValidateRequest
                        .newBuilder()
                        .setPlan(scan(customers))
                        .setContext(PipelineContext.newBuilder().setUserId("u1").setModelVersion("v-test"))
                        .setOptions(ValidationOptions.newBuilder().setApplySecurity(true).setEnforceTopN(true))
                        .build(),
                )
            resp.hasPlan() shouldBe false
            resp.securityAppliedList.size shouldBe 0
            // The rejection's code is the first message's code to every MCP caller — the warning the
            // engine raised before the denial must not take that place.
            resp.messagesList.map { it.code } shouldBe
                listOf("access_denied", "policy_unresolvable_attribute", "unknown_table")
        }

        "a refused bypass does not push the denial off the first place" {
            val resp =
                service(denyingPolicyEngine).validate(
                    ValidateRequest
                        .newBuilder()
                        .setPlan(scan(customers))
                        .setContext(PipelineContext.newBuilder().setUserId("u1").setModelVersion("v-test"))
                        .setOptions(ValidationOptions.newBuilder().setApplySecurity(false))
                        .build(),
                )
            resp.hasPlan() shouldBe false
            resp.messagesList.first().code shouldBe "access_denied"
            resp.messagesList.any { it.code == "security_bypass_denied" } shouldBe true
        }

        "a column denial is the first message too, ahead of a policy-engine warning" {
            val denyWithWarning =
                SecurityClient { _ ->
                    EvaluatePoliciesResponse
                        .newBuilder()
                        .addMessages(message(Severity.WARNING, "unknown_table"))
                        .addColumnRules(
                            ColumnRule
                                .newBuilder()
                                .setTable(customers)
                                .setColumn("ssn")
                                .setAction(ColumnRule.Action.DENY)
                                .setRuleId("pii_protection"),
                        ).build()
                }
            val planTouchingSsn =
                PlanNode
                    .newBuilder()
                    .setProject(
                        ProjectNode
                            .newBuilder()
                            .setInput(scan(customers))
                            .addExpressions(
                                NamedExpression.newBuilder().setExpression(columnRef("ssn")).setAlias("ssn"),
                            ),
                    ).build()
            val resp =
                service(denyWithWarning).validate(
                    ValidateRequest
                        .newBuilder()
                        .setPlan(planTouchingSsn)
                        .setContext(PipelineContext.newBuilder().setUserId("u1").setModelVersion("v-test"))
                        .setOptions(ValidationOptions.newBuilder().setApplySecurity(true))
                        .build(),
                )
            resp.hasPlan() shouldBe false
            resp.messagesList.map { it.code } shouldBe listOf("column_denied", "unknown_table")
        }

        // Phase 08 C1 / DF-V06 — strict_coercion option.
        "strict_coercion=true rejects an int-vs-text comparison; strict_coercion=false approves it" {
            // A Filter(plan) where the condition is `int_col = 'text-literal'` — clearly a
            // coercion the DB would silently apply. Use a SecurityClient stub that returns no
            // predicates so the workingPlan reaches the strict-coercion stage unchanged.
            val intColEqText =
                Expression
                    .newBuilder()
                    .setFunction(
                        FunctionCall
                            .newBuilder()
                            .setOperation("eq")
                            .addOperands(
                                Expression
                                    .newBuilder()
                                    .setColumnRef(ColumnRef.newBuilder().setName("id").setType("int"))
                                    .setResultType("int"),
                            ).addOperands(
                                Expression
                                    .newBuilder()
                                    .setLiteral(Literal.newBuilder().setStringValue("42").setType("text"))
                                    .setResultType("text"),
                            ),
                    ).setResultType("bool")
                    .build()
            val plan =
                PlanNode
                    .newBuilder()
                    .setFilter(
                        org.tatrman.plan.v1.FilterNode
                            .newBuilder()
                            .setInput(scan(customers))
                            .setCondition(intColEqText),
                    ).build()

            val emptyPolicies = SecurityClient { _ -> EvaluatePoliciesResponse.getDefaultInstance() }
            // Strict mode ON -> rejection.
            val rejectResp =
                service(emptyPolicies).validate(
                    ValidateRequest
                        .newBuilder()
                        .setPlan(plan)
                        .setContext(PipelineContext.newBuilder().setUserId("u1").setModelVersion("v-test"))
                        .setOptions(
                            ValidationOptions
                                .newBuilder()
                                .setApplySecurity(false)
                                .setEnforceTopN(true)
                                .setStrictCoercion(true),
                        ).build(),
                )
            rejectResp.hasPlan() shouldBe false
            rejectResp.messagesList.any { it.code == "strict_coercion_rejected" } shouldBe true

            // Strict mode OFF (default) -> approved despite the same coercion.
            val approveResp =
                service(emptyPolicies).validate(
                    ValidateRequest
                        .newBuilder()
                        .setPlan(plan)
                        .setContext(PipelineContext.newBuilder().setUserId("u1").setModelVersion("v-test"))
                        .setOptions(
                            ValidationOptions
                                .newBuilder()
                                .setApplySecurity(false)
                                .setEnforceTopN(true), // strict_coercion not set -> default false
                        ).build(),
                )
            approveResp.hasPlan() shouldBe true
            approveResp.messagesList.any { it.code == "strict_coercion_rejected" } shouldBe false
        }

        // ----- review-159 -----

        fun request(
            plan: PlanNode,
            options: ValidationOptions.Builder = ValidationOptions.newBuilder().setApplySecurity(true),
        ): ValidateRequest =
            ValidateRequest
                .newBuilder()
                .setPlan(plan)
                .setContext(PipelineContext.newBuilder().setUserId("u1").setModelVersion("v-test"))
                .setOptions(options)
                .build()

        val workspace =
            PlanNode
                .newBuilder()
                .setWorkspaceRef(
                    org.tatrman.plan.v1.WorkspaceRef
                        .newBuilder()
                        .setWorkspaceName("q1"),
                ).build()

        // ② — a workspace switches off security for itself only, never for the tables beside it.
        "a table joined to a workspace is still filtered; the workspace is left as it is" {
            val plan =
                PlanNode
                    .newBuilder()
                    .setJoin(
                        org.tatrman.plan.v1.JoinNode
                            .newBuilder()
                            .setLeft(workspace)
                            .setRight(scan(customers))
                            .setJoinType(org.tatrman.plan.v1.JoinType.INNER),
                    ).build()
            val resp = service(matchingPolicy).validate(request(plan))
            resp.plan.join.left
                .hasWorkspaceRef() shouldBe true
            resp.plan.join.right.filter.condition shouldBe tenant
            resp.securityAppliedList.map { it.ruleId } shouldBe listOf("tenant_isolation")
            resp.context.warningsList.any { it.code == "security_skipped_for_workspace" } shouldBe true
        }

        "a table in a UNION branch beside a workspace is still filtered" {
            val plan =
                PlanNode
                    .newBuilder()
                    .setUnion(
                        org.tatrman.plan.v1.UnionNode
                            .newBuilder()
                            .addInputs(workspace)
                            .addInputs(scan(customers)),
                    ).build()
            val resp = service(matchingPolicy).validate(request(plan))
            resp.plan.union.inputsList[1]
                .filter.condition shouldBe tenant
        }

        // ⑥ — every rejection lists its ERROR first, whatever warnings the pipeline raised before.
        "a strict-coercion rejection comes before the row cap's warning" {
            val intColEqText =
                Expression
                    .newBuilder()
                    .setFunction(
                        FunctionCall
                            .newBuilder()
                            .setOperation("eq")
                            .addOperands(
                                Expression
                                    .newBuilder()
                                    .setColumnRef(ColumnRef.newBuilder().setName("id").setType("int"))
                                    .setResultType("int"),
                            ).addOperands(
                                Expression
                                    .newBuilder()
                                    .setLiteral(Literal.newBuilder().setStringValue("42").setType("text"))
                                    .setResultType("text"),
                            ),
                    ).setResultType("bool")
                    .build()
            val plan =
                PlanNode
                    .newBuilder()
                    .setFilter(
                        org.tatrman.plan.v1.FilterNode
                            .newBuilder()
                            .setInput(scan(customers))
                            .setCondition(intColEqText),
                    ).build()
            val resp =
                service(SecurityClient { _ -> EvaluatePoliciesResponse.getDefaultInstance() }).validate(
                    request(plan, ValidationOptions.newBuilder().setEnforceTopN(true).setStrictCoercion(true)),
                )
            resp.hasPlan() shouldBe false
            resp.messagesList[0].code shouldBe "strict_coercion_rejected"
            resp.messagesList.map { it.code }.contains(RuleEnforcer.TOP_N_APPLIED) shouldBe true
        }

        "an llm_guard rejection comes before the row cap's warning" {
            val down =
                object : LlmGatewayClient {
                    override suspend fun chat(
                        model: String,
                        system: String,
                        user: String,
                        responseFormat: GatewayResponseFormat?,
                    ): String = throw LlmGatewayException("gateway down")

                    override fun close() = Unit
                }
            val svc =
                ValidateServiceImpl(
                    securityApplier = SecurityApplier { _ -> EvaluatePoliciesResponse.getDefaultInstance() },
                    ruleEnforcer = RuleEnforcer(serviceDefault = 30),
                    llmGuard = LlmGuard(enabled = true, gateway = down),
                    metadataClient = StaticMetadataClient("v-test"),
                )
            val resp =
                svc.validate(
                    request(scan(customers), ValidationOptions.newBuilder().setEnforceTopN(true).setLlmGuard(true)),
                )
            resp.hasPlan() shouldBe false
            resp.messagesList[0].code shouldBe "llm_guard_rejected"
        }

        // ⑨ — column rules read the plan as the caller asked it, before security's filters are in it.
        "a MASK on the policy's own column masks the answer and leaves the policy filter intact" {
            val masking =
                SecurityClient { _ ->
                    EvaluatePoliciesResponse
                        .newBuilder()
                        .addPredicates(
                            TablePredicate
                                .newBuilder()
                                .setTable(customers)
                                .setPredicate(tenant)
                                .setRuleId("tenant_isolation"),
                        ).addColumnRules(
                            ColumnRule
                                .newBuilder()
                                .setTable(customers)
                                .setColumn("tenant_id")
                                .setAction(ColumnRule.Action.MASK)
                                .setMaskExpression(
                                    Expression
                                        .newBuilder()
                                        .setLiteral(Literal.newBuilder().setStringValue("***").setType("text")),
                                ).setRuleId("mask_tenant"),
                        ).build()
                }
            val plan =
                PlanNode
                    .newBuilder()
                    .setProject(
                        ProjectNode
                            .newBuilder()
                            .setInput(scan(customers))
                            .addExpressions(NamedExpression.newBuilder().setExpression(columnRef("tenant_id"))),
                    ).build()
            val resp = service(masking).validate(request(plan))
            resp.plan.project.expressionsList[0]
                .expression.literal.stringValue shouldBe "***"
            resp.plan.project.input.filter.condition shouldBe tenant
        }

        "a DENY on a column only the policy filter names does not refuse the query" {
            val denying =
                SecurityClient { _ ->
                    EvaluatePoliciesResponse
                        .newBuilder()
                        .addPredicates(
                            TablePredicate
                                .newBuilder()
                                .setTable(customers)
                                .setPredicate(tenant)
                                .setRuleId("tenant_isolation"),
                        ).addColumnRules(
                            ColumnRule
                                .newBuilder()
                                .setTable(customers)
                                .setColumn("tenant_id")
                                .setAction(ColumnRule.Action.DENY)
                                .setRuleId("hide_tenant"),
                        ).build()
                }
            val plan =
                PlanNode
                    .newBuilder()
                    .setProject(
                        ProjectNode
                            .newBuilder()
                            .setInput(scan(customers))
                            .addExpressions(NamedExpression.newBuilder().setExpression(columnRef("name"))),
                    ).build()
            val resp = service(denying).validate(request(plan))
            resp.hasPlan() shouldBe true
            resp.plan.project.input.filter.condition shouldBe tenant
        }
    })

private fun qname(
    schema: String,
    ns: String,
    name: String,
): QualifiedName =
    QualifiedName
        .newBuilder()
        .setSchemaCode(
            if (schema ==
                "obj"
            ) {
                org.tatrman.plan.v1.SchemaCode.SCHEMA_CODE_UNSPECIFIED
            } else {
                org.tatrman.plan.v1.SchemaCode
                    .valueOf(schema.uppercase())
            },
        ).setNamespace(ns)
        .setName(name)
        .build()

private fun scan(table: QualifiedName): PlanNode =
    PlanNode
        .newBuilder()
        .setTableScan(TableScanNode.newBuilder().setTable(table))
        .build()

/** Build a plan with an ER `ScanNode` leaf — the entity counterpart of [scan]. */
private fun erScan(entity: QualifiedName): PlanNode =
    PlanNode
        .newBuilder()
        .setScan(
            org.tatrman.plan.v1.ScanNode
                .newBuilder()
                .setObject(entity),
        ).build()

private fun columnRef(name: String): Expression =
    Expression
        .newBuilder()
        .setColumnRef(ColumnRef.newBuilder().setName(name))
        .build()

private fun intLit(v: Long): Expression =
    Expression
        .newBuilder()
        .setLiteral(Literal.newBuilder().setIntValue(v).setType("int"))
        .build()

private fun eq(
    left: Expression,
    right: Expression,
): Expression =
    Expression
        .newBuilder()
        .setFunction(
            FunctionCall
                .newBuilder()
                .setOperation("eq")
                .addOperands(left)
                .addOperands(right),
        ).setResultType("bool")
        .build()

private fun message(
    severity: Severity,
    code: String,
): org.tatrman.common.v1.ResponseMessage =
    org.tatrman.common.v1.ResponseMessage
        .newBuilder()
        .setSeverity(severity)
        .setCode(code)
        .setHumanMessage(code)
        .build()
