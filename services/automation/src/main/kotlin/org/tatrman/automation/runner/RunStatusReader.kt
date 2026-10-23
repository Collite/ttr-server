// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.ledger.Ledger
import org.tatrman.automation.ledger.NoSuchRun
import java.util.UUID

/** Durable owner-scoped observation only. Polling never executes, retries or fails a run. */
class RunStatusReader(
    private val ledger: Ledger,
) {
    suspend fun read(
        id: UUID,
        caller: Caller,
    ): RunProgress =
        withContext(Dispatchers.IO) {
            try {
                ledger.readRunProgress(id, caller.sub)
            } catch (_: NoSuchRun) {
                // Unknown handles, other subjects and legacy rows have the same response.
                throw AutomationException(ErrorCode.IDENTITY, "the run is not available to this caller")
            }
        }
}
