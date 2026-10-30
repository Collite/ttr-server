// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.e2e

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import org.tatrman.common.v1.Severity
import org.tatrman.dispatch.v1.DispatchRequest
import org.tatrman.meta.v1.GetQueryResponse
import org.tatrman.meta.v1.ParseStatus
import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.Value
import org.tatrman.query.cache.CompiledPlanCache
import org.tatrman.query.client.DispatcherClient
import org.tatrman.query.client.SavedQueryClient
import org.tatrman.query.client.TranslatorClient
import org.tatrman.query.client.TranslatorDetectClient
import org.tatrman.query.client.TranslatorTranslateClient
import org.tatrman.query.client.ValidatorClient
import org.tatrman.query.grpc.ParameterCheck
import org.tatrman.query.grpc.QueryServiceImpl
import org.tatrman.query.retry.RetryPolicy
import org.tatrman.query.v1.RunRequest
import org.tatrman.query.v1.RunSavedQueryRequest
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.ParseResponse
import org.tatrman.translator.framework.HartlandJoinModel
import org.tatrman.translator.orchestrator.ParseResult
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.params.SqlParam
import org.tatrman.validate.v1.ValidateResponse
import org.tatrman.worker.v1.ResultBatch
import java.time.Duration

/**
 * #260: #264 already added the pre-dispatch guard. Exercise it with the pinned translator's actual plans,
 * including saved canonical plans and a cache hit, rather than a scripted plan containing one ParameterRef.
 * Validation and dispatch are recording seams; the real worker/unparse binder is not run here.
 * Exact-name compatibility is pinned at the guard before dispatch; this is not a database integration test.
 */
