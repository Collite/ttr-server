// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.grpc

import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.Warning
import org.tatrman.validate.v1.ValidateRequest
import org.tatrman.validate.v1.ValidateResponse
import org.tatrman.validate.v1.ValidateServiceGrpcKt
import io.grpc.Status
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.tatrman.validate.client.MetadataClient
import org.tatrman.validate.roles.BearerRoleSource
import org.tatrman.validate.roles.RoleSource
import org.tatrman.validate.roles.RoleSourceUnavailableException
import org.tatrman.validate.stages.LlmGuard
import org.tatrman.validate.stages.MixedLayerDetector
import org.tatrman.validate.stages.RuleEnforcer
import org.tatrman.validate.stages.SecurityApplier
import org.tatrman.validate.stages.StrictCoercionChecker
import org.tatrman.validate.stages.WorkspaceRefDetector
import org.tatrman.translator.framework.SchemaVersionVerifier
import org.tatrman.translator.framework.VerificationResult

/**
 * gRPC entrypoint for the Validator. The order of operations is:
 *
 *   1. Schema-version verification (Section F). Mismatch produces a warning
 *      that flows back to the caller via PipelineContext + ResponseMessage,
 *      never a hard error.
 *   2. SECURITY (Section B). Skipped when `options.apply_security = false`. The bypass is gated
 *      on a Keycloak admin role (DF-V02): the caller's roles travel in
 *      `PipelineContext.auth_roles` (populated upstream by `tools/query-mcp`'s `IdentityResolver`
 *      from a JWT's `realm_access.roles`); the bypass is honoured iff that list contains the
 *      service-configured admin role (`validator.security-bypass.admin-role`, default
 *      `query-platform-admin`). Trust model: validator trusts the upstream-populated context; the
 *      MCP edge is the JWT trust boundary in v1. Inter-service JWT re-validation is v2.
 *   3. RULES (Section C). TopN + column allow/deny/mask enforcement (DF-V01) — consumes the
 *      `column_rules` returned by sql-security alongside the row predicates. A `DENY` on a
 *      referenced column short-circuits with a `column_denied` ERROR and no plan; `MASK` rewrites
 *      `ColumnRef` expressions to the rule's mask expression.
 *   3b. STRICT_COERCION (Phase 08 C1 / DF-V06). Off by default; when on, comparison expressions
 *       whose operand surface-types disagree (`int vs text`, etc.) surface as
 *       `strict_coercion_rejected` ERRORs and the response carries no plan.
 *   4. LLM_GUARD (Section D). Only runs when both the request and HOCON flag
 *      enable it; rejection short-circuits the response.
 *
 * Errors travel through the platform's structured-message channel: gRPC
 * status `OK` with a populated `messages = 99`. Reserved for genuine
 * transport failures only.
 */

