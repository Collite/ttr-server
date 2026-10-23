// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.protocol

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.flywaydb.core.Flyway
import org.tatrman.automation.LedgerTestDb
import org.tatrman.automation.migrateLedger

class SubjectMigrationSpec :
    StringSpec({
        "V6 upgrades existing runs without guessing ownership from a reused caller label" {
            LedgerTestDb.asSuperuser { it.execute("CREATE DATABASE protocol_upgrade") }
            val source = LedgerTestDb.dataSource("protocol_upgrade", "automation", "automation")
            Flyway
                .configure()
                .dataSource(source)
                .schemas("automation")
                .target("5")
                .load()
                .migrate()
            source.connection.use { connection ->
                connection.createStatement().use {
                    it.execute(
                        "INSERT INTO automation.runs (id, recipe, caller, status) " +
                            "VALUES (gen_random_uuid(), 'r', 'maya', 'open')",
                    )
                }
            }
            migrateLedger(source) shouldBe 2
            source.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement
                        .executeQuery(
                            "SELECT caller, caller_subject, staging_released_at, staging_checked_at FROM automation.runs",
                        ).use {
                            it.next() shouldBe true
                            it.getString("caller") shouldBe "maya"
                            it.getString("caller_subject") shouldBe null
                            it.getObject("staging_released_at") shouldBe null
                            it.getObject("staging_checked_at") shouldBe null
                        }
                }
            }
        }
    })
