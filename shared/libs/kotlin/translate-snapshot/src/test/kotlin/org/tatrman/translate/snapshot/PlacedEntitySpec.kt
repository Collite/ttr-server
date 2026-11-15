// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translate.snapshot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.string.shouldContain
import org.tatrman.meta.v1.DbTableDetail
import org.tatrman.meta.v1.DbColumnSummary
import org.tatrman.meta.v1.AttributeDetail
import org.tatrman.meta.v1.EntityDetail
import org.tatrman.meta.v1.Er2DbEntityMappingDetail
import org.tatrman.meta.v1.ModelSnapshot
import org.tatrman.meta.v1.ObjectDescriptor
import org.tatrman.meta.v1.ObjectEntry
import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.ColumnValue
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FunctionCall
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.RowWrite
import org.tatrman.plan.v1.RowWriteOp
import org.tatrman.plan.v1.RowWrites
import org.tatrman.plan.v1.ScanNode
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.Literal
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translator.detect.SchemaDetector
import org.tatrman.translator.detect.SchemaDecision
import org.tatrman.translator.framework.EntityMapping
import org.tatrman.translator.framework.ModelColumn
import org.tatrman.translator.framework.SurfaceType
import org.tatrman.translator.orchestrator.Translator
import org.tatrman.translator.orchestrator.UnparseResult
import org.tatrman.translator.schema.MapToPhysical
import org.tatrman.translator.schema.MapToPhysicalResult

private const val GENERATED = "hartland.automation.generated"

