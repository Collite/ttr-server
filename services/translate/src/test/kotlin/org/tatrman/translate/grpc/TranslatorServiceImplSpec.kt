// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translate.grpc

import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.ExplainRequest
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.ParseRequest
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translate.v1.TranslateRequest
import org.tatrman.translate.v1.UnparseRequest
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldContainIgnoringCase
import io.kotest.matchers.string.shouldNotContain
import org.tatrman.translate.model.BootFixtureModel
import org.tatrman.translate.model.StaticModelHandleProvider
import org.tatrman.translator.framework.EntityMapping
import org.tatrman.translator.framework.ModelAttribute
import org.tatrman.translator.framework.ModelColumn
import org.tatrman.translator.framework.ModelEntity
import org.tatrman.translator.framework.ModelForeignKey
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.translator.framework.ModelRelation
import org.tatrman.translator.framework.ModelSavedQuery
import org.tatrman.translator.framework.ModelTable
import org.tatrman.translator.framework.SavedQueryBody
import org.tatrman.translator.framework.SurfaceType

class TranslatorServiceImplSpec :
    StringSpec({

        val service = TranslatorServiceImpl(StaticModelHandleProvider(BootFixtureModel.handle()))

        "ParseToRelNode returns a populated PlanNode for valid SQL" {
            val resp =
                service.parseToRelNode(
                    ParseRequest
                        .newBuilder()
                        .setSource("SELECT id, name FROM qsubjekt")
                        .setSourceLanguage(Language.SQL)
                        .setTargetSchema(SchemaCode.DB)
                        .build(),
                )
            resp.hasPlan() shouldBe true
            resp.plan.hasProject() shouldBe true
            resp.messagesList shouldHaveSize 0
        }

        "ParseToRelNode resolves ER columns containing Czech diacritics" {
            // Repro for IRL bug: WHERE-clause column `název_produktu` is rejected even
            // though the `produkt` ER entity declares it as an attribute. Mirrors the
            // user's actual payload against the model's `produkt` entity (bt02_artikl.yaml).
            val produktModel = buildProduktErModel()
            val produktService = TranslatorServiceImpl(StaticModelHandleProvider(produktModel))
            val resp =
                produktService.parseToRelNode(
                    ParseRequest
                        .newBuilder()
                        .setSource(
                            "SELECT id_produktu, kód_produktu FROM produkt WHERE název_produktu LIKE 'A%'",
                        ).setSourceLanguage(Language.SQL)
                        .setTargetSchema(SchemaCode.ER)
                        .build(),
                )
            resp.messagesList shouldHaveSize 0
            resp.hasPlan() shouldBe true
        }

        "End-to-end parse+unparse: ER SQL → DB SQL via the produkt model" {
            // Closes the loop: parseToRelNode produces a TableScan with name=DB / alias=ER /
            // type=DB-type, then unparseFromRelNode walks that PlanNode → RelNode → SQL.
            // Verifies the alias-at-boundary design actually round-trips through the decoder
            // (the concern flagged in the heads-up — Calcite's RelNode decoder must honour
            // the `alias` field on TableScan.output_columns).
            val produktModel = buildProduktErModel()
            val produktService = TranslatorServiceImpl(StaticModelHandleProvider(produktModel))
            val parseResp =
                produktService.parseToRelNode(
                    ParseRequest
                        .newBuilder()
                        .setSource(
                            "SELECT id_produktu, kód_produktu FROM produkt WHERE název_produktu LIKE 'A%'",
                        ).setSourceLanguage(Language.SQL)
                        .setSourceSchema(SchemaCode.ER)
                        .setTargetSchema(SchemaCode.DB)
                        .build(),
                )
            parseResp.messagesList shouldHaveSize 0
            parseResp.hasPlan() shouldBe true

            val unparseResp =
                produktService.unparseFromRelNode(
                    UnparseRequest
                        .newBuilder()
                        .setPlan(parseResp.plan)
                        .setTargetLanguage(Language.SQL)
                        .setTargetDialect(SqlDialect.MSSQL)
                        .build(),
                )
            unparseResp.messagesList shouldHaveSize 0
            // Generated MSSQL (verified manually, asserted in pieces below):
            //   SELECT [id_produktu], [kód_produktu]
            //   FROM (SELECT [IDSKUPZBOZI] AS [id_produktu],
            //                [KOD_SKUP_ZBOZI] AS [kód_produktu],
            //                [NAZEV_SKUP_ZBOZI] AS [název_produktu]
            //         FROM [dbo].[QSKUPZBOZI_DF]) AS [t]
            //   WHERE [název_produktu] LIKE 'A%'
            // Issue #57 Phase A — no `[db].` virtual prefix in the emitted SQL.
            unparseResp.output.shouldContainIgnoringCase("QSKUPZBOZI_DF")
            unparseResp.output.shouldContainIgnoringCase("IDSKUPZBOZI")
            unparseResp.output.shouldContainIgnoringCase("AS [id_produktu]")
            unparseResp.output.shouldContainIgnoringCase("AS [název_produktu]")
            unparseResp.output.shouldContainIgnoringCase("WHERE [název_produktu]")
            unparseResp.output shouldNotContain "[db]."
        }

        "End-to-end parse+unparse: ER SQL → PostgreSQL via the produkt model (the Postgres contract)" {
            // The Postgres worker (Postgres) calls UnparseFromRelNode with SqlDialect.POSTGRESQL.
            // Pins that contract: the same PlanNode that renders to MSSQL above must render to
            // valid PostgreSQL — double-quoted identifiers, no MSSQL brackets, no `[db].` prefix.
            // (docs/implementation/v1/postgres/plan.md Stage 1.2 T1 — Translate PG-unparse audit.)
            val produktModel = buildProduktErModel()
            val produktService = TranslatorServiceImpl(StaticModelHandleProvider(produktModel))
            val parseResp =
                produktService.parseToRelNode(
                    ParseRequest
                        .newBuilder()
                        .setSource(
                            "SELECT id_produktu, kód_produktu FROM produkt WHERE název_produktu LIKE 'A%'",
                        ).setSourceLanguage(Language.SQL)
                        .setSourceSchema(SchemaCode.ER)
                        .setTargetSchema(SchemaCode.DB)
                        .build(),
                )
            parseResp.messagesList shouldHaveSize 0
            parseResp.hasPlan() shouldBe true

            val unparseResp =
                produktService.unparseFromRelNode(
                    UnparseRequest
                        .newBuilder()
                        .setPlan(parseResp.plan)
                        .setTargetLanguage(Language.SQL)
                        .setTargetDialect(SqlDialect.POSTGRESQL)
                        .build(),
                )
            unparseResp.messagesList shouldHaveSize 0
            // Postgres dialect → double-quoted identifiers, never MSSQL square brackets.
            unparseResp.output.shouldContainIgnoringCase("QSKUPZBOZI_DF")
            unparseResp.output.shouldContain("\"id_produktu\"")
            unparseResp.output.shouldContain("\"název_produktu\"")
            unparseResp.output shouldNotContain "[id_produktu]"
            unparseResp.output shouldNotContain "[db]."
        }

        "ParseToRelNode + source_schema=ER + target_schema=DB → TableScan carries DB cols + ER aliases + DB types" {
            // DF-T05 v1.x — alias-at-boundary design:
            //   - TableScan.output_columns: name=DB column, alias=ER attribute, type=real DB type
            //   - Filter/Project ColumnRefs stay in the ER vocabulary (resolve via the alias)
            // This matches the worker's expectations: it sees ER-named references upstream,
            // and the TableScan emits a SELECT-with-aliases against the physical table.
            val produktModel = buildProduktErModel()
            val produktService = TranslatorServiceImpl(StaticModelHandleProvider(produktModel))
            val resp =
                produktService.parseToRelNode(
                    ParseRequest
                        .newBuilder()
                        .setSource(
                            "SELECT id_produktu, kód_produktu FROM produkt WHERE název_produktu LIKE 'A%'",
                        ).setSourceLanguage(Language.SQL)
                        .setSourceSchema(SchemaCode.ER)
                        .setTargetSchema(SchemaCode.DB)
                        .build(),
                )
            resp.messagesList shouldHaveSize 0
            resp.hasPlan() shouldBe true

            // Drill: Project → Filter → TableScan
            val filterNode = resp.plan.project.input
            val tableScanNode = filterNode.filter.input
            tableScanNode.nodeCase shouldBe PlanNode.NodeCase.TABLE_SCAN
            tableScanNode.tableScan.table.name shouldBe "QSKUPZBOZI_DF"
            tableScanNode.tableScan.table.schemaCode shouldBe SchemaCode.DB

            // TableScan.output_columns: keyed by alias (= ER attribute name)
            val byAlias = tableScanNode.tableScan.outputColumnsList.associateBy { it.alias }
            (byAlias["id_produktu"]?.name) shouldBe "IDSKUPZBOZI"
            (byAlias["id_produktu"]?.type) shouldBe "int"
            (byAlias["kód_produktu"]?.name) shouldBe "KOD_SKUP_ZBOZI"
            (byAlias["kód_produktu"]?.type) shouldBe "text"
            (byAlias["název_produktu"]?.name) shouldBe "NAZEV_SKUP_ZBOZI"
            (byAlias["název_produktu"]?.type) shouldBe "text"

            // Filter ColumnRef stays in ER vocabulary (resolves via TableScan's alias).
            filterNode.filter.condition.function.operandsList[0]
                .columnRef.name shouldBe "název_produktu"

            // Project expressions stay in ER vocabulary; aliases preserved.
            resp.plan.project.expressionsList.map {
                it.expression.columnRef.name
            } shouldBe listOf("id_produktu", "kód_produktu")
            resp.plan.project.expressionsList
                .map { it.alias } shouldBe
                listOf("id_produktu", "kód_produktu")
        }

        "ParseToRelNode handles source_schema=ER + target_schema=DB (full ER→DB pipeline)" {
            // Verifies the split: SQL uses ER identifiers (parsed against the ER catalog),
            // pipeline continues into MapToPhysical so the output scans reference DB tables.
            // BootFixtureModel's `customer` → `QSUBJEKT`; attribute names match column names
            // (id, name) — isolates the source_schema concern from the v1 attribute-rename gap.
            val resp =
                service.parseToRelNode(
                    ParseRequest
                        .newBuilder()
                        .setSource("SELECT id, name FROM customer WHERE name LIKE 'A%'")
                        .setSourceLanguage(Language.SQL)
                        .setSourceSchema(SchemaCode.ER)
                        .setTargetSchema(SchemaCode.DB)
                        .build(),
                )
            resp.messagesList shouldHaveSize 0
            resp.hasPlan() shouldBe true
            // After MapToPhysical, every scan in the plan must reference DB, not ER.
            scanSchemaCodes(resp.plan).all { it == SchemaCode.DB } shouldBe true
        }

        "ParseToRelNode with explicit source_schema=ER + target_schema=ER stops before MapToPhysical" {
            // Locks in the symmetric form of the source/target split: explicitly setting both
            // to ER must behave the same as the default-fallback ER case — Calcite parses against
            // the ER catalog AND the pipeline stops after JoinerLogical (Scan(ER) survives in the
            // output). Guards against any drift between the explicit-ER path and the legacy path.
            val resp =
                service.parseToRelNode(
                    ParseRequest
                        .newBuilder()
                        .setSource("SELECT id, name FROM customer")
                        .setSourceLanguage(Language.SQL)
                        .setSourceSchema(SchemaCode.ER)
                        .setTargetSchema(SchemaCode.ER)
                        .build(),
                )
            resp.messagesList shouldHaveSize 0
            resp.hasPlan() shouldBe true
            // ER target → MapToPhysical is skipped, so the Scan stays ER-side.
            scanSchemaCodes(resp.plan).all { it == SchemaCode.ER } shouldBe true
        }

        "ParseToRelNode resolves ER-side identifiers when target_schema = ER" {
            // Repro for IRL bug: SELECT against an ER entity name (lowercase, ER-side)
            // with target_schema = ER must parse successfully — Calcite's default schema
            // must follow target_schema, not stay pinned to db.dbo.
            // BootFixtureModel exposes `customer` as the ER entity (mapped to DB table QSUBJEKT).
            val resp =
                service.parseToRelNode(
                    ParseRequest
                        .newBuilder()
                        .setSource("SELECT id, name FROM customer")
                        .setSourceLanguage(Language.SQL)
                        .setTargetSchema(SchemaCode.ER)
                        .build(),
                )
            resp.messagesList shouldHaveSize 0
            resp.hasPlan() shouldBe true
        }

        "ParseToRelNode surfaces validation_failed via messages, NOT gRPC error" {
            val resp =
                service.parseToRelNode(
                    ParseRequest
                        .newBuilder()
                        .setSource("SELECT does_not_exist FROM qsubjekt")
                        .setSourceLanguage(Language.SQL)
                        .setTargetSchema(SchemaCode.DB)
                        .build(),
                )
            resp.messagesList shouldHaveSize 1
            resp.messagesList[0].code shouldBe "validation_failed"
        }

        "UnparseFromRelNode produces SQL for a valid PlanNode" {
            val parseResp =
                service.parseToRelNode(
                    ParseRequest
                        .newBuilder()
                        .setSource("SELECT id FROM qsubjekt")
                        .setSourceLanguage(Language.SQL)
                        .setTargetSchema(SchemaCode.DB)
                        .build(),
                )
            val resp =
                service.unparseFromRelNode(
                    UnparseRequest
                        .newBuilder()
                        .setPlan(parseResp.plan)
                        .setTargetLanguage(Language.SQL)
                        .setTargetDialect(SqlDialect.MSSQL)
                        .build(),
                )
            resp.output.shouldContainIgnoringCase("qsubjekt")
            resp.messagesList shouldHaveSize 0
        }

        "Translate end-to-end SQL to MSSQL SQL" {
            val resp =
                service.translate(
                    TranslateRequest
                        .newBuilder()
                        .setSource("SELECT id FROM qsubjekt")
                        .setSourceLanguage(Language.SQL)
                        .setTargetLanguage(Language.SQL)
                        .setTargetSchema(SchemaCode.DB)
                        .setTargetDialect(SqlDialect.MSSQL)
                        .build(),
                )
            resp.output.shouldContainIgnoringCase("qsubjekt")
            resp.messagesList shouldHaveSize 0
        }

        "Translate from an invalid TransDSL source reports a structured parse error" {
            // TransDSL is a recognised source language now; an unparseable source yields a
            // structured parse-failure code rather than a "language not supported" message.
            val resp =
                service.translate(
                    TranslateRequest
                        .newBuilder()
                        .setSource("{ ... }")
                        .setSourceLanguage(Language.TRANSFORMATION_DSL)
                        .setTargetLanguage(Language.SQL)
                        .setTargetDialect(SqlDialect.MSSQL)
                        .build(),
                )
            resp.messagesList shouldHaveSize 1
            resp.messagesList[0].code shouldBe "transdsl_parse_failed"
        }

        "Translate from LANGUAGE_UNSPECIFIED reports a structured error" {
            val resp =
                service.translate(
                    TranslateRequest
                        .newBuilder()
                        .setSource("anything")
                        .setSourceLanguage(Language.LANGUAGE_UNSPECIFIED)
                        .setTargetLanguage(Language.SQL)
                        .setTargetDialect(SqlDialect.MSSQL)
                        .build(),
                )
            resp.messagesList shouldHaveSize 1
            resp.messagesList[0].code shouldBe "language_unspecified"
        }

        "Explain captures stages for a SQL parse" {
            val resp =
                service.explain(
                    ExplainRequest
                        .newBuilder()
                        .setParse(
                            ParseRequest
                                .newBuilder()
                                .setSource("SELECT id FROM qsubjekt")
                                .setSourceLanguage(Language.SQL)
                                .setTargetSchema(SchemaCode.DB),
                        ).build(),
                )
            // ttr-translator 0.11.1 (MJ) added `model_joins` — the statement after the bare-join
            // rewrite, or "(no bare joins)" — between the parse and the unparse.
            resp.stagesList.map { it.stageCode } shouldBe
                listOf("parse_and_to_rel", "model_joins", "optimize_and_unparse")
        }

        "Explain rejects an empty request with a structured message" {
            val resp = service.explain(ExplainRequest.newBuilder().build())
            resp.messagesList shouldHaveSize 1
            resp.messagesList[0].code shouldBe "explain_request_empty"
        }

        // ---- T7 parameter-bridge restoration (master-plan §7) ---------------------------
        // The gRPC edge must thread `context.parameters` into parse (so `{name}` → `?`) and
        // emit the positional binding list on unparse (so Mssql binds JDBC positions 1:1).

        "ParseToRelNode + UnparseFromRelNode round-trip a named {name} via context.parameters" {
            val context =
                org.tatrman.plan.v1.PipelineContext
                    .newBuilder()
                    .addParameters(
                        org.tatrman.plan.v1.ParameterBinding
                            .newBuilder()
                            .setName("q")
                            .setType("text")
                            .setValue(
                                org.tatrman.plan.v1.Value
                                    .newBuilder()
                                    .setStringValue("DF"),
                            ),
                    ).build()
            // Parse: `{q}` must be rewritten — no opaque syntax error, a plan is produced.
            val parsed =
                service.parseToRelNode(
                    ParseRequest
                        .newBuilder()
                        .setSource("SELECT id, name FROM qsubjekt WHERE name = {q} OR name = {q}")
                        .setSourceLanguage(Language.SQL)
                        .setTargetSchema(SchemaCode.DB)
                        .setContext(context)
                        .build(),
                )
            parsed.messagesList shouldHaveSize 0
            parsed.hasPlan() shouldBe true
            // Unparse: the `?` placeholders come back with a positional binding per `?`.
            val unparsed =
                service.unparseFromRelNode(
                    UnparseRequest
                        .newBuilder()
                        .setPlan(parsed.plan)
                        .setTargetLanguage(Language.SQL)
                        .setTargetDialect(SqlDialect.MSSQL)
                        .setContext(context)
                        .build(),
                )
            unparsed.messagesList shouldHaveSize 0
            unparsed.output shouldContain "?"
            // One named binding → two `?` positions → two positional bindings (repeats expanded).
            unparsed.context.parametersList shouldHaveSize 2
            unparsed.context.parametersList.all {
                it.name == "q" && it.value.stringValue == "DF"
            } shouldBe true
        }

        "ParseToRelNode reports parameter_unknown when context.parameters lacks a {name}" {
            val context =
                org.tatrman.plan.v1.PipelineContext
                    .newBuilder()
                    .addParameters(
                        org.tatrman.plan.v1.ParameterBinding
                            .newBuilder()
                            .setName("other")
                            .setType("text")
                            .setValue(
                                org.tatrman.plan.v1.Value
                                    .newBuilder()
                                    .setStringValue("x"),
                            ),
                    ).build()
            val resp =
                service.parseToRelNode(
                    ParseRequest
                        .newBuilder()
                        .setSource("SELECT id FROM qsubjekt WHERE name = {q}")
                        .setSourceLanguage(Language.SQL)
                        .setTargetSchema(SchemaCode.DB)
                        .setContext(context)
                        .build(),
                )
            resp.messagesList shouldHaveSize 1
            resp.messagesList[0].code shouldBe "parameter_unknown"
        }
    })

