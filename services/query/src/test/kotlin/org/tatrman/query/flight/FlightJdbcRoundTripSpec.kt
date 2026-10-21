// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.types.pojo.Schema
import org.tatrman.query.flight.FlightTestKit.RunningEdge
import org.tatrman.query.flight.FlightTestKit.ScriptedDoor
import org.tatrman.query.world.Worlds
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.util.Properties

/**
 * C-1 — the stock Arrow Flight SQL JDBC driver (18.3.0) against the edge, making the calls DBeaver makes when it
 * connects: catalogs, schemas, tables, columns, then one query. The door and Veles are stubs (the recorded hartland
 * catalog). The metadata cases run the authenticator in allow-all TEST mode; the C-2/C-3 cases at the end run the real
 * identity middleware, as a DBeaver user would meet it: `user`/`password`, `token`, `catalog`, `world`.
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

        fun connect(
            edge: RunningEdge,
            vararg properties: Pair<String, String>,
        ): Connection {
            Class.forName("org.apache.arrow.driver.jdbc.ArrowFlightJdbcDriver")
            val props =
                Properties().apply {
                    setProperty("useEncryption", "false")
                    properties.forEach { (k, v) -> setProperty(k, v) }
                }
            return DriverManager.getConnection("jdbc:arrow-flight-sql://127.0.0.1:${edge.edge.port}/", props)
        }

        fun Connection.count(sql: String): Int =
            createStatement().use { st ->
                st.executeQuery(sql).use { rs -> generateSequence { rs.next().takeIf { it } }.count() }
            }

        val worlds = Worlds.parse("hartland_us=pg-hartland-cz,hartland_cz=pg-hartland-cz")

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

        "C-5 — an empty answer still has the statement's columns" {
            val empty =
                ScriptedDoor {
                    listOf(
                        FlightTestKit
                            .tail(0)
                            .toBuilder()
                            .setIsFirst(true)
                            .setArrowIpc(
                                com.google.protobuf.ByteString
                                    .copyFrom(FlightTestKit.ipc(allocator, emptyList())),
                            ).build(),
                    )
                }
            RunningEdge(empty, RecordedVeles(), session).use { edge ->
                connect(edge).use { conn ->
                    conn.createStatement().use { st ->
                        st.executeQuery("SELECT * FROM er.entity.warehouse WHERE 1 = 0").use { rs ->
                            val names = (1..rs.metaData.columnCount).map { rs.metaData.getColumnName(it) }
                            // The edge sends the two columns once; the stock driver (≥ 15.0.0, so 18.3.0 too) lists an
                            // empty result's columns twice (apache/arrow#44467).
                            names.distinct() shouldContainExactly listOf("id", "name")
                            rs.next() shouldBe false
                        }
                    }
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

        "C-2 — user and password: the driver's Basic handshake gets the realm's bearer, and every call runs as petr" {
            AuthTestKit.KeycloakStub().use { keycloak ->
                val door = door()
                val identity =
                    IdentityWorldMiddleware.Factory(
                        FlightAuthenticator(AuthTestKit.verifier, KeycloakPasswordGrant(keycloak.tokenUrl, "jdbc")),
                        worlds,
                    )
                RunningEdge(door, RecordedVeles(), identity = identity, worlds = worlds).use { edge ->
                    connect(edge, "user" to "petr", "password" to "petr-secret").use { conn ->
                        conn.count("SELECT * FROM er.entity.warehouse") shouldBe 3
                        conn.count("SELECT * FROM er.entity.warehouse") shouldBe 3
                    }
                    door.requests.map { it.context.userId }.distinct() shouldContainExactly listOf("petr")
                    // One grant at connect; the driver reuses the bearer for every call after.
                    keycloak.forms.size shouldBe 1
                    val e = shouldThrow<SQLException> { connect(edge, "user" to "petr", "password" to "nope") }
                    e.stackTraceToString() shouldContain "Keycloak refused the password grant (401 invalid_grant)"
                }
            }
        }

        "C-2 — the token property is a bearer; without one the connection is refused" {
            val door = door()
            val identity = IdentityWorldMiddleware.Factory(FlightAuthenticator(AuthTestKit.verifier), worlds)
            RunningEdge(door, RecordedVeles(), identity = identity, worlds = worlds).use { edge ->
                connect(edge, "token" to AuthTestKit.token(user = "marketa")).use { conn ->
                    conn.count("SELECT * FROM er.entity.warehouse") shouldBe 3
                }
                door.requests
                    .last()
                    .context.userId shouldBe "marketa"
                val e = shouldThrow<SQLException> { connect(edge).use { it.count("SELECT 1") } }
                e.stackTraceToString() shouldContain "missing_credentials"
            }
        }

        "C-3 — the catalog property (SetSessionOptions) and the world property (a header) pick the world" {
            val door = door()
            val identity = IdentityWorldMiddleware.Factory(FlightAuthenticator(AuthTestKit.verifier), worlds)
            RunningEdge(door, RecordedVeles(), identity = identity, worlds = worlds).use { edge ->
                val token = AuthTestKit.token()
                val conn = connect(edge, "token" to token, "catalog" to "hartland_us")
                conn.metaData.catalogs.column("TABLE_CAT") shouldContainExactly listOf("hartland_us")
                conn.count("SELECT * FROM er.entity.warehouse") shouldBe 3
                door.requests.last().world shouldBe "hartland_us"
                // Canary — driver 18.3.0 closes its client handler twice, and with `catalog` set the second close
                // re-sends CloseSession on the channel it has just shut down (apache/arrow-java#863): the statements
                // are fine, Connection.close() reports UNAVAILABLE. docs/jdbc.md says so and recommends `world`.
                // When a driver upgrade fixes it this assertion fails: update the doc and drop it.
                shouldThrow<SQLException> { conn.close() }.message shouldBe "Channel shutdown invoked"
                connect(edge, "token" to token, "world" to "hartland_cz").use { conn ->
                    conn.count("SELECT * FROM er.entity.warehouse") shouldBe 3
                }
                door.requests.last().world shouldBe "hartland_cz"
                // An unknown catalog fails the connect with the edge's reason, not the driver's generic one.
                val e = shouldThrow<SQLException> { connect(edge, "token" to token, "catalog" to "hartland_de") }
                e.stackTraceToString() shouldContain "unknown_world: World 'hartland_de' is not served here"
            }
        }

        "an empty Schema serialises (zero-column results still frame)" {
            TtrFlightSqlProducer.serializeSchema(Schema(emptyList())).isNotEmpty() shouldBe true
        }
    })
