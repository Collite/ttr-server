// SPDX-License-Identifier: Apache-2.0
package org.tatrman.worker.postgres.pipeline

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.tatrman.testkit.containers.Containers
import org.tatrman.worker.v1.RlsOutcome
import org.tatrman.worker.v1.StatementKind
import java.util.UUID

/**
 * **ES-P0·S0.2, the real-Postgres half.**
 *
 * The unit tier ([ExecutionReceiptPipelineSpec]) proves every `is_last` path attaches a receipt
 * against mocked JDBC. Two of the receipt's claims cannot be proved that way and are exactly the
 * two the document will print as fact:
 *
 *  - **`duration_ms` is a real measurement** — a mocked `executeQuery` returns in microseconds, so
 *    only a live round-trip shows the clock running from prepare to the last row;
 *  - **`rls = RLS_APPLIED` means Postgres actually scoped the rows** — the tenant bound by
 *    `SET LOCAL app.tenant_id` and the row-level-security policy that reads it are the database's
 *    behaviour, not the worker's. The row count IS the proof: a second tenant's rows exist in the
 *    table and do not come back.
 *
 * Postgres is native multi-arch, so no `CiOnly` gate (unlike MSSQL).
 */
@Tags("component")
class PostgresReceiptComponentSpec :
    StringSpec({

        "the tail marker of a real RLS-scoped run carries the statement that ran, the rows and the clock" {
            Containers.postgres().use { pg ->
                pg.start()

                val tenant = UUID.randomUUID()
                val otherTenant = UUID.randomUUID()
                PostgresPgFixture.connect(pg.jdbcUrl, pg.username, pg.password).use { su ->
                    PostgresPgFixture.provision(su)
                    PostgresPgFixture.insert(su, tenant, 1001L, "123.4500", "alpha")
                    PostgresPgFixture.insert(su, tenant, 1002L, "67.8900", "beta")
                    // Another tenant's row — RLS must keep it out of both the stream and the count.
                    PostgresPgFixture.insert(su, otherTenant, 9999L, "1.0000", "not-yours")
                }

                val pool = PostgresComponentSupport.pool(pg.jdbcUrl, pg.databaseName)
                val batches =
                    try {
                        PostgresComponentSupport.execute(pool, tenant)
                    } finally {
                        pool.close()
                    }

                val tail = batches.last()
                tail.isLast shouldBe true
                tail.hasReceipt() shouldBe true

                val receipt = tail.receipt
                receipt.statementKind shouldBe StatementKind.SQL_EXECUTED
                receipt.dialect shouldBe "POSTGRESQL"
                // The statement the receipt names is the one the engine was handed, verbatim.
                receipt.statement shouldBe PostgresComponentSupport.QUERY
                receipt.statementTruncated shouldBe false
                receipt.statementRef shouldBe ""
                receipt.connectionId shouldBe "pg-midas"
                receipt.engineLabel shouldBe "worker-postgres@pg-midas"
                // Two rows, not three: the receipt counts what RLS let through.
                receipt.rowsTotal shouldBe 2L
                receipt.rowsTotal shouldBe batches.sumOf { it.batchRowCount }
                receipt.durationMs shouldBeGreaterThan 0L
                receipt.rls shouldBe RlsOutcome.RLS_APPLIED
                // The plan half belongs to the query service (⚑ES-1) — the worker leaves it unset.
                receipt.hasDispatchedPlan() shouldBe false
                receipt.securityAppliedCount shouldBe 0
            }
        }

        "a connection without the tenant flag reports RLS_NOT_REQUIRED" {
            Containers.postgres().use { pg ->
                pg.start()

                val tenant = UUID.randomUUID()
                PostgresPgFixture.connect(pg.jdbcUrl, pg.username, pg.password).use { su ->
                    PostgresPgFixture.provision(su)
                    PostgresPgFixture.insert(su, tenant, 1001L, "123.4500", "alpha")
                }

                val pool = PostgresComponentSupport.pool(pg.jdbcUrl, pg.databaseName, requiresTenantId = false)
                val batches =
                    try {
                        PostgresComponentSupport.execute(pool, tenant = null)
                    } finally {
                        pool.close()
                    }

                batches.last().receipt.rls shouldBe RlsOutcome.RLS_NOT_REQUIRED
            }
        }
    })