@Suppress("ktlint:standard:max-line-length")
class ValidateServiceImpl(
    private val securityApplier: SecurityApplier,
    private val ruleEnforcer: RuleEnforcer,
    private val llmGuard: LlmGuard,
    private val metadataClient: MetadataClient,
    private val adminRole: String = "query-platform-admin",
    private val roleSource: RoleSource = BearerRoleSource(),
) : ValidateServiceGrpcKt.ValidateServiceCoroutineImplBase() {
    override suspend fun validate(request: ValidateRequest): ValidateResponse {
        val incomingPlan = request.plan
        val incomingContext = request.context
        val options = request.options

        val responseMessages = mutableListOf<ResponseMessage>()
        val contextBuilder = incomingContext.toBuilder()

        // §60 — mixed-layer rejection: reject plans that contain both ER ScanNode and DB TableScanNode.
        if (MixedLayerDetector.hasMixedLayers(incomingPlan)) {
            responseMessages.add(
                message(
                    severity = Severity.ERROR,
                    code = "validator_mixed_layer_tree",
                    human = "Plan contains both ER and DB layer nodes — mixed-layer trees are not supported.",
                ),
            )
            return ValidateResponse
                .newBuilder()
                .setContext(contextBuilder.build())
                .addAllMessages(responseMessages)
                .build()
        }

        // Section F — schema-version verification. Metadata is a HARD dependency: an
        // unreachable metadata service fails the call with gRPC UNAVAILABLE rather than
        // degrading to a warning. The DependencyMonitor flips /ready to 503 so K8s pulls
        // traffic until metadata recovers; this propagation handles requests that race in.
        val currentVersion = readCurrentVersionOrFail()
        if (currentVersion.isNotEmpty()) {
            when (val v = SchemaVersionVerifier.verifyContext(incomingContext, currentVersion)) {
                is VerificationResult.MismatchWarn -> {
                    contextBuilder.addWarnings(
                        warning(
                            code = "model_version_mismatch_minor",
                            message = "Plan compiled against ${v.got} but metadata is ${v.expected}.",
                        ),
                    )
                }
                is VerificationResult.Missing -> {
                    contextBuilder.addWarnings(
                        warning(
                            code = "model_version_missing",
                            message = "Request did not carry a model_version; using metadata's ${v.current}.",
                        ),
                    )
                }
                VerificationResult.Ok -> Unit
            }
        }

        // Fork Stage 5.3 — resolve the EFFECTIVE role set via the configured RoleSource. Default
        // (`bearer`) returns the forwarded bearer's `auth_roles` verbatim (Phase-3 behaviour, no
        // change). The opt-in `whois` source enriches that floor with the ERP-hierarchy roles the
        // JWT omits, keyed by the bearer-trusted user_id. On an enrichment-source outage we FAIL
        // CLOSED (kantheon-security §2.1): a Rule-6 error and no plan — never a wider role set.
        val effectiveRoles =
            try {
                roleSource.resolveRoles(incomingContext)
            } catch (e: RoleSourceUnavailableException) {
                log.warn(
                    "role source unavailable for user '{}' — failing closed: {}",
                    incomingContext.userId,
                    e.message,
                )
                responseMessages.add(
                    message(
                        severity = Severity.ERROR,
                        code = "role_source_unavailable",
                        human = "Role enrichment source is unavailable; the request is rejected (fail-closed).",
                    ),
                )
                return ValidateResponse
                    .newBuilder()
                    .setContext(contextBuilder.build())
                    .addAllMessages(responseMessages)
                    .build()
            }
        // Write the enriched roles back so the admin gate AND the SecurityApplier (which reads
        // PipelineContext.auth_roles for RLS) both see them. No-op in bearer mode.
        if (effectiveRoles != incomingContext.authRolesList) {
            contextBuilder.clearAuthRoles().addAllAuthRoles(effectiveRoles)
        }

        // DF-V02 admin gating for `apply_security = false` — honoured iff the caller carries the
        // configured admin role in the effective role set (bearer, or bearer + whois enrichment).
        val applySecurity =
            if (!options.applySecurity) {
                if (adminRole in effectiveRoles) {
                    false
                } else {
                    log.warn(
                        "Caller '{}' attempted apply_security = false without the '{}' role; forcing security on",
                        incomingContext.userId,
                        adminRole,
                    )
                    responseMessages.add(
                        message(
                            severity = Severity.WARNING,
                            code = "security_bypass_denied",
                            human = "Caller does not hold the '$adminRole' role required to bypass security; apply_security forced to true.",
                        ),
                    )
                    true
                }
            } else {
                true
            }

        // Phase 2.4 — workspace-rooted plans skip the SecurityApplier.
        // Workspace data was already filtered when produced; re-evaluating
        // would be wrong (double-filter) and not always possible (joined /
        // aggregated workspace dfs may not have a clean predicate root).
        // Trade-off documented in progress-phase-02-4-worker-polars.md.
        val planContainsWorkspaceRef = WorkspaceRefDetector.hasWorkspaceRef(incomingPlan)
        val effectiveApplySecurity = applySecurity && !planContainsWorkspaceRef
        if (planContainsWorkspaceRef && applySecurity) {
            contextBuilder.addWarnings(
                warning(
                    code = "security_skipped_for_workspace",
                    message = "Plan references a session-scoped workspace; security applied at workspace creation time, not re-evaluated here.",
                ),
            )
        }

        // Section B — SECURITY.
        var workingPlan = incomingPlan
        val applied = mutableListOf<org.tatrman.validate.v1.SecurityRuleApplied>()
        var columnRules: List<org.tatrman.security.v1.ColumnRule> = emptyList()
        if (effectiveApplySecurity) {
            // sql-security is a HARD dependency too — same fail-fast posture as metadata above.
            val securityResult = applySecurityOrFail(workingPlan, contextBuilder.build())
            if (securityResult.denied) {
                responseMessages.addAll(securityResult.messages)
                return rejection(contextBuilder.build(), applied, responseMessages)
            }
            workingPlan = securityResult.plan
            applied.addAll(securityResult.applied)
            responseMessages.addAll(securityResult.messages)
            columnRules = securityResult.columnRules
            // DF-V05 / G7 — propagate per-rule `security_predicate_applied` warnings into the
            // PipelineContext so the warning surface reaches query-mcp's `pipeline_warnings`.
            securityResult.warnings.forEach(contextBuilder::addWarnings)
        }

        // Section C — RULES (DF-V01 column deny/mask enforcement + TopN).
        val rulesResult = ruleEnforcer.enforce(workingPlan, options, columnRules)
        responseMessages.addAll(rulesResult.messages)
        if (rulesResult.rejected) {
            return rejection(contextBuilder.build(), applied, responseMessages)
        }
        workingPlan = rulesResult.plan

        // Phase 08 C1 / DF-V06 — strict-mode coercion check. Off by default; when on, every
        // comparison whose operands carry disagreeing surface-type tags surfaces as a
        // strict_coercion_rejected ERROR and the response carries no plan. The detection is
        // best-effort over the codecs' result_type tags; without the RESOLVE stage (A1) bare
        // ColumnRefs often arrive untyped and the check no-ops on them.
        if (options.strictCoercion) {
            val coercionErrors = StrictCoercionChecker.check(workingPlan)
            if (coercionErrors.isNotEmpty()) {
                responseMessages.addAll(coercionErrors)
                return ValidateResponse
                    .newBuilder()
                    .setContext(contextBuilder.build())
                    .addAllSecurityApplied(applied)
                    .addAllMessages(responseMessages)
                    .build()
            }
        }

        // Section D — LLM_GUARD.
        if (options.llmGuard) {
            when (val decision = llmGuard.evaluate(workingPlan, contextBuilder.build())) {
                null -> Unit // disabled at config level — silent skip
                is LlmGuard.Decision.Approved -> {
                    workingPlan = decision.plan
                    responseMessages.add(decision.warning)
                }
                is LlmGuard.Decision.Rejected -> {
                    responseMessages.add(
                        message(
                            severity = Severity.ERROR,
                            code = "llm_guard_rejected",
                            human = decision.reason,
                        ),
                    )
                    return ValidateResponse
                        .newBuilder()
                        .setContext(contextBuilder.build())
                        .addAllSecurityApplied(applied)
                        .addAllMessages(responseMessages)
                        .build()
                }
            }
        }

        // §62 — populate PipelineContext.used_objects with all ER entities and DB tables
        // referenced in the plan. Used for audit and lineage tracking.
        for (ref in MixedLayerDetector.collectUsedObjects(workingPlan)) {
            contextBuilder.addUsedObjects(ref)
        }

        return ValidateResponse
            .newBuilder()
            .setPlan(workingPlan)
            .setContext(contextBuilder.build())
            .addAllSecurityApplied(applied)
            .addAllMessages(responseMessages)
            .build()
    }

    /**
     * A response with no plan. Its ERRORs come first, in their own order, then everything else: every
     * MCP caller (query-mcp's tools, and through them the Golem) reads the FIRST message's code as the
     * rejection's code, and a warning raised earlier in the pipeline — `security_bypass_denied`, an
     * engine's `unknown_table` — must not stand in for the reason the request was refused.
     */
    private fun rejection(
        context: PipelineContext,
        applied: List<org.tatrman.validate.v1.SecurityRuleApplied>,
        messages: List<ResponseMessage>,
    ): ValidateResponse =
        ValidateResponse
            .newBuilder()
            .setContext(context)
            .addAllSecurityApplied(applied)
            .addAllMessages(messages.sortedByDescending { it.severity == Severity.ERROR })
            .build()

    /**
     * Read the metadata model version, mapping any downstream failure to a clean gRPC
     * UNAVAILABLE (instead of leaking the raw channel "io exception"). [CancellationException]
     * is rethrown untouched so coroutine cancellation still works.
     */
    private suspend fun readCurrentVersionOrFail(): String =
        try {
            metadataClient.currentVersion()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            log.warn("metadata unavailable — failing validation: {}", e.message)
            throw Status.UNAVAILABLE
                .withDescription("metadata service unavailable: ${e.message}")
                .withCause(e)
                .asRuntimeException()
        }

    /** As [readCurrentVersionOrFail] but for the sql-security `EvaluatePolicies` call. */
    private suspend fun applySecurityOrFail(
        plan: org.tatrman.plan.v1.PlanNode,
        context: PipelineContext,
    ): SecurityApplier.Result =
        try {
            securityApplier.apply(plan, context)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            log.warn("sql-security unavailable — failing validation: {}", e.message)
            throw Status.UNAVAILABLE
                .withDescription("sql-security service unavailable: ${e.message}")
                .withCause(e)
                .asRuntimeException()
        }

    private fun warning(
        code: String,
        message: String,
    ): Warning =
        Warning
            .newBuilder()
            .setCode(code)
            .setMessage(message)
            .setSourceStage("validate")
            .setSourceService("validator")
            .build()

    private fun message(
        severity: Severity,
        code: String,
        human: String,
    ): ResponseMessage =
        ResponseMessage
            .newBuilder()
            .setSeverity(severity)
            .setCode(code)
            .setHumanMessage(human)
            .build()

    @Suppress("unused")
    private fun PipelineContext.dumpForLog(): String = "user=$userId, model_version=$modelVersion"

    companion object {
        private val log = LoggerFactory.getLogger(ValidateServiceImpl::class.java)
    }
}
