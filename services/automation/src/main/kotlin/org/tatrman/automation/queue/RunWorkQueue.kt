// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.queue

import java.util.UUID

/** A delivery carries handles only, never bearer tokens, bindings or reconstructed execution contexts. */
data class RunDelivery(
    val run: UUID,
    val owner: UUID,
)

interface RunWorkQueue {
    fun enqueue(
        run: UUID,
        owner: UUID,
    )

    /** The owner's oldest visible ticket, hidden for [visibilitySeconds]; null when there is none. */
    fun read(
        owner: UUID,
        visibilitySeconds: Int,
    ): RunDelivery?

    /** A committed claim is permanent, even after visibility expires. Lost execution is abandoned, not retried. */
    fun claim(delivery: RunDelivery): Boolean

    fun archive(delivery: RunDelivery)

    fun archiveFinished(limit: Int = 100): Int
}
