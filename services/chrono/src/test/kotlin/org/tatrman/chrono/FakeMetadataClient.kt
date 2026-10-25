// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono

import org.tatrman.chrono.discover.ColumnRef
import org.tatrman.chrono.discover.PeriodTable
import org.tatrman.chrono.discover.SemanticDiscovery
import org.tatrman.chrono.recognize.DateTarget
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode

/**
 * Domain fake for the [SemanticDiscovery] port (RG-P3.S0 metadata seam). Backs chrono's
 * discovery/recipe tests from an in-memory object list, answering [periodTable]/[anchorColumn]
 * with the recipe layer's domain types directly — **no metadata protos** (the real proto mapping
 * lives in `MetaV1SemanticDiscovery`, proven by its component spec against in-process Metadata).
 */
class FakeMetadataClient(
    private val objects: List<Obj>,
) : SemanticDiscovery {
    /** One metadata object. `role`/`semanticKind` are the semantics strings; null = none. */
    data class Obj(
        val pkg: String,
        val entityName: String,
        val name: String,
        val kind: String, // "entity" | "attribute"
        val role: String? = null, // attribute role, e.g. "event_date"
        val semanticKind: String? = null, // entity kind, e.g. "period_table"
        val codeFormat: String? = null,
    ) {
        val qname: QualifiedName =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.ER)
                .setNamespace(entityName)
                .setName(name)
                .setPackage(pkg)
                .build()
    }

    private fun Obj.toColumnRef(): ColumnRef = ColumnRef(qname = qname, entityName = entityName, columnName = name)

    override suspend fun periodTable(pkg: String): PeriodTable? {
        val entity =
            objects.firstOrNull {
                it.pkg == pkg && it.kind == "entity" && it.semanticKind == "period_table"
            } ?: return null

        fun roleColumn(role: String): ColumnRef? =
            objects
                .firstOrNull {
                    it.pkg == pkg && it.kind == "attribute" && it.entityName == entity.entityName && it.role == role
                }?.toColumnRef()

        val codeObj =
            objects.firstOrNull {
                it.pkg == pkg && it.entityName == entity.entityName && it.role == "period_code"
            }
        return PeriodTable(
            entity = entity.qname,
            entityName = entity.entityName,
            start = roleColumn("period_start"),
            end = roleColumn("period_end"),
            code = roleColumn("period_code"),
            codeFormat = codeObj?.codeFormat ?: "yyyyMM",
        )
    }

    override suspend fun anchorColumn(
        pkg: String,
        target: DateTarget?,
    ): ColumnRef? {
        val role =
            when (target) {
                DateTarget.DUE -> "due_date"
                DateTarget.POSTING -> "posting_date"
                DateTarget.DOCUMENT -> "document_date"
                DateTarget.EVENT, null -> "event_date"
            }

        fun firstWithRole(r: String): ColumnRef? =
            objects.firstOrNull { it.pkg == pkg && it.kind == "attribute" && it.role == r }?.toColumnRef()

        return firstWithRole(role) ?: firstWithRole("event_date").takeIf { target != null }
    }

    override suspend fun probeReady(): Boolean = true

    companion object {
        /**
         * The canonical calendar/accounting fixture: package "cnc" with a period_table
         * AccountingPeriod (start_date/end_date/period, code_format yyyyMM) and a Transaction fact
         * carrying event_date (`date`) + due_date (`due`).
         */
        fun accounting(pkg: String = "cnc"): FakeMetadataClient =
            FakeMetadataClient(
                listOf(
                    Obj(pkg, "AccountingPeriod", "AccountingPeriod", "entity", semanticKind = "period_table"),
                    Obj(pkg, "AccountingPeriod", "start_date", "attribute", role = "period_start"),
                    Obj(pkg, "AccountingPeriod", "end_date", "attribute", role = "period_end"),
                    Obj(pkg, "AccountingPeriod", "period", "attribute", role = "period_code", codeFormat = "yyyyMM"),
                    Obj(pkg, "Transaction", "date", "attribute", role = "event_date"),
                    Obj(pkg, "Transaction", "due", "attribute", role = "due_date"),
                ),
            )
    }
}
