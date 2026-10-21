// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

import com.google.protobuf.ByteString
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.apache.arrow.flight.FlightRuntimeException
import org.apache.arrow.memory.RootAllocator
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.query.flight.FlightTestKit.RunningEdge
import org.tatrman.query.flight.FlightTestKit.ScriptedDoor
import org.tatrman.query.flight.FlightTestKit.dataBatch
import org.tatrman.query.flight.FlightTestKit.errorBatch
import org.tatrman.query.flight.FlightTestKit.ipc
import org.tatrman.query.flight.FlightTestKit.readAll
import org.tatrman.validate.v1.SecurityRuleApplied
import org.tatrman.worker.v1.ExecutionReceipt
import org.tatrman.worker.v1.ResultBatch
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/**
 * BI (contracts C-6) — every edge statement leaves one receipt: who, which world, what the door ran and the row rules
 * it applied, how it ended. Written after the stream and best effort — a store that fails is logged, and the
 * statement it would have recorded completes anyway. `GET /receipts` reads them back for `query-platform-admin` only.
 */
class ReceiptSinkSpec :
    StringSpec({
        val allocator = RootAllocator()
        afterSpec { allocator.close() }
        val now = Instant.parse("2026-10-21T10:15:30Z")
        val clock = Clock.fixed(now, ZoneOffset.UTC)

        /** Receipts in memory; the first [failures] inserts fail, as a database that is down does. */
        class MemoryReceipts(
            private var failures: Int = 0,
        ) : ReceiptStore {
            val rows = CopyOnWriteArrayList<Receipt>()

            @Synchronized
            override fun insert(receipt: Receipt) {
                if (failures > 0) {
                    failures--
                    error("database is down")
                }
                rows += receipt
            }

            override fun find(query: ReceiptQuery): List<Receipt> =
                rows
                    .filter { query.correlationId == null || it.correlationId == query.correlationId }
                    .filter { query.userId == null || it.userId == query.userId }
                    .filter { query.since == null || !it.ts.isBefore(query.since) }
                    .take(query.limit)
        }

        val rule =
            SecurityRuleApplied
                .newBuilder()
                .setRuleId("dc_scope")
                .setPredicateSummary("WHERE warehouse IN (your DCs)")
                .setTable("db.dbo.warehouse")
                .build()

        fun governedDoor() =
            ScriptedDoor {
                listOf(
                    dataBatch(0, ipc(allocator, listOf(5 to "Brno DC")), 1),
                    ResultBatch
                        .newBuilder()
                        .setBatchIndex(1)
                        .setIsLast(true)
                        .setArrowIpc(ByteString.EMPTY)
                        .setReceipt(
                            ExecutionReceipt
                                .newBuilder()
                                .setConnectionId("pg-hartland-cz")
                                .setStatement("SELECT \"sk\", \"warehouse_name\" FROM \"dbo\".\"warehouse\" WHERE …")
                                .setRowsTotal(1)
                                .addSecurityApplied(rule),
                        ).addMessages(
                            ResponseMessage
                                .newBuilder()
                                .setSeverity(Severity.WARNING)
                                .setCode("top_n_applied")
                                .setHumanMessage("Answer limited to 1 row."),
                        ).build(),
                )
            }

        val session =
            FlightTestKit.SESSION.copy(world = "hartland_cz", client = EdgeSession.CLIENT_JDBC)

        "one row per statement, every C-6 column filled from the session, the stream and the door's ES receipt" {
            val store = MemoryReceipts()
            val sink = ReceiptSink(store, clock = clock)
            RunningEdge(governedDoor(), session = session, listener = sink).use { edge ->
                val info = edge.sql.execute("SELECT sk, warehouse_name FROM er.entity.warehouse")
                edge.sql.getStream(info.endpoints.single().ticket).use { it.readAll() }
            }
            sink.close()
            val receipt = store.rows.single()
            receipt.correlationId.isNotBlank() shouldBe true
            receipt.ts shouldBe now
            receipt.userId shouldBe "petr"
            receipt.roles shouldContainExactly listOf("kantheon-area-hartland", "kantheon-scope-dc-5")
            receipt.world shouldBe "hartland_cz"
            receipt.connectionId shouldBe "pg-hartland-cz"
            receipt.client shouldBe "jdbc"
            receipt.statement shouldBe "SELECT sk, warehouse_name FROM er.entity.warehouse"
            receipt.executedSql shouldBe "SELECT \"sk\", \"warehouse_name\" FROM \"dbo\".\"warehouse\" WHERE …"
            receipt.securityApplied shouldBe
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("rule_id", "dc_scope")
                            put("predicate_summary", "WHERE warehouse IN (your DCs)")
                            put("table", "db.dbo.warehouse")
                        },
                    )
                }
            receipt.rowsTotal shouldBe 1
            receipt.topNApplied shouldBe true
            receipt.status shouldBe "ok"
            receipt.errorCode shouldBe ""
        }

        "a refused statement is a receipt too: status refused, its code, nothing executed" {
            val store = MemoryReceipts()
            val sink = ReceiptSink(store, clock = clock)
            val door = ScriptedDoor { listOf(errorBatch("translator_rejected", "Unknown table 'warehous'.")) }
            RunningEdge(door, session = session, listener = sink).use { edge ->
                shouldThrow<FlightRuntimeException> { edge.sql.execute("SELECT * FROM er.entity.warehous") }
            }
            sink.close()
            val receipt = store.rows.single()
            receipt.status shouldBe "refused"
            receipt.errorCode shouldBe "translator_rejected"
            receipt.executedSql shouldBe ""
            receipt.rowsTotal shouldBe 0
        }

        "a store that fails is logged, never felt: the stream completes, and the sink keeps going" {
            val store = MemoryReceipts(failures = 1)
            val sink = ReceiptSink(store, clock = clock)
            RunningEdge(governedDoor(), session = session, listener = sink).use { edge ->
                val info = edge.sql.execute("SELECT 1")
                edge.sql
                    .getStream(info.endpoints.single().ticket)
                    .use { it.readAll() }
                    .rows shouldBe
                    listOf(5 to "Brno DC")
                edge.outcomes.single().status shouldBe StatementOutcome.Status.OK
                // The database comes back: the next statement's receipt is written.
                val again = edge.sql.execute("SELECT 2")
                edge.sql.getStream(again.endpoints.single().ticket).use { it.readAll() }
            }
            sink.close()
            store.rows.map { it.statement } shouldContainExactly listOf("SELECT 2")
        }

        "GET /receipts: the row for query-platform-admin; 403 anyone else, 401 without a bearer, 400 without a key" {
            val store = MemoryReceipts()
            val sink = ReceiptSink(store, clock = clock)
            RunningEdge(governedDoor(), session = session, listener = sink).use { edge ->
                val info = edge.sql.execute("SELECT 1")
                edge.sql.getStream(info.endpoints.single().ticket).use { it.readAll() }
            }
            sink.close()
            val id = store.rows.single().correlationId
            val admin = AuthTestKit.token(user = "ops", roles = listOf("query-platform-admin"))
            val petr = AuthTestKit.token()
            testApplication {
                install(ContentNegotiation) { json() }
                routing { receiptRoutes(store, AuthTestKit.verifier) }
                val ok =
                    client.get(
                        "/receipts?correlation_id=$id",
                    ) { header(HttpHeaders.Authorization, "Bearer $admin") }
                ok.status shouldBe HttpStatusCode.OK
                val row =
                    Json
                        .parseToJsonElement(
                            ok.bodyAsText(),
                        ).jsonObject["receipts"]!!
                        .jsonArray
                        .single()
                        .jsonObject
                row["correlation_id"]!!.jsonPrimitive.content shouldBe id
                row["world"]!!.jsonPrimitive.content shouldBe "hartland_cz"
                row["security_applied"]!!
                    .jsonArray
                    .single()
                    .jsonObject["rule_id"]!!
                    .jsonPrimitive.content shouldBe
                    "dc_scope"
                row["ts"]!!.jsonPrimitive.content shouldBe "2026-10-21T10:15:30Z"
                client
                    .get("/receipts?user_id=petr&since=2026-10-21T00:00:00Z") {
                        header(HttpHeaders.Authorization, "Bearer $admin")
                    }.let {
                        Json
                            .parseToJsonElement(it.bodyAsText())
                            .jsonObject["receipts"]!!
                            .jsonArray.size
                    } shouldBe
                    1
                client
                    .get("/receipts?correlation_id=$id") { header(HttpHeaders.Authorization, "Bearer $petr") }
                    .status shouldBe HttpStatusCode.Forbidden
                client.get("/receipts?correlation_id=$id").status shouldBe HttpStatusCode.Unauthorized
                val forged = AuthTestKit.token(roles = listOf("query-platform-admin"), pair = AuthTestKit.otherRealm)
                client
                    .get("/receipts?correlation_id=$id") { header(HttpHeaders.Authorization, "Bearer $forged") }
                    .status shouldBe HttpStatusCode.Unauthorized
                client
                    .get("/receipts") { header(HttpHeaders.Authorization, "Bearer $admin") }
                    .status shouldBe HttpStatusCode.BadRequest
                client
                    .get(
                        "/receipts?user_id=petr&since=yesterday",
                    ) { header(HttpHeaders.Authorization, "Bearer $admin") }
                    .status shouldBe HttpStatusCode.BadRequest
            }
        }
    })
