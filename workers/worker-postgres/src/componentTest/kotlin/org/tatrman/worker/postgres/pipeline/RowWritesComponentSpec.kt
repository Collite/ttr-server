// SPDX-License-Identifier: Apache-2.0
package org.tatrman.worker.postgres.pipeline

import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.toList
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.ipc.ArrowStreamReader
import org.tatrman.plan.v1.*
import org.tatrman.translate.v1.*
import org.tatrman.translator.codec.sql.RowWriteUnparser
import org.tatrman.testkit.containers.Containers
import org.tatrman.worker.postgres.client.TranslatorClient
import org.tatrman.worker.postgres.connection.ConnectionConfig
import org.tatrman.worker.postgres.connection.ConnectionPoolManager
import org.tatrman.worker.v1.ExecuteRequest
import org.tatrman.worker.v1.ResultBatch
import java.io.ByteArrayInputStream
import java.sql.DriverManager

@Tags("component")
class RowWritesComponentSpec :
    StringSpec({
        val pg = Containers.postgres()
        beforeSpec { pg.start() }
        afterSpec { pg.stop() }

        fun sql(text: String) =
            DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { c ->
                c.createStatement().use { it.execute(text) }
            }
        beforeTest {
            sql(
                "CREATE SCHEMA IF NOT EXISTS cz; DROP TABLE IF EXISTS cz.rw_orders; DROP TABLE IF EXISTS cz.rw_parents; CREATE TABLE cz.rw_orders (id bigint PRIMARY KEY, state text NOT NULL)",
            )
        }
        val target =
            QualifiedName
                .newBuilder()
                .setSchemaCode(
                    SchemaCode.DB,
                ).setNamespace("public")
                .setName("rw_orders")
                .build()

        fun number(n: Long) = Expression.newBuilder().setLiteral(Literal.newBuilder().setIntValue(n)).build()

        fun text(s: String) = Expression.newBuilder().setLiteral(Literal.newBuilder().setStringValue(s)).build()

        fun col(s: String) = Expression.newBuilder().setColumnRef(ColumnRef.newBuilder().setName(s)).build()

        fun fn(
            op: String,
            vararg args: Expression,
        ) = Expression
            .newBuilder()
            .setFunction(
                FunctionCall.newBuilder().setOperation(op).addAllOperands(args.toList()),
            ).build()

        fun value(
            name: String,
            expr: Expression,
        ) = ColumnValue
            .newBuilder()
            .setName(name)
            .setValue(expr)
            .build()

        fun insert(
            id: Long,
            state: String,
            absent: Boolean = false,
        ) = RowWrite
            .newBuilder()
            .setTarget(target)
            .setOp(
                RowWriteOp.INSERT,
            ).setIfAbsent(absent)
            .addValues(value("id", number(id)))
            .addValues(value("state", text(state)))
            .build()

        fun update(
            id: Long,
            expected: String,
            next: String,
        ) = RowWrite
            .newBuilder()
            .setTarget(target)
            .setOp(RowWriteOp.UPDATE)
            .addValues(value("state", text(next)))
            .addReadBefore("state")
            .setWhere(fn("and", fn("eq", col("id"), number(id)), fn("eq", col("state"), text(expected))))
            .build()

        fun count(): Long =
            DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use { c ->
                c.createStatement().use {
                    it.executeQuery("SELECT count(*) FROM cz.rw_orders").use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            }

        fun decode(batch: ResultBatch): List<List<Any?>> =
            RootAllocator().use { allocator ->
                ArrowStreamReader(ByteArrayInputStream(batch.arrowIpc.toByteArray()), allocator).use { reader ->
                    reader.loadNextBatch() shouldBe true
                    val root = reader.vectorSchemaRoot
                    (0 until root.rowCount).map { i ->
                        root.fieldVectors.map {
                            it.getObject(i)?.let { v ->
                                if (v is org.apache.arrow.vector.util.Text) v.toString() else v
                            }
                        }
                    }
                }
            }

        suspend fun execute(
            rows: List<RowWrite>,
            readOnly: Boolean = false,
            select: Boolean = false,
        ): Pair<List<ResultBatch>, Int> {
            var calls = 0
            val translator =
                object : TranslatorClient {
                    override suspend fun probe() = Unit

                    override suspend fun unparse(request: UnparseRequest): UnparseResponse {
                        calls++
                        if (select) {
                            return UnparseResponse
                                .newBuilder()
                                .setOutput(
                                    "SELECT id, state FROM cz.rw_orders ORDER BY id",
                                ).setContext(request.context)
                                .build()
                        }
                        request.plan.rowWrites.rowsCount shouldBe 1
                        val statement =
                            RowWriteUnparser.unparse(
                                request.plan.rowWrites.rowsList
                                    .single(),
                                SqlDialect.POSTGRESQL,
                                request.context.parametersList,
                                listOf("id"),
                            )
                        return UnparseResponse
                            .newBuilder()
                            .setOutput(
                                statement.sql,
                            ).setContext(
                                request.context
                                    .toBuilder()
                                    .clearParameters()
                                    .addAllParameters(statement.parameters),
                            ).build()
                    }
                }
            val pool =
                ConnectionPoolManager(
                    mapOf(
                        "writer" to
                            ConnectionConfig(
                                id = "writer",
                                jdbcUrl = pg.jdbcUrl,
                                username = pg.username,
                                password = pg.password,
                                database = pg.databaseName,
                                defaultSchema = "cz",
                                readOnly = readOnly,
                            ),
                    ),
                )
            return try {
                RootAllocator().use { allocator ->
                    val plan =
                        if (select) {
                            PlanNode.newBuilder().setTableScan(TableScanNode.newBuilder().setTable(target)).build()
                        } else {
                            PlanNode.newBuilder().setRowWrites(RowWrites.newBuilder().addAllRows(rows)).build()
                        }
                    val request =
                        ExecuteRequest
                            .newBuilder()
                            .setPlan(plan)
                            .setConnectionId("writer")
                            .setContext(PipelineContext.newBuilder().setUserId("writer"))
                            .build()
                    val pipeline =
                        ExecutePipeline(
                            pool,
                            translator,
                            ExecutePipeline.ExecutionLimits(100, 1000, 30, 60, 1048576),
                            allocator,
                        )
                    pipeline.execute(request).toList() to calls
                }
            } finally {
                pool.close()
            }
        }

        "INSERT, CAS hit and CAS miss execute in order in one transaction with prior" {
            // The updates must see the uncommitted insert and previous update on the same connection.
            val (batches, calls) =
                execute(
                    listOf(insert(1, "open"), update(1, "open", "held"), update(1, "open", "closed")),
                )
            calls shouldBe 3
            val batch = batches.single()
            batch.messagesCount shouldBe 0
            batch.isFirst shouldBe true
            batch.isLast shouldBe true
            val rows = decode(batch)
            rows.map { it.take(2) } shouldBe listOf(listOf(0, 1L), listOf(1, 1L), listOf(2, 0L))
            rows[0][2] shouldBe null
            (rows[1][2] as String) shouldContain "open"
            (rows[2][2] as String) shouldContain "held"
            count() shouldBe 1L
        }
        "SQL error at seq 1 rolls back the earlier insert" {
            val batch = execute(listOf(insert(1, "open"), insert(1, "duplicate"))).first.single()
            batch.messagesList.single().code shouldBe "worker_execution_failed"
            batch.messagesList.single().humanMessage shouldContain "seq 1"
            batch.arrowIpc.isEmpty shouldBe true
            count() shouldBe 0L
        }
        "deferred constraint failure is a commit error with a receipt and no seq" {
            sql(
                "CREATE TABLE cz.rw_parents (id bigint PRIMARY KEY); ALTER TABLE cz.rw_orders ADD CONSTRAINT rw_parent FOREIGN KEY (id) REFERENCES cz.rw_parents(id) DEFERRABLE INITIALLY DEFERRED",
            )
            val batch = execute(listOf(insert(1, "orphan"))).first.single()
            batch.messagesList.single().code shouldBe "worker_execution_failed"
            batch.messagesList.single().humanMessage shouldContain "row_writes commit:"
            batch.messagesList
                .single()
                .humanMessage
                .contains("seq") shouldBe false
            batch.hasReceipt() shouldBe true
            batch.receipt.connectionId shouldBe "writer"
            batch.receipt.statementKind shouldBe org.tatrman.worker.v1.StatementKind.SQL_EXECUTED
            batch.arrowIpc.isEmpty shouldBe true
            count() shouldBe 0L
        }
        "if_absent unique conflict answers zero without error" {
            sql("INSERT INTO cz.rw_orders VALUES (1, 'open')")
            val batch = execute(listOf(insert(1, "ignored", true))).first.single()
            batch.messagesCount shouldBe 0
            decode(batch) shouldBe listOf(listOf(0, 0L, null))
            count() shouldBe 1L
        }
        "read-only connection refuses before unparse or database statements" {
            val (batches, calls) = execute(listOf(insert(1, "open")), readOnly = true)
            batches
                .single()
                .messagesList
                .single()
                .code shouldBe "connection_read_only"
            calls shouldBe 0
            count() shouldBe 0L
        }
        "non-row-write DML is read-only and the same physical connection can then commit row writes" {
            val pool =
                ConnectionPoolManager(
                    mapOf(
                        "writer" to
                            ConnectionConfig(
                                id = "writer",
                                jdbcUrl = pg.jdbcUrl,
                                username = pg.username,
                                password = pg.password,
                                database = pg.databaseName,
                                defaultSchema = "cz",
                                readOnly = false,
                                maxPoolSize = 1,
                            ),
                    ),
                )
            var readSql = "INSERT INTO cz.rw_orders VALUES (1, 'bypass') RETURNING id"
            val translator =
                object : TranslatorClient {
                    override suspend fun probe() = Unit

                    override suspend fun unparse(request: UnparseRequest): UnparseResponse {
                        if (request.plan.nodeCase != PlanNode.NodeCase.ROW_WRITES) {
                            return UnparseResponse
                                .newBuilder()
                                .setOutput(readSql)
                                .setContext(request.context)
                                .build()
                        }
                        val result =
                            RowWriteUnparser.unparse(
                                request.plan.rowWrites.rowsList
                                    .single(),
                                SqlDialect.POSTGRESQL,
                                request.context.parametersList,
                                listOf("id"),
                            )
                        return UnparseResponse
                            .newBuilder()
                            .setOutput(result.sql)
                            .setContext(
                                request.context
                                    .toBuilder()
                                    .clearParameters()
                                    .addAllParameters(result.parameters),
                            ).build()
                    }
                }
            pool.use {
                fun backendPid() =
                    pool.acquire("writer").use { c ->
                        c.unwrap(org.postgresql.PGConnection::class.java).backendPID
                    }
                val pid = backendPid()
                RootAllocator().use { allocator ->
                    val pipeline =
                        ExecutePipeline(
                            pool,
                            translator,
                            ExecutePipeline.ExecutionLimits(100, 1000, 30, 60, 1048576),
                            allocator,
                        )

                    suspend fun run(plan: PlanNode) =
                        pipeline
                            .execute(
                                ExecuteRequest
                                    .newBuilder()
                                    .setPlan(plan)
                                    .setConnectionId("writer")
                                    .build(),
                            ).toList()
                    val readPlan =
                        PlanNode
                            .newBuilder()
                            .setTableScan(
                                TableScanNode.newBuilder().setTable(target),
                            ).build()
                    val rejected = run(readPlan).single()
                    rejected.messagesList.single().code shouldBe "worker_execution_failed"
                    rejected.messagesList.single().humanMessage shouldContain "read-only transaction"
                    count() shouldBe 0L
                    readSql = "SELECT id, state FROM cz.rw_orders"
                    run(readPlan).last().messagesCount shouldBe 0
                    val written =
                        run(
                            PlanNode
                                .newBuilder()
                                .setRowWrites(
                                    RowWrites.newBuilder().addRows(insert(2, "allowed")),
                                ).build(),
                        ).single()
                    written.messagesCount shouldBe 0
                    decode(written) shouldBe listOf(listOf(0, 1L, null))
                    count() shouldBe 1L
                    backendPid() shouldBe pid
                }
            }
        }
        "SELECT retains normal streaming behavior on a read-write connection" {
            sql("INSERT INTO cz.rw_orders VALUES (1, 'open')")
            val (batches, calls) = execute(emptyList(), select = true)
            calls shouldBe 1
            batches.last().isLast shouldBe true
            batches.sumOf { it.batchRowCount } shouldBe 1L
            decode(batches.first()) shouldBe listOf(listOf(1L, "open"))
        }
    })
