// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.policy

import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.security.v1.ColumnRule as ProtoColumnRule
import org.tatrman.security.v1.EvaluatePoliciesRequest
import org.tatrman.security.v1.EvaluatePoliciesResponse
import org.tatrman.security.v1.TablePredicate
import org.tatrman.validate.stages.PlanWalker
import org.tatrman.validate.stages.dotted
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
        val caller = Caller(context.authRolesList, identity.attributeNames())
        // LR C-5·2 — fail closed: one per (policy, table) that applies to this caller and cannot be
        // enforced for it. Any entry refuses the whole request (see the end of this function).
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

        // A write's target is not a scan: no filter can narrow which of its rows the write replaces,
        // updates or deletes. A row policy that covers it for this caller refuses the write.
        for (target in PlanWalker.writeTargets(request.plan)) {
            for (policy in registry.policiesFor(target, caller)) {
                denials.add(
                    error(
                        POLICY_RESTRICTED_WRITE,
                        "Policy '${policy.id}' restricts the rows of '${target.dotted()}' for this caller; " +
                            "a write to it cannot be narrowed to those rows.",
                    ),
                )
            }
        }

        for (table in tableQnames) {
            for (policy in registry.policiesFor(table, caller)) {
                val expr =
                    enforceable(policy, table, context.userId, denials) {
                        PolicyToExpression.convert(policy.predicate, identity)
                    } ?: continue
                response.addPredicates(
                    TablePredicate
                        .newBuilder()
                        .setTable(table)
                        .setPredicate(expr)
                        .setRuleId(policy.id)
                        .setPredicateSummary(policy.description.ifEmpty { policy.id }),
                )
                // Column-level rules (DF-S02) — Validate's RuleEnforcer checks the actual query's
                // columns against these (deny → reject; mask → rewrite).
                for (rule in policy.columnRules) {
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
                            b.maskExpression =
                                enforceable(policy, table, context.userId, denials) {
                                    PolicyToExpression.maskExpression(action.maskValue, identity)
                                } ?: continue
                        }
                    }
                    response.addColumnRules(b)
                }
            }
        }
        if (denials.isNotEmpty()) return denied(context, denials, response.messagesList)

        // Metadata-aware: flag a table the model doesn't know (typo / dropped table). Only for a
        // request that will run — a refusal does not wait on one lookup per table — and concurrently.
        val known =
            coroutineScope {
                tableQnames.map { table -> async { table to metadata.objectExists(table) } }.awaitAll()
            }
        for ((table, exists) in known) {
            if (!exists) {
                response.addMessages(
                    ResponseMessage
                        .newBuilder()
                        .setSeverity(Severity.WARNING)
                        .setCode("unknown_table")
                        .setHumanMessage("Table '${table.dotted()}' is not present in the metadata model"),
                )
            }
        }
        return response.build()
    }

    /**
     * [evaluate]d for [policy] on [table], or null when it cannot be for this caller — the denial is
     * recorded in [denials] (LR C-5·2). A missing caller attribute is the expected reason; any other
     * failure is a policy this engine cannot apply, and it refuses as well rather than failing the
     * call as "unavailable", which the query service would retry as transient.
     */
    private inline fun <T> enforceable(
        policy: Policy,
        table: QualifiedName,
        userId: String,
        denials: MutableList<ResponseMessage>,
        evaluate: () -> T,
    ): T? =
        try {
            evaluate()
        } catch (ex: UnresolvableAttributeException) {
            log.warn(
                "Denying: policy '{}' on '{}' needs attribute '{}', unresolved for '{}'",
                policy.id,
                table.dotted(),
                ex.attribute,
                userId,
            )
            denials.add(
                error(
                    POLICY_UNRESOLVABLE_ATTRIBUTE,
                    "Policy '${policy.id}' on '${table.dotted()}' needs the caller attribute " +
                        "'${ex.attribute}', which is not available for this caller.",
                ),
            )
            null
        } catch (ex: Exception) {
            log.error(
                "Denying: policy '{}' on '{}' could not be evaluated for '{}'",
                policy.id,
                table.dotted(),
                userId,
                ex,
            )
            denials.add(
                error(
                    POLICY_EVALUATION_FAILED,
                    "Policy '${policy.id}' on '${table.dotted()}' could not be evaluated for this caller.",
                ),
            )
            null
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
                    "Access denied: a row policy applies to this query and cannot be enforced for this caller.",
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

        /** The reason: a row policy covers a table the plan WRITES — a write cannot be narrowed. */
        const val POLICY_RESTRICTED_WRITE = "policy_restricted_write"

        /** The reason: an applicable policy failed to evaluate for an unexpected cause (logged). */
        const val POLICY_EVALUATION_FAILED = "policy_evaluation_failed"
    }
}
