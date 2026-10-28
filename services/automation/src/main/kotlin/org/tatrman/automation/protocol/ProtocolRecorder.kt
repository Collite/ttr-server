// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package org.tatrman.automation.protocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import org.tatrman.automation.api.scalar
import kotlinx.serialization.json.JsonElement
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.ledger.Ledger
import org.tatrman.automation.ledger.NoSuchRun
import org.tatrman.automation.ledger.RunOutput
import org.tatrman.automation.runner.BoundValue
import java.util.UUID

/** Entries keep their original section sequence and timestamp; every document includes all nine sections. */
@Serializable
data class ProtocolEntry(
    val seq: Int,
    val at: String,
    val payload: JsonElement,
)

@Serializable
data class ProtocolDocument(
    val run: String,
    val sections: Map<String, List<ProtocolEntry>>,
    @EncodeDefault val schema: String = "protocol/v1",
)

@Serializable
data class ProtocolParameter(
    val type: String,
    val value: JsonElement,
)

/**
 * One successful storage part or published island port. A multi-output island has one entry per port;
 * shared parts have their own entries and are never counted again for each dependent combine.
 * Statement paths are relative to the run's pinned recipe bundle; `runtime:obligation-lookup/v2` and
 * `runtime:calendar-snapshot/v1` name the Server's versioned auxiliary-read templates. Neither form contains SQL or connection credentials.
 * Part byte counts are absent: the door exposes decoded rows, not the source's IPC byte count.
 */
@Serializable
data class IslandExecution(
    val kind: String,
    val name: String,
    val statement: String,
    val engine: String,
    val params: Map<String, ProtocolParameter>,
    val rows: Long,
    val durationMs: Long,
    val warnings: List<String>,
    val port: String? = null,
    val storage: String? = null,
    val bytes: Long? = null,
) {
    init {
        require(kind in setOf("part", "output") && name.isNotBlank() && statement.isNotBlank())
        require((kind == "output") == (port != null))
        require(rows >= 0 && durationMs >= 0 && (bytes == null || bytes >= 0))
    }
}

/** Durable evidence only. Reading never consults a current bundle, token cache, executor or Query. */
class ProtocolRecorder(
    private val ledger: Ledger,
) {
    fun part(
        run: UUID,
        step: IslandExecution,
    ) {
        require(step.kind == "part")
        ledger.recordExecution(run, step)
    }

    /** The output row and its evidence commit together, after immutable blob publication. */
    fun output(
        output: RunOutput,
        step: IslandExecution,
    ) = ledger.putOutput(output, step)

    suspend fun read(
        run: UUID,
        caller: Caller,
    ): ProtocolDocument =
        withContext(Dispatchers.IO) {
            try {
                ledger.readProtocol(run, caller.sub)
            } catch (_: NoSuchRun) {
                // Unknown, legacy-unowned and other subjects' handles are deliberately indistinguishable.
                throw AutomationException(ErrorCode.IDENTITY, "the protocol is not available to this caller")
            }
        }

    companion object {
        internal fun parameters(values: Map<String, BoundValue>): Map<String, ProtocolParameter> =
            values.mapValues { (_, binding) ->
                ProtocolParameter(binding.type, scalar(binding.value))
            }
    }
}
