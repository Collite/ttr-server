// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.ledger

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.automation.LedgerTestDb
import org.tatrman.automation.migrateLedger
import org.tatrman.automation.queue.LedgerQueue

/**
 * D-130 on a real estate: a cluster's service role OWNS its database and is no superuser (hartland-pg's CNPG
 * managed role). The other component specs migrate as the container's superuser, so this one proves the
 * vendored pgmq install — and the ledger with its triggers — needs nothing more than ownership.
 */
class OwnerMigrationSpec :
    StringSpec({
        "the ledger, pgmq and the queues migrate as a plain database owner, and pgmq round-trips" {
            LedgerTestDb.asSuperuser {
                it.execute("CREATE ROLE ledger_owner LOGIN PASSWORD 'owner' NOSUPERUSER NOCREATEDB NOCREATEROLE")
                it.execute("CREATE DATABASE ledger_owned OWNER ledger_owner")
            }
            val owned = LedgerTestDb.dataSource("ledger_owned", "ledger_owner", "owner")

            migrateLedger(owned) shouldBe 7

            owned.connection.use { c ->
                c.createStatement().use { s ->
                    s.executeQuery("SELECT rolsuper FROM pg_roles WHERE rolname = current_user").use { rs ->
                        rs.next()
                        rs.getBoolean(1) shouldBe false
                    }
                    val queue = LedgerQueue.entries.first().queueName
                    s.executeQuery("SELECT * FROM pgmq.send('$queue', '{\"run\": 1}'::jsonb)").use {
                        it.next() shouldBe
                            true
                    }
                    s.executeQuery("SELECT message->>'run' FROM pgmq.read('$queue', 30, 1)").use { rs ->
                        rs.next() shouldBe true
                        rs.getString(1) shouldBe "1"
                    }
                }
            }
        }
    })
