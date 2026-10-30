// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.world

import com.typesafe.config.Config

/**
 * BI (contracts C-9) — the door's world table, `QUERY_WORLDS`: which connection each world runs on
 * (`hartland_us=pg-hartland-us,hartland_cz=pg-hartland-cz`). `RunRequest.world` is looked up here and the
 * connection id goes to dispatch, which treats an explicit `connection_id` as authoritative. A table, not string
 * surgery on the world's name: the connection a world runs on is a deployment's fact.
 *
 * Empty (the default) means any named world is unknown. Omission preserves derived/default routing unless
 * `QUERY_REQUIRE_WORLD` is on and more than one world is configured, independent of caller roles.
 */
class Worlds(
    val connections: Map<String, String>,
    val requireWorld: Boolean = false,
) {
    /** The connection [world] runs on, or null when this door does not serve it. */
    fun connectionOf(world: String): String? = connections[world]

    operator fun contains(world: String): Boolean = world in connections

    val names: List<String> get() = connections.keys.sorted()

    val requiresWorld: Boolean get() = requireWorld && connections.size > 1

    /** Execution refusal; Flight connect only applies this to non-empty names so catalog can be set later. */
    fun refusal(world: String): Pair<String, String>? =
        when {
            world.isEmpty() && requiresWorld -> WORLD_UNSPECIFIED to unspecified()
            world.isNotEmpty() && world !in this -> UNKNOWN_WORLD to unknown(world)
            else -> null
        }

    fun unspecified(): String = "A world is required here; the worlds are ${names.joinToString(", ")}."

    /** The refusal a request naming [world] gets when the table does not have it. */
    fun unknown(world: String): String =
        if (connections.isEmpty()) {
            "World '$world' is not served here: this door runs one world, on its default connection."
        } else {
            "World '$world' is not served here; the worlds are ${names.joinToString(", ")}."
        }

    override fun toString(): String = connections.entries.joinToString(", ") { "${it.key} -> ${it.value}" }

    companion object {
        /** The code a request naming a world this door does not serve is refused with. */
        const val UNKNOWN_WORLD = "unknown_world"

        const val WORLD_UNSPECIFIED = "world_unspecified"

        val NONE = Worlds(emptyMap())

        /** `world=connection` pairs, comma-separated; a malformed or repeated pair is a configuration error, named. */
        fun parse(
            raw: String?,
            requireWorld: Boolean = false,
        ): Worlds {
            val pairs =
                raw
                    .orEmpty()
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { pair ->
                        val world = pair.substringBefore('=', "").trim()
                        val connection = pair.substringAfter('=', "").trim()
                        require(world.isNotEmpty() && connection.isNotEmpty()) {
                            "query.worlds (QUERY_WORLDS): '$pair' is not world=connection"
                        }
                        world to connection
                    }
            val repeated = pairs.groupBy { it.first }.filterValues { it.size > 1 }.keys
            require(repeated.isEmpty()) { "query.worlds (QUERY_WORLDS) names ${repeated.joinToString()} twice" }
            return Worlds(pairs.toMap(), requireWorld)
        }

        fun fromConfig(config: Config): Worlds =
            parse(
                if (config.hasPath("query.worlds")) config.getString("query.worlds") else null,
                requireWorld = config.hasPath("query.require-world") && config.getBoolean("query.require-world"),
            )
    }
}
