// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.grpc

import com.google.protobuf.UnknownFieldSet
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.*
import org.tatrman.query.cache.CompiledPlanCache
import org.tatrman.query.client.*
import org.tatrman.query.retry.RetryPolicy
import org.tatrman.query.v1.*
import org.tatrman.translate.v1.*
import org.tatrman.validate.v1.ValidateResponse
import org.tatrman.worker.v1.ResultBatch
import java.time.Duration

class ErReadScopeSpec :
    StringSpec({
        "scope includes expanded boundaries, logical bodies, unions and nested expression subqueries" {
            val body =
                PlanNode
                    .newBuilder()
                    .setUnion(
                        UnionNode.newBuilder().addInputs(erScan("body")).addInputs(erScan("body")),
                    ).build()
            val nested =
                Expression
                    .newBuilder()
                    .setSubquery(
                        SubqueryExpression.newBuilder().setSubquery(erScan("secret")),
                    ).build()
            val expression =
                Expression.newBuilder().setCast(
                    CastExpression.newBuilder().setValue(
                        Expression.newBuilder().setFunction(
                            FunctionCall.newBuilder().setOperation("coalesce").addOperands(nested),
                        ),
                    ),
                )
            val plan =
                PlanNode
                    .newBuilder()
                    .setProject(
                        ProjectNode
                            .newBuilder()
                            .setExpandedFrom(erName("stacked"))
                            .setInput(body)
                            .addExpressions(NamedExpression.newBuilder().setExpression(expression)),
                    ).build()
            ErReads.inspect(plan).objectsList shouldBe listOf("er.entity.body", "er.entity.secret", "er.entity.stacked")
        }
        "scope walks join conditions and window operands, partition and order expressions" {
            fun sub(name: String) =
                Expression
                    .newBuilder()
                    .setSubquery(
                        SubqueryExpression.newBuilder().setSubquery(erScan(name)),
                    ).build()
            val plan =
                PlanNode
                    .newBuilder()
                    .setProject(
                        ProjectNode
                            .newBuilder()
                            .setInput(
                                PlanNode.newBuilder().setJoin(
                                    JoinNode
                                        .newBuilder()
                                        .setLeft(erScan("left"))
                                        .setRight(erScan("right"))
                                        .setCondition(sub("join")),
                                ),
                            ).addExpressions(
                                NamedExpression.newBuilder().setExpression(
                                    Expression.newBuilder().setOver(
                                        OverExpression
                                            .newBuilder()
                                            .addOperands(sub("operand"))
                                            .addPartitionKeys(sub("partition"))
                                            .addOrderKeys(OverOrderKey.newBuilder().setExpr(sub("order"))),
                                    ),
                                ),
                            ),
                    ).build()
            ErReads.inspect(plan).objectsList.toSet() shouldBe
                setOf("left", "right", "join", "operand", "partition", "order")
                    .map { "er.entity.$it" }
                    .toSet()
        }
        "physical, workspace, write, unset and unknown plans cannot claim an empty scope" {
            val unknown =
                erScan("ok")
                    .toBuilder()
                    .setUnknownFields(
                        UnknownFieldSet
                            .newBuilder()
                            .addField(
                                900,
                                UnknownFieldSet.Field
                                    .newBuilder()
                                    .addVarint(1)
                                    .build(),
                            ).build(),
                    ).build()
            for (plan in listOf(
                physical(),
                PlanNode.newBuilder().setWorkspaceRef(WorkspaceRef.getDefaultInstance()).build(),
                PlanNode.newBuilder().setStore(StoreNode.newBuilder().setInput(erScan("ok"))).build(),
                PlanNode.getDefaultInstance(),
                unknown,
                PlanNode
                    .newBuilder()
                    .setScan(
                        ScanNode.newBuilder().setObject(erName("bad").toBuilder().setSchemaCode(SchemaCode.DB)),
                    ).build(),
            )) {
                shouldThrowAny { ErReads.inspect(plan) }
            }
            ErReads.inspect(constant()).objectsList shouldBe emptyList()
        }
        "compile reports fresh ER evidence on cache hits, independent of context and DB audit" {
            var validations = 0
            var parses = 0
            var dispatches = 0
            val service =
                scopeService(
                    parse = {
                        parses++
                        erScan("parsed")
                    },
                    validate = { plan, context ->
                        validations++
                        if (plan.hasTableScan()) physical() else erScan(context.userId)
                    },
                    dispatch = { dispatches++ },
                )
            for (user in listOf("alice", "bob", "alice")) {
                val result = service.compile(scopeRequest(user))
                result.hasPlan() shouldBe true
                result.plan shouldBe physical()
                result.hasErReads() shouldBe true
                result.erReads.objectsList shouldBe listOf("er.entity.$user")
            }
            parses shouldBe 1
            validations shouldBe 6
            dispatches shouldBe 0
        }
        "constant ER reads carry present empty evidence; DB compilation has no ER evidence" {
            for (schema in listOf(SchemaCode.ER, SchemaCode.DB)) {
                val svc = scopeService(schema = schema, parse = { constant() })
                repeat(2) {
                    val answer = svc.compile(scopeRequest("u").toBuilder().setSourceSchema(schema).build())
                    answer.hasPlan() shouldBe true
                    answer.hasErReads() shouldBe (schema == SchemaCode.ER)
                    answer.erReads.objectsList shouldBe emptyList()
                }
            }
        }
        "compile refusals never carry successful read evidence" {
            val svc = scopeService(refuse = true)
            val answer = svc.compile(scopeRequest("u"))
            answer.hasPlan() shouldBe false
            answer.hasErReads() shouldBe false
            answer.messagesList.first().code shouldBe "denied"
        }
        "allow-list is enforced on every run and compile, including cache hits and a caller policy change" {
            var dispatches = 0
            var expansions = 0
            val svc =
                scopeService(
                    validate = { plan, context ->
                        if (plan.hasTableScan()) {
                            plan
                        } else {
                            expansions++
                            erScan(context.userId)
                        }
                    },
                    dispatch = { dispatches++ },
                )
            val allowed = scopeRequest("allowed").toBuilder().setAllowedErReads(scope("allowed")).build()
            repeat(2) {
                svc
                    .run(allowed)
                    .toList()
                    .single()
                    .messagesCount shouldBe 0
            }
            dispatches shouldBe 2
            val changed = allowed.toBuilder().setContext(allowed.context.toBuilder().setUserId("hidden")).build()
            repeat(2) {
                svc
                    .run(changed)
                    .toList()
                    .single()
                    .messagesList
                    .first()
                    .code shouldBe "out-of-scope-read"
                val compiled = svc.compile(changed)
                compiled.hasPlan() shouldBe false
                compiled.hasErReads() shouldBe false
                compiled.messagesList.first().code shouldBe "out-of-scope-read"
            }
            dispatches shouldBe 2
            expansions shouldBe 6
        }
        "a preflight success cannot authorize a different ER tree at execution" {
            var expand = "allowed"
            var dispatches = 0
            val svc =
                scopeService(validate = {
                    plan,
                    _,
                    ->
                    if (plan.hasTableScan()) plan else erScan(expand)
                }, dispatch = { dispatches++ })
            val request = scopeRequest("u").toBuilder().setAllowedErReads(scope("allowed")).build()
            svc.compile(request).erReads.objectsList shouldBe listOf("er.entity.allowed")
            expand = "secret"
            svc
                .run(request)
                .toList()
                .single()
                .messagesList
                .first()
                .code shouldBe "out-of-scope-read"
            dispatches shouldBe 0
        }
        "scope checks include expanded logical bodies rather than trusting a declared boundary alone" {
            val expanded =
                PlanNode
                    .newBuilder()
                    .setProject(
                        ProjectNode
                            .newBuilder()
                            .setExpandedFrom(erName("stacked"))
                            .setInput(erScan("secret")),
                    ).build()
            val svc = scopeService(parse = { expanded }, dispatch = { error("must not dispatch undeclared body") })
            val request = scopeRequest("u").toBuilder().setAllowedErReads(scope("stacked")).build()
            repeat(2) {
                svc
                    .run(request)
                    .toList()
                    .single()
                    .messagesList
                    .first()
                    .code shouldBe "out-of-scope-read"
                svc.compile(request).hasPlan() shouldBe false
            }
        }
        "DB or uninspectable plans refuse an ER allow-list; existing clients retain behavior" {
            for (schema in listOf(SchemaCode.DB, SchemaCode.ER)) {
                val svc = scopeService(schema = schema, parse = { physical() })
                val request =
                    scopeRequest("u")
                        .toBuilder()
                        .setSourceSchema(schema)
                        .setAllowedErReads(scope())
                        .build()
                repeat(2) {
                    svc
                        .run(request)
                        .toList()
                        .single()
                        .messagesList
                        .first()
                        .code shouldBe "out-of-scope-read"
                    svc.compile(request).hasPlan() shouldBe false
                }
                svc
                    .run(request.toBuilder().clearAllowedErReads().build())
                    .toList()
                    .single()
                    .messagesCount shouldBe 0
            }
        }
    })

