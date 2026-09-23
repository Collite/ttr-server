// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.grpc

import com.google.protobuf.kotlin.toByteString
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.query.cache.CompiledPlanCache
import org.tatrman.query.client.DispatcherClient
import org.tatrman.query.client.TranslatorClient
import org.tatrman.query.client.TranslatorDetectClient
import org.tatrman.query.client.TranslatorTranslateClient
import org.tatrman.query.client.ValidatorClient
import org.tatrman.query.retry.RetryPolicy
import org.tatrman.query.v1.RunRequest
import org.tatrman.translate.v1.DetectSchemaResponse
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.ParseResponse
import org.tatrman.translate.v1.SchemaDecision
import org.tatrman.translate.v1.TranslateResponse
import org.tatrman.validate.v1.SecurityRuleApplied
import org.tatrman.validate.v1.ValidateResponse
import org.tatrman.worker.receipt.ExecutionReceiptBuilder
import org.tatrman.worker.v1.ExecutionReceipt
import org.tatrman.worker.v1.ResultBatch
import org.tatrman.worker.v1.RlsOutcome
import org.tatrman.worker.v1.StatementKind
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * **ES-P0·S0.3 — the query service fills the plan half (⚑ES-1).**
 *
 * the query service is the only hop that holds four of the facts the protocol document has been missing:
 * the plan that was actually **dispatched** (post-validate, physical on the DB path — not the ER
 * plan iris reconstructs today), the validator's `security_applied` set (the one A-1 called
 * *structurally unreachable from kantheon*), whether the compile was a cache hit, and how long it
 * took. It puts them on the FIRST batch, where they are known before a row flows, and re-attaches
 * them to the LAST, so a consumer that reads only the tail has the whole receipt.
 *
 * The one thing it must never do is claim a run that did not happen: a validation that refused
 * dispatches nothing, and a receipt on that error batch would say a plan went to a worker.
 */