/** Collect the schemaCode of every Scan node in the plan, depth-first. */
private fun scanSchemaCodes(plan: PlanNode): List<SchemaCode> {
    val out = mutableListOf<SchemaCode>()

    fun walk(n: PlanNode) {
        when (n.nodeCase) {
            PlanNode.NodeCase.SCAN -> out += n.scan.getObject().schemaCode
            PlanNode.NodeCase.PROJECT -> walk(n.project.input)
            PlanNode.NodeCase.FILTER -> walk(n.filter.input)
            PlanNode.NodeCase.JOIN -> {
                walk(n.join.left)
                walk(n.join.right)
            }
            PlanNode.NodeCase.AGGREGATE -> walk(n.aggregate.input)
            PlanNode.NodeCase.SORT -> walk(n.sort.input)
            PlanNode.NodeCase.LIMIT_OFFSET -> walk(n.limitOffset.input)
            PlanNode.NodeCase.SUBQUERY -> walk(n.subquery.subquery)
            else -> Unit
        }
    }
    walk(plan)
    return out
}

private fun qname(
    schema: SchemaCode,
    ns: String,
    name: String,
): QualifiedName =
    QualifiedName
        .newBuilder()
        .setSchemaCode(schema)
        .setNamespace(ns)
        .setName(name)
        .build()