private fun erName(name: String) =
    QualifiedName
        .newBuilder()
        .setSchemaCode(SchemaCode.ER)
        .setNamespace("entity")
        .setName(name)
        .build()

private fun erScan(name: String) = PlanNode.newBuilder().setScan(ScanNode.newBuilder().setObject(erName(name))).build()

private fun physical() =
    PlanNode
        .newBuilder()
        .setTableScan(
            TableScanNode
                .newBuilder()
                .setTable(erName("physical_table").toBuilder().setSchemaCode(SchemaCode.DB).setNamespace("public")),
        ).build()

private fun constant() = PlanNode.newBuilder().setValues(ValuesNode.getDefaultInstance()).build()

private fun scope(vararg names: String) = ErReadScope.newBuilder().addAllObjects(names.map { "er.entity.$it" }).build()

private fun scopeRequest(user: String) =
    RunRequest
        .newBuilder()
        .setSource("SELECT ...")
        .setSourceLanguage(Language.SQL)
        .setSourceSchema(SchemaCode.ER)
        .setContext(
            PipelineContext
                .newBuilder()
                .setUserId(user)
                .setModelVersion("pinned")
                .addUsedObjects(
                    ObjectRef.newBuilder().setSchemaCode(SchemaCode.ER).setQualifiedName("er.entity.forged"),
                ),
        ).build()

