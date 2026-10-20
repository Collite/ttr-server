// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.bundle

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tatrman.ttr.metadata.model.WorldDeployment

/**
 * An executor type's manifest (exporters `contracts.md` §1, D-35) — what a target can run. [family] is the key
 * an action's `sinks:` uses for the type (`n8n: door`); [kinds] maps an island kind to how it runs (`door`,
 * `native`, `no`); [sinks] maps a sink kind to how the type performs it.
 */
@Serializable
data class ExecutorType(
    val name: String,
    val family: String,
    val fidelity: String = "",
    val modes: List<String> = emptyList(),
    val ports: List<String> = emptyList(),
    val kinds: Map<String, String> = emptyMap(),
    val sinks: Map<String, String> = emptyMap(),
    val granularity: List<String> = emptyList(),
    val ungoverned: Boolean = false,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The types this Server knows: `executor-types/<name>.json` on the classpath (a copy until the exporters publish). */
        fun builtin(names: List<String> = listOf("native", "n8n_l1")): Map<String, ExecutorType> =
            names.associateWith { n ->
                val text =
                    checkNotNull(ExecutorType::class.java.getResourceAsStream("/executor-types/$n.json")) {
                        "executor type $n is not on the classpath"
                    }.use { it.readBytes().toString(Charsets.UTF_8) }
                json.decodeFromString(serializer(), text)
            }
    }
}

/** One cell of the registry grid (contracts §1.1 `registry: [{ target, mode, status, reason }]`). */
@Serializable
data class RegistryCell(
    val deployment: String,
    val target: String,
    val targetType: String?,
    val mode: String?,
    val status: String,
    val reason: String?,
    val notes: List<String> = emptyList(),
)

/**
 * The registry grid (S0.2 T3, D-35): per deployment, may its executor type run its program(s)? `refused` names
 * the first rule broken — `TTRP-TGT-001` the program's mode is not one of the type's, `-002` an island kind the
 * type marks `no` (or does not list), `-003` an action whose sink the type cannot perform, `-004` a granularity
 * the type does not offer, `-005` an ungoverned type on a governed deployment — or an executor type this Server
 * does not know. `notes` = legal, with the sinks that the type performs by calling back into the Server (`door`).
 * An action's sink for a type is its `sinks.<family>` entry, else its `native:` sink kind, which the type's
 * `sinks` must list.
 */
class Registry(
    private val types: Map<String, ExecutorType>,
) {
    fun grid(bundle: Bundle): List<RegistryCell> = bundle.deployments.values.map { cell(bundle, it) }

    fun cell(
        bundle: Bundle,
        d: WorldDeployment,
    ): RegistryCell {
        val typeName = bundle.targetType(d)
        val programs = bundle.programsOf(d).mapNotNull { bundle.programs[it] }
        val mode =
            programs
                .mapNotNull { it.mode }
                .distinct()
                .joinToString(",")
                .ifEmpty { null }

        fun refused(reason: String) = RegistryCell(d.qname.name, d.target, typeName, mode, REFUSED, reason)
        val type =
            typeName?.let { types[it] }
                ?: return refused("executor type ${typeName ?: "of ${d.target}"} is not known to this Server")
        if (type.ungoverned &&
            !d.ungoverned
        ) {
            return refused(
                "TTRP-TGT-005: ${type.name} is ungoverned; the deployment does not say `ungoverned: true`",
            )
        }
        d.granularity?.let { g ->
            if (g !in type.granularity) return refused("TTRP-TGT-004: ${type.name} does not run granularity $g")
        }
        val notes = sortedSetOf<String>()
        for (p in programs) {
            val pm = p.mode
            if (pm != null &&
                pm !in type.modes
            ) {
                return refused("TTRP-TGT-001: ${type.name} does not run mode $pm (program ${p.qname.name})")
            }
            val recipeName = p.recipe ?: continue
            // The load refuses a commit with a program whose recipe has no bundle; never call such a cell ok.
            val recipe =
                bundle.recipes[recipeName]
                    ?: return refused("recipe $recipeName of program ${p.qname.name} has no bundle")
            for (k in recipe.recipe.kinds) {
                val how = type.kinds[k]
                if (how == null ||
                    how == NO
                ) {
                    return refused("TTRP-TGT-002: ${type.name} cannot run a $k island (recipe ${recipe.name})")
                }
            }
            for (w in recipe.recipe.wiring) {
                val action = bundle.actions[w.action] ?: continue
                val own = action.sinks[type.family]
                val how =
                    when {
                        own != null && own != DOOR -> own
                        own == DOOR -> DOOR
                        else -> action.sinks[Bundle.NATIVE]?.let { type.sinks[it] }
                    } ?: return refused(
                        "TTRP-TGT-003: ${type.name} cannot perform action ${w.action} " +
                            "(sink ${action.sinks[Bundle.NATIVE] ?: "none"}; recipe ${recipe.name})",
                    )
                if (how == DOOR) notes += "${w.action} through the Server"
            }
        }
        return RegistryCell(
            d.qname.name,
            d.target,
            type.name,
            mode,
            if (notes.isEmpty()) OK else NOTES,
            null,
            notes.toList(),
        )
    }

    companion object {
        const val OK = "ok"
        const val NOTES = "notes"
        const val REFUSED = "refused"
        private const val DOOR = "door"
        private const val NO = "no"
    }
}
