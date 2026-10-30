// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.grpc

import com.google.protobuf.kotlin.toByteString
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.LimitOffsetNode
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.query.v1.GetStatusRequest
import org.tatrman.query.v1.RowWindow
import org.tatrman.query.v1.RunRequest
import org.tatrman.translate.v1.DetectSchemaResponse
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.ParseRequest
import org.tatrman.translate.v1.ParseResponse
import org.tatrman.translate.v1.TranslateRequest
import org.tatrman.translate.v1.TranslateResponse
import org.tatrman.validate.v1.ValidateRequest
import org.tatrman.validate.v1.ValidateResponse
import org.tatrman.worker.v1.ExecutionOptions
import org.tatrman.worker.v1.ResultBatch
import io.grpc.Status
import io.grpc.StatusException
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.tatrman.query.cache.CompiledPlanCache
import org.tatrman.query.client.DispatcherClient
import org.tatrman.query.client.TranslatorClient
import org.tatrman.query.client.TranslatorDetectClient
import org.tatrman.query.client.TranslatorTranslateClient
import org.tatrman.query.client.ValidatorClient
import org.tatrman.query.retry.RetryPolicy
import org.tatrman.query.world.Worlds
import org.tatrman.dispatch.v1.DispatchRequest
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

class QueryServiceImplSpec :
    StringSpec({
        val customers =
            QualifiedName
                .newBuilder()
                .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                .setNamespace("dbo")
                .setName("customers")
                .build()
        val planAfterParse =
            PlanNode
                .newBuilder()
                .setTableScan(TableScanNode.newBuilder().setTable(customers))
                .build()

        // A plan whose TableScan name encodes the source — lets a test tell two
        // distinct compiled plans apart at the dispatcher edge (T3 cache replay).
        fun planFor(source: String): PlanNode =
            PlanNode
                .newBuilder()
                .setTableScan(
                    TableScanNode.newBuilder().setTable(
                        QualifiedName
                            .newBuilder()
                            .setSchemaCode(org.tatrman.plan.v1.SchemaCode.DB)
                            .setNamespace("dbo")
                            .setName(source),
                    ),
                ).build()

        val detectStub: TranslatorDetectClient =
            TranslatorDetectClient {
                DetectSchemaResponse
                    .newBuilder()
                    .setDecision(org.tatrman.translate.v1.SchemaDecision.CONFIRMED)
                    .setEffectiveSchema(org.tatrman.plan.v1.SchemaCode.ER)
                    .build()
            }

        val parseStub: TranslatorClient =
            TranslatorClient { req ->
                ParseResponse
                    .newBuilder()
                    .setPlan(planAfterParse)
                    .setContext(req.context)
                    .build()
            }
        val translateStub: TranslatorTranslateClient =
            TranslatorTranslateClient { req ->
                TranslateResponse
                    .newBuilder()
                    .setOutput("OUT")
                    .setContext(req.context)
                    .build()
            }
        val validatorStub: ValidatorClient =
            ValidatorClient { req ->
                ValidateResponse
                    .newBuilder()
                    .setPlan(req.plan)
                    .setContext(req.context)
                    .build()
            }
        val dispatcherStub: DispatcherClient =
            DispatcherClient { _ ->
                flowOf(
                    ResultBatch
                        .newBuilder()
                        .setIsFirst(true)
                        .setIsLast(false)
                        .setSchemaFingerprint("fp-actual")
                        .setArrowIpc(ByteArray(0).toByteString())
                        .setContext(PipelineContext.getDefaultInstance())
                        .build(),
                    ResultBatch
                        .newBuilder()
                        .setIsLast(true)
                        .setBatchIndex(1)
                        .setArrowIpc(ByteArray(0).toByteString())
                        .build(),
                )
            }

        val nojitterRetry =
            RetryPolicy(
                maxAttempts = 3,
                initialBackoffMillis = 1,
                multiplier = 1.0,
                jitterPercent = 0,
            )

        fun service(
            translator: TranslatorClient = parseStub,
            translatorDetect: TranslatorDetectClient = detectStub,
            translatorTranslate: TranslatorTranslateClient = translateStub,
            validator: ValidatorClient = validatorStub,
            dispatcher: DispatcherClient = dispatcherStub,
            cache: CompiledPlanCache = CompiledPlanCache(100, Duration.ofMinutes(60)),
            retry: RetryPolicy = nojitterRetry,
            worlds: Worlds = Worlds.NONE,
        ): QueryServiceImpl =
            QueryServiceImpl(
                translator,
                translatorDetect,
                translatorTranslate,
                validator,
                dispatcher,
                cache,
                retry,
                worlds = worlds,
            )

        "Run streams Dispatcher batches and annotates the first with cache_miss + compile_duration_ms" {
            runBlocking {
                val resp =
                    service()
                        .run(
                            RunRequest
                                .newBuilder()
                                .setSource("SELECT id FROM customers")
                                .setSourceLanguage(Language.SQL)
                                .setContext(PipelineContext.newBuilder().setUserId("u").setModelVersion("v"))
                                .setExecutionOptions(ExecutionOptions.getDefaultInstance())
                                .build(),
                        ).toList()
                resp.size shouldBe 2
                val codes = resp[0].context.warningsList.map { it.code }
                codes shouldContain "compile_duration_ms"
                codes shouldContain "cache_miss"
            }
        }

        "second identical Run hits the cache" {
            runBlocking {
                val cache = CompiledPlanCache(100, Duration.ofMinutes(60))
                val svc = service(cache = cache)
                val req =
                    RunRequest
                        .newBuilder()
                        .setSource("SELECT id FROM customers")
                        .setSourceLanguage(Language.SQL)
                        .setContext(PipelineContext.newBuilder().setUserId("u").setModelVersion("v"))
                        .build()
                svc.run(req).toList() // populate
                val second = svc.run(req).toList()
                val codes = second[0].context.warningsList.map { it.code }
                codes shouldContain "cache_hit"
                cache.stats().hits shouldBe 1
            }
        }

        // Fork Stage 3.5 T3 — cache REPLAY must serve the right compiled plan for the right
        // args, not merely "dispatcher called once". Inherited trap (the predecessor platform's CLAUDE.md):
        // call-counting mocks miss cache-replay bugs (wrong-entry / shared-mutable-plan / key
        // collision). Two distinct queries → two entries; replaying query 1 must dispatch
        // query 1's plan, captured at Dispatch's edge.
        "cache replay dispatches the right compiled plan for the args (not a sibling entry)" {
            runBlocking {
                val cache = CompiledPlanCache(100, Duration.ofMinutes(60))
                val dispatchedPlans = CopyOnWriteArrayList<PlanNode>()
                val capturingDispatcher =
                    DispatcherClient { req ->
                        dispatchedPlans.add(req.plan)
                        flowOf(
                            ResultBatch
                                .newBuilder()
                                .setIsFirst(true)
                                .setIsLast(true)
                                .setArrowIpc(ByteArray(0).toByteString())
                                .setContext(PipelineContext.getDefaultInstance())
                                .build(),
                        )
                    }
                // DB path: each source parses to a DISTINCT physical plan (table name = source),
                // so a wrong-entry replay would dispatch the other query's table.
                val perSourceParse =
                    TranslatorClient { req ->
                        ParseResponse
                            .newBuilder()
                            .setPlan(planFor(req.source))
                            .setContext(req.context)
                            .build()
                    }
                val dbDetect =
                    TranslatorDetectClient {
                        DetectSchemaResponse
                            .newBuilder()
                            .setDecision(org.tatrman.translate.v1.SchemaDecision.CONFIRMED)
                            .setEffectiveSchema(org.tatrman.plan.v1.SchemaCode.DB)
                            .build()
                    }
                val svc =
                    service(
                        translator = perSourceParse,
                        translatorDetect = dbDetect,
                        dispatcher = capturingDispatcher,
                        cache = cache,
                    )

                fun req(sql: String) =
                    RunRequest
                        .newBuilder()
                        .setSource(sql)
                        .setSourceLanguage(Language.SQL)
                        .setSourceSchema(org.tatrman.plan.v1.SchemaCode.DB)
                        .setContext(PipelineContext.newBuilder().setUserId("u").setModelVersion("v"))
                        .build()

                svc.run(req("SELECT id FROM customers")).toList() // miss → entry A
                svc.run(req("SELECT id FROM orders")).toList() // miss → entry B
                cache.stats().entries shouldBe 2

                dispatchedPlans.clear()
                val replay = svc.run(req("SELECT id FROM customers")).toList()

                replay[0].context.warningsList.map { it.code } shouldContain "cache_hit"
                cache.stats().hits shouldBe 1
                // The replay dispatched query A's compiled plan — NOT query B's.
                dispatchedPlans.size shouldBe 1
                dispatchedPlans[0].tableScan.table.name shouldBe "SELECT id FROM customers"
            }
        }

        // review-123 R3 — the detector decides the layer WITH the stated one, so the stated layer is part of what was
        // compiled. Keyed without it, a text stated DB and then ER was served the DB caller's plan.
        "the stated source_schema is part of the cache key: one text stated DB, then ER, compiles twice" {
            runBlocking {
                val cache = CompiledPlanCache(100, Duration.ofMinutes(60))
                val stated = CopyOnWriteArrayList<org.tatrman.plan.v1.SchemaCode>()
                val followsStated =
                    TranslatorDetectClient { req ->
                        stated.add(req.statedSchema)
                        DetectSchemaResponse
                            .newBuilder()
                            .setDecision(org.tatrman.translate.v1.SchemaDecision.CONFIRMED)
                            .setEffectiveSchema(req.statedSchema)
                            .build()
                    }
                val svc = service(translatorDetect = followsStated, cache = cache)

                fun req(schema: org.tatrman.plan.v1.SchemaCode) =
                    RunRequest
                        .newBuilder()
                        .setSource("SELECT id FROM customers")
                        .setSourceLanguage(Language.SQL)
                        .setSourceSchema(schema)
                        .setContext(PipelineContext.newBuilder().setUserId("u").setModelVersion("v"))
                        .build()

                svc.run(req(org.tatrman.plan.v1.SchemaCode.DB)).toList()
                val asEr = svc.run(req(org.tatrman.plan.v1.SchemaCode.ER)).toList()
                asEr[0].context.warningsList.map { it.code } shouldContain "cache_miss"
                stated shouldBe listOf(org.tatrman.plan.v1.SchemaCode.DB, org.tatrman.plan.v1.SchemaCode.ER)
                cache.stats().entries shouldBe 2
                val again = svc.run(req(org.tatrman.plan.v1.SchemaCode.DB)).toList()
                again[0].context.warningsList.map { it.code } shouldContain "cache_hit"
            }
        }

        "bypass_cache forces a fresh translator call even with a populated cache" {
            runBlocking {
                val cache = CompiledPlanCache(100, Duration.ofMinutes(60))
                var translatorCalls = 0
                val countingTranslator =
                    TranslatorClient { req ->
                        translatorCalls++
                        ParseResponse
                            .newBuilder()
                            .setPlan(planAfterParse)
                            .setContext(req.context)
                            .build()
                    }
                val svc = service(translator = countingTranslator, cache = cache)
                val req =
                    RunRequest
                        .newBuilder()
                        .setSource("SELECT id FROM customers")
                        .setSourceLanguage(Language.SQL)
                        .setContext(PipelineContext.newBuilder().setUserId("u").setModelVersion("v"))
                        .build()
                svc.run(req).toList()
                svc.run(req.toBuilder().setBypassCache(true).build()).toList()
                translatorCalls shouldBe 4
            }
        }

        "Run retries Translator UNAVAILABLE before first batch" {
            runBlocking {
                var calls = 0
                val flapping =
                    TranslatorClient { req ->
                        calls++
                        if (calls < 2) throw StatusException(Status.UNAVAILABLE)
                        ParseResponse
                            .newBuilder()
                            .setPlan(planAfterParse)
                            .setContext(req.context)
                            .build()
                    }
                service(translator = flapping)
                    .run(
                        RunRequest
                            .newBuilder()
                            .setSource("SELECT 1")
                            .setSourceLanguage(Language.SQL)
                            .setContext(PipelineContext.newBuilder().setModelVersion("v"))
                            .build(),
                    ).toList()
                calls shouldBe 3
            }
        }

        "Run surfaces translator_unavailable after retries exhausted" {
            runBlocking {
                val alwaysDown =
                    TranslatorClient { _ ->
                        throw StatusException(Status.UNAVAILABLE.withDescription("down"))
                    }
                val out =
                    service(translator = alwaysDown)
                        .run(
                            RunRequest
                                .newBuilder()
                                .setSource("SELECT 1")
                                .setSourceLanguage(Language.SQL)
                                .setContext(PipelineContext.newBuilder().setModelVersion("v"))
                                .build(),
                        ).toList()
                out.size shouldBe 1
                out[0].messagesList[0].code shouldBe "translator_unavailable"
            }
        }

        "Translator's structured ERROR message surfaces as translator_rejected" {
            runBlocking {
                val rejecting =
                    TranslatorClient { req ->
                        ParseResponse
                            .newBuilder()
                            .setContext(req.context)
                            .addMessages(
                                ResponseMessage
                                    .newBuilder()
                                    .setSeverity(Severity.ERROR)
                                    .setCode("validation_failed")
                                    .setHumanMessage("bad SQL"),
                            ).build()
                    }
                val out =
                    service(translator = rejecting)
                        .run(
                            RunRequest
                                .newBuilder()
                                .setSource("nonsense")
                                .setSourceLanguage(Language.SQL)
                                .setContext(PipelineContext.newBuilder().setModelVersion("v"))
                                .build(),
                        ).toList()
                out[0].messagesList[0].code shouldBe "translator_rejected"
            }
        }

        "Compile returns predicted_schema_fingerprint" {
            runBlocking {
                val resp =
                    service().compile(
                        RunRequest
                            .newBuilder()
                            .setSource("SELECT id FROM customers")
                            .setSourceLanguage(Language.SQL)
                            .setContext(PipelineContext.newBuilder().setModelVersion("v"))
                            .build(),
                    )
                resp.predictedSchemaFingerprint.length shouldBe 64
            }
        }

        "Compile surfaces translator error via messages" {
            runBlocking {
                val rejecting =
                    TranslatorClient { req ->
                        ParseResponse
                            .newBuilder()
                            .setContext(req.context)
                            .addMessages(
                                ResponseMessage
                                    .newBuilder()
                                    .setSeverity(Severity.ERROR)
                                    .setCode("validation_failed")
                                    .setHumanMessage("bad"),
                            ).build()
                    }
                val resp =
                    service(translator = rejecting).compile(
                        RunRequest
                            .newBuilder()
                            .setSource("nonsense")
                            .setSourceLanguage(Language.SQL)
                            .setContext(PipelineContext.newBuilder().setModelVersion("v"))
                            .build(),
                    )
                resp.messagesList[0].code shouldBe "validation_failed"
            }
        }

        "Translate is a passthrough to Translator.Translate" {
            runBlocking {
                val resp =
                    service().translate(
                        TranslateRequest
                            .newBuilder()
                            .setSource("SELECT 1")
                            .setSourceLanguage(Language.SQL)
                            .setTargetLanguage(Language.SQL)
                            .build(),
                    )
                resp.output shouldBe "OUT"
            }
        }

        "GetStatus reports cache stats" {
            runBlocking {
                val resp = service().getStatus(GetStatusRequest.getDefaultInstance())
                resp.ready shouldBe true
                resp.compiledCache.maxEntries shouldBe 100L
            }
        }

        "Compile: AUTODETECTED-DB calls parse once with sourceSchema=DB, targetSchema=DB, then validates once" {
            runBlocking {
                var parseCallCount = 0
                val dbDetectStub =
                    TranslatorDetectClient {
                        DetectSchemaResponse
                            .newBuilder()
                            .setDecision(org.tatrman.translate.v1.SchemaDecision.AUTODETECTED)
                            .setEffectiveSchema(org.tatrman.plan.v1.SchemaCode.DB)
                            .addMessages(
                                ResponseMessage
                                    .newBuilder()
                                    .setSeverity(Severity.INFO)
                                    .setCode("schema_autodetected")
                                    .setHumanMessage("Schema auto-detected as DB"),
                            ).build()
                    }
                val dbParseStub =
                    TranslatorClient { req ->
                        parseCallCount++
                        ParseResponse
                            .newBuilder()
                            .setPlan(planAfterParse)
                            .setContext(req.context)
                            .build()
                    }
                val svc =
                    service(
                        translator = dbParseStub,
                        translatorDetect = dbDetectStub,
                    )
                val resp =
                    svc.compile(
                        RunRequest
                            .newBuilder()
                            .setSource("SELECT id FROM item")
                            .setSourceLanguage(Language.SQL)
                            .setContext(PipelineContext.newBuilder().setModelVersion("v"))
                            .build(),
                    )
                parseCallCount shouldBe 1
                resp.messagesList.isNotEmpty() shouldBe true
                resp.messagesList[0].code shouldBe "schema_autodetected"
            }
        }

        "Compile: AUTODETECTED-ER falls back to two-pass ER path" {
            runBlocking {
                var parseCallCount = 0
                val erDetectStub =
                    TranslatorDetectClient {
                        DetectSchemaResponse
                            .newBuilder()
                            .setDecision(org.tatrman.translate.v1.SchemaDecision.AUTODETECTED)
                            .setEffectiveSchema(org.tatrman.plan.v1.SchemaCode.ER)
                            .build()
                    }
                val twoPassParseStub =
                    TranslatorClient { req ->
                        parseCallCount++
                        ParseResponse
                            .newBuilder()
                            .setPlan(planAfterParse)
                            .setContext(req.context)
                            .build()
                    }
                val svc =
                    service(
                        translator = twoPassParseStub,
                        translatorDetect = erDetectStub,
                    )
                val resp =
                    svc.compile(
                        RunRequest
                            .newBuilder()
                            .setSource("SELECT id FROM customers")
                            .setSourceLanguage(Language.SQL)
                            .setContext(PipelineContext.newBuilder().setModelVersion("v"))
                            .build(),
                    )
                parseCallCount shouldBe 2
            }
        }

        "Compile: CORRECTED carries WARNING message" {
            runBlocking {
                val correctedDetectStub =
                    TranslatorDetectClient {
                        DetectSchemaResponse
                            .newBuilder()
                            .setDecision(org.tatrman.translate.v1.SchemaDecision.CORRECTED)
                            .setEffectiveSchema(org.tatrman.plan.v1.SchemaCode.DB)
                            .addMessages(
                                ResponseMessage
                                    .newBuilder()
                                    .setSeverity(Severity.WARNING)
                                    .setCode("schema_corrected")
                                    .setHumanMessage("Stated schema ER did not match detected DB"),
                            ).build()
                    }
                val svc = service(translatorDetect = correctedDetectStub)
                val resp =
                    svc.compile(
                        RunRequest
                            .newBuilder()
                            .setSource("SELECT id FROM item")
                            .setSourceLanguage(Language.SQL)
                            .setContext(PipelineContext.newBuilder().setModelVersion("v"))
                            .build(),
                    )
                resp.messagesList.any { it.code == "schema_corrected" } shouldBe true
            }
        }

        "Compile: AMBIGUOUS returns messages only, no parse/validate calls" {
            runBlocking {
                var parseCallCount = 0
                val ambiguousDetectStub =
                    TranslatorDetectClient {
                        DetectSchemaResponse
                            .newBuilder()
                            .setDecision(org.tatrman.translate.v1.SchemaDecision.AMBIGUOUS)
                            .setEffectiveSchema(org.tatrman.plan.v1.SchemaCode.SCHEMA_CODE_UNSPECIFIED)
                            .addMessages(
                                ResponseMessage
                                    .newBuilder()
                                    .setSeverity(Severity.ERROR)
                                    .setCode("schema_ambiguous")
                                    .setHumanMessage("Query spans multiple schemas"),
                            ).build()
                    }
                val countingParseStub =
                    TranslatorClient { req ->
                        parseCallCount++
                        ParseResponse
                            .newBuilder()
                            .setPlan(planAfterParse)
                            .setContext(req.context)
                            .build()
                    }
                val svc =
                    service(
                        translator = countingParseStub,
                        translatorDetect = ambiguousDetectStub,
                    )
                val resp =
                    svc.compile(
                        RunRequest
                            .newBuilder()
                            .setSource("SELECT id FROM customers JOIN orders ON ...")
                            .setSourceLanguage(Language.SQL)
                            .setContext(PipelineContext.newBuilder().setModelVersion("v"))
                            .build(),
                    )
                parseCallCount shouldBe 0
                resp.messagesList[0].code shouldBe "schema_ambiguous"
            }
        }

        "Compile: UNKNOWN returns messages only with suggestions, no parse/validate calls" {
            runBlocking {
                var parseCallCount = 0
                val unknownDetectStub =
                    TranslatorDetectClient {
                        DetectSchemaResponse
                            .newBuilder()
                            .setDecision(org.tatrman.translate.v1.SchemaDecision.UNKNOWN)
                            .setEffectiveSchema(org.tatrman.plan.v1.SchemaCode.SCHEMA_CODE_UNSPECIFIED)
                            .addMessages(
                                ResponseMessage
                                    .newBuilder()
                                    .setSeverity(Severity.ERROR)
                                    .setCode("schema_object_unknown")
                                    .setHumanMessage("Table ITEMX not found"),
                            ).build()
                    }
                val countingParseStub =
                    TranslatorClient { req ->
                        parseCallCount++
                        ParseResponse
                            .newBuilder()
                            .setPlan(planAfterParse)
                            .setContext(req.context)
                            .build()
                    }
                val svc =
                    service(
                        translator = countingParseStub,
                        translatorDetect = unknownDetectStub,
                    )
                val resp =
                    svc.compile(
                        RunRequest
                            .newBuilder()
                            .setSource("SELECT id FROM itemx")
                            .setSourceLanguage(Language.SQL)
                            .setContext(PipelineContext.newBuilder().setModelVersion("v"))
                            .build(),
                    )
                parseCallCount shouldBe 0
                resp.messagesList[0].code shouldBe "schema_object_unknown"
            }
        }

        "Compile: MIXED returns messages only, no parse/validate calls" {
            runBlocking {
                var parseCallCount = 0
                val mixedDetectStub =
                    TranslatorDetectClient {
                        DetectSchemaResponse
                            .newBuilder()
                            .setDecision(org.tatrman.translate.v1.SchemaDecision.MIXED)
                            .setEffectiveSchema(org.tatrman.plan.v1.SchemaCode.SCHEMA_CODE_UNSPECIFIED)
                            .addMessages(
                                ResponseMessage
                                    .newBuilder()
                                    .setSeverity(Severity.ERROR)
                                    .setCode("schema_mixed")
                                    .setHumanMessage("Identifiers span multiple schemas"),
                            ).build()
                    }
                val countingParseStub =
                    TranslatorClient { req ->
                        parseCallCount++
                        ParseResponse
                            .newBuilder()
                            .setPlan(planAfterParse)
                            .setContext(req.context)
                            .build()
                    }
                val svc =
                    service(
                        translator = countingParseStub,
                        translatorDetect = mixedDetectStub,
                    )
                val resp =
                    svc.compile(
                        RunRequest
                            .newBuilder()
                            .setSource("SELECT id FROM db.table JOIN er.entity ON ...")
                            .setSourceLanguage(Language.SQL)
                            .setContext(PipelineContext.newBuilder().setModelVersion("v"))
                            .build(),
                    )
                parseCallCount shouldBe 0
                resp.messagesList[0].code shouldBe "schema_mixed"
            }
        }

        "Compile: NOT_APPLICABLE uses legacy two-pass path" {
            runBlocking {
                var parseCallCount = 0
                val naDetectStub =
                    TranslatorDetectClient {
                        DetectSchemaResponse
                            .newBuilder()
                            .setDecision(org.tatrman.translate.v1.SchemaDecision.NOT_APPLICABLE)
                            .setEffectiveSchema(org.tatrman.plan.v1.SchemaCode.SCHEMA_CODE_UNSPECIFIED)
                            .build()
                    }
                val countingParseStub =
                    TranslatorClient { req ->
                        parseCallCount++
                        ParseResponse
                            .newBuilder()
                            .setPlan(planAfterParse)
                            .setContext(req.context)
                            .build()
                    }
                val svc =
                    service(
                        translator = countingParseStub,
                        translatorDetect = naDetectStub,
                    )
                val resp =
                    svc.compile(
                        RunRequest
                            .newBuilder()
                            .setSource("SELECT id FROM customers")
                            .setSourceLanguage(Language.SQL)
                            .setContext(PipelineContext.newBuilder().setModelVersion("v"))
                            .build(),
                    )
                parseCallCount shouldBe 2
            }
        }

        "second identical Compile on a DB query replays the cached physical plan" {
            runBlocking {
                val cache = CompiledPlanCache(100, Duration.ofMinutes(60))
                var parseCallCount = 0
                val dbDetectStub =
                    TranslatorDetectClient {
                        DetectSchemaResponse
                            .newBuilder()
                            .setDecision(org.tatrman.translate.v1.SchemaDecision.AUTODETECTED)
                            .setEffectiveSchema(org.tatrman.plan.v1.SchemaCode.DB)
                            .addMessages(
                                ResponseMessage
                                    .newBuilder()
                                    .setSeverity(Severity.INFO)
                                    .setCode("schema_autodetected")
                                    .setHumanMessage("Schema auto-detected as DB"),
                            ).build()
                    }
                val dbParseStub =
                    TranslatorClient { req ->
                        parseCallCount++
                        ParseResponse
                            .newBuilder()
                            .setPlan(planAfterParse)
                            .setContext(req.context)
                            .build()
                    }
                val svc =
                    service(
                        translator = dbParseStub,
                        translatorDetect = dbDetectStub,
                        cache = cache,
                    )
                val req =
                    RunRequest
                        .newBuilder()
                        .setSource("SELECT id FROM item")
                        .setSourceLanguage(Language.SQL)
                        .setContext(PipelineContext.newBuilder().setModelVersion("v"))
                        .build()
                val first = svc.compile(req)
                val second = svc.compile(req)
                parseCallCount shouldBe 1
                first.plan shouldBe second.plan
            }
        }

        "second identical Run on a DB query replays the cached physical plan" {
            runBlocking {
                val cache = CompiledPlanCache(100, Duration.ofMinutes(60))
                var parseCallCount = 0
                val dbDetectStub =
                    TranslatorDetectClient {
                        DetectSchemaResponse
                            .newBuilder()
                            .setDecision(org.tatrman.translate.v1.SchemaDecision.AUTODETECTED)
                            .setEffectiveSchema(org.tatrman.plan.v1.SchemaCode.DB)
                            .build()
                    }
                val dbParseStub =
                    TranslatorClient { req ->
                        parseCallCount++
                        ParseResponse
                            .newBuilder()
                            .setPlan(planAfterParse)
                            .setContext(req.context)
                            .build()
                    }
                val svc =
                    service(
                        translator = dbParseStub,
                        translatorDetect = dbDetectStub,
                        cache = cache,
                    )
                val req =
                    RunRequest
                        .newBuilder()
                        .setSource("SELECT id FROM item")
                        .setSourceLanguage(Language.SQL)
                        .setContext(PipelineContext.newBuilder().setUserId("u").setModelVersion("v"))
                        .build()
                val first = svc.run(req).toList()
                val second = svc.run(req).toList()
                parseCallCount shouldBe 1
                first.size shouldBe 2
                first
                    .first()
                    .context.warningsList
                    .any { it.code == "cache_miss" } shouldBe true
                second.size shouldBe 2
                second
                    .first()
                    .context.warningsList
                    .any { it.code == "cache_hit" } shouldBe true
            }
        }

        "Compile (ER path) forwards a parse validation_failed at the ER parse" {
            runBlocking {
                var parseCallCount = 0
                val rejecting =
                    TranslatorClient { req ->
                        parseCallCount++
                        ParseResponse
                            .newBuilder()
                            .setContext(req.context)
                            .addMessages(
                                ResponseMessage
                                    .newBuilder()
                                    .setSeverity(Severity.ERROR)
                                    .setCode("validation_failed")
                                    .setHumanMessage(
                                        "Object 'customers' not found — 'customers' is a db object; set source_schema=DB",
                                    ),
                            ).build()
                    }
                // Default detect stub resolves CONFIRMED/ER → ER path. The ER-parse ERROR must be
                // forwarded straight away (parse called once), not swallowed and re-derived via the
                // REL_NODE→DB re-parse.
                val resp =
                    service(translator = rejecting).compile(
                        RunRequest
                            .newBuilder()
                            .setSource("SELECT id FROM customer")
                            .setSourceLanguage(Language.SQL)
                            .setContext(PipelineContext.newBuilder().setModelVersion("v"))
                            .build(),
                    )
                resp.messagesList[0].code shouldBe "validation_failed"
                resp.messagesList[0].humanMessage.contains("is a db object") shouldBe true
                parseCallCount shouldBe 1
            }
        }

        // ── the row window ──────────────────────────────────────────────────────────────────────
        // RunRequest.row_window goes on the plan as its root LimitOffset (the node the validator's
        // TopN rule reads) and into ValidationOptions.default_top_n (what the caller asked for).

        val dbDetect =
            TranslatorDetectClient {
                DetectSchemaResponse
                    .newBuilder()
                    .setDecision(org.tatrman.translate.v1.SchemaDecision.CONFIRMED)
                    .setEffectiveSchema(org.tatrman.plan.v1.SchemaCode.DB)
                    .build()
            }

        fun capturing(seen: MutableList<ValidateRequest>): ValidatorClient =
            ValidatorClient { req ->
                seen.add(req)
                ValidateResponse
                    .newBuilder()
                    .setPlan(req.plan)
                    .setContext(req.context)
                    .build()
            }

        fun windowRequest(
            limit: Long,
            offset: Long,
        ): RunRequest =
            RunRequest
                .newBuilder()
                .setSource("SELECT id FROM customers ORDER BY id")
                .setSourceLanguage(Language.SQL)
                .setContext(PipelineContext.newBuilder().setUserId("u").setModelVersion("v"))
                .setRowWindow(RowWindow.newBuilder().setLimit(limit).setOffset(offset))
                .build()

        "a row window reaches the validator as default_top_n and the plan as its root LimitOffset" {
            runBlocking {
                val seen = CopyOnWriteArrayList<ValidateRequest>()
                service(translatorDetect = dbDetect, validator = capturing(seen)).run(windowRequest(200, 400)).toList()
                seen.size shouldBe 1
                val root = seen[0].plan
                root.hasLimitOffset() shouldBe true
                root.limitOffset.limit shouldBe 200L
                root.limitOffset.offset shouldBe 400L
                root.limitOffset.input shouldBe planAfterParse
                seen[0].options.defaultTopN shouldBe 200
                seen[0].options.enforceTopN shouldBe true
                seen[0].options.applySecurity shouldBe true
            }
        }

        "no row window: the plan reaches the validator unwrapped and default_top_n stays 0" {
            runBlocking {
                val seen = CopyOnWriteArrayList<ValidateRequest>()
                service(translatorDetect = dbDetect, validator = capturing(seen))
                    .run(
                        RunRequest
                            .newBuilder()
                            .setSource("SELECT id FROM customers")
                            .setSourceLanguage(Language.SQL)
                            .setContext(PipelineContext.newBuilder().setUserId("u").setModelVersion("v"))
                            .build(),
                    ).toList()
                seen.single().plan shouldBe planAfterParse
                seen.single().options.defaultTopN shouldBe 0
            }
        }

        "the two-pass ER path applies the window ONCE: pass 1 is wrapped, the DB pass is not" {
            runBlocking {
                val seen = CopyOnWriteArrayList<ValidateRequest>()
                // Default detect stub → ER. The REL_NODE→DB re-parse returns `planAfterParse`
                // whatever it is given, so a second wrap would show up on the pass-2 plan.
                service(validator = capturing(seen)).run(windowRequest(200, 400)).toList()
                seen.size shouldBe 2
                seen[0].plan.limitOffset.offset shouldBe 400L
                seen[0].plan.limitOffset.input shouldBe planAfterParse
                seen[1].plan shouldBe planAfterParse
                seen[1].options.defaultTopN shouldBe 200
            }
        }

        "every page of one query shares its compiled plan: the window is not part of the cache key" {
            runBlocking {
                val cache = CompiledPlanCache(100, Duration.ofMinutes(60))
                val seen = CopyOnWriteArrayList<ValidateRequest>()
                val svc = service(translatorDetect = dbDetect, validator = capturing(seen), cache = cache)
                svc.run(windowRequest(200, 0)).toList()
                val second = svc.run(windowRequest(200, 200)).toList()
                second[0].context.warningsList.map { it.code } shouldContain "cache_hit"
                seen[0].plan.limitOffset.hasOffset() shouldBe false
                seen[1].plan.limitOffset.offset shouldBe 200L
            }
        }

        "a negative row window is refused in-band, before any stage runs" {
            runBlocking {
                val seen = CopyOnWriteArrayList<ValidateRequest>()
                val out = service(validator = capturing(seen)).run(windowRequest(-1, 0)).toList()
                out.size shouldBe 1
                out[0].messagesList.single().code shouldBe "invalid_row_window"
                out[0].messagesList.single().severity shouldBe Severity.ERROR
                seen.size shouldBe 0
            }
        }

        // The node carries int64; ttr-translator's `PlanNodeDecoder.pushLimitOffset` narrows with
        // `.toInt()`, so 2^31 would arrive at Calcite as a NEGATIVE offset — a wrong answer from the
        // one input this refusal exists to name (review-093 ⑹).
        "a row window above Int.MAX_VALUE is refused, not narrowed into a negative offset" {
            runBlocking {
                val seen = CopyOnWriteArrayList<ValidateRequest>()
                val svc = service(validator = capturing(seen))
                val tooBig = Int.MAX_VALUE.toLong() + 1
                val byOffset = svc.run(windowRequest(200, tooBig)).toList()
                byOffset.size shouldBe 1
                byOffset[0].messagesList.single().code shouldBe "invalid_row_window"
                val byLimit = svc.run(windowRequest(tooBig, 0)).toList()
                byLimit.size shouldBe 1
                byLimit[0].messagesList.single().code shouldBe "invalid_row_window"
                seen.size shouldBe 0
            }
        }

        // ── top_n_applied: the validator's statement about the PLAN, passed on only when the
        //    answer actually reached the cap ──

        val capNotice =
            ResponseMessage
                .newBuilder()
                .setSeverity(Severity.WARNING)
                .setCode("top_n_applied")
                .setHumanMessage("Answer limited to 2 rows by the row cap; the caller asked for 5.")
                .build()

        // A validator that caps every plan at 2 rows; `notice` decides whether it says so.
        fun cappingAt2(notice: Boolean): ValidatorClient =
            ValidatorClient { req ->
                val resp =
                    ValidateResponse
                        .newBuilder()
                        .setPlan(
                            PlanNode
                                .newBuilder()
                                .setLimitOffset(LimitOffsetNode.newBuilder().setInput(req.plan).setLimit(2)),
                        ).setContext(req.context)
                if (notice) resp.addMessages(capNotice)
                resp.build()
            }

        // Data batches carrying `rows[i]` rows each, then the worker's empty `is_last` tail.
        fun streaming(vararg rows: Long): DispatcherClient =
            DispatcherClient { _ ->
                val batches =
                    rows.mapIndexed { i, n ->
                        ResultBatch
                            .newBuilder()
                            .setIsFirst(i == 0)
                            .setBatchIndex(i)
                            .setBatchRowCount(n)
                            .setArrowIpc(ByteArray(0).toByteString())
                            .setContext(PipelineContext.getDefaultInstance())
                            .build()
                    } +
                        ResultBatch
                            .newBuilder()
                            .setIsLast(true)
                            .setBatchIndex(rows.size)
                            .setArrowIpc(ByteArray(0).toByteString())
                            .build()
                flowOf(*batches.toTypedArray())
            }

        // ⛔ `compile` returns a plan and NO rows, so "Answer limited to N rows" is a claim it cannot
        // make — and the enforcer raises the notice for every plan it bounds, so every compile made it.
        // The fact worth keeping is about the future run, and it is worded that way (review-093 ⑸).
        "compile states the cap as a fact about the RUN — it never claims an answer was cut" {
            runBlocking {
                val resp =
                    service(translatorDetect = dbDetect, validator = cappingAt2(notice = true))
                        .compile(windowRequest(5, 0))
                val notice = resp.messagesList.single { it.code == "top_n_applied" }
                notice.humanMessage shouldBe
                    "A row cap of 2 rows will apply when this plan runs; `compile` itself returns no rows."
            }
        }

        "an answer that REACHED the row cap carries top_n_applied on its last batch" {
            runBlocking {
                val out =
                    service(
                        translatorDetect = dbDetect,
                        validator = cappingAt2(notice = true),
                        dispatcher = streaming(1, 1),
                    ).run(windowRequest(5, 0))
                        .toList()
                val warning = out.last().messagesList.single { it.code == "top_n_applied" }
                warning.severity shouldBe Severity.WARNING
                warning.humanMessage shouldBe capNotice.humanMessage
                out.dropLast(1).flatMap { it.messagesList } shouldBe emptyList()
            }
        }

        "BI (C-10) — the capped last batch carries the cap as a typed notice too: requested, applied" {
            runBlocking {
                val out =
                    service(
                        translatorDetect = dbDetect,
                        validator = cappingAt2(notice = true),
                        dispatcher = streaming(1, 1),
                    ).run(windowRequest(5, 0))
                        .toList()
                val notice = out.last().noticesList.single()
                notice.code shouldBe "top_n_applied"
                notice.requested shouldBe 5L
                notice.applied shouldBe 2L
                out.dropLast(1).flatMap { it.noticesList } shouldBe emptyList()
                // Unstated: requested is 0.
                service(
                    translatorDetect = dbDetect,
                    validator = cappingAt2(notice = true),
                    dispatcher = streaming(2),
                ).run(windowRequest(0, 0))
                    .toList()
                    .last()
                    .noticesList
                    .single()
                    .requested shouldBe 0L
                // The Flight edge's ask, the largest window, is no number the caller chose: 0 as well.
                val edgeAsk =
                    service(
                        translatorDetect = dbDetect,
                        validator = cappingAt2(notice = true),
                        dispatcher = streaming(2),
                    ).run(windowRequest(Int.MAX_VALUE.toLong(), 0))
                        .toList()
                        .last()
                        .noticesList
                        .single()
                edgeAsk.requested shouldBe 0L
                edgeAsk.applied shouldBe 2L
            }
        }

        // ── BI (C-11): a parameter the plan reads must have a value — Run refuses, Compile reports ──

        val byCityPlan =
            PlanNode
                .newBuilder()
                .setFilter(
                    org.tatrman.plan.v1.FilterNode
                        .newBuilder()
                        .setInput(planFor("warehouse"))
                        .setCondition(
                            org.tatrman.plan.v1.Expression
                                .newBuilder()
                                .setParameter(
                                    org.tatrman.plan.v1.ParameterRef
                                        .newBuilder()
                                        .setName("city"),
                                ),
                        ),
                ).build()
        val byCityParse =
            TranslatorClient { req ->
                ParseResponse
                    .newBuilder()
                    .setPlan(byCityPlan)
                    .setContext(req.context)
                    .build()
            }

        fun cityRequest(value: org.tatrman.plan.v1.Value?): RunRequest =
            RunRequest
                .newBuilder()
                .setSource("SELECT name FROM warehouse WHERE city = {city}")
                .setSourceLanguage(Language.SQL)
                .setContext(
                    PipelineContext
                        .newBuilder()
                        .setModelVersion("v")
                        .addParameters(
                            org.tatrman.plan.v1.ParameterBinding
                                .newBuilder()
                                .setName("city")
                                .setType("text")
                                .apply { if (value != null) setValue(value) },
                        ),
                ).build()

        "Run refuses a declared parameter with no value as parameter_unbound — nothing is dispatched" {
            runBlocking {
                var dispatched = 0
                val out =
                    service(
                        translator = byCityParse,
                        translatorDetect = dbDetect,
                        dispatcher =
                            DispatcherClient { req ->
                                dispatched++
                                dispatcherStub.dispatch(req)
                            },
                    ).run(cityRequest(null)).toList()
                val refusal = out.single().messagesList.single()
                refusal.code shouldBe "parameter_unbound"
                refusal.humanMessage shouldBe "Parameter 'city' has no value; bind it, or send an explicit NULL."
                dispatched shouldBe 0
            }
        }

        "a bound value, or an explicit NULL, runs; Compile still answers an unbound one with a plan" {
            runBlocking {
                val svc = service(translator = byCityParse, translatorDetect = dbDetect)
                val brno =
                    org.tatrman.plan.v1.Value
                        .newBuilder()
                        .setStringValue("Brno")
                        .build()
                svc
                    .run(
                        cityRequest(brno),
                    ).toList()
                    .flatMap { it.messagesList }
                    .none { it.severity == Severity.ERROR } shouldBe
                    true
                val explicitNull =
                    org.tatrman.plan.v1.Value
                        .newBuilder()
                        .setIsNull(true)
                        .build()
                svc.run(cityRequest(explicitNull)).toList().flatMap { it.messagesList }.none {
                    it.severity == Severity.ERROR
                } shouldBe true
                svc.compile(cityRequest(null)).hasPlan() shouldBe true
            }
        }

        "BI (C-11) — a translator refusal keeps the position translate gave it" {
            runBlocking {
                val rejecting =
                    TranslatorClient { req ->
                        ParseResponse
                            .newBuilder()
                            .setContext(req.context)
                            .addMessages(
                                ResponseMessage
                                    .newBuilder()
                                    .setSeverity(Severity.ERROR)
                                    .setCode("validation_failed")
                                    .setHumanMessage("Column 'nme' not found in any table")
                                    .setLine(1)
                                    .setColumn(8),
                            ).build()
                    }
                val refusal =
                    service(translator = rejecting, translatorDetect = dbDetect)
                        .run(cityRequest(null))
                        .toList()
                        .single()
                        .messagesList
                        .single()
                refusal.code shouldBe "translator_rejected"
                refusal.humanMessage shouldBe "Column 'nme' not found in any table"
                refusal.line shouldBe 1
                refusal.column shouldBe 8
            }
        }

        "an answer under the row cap carries nothing: 1 row under a 2-row cap is the whole answer" {
            runBlocking {
                val out =
                    service(
                        translatorDetect = dbDetect,
                        validator = cappingAt2(notice = true),
                        dispatcher = streaming(1),
                    ).run(windowRequest(5, 0))
                        .toList()
                out.flatMap { it.messagesList }.none { it.code == "top_n_applied" } shouldBe true
            }
        }

        "a full page the caller asked for, granted, carries nothing: the caller pages on" {
            runBlocking {
                val out =
                    service(
                        translatorDetect = dbDetect,
                        validator = cappingAt2(notice = false),
                        dispatcher = streaming(2),
                    ).run(windowRequest(2, 0))
                        .toList()
                out.flatMap { it.messagesList } shouldBe emptyList()
            }
        }

        // ── review-159 — a validate REFUSAL ends the request with its reason, on every path ──────
        // The refusal deliberately lists a warning BEFORE its ERRORs (an older validator did): the
        // caller must still read `access_denied` as the first message.
        val accessDenied =
            ValidateResponse
                .newBuilder()
                .addMessages(
                    ResponseMessage
                        .newBuilder()
                        .setSeverity(
                            Severity.WARNING,
                        ).setCode("unknown_table")
                        .setHumanMessage("x"),
                ).addMessages(
                    ResponseMessage
                        .newBuilder()
                        .setSeverity(
                            Severity.ERROR,
                        ).setCode("access_denied")
                        .setHumanMessage("denied"),
                ).addMessages(
                    ResponseMessage
                        .newBuilder()
                        .setSeverity(Severity.ERROR)
                        .setCode("policy_unresolvable_attribute")
                        .setHumanMessage("needs region"),
                ).build()

        // Refuses on validate call [n] (1 = the first pass); passes every other call through.
        fun refusingOnCall(
            n: Int,
            calls: MutableList<ValidateRequest>,
        ): ValidatorClient =
            ValidatorClient { req ->
                calls += req
                if (calls.size == n) {
                    accessDenied
                } else {
                    ValidateResponse
                        .newBuilder()
                        .setPlan(req.plan)
                        .setContext(req.context)
                        .build()
                }
            }

        fun recordingDispatcher(dispatched: MutableList<PlanNode>): DispatcherClient =
            DispatcherClient { req ->
                dispatched += req.plan
                dispatcherStub.dispatch(req)
            }

        fun sqlRequest(): RunRequest =
            RunRequest
                .newBuilder()
                .setSource("SELECT id FROM customers")
                .setSourceLanguage(Language.SQL)
                .setContext(PipelineContext.newBuilder().setUserId("u").setModelVersion("v"))
                .build()

        "run, DB path: a validate refusal is the answer — access_denied first, nothing dispatched" {
            runBlocking {
                val dispatched = CopyOnWriteArrayList<PlanNode>()
                val out =
                    service(
                        translatorDetect = dbDetect,
                        validator = refusingOnCall(1, CopyOnWriteArrayList()),
                        dispatcher = recordingDispatcher(dispatched),
                    ).run(sqlRequest()).toList()
                out.size shouldBe 1
                out[0].messagesList.map { it.code } shouldBe
                    listOf("access_denied", "policy_unresolvable_attribute", "unknown_table")
                dispatched shouldBe emptyList()
            }
        }

        "run, ER path: pass 1's refusal is the answer — no DB translation, no pass 2, nothing dispatched" {
            runBlocking {
                val calls = CopyOnWriteArrayList<ValidateRequest>()
                val parsed = CopyOnWriteArrayList<Language>()
                val dispatched = CopyOnWriteArrayList<PlanNode>()
                val out =
                    service(
                        translator =
                            TranslatorClient { req ->
                                parsed += req.sourceLanguage
                                parseStub.parse(req)
                            },
                        validator = refusingOnCall(1, calls),
                        dispatcher = recordingDispatcher(dispatched),
                    ).run(sqlRequest()).toList()
                out.size shouldBe 1
                out[0].messagesList[0].code shouldBe "access_denied"
                calls.size shouldBe 1
                parsed shouldNotContain Language.REL_NODE
                dispatched shouldBe emptyList()
            }
        }

        "run, ER path: pass 2's refusal is the answer too" {
            runBlocking {
                val dispatched = CopyOnWriteArrayList<PlanNode>()
                val out =
                    service(
                        validator = refusingOnCall(2, CopyOnWriteArrayList()),
                        dispatcher = recordingDispatcher(dispatched),
                    ).run(sqlRequest()).toList()
                out.size shouldBe 1
                out[0].messagesList[0].code shouldBe "access_denied"
                dispatched shouldBe emptyList()
            }
        }

        "compile, ER path: pass 1's refusal is the answer — no plan, access_denied first" {
            runBlocking {
                val calls = CopyOnWriteArrayList<ValidateRequest>()
                val resp = service(validator = refusingOnCall(1, calls)).compile(sqlRequest())
                resp.hasPlan() shouldBe false
                resp.messagesList[0].code shouldBe "access_denied"
                calls.size shouldBe 1
            }
        }

        "compile, DB path: the refusal comes first and carries no plan" {
            runBlocking {
                val resp =
                    service(translatorDetect = dbDetect, validator = refusingOnCall(1, CopyOnWriteArrayList()))
                        .compile(sqlRequest())
                resp.hasPlan() shouldBe false
                resp.messagesList[0].code shouldBe "access_denied"
            }
        }

        // ── ttr-server#232 — a join the translator left a Cartesian product is said to the caller ─────────────────
        fun joinNote(
            severity: Severity,
            code: String,
            text: String,
        ): ResponseMessage =
            ResponseMessage
                .newBuilder()
                .setSeverity(severity)
                .setCode(code)
                .setHumanMessage(text)
                .build()
        val dbHalfCartesian =
            joinNote(
                Severity.WARNING,
                "JOIN_NO_RELATION",
                "no declared relation between entity.reason and entity.date_dim; Cartesian product preserved",
            )
        val erHalfCartesian = dbHalfCartesian.toBuilder().setHumanMessage("the ER half's own word").build()
        val leftToForeignKey =
            joinNote(
                Severity.INFO,
                "JOIN_RELATION_WITHOUT_PAIRS",
                "relation store_returns → reason declares no join pairs; left to its foreign key",
            )

        // The translator as it answers a statement with one unconditioned join: every DB-target parse (the DB path's
        // one parse, the ER path's re-entry) warns, beside the INFO on a join its FK conditioned; the ER half warns too,
        // in words of its own, so a test can tell which half a caller heard.
        fun cartesianTranslator(parses: MutableList<ParseRequest> = CopyOnWriteArrayList()): TranslatorClient =
            TranslatorClient { req ->
                parses += req
                val resp = ParseResponse.newBuilder().setPlan(planAfterParse).setContext(req.context)
                if (req.targetSchema == org.tatrman.plan.v1.SchemaCode.DB) {
                    resp.addMessages(leftToForeignKey).addMessages(dbHalfCartesian)
                } else {
                    resp.addMessages(erHalfCartesian)
                }
                resp.build()
            }

        fun joinWarnings(batch: ResultBatch) = batch.context.warningsList.filter { it.code.startsWith("JOIN_") }

        fun codes(batch: ResultBatch) = batch.context.warningsList.map { it.code }

        "run, ER path: the DB re-entry's join WARNING rides the first batch, once; its INFO does not" {
            runBlocking {
                val out = service(translator = cartesianTranslator()).run(sqlRequest()).toList()
                out.size shouldBe 2
                val warning = joinWarnings(out.first()).single()
                warning.code shouldBe "JOIN_NO_RELATION"
                warning.message shouldBe dbHalfCartesian.humanMessage
                warning.sourceStage shouldBe "expand_joins"
                warning.sourceService shouldBe "translate"
                codes(out.first()) shouldContain "cache_miss"
                // Not again on the last batch: a consumer adding the batches up would hear it twice.
                joinWarnings(out.last()) shouldBe emptyList()
                codes(out.last()) shouldNotContain "cache_miss"
            }
        }

        "run, DB path: a cache hit carries the join WARNING the miss did, without parsing again" {
            runBlocking {
                val parses = CopyOnWriteArrayList<ParseRequest>()
                val svc =
                    service(
                        translator = cartesianTranslator(parses),
                        translatorDetect = dbDetect,
                        cache = CompiledPlanCache(100, Duration.ofMinutes(60)),
                    )
                val miss = svc.run(sqlRequest()).toList()
                val hit = svc.run(sqlRequest()).toList()
                parses.size shouldBe 1
                codes(hit.first()) shouldContain "cache_hit"
                for (out in listOf(miss, hit)) {
                    joinWarnings(out.first()).map { it.message } shouldBe listOf(dbHalfCartesian.humanMessage)
                }
            }
        }

        "a one-batch answer carries each annotation once" {
            runBlocking {
                val oneBatch =
                    DispatcherClient { _ ->
                        flowOf(
                            ResultBatch
                                .newBuilder()
                                .setIsFirst(true)
                                .setIsLast(true)
                                .setArrowIpc(ByteArray(0).toByteString())
                                .setContext(PipelineContext.getDefaultInstance())
                                .build(),
                        )
                    }
                val out = service(translator = cartesianTranslator(), dispatcher = oneBatch).run(sqlRequest()).toList()
                codes(out.single()).count { it == "cache_miss" } shouldBe 1
                codes(out.single()).count { it == "JOIN_NO_RELATION" } shouldBe 1
            }
        }

        "compile, ER and DB paths, miss and hit: the join WARNING is in messages, the INFO is not" {
            runBlocking {
                val er = service(translator = cartesianTranslator())
                val db = service(translator = cartesianTranslator(), translatorDetect = dbDetect)
                val answers = List(2) { er.compile(sqlRequest()) } + List(2) { db.compile(sqlRequest()) }
                for (resp in answers) {
                    resp.hasPlan() shouldBe true
                    resp.messagesList.filter { it.code.startsWith("JOIN_") } shouldBe listOf(dbHalfCartesian)
                }
            }
        }

        // ── BI contracts C-9 — RunRequest.world through QUERY_WORLDS to dispatch's connection_id ──

        val hartlandWorlds = Worlds.parse("hartland_us=pg-hartland-us, hartland_cz=pg-hartland-cz", requireWorld = true)

        fun capturing(into: MutableList<DispatchRequest>): DispatcherClient =
            DispatcherClient { req ->
                into += req
                flowOf(
                    ResultBatch
                        .newBuilder()
                        .setIsFirst(true)
                        .setIsLast(true)
                        .setArrowIpc(ByteArray(0).toByteString())
                        .build(),
                )
            }

        fun worldRequest(world: String): RunRequest =
            RunRequest
                .newBuilder()
                .setSource("SELECT id FROM customers")
                .setSourceLanguage(Language.SQL)
                .setContext(PipelineContext.newBuilder().setUserId("u").setModelVersion("v"))
                .setWorld(world)
                .build()

        "a world the table names runs on its connection: dispatch gets connection_id, explicitly" {
            runBlocking {
                val dispatched = CopyOnWriteArrayList<DispatchRequest>()
                val out =
                    service(dispatcher = capturing(dispatched), worlds = hartlandWorlds)
                        .run(worldRequest("hartland_us"))
                        .toList()
                out.flatMap { it.messagesList }.filter { it.severity == Severity.ERROR } shouldBe emptyList()
                dispatched.single().connectionId shouldBe "pg-hartland-us"
                service(dispatcher = capturing(dispatched), worlds = hartlandWorlds)
                    .run(worldRequest("hartland_cz"))
                    .toList()
                dispatched.last().connectionId shouldBe "pg-hartland-cz"
            }
        }

        "an unknown world is refused before anything is parsed: one error batch, unknown_world, naming the worlds" {
            runBlocking {
                val dispatched = CopyOnWriteArrayList<DispatchRequest>()
                val parses = CopyOnWriteArrayList<ParseRequest>()
                val counting =
                    TranslatorClient { req ->
                        parses += req
                        ParseResponse
                            .newBuilder()
                            .setPlan(planAfterParse)
                            .setContext(req.context)
                            .build()
                    }
                val out =
                    service(translator = counting, dispatcher = capturing(dispatched), worlds = hartlandWorlds)
                        .run(worldRequest("hartland_de"))
                        .toList()
                val error = out.single().messagesList.single()
                error.severity shouldBe Severity.ERROR
                error.code shouldBe "unknown_world"
                error.humanMessage shouldBe
                    "World 'hartland_de' is not served here; the worlds are hartland_cz, hartland_us."
                out.single().isLast shouldBe true
                parses shouldBe emptyList()
                dispatched shouldBe emptyList()
            }
        }

        "a door with no world table refuses any named world, and an empty world uses the default" {
            runBlocking {
                val dispatched = CopyOnWriteArrayList<DispatchRequest>()
                val refused = service(dispatcher = capturing(dispatched)).run(worldRequest("hartland_us")).toList()
                refused
                    .single()
                    .messagesList
                    .single()
                    .code shouldBe "unknown_world"
                dispatched shouldBe emptyList()
                service(dispatcher = capturing(dispatched)).run(worldRequest("")).toList()
                dispatched.single().connectionId shouldBe ""
            }
        }

        "with the requirement off, two worlds allow omission and preserve derived routing for any roles" {
            // Exercise the default as well as an explicit false: default-true and ignoring the flag must fail.
            for (worlds in listOf(
                Worlds.parse("hartland_us=pg-hartland-us,hartland_cz=pg-hartland-cz"),
                Worlds(hartlandWorlds.connections, requireWorld = false),
            )) {
                val dispatched = CopyOnWriteArrayList<DispatchRequest>()
                val svc = service(dispatcher = capturing(dispatched), worlds = worlds)
                for (roles in listOf(emptyList(), listOf("analyst"))) {
                    val req =
                        worldRequest("")
                            .toBuilder()
                            .setContext(
                                PipelineContext.newBuilder().setUserId("u").addAllAuthRoles(roles),
                            ).build()
                    svc
                        .run(req)
                        .toList()
                        .flatMap { it.messagesList }
                        .filter { it.severity == Severity.ERROR } shouldBe
                        emptyList()
                }
                dispatched.map { it.connectionId } shouldBe listOf("", "")
                svc
                    .run(worldRequest("missing"))
                    .toList()
                    .single()
                    .messagesList
                    .single()
                    .code shouldBe "unknown_world"
                dispatched.size shouldBe 2
            }
        }

        "world refusal precedes an invalid row window on Run" {
            val svc = service(worlds = hartlandWorlds)
            val req =
                worldRequest("")
                    .toBuilder()
                    .setRowWindow(
                        org.tatrman.query.v1.RowWindow
                            .newBuilder()
                            .setOffset(-1),
                    ).build()
            svc
                .run(req)
                .toList()
                .single()
                .messagesList
                .single()
                .code shouldBe "world_unspecified"
        }

        "multiple worlds require a choice before any downstream call, with or without roles" {
            val neverParse = TranslatorClient { error("an unspecified world must not parse") }
            val neverDetect = TranslatorDetectClient { error("an unspecified world must not detect") }
            val neverValidate = ValidatorClient { error("an unspecified world must not validate") }
            val dispatched = CopyOnWriteArrayList<DispatchRequest>()
            val svc =
                service(
                    translator = neverParse,
                    translatorDetect = neverDetect,
                    validator = neverValidate,
                    dispatcher = capturing(dispatched),
                    worlds = hartlandWorlds,
                )
            for (roles in listOf(emptyList(), listOf("analyst"))) {
                val context =
                    PipelineContext
                        .newBuilder()
                        .setUserId("u")
                        .setCorrelationId("world-test")
                        .addAllAuthRoles(roles)
                        .build()
                val batch = svc.run(worldRequest("").toBuilder().setContext(context).build()).toList().single()
                batch.isFirst shouldBe true
                batch.isLast shouldBe true
                batch.arrowIpc.isEmpty shouldBe true
                batch.context shouldBe context
                batch.messagesList.single().severity shouldBe Severity.ERROR
                batch.messagesList.single().code shouldBe "world_unspecified"
                batch.messagesList.single().humanMessage shouldBe
                    "A world is required here; the worlds are hartland_cz, hartland_us."
                svc.activeRunCount shouldBe 0
            }
            dispatched shouldBe emptyList()
        }

        "an unnamed world is refused on a warm cache and with bypass_cache" {
            val dispatched = CopyOnWriteArrayList<DispatchRequest>()
            val svc = service(dispatcher = capturing(dispatched), worlds = hartlandWorlds)
            val first = worldRequest("hartland_us")
            val initial = svc.run(first).toList().flatMap { it.messagesList }
            initial.filter { it.severity == Severity.ERROR } shouldBe emptyList()
            val cached = svc.run(first).toList().flatMap { it.context.warningsList }
            cached.map { it.code } shouldContain "cache_hit"
            for (bypass in listOf(false, true)) {
                val batch =
                    svc
                        .run(
                            first
                                .toBuilder()
                                .clearWorld()
                                .setBypassCache(bypass)
                                .build(),
                        ).toList()
                        .single()
                batch.messagesList.single().code shouldBe "world_unspecified"
            }
            dispatched.size shouldBe 2
        }

        "one configured world permits omission and still maps an explicit choice" {
            val dispatched = CopyOnWriteArrayList<DispatchRequest>()
            val svc =
                service(
                    dispatcher = capturing(dispatched),
                    worlds = Worlds.parse("hartland_us=pg-hartland-us", requireWorld = true),
                )
            for (world in listOf("", "hartland_us")) {
                svc
                    .run(worldRequest(world))
                    .toList()
                    .flatMap { it.messagesList }
                    .filter { it.severity == Severity.ERROR } shouldBe emptyList()
            }
            dispatched.map { it.connectionId } shouldBe listOf("", "pg-hartland-us")
        }

        "two world names require a choice even if they alias one connection" {
            val svc = service(worlds = Worlds.parse("alias_a=shared,alias_b=shared", requireWorld = true))
            svc
                .run(worldRequest(""))
                .toList()
                .single()
                .messagesList
                .single()
                .code shouldBe "world_unspecified"
        }

        "compile ignores world selection on a multi-world door" {
            val svc = service(worlds = hartlandWorlds)
            for (world in listOf("", "hartland_us", "hartland_de")) {
                val compiled = svc.compile(worldRequest(world))
                compiled.hasPlan() shouldBe true
                compiled.messagesList.filter { it.severity == Severity.ERROR } shouldBe emptyList()
            }
        }

        "QUERY_WORLDS parses world=connection pairs and names a malformed or repeated one" {
            hartlandWorlds.connectionOf("hartland_us") shouldBe "pg-hartland-us"
            hartlandWorlds.names shouldBe listOf("hartland_cz", "hartland_us")
            Worlds.parse("").connections shouldBe emptyMap()
            Worlds.parse(null).connections shouldBe emptyMap()
            io.kotest.assertions.throwables
                .shouldThrow<IllegalArgumentException> { Worlds.parse("hartland_us") }
                .message shouldBe "query.worlds (QUERY_WORLDS): 'hartland_us' is not world=connection"
            io.kotest.assertions.throwables
                .shouldThrow<IllegalArgumentException> { Worlds.parse("a=x,a=y") }
                .message shouldBe "query.worlds (QUERY_WORLDS) names a twice"
        }

        "Run maps only parameter_unknown at all three translator refusal sites and preserves positions" {
            runBlocking {
                for (site in listOf("DB", "ER", "ER-to-DB")) {
                    for (code in listOf("parameter_unknown", "parameter_in_string_literal", "validation_failed")) {
                        var calls = 0
                        val svc =
                            service(
                                translatorDetect = if (site == "DB") dbDetect else detectStub,
                                translator =
                                    TranslatorClient { req ->
                                        calls++
                                        if (site == "ER-to-DB" && calls == 1) {
                                            parseStub.parse(req)
                                        } else {
                                            ParseResponse
                                                .newBuilder()
                                                .setContext(req.context)
                                                .addMessages(
                                                    ResponseMessage
                                                        .newBuilder()
                                                        .setSeverity(Severity.ERROR)
                                                        .setCode(code)
                                                        .setHumanMessage("missing '{id}'")
                                                        .setLine(3)
                                                        .setColumn(17),
                                                ).build()
                                        }
                                    },
                                dispatcher = DispatcherClient { error("refused plan must not dispatch") },
                            )
                        val message =
                            svc
                                .run(sqlRequest())
                                .toList()
                                .single()
                                .messagesList
                                .single()
                        message.code shouldBe
                            if (code == "parameter_unknown") "parameter_unbound" else "translator_rejected"
                        message.humanMessage shouldBe "missing '{id}'"
                        message.line shouldBe 3
                        message.column shouldBe 17
                        val compiled = svc.compile(sqlRequest())
                        compiled.messagesList.single().code shouldBe code
                    }
                }
            }
        }
    })
