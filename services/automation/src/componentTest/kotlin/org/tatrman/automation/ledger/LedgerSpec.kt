// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.ledger

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tatrman.automation.LedgerTestDb
import org.tatrman.automation.MutableClock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The ledger over a real Postgres: D-46's claim (replay · wait · free · abandon past budget + 2 min), D-97's
 * waiting run that the clock never abandons, the claim race on one fresh key, the status machine, and the
 * INSERT-only, sealed-at-close protocol (D-110).
 */
class LedgerSpec :
    StringSpec({
        LedgerTestDb.ready()
        val t0 = Instant.parse("2026-10-18T10:00:00Z")
        val clock = MutableClock(t0)
        val budget = Duration.ofMinutes(30)
        val abandonedRecipes = mutableListOf<String>()
        val ledger = Ledger(defaultBudget = budget, clock = clock, abandoned = { abandonedRecipes += it })

        beforeEach {
            LedgerTestDb.reset()
            clock.now = t0
            abandonedRecipes.clear()
        }

        fun open(key: String?) = RunOpen(recipe = "release_order", program = "nightly", idempotencyKey = key)

        fun statusOf(runId: UUID): String =
            transaction { Runs.select(Runs.status).where { Runs.id eq runId }.single()[Runs.status] }

        "staged outputs journal their exact Arrow metadata once and refuse changed content" {
            val run = ledger.claimRun(open(null)).runId
            val output = RunOutput(run, "shortfall", "rows", 2, 1024, "runs/$run/shortfall.rows.arrow", "sha256:abc")
            ledger.putOutput(output)
            ledger.putOutput(output)
            transaction {
                val rows = RunOutputs.selectAll().where { RunOutputs.run eq run }.toList()
                rows.size shouldBe 1
                rows.single()[RunOutputs.rows] shouldBe 2L
                rows.single()[RunOutputs.sha256] shouldBe "sha256:abc"
                rows.single()[RunOutputs.releasedAt] shouldBe null
            }
            shouldThrow<IllegalStateException> { ledger.putOutput(output.copy(sha256 = "sha256:changed")) }
            shouldThrow<NoSuchRun> { ledger.putOutput(output.copy(run = UUID.randomUUID())) }
        }

        "without a key every claim opens a new run" {
            val a = ledger.claimRun(open(null))
            val b = ledger.claimRun(open(null))
            a.shouldBeInstanceOf<Claim.Claimed>()
            b.shouldBeInstanceOf<Claim.Claimed>()
            a.runId shouldNotBe b.runId
            statusOf(a.runId) shouldBe "open"
        }

        "a live run is waited on; a closed one is replayed" {
            val first = ledger.claimRun(open("k-1")).shouldBeInstanceOf<Claim.Claimed>()
            ledger.claimRun(open("k-1")) shouldBe Claim.Running(first.runId, RunStatus.OPEN)
            ledger.markStatus(first.runId, RunStatus.RUNNING)
            ledger.claimRun(open("k-1")) shouldBe Claim.Running(first.runId, RunStatus.RUNNING)
            ledger.markStatus(first.runId, RunStatus.CLOSED)
            clock.now = t0 + Duration.ofDays(30)
            ledger.claimRun(open("k-1")) shouldBe Claim.Replay(first.runId)
        }

        "a failed run frees its key: the next claim opens beside it, and the failed one stays failed" {
            val first = ledger.claimRun(open("k-2")).runId
            ledger.markStatus(first, RunStatus.FAILED, JsonObject(mapOf("code" to JsonPrimitive("door-refused"))))
            val second = ledger.claimRun(open("k-2")).shouldBeInstanceOf<Claim.Claimed>()
            second.runId shouldNotBe first
            second.abandoned shouldBe null
            statusOf(first) shouldBe "failed"
        }

        "a live run past its budget + 2 min is abandoned, and the claim opens anew" {
            val first = ledger.claimRun(open("k-3")).runId
            ledger.markStatus(first, RunStatus.RUNNING)
            clock.now = t0 + budget + IdempotencyPolicy.GRACE
            ledger.claimRun(open("k-3")) shouldBe Claim.Running(first, RunStatus.RUNNING)
            clock.now = clock.now + Duration.ofSeconds(1)
            val second = ledger.claimRun(open("k-3")).shouldBeInstanceOf<Claim.Claimed>()
            second.abandoned shouldBe first
            statusOf(first) shouldBe "abandoned"
            ledger.countAbandoned() shouldBe 1L
            abandonedRecipes shouldContainExactly listOf("release_order")
            transaction { Runs.selectAll().where { Runs.id eq first }.single()[Runs.endedAt] }.shouldNotBeNull()
        }

        "a run's own batch budget overrides the default" {
            val first = ledger.claimRun(open("k-4").copy(budgetBatch = Duration.ofMinutes(5))).runId
            clock.now = t0 + Duration.ofMinutes(8)
            ledger.claimRun(open("k-4")).shouldBeInstanceOf<Claim.Claimed>().abandoned shouldBe first
        }

        "a waiting run is never abandoned by the clock (D-97)" {
            val first = ledger.claimRun(open("k-5")).runId
            ledger.markStatus(first, RunStatus.WAITING)
            clock.now = t0 + Duration.ofDays(365)
            ledger.claimRun(open("k-5")) shouldBe Claim.Running(first, RunStatus.WAITING)
            statusOf(first) shouldBe "waiting"
        }

        "eight claims of one fresh key race: exactly one opens, the other seven wait on it" {
            val pool = Executors.newFixedThreadPool(8)
            val start = CountDownLatch(1)
            val futures =
                (1..8).map {
                    pool.submit<Claim> {
                        start.await()
                        ledger.claimRun(open("k-race"))
                    }
                }
            start.countDown()
            val claims = futures.map { it.get(60, TimeUnit.SECONDS) }
            pool.shutdown()
            val claimed = claims.filterIsInstance<Claim.Claimed>()
            claimed.size shouldBe 1
            claims.filterIsInstance<Claim.Running>().map { it.runId }.toSet() shouldBe setOf(claimed.single().runId)
            transaction { Runs.selectAll().where { Runs.idempotencyKey eq "k-race" }.count() } shouldBe 1L
        }

        "the status machine: a terminal status stamps ended_at; an illegal move and an unknown run are refused" {
            val run = ledger.claimRun(open(null)).runId
            ledger.markStatus(run, RunStatus.WAITING)
            shouldThrow<IllegalRunTransition> { ledger.markStatus(run, RunStatus.ABANDONED) }
            shouldThrow<IllegalRunTransition> { ledger.markStatus(run, RunStatus.RUNNING) }
            ledger.markStatus(run, RunStatus.CLOSED)
            transaction { Runs.selectAll().where { Runs.id eq run }.single()[Runs.endedAt] }.shouldNotBeNull()
            shouldThrow<IllegalRunTransition> { ledger.markStatus(run, RunStatus.FAILED) }
            shouldThrow<NoSuchRun> { ledger.markStatus(UUID.randomUUID(), RunStatus.RUNNING) }
        }

        "the protocol numbers each section's entries from 1, and refuses every append once sealed (D-110)" {
            val run = ledger.claimRun(open(null)).runId
            ledger.appendProtocol(
                run,
                ProtocolSection.AUTOMATION,
                JsonObject(
                    mapOf(
                        "recipe" to JsonPrimitive("release_order"),
                    ),
                ),
            ) shouldBe
                1
            ledger.appendProtocol(run, ProtocolSection.ISLANDS, JsonObject(emptyMap())) shouldBe 1
            ledger.appendProtocol(run, ProtocolSection.ISLANDS, JsonObject(emptyMap())) shouldBe 2
            ledger.sealProtocol(run) shouldBe 2
            shouldThrow<ProtocolSealed> { ledger.appendProtocol(run, ProtocolSection.ACTIONS, JsonObject(emptyMap())) }
            shouldThrow<NoSuchRun> {
                ledger.appendProtocol(
                    UUID.randomUUID(),
                    ProtocolSection.ACTIONS,
                    JsonObject(emptyMap()),
                )
            }
        }

        "the claim and the append refuse a caller's transaction: their retry and isolation need their own" {
            val run = ledger.claimRun(open(null)).runId
            shouldThrow<IllegalStateException> { transaction { ledger.claimRun(open("k-6")) } }.message shouldContain
                "claimRun"
            shouldThrow<IllegalStateException> {
                transaction { ledger.appendProtocol(run, ProtocolSection.SIGNALS, JsonObject(emptyMap())) }
            }.message shouldContain "appendProtocol"
            transaction { Runs.selectAll().where { Runs.idempotencyKey eq "k-6" }.count() } shouldBe 0L
        }

        "concurrent appends to one section number without a gap or a clash" {
            val run = ledger.claimRun(open(null)).runId
            val pool = Executors.newFixedThreadPool(6)
            val start = CountDownLatch(1)
            val futures =
                (1..12).map { i ->
                    pool.submit<Int> {
                        start.await()
                        ledger.appendProtocol(run, ProtocolSection.SIGNALS, JsonObject(mapOf("i" to JsonPrimitive(i))))
                    }
                }
            start.countDown()
            futures.map { it.get(60, TimeUnit.SECONDS) }.sorted() shouldContainExactly (1..12).toList()
            pool.shutdown()
            transaction {
                RunProtocol
                    .select(RunProtocol.seq)
                    .where { (RunProtocol.run eq run) and (RunProtocol.section eq "signals") }
                    .orderBy(RunProtocol.seq, SortOrder.ASC)
                    .map { it[RunProtocol.seq] }
            } shouldContainExactly (1..12).toList()
        }
    })
