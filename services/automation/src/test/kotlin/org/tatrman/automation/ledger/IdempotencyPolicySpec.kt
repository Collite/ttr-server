// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.ledger

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** D-46's exactly-one rule, and D-97's exception to it, as the pure decision the claim acts on. */
class IdempotencyPolicySpec :
    StringSpec({
        val started = Instant.parse("2026-10-18T10:00:00Z")
        val budget = Duration.ofMinutes(30)
        val id = UUID.randomUUID()

        fun holder(status: RunStatus) = KeyHolder(id, status, started, budget)

        val withinBudget = started + budget
        val pastBudgetAndGrace = started + budget + IdempotencyPolicy.GRACE + Duration.ofSeconds(1)

        "no holder opens a run" {
            IdempotencyPolicy.decide(null, withinBudget) shouldBe ClaimDecision.Open
        }

        "a done (closed) run is replayed, however old" {
            IdempotencyPolicy.decide(holder(RunStatus.CLOSED), pastBudgetAndGrace) shouldBe ClaimDecision.Replay(id)
        }

        "a failed or abandoned run frees its key" {
            IdempotencyPolicy.decide(holder(RunStatus.FAILED), withinBudget) shouldBe ClaimDecision.Open
            IdempotencyPolicy.decide(holder(RunStatus.ABANDONED), withinBudget) shouldBe ClaimDecision.Open
        }

        "a live run within its budget is waited on" {
            for (status in listOf(RunStatus.OPEN, RunStatus.RUNNING, RunStatus.EVALUATED)) {
                IdempotencyPolicy.decide(holder(status), withinBudget) shouldBe ClaimDecision.Wait(id, status)
            }
        }

        "a live run is still waited on at budget + grace exactly, and abandoned one second later" {
            val atTheEdge = started + budget + IdempotencyPolicy.GRACE
            IdempotencyPolicy.decide(holder(RunStatus.RUNNING), atTheEdge) shouldBe
                ClaimDecision.Wait(id, RunStatus.RUNNING)
            IdempotencyPolicy.decide(holder(RunStatus.RUNNING), pastBudgetAndGrace) shouldBe
                ClaimDecision.AbandonThenOpen(id)
            IdempotencyPolicy.decide(holder(RunStatus.EVALUATED), pastBudgetAndGrace) shouldBe
                ClaimDecision.AbandonThenOpen(id)
            IdempotencyPolicy.decide(holder(RunStatus.OPEN), pastBudgetAndGrace) shouldBe
                ClaimDecision.AbandonThenOpen(id)
        }

        "a waiting run is never abandoned by the clock (D-97)" {
            val aYearLater = started + Duration.ofDays(365)
            IdempotencyPolicy.decide(holder(RunStatus.WAITING), aYearLater) shouldBe
                ClaimDecision.Wait(id, RunStatus.WAITING)
        }

        "a waiting run leaves only by its last answer or due — closed or failed, never abandoned" {
            RunStatus.WAITING.next shouldContainExactlyInAnyOrder setOf(RunStatus.CLOSED, RunStatus.FAILED)
        }

        "terminal statuses go nowhere; a status never moves to itself" {
            for (status in RunStatus.entries) {
                if (status.terminal) status.next shouldBe emptySet()
                status.next shouldNotContain status
            }
        }

        "the wire names are the contract's and round-trip" {
            RunStatus.entries.map { it.wire } shouldBe
                listOf("open", "running", "evaluated", "waiting", "closed", "failed", "abandoned")
            RunStatus.entries.forEach { RunStatus.of(it.wire) shouldBe it }
        }
    })
