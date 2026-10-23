// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.ledger.*
import java.util.UUID

@Serializable
data class CloseResult(
    val status: String,
    val batch: RunBatch,
    val protocolRef: String,
)

/** Durable close first; then join local work before releasing DuckDB. Blob grace retention is separate. */
class RunCloser(
    private val ledger: Ledger,
    private val release: suspend (UUID) -> Unit = {},
) {
    suspend fun close(
        id: UUID,
        caller: Caller,
    ): CloseResult =
        withContext(NonCancellable) {
            val result =
                withContext(Dispatchers.IO) {
                    try {
                        ledger.closePreview(id, caller.sub)
                    } catch (_: NoSuchRun) {
                        hidden()
                    } catch (_: ProtocolSealed) {
                        sealed()
                    } catch (_: IllegalRunTransition) {
                        throw AutomationException(
                            ErrorCode.CLOSE_NOT_READY,
                            "the run must finish evaluation before close",
                        )
                    } catch (_: NoRunBatch) {
                        throw AutomationException(ErrorCode.CLOSE_NOT_READY, "the run has no completed preview batch")
                    }
                }
            // A cleanup failure leaves the committed close retryable; a retry attempts release again.
            release(id)
            result
        }

    suspend fun authorizeExecution(
        id: UUID,
        caller: Caller,
    ) = withContext(Dispatchers.IO) {
        try {
            ledger.checkRunExecution(id, caller.sub)
        } catch (_: NoSuchRun) {
            hidden()
        } catch (_: ProtocolSealed) {
            sealed()
        }
    }

    private fun hidden(): Nothing =
        throw AutomationException(ErrorCode.IDENTITY, "the run is not available to this caller")

    private fun sealed(): Nothing = throw AutomationException(ErrorCode.RUN_CLOSED, "the run protocol is sealed")
}
