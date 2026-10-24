// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.*
import org.tatrman.automation.ledger.StagingRetention
import org.tatrman.automation.staging.StagingStore
import org.tatrman.automation.staging.StagingSweeper
import java.util.UUID

class StagingSweeperSpec :
    StringSpec({
        "fence commits before executor release, deletion and index release" {
            val id = UUID.randomUUID()
            val events = mutableListOf<String>()
            val ledger = mockk<StagingRetention>()
            val store = mockk<StagingStore>()
            every { ledger.candidates(any()) } returns listOf(id)
            every { ledger.fence(id) } answers {
                events.add("fence")
                true
            }
            every { ledger.sweep(id, any()) } answers {
                secondArg<(UUID) -> Unit>().invoke(id)
                events.add("index")
            }
            every { store.deleteRun(id) } answers {
                events.add("delete")
                Unit
            }
            StagingSweeper(ledger, store) { events.add("release") }.sweep()
            events shouldBe listOf("fence", "release", "delete", "index")
        }

        "failed cleanup leaves deletion retryable without blocking other candidates" {
            val failed = UUID.randomUUID()
            val good = UUID.randomUUID()
            val ledger = mockk<StagingRetention>()
            val store = mockk<StagingStore>()
            every { ledger.candidates(any()) } returns listOf(failed, good)
            every { ledger.fence(any()) } returns true
            every { ledger.sweep(any(), any()) } just Runs
            var fail = true
            val sweeper = StagingSweeper(ledger, store) { if (it == failed && fail) error("executor cleanup failed") }
            sweeper.sweep()
            verify(exactly = 0) { ledger.sweep(failed, any()) }
            verify(exactly = 1) { ledger.sweep(good, any()) }
            fail = false
            sweeper.sweep()
            verify(exactly = 1) { ledger.sweep(failed, any()) }
        }

        "shutdown cancellation joins a fenced cleanup before stopping the pass" {
            val id = UUID.randomUUID()
            val later = UUID.randomUUID()
            val ledger = mockk<StagingRetention>()
            val store = mockk<StagingStore>()
            every { ledger.candidates(any()) } returns listOf(id, later)
            every { ledger.fence(any()) } returns true
            every { ledger.sweep(any(), any()) } just Runs
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val task =
                launch {
                    StagingSweeper(ledger, store) {
                        entered.complete(Unit)
                        finish.await()
                    }.sweep()
                }
            withTimeout(5000) { entered.await() }
            task.cancel()
            task.isCompleted shouldBe false
            finish.complete(Unit)
            withTimeout(5000) { task.join() }
            verify(exactly = 1) { ledger.sweep(id, any()) }
            verify(exactly = 0) { ledger.fence(later) }
        }

        "a candidate which no longer qualifies is not cleaned" {
            val id = UUID.randomUUID()
            val ledger = mockk<StagingRetention>()
            val store = mockk<StagingStore>()
            every { ledger.candidates(any()) } returns listOf(id)
            every { ledger.fence(id) } returns false
            val release = mockk<suspend (UUID) -> Unit>()
            StagingSweeper(ledger, store, release).sweep()
            coVerify(exactly = 0) { release(any()) }
            verify(exactly = 0) { ledger.sweep(any(), any()) }
        }
    })