class ExecutionReceiptQuerySpec :
    StringSpec({

        "the FIRST batch carries the plan half — the plan dispatch received, byte-equal" {
            val dispatched = CopyOnWriteArrayList<PlanNode>()
            val out = runBlocking { service(dispatcher = capturing(dispatched)).run(request()).toList() }

            val first = out.first()
            first.isFirst shouldBe true
            first.hasReceipt() shouldBe true

            val receipt = first.receipt
            receipt.dispatchedPlan shouldBe dispatched.single()
            receipt.planOmittedReason shouldBe ""
            receipt.cacheHit shouldBe false
            receipt.compileMs shouldBeGreaterThanOrEqual 0L
            // `compile_ms` is the same measurement the `compile_duration_ms` warning already
            // carries — asserting they agree pins the value without pinning the clock.
            receipt.compileMs.toString() shouldBe
                first.context.warningsList
                    .single { it.code == "compile_duration_ms" }
                    .message

            // ER source (the detect stub confirms ER), so the run took the ER→DB path.
            receipt.effectiveSchema shouldBe "ER"

            // The plan half says nothing the worker owns.
            receipt.statementKind shouldBe StatementKind.STATEMENT_KIND_UNSPECIFIED
        }

        "the validator's security_applied travels verbatim" {
            val out = runBlocking { service(validator = validatorApplying(TENANT_RULE)).run(request()).toList() }

            out.first().receipt.securityAppliedList shouldContainExactly listOf(TENANT_RULE)
        }

        "the LAST batch carries the plan half PLUS the worker's statement half, untouched" {
            val out = runBlocking { service().run(request()).toList() }

            val last = out.last()
            last.isLast shouldBe true
            val receipt = last.receipt

            // the query service's half…
            receipt.hasDispatchedPlan() shouldBe true
            receipt.effectiveSchema shouldBe "ER"
            // …and the worker's, exactly as the worker sent it.
            receipt.statementKind shouldBe StatementKind.SQL_EXECUTED
            receipt.statement shouldBe WORKER_SQL
            receipt.rowsTotal shouldBe 42L
            receipt.durationMs shouldBe 184L
            receipt.rls shouldBe RlsOutcome.RLS_APPLIED
            receipt.engineLabel shouldBe "worker-postgres@pg-hartland"
        }

        "a batch that is neither first nor last carries no receipt" {
            val out = runBlocking { service().run(request()).toList() }

            out.size shouldBe 3
            out[1].isFirst shouldBe false
            out[1].isLast shouldBe false
            out[1].hasReceipt() shouldBe false
        }

        "the last batch's cap notice and the plan half both survive — merge, not overwrite" {
            // A capped plan whose validator raises `top_n_applied`: the existing last-batch
            // mutation and the ES merge touch the same builder.
            val out =
                runBlocking {
                    service(
                        translator = parseStubFor(cappedPlan()),
                        validator = validatorCapping(),
                        dispatcher = capping(),
                    ).run(request()).toList()
                }

            val last = out.last()
            last.messagesList.map { it.code } shouldContainExactly listOf("top_n_applied")
            last.hasReceipt() shouldBe true
            last.receipt.statement shouldBe WORKER_SQL
        }

        "a dispatched plan over the cap is omitted with a reason — everything else is present" {
            val huge = scan("p".repeat(ExecutionReceiptBuilder.MAX_PLAN_BYTES + 1))
            val out = runBlocking { service(translator = parseStubFor(huge)).run(request()).toList() }

            val receipt = out.first().receipt
            receipt.hasDispatchedPlan() shouldBe false
            receipt.planOmittedReason shouldBe ExecutionReceiptBuilder.PLAN_OVER_CAP
            receipt.effectiveSchema shouldBe "ER"
            receipt.compileMs shouldBeGreaterThanOrEqual 0L
        }

        "an error batch carries NO receipt — nothing was dispatched, so nothing may be claimed" {
            val refusing = ValidatorClient { throw IllegalStateException("validator down") }
            val out = runBlocking { service(validator = refusing).run(request()).toList() }

            val only = out.single()
            only.messagesList.single().code shouldBe "validator_unavailable"
            only.isLast shouldBe true
            only.hasReceipt() shouldBe false
        }

        // ── Task 1 — the risk architecture §7 named ───────────────────────────────────────────
        "a cache HIT reports the same security_applied as the cold run that filled the cache" {
            runBlocking {
                val cache = CompiledPlanCache(100, Duration.ofMinutes(60))
                val svc = service(validator = validatorApplying(TENANT_RULE), cache = cache)

                val cold =
                    svc
                        .run(request())
                        .toList()
                        .first()
                        .receipt
                val warm =
                    svc
                        .run(request())
                        .toList()
                        .first()
                        .receipt

                cache.stats().hits shouldBe 1
                cold.cacheHit shouldBe false
                warm.cacheHit shouldBe true

                // The set is not cached — it is re-derived, because the query service re-validates on
                // every run (security depends on user_id). Equality here is the proof that the
                // receipt on a cache hit does not say "no rules" and lie.
                warm.securityAppliedList shouldContainExactly cold.securityAppliedList
                warm.securityAppliedList shouldContainExactly listOf(TENANT_RULE)
            }
        }

        "the DB path reports effective_schema = DB" {
            val detectDb =
                TranslatorDetectClient {
                    DetectSchemaResponse
                        .newBuilder()
                        .setDecision(SchemaDecision.CONFIRMED)
                        .setEffectiveSchema(SchemaCode.DB)
                        .build()
                }
            val out = runBlocking { service(translatorDetect = detectDb).run(request()).toList() }

            out.first().receipt.effectiveSchema shouldBe "DB"
        }
    })

private const val WORKER_SQL = "SELECT account_id FROM public.positions"

private val TENANT_RULE: SecurityRuleApplied =
    SecurityRuleApplied
        .newBuilder()
        .setRuleId("rls.tenant")
        .setPredicateSummary("WHERE tenant_id = (your tenant)")
        .build()

private fun scan(table: String): PlanNode =
    PlanNode
        .newBuilder()
        .setTableScan(
            TableScanNode.newBuilder().setTable(
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(SchemaCode.DB)
                    .setNamespace("public")
                    .setName(table),
            ),
        ).build()

private fun cappedPlan(): PlanNode =
    PlanNode
        .newBuilder()
        .setLimitOffset(
            org.tatrman.plan.v1.LimitOffsetNode
                .newBuilder()
                .setInput(scan("positions"))
                .setLimit(2),
        ).build()

