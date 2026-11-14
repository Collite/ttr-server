// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.ledger

import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.javatime.JavaOffsetDateTimeColumnType
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * D-143 (S3.2 T2): a windowed program's watermark, one `program_windows` row per (program, window). There is no
 * window runtime: the engine's timer opens `port: window`, Open resolves `[from, to)` = `[last_to, as_of)` (the
 * epoch before the first close), and the close that seals a wet run advances `last_to` to its `to` in the seal's
 * transaction. A dry run, a failed run and a run over explicit bounds never advance it.
 */
object Watermark {
    /** The `from` of a program's first window. */
    val EPOCH: Instant = Instant.EPOCH

    /** The stored port of a windowed run: `{ window: { from, to, windows, watermark } }`. */
    const val PORT_KEY = "window"

    /** The program's watermark over [windows]: the earliest `last_to`, the epoch for a window never closed. */
    fun lastTo(
        program: String,
        windows: List<String>,
    ): Instant {
        require(windows.isNotEmpty())
        val rows =
            transaction {
                ProgramWindows
                    .selectAll()
                    .where { (ProgramWindows.program eq program) and (ProgramWindows.window inList windows) }
                    .associate { it[ProgramWindows.window] to it[ProgramWindows.lastTo].toInstant() }
            }
        return windows.minOf { rows[it] ?: EPOCH }
    }

    /** The stored port of a resolved window; [watermark] = whether its close advances the program's rows. */
    fun port(
        from: Instant,
        to: Instant,
        windows: List<String>,
        watermark: Boolean,
    ): JsonObject =
        buildJsonObject {
            putJsonObject(PORT_KEY) {
                put("from", from.toString())
                put("to", to.toString())
                put("windows", JsonArray(windows.map(::JsonPrimitive)))
                put("watermark", watermark)
            }
        }

    /**
     * Called by the seal of a wet run, inside its transaction: when the run's stored port is a window that keeps the
     * watermark, every window's `last_to` moves to the run's `to` — never backwards, so a late close of an earlier
     * window cannot rewind a later one.
     */
    internal fun advanceAtSeal(
        runId: UUID,
        program: String?,
        port: JsonElement,
        now: OffsetDateTime,
    ) {
        val window = (port as? JsonObject)?.get(PORT_KEY) as? JsonObject ?: return
        if ((window["watermark"] as? JsonPrimitive)?.booleanOrNull != true) return
        requireNotNull(program) { "a windowed run has no program" }
        val to = Instant.parse(window.getValue("to").jsonPrimitive.content).atOffset(ZoneOffset.UTC)
        val names = window.getValue("windows").jsonArray.map { it.jsonPrimitive.content }
        val tx = checkNotNull(TransactionManager.currentOrNull()) { "the watermark advances inside the seal" }
        names.forEach { name ->
            tx.exec(
                """
                INSERT INTO automation.program_windows (program, "window", last_to, run, advanced_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (program, "window") DO UPDATE
                SET last_to = EXCLUDED.last_to, run = EXCLUDED.run, advanced_at = EXCLUDED.advanced_at
                WHERE program_windows.last_to < EXCLUDED.last_to
                """.trimIndent(),
                listOf<Pair<IColumnType<*>, Any>>(
                    TextColumnType() to program,
                    TextColumnType() to name,
                    JavaOffsetDateTimeColumnType() to to,
                    UUIDColumnType() to runId,
                    JavaOffsetDateTimeColumnType() to now,
                ),
            )
        }
    }
}
