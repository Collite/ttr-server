// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.ledger.RunStatus
import java.util.UUID

class AbandonedSessionsSpec :
    StringSpec({
        "reaping durable abandonment cancels and joins a pinned read before releasing its executor" {
            val entered = CompletableDeferred<Unit>()
            val cancelling = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val fixture =
                RunSessionFixture(
                    door =
                        Door {
                            entered.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                withContext(NonCancellable) {
                                    cancelling.complete(Unit)
                                    finish.await()
                                }
                            }
                        },
                )
            try {
                val id = UUID.fromString(fixture.open().response.runId)
                coroutineScope {
                    val reading = launch { fixture.runs.read(id, fixture.caller, { "placed" }) { r, n -> r.island(n) } }
                    withTimeout(5000) { entered.await() }
                    fixture.states[id] = RunStatus.ABANDONED // Could be a sweep or same-key claim on another pod.
                    val reap = async { fixture.runs.reapAbandoned() }
                    withTimeout(5000) { cancelling.await() }
                    reap.isCompleted shouldBe false
                    finish.complete(Unit)
                    reap.await()
                    reading.join()
                }
                fixture.outputs.size shouldBe 0
                fixture.evaluations.size shouldBe 0
                fixture.states[id] shouldBe RunStatus.ABANDONED
                // No old executor may reappear, even if a broken external writer were to restore its status.
                fixture.states[id] = RunStatus.OPEN
                shouldThrow<AutomationException> { fixture.runs.progress(id, fixture.caller) }.code shouldBe
                    ErrorCode.RUN_ABANDONED
            } finally {
                finish.complete(Unit)
                fixture.close()
            }
        }

        "reaping the abandoned predecessor never releases the new same-key executor or starts any Query" {
            val fixture = RunSessionFixture()
            try {
                val old = UUID.fromString(fixture.open().response.runId)
                fixture.states[old] = RunStatus.ABANDONED
                val fresh = UUID.fromString(fixture.open().response.runId)
                fixture.runs.reapAbandoned()
                fixture.runs.reapAbandoned() // Empty sweeps must still be safe to reconcile repeatedly.
                fixture.runs.progress(fresh, fixture.caller).status shouldBe "open"
                fixture.states[old] = RunStatus.OPEN
                shouldThrow<AutomationException> { fixture.runs.progress(old, fixture.caller) }.code shouldBe
                    ErrorCode.RUN_ABANDONED
                fixture.created.get() shouldBe 2
                fixture.reads.get() shouldBe 0
            } finally {
                fixture.close()
            }
        }

        "the abandonment reaper leaves waiting, successful and failed-refusal sessions intact" {
            val fixture = RunSessionFixture()
            try {
                val ids =
                    RunStatus.entries.filter { it != RunStatus.ABANDONED }.associateWith { state ->
                        val id = UUID.fromString(fixture.open(key = state.wire).response.runId)
                        fixture.states[id] = state
                        id
                    }
                val reads = fixture.statusReads.get()
                fixture.runs.reapAbandoned()
                fixture.statusReads.get() shouldBe reads + ids.size
                ids.forEach { (_, id) ->
                    fixture.states[id] = RunStatus.OPEN
                    fixture.runs.progress(id, fixture.caller).status shouldBe "open"
                }
                fixture.reads.get() shouldBe 0
            } finally {
                fixture.close()
            }
        }

        "a failed status lookup is retried later while other abandoned sessions are still released" {
            var broken: UUID? = null
            val fixture = RunSessionFixture(beforeStatus = { if (it == broken) error("status unavailable") })
            try {
                val first = UUID.fromString(fixture.open(key = "first").response.runId)
                val second = UUID.fromString(fixture.open(key = "second").response.runId)
                fixture.states[first] = RunStatus.ABANDONED
                fixture.states[second] = RunStatus.ABANDONED
                broken = first
                shouldThrow<IllegalStateException> { fixture.runs.reapAbandoned() }
                broken = null
                fixture.states[second] = RunStatus.OPEN
                shouldThrow<AutomationException> { fixture.runs.progress(second, fixture.caller) }.code shouldBe
                    ErrorCode.RUN_ABANDONED
                fixture.states[first] = RunStatus.OPEN
                fixture.runs.progress(first, fixture.caller).status shouldBe "open"
                fixture.states[first] = RunStatus.ABANDONED
                fixture.runs.reapAbandoned()
                fixture.states[first] = RunStatus.OPEN
                shouldThrow<AutomationException> { fixture.runs.progress(first, fixture.caller) }.code shouldBe
                    ErrorCode.RUN_ABANDONED
            } finally {
                fixture.close()
            }
        }
    })