private fun scopeService(
    schema: SchemaCode = SchemaCode.ER,
    parse: () -> PlanNode = { erScan("read") },
    validate: (PlanNode, PipelineContext) -> PlanNode = { plan, _ -> plan },
    dispatch: () -> Unit = {},
    refuse: Boolean = false,
) = QueryServiceImpl(
    TranslatorClient { request ->
        ParseResponse
            .newBuilder()
            .setPlan(if (request.sourceLanguage == Language.REL_NODE) physical() else parse())
            .setContext(request.context)
            .build()
    },
    TranslatorDetectClient {
        DetectSchemaResponse
            .newBuilder()
            .setEffectiveSchema(schema)
            .setDecision(SchemaDecision.CONFIRMED)
            .build()
    },
    TranslatorTranslateClient { error("unused") },
    ValidatorClient { request ->
        ValidateResponse
            .newBuilder()
            .setContext(
                request.context
                    .toBuilder()
                    .clearUsedObjects()
                    .addUsedObjects(
                        ObjectRef
                            .newBuilder()
                            .setSchemaCode(
                                SchemaCode.DB,
                            ).setQualifiedName("db.public.physical_table"),
                    ),
            ).apply {
                if (refuse) {
                    addMessages(ResponseMessage.newBuilder().setSeverity(Severity.ERROR).setCode("denied"))
                } else {
                    setPlan(validate(request.plan, request.context))
                }
            }.build()
    },
    DispatcherClient {
        dispatch()
        flowOf(
            ResultBatch
                .newBuilder()
                .setIsFirst(true)
                .setIsLast(true)
                .build(),
        )
    },
    CompiledPlanCache(20, Duration.ofMinutes(60)),
    RetryPolicy(maxAttempts = 1, initialBackoffMillis = 1),
)
