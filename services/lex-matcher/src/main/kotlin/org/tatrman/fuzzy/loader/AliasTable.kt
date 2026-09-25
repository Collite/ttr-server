// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import org.tatrman.fuzzy.config.DatabaseConfig
import org.tatrman.plan.v1.QualifiedName

/**
 * An estate synonym table declared via `semantics{kind: alias_table}` (RS-12-γ):
 * a table mapping [aliasColumn] synonyms to [pkColumn] keys, whose rows are
 * loaded as ADDITIONAL member candidates for [ownerCategory] (same PK space, so
 * an alias match resolves to the same `resolved_id` as the primary name).
 */
data class AliasTableDecl(
    val ownerCategory: String,
    val tableQname: QualifiedName,
    val pkColumn: String,
    val aliasColumn: String,
)

/**
 * Composes `SELECT pk, alias FROM alias_table` per declaration (reusing the
 * [buildSelect] identifier-validation + dialect-quoting discipline), fetches the
 * rows, and groups them by owner category (lower-cased) — ready to MERGE into
 * the owning member category. [fetch] runs the SQL and returns its `(pk, alias)`
 * rows in whatever shape the caller builds candidates from — the member loader
 * takes raw rows, so each candidate is built once, with its owner's method
 * (review-104 F13).
 *
 * A declaration whose identifiers fail validation is skipped (returns no rows)
 * rather than aborting the whole load.
 */
fun <T> composeAliasCandidates(
    decls: List<AliasTableDecl>,
    dialect: DatabaseConfig,
    fetch: (String) -> List<T>,
): Map<String, List<T>> =
    decls
        .groupBy { it.ownerCategory.lowercase() }
        .mapValues { (_, group) ->
            group.flatMap { decl ->
                val sql =
                    try {
                        buildSelect(decl.tableQname, decl.pkColumn, decl.aliasColumn, dialect)
                    } catch (e: SqlComposerException) {
                        return@flatMap emptyList<T>()
                    }
                fetch(sql)
            }
        }
