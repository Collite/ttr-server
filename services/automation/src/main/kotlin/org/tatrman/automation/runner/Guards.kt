// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** Stable evidence for evaluate/protocol. It names policy and island, never candidate values or credentials. */
@Serializable
data class GuardResult(
    val code: String,
    val island: String,
    val status: String,
)

/**
 * Run-local decision safety (D-92/D-94, REC-004). Guard each governed read BEFORE loading or staging it, then
 * check candidates after their combine and key filter. A source at its cap is unsafe even if an aggregate
 * reduces it to one row. `top_n_applied` signals a reached validator cap lower than the declared read ceiling.
 * Refusals latch: later cached reads and in-flight reads checked after a refusal cannot bypass it.
 * QueryDoor verifies ER scan scope before execution; its refusals latch alongside the result guards.
 */
class Guards(
    private val context: RunContext,
) {
    private val failure = AtomicReference<AutomationException?>()
    private val evidence = ConcurrentHashMap<Pair<String, String>, GuardResult>()
    val results: List<GuardResult> get() = evidence.values.sortedWith(compareBy({ it.island }, { it.code }))

    fun ensureAllowed() {
        failure.get()?.let { throw it }
    }

    fun scopePassed(island: String) {
        ensureAllowed()
        record("out-of-scope-read", island, "passed")
    }

    fun doorRefused(
        island: String,
        exception: AutomationException,
    ): Nothing {
        if (exception.code == ErrorCode.OUT_OF_SCOPE_READ) refuse(exception, island)
        throw exception
    }

    fun read(
        island: String,
        result: ReadResult,
        requireComplete: Boolean = false,
        completeMessage: String = "obligation lookup requires a complete snapshot",
    ) {
        ensureAllowed()
        if (requireComplete ||
            context.recipe.recipe.roles
                ?.decision != null
        ) {
            val restricted = "security_predicate_applied" in result.warnings
            if (restricted && (requireComplete || !context.allowRestrictedView)) {
                refuse(
                    AutomationException(
                        ErrorCode.RESTRICTED_VIEW,
                        if (requireComplete) {
                            completeMessage
                        } else {
                            "a decision read was narrowed by security"
                        },
                        detail(island),
                    ),
                    island,
                )
            }
            record("restricted-view", island, if (restricted) "allowed" else "passed")
        }
        ceiling(island, result.table, "top_n_applied" in result.warnings)
        ensureAllowed()
    }

    fun ceiling(
        island: String,
        table: Table,
        validatorCap: Boolean = false,
    ) {
        ensureAllowed()
        if (table.rows.size >= context.readCeiling || validatorCap) {
            refuse(
                AutomationException(
                    ErrorCode.CANDIDATES_TRUNCATED,
                    "a recipe read reached its ceiling",
                    detail(island),
                ),
                island,
            )
        }
        record("candidates-truncated", island, "passed")
    }

    fun candidates(
        island: String,
        table: Table,
    ) {
        ensureAllowed()
        val keys =
            context.recipe.recipe.key
                .map { it.substringAfterLast('.') }
        if (keys.isNotEmpty()) {
            val indices =
                keys.map { key ->
                    table.columns.indexOfFirst { it.name == key }.also {
                        require(
                            it >= 0,
                        ) { "candidate key column missing" }
                    }
                }
            val seen = HashSet<List<Any>>()
            for (row in table.rows) {
                val key =
                    indices.map { index ->
                        val value = requireNotNull(row[index]) { "candidate key is null" }
                        if (value is Number) BigDecimal(value.toString()).stripTrailingZeros() else value
                    }
                if (!seen.add(key)) {
                    refuse(
                        AutomationException(ErrorCode.DUPLICATE_KEY, "candidate keys are not unique", detail(island)),
                        island,
                    )
                }
            }
            record("duplicate-key", island, "passed")
        }
        if (!context.keys.isNullOrEmpty() && table.rows.isEmpty()) {
            refuse(
                AutomationException(ErrorCode.OUT_OF_SCOPE, "the keyed open has no candidate", detail(island)),
                island,
            )
        }
        record("out-of-scope", island, "passed")
        ensureAllowed()
    }

    private fun detail(island: String) = buildJsonObject { put("island", island) }

    private fun record(
        code: String,
        island: String,
        status: String,
    ) {
        // A later passing output on the same island cannot erase an earlier refusal/opt-in.
        val rank = mapOf("passed" to 0, "allowed" to 1, "refused" to 2)
        evidence.merge(code to island, GuardResult(code, island, status)) { old, new ->
            if (rank.getValue(old.status) >= rank.getValue(new.status)) old else new
        }
    }

    private fun refuse(
        exception: AutomationException,
        island: String,
    ): Nothing {
        record(exception.code.code, island, "refused")
        failure.compareAndSet(null, exception)
        throw failure.get()!!
    }
}
