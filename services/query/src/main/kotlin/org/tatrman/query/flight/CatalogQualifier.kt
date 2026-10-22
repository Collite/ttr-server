// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

/**
 * BI (contracts C-3) — a statement may name its catalog, the way a tool that read `GetCatalogs` qualifies a table:
 * `hartland_cz.er.entity.warehouse`, or quoted, `"hartland_cz"."er.entity"."warehouse"`. The connection's world is
 * the only catalog it may name: that qualifier is blanked out before the door sees the statement (the door's names
 * have no catalog part) — spaces in its place, so a refusal's line and column (C-11) still point into the statement
 * as the client wrote it — and any other catalog is [Outcome.Mismatch] — `world_mismatch`, refused before `Run`,
 * never run quietly against the connection's own world.
 *
 * A qualifier is recognised by what follows it, the door's schema spellings: `<catalog>.er|db|obj.<namespace>.<name>`,
 * each part quoted or not (`"hartland_cz"."er"."entity"."warehouse"`, as a tool that quotes every identifier writes
 * it; four parts at least, so `alias.db` — a column called `db` — is not one), or a quoted dotted schema,
 * `<catalog>."er.entity".<name>`. String literals and comments are skipped; nothing else in the statement changes.
 */
internal object CatalogQualifier {
    sealed interface Outcome {
        /** The statement to run: [sql] with the connection's own catalog qualifiers blanked out. */
        data class Run(
            val sql: String,
        ) : Outcome

        /** The statement names [catalog], which is not the connection's world. */
        data class Mismatch(
            val catalog: String,
        ) : Outcome
    }

    fun resolve(
        sql: String,
        world: String,
    ): Outcome {
        val drops = mutableListOf<IntRange>()
        for (chain in chains(sql)) {
            if (!isCatalogQualified(chain)) continue
            val catalog = chain[0]
            val same = if (catalog.quoted) catalog.name == world else catalog.name.equals(world, ignoreCase = true)
            if (!same || world.isEmpty()) return Outcome.Mismatch(catalog.name)
            drops += catalog.start until chain[1].start
        }
        if (drops.isEmpty()) return Outcome.Run(sql)
        val out = StringBuilder(sql)
        for (range in drops) {
            for (i in range) if (out[i] != '\n' && out[i] != '\r') out.setCharAt(i, ' ')
        }
        return Outcome.Run(out.toString())
    }

    /** One part of a `.`-joined run of identifiers, in source order (also read by [SourceSchema]). */
    internal data class Part(
        val start: Int,
        val name: String,
        val quoted: Boolean,
    )

    internal val SCHEMA_HEADS = setOf("er", "db", "obj")

    private fun isCatalogQualified(chain: List<Part>): Boolean {
        if (chain.size < 3) return false
        val second = chain[1]
        if (second.quoted && second.name.contains('.')) {
            return second.name.substringBefore('.').lowercase() in SCHEMA_HEADS
        }
        return chain.size >= 4 && second.name.lowercase() in SCHEMA_HEADS
    }

    /** Every `.`-joined identifier run of two parts or more; string literals and comments skipped. */
    internal fun chains(sql: String): List<List<Part>> {
        val chains = mutableListOf<List<Part>>()
        var current = mutableListOf<Part>()
        var afterDot = false

        fun end() {
            if (current.size > 1) chains += current
            current = mutableListOf()
            afterDot = false
        }

        fun add(part: Part) {
            if (!afterDot) end()
            current += part
            afterDot = false
        }
        var i = 0
        while (i < sql.length) {
            val c = sql[i]
            when {
                c == '\'' -> {
                    end()
                    i = closing(sql, i, '\'')
                }
                sql.startsWith("--", i) -> {
                    end()
                    i = sql.indexOf('\n', i).let { if (it < 0) sql.length else it }
                }
                sql.startsWith("/*", i) -> {
                    end()
                    i = sql.indexOf("*/", i + 2).let { if (it < 0) sql.length else it + 2 }
                }
                c == '"' -> {
                    val next = closing(sql, i, '"')
                    add(Part(i, sql.substring(i + 1, (next - 1).coerceAtLeast(i + 1)).replace("\"\"", "\""), true))
                    i = next
                }
                c.isLetter() || c == '_' -> {
                    var j = i + 1
                    while (j < sql.length && (sql[j].isLetterOrDigit() || sql[j] == '_' || sql[j] == '$')) j++
                    add(Part(i, sql.substring(i, j), false))
                    i = j
                }
                c == '.' -> {
                    if (current.isNotEmpty() && !afterDot) afterDot = true else end()
                    i++
                }
                c.isWhitespace() -> i++
                else -> {
                    end()
                    i++
                }
            }
        }
        end()
        return chains
    }

    /** The index just past the quote that closes the one at [open] (a doubled quote is an escaped one). */
    private fun closing(
        sql: String,
        open: Int,
        quote: Char,
    ): Int {
        var j = open + 1
        while (j < sql.length) {
            if (sql[j] == quote) {
                if (j + 1 < sql.length && sql[j + 1] == quote) {
                    j += 2
                    continue
                }
                return j + 1
            }
            j++
        }
        return sql.length
    }
}
