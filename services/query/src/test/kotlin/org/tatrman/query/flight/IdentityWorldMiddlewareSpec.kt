// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.apache.arrow.flight.CallOption
import org.apache.arrow.flight.FlightCallHeaders
import org.apache.arrow.flight.FlightClient
import org.apache.arrow.flight.FlightRuntimeException
import org.apache.arrow.flight.FlightStatusCode
import org.apache.arrow.flight.HeaderCallOption
import org.apache.arrow.flight.Location
import org.apache.arrow.flight.SessionOptionValueFactory
import org.apache.arrow.flight.SetSessionOptionsRequest
import org.apache.arrow.flight.auth2.BearerCredentialWriter
import org.apache.arrow.flight.client.ClientCookieMiddleware
import org.apache.arrow.flight.grpc.CredentialCallOption
import org.apache.arrow.flight.sql.FlightSqlClient
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.VarCharVector
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.query.flight.CatalogQualifier.Outcome
import org.tatrman.query.flight.FlightTestKit.RunningEdge
import org.tatrman.query.flight.FlightTestKit.ScriptedDoor
import org.tatrman.query.flight.FlightTestKit.dataBatch
import org.tatrman.query.flight.FlightTestKit.ipc
import org.tatrman.query.flight.FlightTestKit.readAll
import org.tatrman.query.flight.FlightTestKit.tail
import org.tatrman.query.world.Worlds

/**
 * BI (contracts C-3) — every call's world: the `world` header, or the JDBC `catalog` property kept as a cookie. It
 * goes to the door as `RunRequest.world` on every statement; a statement naming another catalog is refused
 * `world_mismatch` before `Run`, and so is a stream whose receipt ran on another world's connection.
 */
