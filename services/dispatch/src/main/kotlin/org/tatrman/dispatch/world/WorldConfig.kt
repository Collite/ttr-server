// SPDX-License-Identifier: Apache-2.0
package org.tatrman.dispatch.world

import com.typesafe.config.Config
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.schemaCodeToToken

/**
 * Maps physical table qnames to a `connection_id`. Loaded from HOCON at boot:
 *
 *   world {
 *     default-connection = "hartland-default"
 *     table-connections {
 *       "db.dbo.store_*"   = "hartland-store"
 *       "db.dbo.customer*" = "hartland-customer"
 *     }
 *   }
 *
 * Lookup rules (Phase 1.7 Section D):
 *   1. Exact match wins.
 *   2. Otherwise the first prefix match — patterns ending in `*` match any
 *      qname whose dot-path starts with everything before the asterisk.
 *   3. Otherwise null — the caller falls back to [defaultConnection].
 *
 * Patterns are scanned in declaration order so callers can write more
 * specific patterns first.
 */
class WorldConfig(
    val defaultConnection: String,
    private val patterns: List<Pair<String, String>>,
) {
    fun routingFor(table: QualifiedName): String? {
        val key = qnameKey(table)
        // Exact match first.
        for ((pattern, connection) in patterns) {
            if (!pattern.endsWith("*") && pattern == key) return connection
        }
        // Then prefix match.
        for ((pattern, connection) in patterns) {
            if (pattern.endsWith("*") && key.startsWith(pattern.dropLast(1))) return connection
        }
        return null
    }

    /** Resolves a connection_id, falling back to [defaultConnection] when no pattern matches. */
    fun resolveOrDefault(table: QualifiedName): String = routingFor(table) ?: defaultConnection

    companion object {
        fun fromConfig(config: Config): WorldConfig {
            val default = config.getString("world.default-connection")
            val patterns =
                if (config.hasPath("world.table-connections")) {
                    val tc = config.getConfig("world.table-connections")
                    tc.entrySet().map { (k, v) -> k.trim('"') to v.unwrapped().toString() }
                } else {
                    emptyList()
                }
            return WorldConfig(defaultConnection = default, patterns = patterns)
        }

        internal fun qnameKey(q: QualifiedName): String =
            buildString {
                if (q.schemaCode != org.tatrman.plan.v1.SchemaCode.SCHEMA_CODE_UNSPECIFIED) {
                    append(schemaCodeToToken(q.schemaCode))
                    if (q.namespace.isNotEmpty()) append('.')
                }
                if (q.namespace.isNotEmpty()) {
                    append(q.namespace)
                    append('.')
                }
                append(q.name)
            }
    }
}
