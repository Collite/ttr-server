// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.policy

import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.schemaCodeToToken
import org.tatrman.security.v1.ColumnRule as ProtoColumnRule
import org.tatrman.security.v1.EvaluatePoliciesRequest
import org.tatrman.security.v1.EvaluatePoliciesResponse
import org.tatrman.security.v1.TablePredicate
import org.tatrman.validate.stages.PlanWalker
import org.slf4j.LoggerFactory

/**
 * PolicyEngine — the in-process row-level-security + column-rule evaluator folded from
 * ai-platform's `infra/sql-security` `SecurityServiceImpl.evaluatePolicies` (fork Stage 3.2).
 *
 * The fold drops the gRPC/REST service layers, the legacy SQL-fragment endpoint, OPA, and the
 * **whois role lookup**. Roles now arrive on the bearer: `EvaluatePoliciesRequest.context.auth_roles`
 * (populated upstream at the query-mcp edge from the JWT's `realm_access.roles`). This is the
 * fork-default `roleSource = bearer` (contracts §3); the optional whois enrichment source is a
 * Phase-5 additive, not built here.
 *
 * Policies come from HOCON (DF-S01 — [PolicyConfigLoader]). Metadata-aware checks (DF-S02): the
 * engine consults the [PolicyMetadataClient] to flag tables absent from the model (`unknown_table`)
 * and a stale request `model_version` (`model_version_mismatch`). Column rules (DF-S02) are returned
 * in `EvaluatePoliciesResponse.column_rules` for Validate's RuleEnforcer (DF-V01).
 */
class PolicyEngine(
    private val registry: PolicyRegistry,
    private val metadata: PolicyMetadataClient = StaticPolicyMetadataClient.permissive(),
) {
    suspend fun evaluatePolicies(request: EvaluatePoliciesRequest): EvaluatePoliciesResponse {
        val context = request.context
        val tableQnames = PlanWalker.scannedTables(request.plan)

        val response = EvaluatePoliciesResponse.newBuilder().setContext(context)
        val identity = resolveIdentity(context.userId, context.authRolesList)
        // LR C-5·2 — fail closed: one per (policy, table) that applies to this caller and cannot be
        // evaluated for it. Any entry refuses the whole request (see the end of this function).
        val denials = mutableListOf<ResponseMessage>()

        // Metadata-aware: flag a stale plan (request model_version ≠ live model version).
        if (context.modelVersion.isNotEmpty()) {
            val live = metadata.currentVersion()
            if (live.isNotEmpty() && live != context.modelVersion) {
                response.addMessages(
                    ResponseMessage
                        .newBuilder()
                        .setSeverity(Severity.WARNING)
                        .setCode("model_version_mismatch")
                        .setHumanMessage(
                            "Request model_version '${context.modelVersion}' ≠ live metadata version '$live' — policies evaluated against the request's tables anyway",
                        ),
                )
            }
        }

        for (table in tableQnames) {
            // Metadata-aware: flag a table the model doesn't know (typo / dropped table).
            if (!metadata.objectExists(table)) {
                response.addMessages(
                    ResponseMessage
                        .newBuilder()
                        .setSeverity(Severity.WARNING)
                        .setCode("unknown_table")
                        .setHumanMessage(
                            "Table '${table.schemaCode}.${table.namespace}.${table.name}' is not present in the metadata model",
                        ),
                )
            }
            val applicable = registry.policiesFor(table, context.authRolesList)
            for (policy in applicable) {
                try {
                    val expr = PolicyToExpression.convert(policy.predicate, identity)
                    response.addPredicates(
                        TablePredicate
                            .newBuilder()
                            .setTable(table)
                            .setPredicate(expr)
                            .setRuleId(policy.id)
                            .setPredicateSummary(policy.description.ifEmpty { policy.id }),
                    )
                } catch (ex: UnresolvableAttributeException) {
                    log.warn(
                        "Denying: policy '{}' on '{}' needs attribute '{}', unresolved for '{}'",
                        policy.id,
                        table.name,
                        ex.attribute,
                        context.userId,
                    )
                    denials.add(
                        error(
                            POLICY_UNRESOLVABLE_ATTRIBUTE,
                            "Policy '${policy.id}' on '${qnameDot(table)}' needs the caller attribute " +
                                "'${ex.attribute}', which is not available for this caller.",
                        ),
                    )
                }
            }
            // Column-level rules for this table (DF-S02) — Validate's RuleEnforcer checks the actual
            // query's columns against these (deny → reject; mask → rewrite).
            for ((policy, rule) in registry.columnRulesFor(table, context.authRolesList)) {
                val b =
                    ProtoColumnRule
                        .newBuilder()
                        .setTable(table)
                        .setColumn(rule.column)
                        .setRuleId(policy.id)
                when (val action = rule.action) {
                    is ColumnAction.Deny -> b.action = ProtoColumnRule.Action.DENY
                    is ColumnAction.Mask -> {
                        b.action = ProtoColumnRule.Action.MASK
                        (action.maskValue as? PolicyValue.Literal)?.let {
                            b.maskExpression = PolicyToExpression.literalExpression(it)
                        }
                    }
                }
                response.addColumnRules(b)
            }
        }
        if (denials.isNotEmpty()) return denied(context, denials, response.messagesList)
        return response.build()
    }

    /**
     * The request is refused, not narrowed: no predicates and no column rules travel back, so nothing
     * downstream can run a partly-restricted plan. `access_denied` comes FIRST — every MCP caller
     * reads the first message's code as the rejection's code — then the per-policy reasons, then
     * whatever warnings the walk raised.
     */
    private fun denied(
        context: org.tatrman.plan.v1.PipelineContext,
        reasons: List<ResponseMessage>,
        warnings: List<ResponseMessage>,
    ): EvaluatePoliciesResponse =
        EvaluatePoliciesResponse
            .newBuilder()
            .setContext(context)
            .addMessages(
                error(
                    ACCESS_DENIED,
                    "Access denied: a row policy applies to this query and could not be evaluated for this caller.",
                ),
            ).addAllMessages(reasons)
            .addAllMessages(warnings)
            .build()

    private fun error(
        code: String,
        human: String,
    ): ResponseMessage =
        ResponseMessage
            .newBuilder()
            .setSeverity(Severity.ERROR)
            .setCode(code)
            .setHumanMessage(human)
            .build()

    private fun qnameDot(qn: QualifiedName): String = "${schemaCodeToToken(qn.schemaCode)}.${qn.namespace}.${qn.name}"

    /** Loaded-policy count, for /status surfaces. */
    fun loadedPolicies(): Int = registry.size()

    /**
     * Build the per-call identity from the bearer. `tenant_id`/`user_id` come from the
     * `tenant:user` split of `user_id` (authoritative); `roles` is the forwarded
     * `auth_roles` list (the bearer's `realm_access.roles`). No whois hop.
     */
    private fun resolveIdentity(
        userId: String,
        authRoles: List<String>,
    ): ResolvedIdentity {
        val base = ResolvedIdentity.fromUserId(userId)
        return if (authRoles.isEmpty()) {
            base
        } else {
            base.withExtra(mapOf("roles" to authRoles.joinToString(",")))
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(PolicyEngine::class.java)

        /** The rejection code of a fail-closed denial (LR C-5·2). */
        const val ACCESS_DENIED = "access_denied"

        /** The reason: an applicable policy names a caller attribute the identity does not carry. */
        const val POLICY_UNRESOLVABLE_ATTRIBUTE = "policy_unresolvable_attribute"
    }
}
