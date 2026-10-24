// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import org.tatrman.automation.bundle.ManifestColumn

/** A-2: the program selects candidates by wrapping SQL; bundle files and hashes remain untouched. */
object CandidateScope {
    data class Statement(
        val sql: String,
        val bindings: Map<String, BoundValue>,
    )

    fun wrap(
        sql: String,
        columns: List<ManifestColumn>,
        keys: List<Map<String, Any?>>?,
    ): Statement {
        if (keys == null) return Statement(sql, emptyMap())
        val names =
            keys
                .firstOrNull()
                ?.keys
                ?.sorted()
                .orEmpty()
        val bindings = linkedMapOf<String, BoundValue>()

        fun bind(
            row: Int,
            col: Int,
        ): String {
            val name = names[col]
            val type = columns.single { it.name == name }.type
            val param = "_au_port_${row}_$col"
            bindings[param] = BoundValue(type, keys[row].getValue(name))
            return ":$param"
        }

        fun quote(name: String) = "\"${name.replace("\"", "\"\"")}\""
        val predicate =
            when {
                keys.isEmpty() -> "FALSE"
                names.size == 1 -> "${quote(names.single())} IN (${keys.indices.joinToString(", ") { bind(it, 0) }})"
                else ->
                    keys.indices.joinToString(" OR ") { row ->
                        "(" +
                            names.indices.joinToString(" AND ") { col -> "${quote(names[col])} = ${bind(row, col)}" } +
                            ")"
                    }
            }
        require(
            keys.isEmpty() ||
                names.isNotEmpty() &&
                keys.all { it.keys == names.toSet() && it.values.none { v -> v == null } },
        )
        return Statement(
            "SELECT * FROM (${sql.trimEnd().removeSuffix(";")}) AS _au_candidates WHERE $predicate",
            bindings,
        )
    }
}
