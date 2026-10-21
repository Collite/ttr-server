// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.types.pojo.Schema
import org.tatrman.query.flight.FlightTestKit.RunningEdge
import org.tatrman.query.flight.FlightTestKit.ScriptedDoor
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Types
import java.util.Properties

/**
 * C-1 — the stock Arrow Flight SQL JDBC driver (18.3.0) against the edge, making the calls DBeaver makes when it
 * connects: catalogs, schemas, tables, columns, then one query. The door and Veles are stubs (the recorded hartland
 * catalog); the authenticator is in allow-all TEST mode — identity is S1.2's and is tested there.
 */
class FlightJdbcRoundTripSpec :
    StringSpec({
        val allocator = RootAllocator()
        val rows = listOf(1 to "Praha DC", 2 to "Brno DC", 5 to "Plzeň DC")
        val payload = FlightTestKit.ipc(allocator, rows)
        val session = FlightTestKit.SESSION.copy(world = "hartland_cz")

        fun door() =
            ScriptedDoor { request ->
                if (request.rowWindow.limit == 1L) {
                    // The catalog's probe of a logical query.
                    listOf(
                        FlightTestKit.dataBatch(0, FlightTestKit.ipc(allocator, emptyList()), 0),
                        FlightTestKit.tail(1),
                    )
                } else {
                    listOf(FlightTestKit.dataBatch(0, payload, rows.size), FlightTestKit.tail(1, rowsTotal = 3))
                }
            }

        fun connect(edge: RunningEdge): Connection {
            Class.forName("org.apache.arrow.driver.jdbc.ArrowFlightJdbcDriver")
            val props = Properties().apply { setProperty("useEncryption", "false") }
            return DriverManager.getConnection("jdbc:arrow-flight-sql://127.0.0.1:${edge.edge.port}/", props)
        }

        fun ResultSet.column(name: String): List<String?> = use { buildList { while (next()) add(getString(name)) } }

        afterSpec { allocator.close() }

        "DatabaseMetaData: the world is the one catalog; the schemas are er.entity, obj.query, db.dbo" {
            RunningEdge(door(), RecordedVeles(), session).use { edge ->
                connect(edge).use { conn ->
                    val meta = conn.metaData
                    meta.catalogs.column("TABLE_CAT") shouldContainExactly listOf("hartland_cz")
                    meta.schemas.column("TABLE_SCHEM") shouldContainExactly listOf("db.dbo", "er.entity", "obj.query")
                    meta.identifierQuoteString shouldBe "\""
                    meta.isReadOnly shouldBe true
                    meta.supportsTransactions() shouldBe false
                }
            }
        }

        "getTables lists entities, the logical query and bound tables; getColumns types an entity's attributes" {
            RunningEdge(door(), RecordedVeles(), session).use { edge ->
                connect(edge).use { conn ->
                    val meta = conn.metaData
                    meta.getTables(null, "er.entity", "%", null).column("TABLE_NAME").size shouldBe 36
                    meta.getTables(null, "obj.query", "%", null).column("TABLE_NAME") shouldContainExactly
                        listOf("revenue_per_customer")
                    meta.getTables(null, "db.dbo", "%", arrayOf("VIEW")).column("TABLE_NAME") shouldContainExactly
                        listOf("channel_sales", "document_flow", "return_request")
                    val seen = mutableListOf<Pair<String, Int>>()
                    meta.getColumns(null, "er.entity", "warehouse", "%").use { columns ->
                        while (columns.next()) seen += columns.getString("COLUMN_NAME") to columns.getInt("DATA_TYPE")
                    }
                    seen shouldContainExactly
                        listOf(
                            "sk" to Types.INTEGER,
                            "warehouse_id" to Types.VARCHAR,
                            "warehouse_name" to Types.VARCHAR,
                            "city" to Types.VARCHAR,
                            "state" to Types.VARCHAR,
                        )
                }
            }
        }

        "executeQuery returns the door's rows" {
            val door = door()
            RunningEdge(door, RecordedVeles(), session).use { edge ->
                connect(edge).use { conn ->
                    conn.createStatement().use { st ->
                        val got = mutableListOf<Pair<Int, String?>>()
                        st.executeQuery("SELECT * FROM er.entity.warehouse").use { rs ->
                            while (rs.next()) got += rs.getInt("id") to rs.getString("name")
                        }
                        got shouldContainExactly rows
                    }
                }
                door.requests.last().source shouldBe "SELECT * FROM er.entity.warehouse"
                door.requests
                    .last()
                    .context.userId shouldBe "petr"
            }
        }

        "a PreparedStatement runs the door once per execution" {
            val door = door()
            RunningEdge(door, RecordedVeles(), session).use { edge ->
                connect(edge).use { conn ->
                    conn.prepareStatement("SELECT * FROM er.entity.warehouse").use { ps ->
                        repeat(2) {
                            var n = 0
                            ps.executeQuery().use { rs -> while (rs.next()) n++ }
                            n shouldBe 3
                        }
                    }
                }
                door.requests.count { it.source == "SELECT * FROM er.entity.warehouse" } shouldBe 2
            }
        }

        "an empty Schema serialises (zero-column results still frame)" {
            TtrFlightSqlProducer.serializeSchema(Schema(emptyList())).isNotEmpty() shouldBe true
        }
    })
