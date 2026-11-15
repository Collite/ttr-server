// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.ledger

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.automation.LedgerTestDb
import org.tatrman.automation.migrateLedger

/**
 * D-130 on a real estate: a cluster's service role OWNS its database and is no superuser (hartland-pg's CNPG
 * managed role). The other component specs migrate as the container's superuser, so this one proves the ledger with
 * its triggers needs nothing more than ownership — including V12 (D-142), which drops the SQL-only queue install
 * V2/V3 created (its schema and default privileges) as that same owner.
 */
class OwnerMigrationSpec :
    StringSpec({
        "the ledger migrates V1–V13 as a plain database owner, and V12 leaves no queue schema behind" {
            LedgerTestDb.asSuperuser {
                it.execute("CREATE ROLE ledger_owner LOGIN PASSWORD 'owner' NOSUPERUSER NOCREATEDB NOCREATEROLE")
                it.execute("CREATE DATABASE ledger_owned OWNER ledger_owner")
            }
            val owned = LedgerTestDb.dataSource("ledger_owned", "ledger_owner", "owner")

            migrateLedger(owned) shouldBe 13

            owned.connection.use { c ->
                c.createStatement().use { s ->
                    s.executeQuery("SELECT rolsuper FROM pg_roles WHERE rolname = current_user").use { rs ->
                        rs.next()
                        rs.getBoolean(1) shouldBe false
                    }
                    s.executeQuery("SELECT count(*) FROM pg_namespace WHERE nspname = 'pgmq'").use { rs ->
                        rs.next()
                        rs.getLong(1) shouldBe 0L
                    }
                    s.executeQuery("SELECT count(*) FROM pg_default_acl").use { rs ->
                        rs.next()
                        rs.getLong(1) shouldBe 0L
                    }
                }
            }
        }
    })