/**
 * Minimal model mirroring the production `produkt` entity (bt02_artikl.yaml) — Czech
 * ER attribute names with diacritics, backed by a synthetic DB table so the ER↔DB
 * mapping is wired (not used by parse-only assertions but kept consistent).
 */
private fun buildProduktErModel(): ModelHandle {
    val tableQn = qname(SchemaCode.DB, "dbo", "QSKUPZBOZI_DF")
    val table =
        ModelTable(
            qname = tableQn,
            columns =
                listOf(
                    ModelColumn("IDSKUPZBOZI", SurfaceType.INT, nullable = false),
                    ModelColumn("KOD_SKUP_ZBOZI", SurfaceType.TEXT, nullable = true),
                    ModelColumn("NAZEV_SKUP_ZBOZI", SurfaceType.TEXT, nullable = true),
                ),
            primaryKey = listOf("IDSKUPZBOZI"),
        )
    val entityQn = qname(SchemaCode.ER, "entity", "produkt")
    val entity =
        ModelEntity(
            qname = entityQn,
            attributes =
                listOf(
                    ModelAttribute("id_produktu", SurfaceType.INT, nullable = false, isKey = true),
                    ModelAttribute("kód_produktu", SurfaceType.TEXT, nullable = true),
                    ModelAttribute("název_produktu", SurfaceType.TEXT, nullable = true),
                ),
        )
    return object : ModelHandle {
        override fun tables(
            schemaCode: SchemaCode,
            namespace: String,
        ): Map<QualifiedName, ModelTable> =
            if (schemaCode == SchemaCode.DB && namespace == "dbo") mapOf(tableQn to table) else emptyMap()

        override fun columns(tableQname: QualifiedName): List<ModelColumn> =
            if (tableQname == tableQn) table.columns else emptyList()

        override fun foreignKeys(): List<ModelForeignKey> = emptyList()

        override fun entities(
            schemaCode: SchemaCode,
            namespace: String,
        ): Map<QualifiedName, ModelEntity> =
            if (schemaCode == SchemaCode.ER && namespace == "entity") mapOf(entityQn to entity) else emptyMap()

        override fun attributes(entityQname: QualifiedName): List<ModelAttribute> =
            if (entityQname == entityQn) entity.attributes else emptyList()

        override fun relations(): List<ModelRelation> = emptyList()

        override fun entityMapping(entityQname: QualifiedName): EntityMapping? =
            if (entityQname == entityQn) EntityMapping.ToTable(table = tableQn, whereFilter = null) else null

        override fun attributeColumnRenames(entityQname: QualifiedName): Map<String, String> =
            if (entityQname == entityQn) {
                mapOf(
                    "id_produktu" to "IDSKUPZBOZI",
                    "kód_produktu" to "KOD_SKUP_ZBOZI",
                    "název_produktu" to "NAZEV_SKUP_ZBOZI",
                )
            } else {
                emptyMap()
            }

        override fun savedQueries(
            schemaCode: SchemaCode,
            namespace: String,
        ): Map<QualifiedName, ModelSavedQuery> = emptyMap()

        override fun savedQueryBody(queryQname: QualifiedName): SavedQueryBody = error("no saved queries")

        override fun currentVersion(): String = "produkt-test-v0"

        override fun namespaces(schemaCode: SchemaCode): Set<String> =
            when (schemaCode) {
                SchemaCode.DB -> setOf("dbo")
                SchemaCode.ER -> setOf("entity")
                else -> emptySet()
            }
    }
}
