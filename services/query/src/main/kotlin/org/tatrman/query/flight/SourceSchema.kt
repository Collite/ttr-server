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
 * - `obj` and `db` only → unstated: a saved query reads either layer, and the door picks the one its body reads
 *   (⚑R123-D), where stating ER would refuse a DB-lane query joined to a table as mixed;
 * - `er` and `db` → ER: the door then refuses the mixed tree with LQ-2's own sentence (ledger 10b), not "Set source_schema";
 * - no qualified name → unstated: the door autodetects, as for every other caller.
 *
 * The stated layer is the statement's, so the door reads its bare names in it too: `FROM db.dbo.inventory i JOIN
 * warehouse w` reads the table `warehouse` (⚑R123-C, review-123 R8).
 *
 * A qualified name is three parts or more (`er.entity.warehouse`, a column `er.entity.warehouse.city`), each part
 * quoted or not, so an alias called `db` (`db.city`) is not one. Read the text [CatalogQualifier] made: the connection's
 * own catalog blanked out and a quoted dotted schema unfolded (`"er.entity".warehouse` → `er."entity".warehouse`), so
 * a quoted dotted name still standing is one the statement defines (an alias), not a layer.
 */
internal object SourceSchema {
    fun of(sql: String): SchemaCode {
        val layers = CatalogQualifier.chains(sql).mapNotNull(::layerOf).toSet()
        return when {
            layers.isEmpty() -> SchemaCode.SCHEMA_CODE_UNSPECIFIED
            layers == setOf("db") -> SchemaCode.DB
            layers == setOf("obj", "db") -> SchemaCode.SCHEMA_CODE_UNSPECIFIED
            else -> SchemaCode.ER
        }
    }

    private fun layerOf(chain: List<CatalogQualifier.Part>): String? {
        if (chain.size < 3) return null
        return chain[0].name.lowercase().takeIf { it in CatalogQualifier.SCHEMA_HEADS }
    }
}
