// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

import org.tatrman.plan.v1.SchemaCode

/**
 * BI (BI-0 memo S-8) — the layer an edge statement's qualified names are written in, stated to the door as
 * `RunRequest.source_schema`. The door's detector resolves bare table names against the catalog and does not read a
 * statement's own qualifiers, so left unstated, `SELECT … FROM db.dbo.warehouse` is refused `detection_failed`
 * whenever an entity shares the table's name — on hartland 34 of 36 do (found live, BI-3·S3.1 T1).
 *
 * - `er.<namespace>.<name>` or `obj.<namespace>.<name>` only → ER (a logical query is a table under ER; spike ledger 11b);
 * - `db.<namespace>.<name>` only → DB;
 * - both → ER: the door then refuses the mixed tree with LQ-2's own sentence (ledger 10b), not "Set source_schema";
 * - no qualified name → unstated: the door autodetects, as for every other caller.
 *
 * A qualified name is three parts or more (`er.entity.warehouse`, a column `er.entity.warehouse.city`) or a quoted
 * dotted schema (`"er.entity".warehouse`), each part quoted or not, so an alias called `db` (`db.city`) is not one.
 * Read after [CatalogQualifier] has blanked the connection's own catalog out.
 */
internal object SourceSchema {
    fun of(sql: String): SchemaCode {
        val layers = CatalogQualifier.chains(sql).mapNotNull(::layerOf).toSet()
        return when {
            layers.isEmpty() -> SchemaCode.SCHEMA_CODE_UNSPECIFIED
            layers == setOf("db") -> SchemaCode.DB
            else -> SchemaCode.ER
        }
    }

    private fun layerOf(chain: List<CatalogQualifier.Part>): String? {
        val head = chain[0]
        val layer =
            when {
                head.quoted && head.name.contains('.') -> head.name.substringBefore('.')
                chain.size >= 3 -> head.name
                else -> return null
            }.lowercase()
        return layer.takeIf { it in CatalogQualifier.SCHEMA_HEADS }
    }
}
