// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.grpc

import com.google.protobuf.ByteString
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrowAny
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import org.tatrman.plan.v1.*
import org.tatrman.query.cache.CompiledPlanCache
import org.tatrman.query.client.*
import org.tatrman.query.retry.RetryPolicy
import org.tatrman.query.v1.*
import org.tatrman.query.world.Worlds
import org.tatrman.translate.v1.*
import org.tatrman.validate.v1.ValidateResponse
import org.tatrman.worker.v1.ResultBatch
import java.time.Duration

class RowWritesSpec :
    StringSpec({
        val er =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.ER)
                .setNamespace("entity")
                .setName("orders")
                .build()
        val db =
            er
                .toBuilder()
                .setSchemaCode(SchemaCode.DB)
                .setNamespace("dbo")
                .setName("order_rows")
                .build()

        fun plan(
            target: QualifiedName,
            parameterized: Boolean = false,
        ) = PlanNode
            .newBuilder()
            .setRowWrites(
                RowWrites.newBuilder().addRows(
                    RowWrite.newBuilder().setTarget(target).setOp(RowWriteOp.INSERT).apply {
                        if (parameterized) {
                            addValues(
                                ColumnValue.newBuilder().setName("state").setValue(
                                    Expression.newBuilder().setParameter(ParameterRef.newBuilder().setName("state")),
                                ),
                            )
                        }
                    },
                ),
            ).build()

        fun request(
            user: String = "writer",
            scoped: Boolean = false,
            parameterized: Boolean = false,
        ) = RunRequest
            .newBuilder()
            .setSourceLanguage(Language.REL_NODE)
            .setSourceSchema(SchemaCode.ER)
            .setSource(plan(er, parameterized).toByteArray().toString(Charsets.ISO_8859_1))
            .setWorld("cz-write")
            .setContext(PipelineContext.newBuilder().setUserId(user).setCorrelationId("write-test"))
            .apply { if (scoped) setAllowedErReads(ErReadScope.getDefaultInstance()) }
            .build()
        var parses = 0
        var validations = 0
        var dispatches = 0
        var sentConnection = ""
        val answer =
            ResultBatch
                .newBuilder()
                .setArrowIpc(ByteString.copyFromUtf8("opaque worker answer"))
                .setBatchIndex(7)
                .setBatchRowCount(3)
                .setIsFirst(true)
                .setIsLast(true)
                .build()
        beforeTest {
            parses = 0
            validations = 0
            dispatches = 0
            sentConnection = ""
        }

        fun service(
            allowed: Set<String> = setOf("writer"),
            parameterized: Boolean = false,
            refuseDb: Boolean = false,
        ) = QueryServiceImpl(
            TranslatorClient { r ->
                parses++
                r.sourceLanguage shouldBe Language.REL_NODE
                ParseResponse
                    .newBuilder()
                    .setPlan(
                        if (r.targetSchema ==
                            SchemaCode.DB
                        ) {
                            plan(db, parameterized)
                        } else {
                            plan(er, parameterized)
                        },
                    ).setContext(r.context)
                    .build()
            },
            TranslatorDetectClient { error("row writes are not SQL detection") },
            TranslatorTranslateClient { error("row writes use plan lowering") },
            ValidatorClient { r ->
                validations++
                r.options.applySecurity shouldBe true
                r.options.enforceTopN shouldBe false
                r.context.userId shouldBe "writer"
                ValidateResponse
                    .newBuilder()
                    .setContext(r.context)
                    .apply {
                        if (refuseDb &&
                            r.plan.rowWrites.rowsList
                                .single()
                                .target.schemaCode == SchemaCode.DB
                        ) {
                            addMessages(
                                org.tatrman.common.v1.ResponseMessage
                                    .newBuilder()
                                    .setSeverity(org.tatrman.common.v1.Severity.ERROR)
                                    .setCode("write_under_column_rule"),
                            )
                        } else {
                            setPlan(r.plan)
                        }
                    }.build()
            },
            DispatcherClient { r ->
                dispatches++
                sentConnection = r.connectionId
                r.plan shouldBe plan(db, parameterized)
                flowOf(answer)
            },
            CompiledPlanCache(10, Duration.ofMinutes(1)),
            RetryPolicy(maxAttempts = 1),
            worlds = Worlds(mapOf("cz-write" to "pg-writer")),
            rowWritesPrincipals = allowed,
        )
        "unlisted and default-empty principals cannot Run or Compile through any schema/cache path" {
            for (allowed in listOf(emptySet(), setOf("another"))) {
                val query = service(allowed)
                for (schema in listOf(SchemaCode.ER, SchemaCode.DB, SchemaCode.SCHEMA_CODE_UNSPECIFIED)) {
                    for (bypass in listOf(false, true)) {
                        val r =
                            request()
                                .toBuilder()
                                .setSourceSchema(schema)
                                .setBypassCache(bypass)
                                .build()
                        query
                            .compile(r)
                            .messagesList
                            .single()
                            .code shouldBe "row_writes_principal"
                        query
                            .run(r)
                            .toList()
                            .single()
                            .messagesList
                            .single()
                            .code shouldBe "row_writes_principal"
                    }
                }
            }
            parses shouldBe 0
            validations shouldBe 0
            dispatches shouldBe 0
        }
        "a read scope refuses writes before translation and ER read inspection fails closed" {
            val query = service()
            query.compile(request(scoped = true)).messagesList.single().let {
                it.code shouldBe "out-of-scope-read"
                it.humanMessage shouldBe "row_writes"
            }
            query
                .run(request(scoped = true))
                .toList()
                .single()
                .messagesList
                .single()
                .code shouldBe "out-of-scope-read"
            shouldThrowAny { ErReads.inspect(plan(er)) }
            parses shouldBe 0
            validations shouldBe 0
            dispatches shouldBe 0
        }
        "missing identity refuses before downstream calls" {
            service()
                .compile(request(user = ""))
                .messagesList
                .single()
                .code shouldBe "identity"
            service()
                .run(request(user = ""))
                .toList()
                .single()
                .messagesList
                .single()
                .code shouldBe "identity"
            parses shouldBe 0
        }
        "Run lowers ER to DB, validates both and streams the worker batch unchanged on the world's connection" {
            val query = service()
            query.run(request()).toList() shouldBe listOf(answer)
            query.run(request()).toList() shouldBe listOf(answer)
            parses shouldBe 4
            validations shouldBe 4
            dispatches shouldBe 2
            sentConnection shouldBe "pg-writer"
            query.activeRunCount shouldBe 0
        }
        "Compile reports write parameters and Run refuses unbound values before dispatch" {
            val query = service(parameterized = true)
            val r = request(parameterized = true)
            query.compile(r).requiredParametersList.map { it.name } shouldBe listOf("state")
            query
                .run(r)
                .toList()
                .single()
                .messagesList
                .single()
                .code shouldBe "parameter_unbound"
            dispatches shouldBe 0
            val bound =
                r
                    .toBuilder()
                    .setContext(
                        r.context.toBuilder().addParameters(
                            ParameterBinding
                                .newBuilder()
                                .setName(
                                    "state",
                                ).setType("text")
                                .setValue(Value.newBuilder().setStringValue("held")),
                        ),
                    ).build()
            query.run(bound).toList() shouldBe listOf(answer)
            dispatches shouldBe 1
        }
        "a DB policy refusal returns no partial compiled plan and never dispatches" {
            val query = service(refuseDb = true)
            val compiled = query.compile(request())
            compiled.hasPlan() shouldBe false
            compiled.messagesList.single().code shouldBe "write_under_column_rule"
            query
                .run(request())
                .toList()
                .single()
                .messagesList
                .single()
                .code shouldBe "write_under_column_rule"
            dispatches shouldBe 0
        }
        "Compile reports lowered write targets without dispatch" {
            val result = service().compile(request())
            result.messagesCount shouldBe 0
            result.plan shouldBe plan(db)
            parses shouldBe 2
            validations shouldBe 2
            dispatches shouldBe 0
        }
    })
