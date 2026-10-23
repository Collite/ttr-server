// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import kotlinx.serialization.Serializable
import org.tatrman.automation.staging.StagedOutput
import java.math.BigDecimal

@Serializable
data class RouteSummary(
    val rowCount: Long,
    val ref: String,
)

@Serializable
data class VerdictSummary(
    val routes: Map<String, RouteSummary>,
    val none: Long,
)

/** The evaluate response is also the immutable decision section recorded by the journal callback. */
@Serializable
data class EvaluationResult(
    val verdict: VerdictSummary,
    val guards: List<GuardResult>,
    val mode: String? = null,
)

/**
 * Route names are ports of the decision island in manifest v3, not separate Query statements. Execute the
 * scheduled inputs before reading those ports; reuse every staged output when evaluate overlaps a part read.
 * Count unmatched candidates by key-set difference: overlapping routes must not subtract one candidate twice.
 * SQL supplies verdict_reason. Never re-render the manifest's because template in the server.
 */
internal suspend fun summarizeEvaluation(runner: PartRunner): EvaluationResult {
    val recipe = runner.context.recipe.recipe
    val roles = recipe.roles
    val decision = roles?.decision
    if (decision == null) {
        require(roles?.routes.isNullOrEmpty()) { "routes require a decision island" }
        runner.all()
        return EvaluationResult(VerdictSummary(emptyMap(), 0), runner.guards)
    }
    val candidates = runner.island(requireNotNull(roles.candidates) { "decision requires candidates" }).single()
    val outputs = runner.island(decision).associateBy { it.output.port }
    val routes = roles.routes.map { it.name }
    val ports = roles.routes.flatMap { route -> listOf(route.name) + route.detail.map { "${route.name}.$it" } }
    require(ports.distinct().size == ports.size && outputs.keys == ports.toSet()) {
        "decision ports must match the declared routes and details"
    }
    require(roles.switchMode == null || roles.switchMode in setOf("exclusive", "overlapping")) {
        "unknown switch mode"
    }
    val keys = recipe.key.map { it.substringAfterLast('.') }
    require(keys.isNotEmpty() && keys.distinct().size == keys.size) { "decision requires unambiguous key columns" }
    val candidateKeys = candidates.keys(keys)
    require(candidateKeys.size == candidates.table.rows.size) { "candidate keys must be unique" }
    val selected = mutableSetOf<List<Any>>()
    val summaries =
        routes.associateWith { name ->
            val output = outputs.getValue(name)
            require(output.table.columns.any { it.name == "verdict_reason" }) { "route requires verdict_reason" }
            val routeColumn = output.table.columns.indexOfFirst { it.name == "route" }
            require(
                routeColumn < 0 || output.table.rows.all { it[routeColumn] == name },
            ) { "route column differs from its port" }
            val routeKeys = output.keys(keys)
            require(routeKeys.size == output.table.rows.size) { "route keys must be unique" }
            require(candidateKeys.containsAll(routeKeys)) { "route contains a non-candidate key" }
            require(roles.switchMode != "exclusive" || routeKeys.none { it in selected }) { "exclusive routes overlap" }
            selected += routeKeys
            RouteSummary(output.output.rows, output.ref)
        }
    return EvaluationResult(
        VerdictSummary(summaries, (candidateKeys - selected).size.toLong()),
        runner.guards,
        roles.switchMode,
    )
}

private fun StagedOutput.keys(names: List<String>): Set<List<Any>> {
    val indices =
        names.map { name ->
            table.columns.indexOfFirst { it.name == name }.also { require(it >= 0) { "decision key column missing" } }
        }
    return table.rows
        .map { row ->
            indices.map { index ->
                val value = requireNotNull(row[index]) { "decision key is null" }
                if (value is Number) BigDecimal(value.toString()).stripTrailingZeros() else value
            }
        }.toSet()
}
