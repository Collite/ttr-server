// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.queue

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tatrman.automation.LedgerTestDb

/**
 * pgmq over JDBC on the SQL-only install (D-130): a message round-trips with its visibility timeout, archives
 * and deletes, the metrics count it — and a send inside a transaction that rolls back never happened.
 */
class PgmqSpec :
    StringSpec({
        LedgerTestDb.ready()
        val pgmq = Pgmq()

        beforeEach { LedgerTestDb.reset() }

        fun msg(n: Int) = JsonObject(mapOf("run" to JsonPrimitive("r-$n")))

        "send → read hides the message for its visibility timeout, then it comes back with a higher read count" {
            val id = pgmq.send(LedgerQueue.RUNS, msg(1))
            val first = pgmq.read(LedgerQueue.RUNS, visibilitySeconds = 1).single()
            first.msgId shouldBe id
            first.message shouldBe msg(1)
            first.readCount shouldBe 1
            pgmq.read(LedgerQueue.RUNS, visibilitySeconds = 1).shouldBeEmpty()
            Thread.sleep(1_500)
            pgmq.read(LedgerQueue.RUNS, visibilitySeconds = 30).single().readCount shouldBe 2
        }

        "archive moves a handled message to the archive table; a second archive finds nothing" {
            val id = pgmq.send(LedgerQueue.TASKS, msg(2))
            pgmq.archive(LedgerQueue.TASKS, id) shouldBe true
            pgmq.archive(LedgerQueue.TASKS, id) shouldBe false
            pgmq.read(LedgerQueue.TASKS, visibilitySeconds = 30).shouldBeEmpty()
            transaction {
                exec("SELECT count(*) FROM pgmq.a_automation_tasks WHERE msg_id = $id") { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            } shouldBe 1L
        }

        "delete removes a message outright" {
            val id = pgmq.send(LedgerQueue.SIGNALS, msg(3))
            pgmq.delete(LedgerQueue.SIGNALS, id) shouldBe true
            pgmq.delete(LedgerQueue.SIGNALS, id) shouldBe false
        }

        "a delayed message is invisible until its delay passes" {
            pgmq.send(LedgerQueue.WINDOWS, msg(4), delaySeconds = 60)
            pgmq.read(LedgerQueue.WINDOWS, visibilitySeconds = 30).shouldBeEmpty()
        }

        "metrics count what waits in a queue" {
            repeat(3) { pgmq.send(LedgerQueue.RUNS, msg(it)) }
            pgmq.metrics(LedgerQueue.RUNS).queueLength shouldBe 3L
            pgmq.metrics(LedgerQueue.TASKS).queueLength shouldBe 0L
        }

        "a send in a rolled-back transaction never happened: the message commits with the ledger write or not at all" {
            shouldThrow<IllegalStateException> {
                transaction {
                    pgmq.send(LedgerQueue.RUNS, msg(5))
                    error("the ledger write after it failed")
                }
            }
            pgmq.metrics(LedgerQueue.RUNS).queueLength shouldBe 0L
        }
    })
