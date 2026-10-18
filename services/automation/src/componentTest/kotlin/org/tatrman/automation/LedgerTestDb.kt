// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation

import com.typesafe.config.ConfigFactory
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import org.tatrman.automation.queue.LedgerQueue
import shared.libs.db.common.DatabaseConnection
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * One Postgres for the component specs, migrated once by the service's own [migrateLedger] (so the specs test
 * the bootstrap's Flyway configuration, not a copy). `postgres:18` = the major the clusters' CNPG images run;
 * pgmq needs nothing of the image (D-130). Each spec calls [reset] for a clean ledger.
 */
object LedgerTestDb {
    private val container: PostgreSQLContainer by lazy {
        PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("automation")
            .withUsername("automation")
            .withPassword("automation")
            .also { pg ->
                pg.start()
                Runtime.getRuntime().addShutdownHook(Thread { pg.stop() })
            }
    }

    val db: DatabaseConnection by lazy {
        DatabaseConnection(
            ConfigFactory.parseString(
                """
                type = "POSTGRES"
                host = "${container.host}"
                port = "${container.firstMappedPort}"
                database = "automation"
                user = "automation"
                password = "automation"
                """.trimIndent(),
            ),
        ).also { it.init() }
    }

    /** How many migrations the first migrate applied — the schema spec's "clean database" evidence. */
    val firstMigration: Int by lazy { migrateLedger(db) }

    fun ready(): DatabaseConnection {
        firstMigration
        return db
    }

    /** Empties the ledger and the queues. The retention opt-in lets the TRUNCATE past run_protocol's guard. */
    fun reset() {
        ready()
        transaction {
            exec("SET LOCAL automation.retention = 'on'")
            exec(
                "TRUNCATE automation.bundles, automation.runs, automation.run_outputs, automation.tasks, " +
                    "automation.windows, automation.window_events, automation.deliveries, automation.callbacks, " +
                    "automation.approvals, automation.batches, automation.batch_rows, automation.run_protocol CASCADE",
            )
            LedgerQueue.entries.forEach { exec("SELECT pgmq.purge_queue('${it.queueName}')") }
        }
    }
}

/** A clock the specs move by hand — the claim's abandonment is a function of `now`. */
class MutableClock(
    var now: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now
}
