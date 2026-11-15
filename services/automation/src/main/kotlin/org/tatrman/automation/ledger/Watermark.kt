// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.ledger

import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.IntegerColumnType
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.java.UUIDColumnType
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.javatime.JavaOffsetDateTimeColumnType
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * D-143 (S3.2 T2): a windowed program's watermark, one `program_windows` row per (program, window). There is no
 * window runtime: the engine's timer opens `port: window`, Open resolves `[from, to)` = `[last_to, as_of)` (the
 * epoch before the first close), and the close that seals a wet run advances `last_to` to its `to` in the seal's
 * transaction. A dry run, a failed run, a close with any transient row (D-195 (C), D-202 (F5): `unknown`, a door or
 * transport failure — `RowOutcome.holds`; a settled failure such as `stale-state` does not count) and a run over
 * explicit bounds never advance it.
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
    ): Instant = transaction { current(program, windows).first }

    /** The watermark and the run that last advanced it (null: never advanced), read in the current transaction. */
    private fun current(
        program: String,
        windows: List<String>,
    ): Pair<Instant, UUID?> {
        require(windows.isNotEmpty())
        val rows =
            ProgramWindows
                .selectAll()
                .where { (ProgramWindows.program eq program) and (ProgramWindows.window inList windows) }
                .associate {
                    it[ProgramWindows.window] to
                        (it[ProgramWindows.lastTo].toInstant() to it[ProgramWindows.run])
                }
        return windows.map { rows[it] ?: (EPOCH to null) }.minBy { it.first }
    }

    /** A stored port's window when it keeps the watermark (its seal advances the program's rows); else null. */
    fun keeping(port: JsonElement): JsonObject? =
        ((port as? JsonObject)?.get(PORT_KEY) as? JsonObject)
            ?.takeIf { (it["watermark"] as? JsonPrimitive)?.booleanOrNull == true }

    /** Whether the claim of [open] must be guarded by [guardOpen] (and so read committed, after the program lock). */
    fun guards(open: RunOpen): Boolean = !open.dryRun && keeping(open.port) != null

    /**
     * The first int4 of a program lock's two-key form (`pg_advisory_xact_lock(int4, int4)` lives in a key space of its
     * own, apart from the single-`int8` `hashtext` locks of the bundles, review-280 N5); the second is `hashtext(program)`.
     */
    const val LOCK_NAMESPACE: Int = 0x4155574D

    /**
     * ✅R280-A / review-280 N1: the claim of a guarded open takes its program's lock as its FIRST statement, before
     * the idempotency key's holder is read. Read committed, the holder read then sees a same-key claim that won the
     * lock and committed, so a retry racing its own first attempt answers `202` (or `200`), never `409 window-open`.
     */
    internal fun lockProgram(open: RunOpen) {
        if (!guards(open)) return
        val program = requireNotNull(open.program) { "a windowed run has no program" }
        val tx = checkNotNull(TransactionManager.currentOrNull()) { "the program lock is taken inside the claim" }
        tx.exec(
            "SELECT pg_advisory_xact_lock(?, hashtext(?))",
            listOf<Pair<IColumnType<*>, Any>>(IntegerColumnType() to LOCK_NAMESPACE, TextColumnType() to program),
        ) {}
    }

    /**
     * ✅R280-A (review-280 F1) + D-202 (F3): called by the claim, in its transaction, after [lockProgram] and before a
     * new wet watermark run is inserted. The program lock serialises its watermark opens across replicas; the claim runs
     * read committed, so these reads see every seal and claim committed before it. The open refuses `409 window-open`
     * (the two runs would cover the same range) while another wet watermark run of the program
     * - is unsealed (`open · running · evaluated`; detail `reason: unsealed`), or
     * - is `waiting` and its seal did not advance (its window's `to` is past the watermark: a transient row held it,
     *   D-195 (C)) — a re-read now would evaluate its held rows again (`reason: waiting`; a `waiting` run whose seal
     *   advanced does not block), or
     * when the watermark moved past this open's `from` since Open resolved it (`reason: moved`). A dry run and an
     * explicit window neither block nor are blocked.
     */
    internal fun guardOpen(open: RunOpen) {
        if (!guards(open)) return
        val window = keeping(open.port) ?: return
        val program = requireNotNull(open.program) { "a windowed run has no program" }
        val tx = checkNotNull(TransactionManager.currentOrNull()) { "the window guard runs inside the claim" }
        val from = Instant.parse(window.getValue("from").jsonPrimitive.content)
        val to = Instant.parse(window.getValue("to").jsonPrimitive.content)
        val names = window.getValue("windows").jsonArray.map { it.jsonPrimitive.content }
        // The order matters (review-293 F3): a close neither takes the program lock nor waits for it, and each read
        // here sees what was committed before it ran (read committed). So `unsealed` comes first and the watermark
        // after it — a run that seals and advances between the two is then either still unsealed or already moved —
        // and `waiting` last, bound to that watermark (a run's WAITING status and its seal commit together).
        val unsealed =
            tx.exec(
                """
                SELECT id FROM automation.runs
                WHERE program = ? AND NOT dry_run AND status IN ('${RunStatus.OPEN.wire}', '${RunStatus.RUNNING.wire}',
                    '${RunStatus.EVALUATED.wire}')
                  AND (port -> '$PORT_KEY' ->> 'watermark') = 'true'
                ORDER BY started_at
                LIMIT 1
                """.trimIndent(),
                listOf<Pair<IColumnType<*>, Any>>(TextColumnType() to program),
            ) { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) else null }
        val (watermark, advancedBy) = current(program, names)
        val waiting =
            tx.exec(
                """
                SELECT id FROM automation.runs
                WHERE program = ? AND NOT dry_run AND status = '${RunStatus.WAITING.wire}'
                  AND (port -> '$PORT_KEY' ->> 'watermark') = 'true'
                  AND (port -> '$PORT_KEY' ->> 'to')::timestamptz > ?
                ORDER BY started_at
                LIMIT 1
                """.trimIndent(),
                listOf<Pair<IColumnType<*>, Any>>(
                    TextColumnType() to program,
                    JavaOffsetDateTimeColumnType() to watermark.atOffset(ZoneOffset.UTC),
                ),
            ) { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) else null }
        val reason =
            when {
                unsealed != null -> "unsealed"
                waiting != null -> "waiting"
                watermark != from -> "moved"
                else -> return
            }
        throw AutomationException(
            ErrorCode.WINDOW_OPEN,
            when (reason) {
                "unsealed" -> "another run of program $program holds its window until it closes"
                "waiting" ->
                    "a waiting run of program $program did not advance its watermark; it holds the window until its " +
                        "held groups are decided"
                else -> "the watermark of program $program moved since this open resolved its window"
            },
            buildJsonObject {
                put("program", program)
                put("run", (unsealed ?: waiting ?: advancedBy)?.toString())
                put("reason", reason)
                put("from", from.toString())
                put("to", to.toString())
                put("watermark", watermark.toString())
            },
        )
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
     * window cannot rewind a later one. Public for the component spec's direct never-rewind case (review-280 N4).
     */
    fun advanceAtSeal(
        runId: UUID,
        program: String?,
        port: JsonElement,
        now: OffsetDateTime,
    ) {
        val window = keeping(port) ?: return
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