class PlacedEntitySpec :
    StringSpec({
        "package and named entity placement synthesize ordered columns and composite keys under any namespace" {
            val snapshot = placedSnapshot()
            val handle =
                SnapshotModelHandle.from(
                    snapshot,
                    placedPackages = setOf(GENERATED),
                    placedEntities = setOf("obligation"),
                )
            for (name in listOf("release_order_verdict", "obligation")) {
                val qn = placedQname(SchemaCode.DB, "", name)
                handle.entityMapping(placedQname(SchemaCode.ER, "entity", name)) shouldBe EntityMapping.ToTable(qn)
                val columns =
                    listOf(
                        ModelColumn("run", SurfaceType.TEXT, false),
                        ModelColumn("key", SurfaceType.INT, false),
                        ModelColumn("amount", SurfaceType.FLOAT, true),
                    )
                for (ns in listOf("", "cz", "public", "another_schema")) {
                    val target = qn.toBuilder().setNamespace(ns).build()
                    handle.columns(target) shouldBe columns
                    val table = handle.tables(SchemaCode.DB, ns).getValue(target)
                    table.qname shouldBe target
                    table.columns shouldBe columns
                    table.primaryKey shouldBe listOf("run", "key")
                }
                handle.attributeColumnRenames(placedQname(SchemaCode.ER, "entity", name)) shouldBe emptyMap()
                handle.tables(SchemaCode.ER, "cz") shouldBe emptyMap()
                handle.columns(placedQname(SchemaCode.ER, "cz", name)) shouldBe emptyList()
            }
        }

        for (namespace in listOf("cz", "CZ")) {
            for (primaryKey in listOf(listOf("real_id"), emptyList())) {
                "real table in $namespace with key $primaryKey wins over same-name placement" {
                    val realName = if (namespace == "cz") "obligation" else "Obligation"
                    val real = placedQname(SchemaCode.DB, namespace, realName)
                    val snapshot =
                        placedSnapshot()
                            .toBuilder()
                            .addObjects(
                                ObjectEntry
                                    .newBuilder()
                                    .setObjectDescriptor(ObjectDescriptor.newBuilder().setQualifiedName(real))
                                    .setTable(
                                        DbTableDetail
                                            .newBuilder()
                                            .addColumns(
                                                DbColumnSummary.newBuilder().setName("real_id").setDataType("int"),
                                            ).addColumns(
                                                DbColumnSummary.newBuilder().setName("real_x").setDataType("text"),
                                            ).addAllPrimaryKey(primaryKey),
                                    ),
                            ).build()
                    val handle = SnapshotModelHandle.from(snapshot, placedEntities = setOf("obligation"))
                    val tables = handle.tables(SchemaCode.DB, "cz")
                    tables.keys shouldBe setOf(real)
                    tables.getValue(real).columns.map { it.name } shouldBe listOf("real_id", "real_x")
                    tables.getValue(real).columns shouldBe handle.columns(real)
                    tables.getValue(real).primaryKey shouldBe primaryKey
                    val placed = placedQname(SchemaCode.DB, "public", "obligation")
                    handle.tables(SchemaCode.DB, "public").getValue(placed).primaryKey shouldBe listOf("run", "key")
                }
            }
        }

        "duplicate placed names fail closed for every owner while unique names still map" {
            val base = placedSnapshot()
            val duplicateEntries =
                base.objectsList
                    .filter {
                        it.objectDescriptor.qualifiedName.name == "obligation" ||
                            it.objectDescriptor.qualifiedName.name
                                .startsWith("obligation.")
                    }.map { entry ->
                        entry
                            .toBuilder()
                            .apply {
                                objectDescriptor =
                                    entry.objectDescriptor
                                        .toBuilder()
                                        .setQualifiedName(
                                            entry.objectDescriptor.qualifiedName
                                                .toBuilder()
                                                .setNamespace("automation"),
                                        ).build()
                                if (entry.hasAttribute()) {
                                    attribute =
                                        entry.attribute
                                            .toBuilder()
                                            .setEntity(
                                                entry.attribute.entity
                                                    .toBuilder()
                                                    .setNamespace("automation"),
                                            ).build()
                                }
                            }.build()
                    }
            val handle =
                SnapshotModelHandle.from(
                    base.toBuilder().addAllObjects(duplicateEntries).build(),
                    placedPackages = setOf(GENERATED),
                    placedEntities = setOf("obligation"),
                )
            for (namespace in listOf("entity", "automation")) {
                val entity = placedQname(SchemaCode.ER, namespace, "obligation")
                handle.entityMapping(entity) shouldBe null
                val original = rowPlan("obligation")
                val plan =
                    original
                        .toBuilder()
                        .setRowWrites(
                            original.rowWrites.toBuilder().setRows(
                                0,
                                original.rowWrites
                                    .getRows(0)
                                    .toBuilder()
                                    .setTarget(entity),
                            ),
                        ).build()
                MapToPhysical
                    .apply(plan, handle)
                    .shouldBeInstanceOf<MapToPhysicalResult.Failure>()
                    .code shouldBe "entity_unmapped"
            }
            handle.tables(SchemaCode.DB, "cz").keys.map { it.name } shouldBe listOf("release_order_verdict")
            MapToPhysical
                .apply(rowPlan("release_order_verdict"), handle)
                .shouldBeInstanceOf<MapToPhysicalResult.Success>()
        }

        "placed SQL names require the explicit ER lane while unplaced names autodetect ER" {
            for (placed in listOf(false, true)) {
                val handle =
                    SnapshotModelHandle.from(
                        placedSnapshot(),
                        placedEntities = if (placed) setOf("obligation") else emptySet(),
                    )
                for (stated in listOf(SchemaCode.SCHEMA_CODE_UNSPECIFIED, SchemaCode.ER)) {
                    val result =
                        SchemaDetector.detect(
                            "SELECT run, amount FROM obligation",
                            Language.SQL,
                            stated,
                            handle,
                        )
                    result.decision shouldBe
                        when {
                            stated == SchemaCode.ER -> SchemaDecision.CONFIRMED
                            placed -> SchemaDecision.AMBIGUOUS
                            else -> SchemaDecision.AUTODETECTED
                        }
                    if (!placed || stated == SchemaCode.ER) result.effectiveSchema shouldBe SchemaCode.ER
                }
            }
        }

        "unlisted unbound entities stay unmapped regardless of source filename" {
            val handle =
                SnapshotModelHandle.from(
                    placedSnapshot(),
                    placedPackages = setOf(GENERATED),
                    placedEntities = setOf("obligation"),
                )
            handle.entityMapping(placedQname(SchemaCode.ER, "entity", "sales")) shouldBe null
            val result = MapToPhysical.apply(rowPlan("sales"), handle).shouldBeInstanceOf<MapToPhysicalResult.Failure>()
            result.code shouldBe "entity_unmapped"
        }

        "every explicit binding wins and both lists empty preserve the existing catalog" {
            val base = placedSnapshot()
            val er = placedQname(SchemaCode.ER, "entity", "release_order_verdict")
            val db = placedQname(SchemaCode.DB, "dbo", "existing")
            for (kind in listOf("table", "view", "query", "unset")) {
                val binding =
                    Er2DbEntityMappingDetail.newBuilder().setEntity(er).apply {
                        when (kind) {
                            "table" -> setTable(db)
                            "view" -> setView(db)
                            "query" -> setSqlQuery(placedQname(SchemaCode.OBJ, "query", "existing"))
                        }
                    }
                val snapshot =
                    base
                        .toBuilder()
                        .addObjects(
                            ObjectEntry.newBuilder().setEr2DbEntityMapping(binding),
                        ).build()
                val plain = SnapshotModelHandle.from(snapshot)
                val placed = SnapshotModelHandle.from(snapshot, placedPackages = setOf(GENERATED))
                placed.entityMapping(er) shouldBe plain.entityMapping(er)
                placed.tables(SchemaCode.DB, "cz") shouldBe plain.tables(SchemaCode.DB, "cz")
            }
            val plain = SnapshotModelHandle.from(base)
            val empty = SnapshotModelHandle.from(base, placedPackages = emptySet(), placedEntities = emptySet())
            empty.namespaces(SchemaCode.DB) shouldBe plain.namespaces(SchemaCode.DB)
            empty.namespaces(SchemaCode.ER) shouldBe plain.namespaces(SchemaCode.ER)
            empty.entities(SchemaCode.ER, "entity") shouldBe plain.entities(SchemaCode.ER, "entity")
            empty.entityMapping(er) shouldBe null
            empty.columns(placedQname(SchemaCode.DB, "", "release_order_verdict")) shouldBe emptyList()
        }

        "the pinned translator lowers a placed row write and unparses its key after dispatch fills cz" {
            val handle = SnapshotModelHandle.from(placedSnapshot(), placedPackages = setOf(GENERATED))
            val lowered =
                MapToPhysical
                    .apply(rowPlan("release_order_verdict"), handle)
                    .shouldBeInstanceOf<MapToPhysicalResult.Success>()
                    .plan
            val row = lowered.rowWrites.rowsList.single()
            row.target shouldBe placedQname(SchemaCode.DB, "", "release_order_verdict")
            row.valuesList.map { it.name } shouldBe listOf("run", "key", "amount")

            // UPDATE with read_before requires both PK columns from ModelHandle, unlike an INSERT.
            fun eq(
                name: String,
                value: Literal,
            ) = Expression
                .newBuilder()
                .setFunction(
                    FunctionCall
                        .newBuilder()
                        .setOperation("eq")
                        .addOperands(Expression.newBuilder().setColumnRef(ColumnRef.newBuilder().setName(name)))
                        .addOperands(Expression.newBuilder().setLiteral(value)),
                ).build()
            val update =
                row
                    .toBuilder()
                    .setTarget(row.target.toBuilder().setNamespace("cz"))
                    .setOp(RowWriteOp.UPDATE)
                    .setIfAbsent(false)
                    .clearValues()
                    .addValues(row.getValues(2))
                    .setWhere(
                        Expression.newBuilder().setFunction(
                            FunctionCall
                                .newBuilder()
                                .setOperation("and")
                                .addOperands(
                                    eq(
                                        "run",
                                        Literal
                                            .newBuilder()
                                            .setStringValue("run-1")
                                            .setType("text")
                                            .build(),
                                    ),
                                ).addOperands(
                                    eq(
                                        "key",
                                        Literal
                                            .newBuilder()
                                            .setIntValue(1)
                                            .setType("int")
                                            .build(),
                                    ),
                                ),
                        ),
                    ).addReadBefore("amount")
            val concrete =
                lowered
                    .toBuilder()
                    .setRowWrites(
                        lowered.rowWrites.toBuilder().setRows(0, update),
                    ).build()
            val result =
                Translator(handle)
                    .unparseFromRelNode(concrete, Language.SQL, SqlDialect.POSTGRESQL)
                    .shouldBeInstanceOf<UnparseResult.Success>()
            result.output shouldContain "release_order_verdict"
            result.output shouldContain "FOR UPDATE"
            result.output shouldContain "_rw_target.\"run\" = _rw_prior.\"run\""
            result.output shouldContain "_rw_target.\"key\" = _rw_prior.\"key\""
        }

        for (namespace in listOf("", "cz", "public")) {
            "the pinned translator unparses a placed obligation read in namespace '$namespace'" {
                val handle = SnapshotModelHandle.from(placedSnapshot(), placedEntities = setOf("obligation"))
                val scan =
                    PlanNode
                        .newBuilder()
                        .setScan(
                            ScanNode
                                .newBuilder()
                                .setObject(placedQname(SchemaCode.ER, "entity", "obligation"))
                                .addOutputColumns(ColumnRef.newBuilder().setName("run").setType("text")),
                        ).build()
                val lowered = MapToPhysical.apply(scan, handle).shouldBeInstanceOf<MapToPhysicalResult.Success>().plan
                lowered.nodeCase shouldBe PlanNode.NodeCase.TABLE_SCAN
                val concrete =
                    lowered
                        .toBuilder()
                        .setTableScan(
                            lowered.tableScan.toBuilder().setTable(
                                lowered.tableScan.table
                                    .toBuilder()
                                    .setNamespace(namespace),
                            ),
                        ).build()
                val view = withPlanNamespaces(handle, concrete)
                val result = Translator(view).unparseFromRelNode(concrete, Language.SQL, SqlDialect.POSTGRESQL)
                if (namespace.isEmpty()) {
                    result.shouldBeInstanceOf<UnparseResult.Failure>().code shouldBe "sql_unparse_failed"
                } else {
                    result.shouldBeInstanceOf<UnparseResult.Success>().output shouldContain "\"obligation\""
                    view.namespaces(SchemaCode.DB) shouldBe handle.namespaces(SchemaCode.DB) + namespace
                    handle.namespaces(SchemaCode.DB) shouldBe setOf("")
                    view.entities(SchemaCode.ER, "entity") shouldBe handle.entities(SchemaCode.ER, "entity")
                }
            }
        }
    })

