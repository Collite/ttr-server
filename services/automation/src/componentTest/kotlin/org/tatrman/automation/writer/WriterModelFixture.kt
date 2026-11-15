// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.writer

import org.tatrman.automation.bundle.Bundle
import org.tatrman.meta.v1.AttributeDetail
import org.tatrman.meta.v1.DbColumnSummary
import org.tatrman.meta.v1.DbTableDetail
import org.tatrman.meta.v1.DbViewDetail
import org.tatrman.meta.v1.EntityDetail
import org.tatrman.meta.v1.Er2DbAttributeMappingDetail
import org.tatrman.meta.v1.Er2DbEntityMappingDetail
import org.tatrman.meta.v1.ModelSnapshot
import org.tatrman.meta.v1.ObjectDescriptor
import org.tatrman.meta.v1.ObjectEntry
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.snapshot.SnapshotModelHandle
import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.AttributeMappingTarget
import org.tatrman.ttr.metadata.model.DbColumn
import org.tatrman.ttr.metadata.model.DbTable
import org.tatrman.ttr.metadata.model.DbView
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.Er2DbAttributeMapping
import org.tatrman.ttr.metadata.model.Er2DbEntityMapping
import org.tatrman.ttr.metadata.model.MappingTarget

/** Snapshot wire for the fixture's real model and bindings; placement itself is production code. */
fun writerModelHandle(bundle: Bundle): SnapshotModelHandle {
    fun org.tatrman.ttr.metadata.model.QualifiedName.wire(): QualifiedName =
        QualifiedName
            .newBuilder()
            .setSchemaCode(
                runCatching { SchemaCode.valueOf(schemaCode.name) }.getOrDefault(SchemaCode.SCHEMA_CODE_UNSPECIFIED),
            ).setNamespace(namespace)
            .setName(name)
            .build()

    fun DbColumn.wire(): DbColumnSummary =
        DbColumnSummary
            .newBuilder()
            .setName(qname.name.substringAfterLast('.'))
            .setDataType(dataType)
            .setNullable(nullable)
            .build()
    val snapshot = ModelSnapshot.newBuilder()
    for (obj in bundle.model.objectByQname().values) {
        val entry =
            ObjectEntry.newBuilder().setObjectDescriptor(
                ObjectDescriptor
                    .newBuilder()
                    .setQualifiedName(obj.qname.wire())
                    .setLocalName(obj.qname.name)
                    .setPackageName(bundle.packages[obj.sourceFile].orEmpty()),
            )
        when (obj) {
            is Entity -> entry.setEntity(EntityDetail.getDefaultInstance())
            is Attribute ->
                entry.setAttribute(
                    AttributeDetail
                        .newBuilder()
                        .setEntity(obj.entity.wire())
                        .setType(obj.type)
                        .setNullable(obj.nullable)
                        .setIsKey(obj.isKey),
                )
            is DbTable ->
                entry.setTable(
                    DbTableDetail
                        .newBuilder()
                        .addAllColumns(obj.columns.map { it.wire() })
                        .addAllPrimaryKey(obj.primaryKey),
                )
            is DbView -> entry.setView(DbViewDetail.newBuilder().addAllColumns(obj.columns.map { it.wire() }))
            is Er2DbEntityMapping ->
                entry.setEr2DbEntityMapping(
                    Er2DbEntityMappingDetail
                        .newBuilder()
                        .setEntity(obj.entity.wire())
                        .apply {
                            when (val target = obj.target) {
                                is MappingTarget.Table -> setTable(target.qname.wire())
                                is MappingTarget.View -> setView(target.qname.wire())
                                is MappingTarget.SqlQuery -> setSqlQuery(target.qname.wire())
                            }
                        },
                )
            is Er2DbAttributeMapping ->
                entry.setEr2DbAttributeMapping(
                    Er2DbAttributeMappingDetail
                        .newBuilder()
                        .setAttribute(obj.attribute.wire())
                        .apply {
                            val target = obj.target
                            if (target is AttributeMappingTarget.Column) setColumn(target.qname.wire())
                        },
                )
            else -> continue
        }
        snapshot.addObjects(entry)
    }
    return SnapshotModelHandle.from(
        snapshot.build(),
        placedPackages = setOf("hartland.automation.generated"),
        placedEntities = setOf("obligation", "state_history", "approvals"),
    )
}
