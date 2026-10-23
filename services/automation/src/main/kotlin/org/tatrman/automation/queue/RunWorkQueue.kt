// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.queue

import java.util.UUID

/** Queue payloads carry handles only, never bearer tokens, bindings or reconstructed execution contexts. */
data class RunDelivery(
    val messageId: Long,
    val run: UUID,
    val owner: UUID,
)

interface RunWorkQueue {
    fun enqueue(
        run: UUID,
        owner: UUID,
    )

    fun read(
        owner: UUID,
        visibilitySeconds: Int,
    ): RunDelivery?

    /** A committed claim is permanent, even after visibility expires. Lost execution is abandoned, not retried. */
    fun claim(delivery: RunDelivery): Boolean

    fun archive(delivery: RunDelivery)

    fun archiveFinished(limit: Int = 100): Int
}
