// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.writer

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * D-206 (F1 residual, review-293 ✅R293-C): every refusal code the row-write path can produce is named in the transient
 * or the settled table ([RowOutcome.classified]). A code in neither still settles at run time ("unnamed codes settle"),
 * so an outage code a producer adds would silently advance a windowed program past unwritten rows — this spec fails on
 * it instead. The codes are this module's own literals, read from its sources, and the codes the other producers on the
 * path answer (contracts.md Amendment (d) "As built", review-293 F1).
 */
class RowOutcomeClassificationSpec :
    StringSpec({
        val sources: Map<Path, String> =
            Files.walk(Path.of("src/main/kotlin")).use { paths ->
                paths.filter { it.extension == "kt" }.toList().associateWith { it.readText() }
            }

        /** A `failed` row's code as written at its producer; a template keeps its literal head (`rows-affected-`). */
        fun literal(code: String) = code.substringBefore('$')

        // This writer's row refusals, the door's refusals before the worker ran, and the codes written on a failed row.
        val producers =
            listOf(
                Regex("""RowRefusal\(\s*"([^"]+)""""),
                Regex("""DoorWriteFailure\.Refused\(\s*"([^"]+)""""),
                Regex("""RowOutcome\([^"\n]*?"failed",\s*"([^"]+)""""),
            )
        val constants = Regex("""RowOutcome\([^"\n]*?"failed",\s*RowOutcome\.([A-Z_]+)""")

        fun own(): Map<String, Set<Path>> {
            val found = mutableMapOf<String, MutableSet<Path>>()
            for ((path, text) in sources) {
                for (regex in producers) {
                    regex
                        .findAll(
                            text,
                        ).forEach { found.getOrPut(literal(it.groupValues[1])) { mutableSetOf() }.add(path) }
                }
                constants.findAll(text).forEach {
                    val value = RowOutcome::class.java.getField(it.groupValues[1]).get(null) as String
                    found.getOrPut(value) { mutableSetOf() }.add(path)
                }
            }
            return found
        }

        // The other producers on the row_writes path (contracts.md Amendment (d), review-293 F1): query, translate/
        // validate, dispatch and the worker. Their codes reach the writer through the door's `failure()`.
        val external =
            listOf(
                // query (`QueryServiceImpl.runRowWrites`/`compileRowWrites`)
                "translator_unavailable",
                "validator_unavailable",
                "query_failed",
                "unknown_world",
                "world_unspecified",
                "out-of-scope-read",
                "row_writes_principal",
                "parameter_unbound",
                // translate/validate plan refusals
                "write_under_row_policy",
                "role_source_unavailable",
                // dispatch (`DispatchServiceImpl`)
                "no_worker_for_connection",
                "no_stateful_worker_available",
                "worker_stream_failed",
                "row_writes_schema_mismatch",
                "row_writes_connection_mismatch",
                "cross_database_not_supported",
                "workspace_requires_session",
                "session_lost",
                // the worker (worker-postgres `ExecutePipeline`): a row the translator refuses, an execution fault
                "row_writes_shape",
                "worker_execution_failed",
            )

        "the scan finds this module's producers (a scan that found nothing would pass vacuously)" {
            own().keys shouldContainAll
                listOf(
                    "bad-transition",
                    "bad-value",
                    "unknown-entity",
                    "storage-unmapped",
                    "identity",
                    "no-row",
                    "rows-affected-",
                    RowOutcome.STALE_STATE,
                    RowOutcome.RECONCILE_UNREAD,
                )
        }

        "every refusal code on the row-write path is transient or settled — none is left to the default" {
            val unnamed =
                own().filterKeys { !RowOutcome.classified(it) }.map { (code, at) -> "$code (${at.joinToString()})" } +
                    external.filterNot { RowOutcome.classified(it) }
            withClue("name each in RowOutcome.TRANSIENT or RowOutcome.SETTLED_FAILURES") { unnamed.shouldBeEmpty() }
        }

        "the two tables do not overlap: a code is transient or settled, never both" {
            (RowOutcome.TRANSIENT intersect RowOutcome.SETTLED_FAILURES).shouldBeEmpty()
            for (code in RowOutcome.SETTLED_FAILURES) {
                withClue(code) { RowOutcome.holds("failed", code) shouldBe false }
            }
        }
    })
