// SPDX-License-Identifier: Apache-2.0
package org.tatrman.worker.postgres.pipeline

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.ipc.ArrowStreamReader
import org.tatrman.testkit.containers.Containers
import org.tatrman.worker.v1.RlsOutcome
import org.tatrman.worker.v1.StatementKind
import java.io.ByteArrayInputStream
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

        "BI (C-10) — row_limit is exact on a real server: 2 rows of 3, in one batch of a hundred" {
            Containers.postgres().use { pg ->
                pg.start()
                val tenant = UUID.randomUUID()
                PostgresPgFixture.connect(pg.jdbcUrl, pg.username, pg.password).use { su ->
                    PostgresPgFixture.provision(su)
                    PostgresPgFixture.insert(su, tenant, 1001L, "1.0000", "a")
                    PostgresPgFixture.insert(su, tenant, 1002L, "2.0000", "b")
                    PostgresPgFixture.insert(su, tenant, 1003L, "3.0000", "c")
                }
                val pool = PostgresComponentSupport.pool(pg.jdbcUrl, pg.databaseName)
                val batches =
                    try {
                        PostgresComponentSupport.execute(
                            pool,
                            tenant,
                            org.tatrman.worker.v1.ExecutionOptions
                                .newBuilder()
                                .setRowLimit(2)
                                .setBatchSizeRows(100)
                                .build(),
                        )
                    } finally {
                        pool.close()
                    }
                batches.sumOf { it.batchRowCount } shouldBe 2L
                PostgresComponentSupport.decode(batches).size shouldBe 2
                batches.last().receipt.rowsTotal shouldBe 2L
            }
        }

        "BI (C-5) — an empty answer on a real server still names its columns: the tail is a zero-row batch" {
            Containers.postgres().use { pg ->
                pg.start()
                PostgresPgFixture.connect(pg.jdbcUrl, pg.username, pg.password).use { su ->
                    PostgresPgFixture.provision(su)
                    PostgresPgFixture.insert(su, UUID.randomUUID(), 1001L, "1.0000", "someone else's")
                }
                val pool = PostgresComponentSupport.pool(pg.jdbcUrl, pg.databaseName)
                // A tenant with no rows: RLS lets nothing through, so the statement answers no rows.
                val batches =
                    try {
                        PostgresComponentSupport.execute(pool, UUID.randomUUID())
                    } finally {
                        pool.close()
                    }
                val tail = batches.single()
                tail.isLast shouldBe true
                tail.batchRowCount shouldBe 0L
                RootAllocator(Long.MAX_VALUE).use { alloc ->
                    ArrowStreamReader(ByteArrayInputStream(tail.arrowIpc.toByteArray()), alloc).use { reader ->
                        reader.vectorSchemaRoot.schema.fields
                            .map { it.name } shouldBe listOf("account_id", "amount", "label")
                        reader.loadNextBatch() shouldBe true
                        reader.vectorSchemaRoot.rowCount shouldBe 0
                    }
                }
                tail.receipt.rowsTotal shouldBe 0L
            }
        }

        "a non-empty answer's tail stays empty on a real server" {
            Containers.postgres().use { pg ->
                pg.start()
                val tenant = UUID.randomUUID()
                PostgresPgFixture.connect(pg.jdbcUrl, pg.username, pg.password).use { su ->
                    PostgresPgFixture.provision(su)
                    PostgresPgFixture.insert(su, tenant, 1001L, "1.0000", "a")
                }
                val pool = PostgresComponentSupport.pool(pg.jdbcUrl, pg.databaseName)
                val batches =
                    try {
                        PostgresComponentSupport.execute(pool, tenant)
                    } finally {
                        pool.close()
                    }
                batches.size shouldBe 2
                batches.last().arrowIpc.isEmpty shouldBe true
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
