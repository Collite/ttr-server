// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.staging

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.tatrman.automation.ledger.StagingRetention
import java.util.UUID

/** RunWorkers invokes this on its maintenance timer; shutdown joins the current pass. */
class StagingSweeper(
    private val retention: StagingRetention,
    private val store: StagingStore,
    private val release: suspend (UUID) -> Unit,
) {
    suspend fun sweep() {
        val candidates = withContext(Dispatchers.IO) { retention.candidates() }
        for (id in candidates) {
            currentCoroutineContext().ensureActive()
            // Once fenced, finish this run's cleanup even when the maintenance job is shutting down.
            withContext(NonCancellable) {
                try {
                    if (withContext(Dispatchers.IO) { retention.fence(id) }) {
                        release(id) // Join local work before deleting its snapshots.
                        withContext(Dispatchers.IO) { retention.sweep(id, store::deleteRun) }
                    }
                } catch (e: Exception) {
                    log.warn("staging cleanup failed for run {}; the next pass will retry", id, e)
                }
            }
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(StagingSweeper::class.java)
    }
}