class IdentityWorldMiddlewareSpec :
    StringSpec({
        val allocator = RootAllocator()
        afterSpec { allocator.close() }
        val worlds = Worlds.parse("hartland_us=pg-hartland-us,hartland_cz=pg-hartland-cz", requireWorld = true)
        val identity = IdentityWorldMiddleware.Factory(FlightAuthenticator(AuthTestKit.verifier), worlds)
        val bearer = CredentialCallOption(BearerCredentialWriter(AuthTestKit.token()))

        fun world(name: String): CallOption = HeaderCallOption(FlightCallHeaders().apply { insert("world", name) })

        fun answering(connectionId: String = "pg-hartland-cz") =
            ScriptedDoor {
                listOf(dataBatch(0, ipc(allocator, listOf(7 to "Brno DC")), 1), tail(1, connectionId = connectionId))
            }

        fun RunningEdge.run(
            sql: String,
            vararg options: CallOption,
        ): FlightTestKit.Read {
            val info = this.sql.execute(sql, *options)
            return this.sql.getStream(info.endpoints.single().ticket, *options).use { it.readAll() }
        }

        "the world header is the connection's world: on every Run, and the one catalog GetCatalogs lists" {
            val door = answering(connectionId = "pg-hartland-us")
            RunningEdge(door, identity = identity, worlds = worlds).use { edge ->
                edge.run("SELECT id, name FROM er.entity.warehouse", bearer, world("hartland_us")).rows shouldBe
                    listOf(7 to "Brno DC")
                val request = door.requests.single()
                request.world shouldBe "hartland_us"
                request.context.userId shouldBe "petr"
                val catalogs =
                    edge.sql.getCatalogs(bearer, world("hartland_us")).let { info ->
                        edge.sql.getStream(info.endpoints.single().ticket, bearer, world("hartland_us")).use { s ->
                            buildList {
                                while (s.next()) {
                                    val names = s.root.getVector("catalog_name") as VarCharVector
                                    (0 until s.root.rowCount).forEach { add(names.getObject(it).toString()) }
                                }
                            }
                        }
                    }
                catalogs shouldContainExactly listOf("hartland_us")
                edge
                    .awaitOutcomes()
                    .single()
                    .session.world shouldBe "hartland_us"
            }
        }

        "no world can connect even with the requirement on: statement validation belongs to Run" {
            val door = answering()
            RunningEdge(door, identity = identity, worlds = worlds).use { edge ->
                edge.run("SELECT id, name FROM er.entity.warehouse", bearer)
                door.requests.single().world shouldBe ""
            }
        }

        "a world this door does not serve is refused on the call, unknown_world, naming the ones it does" {
            val door = answering()
            RunningEdge(door, identity = identity, worlds = worlds).use { edge ->
                val e =
                    shouldThrow<FlightRuntimeException> { edge.sql.execute("SELECT 1", bearer, world("hartland_de")) }
                e.status().code() shouldBe FlightStatusCode.INVALID_ARGUMENT
                e.message shouldBe
                    "unknown_world: World 'hartland_de' is not served here; the worlds are hartland_cz, hartland_us."
                door.requests.shouldBeEmpty()
            }
        }

        "a statement naming another catalog is world_mismatch, before Run; it is still a statement with an outcome" {
            val door = answering()
            RunningEdge(door, identity = identity, worlds = worlds).use { edge ->
                val e =
                    shouldThrow<FlightRuntimeException> {
                        edge.sql.execute("SELECT * FROM hartland_us.er.entity.warehouse", bearer, world("hartland_cz"))
                    }
                e.status().code() shouldBe FlightStatusCode.UNAUTHORIZED
                e.message shouldBe
                    "world_mismatch: the statement reads catalog 'hartland_us', but this connection's world is 'hartland_cz'."
                door.requests.shouldBeEmpty()
                val outcome = edge.awaitOutcomes().single()
                outcome.status shouldBe StatementOutcome.Status.REFUSED
                outcome.errorCode shouldBe "world_mismatch"
            }
        }

        "a statement naming its own catalog runs, the qualifier blanked out for the door (positions hold)" {
            val door = answering()
            RunningEdge(door, identity = identity, worlds = worlds).use { edge ->
                edge.run("""SELECT w.city FROM "hartland_cz"."er.entity"."warehouse" w""", bearer, world("hartland_cz"))
                // #275: the quoted dotted schema reaches the door as its two names.
                door.requests.single().source shouldBe
                    """SELECT w.city FROM ${" ".repeat(14)}er."entity"."warehouse" w"""
                // S-8: the layer is read past the blanked catalog.
                door.requests.single().sourceSchema shouldBe SchemaCode.ER
                edge.awaitOutcomes().single().statement shouldBe
                    """SELECT w.city FROM "hartland_cz"."er.entity"."warehouse" w"""
            }
        }

        "the receipt guard: rows the door ran on another world's connection are not delivered as this world's" {
            val door = answering(connectionId = "pg-hartland-us")
            RunningEdge(door, identity = identity, worlds = worlds).use { edge ->
                val e = shouldThrow<FlightRuntimeException> { edge.run("SELECT 1", bearer, world("hartland_cz")) }
                e.status().code() shouldBe FlightStatusCode.UNAUTHORIZED
                e.message shouldBe
                    "world_mismatch: the door ran this on connection 'pg-hartland-us', not on world 'hartland_cz' " +
                    "('pg-hartland-cz')."
                edge.awaitOutcomes().single().status shouldBe StatementOutcome.Status.FAILED
                edge.awaitOutcomes().single().errorCode shouldBe "world_mismatch"
            }
        }

        "the catalog session option keeps the world as a cookie, sent back on every later call" {
            val door = answering(connectionId = "pg-hartland-us")
            RunningEdge(door, identity = identity, worlds = worlds).use { edge ->
                RootAllocator().use { clientAllocator ->
                    FlightClient
                        .builder(clientAllocator, Location.forGrpcInsecure("127.0.0.1", edge.edge.port))
                        .intercept(ClientCookieMiddleware.Factory())
                        .build()
                        .use { client ->
                            val sql = FlightSqlClient(client)
                            val set =
                                SetSessionOptionsRequest(
                                    mapOf("catalog" to SessionOptionValueFactory.makeSessionOptionValue("hartland_us")),
                                )
                            client.setSessionOptions(set, bearer).hasErrors() shouldBe false
                            val info = sql.execute("SELECT 1", bearer)
                            sql.getStream(info.endpoints.single().ticket, bearer).use { it.readAll() }
                            door.requests.single().world shouldBe "hartland_us"
                            val refused =
                                SetSessionOptionsRequest(
                                    mapOf("catalog" to SessionOptionValueFactory.makeSessionOptionValue("hartland_de")),
                                )
                            val e = shouldThrow<FlightRuntimeException> { client.setSessionOptions(refused, bearer) }
                            e.message shouldStartWith "unknown_world:"
                        }
                }
            }
        }

        "the client kind: x-ttr-client when it names one, else the gRPC user agent" {
            fun kind(vararg headers: Pair<String, String>) =
                IdentityWorldMiddleware.clientOf(
                    FlightCallHeaders().apply {
                        headers.forEach { (k, v) ->
                            insert(k, v)
                        }
                    },
                )
            kind("x-ttr-client" to "superset", "user-agent" to "grpc-java-netty/1.78.0") shouldBe "superset"
            kind("user-agent" to "grpc-java-netty/1.78.0") shouldBe "jdbc"
            kind("user-agent" to "ADBC Flight SQL Driver/1.12 grpc-go/1.71.0") shouldBe "adbc"
            kind("x-ttr-client" to "excel", "user-agent" to "grpc-python/1.66") shouldBe "other"
            kind() shouldBe "other"
        }

        "catalog qualifiers: the door's schema spellings after a name make it a catalog; strings and comments don't" {
            fun run(
                sql: String,
                world: String = "hartland_cz",
            ) = CatalogQualifier.resolve(sql, world)
            run("SELECT * FROM HARTLAND_CZ.er.entity.warehouse") shouldBe
                Outcome.Run("SELECT * FROM ${" ".repeat(12)}er.entity.warehouse")
            run("SELECT * FROM hartland_cz .\n db.dbo.web_sales") shouldBe
                Outcome.Run("SELECT * FROM ${" ".repeat(13)}\n db.dbo.web_sales")
            run("SELECT * FROM hartland_cz.obj.query.revenue_per_customer") shouldBe
                Outcome.Run("SELECT * FROM ${" ".repeat(12)}obj.query.revenue_per_customer")
            run("SELECT * FROM hartland_us.er.entity.warehouse") shouldBe Outcome.Mismatch("hartland_us")
            run("""SELECT * FROM "hartland_us"."db.dbo"."web_sales"""") shouldBe Outcome.Mismatch("hartland_us")
            run("SELECT * FROM hartland_cz.er.entity.warehouse", world = "") shouldBe Outcome.Mismatch("hartland_cz")
            // R11 — every part quoted, as a tool that quotes every identifier writes it.
            run("""SELECT * FROM "hartland_us"."er"."entity"."warehouse"""") shouldBe Outcome.Mismatch("hartland_us")
            val own =
                """SELECT "hartland_cz"."er"."entity"."warehouse"."city" """ +
                    """FROM "hartland_cz"."er"."entity"."warehouse""""
            run(own) shouldBe Outcome.Run(own.replace("\"hartland_cz\".", " ".repeat(14)))
            run("""SELECT "t"."er" FROM er.entity.warehouse "t"""") shouldBe
                Outcome.Run("""SELECT "t"."er" FROM er.entity.warehouse "t"""")
            // Not catalogs: three-part door names, a column called `db`, literals and comments.
            run("SELECT w.db, w.er FROM er.entity.warehouse w") shouldBe
                Outcome.Run("SELECT w.db, w.er FROM er.entity.warehouse w")
            val quiet =
                "SELECT 'hartland_us.er.entity.x' -- hartland_us.er.entity.x\n FROM er.entity.warehouse /* a.er.b.c */"
            run(quiet) shouldBe Outcome.Run(quiet)
        }

        "#275 — a quoted dotted schema leading a name becomes its two names, at its length; nothing else like it does" {
            fun run(sql: String) = CatalogQualifier.resolve(sql, "hartland_cz")
            run("""SELECT t.* FROM "er.entity".warehouse t""") shouldBe
                Outcome.Run("""SELECT t.* FROM er."entity".warehouse t""")
            run("""SELECT * FROM "DB.dbo"."web_sales" JOIN "obj.query".revenue_per_customer r ON 1 = 1""") shouldBe
                Outcome.Run("""SELECT * FROM DB."dbo"."web_sales" JOIN obj."query".revenue_per_customer r ON 1 = 1""")
            run("""SELECT "er.entity".warehouse.city FROM hartland_cz."er.entity".warehouse""") shouldBe
                Outcome.Run("""SELECT er."entity".warehouse.city FROM ${" ".repeat(12)}er."entity".warehouse""")
            // The namespace stays quoted: a reserved word, any text a database allows.
            run("""SELECT * FROM "db.user".t, "db.Účetnictví"."faktura", "er.entity x".w""") shouldBe
                Outcome.Run("""SELECT * FROM db."user".t, db."Účetnictví"."faktura", er."entity x".w""")
            // The world check still comes first, and a comment between a name's parts is whitespace.
            run("""SELECT * FROM hartland_us."er.entity".warehouse""") shouldBe Outcome.Mismatch("hartland_us")
            run("""SELECT * FROM hartland_us/* x */."er.entity".warehouse""") shouldBe Outcome.Mismatch("hartland_us")
            run("SELECT * FROM hartland_us -- x\n.er.entity.warehouse") shouldBe Outcome.Mismatch("hartland_us")
            // Not schemas: a quoted dotted name that ends the run (an alias, a column), one after an alias, one the
            // statement defines itself (an alias spelled like a schema), another head, no namespace, an escaped quote,
            // literals and comments.
            val untouched =
                listOf(
                    """SELECT w.city AS "er.entity" FROM er.entity.warehouse w""",
                    """SELECT w."er.entity" FROM er.entity.warehouse w""",
                    """SELECT "db.w".city FROM db.dbo.warehouse AS "db.w"""",
                    """SELECT * FROM "dw.entity".warehouse""",
                    """SELECT * FROM "er.".warehouse""",
                    """SELECT * FROM "er.en""tity".warehouse""",
                    """SELECT * FROM "er.entity.warehouse"""",
                    """SELECT '"er.entity".x' FROM er.entity.warehouse -- "er.entity".x""",
                    """SELECT 1 FROM er.entity.warehouse // "er.entity".x""",
                )
            untouched.forEach { run(it) shouldBe Outcome.Run(it) }
        }
    })