/** The worker's half, as the last batch would arrive from dispatch. */
private fun workerHalf(): ExecutionReceipt =
    ExecutionReceiptBuilder
        .statementHalf(
            kind = StatementKind.SQL_EXECUTED,
            dialect = "POSTGRESQL",
            sql = WORKER_SQL,
            parameters = emptyList(),
            connectionId = "pg-hartland",
            engineLabel = "worker-postgres@pg-hartland",
            rowsTotal = 42,
            durationMs = 184,
            rls = RlsOutcome.RLS_APPLIED,
            correlationId = "corr-es",
        )!!

private fun batch(
    index: Int,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    receipt: ExecutionReceipt? = null,
): ResultBatch =
    ResultBatch
        .newBuilder()
        .setBatchIndex(index)
        .setIsFirst(isFirst)
        .setIsLast(isLast)
        .setArrowIpc(ByteArray(0).toByteString())
        .setContext(PipelineContext.getDefaultInstance())
        .apply { if (receipt != null) setReceipt(receipt) }
        .build()

/** first · middle · last — the last one carrying the worker's statement half. */
private fun threeBatches(): List<ResultBatch> =
    listOf(
        batch(0, isFirst = true),
        batch(1),
        batch(2, isLast = true, receipt = workerHalf()),
    )

private val dispatcherStub =
    DispatcherClient { _ -> flowOf(*threeBatches().toTypedArray()) }

private fun capturing(sink: MutableList<PlanNode>) =
    DispatcherClient { req ->
        sink.add(req.plan)
        flowOf(*threeBatches().toTypedArray())
    }

/** One batch that is first AND last, with two rows — enough to reach a cap of 2. */
private fun capping() =
    DispatcherClient { _ ->
        flowOf(
            ResultBatch
                .newBuilder()
                .setBatchIndex(0)
                .setIsFirst(true)
                .setIsLast(true)
                .setBatchRowCount(2)
                .setArrowIpc(ByteArray(0).toByteString())
                .setContext(PipelineContext.getDefaultInstance())
                .setReceipt(workerHalf())
                .build(),
        )
    }

private fun parseStubFor(plan: PlanNode) =
    TranslatorClient { req ->
        ParseResponse
            .newBuilder()
            .setPlan(plan)
            .setContext(req.context)
            .build()
    }

private fun validatorApplying(vararg rules: SecurityRuleApplied) =
    ValidatorClient { req ->
        ValidateResponse
            .newBuilder()
            .setPlan(req.plan)
            .setContext(req.context)
            .addAllSecurityApplied(rules.toList())
            .build()
    }

private fun validatorCapping() =
    ValidatorClient { req ->
        ValidateResponse
            .newBuilder()
            .setPlan(req.plan)
            .setContext(req.context)
            .addMessages(
                org.tatrman.common.v1.ResponseMessage
                    .newBuilder()
                    .setSeverity(org.tatrman.common.v1.Severity.WARNING)
                    .setCode("top_n_applied")
                    .setHumanMessage("Result capped at 2 rows."),
            ).build()
    }

private fun request(): RunRequest =
    RunRequest
        .newBuilder()
        .setSource("SELECT account_id FROM positions")
        .setSourceLanguage(Language.SQL)
        .setContext(PipelineContext.newBuilder().setUserId("u").setModelVersion("v"))
        .build()

@Suppress("LongParameterList")
private fun service(
    translator: TranslatorClient = parseStubFor(scan("positions")),
    translatorDetect: TranslatorDetectClient =
        TranslatorDetectClient {
            DetectSchemaResponse
                .newBuilder()
                .setDecision(SchemaDecision.CONFIRMED)
                .setEffectiveSchema(SchemaCode.ER)
                .build()
        },
    translatorTranslate: TranslatorTranslateClient =
        TranslatorTranslateClient { req ->
            TranslateResponse
                .newBuilder()
                .setOutput("OUT")
                .setContext(req.context)
                .build()
        },
    validator: ValidatorClient = validatorApplying(),
    dispatcher: DispatcherClient = dispatcherStub,
    cache: CompiledPlanCache = CompiledPlanCache(100, Duration.ofMinutes(60)),
): QueryServiceImpl =
    QueryServiceImpl(
        translator,
        translatorDetect,
        translatorTranslate,
        validator,
        dispatcher,
        cache,
        RetryPolicy(maxAttempts = 1, initialBackoffMillis = 1, multiplier = 1.0, jitterPercent = 0),
    )
