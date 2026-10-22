// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

import com.google.protobuf.ByteString
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.apache.arrow.flight.CallStatus
import org.apache.arrow.flight.FlightRuntimeException
import org.apache.arrow.flight.FlightStatusCode
import org.apache.arrow.flight.sql.impl.FlightSql
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.UInt4Vector
import org.apache.arrow.vector.complex.DenseUnionVector
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.Warning
import org.tatrman.query.flight.FlightTestKit.RunningEdge
import org.tatrman.query.flight.FlightTestKit.ScriptedDoor
import org.tatrman.query.flight.FlightTestKit.readAll
import org.tatrman.translate.v1.Language
import java.util.concurrent.atomic.AtomicLong

class TtrFlightSqlProducerSpec :
    StringSpec({
        val allocator = RootAllocator()
        val first = listOf(1 to "Praha DC", 2 to "Brno DC", 3 to null)
        val second = listOf(4 to "Ostrava DC", 5 to "Plzeň DC")
        val firstIpc = FlightTestKit.ipc(allocator, first)
        val secondIpc = FlightTestKit.ipc(allocator, second)

        fun twoBatchesAndTail(topN: Boolean = false) =
            listOf(
                FlightTestKit.dataBatch(0, firstIpc, first.size),
                FlightTestKit.dataBatch(1, secondIpc, second.size),
                FlightTestKit.tail(2, rowsTotal = 5, topNApplied = topN),
            )

        afterSpec { allocator.close() }

        "GetFlightInfo's schema is the first worker batch's" {
            val door = ScriptedDoor { twoBatchesAndTail() }
            RunningEdge(door).use { edge ->
                val info = edge.sql.execute("SELECT * FROM er.entity.warehouse")
                info.schema shouldBe FlightTestKit.SCHEMA
                info.endpoints shouldHaveSize 1
            }
        }

        "the stream re-frames every worker batch losslessly: same values, same row counts, batch by batch" {
            val door = ScriptedDoor { twoBatchesAndTail() }
            RunningEdge(door).use { edge ->
                val info = edge.sql.execute("SELECT * FROM er.entity.warehouse")
                val read = edge.sql.getStream(info.endpoints[0].ticket).use { it.readAll() }
                read.batches shouldContainExactly listOf(first, second)
                read.rows shouldHaveSize 5
            }
        }

        "the statement reaches the door as SQL, with the caller's identity and a correlation id" {
            val door = ScriptedDoor { twoBatchesAndTail() }
            RunningEdge(door).use { edge ->
                val info = edge.sql.execute("SELECT * FROM er.entity.warehouse")
                edge.sql.getStream(info.endpoints[0].ticket).use { it.readAll() }
                val request = door.requests.single()
                request.source shouldBe "SELECT * FROM er.entity.warehouse"
                request.sourceLanguage shouldBe Language.SQL
                request.context.userId shouldBe "petr"
                request.context.authRolesList shouldContainExactly
                    listOf("kantheon-area-hartland", "kantheon-scope-dc-5")
                request.context.correlationId.isNotEmpty() shouldBe true
                request.sourceSchema shouldBe SchemaCode.ER
            }
        }

        "S-8 — the layer the statement names is stated, so a name both layers hold is not ambiguous at the door" {
            val door = ScriptedDoor { twoBatchesAndTail() }
            RunningEdge(door).use { edge ->
                for (sql in listOf("SELECT w_city FROM db.dbo.warehouse", """SELECT * FROM "db"."dbo"."warehouse"""")) {
                    val info = edge.sql.execute(sql)
                    edge.sql.getStream(info.endpoints[0].ticket).use { it.readAll() }
                }
                door.requests.map { it.sourceSchema } shouldContainExactly listOf(SchemaCode.DB, SchemaCode.DB)
            }
            val probed = ScriptedDoor { twoBatchesAndTail() }
            RunnerSchemaProbe(probed, allocator).schemaOf("SELECT * FROM obj.query.q", FlightTestKit.SESSION)
            probed.requests.single().sourceSchema shouldBe SchemaCode.ER
        }

        "C-10 — a statement asks for as many rows as the caller's ceiling allows; the schema probe for one" {
            val door = ScriptedDoor { twoBatchesAndTail() }
            RunningEdge(door).use { edge ->
                val info = edge.sql.execute("SELECT * FROM db.dbo.catalog_sales LIMIT 10000")
                edge.sql.getStream(info.endpoints[0].ticket).use { it.readAll() }
                door.requests
                    .single()
                    .rowWindow.limit shouldBe Int.MAX_VALUE.toLong()
                door.requests
                    .single()
                    .rowWindow.limit shouldBe EDGE_ROW_ASK
            }
            val probed = ScriptedDoor { twoBatchesAndTail() }
            RunnerSchemaProbe(probed, allocator).schemaOf("SELECT * FROM obj.query.q", FlightTestKit.SESSION)
            probed.requests
                .single()
                .rowWindow.limit shouldBe 1L
        }

        "the last batch — and only it — carries the closing metadata: connection, rows, cap notice" {
            val door = ScriptedDoor { twoBatchesAndTail(topN = true) }
            RunningEdge(door).use { edge ->
                val info = edge.sql.execute("SELECT * FROM db.dbo.catalog_sales")
                val read = edge.sql.getStream(info.endpoints[0].ticket).use { it.readAll() }
                read.metadata.first() shouldBe null
                val closing = Json.parseToJsonElement(read.closing.shouldNotBeNull()).jsonObject
                closing["connection_id"]!!.jsonPrimitive.content shouldBe "pg-hartland-cz"
                closing["rows_total"]!!.jsonPrimitive.long shouldBe 5
                closing["top_n_applied"]!!.jsonPrimitive.boolean shouldBe true
                closing["correlation_id"]!!.jsonPrimitive.content shouldBe
                    door.requests
                        .single()
                        .context.correlationId
                val notice = closing["notices"]!!.jsonArray.single().jsonObject
                notice["code"]!!.jsonPrimitive.content shouldBe "top_n_applied"
                // C-10 — the typed notice's numbers ride along (0: the edge's ask is no number the caller chose).
                notice["requested"]!!.jsonPrimitive.long shouldBe 0
                notice["applied"]!!.jsonPrimitive.long shouldBe 200
                edge.outcomes.single().topNApplied shouldBe true
            }
        }

        "a last batch that carries rows itself carries the metadata, and a zero-row answer still closes with it" {
            val door = ScriptedDoor { listOf(FlightTestKit.dataBatch(0, firstIpc, first.size, last = true)) }
            RunningEdge(door).use { edge ->
                val info = edge.sql.execute("SELECT * FROM er.entity.warehouse")
                val read = edge.sql.getStream(info.endpoints[0].ticket).use { it.readAll() }
                read.batches shouldHaveSize 1
                Json
                    .parseToJsonElement(
                        read.closing.shouldNotBeNull(),
                    ).jsonObject["rows_total"]!!
                    .jsonPrimitive.long shouldBe
                    3
            }
            val empty = FlightTestKit.ipc(allocator, emptyList())
            val none = ScriptedDoor { listOf(FlightTestKit.dataBatch(0, empty, 0), FlightTestKit.tail(1)) }
            RunningEdge(none).use { edge ->
                val info = edge.sql.execute("SELECT * FROM er.entity.warehouse WHERE 1 = 0")
                info.schema shouldBe FlightTestKit.SCHEMA
                val read = edge.sql.getStream(info.endpoints[0].ticket).use { it.readAll() }
                read.rows shouldHaveSize 0
                Json
                    .parseToJsonElement(
                        read.closing.shouldNotBeNull(),
                    ).jsonObject["rows_total"]!!
                    .jsonPrimitive.long shouldBe
                    0
            }
        }

        "C-5 — an empty answer names its columns and closes with one message, from either worker shape" {
            // (a) PostgreSQL / MSSQL: the tail itself carries a zero-row batch. (b) Polars: a schema, no batch.
            val shapes =
                listOf(
                    FlightTestKit.ipc(allocator, emptyList()),
                    FlightTestKit.schemaOnly(allocator),
                )
            shapes.forEach { payload ->
                val tail =
                    FlightTestKit
                        .tail(0)
                        .toBuilder()
                        .setIsFirst(true)
                        .setArrowIpc(ByteString.copyFrom(payload))
                val door = ScriptedDoor { listOf(tail.build()) }
                RunningEdge(door).use { edge ->
                    val info = edge.sql.execute("SELECT * FROM er.entity.warehouse WHERE 1 = 0")
                    info.schema shouldBe FlightTestKit.SCHEMA
                    val read = edge.sql.getStream(info.endpoints[0].ticket).use { it.readAll() }
                    read.rows shouldHaveSize 0
                    read.metadata shouldHaveSize 1
                    val closing = Json.parseToJsonElement(read.closing.shouldNotBeNull()).jsonObject
                    closing["rows_total"]!!.jsonPrimitive.long shouldBe 0
                    closing["connection_id"]!!.jsonPrimitive.content shouldBe "pg-hartland-cz"
                }
            }
        }

        "C-5 — warnings from every batch reach the closing metadata and the receipt once; bookkeeping does not" {
            fun warning(code: String) =
                ResponseMessage
                    .newBuilder()
                    .setSeverity(Severity.WARNING)
                    .setCode(code)
                    .setHumanMessage("Row dropped: a value exceeds max-blob-bytes-per-cell.")
                    .build()
            val context =
                PipelineContext
                    .newBuilder()
                    .addWarnings(Warning.newBuilder().setCode("cache_miss").setMessage("abc"))
                    .addWarnings(Warning.newBuilder().setCode("unsupported_type_as_binary").setMessage("Column 'g'."))
            val door =
                ScriptedDoor {
                    listOf(
                        FlightTestKit
                            .dataBatch(0, firstIpc, first.size)
                            .toBuilder()
                            .setContext(context)
                            .addMessages(warning("blob_too_large"))
                            .build(),
                        // The same warning again, on a later batch: said once.
                        FlightTestKit
                            .dataBatch(1, secondIpc, second.size)
                            .toBuilder()
                            .addMessages(warning("blob_too_large"))
                            .build(),
                        FlightTestKit.tail(2, rowsTotal = 5),
                    )
                }
            RunningEdge(door).use { edge ->
                val info = edge.sql.execute("SELECT * FROM db.dbo.inventory")
                val read = edge.sql.getStream(info.endpoints[0].ticket).use { it.readAll() }
                val codes =
                    Json
                        .parseToJsonElement(read.closing.shouldNotBeNull())
                        .jsonObject["notices"]!!
                        .jsonArray
                        .map { it.jsonObject["code"]!!.jsonPrimitive.content }
                codes shouldContainExactly listOf("blob_too_large", "unsupported_type_as_binary")
                edge.outcomes
                    .single()
                    .notices
                    .map { it.code } shouldContainExactly codes
            }
        }

        "a client that stops reading is ended TIMED_OUT (client_stalled), and the outcome is recorded once" {
            val wide = FlightTestKit.ipc(allocator, (0 until 1_000).map { it to "x".repeat(1_000) })
            val door =
                ScriptedDoor {
                    (0 until 30).map { FlightTestKit.dataBatch(it, wide, 1_000) } + FlightTestKit.tail(30)
                }
            val config = FlightTestKit.testConfig().copy(readyWaitMillis = 300)
            RunningEdge(door, config = config).use { edge ->
                val info = edge.sql.execute("SELECT * FROM db.dbo.inventory")
                val e =
                    shouldThrow<FlightRuntimeException> {
                        edge.sql.getStream(info.endpoints[0].ticket).use { stream ->
                            stream.next()
                            Thread.sleep(3_000)
                            while (stream.next()) Unit
                        }
                    }
                e.status().code() shouldBe FlightStatusCode.TIMED_OUT
                e.status().description() shouldStartWith "client_stalled: "
                val deadline = System.currentTimeMillis() + 10_000
                while (edge.outcomes.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
                Thread.sleep(200)
                edge.outcomes.single().errorCode shouldBe TtrFlightSqlProducer.CLIENT_STALLED
                edge.outcomes.single().status shouldBe StatementOutcome.Status.CANCELLED
            }
        }

        "a refusal before any row is a Flight error at GetFlightInfo: code + one sentence, code in the metadata" {
            val door =
                ScriptedDoor {
                    listOf(
                        FlightTestKit.errorBatch(
                            "translator_rejected",
                            "Unknown table 'warehose'\n  at line 1, column 15",
                        ),
                    )
                }
            RunningEdge(door).use { edge ->
                val e = shouldThrow<FlightRuntimeException> { edge.sql.execute("SELECT * FROM er.entity.warehose") }
                e.status().code() shouldBe FlightStatusCode.INVALID_ARGUMENT
                e.status().description() shouldBe "translator_rejected: Unknown table 'warehose'"
                e.status().metadata().get(TtrFlightSqlProducer.CODE_METADATA_KEY) shouldBe "translator_rejected"
                edge.outcomes.single().status shouldBe StatementOutcome.Status.REFUSED
                edge.outcomes.single().errorCode shouldBe "translator_rejected"
            }
        }

        "C-11 — a refusal that points into the statement carries the line and column in the metadata" {
            val door =
                ScriptedDoor {
                    listOf(
                        FlightTestKit.errorBatch(
                            "translator_rejected",
                            "Syntax error near \"= {\".",
                            line = 2,
                            column = 7,
                        ),
                    )
                }
            RunningEdge(door).use { edge ->
                val e = shouldThrow<FlightRuntimeException> { edge.sql.execute("SELECT 1\nWHERE = {x}") }
                e.status().description() shouldBe "translator_rejected: Syntax error near \"= {\"."
                e.status().metadata().get(TtrFlightSqlProducer.LINE_METADATA_KEY) shouldBe "2"
                e.status().metadata().get(TtrFlightSqlProducer.COLUMN_METADATA_KEY) shouldBe "7"
            }
            val positionless = ScriptedDoor { listOf(FlightTestKit.errorBatch("translator_rejected", "nope")) }
            RunningEdge(positionless).use { edge ->
                val e = shouldThrow<FlightRuntimeException> { edge.sql.execute("SELECT 1") }
                e.status().metadata().containsKey(TtrFlightSqlProducer.LINE_METADATA_KEY) shouldBe false
            }
        }

        "an ERROR batch mid-stream fails the stream with its code and message" {
            val door =
                ScriptedDoor {
                    listOf(
                        FlightTestKit.dataBatch(0, firstIpc, first.size),
                        FlightTestKit.errorBatch("worker_failed", "connection reset by peer", index = 1),
                    )
                }
            RunningEdge(door).use { edge ->
                val info = edge.sql.execute("SELECT * FROM er.entity.warehouse")
                val e =
                    shouldThrow<FlightRuntimeException> {
                        edge.sql.getStream(info.endpoints[0].ticket).use { it.readAll() }
                    }
                e.status().description() shouldStartWith "worker_failed: connection reset by peer"
                edge.outcomes.single().status shouldBe StatementOutcome.Status.FAILED
                edge.outcomes.single().errorCode shouldBe "worker_failed"
            }
        }

        "access refusals are UNAUTHORIZED, unavailable dependencies UNAVAILABLE" {
            TtrFlightSqlProducer.statusFor("column_denied") shouldBe CallStatus.UNAUTHORIZED
            TtrFlightSqlProducer.statusFor("validator_unavailable") shouldBe CallStatus.UNAVAILABLE
            TtrFlightSqlProducer.statusFor("query_failed") shouldBe CallStatus.INTERNAL
            TtrFlightSqlProducer.statusFor("validator_mixed_layer_tree") shouldBe CallStatus.INVALID_ARGUMENT
        }

        "CreatePreparedStatement executes eagerly: the door ran once at prepare, the dataset schema is real" {
            val door = ScriptedDoor { twoBatchesAndTail() }
            RunningEdge(door).use { edge ->
                edge.sql.prepare("SELECT * FROM er.entity.warehouse").use { prepared ->
                    door.requests shouldHaveSize 1
                    prepared.resultSetSchema shouldBe FlightTestKit.SCHEMA
                    // The first execute streams the run `prepare` started — the door is not asked twice.
                    val info = prepared.execute()
                    edge.sql
                        .getStream(info.endpoints[0].ticket)
                        .use { it.readAll() }
                        .rows shouldHaveSize 5
                    door.requests shouldHaveSize 1
                    // A second execute is a fresh run.
                    val again = prepared.execute()
                    edge.sql
                        .getStream(again.endpoints[0].ticket)
                        .use { it.readAll() }
                        .rows shouldHaveSize 5
                    door.requests shouldHaveSize 2
                }
            }
        }

        "a ticket is read once" {
            val door = ScriptedDoor { twoBatchesAndTail() }
            RunningEdge(door).use { edge ->
                val info = edge.sql.execute("SELECT * FROM er.entity.warehouse")
                edge.sql.getStream(info.endpoints[0].ticket).use { it.readAll() }
                val e =
                    shouldThrow<FlightRuntimeException> {
                        edge.sql.getStream(info.endpoints[0].ticket).use { it.readAll() }
                    }
                e.status().code() shouldBe FlightStatusCode.NOT_FOUND
            }
        }

        "a statement nobody fetches expires: its run is cancelled and reported" {
            val door = ScriptedDoor { twoBatchesAndTail() }
            val config = FlightTestKit.testConfig().copy(pendingTtlSeconds = 0)
            RunningEdge(door, config = config).use { edge ->
                edge.sql.execute("SELECT * FROM er.entity.warehouse")
                val deadline = System.currentTimeMillis() + 20_000
                while (edge.outcomes.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(100)
                edge.outcomes.single().status shouldBe StatementOutcome.Status.EXPIRED
            }
        }

        "the unread-statement TTL counts from FlightInfo, not from the statement's start" {
            // A first batch that takes 5 s: the clock moves while the door works. TTL 1 s.
            val clock = AtomicLong(0)
            val slow =
                StatementRunner {
                    flow {
                        clock.set(5_000_000_000)
                        emitAll(twoBatchesAndTail().asFlow())
                    }
                }
            val config = FlightTestKit.testConfig().copy(pendingTtlSeconds = 1)
            RunningEdge(slow, config = config, nanoClock = clock::get).use { edge ->
                val info = edge.sql.execute("SELECT * FROM db.dbo.inventory")
                clock.set(5_500_000_000)
                edge.producer.sweep()
                // 0.5 s after its FlightInfo, 5.5 s after it started: still there.
                edge.sql
                    .getStream(info.endpoints[0].ticket)
                    .use { it.readAll() }
                    .rows shouldHaveSize 5
            }
            clock.set(0)
            RunningEdge(slow, config = config, nanoClock = clock::get).use { edge ->
                edge.sql.execute("SELECT * FROM db.dbo.inventory")
                clock.set(5_500_000_000)
                edge.producer.sweep()
                edge.outcomes shouldHaveSize 0
                // …and an unread one still expires 1 s after its FlightInfo.
                clock.set(6_100_000_000)
                edge.producer.sweep()
                edge.outcomes.single().status shouldBe StatementOutcome.Status.EXPIRED
            }
        }

        "no verified caller, no call" {
            val door = ScriptedDoor { twoBatchesAndTail() }
            RunningEdge(door, session = null).use { edge ->
                val e = shouldThrow<FlightRuntimeException> { edge.sql.execute("SELECT 1") }
                e.status().code() shouldBe FlightStatusCode.UNAUTHENTICATED
                door.requests shouldHaveSize 0
            }
        }

        "GetSqlInfo: read-only, no transactions, \" quoting, the translator's reserved words" {
            RunningEdge(ScriptedDoor { emptyList() }).use { edge ->
                val info =
                    edge.sql.getSqlInfo(
                        FlightSql.SqlInfo.FLIGHT_SQL_SERVER_READ_ONLY,
                        FlightSql.SqlInfo.FLIGHT_SQL_SERVER_TRANSACTION,
                        FlightSql.SqlInfo.SQL_TRANSACTIONS_SUPPORTED,
                        FlightSql.SqlInfo.SQL_IDENTIFIER_QUOTE_CHAR,
                        FlightSql.SqlInfo.SQL_KEYWORDS,
                    )
                val values = mutableMapOf<Int, Any?>()
                edge.sql.getStream(info.endpoints[0].ticket).use { stream ->
                    while (stream.next()) {
                        val names = stream.root.getVector("info_name") as UInt4Vector
                        val union = stream.root.getVector("value") as DenseUnionVector
                        for (i in 0 until stream.root.rowCount) values[names.get(i)] = union.getObject(i)
                    }
                }
                values[FlightSql.SqlInfo.FLIGHT_SQL_SERVER_READ_ONLY_VALUE] shouldBe true
                values[FlightSql.SqlInfo.SQL_TRANSACTIONS_SUPPORTED_VALUE] shouldBe false
                values[FlightSql.SqlInfo.FLIGHT_SQL_SERVER_TRANSACTION_VALUE] shouldBe
                    FlightSql.SqlSupportedTransaction.SQL_SUPPORTED_TRANSACTION_NONE_VALUE
                values[FlightSql.SqlInfo.SQL_IDENTIFIER_QUOTE_CHAR_VALUE].toString() shouldBe "\""
                val keywords = (values[FlightSql.SqlInfo.SQL_KEYWORDS_VALUE] as List<*>).map { it.toString() }
                keywords shouldContain "count"
                keywords shouldContain "value"
            }
        }

        "oneSentence keeps the first line, collapses whitespace, caps the length" {
            TtrFlightSqlProducer.oneSentence("  From line 1,   column 15\nrepeated\nrepeated") shouldBe
                "From line 1, column 15"
            TtrFlightSqlProducer.oneSentence("x".repeat(1000)).length shouldBe 400
            TtrFlightSqlProducer.oneSentence("") shouldContain ""
        }
    })