class ParameterContractSpec :
    StringSpec({
        val translator = Translator(HartlandJoinModel.handle())
        val declared =
            ParameterBinding
                .newBuilder()
                .setName("id")
                .setType("int")
                .build()
        val sql = "SELECT shopper_id FROM customer WHERE shopper_id = {id} OR shopper_id = {id}"

        fun context(binding: ParameterBinding? = declared): PipelineContext =
            PipelineContext
                .newBuilder()
                .setUserId("reader")
                .setModelVersion("v1")
                .setCorrelationId("parameter-contract")
                .apply { if (binding != null) addParameters(binding) }
                .build()

        fun parsed(
            source: String = sql,
            schema: SchemaCode = SchemaCode.DB,
        ): PlanNode =
            translator
                .parseToRelNode(
                    source,
                    Language.SQL,
                    targetSchema = schema,
                    sourceSchema = schema,
                    parameters = listOf(SqlParam("id", "int", null)),
                ).shouldBeInstanceOf<ParseResult.Success>()
                .plan

        fun request(ctx: PipelineContext): RunRequest =
            RunRequest
                .newBuilder()
                .setSource(sql)
                .setSourceLanguage(Language.SQL)
                .setSourceSchema(SchemaCode.DB)
                .setContext(ctx)
                .build()

        class Harness(
            val stored: PlanNode? = null,
        ) {
            val dispatched = mutableListOf<DispatchRequest>()
            val cache = CompiledPlanCache(100, Duration.ofHours(1))
            var parses = 0
            val service =
                QueryServiceImpl(
                    TranslatorClient { req ->
                        parses++
                        // The production service supplies names/types to this same library. Values are irrelevant
                        // to parsing: every execution binds its own context at dispatch, including cache hits.
                        when (
                            val result =
                                translator.parseToRelNode(
                                    req.source,
                                    req.sourceLanguage,
                                    targetSchema = req.targetSchema,
                                    sourceSchema = req.sourceSchema,
                                    parameters = req.context.parametersList.map { SqlParam(it.name, it.type, null) },
                                    expandLogicalBodies = req.expandLogicalBodies,
                                )
                        ) {
                            is ParseResult.Success ->
                                ParseResponse
                                    .newBuilder()
                                    .setPlan(result.plan)
                                    .setContext(req.context)
                                    .build()
                            is ParseResult.Failure ->
                                ParseResponse
                                    .newBuilder()
                                    .setContext(req.context)
                                    .addMessages(
                                        org.tatrman.common.v1.ResponseMessage
                                            .newBuilder()
                                            .setSeverity(Severity.ERROR)
                                            .setCode(result.code)
                                            .setHumanMessage(result.message),
                                    ).build()
                        }
                    },
                    TranslatorDetectClient { req ->
                        val result =
                            org.tatrman.translator.detect.SchemaDetector.detect(
                                req.source,
                                req.sourceLanguage,
                                req.statedSchema,
                                HartlandJoinModel.handle(),
                                savedQueryRefsNeutral = true,
                            )
                        org.tatrman.translate.v1.DetectSchemaResponse
                            .newBuilder()
                            .setEffectiveSchema(result.effectiveSchema)
                            .setDecision(
                                org.tatrman.translate.v1.SchemaDecision
                                    .valueOf(result.decision.name),
                            ).build()
                    },
                    TranslatorTranslateClient { error("translate RPC is not used") },
                    ValidatorClient { req ->
                        ValidateResponse
                            .newBuilder()
                            .setPlan(req.plan)
                            .setContext(req.context)
                            .build()
                    },
                    DispatcherClient { req ->
                        dispatched += req
                        flowOf(
                            ResultBatch
                                .newBuilder()
                                .setIsFirst(true)
                                .setIsLast(true)
                                .setContext(req.context)
                                .build(),
                        )
                    },
                    cache,
                    RetryPolicy(maxAttempts = 1, initialBackoffMillis = 1, multiplier = 1.0, jitterPercent = 0),
                    rawSavedQueries =
                        SavedQueryClient {
                            GetQueryResponse
                                .newBuilder()
                                .setParseStatus(ParseStatus.PARSE_STATUS_PARSED)
                                .setCanonicalForm(checkNotNull(stored))
                                .build()
                        },
                )
        }

        fun refusal(
            batches: List<ResultBatch>,
            ctx: PipelineContext,
        ) {
            val batch = batches.single()
            batch.isFirst shouldBe true
            batch.isLast shouldBe true
            batch.arrowIpc.isEmpty shouldBe true
            batch.context shouldBe ctx
            batch.messagesList.single().severity shouldBe Severity.ERROR
            batch.messagesList.single().code shouldBe "parameter_unbound"
            batch.messagesList.single().humanMessage shouldBe
                "Parameter 'id' has no value; bind it, or send an explicit NULL."
        }

        "typed SQL with a valueless declaration is refused before dispatch using real nested parameter refs" {
            val plan = parsed()
            ParameterCheck.unbound(plan, context(null)) shouldBe listOf("id") // duplicates reported once
            val h = Harness()
            refusal(h.service.run(request(context())).toList(), context())
            h.dispatched.shouldBeEmpty()
        }

        "an empty Value is unbound, while explicit NULL and zero are values" {
            val h = Harness()
            val empty = context(declared.toBuilder().setValue(Value.getDefaultInstance()).build())
            refusal(h.service.run(request(empty)).toList(), empty)
            h.dispatched.shouldBeEmpty()
            for (value in listOf(
                Value.newBuilder().setIsNull(true).build(),
                Value.newBuilder().setIntValue(0).build(),
            )) {
                val ctx = context(declared.toBuilder().setValue(value).build())
                h.service
                    .run(request(ctx))
                    .toList()
                    .flatMap { it.messagesList }
                    .shouldBeEmpty()
                h.dispatched
                    .last()
                    .context.parametersList
                    .single()
                    .value shouldBe value
            }
        }

        "a cache hit checks this execution's bindings after a bound execution" {
            val h = Harness()
            val bound = context(declared.toBuilder().setValue(Value.newBuilder().setIntValue(7)).build())
            h.service
                .run(request(bound))
                .toList()
                .flatMap { it.messagesList }
                .shouldBeEmpty()
            val parses = h.parses
            val hits = h.cache.stats().hits
            refusal(h.service.run(request(context())).toList(), context())
            h.parses shouldBe parses
            h.cache.stats().hits shouldBe hits + 1
            h.dispatched.size shouldBe 1
            h.dispatched.single().context shouldBe bound
        }

        "Compile reports a valueless declaration and its cached plan is refused by Run" {
            val h = Harness()
            val compiled = h.service.compile(request(context()))
            compiled.messagesList.filter { it.severity == Severity.ERROR }.shouldBeEmpty()
            compiled.hasPlan() shouldBe true
            compiled.requiredParametersList.map { it.name } shouldBe listOf("id")
            ParameterCheck.unbound(compiled.plan, context()) shouldBe listOf("id")
            val parses = h.parses
            refusal(h.service.run(request(context())).toList(), context())
            h.parses shouldBe parses
            h.cache.stats().hits shouldBe 1
            h.dispatched.shouldBeEmpty()
        }

        // LQ-5: logical saved queries cannot declare parameters. Only the stored DB shape is valid.
        run {
            val schema = SchemaCode.DB
            val plan = parsed()
            val name =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(schema)
                    .setNamespace("query")
                    .setName("by_id")
                    .build()

            fun saved(ctx: PipelineContext) =
                RunSavedQueryRequest
                    .newBuilder()
                    .setQualifiedName(name)
                    .setContext(ctx)
                    .build()

            "saved $schema query refuses missing, valueless and empty bindings before dispatch" {
                val h = Harness(plan)
                for (ctx in listOf(
                    context(null),
                    context(),
                    context(declared.toBuilder().setValue(Value.getDefaultInstance()).build()),
                )) {
                    refusal(h.service.runSavedQuery(saved(ctx)).toList(), ctx)
                }
                h.dispatched.shouldBeEmpty()
            }

            "saved $schema query forwards a value or explicit NULL from this caller" {
                val h = Harness(plan)
                for (value in listOf(
                    Value.newBuilder().setIntValue(7).build(),
                    Value.newBuilder().setIsNull(true).build(),
                )) {
                    val ctx = context(declared.toBuilder().setValue(value).build())
                    h.service
                        .runSavedQuery(saved(ctx))
                        .toList()
                        .flatMap { it.messagesList }
                        .shouldBeEmpty()
                    h.dispatched.last().context shouldBe ctx
                    ParameterCheck.unbound(h.dispatched.last().plan, context(null)) shouldBe listOf("id")
                }
                h.dispatched.size shouldBe 2
            }
        }

        "a REL_NODE plan with no binding is parameter_unbound even without declaration metadata" {
            val h = Harness()
            val req =
                RunRequest
                    .newBuilder()
                    .setSource(String(parsed().toByteArray(), Charsets.ISO_8859_1))
                    .setSourceLanguage(Language.REL_NODE)
                    .setSourceSchema(SchemaCode.DB)
                    .setContext(context(null))
                    .build()
            refusal(h.service.run(req).toList(), context(null))
            h.dispatched.shouldBeEmpty()
        }

        "SQL with no typed bindings is parsed verbatim and fails before the plan-reference check" {
            val raw =
                translator
                    .parseToRelNode(sql, Language.SQL, targetSchema = SchemaCode.DB, sourceSchema = SchemaCode.DB)
                    .shouldBeInstanceOf<ParseResult.Failure>()
            val h = Harness()
            val batch =
                h.service
                    .run(request(context(null)))
                    .toList()
                    .single()
            batch.messagesList.single().code shouldBe "translator_rejected"
            batch.messagesList.single().humanMessage shouldBe raw.message
            h.dispatched.shouldBeEmpty()
        }

        "an undeclared SQL placeholder with other typed bindings is parameter_unbound" {
            val h = Harness()
            val other = context(declared.toBuilder().setName("other").build())
            val batch =
                h.service
                    .run(request(other))
                    .toList()
                    .single()
            batch.messagesList.single().code shouldBe "parameter_unbound"
            batch.messagesList.single().humanMessage shouldContain "'{id}'"
            h.dispatched.shouldBeEmpty()
        }

        "Compile derives requirements from a stored plan even without declarations and omits unused bindings" {
            val h = Harness()
            val rel =
                request(context(null))
                    .toBuilder()
                    .setSource(
                        String(parsed().toByteArray(), Charsets.ISO_8859_1),
                    ).setSourceLanguage(Language.REL_NODE)
                    .build()
            for (ctx in listOf(context(null), context(declared.toBuilder().setName("unused").build()))) {
                val compiled = h.service.compile(rel.toBuilder().setContext(ctx).build())
                compiled.hasPlan() shouldBe true
                compiled.requiredParametersList.map { it.name } shouldBe listOf("id")
                compiled.requiredParametersList.single().type shouldBe ""
                compiled.requiredParametersList.single().hasValue() shouldBe false
            }
            h.dispatched.shouldBeEmpty()
        }

        for (schema in listOf(SchemaCode.DB, SchemaCode.ER)) {
            for (primeWithRun in listOf(true, false)) {
                "Compile $schema isolates values after a ${if (primeWithRun) "Run" else "Compile"} cache fill" {
                    val h = Harness()

                    fun req(ctx: PipelineContext) =
                        request(ctx)
                            .toBuilder()
                            .setSourceSchema(schema)
                            .setSource(if (schema == SchemaCode.ER) sql.replace("customer", "shopper") else sql)
                            .build()
                    val a =
                        context(declared.toBuilder().setValue(Value.newBuilder().setIntValue(4242)).build())
                            .toBuilder()
                            .setUserId("A")
                            .build()
                    if (primeWithRun) {
                        h.service
                            .run(req(a))
                            .toList()
                            .flatMap { it.messagesList }
                            .shouldBeEmpty()
                    } else {
                        val first = h.service.compile(req(a))
                        first.hasPlan() shouldBe true
                        first.requiredParametersList
                            .single()
                            .value.intValue shouldBe 4242
                    }
                    val b = context().toBuilder().setUserId("B").build()
                    val hits = h.cache.stats().hits
                    val compiled = h.service.compile(req(b))
                    compiled.hasPlan() shouldBe true
                    compiled.requiredParametersList.map { it.name } shouldBe listOf("id")
                    compiled.requiredParametersList.single().type shouldBe "int"
                    compiled.requiredParametersList.single().hasValue() shouldBe false
                    compiled.toString() shouldNotContain "4242"
                    h.cache.stats().hits shouldBe hits + 1
                    val own =
                        b
                            .toBuilder()
                            .clearParameters()
                            .addParameters(declared.toBuilder().setValue(Value.newBuilder().setIntValue(7)))
                            .build()
                    val withOwn = h.service.compile(req(own))
                    withOwn.requiredParametersList
                        .single()
                        .value.intValue shouldBe 7
                    withOwn.toString() shouldNotContain "4242"
                }
            }
        }

        for (schema in listOf(SchemaCode.DB, SchemaCode.ER)) {
            "Compile $schema cache hit derives requirements from the plan, not the declarations" {
                val h = Harness()
                val ctx =
                    context()
                        .toBuilder()
                        .addParameters(
                            ParameterBinding.newBuilder().setName("unused").setType("text"),
                        ).build()
                val req =
                    request(ctx)
                        .toBuilder()
                        .setSourceSchema(schema)
                        .setSource(if (schema == SchemaCode.ER) sql.replace("customer", "shopper") else sql)
                        .build()
                h.service
                    .compile(req)
                    .requiredParametersList
                    .map { it.name } shouldBe listOf("id")
                val hits = h.cache.stats().hits
                val second = h.service.compile(req)
                h.cache.stats().hits shouldBe hits + 1
                second.requiredParametersList.map { it.name } shouldBe listOf("id")
            }
        }

        "Compile of a stored plan lists its references on a cache hit too" {
            val h = Harness()
            val rel =
                request(context(null))
                    .toBuilder()
                    .setSource(String(parsed().toByteArray(), Charsets.ISO_8859_1))
                    .setSourceLanguage(Language.REL_NODE)
                    .build()
            h.service
                .compile(rel)
                .requiredParametersList
                .map { it.name } shouldBe listOf("id")
            val hits = h.cache.stats().hits
            val second = h.service.compile(rel)
            h.cache.stats().hits shouldBe hits + 1
            second.requiredParametersList.map { it.name } shouldBe listOf("id")
        }

        "braces on a binding name cannot satisfy the stored plan's bare id before the worker binder" {
            val plan = parsed()
            val h = Harness(plan)
            val value = declared.toBuilder().setValue(Value.newBuilder().setIntValue(7)).build()
            val braced = context(value.toBuilder().setName("{id}").build())
            ParameterCheck.unbound(plan, braced) shouldBe listOf("id")
            val name =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(
                        SchemaCode.DB,
                    ).setNamespace("dbo")
                    .setName("by_id")
                    .build()

            fun saved(ctx: PipelineContext) =
                RunSavedQueryRequest
                    .newBuilder()
                    .setQualifiedName(name)
                    .setContext(ctx)
                    .build()
            refusal(h.service.runSavedQuery(saved(braced)).toList(), braced)
            h.dispatched.shouldBeEmpty()
            h.service
                .runSavedQuery(saved(context(value)))
                .toList()
                .flatMap { it.messagesList }
                .shouldBeEmpty()
            h.dispatched
                .single()
                .context.parametersList
                .single() shouldBe value
        }
    })