private fun placedQname(
    code: SchemaCode,
    ns: String,
    name: String,
): QualifiedName =
    QualifiedName
        .newBuilder()
        .setSchemaCode(code)
        .setNamespace(ns)
        .setName(name)
        .build()

private fun placedSnapshot(): ModelSnapshot {
    val snapshot = ModelSnapshot.newBuilder()
    for ((name, pkg) in listOf("release_order_verdict" to GENERATED, "obligation" to "", "sales" to "hartland")) {
        val er = placedQname(SchemaCode.ER, "entity", name)
        snapshot.addObjects(
            ObjectEntry
                .newBuilder()
                .setObjectDescriptor(
                    ObjectDescriptor
                        .newBuilder()
                        .setQualifiedName(er)
                        .setLocalName(name)
                        .setPackageName(pkg)
                        .setSourceFile("/hartland/automation/generated/catalog.ttrm"),
                ).setEntity(EntityDetail.getDefaultInstance()),
        )
        for ((attr, type, key) in listOf(
            Triple("run", "text", true),
            Triple("key", "int", true),
            Triple("amount", "decimal", false),
        )) {
            snapshot.addObjects(
                ObjectEntry
                    .newBuilder()
                    .setObjectDescriptor(
                        ObjectDescriptor
                            .newBuilder()
                            .setQualifiedName(placedQname(SchemaCode.ER, "entity", "$name.$attr"))
                            .setLocalName("$name.$attr"),
                    ).setAttribute(
                        AttributeDetail
                            .newBuilder()
                            .setEntity(er)
                            .setType(type)
                            .setIsKey(key)
                            .setNullable(!key),
                    ),
            )
        }
    }
    return snapshot.build()
}

private fun rowPlan(entity: String): PlanNode =
    PlanNode
        .newBuilder()
        .setRowWrites(
            RowWrites.newBuilder().addRows(
                RowWrite
                    .newBuilder()
                    .setTarget(placedQname(SchemaCode.ER, "entity", entity))
                    .setOp(RowWriteOp.INSERT)
                    .setIfAbsent(true)
                    .addValues(
                        literalColumn(
                            "run",
                            Literal
                                .newBuilder()
                                .setStringValue("run-1")
                                .setType("text")
                                .build(),
                        ),
                    ).addValues(
                        literalColumn(
                            "key",
                            Literal
                                .newBuilder()
                                .setIntValue(1)
                                .setType("int")
                                .build(),
                        ),
                    ).addValues(
                        literalColumn(
                            "amount",
                            Literal
                                .newBuilder()
                                .setFloatValue(12.5)
                                .setType("float")
                                .build(),
                        ),
                    ),
            ),
        ).build()

private fun literalColumn(
    name: String,
    value: Literal,
): ColumnValue =
    ColumnValue
        .newBuilder()
        .setName(name)
        .setValue(Expression.newBuilder().setLiteral(value))
        .build()
