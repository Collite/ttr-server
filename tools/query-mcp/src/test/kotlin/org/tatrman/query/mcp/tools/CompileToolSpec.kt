// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.mcp.tools

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.mcp.identity.IdentitySource
import org.tatrman.mcp.identity.UserIdentity
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.TableScanNode
import org.tatrman.query.mcp.QueryMcpConfig
import org.tatrman.query.mcp.upstream.TranslatorClient
import org.tatrman.query.mcp.upstream.ValidatorClient
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.ParseRequest
import org.tatrman.translate.v1.ParseResponse
import org.tatrman.translate.v1.TranslateRequest
import org.tatrman.translate.v1.TranslateResponse
import org.tatrman.translate.v1.UnparseRequest
import org.tatrman.translate.v1.UnparseResponse
import org.tatrman.validate.v1.ValidateRequest
import org.tatrman.validate.v1.ValidateResponse

class CompileToolSpec :
    StringSpec({

        val cfg =
            QueryMcpConfig(
                serverPort = 7401,
                mcpTransport = "streamable-http",
                mcpPath = "/mcp",
                upstream =
                    QueryMcpConfig.Upstream(
                        queryRunner = QueryMcpConfig.GrpcEndpoint("h", 1, 30),
                        translator = QueryMcpConfig.GrpcEndpoint("h", 1, 30),
                        validator = QueryMcpConfig.GrpcEndpoint("h", 1, 30),
                        metadata = QueryMcpConfig.GrpcEndpoint("h", 1, 30),
                    ),
                limits =
                    QueryMcpConfig.Limits(
                        rowLimitDefault = 500,
                        rowLimitMax = 5000,
                        requestTimeoutSeconds = 120,
                        maxMessageBytes = 32 * 1024 * 1024,
                    ),
                security = QueryMcpConfig.Security(requireIdentity = false),
                toolTimeoutsMs = mapOf("compile" to 60_000L),
            )

        fun fakeTranslator(
            parseImpl: (ParseRequest) -> ParseResponse = { ParseResponse.getDefaultInstance() },
            unparseImpl: (UnparseRequest) -> UnparseResponse = {
                UnparseResponse.newBuilder().setOutput("SELECT 1 /* default */").build()
            },
        ) = object : TranslatorClient {
            override suspend fun parseToRelNode(request: ParseRequest): ParseResponse = parseImpl(request)

            override suspend fun translate(request: TranslateRequest): TranslateResponse =
                throw UnsupportedOperationException()

            override suspend fun unparseFromRelNode(request: UnparseRequest): UnparseResponse = unparseImpl(request)
        }

        fun fakeValidator(impl: (ValidateRequest) -> ValidateResponse) =
            object : ValidatorClient {
                override suspend fun validate(request: ValidateRequest): ValidateResponse = impl(request)
            }

        val plan = PlanNode.getDefaultInstance()

        fun validated(req: ValidateRequest): ValidateResponse =
            ValidateResponse
                .newBuilder()
                .setPlan(req.plan)
                .setContext(req.context)
                .build()

        fun compileRequest(sourceSchema: String? = null): CallToolRequest =
            CallToolRequest(
                params =
                    CallToolRequestParams(
                        name = "compile",
                        arguments =
                            buildJsonObject {
                                put("source", JsonPrimitive("SELECT * FROM Customers"))
                                put("source_language", JsonPrimitive("sql"))
                                put("target_dialect", JsonPrimitive("mssql"))
                                if (sourceSchema != null) put("source_schema", JsonPrimitive(sourceSchema))
                            },
                    ),
            )

        // Records every ParseRequest the tool sends, answering each with an empty plan.
        fun capturingTranslator(captured: MutableList<ParseRequest>) =
            fakeTranslator(
                parseImpl = { req ->
                    captured += req
                    ParseResponse
                        .newBuilder()
                        .setPlan(plan)
                        .setContext(req.context)
                        .build()
                },
            )

        "happy path with apply_security=true: parse → validate → unparse" {
            var parsed = false
            var validated = false
            var unparsed = false
            val translator =
                fakeTranslator(
                    parseImpl = {
                        parsed = true
                        ParseResponse
                            .newBuilder()
                            .setPlan(plan)
                            .setContext(PipelineContext.getDefaultInstance())
                            .build()
                    },
                    unparseImpl = { req ->
                        unparsed = true
                        UnparseResponse
                            .newBuilder()
                            .setOutput("SELECT * FROM Customers WHERE tenantId = 'X'")
                            .setContext(req.context)
                            .build()
                    },
                )
            val validator =
                fakeValidator { req ->
                    validated = true
                    ValidateResponse
                        .newBuilder()
                        .setPlan(req.plan)
                        .setContext(req.context)
                        .build()
                }
            val tool = CompileTool(cfg, translator, validator)
            val args =
                buildJsonObject {
                    put("source", JsonPrimitive("SELECT * FROM Customers"))
                    put("source_language", JsonPrimitive("sql"))
                    put("target_dialect", JsonPrimitive("mssql"))
                }
            val res =
                runBlocking {
                    tool.execute(
                        CallToolRequest(params = CallToolRequestParams(name = "compile", arguments = args)),
                        identity = null,
                    )
                }
            res.isError shouldBe false
            parsed shouldBe true
            validated shouldBe true
            unparsed shouldBe true
            (res.content[0] as TextContent).text shouldBe "SELECT * FROM Customers WHERE tenantId = 'X'"
            ((res.structuredContent!!["compiledSql"] as JsonPrimitive).content) shouldBe
                "SELECT * FROM Customers WHERE tenantId = 'X'"
            ((res.structuredContent!!["appliedSecurity"] as JsonPrimitive).content) shouldBe "true"
        }

        // The plan CompileResponse has always carried, now surfaced. A caller that wants to show
        // what it is about to run had only the SQL — the compiled form, after the plan had been
        // unparsed away.
        "compile returns the plan as text" {
            val scanPlan =
                PlanNode
                    .newBuilder()
                    .setTableScan(TableScanNode.newBuilder().setTable(QualifiedName.newBuilder().setName("Customers")))
                    .build()
            val translator =
                fakeTranslator(
                    parseImpl = {
                        ParseResponse
                            .newBuilder()
                            .setPlan(scanPlan)
                            .setContext(PipelineContext.getDefaultInstance())
                            .build()
                    },
                )
            val tool = CompileTool(cfg, translator, fakeValidator { req -> validated(req) })
            val res = runBlocking { tool.execute(compileRequest(), identity = null) }

            val text = (res.structuredContent!!["relPlanText"] as JsonPrimitive).content
            // Proto text format, so PROTO field names — `table_scan`, not `tableScan`. The tree
            // is rendered indented, which is what makes it readable without a bespoke printer.
            text shouldContain "table_scan {"
            text shouldContain "Customers"
        }

        // ⚑ The assertion that makes the field trustworthy. The validator injects row-level
        // predicates, so the plan it returns is the one that RUNS. Publishing the pre-validator
        // plan would show a reader a materially different query and present it as the real one.
        "the plan returned is the one AFTER the validator, not after the parse" {
            val parsedPlan =
                PlanNode
                    .newBuilder()
                    .setTableScan(TableScanNode.newBuilder().setTable(QualifiedName.newBuilder().setName("BeforeRls")))
                    .build()
            val securedPlan =
                PlanNode
                    .newBuilder()
                    .setTableScan(TableScanNode.newBuilder().setTable(QualifiedName.newBuilder().setName("AfterRls")))
                    .build()
            val translator =
                fakeTranslator(
                    parseImpl = {
                        ParseResponse
                            .newBuilder()
                            .setPlan(parsedPlan)
                            .setContext(PipelineContext.getDefaultInstance())
                            .build()
                    },
                )
            // A validator that REWRITES the plan, the way security injection does.
            val validator =
                fakeValidator { req ->
                    ValidateResponse
                        .newBuilder()
                        .setPlan(securedPlan)
                        .setContext(req.context)
                        .build()
                }
            val tool = CompileTool(cfg, translator, validator)
            val res = runBlocking { tool.execute(compileRequest(), identity = null) }

            val text = (res.structuredContent!!["relPlanText"] as JsonPrimitive).content
            text shouldContain "AfterRls"
            text shouldNotContain "BeforeRls"
        }

        "apply_security=false + admin: skips validator" {
            var validated = false
            val translator =
                fakeTranslator(
                    parseImpl = {
                        ParseResponse.newBuilder().setPlan(plan).build()
                    },
                    unparseImpl = { UnparseResponse.newBuilder().setOutput("SELECT *").build() },
                )
            val validator =
                fakeValidator { _ ->
                    validated = true
                    ValidateResponse.getDefaultInstance()
                }
            val tool = CompileTool(cfg, translator, validator)
            val args =
                buildJsonObject {
                    put("source", JsonPrimitive("SELECT *"))
                    put("source_language", JsonPrimitive("sql"))
                    put("target_dialect", JsonPrimitive("mssql"))
                    put("apply_security", JsonPrimitive(false))
                }
            val admin = UserIdentity(id = "admin:bora", roles = setOf("admin"), source = IdentitySource.HEADER)
            val res =
                runBlocking {
                    tool.execute(
                        CallToolRequest(params = CallToolRequestParams(name = "compile", arguments = args)),
                        identity = admin,
                    )
                }
            res.isError shouldBe false
            validated shouldBe false
            ((res.structuredContent!!["appliedSecurity"] as JsonPrimitive).content) shouldBe "false"
        }

        "apply_security=false + non-admin: returns permission_denied" {
            val tool =
                CompileTool(
                    cfg,
                    fakeTranslator(),
                    fakeValidator { ValidateResponse.getDefaultInstance() },
                )
            val args =
                buildJsonObject {
                    put("source", JsonPrimitive("SELECT *"))
                    put("source_language", JsonPrimitive("sql"))
                    put("target_dialect", JsonPrimitive("mssql"))
                    put("apply_security", JsonPrimitive(false))
                }
            val nonAdmin = UserIdentity(id = "bob", roles = emptySet(), source = IdentitySource.HEADER)
            val res =
                runBlocking {
                    tool.execute(
                        CallToolRequest(params = CallToolRequestParams(name = "compile", arguments = args)),
                        identity = nonAdmin,
                    )
                }
            res.isError shouldBe true
            val msgs = (res.structuredContent!!["messages"] as JsonArray)
            ((msgs[0] as JsonObject)["code"] as JsonPrimitive).content shouldBe "permission_denied"
        }

        "validator rejection surfaces as ok=false" {
            val translator =
                fakeTranslator(
                    parseImpl = { ParseResponse.newBuilder().setPlan(plan).build() },
                )
            val validator =
                fakeValidator { _ ->
                    ValidateResponse
                        .newBuilder()
                        .addMessages(
                            ResponseMessage
                                .newBuilder()
                                .setSeverity(Severity.ERROR)
                                .setCode("rule_violation")
                                .setHumanMessage("Top-N exceeded.")
                                .build(),
                        ).build()
                }
            val tool = CompileTool(cfg, translator, validator)
            val args =
                buildJsonObject {
                    put("source", JsonPrimitive("SELECT *"))
                    put("source_language", JsonPrimitive("sql"))
                    put("target_dialect", JsonPrimitive("mssql"))
                }
            val res =
                runBlocking {
                    tool.execute(
                        CallToolRequest(params = CallToolRequestParams(name = "compile", arguments = args)),
                        identity = null,
                    )
                }
            res.isError shouldBe true
            ((res.structuredContent!!["ok"] as JsonPrimitive).content) shouldBe "false"
            val msgs = (res.structuredContent!!["messages"] as JsonArray)
            ((msgs[0] as JsonObject)["code"] as JsonPrimitive).content shouldBe "rule_violation"
        }

        "missing target_dialect returns missing_required_field" {
            val tool =
                CompileTool(
                    cfg,
                    fakeTranslator(),
                    fakeValidator { ValidateResponse.getDefaultInstance() },
                )
            val args =
                buildJsonObject {
                    put("source", JsonPrimitive("SELECT *"))
                    put("source_language", JsonPrimitive("sql"))
                }
            val res =
                runBlocking {
                    tool.execute(
                        CallToolRequest(params = CallToolRequestParams(name = "compile", arguments = args)),
                        identity = null,
                    )
                }
            res.isError shouldBe true
            val msgs = (res.structuredContent!!["messages"] as JsonArray)
            ((msgs[0] as JsonObject)["code"] as JsonPrimitive).content shouldBe "missing_required_field"
        }

        "G7: warnings on the final context surface in pipelineWarnings" {
            val ctxWithWarning =
                PipelineContext
                    .newBuilder()
                    .addWarnings(
                        org.tatrman.plan.v1.Warning
                            .newBuilder()
                            .setCode("model_version_mismatch_minor")
                            .setMessage("model drift")
                            .setSourceService("validator")
                            .setSourceStage("schema_check")
                            .build(),
                    ).build()
            val translator =
                fakeTranslator(
                    parseImpl = {
                        ParseResponse
                            .newBuilder()
                            .setPlan(plan)
                            .setContext(ctxWithWarning)
                            .build()
                    },
                    unparseImpl = { req ->
                        UnparseResponse
                            .newBuilder()
                            .setOutput("SELECT 1")
                            .setContext(req.context)
                            .build()
                    },
                )
            val validator =
                fakeValidator { req ->
                    ValidateResponse
                        .newBuilder()
                        .setPlan(req.plan)
                        .setContext(req.context)
                        .build()
                }
            val tool = CompileTool(cfg, translator, validator)
            val args =
                buildJsonObject {
                    put("source", JsonPrimitive("SELECT 1"))
                    put("source_language", JsonPrimitive("sql"))
                    put("target_dialect", JsonPrimitive("mssql"))
                }
            val res =
                runBlocking {
                    tool.execute(
                        CallToolRequest(params = CallToolRequestParams(name = "compile", arguments = args)),
                        identity = null,
                    )
                }
            val warnings = (res.structuredContent!!["pipelineWarnings"] as JsonArray)
            warnings.size shouldBe 1
            ((warnings[0] as JsonObject)["code"] as JsonPrimitive).content shouldBe "model_version_mismatch_minor"
        }

        "unknown dialect returns unknown_target_dialect" {
            val tool =
                CompileTool(
                    cfg,
                    fakeTranslator(),
                    fakeValidator { ValidateResponse.getDefaultInstance() },
                )
            val args =
                buildJsonObject {
                    put("source", JsonPrimitive("SELECT *"))
                    put("source_language", JsonPrimitive("sql"))
                    put("target_dialect", JsonPrimitive("oracle"))
                }
            val res =
                runBlocking {
                    tool.execute(
                        CallToolRequest(params = CallToolRequestParams(name = "compile", arguments = args)),
                        identity = null,
                    )
                }
            res.isError shouldBe true
            val msgs = (res.structuredContent!!["messages"] as JsonArray)
            ((msgs[0] as JsonObject)["code"] as JsonPrimitive).content shouldBe "unknown_target_dialect"
        }

        "source_schema=er reaches ParseToRelNode as ER" {
            val captured = mutableListOf<ParseRequest>()
            val tool = CompileTool(cfg, capturingTranslator(captured), fakeValidator { req -> validated(req) })
            val res = runBlocking { tool.execute(compileRequest(sourceSchema = "er"), identity = null) }

            res.isError shouldBe false
            captured.size shouldBe 1
            captured[0].sourceSchema shouldBe SchemaCode.ER
            // The target schema is not what the argument changes.
            captured[0].targetSchema shouldBe SchemaCode.DB
        }

        // Absent must not mean "db": without the argument the request is the one compile has
        // always sent, so the translator still decides which catalog the source reads. A blank
        // value is read as absent.
        "absent or blank source_schema leaves the ParseRequest unchanged" {
            for (sourceSchema in listOf(null, "", "  ")) {
                val captured = mutableListOf<ParseRequest>()
                val tool = CompileTool(cfg, capturingTranslator(captured), fakeValidator { req -> validated(req) })
                val res = runBlocking { tool.execute(compileRequest(sourceSchema), identity = null) }

                res.isError shouldBe false
                captured.size shouldBe 1
                captured[0] shouldBe
                    ParseRequest
                        .newBuilder()
                        .setSource("SELECT * FROM Customers")
                        .setSourceLanguage(Language.SQL)
                        .setTargetSchema(SchemaCode.DB)
                        // The context carries a fresh correlation id per call; it is not under test.
                        .setContext(captured[0].context)
                        .build()
                captured[0].sourceSchema shouldBe SchemaCode.SCHEMA_CODE_UNSPECIFIED
            }
        }

        "unknown source_schema returns unknown_source_schema without calling the translator" {
            val captured = mutableListOf<ParseRequest>()
            val tool = CompileTool(cfg, capturingTranslator(captured), fakeValidator { req -> validated(req) })
            val res = runBlocking { tool.execute(compileRequest(sourceSchema = "physical"), identity = null) }

            res.isError shouldBe true
            ((res.structuredContent!!["ok"] as JsonPrimitive).content) shouldBe "false"
            val msgs = (res.structuredContent!!["messages"] as JsonArray)
            ((msgs[0] as JsonObject)["code"] as JsonPrimitive).content shouldBe "unknown_source_schema"
            ((msgs[0] as JsonObject)["text"] as JsonPrimitive).content shouldContain "'physical'"
            captured.size shouldBe 0
        }
    })
