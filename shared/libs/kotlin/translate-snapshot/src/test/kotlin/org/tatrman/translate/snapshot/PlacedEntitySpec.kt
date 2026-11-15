// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translate.snapshot

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.string.shouldContain
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
                val result =
                    Translator(handle)
                        .unparseFromRelNode(concrete, Language.SQL, SqlDialect.POSTGRESQL)
                        .shouldBeInstanceOf<UnparseResult.Success>()
                result.output shouldContain "obligation"
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
