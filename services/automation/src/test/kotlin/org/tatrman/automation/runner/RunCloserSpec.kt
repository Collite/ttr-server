// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.ledger.*
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RunCloserSpec :
    StringSpec({
        val id = UUID.randomUUID()
        val ledger = mockk<Ledger>()
        val caller = Caller("subject", "maya", emptySet(), org.tatrman.automation.identity.TokenKind.PERSON, "bearer")
        val result = CloseResult("closed", RunBatch(UUID.randomUUID().toString(), "preview", 0), "protocol")
        beforeEach { clearMocks(ledger) }

        "a disconnected close waiter still finishes commit and resource release" {
            val entered = CompletableDeferred<Unit>()
            val committed = CountDownLatch(1)
            val releaseEntered = CompletableDeferred<Unit>()
            val releaseDone = CompletableDeferred<Unit>()
            every { ledger.closePreview(id, caller.sub) } answers {
                entered.complete(Unit)
                check(committed.await(5, TimeUnit.SECONDS))
                result
            }
            val closer =
                RunCloser(ledger) {
                    releaseEntered.complete(Unit)
                    releaseDone.await()
                }
            coroutineScope {
                val waiter = launch { closer.close(id, caller) }
                withTimeout(5000) { entered.await() }
                waiter.cancel()
                committed.countDown()
                withTimeout(5000) { releaseEntered.await() }
                waiter.isCompleted shouldBe false
                releaseDone.complete(Unit)
                withTimeout(5000) { waiter.join() }
            }
            verify(exactly = 1) { ledger.closePreview(id, caller.sub) }
            confirmVerified(ledger)
        }

        "cleanup failure leaves durable close retryable and retries cleanup" {
            every { ledger.closePreview(id, caller.sub) } returns result
            var releases = 0
            val closer = RunCloser(ledger) { if (++releases == 1) error("cleanup refused") }
            shouldThrow<IllegalStateException> { closer.close(id, caller) }
            closer.close(id, caller) shouldBe result
            releases shouldBe 2
        }

        "failed executor cleanup retains the cancelled session for a later release attempt" {
            val fixture = RunSessionFixture()
            val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val seed = fixture.open()
                val context = seed.context!!
                val policy = mockk<Open>()
                every { policy.open(any(), any(), any()) } returns seed
                val runner = mockk<PartRunner>()
                every { runner.context } returns context
                every { runner.evaluationRecorded } returns true
                var attempts = 0
                every { runner.close() } answers { if (++attempts == 1) error("close refused") }
                val sessions =
                    RunSessions(
                        policy,
                        parent,
                        { _, _ -> runner },
                        { RunStatus.EVALUATED },
                        { _, _ -> },
                        clock = org.tatrman.automation.bundle.BundleFixture.clock,
                    )
                sessions.open(
                    "release_order",
                    OpenRequest(
                        "release_native_event",
                        buildJsonObject {
                            put("key", "4711")
                        },
                    ),
                    fixture.caller,
                )
                shouldThrow<IllegalStateException> { sessions.release(context.id) }
                sessions.release(context.id)
                sessions.release(context.id)
                sessions.shutdown()
                attempts shouldBe 2
            } finally {
                parent.cancel()
                fixture.close()
            }
        }

        "execution authorization maps owner and seal refusals before a local session is needed" {
            val closer = RunCloser(ledger)
            every { ledger.checkRunExecution(id, caller.sub) } throws NoSuchRun(id)
            shouldThrow<AutomationException> { closer.authorizeExecution(id, caller) }.code shouldBe ErrorCode.IDENTITY
            every { ledger.checkRunExecution(id, caller.sub) } throws ProtocolSealed(id)
            shouldThrow<AutomationException> { closer.authorizeExecution(id, caller) }.code shouldBe
                ErrorCode.RUN_CLOSED
            every { ledger.checkRunExecution(id, caller.sub) } returns Unit
            closer.authorizeExecution(id, caller)
        }
    })
