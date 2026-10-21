// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
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
import org.tatrman.query.flight.FlightTestKit.RunningEdge
import org.tatrman.query.flight.FlightTestKit.ScriptedDoor
import org.tatrman.query.flight.FlightTestKit.readAll
import org.tatrman.translate.v1.Language

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
            }
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
                closing["notices"]!!
                    .jsonArray
                    .single()
                    .jsonObject["code"]!!
                    .jsonPrimitive.content shouldBe
                    "top_n_applied"
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
