// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

import com.typesafe.config.ConfigFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.apache.arrow.flight.FlightClient
import org.apache.arrow.flight.FlightRuntimeException
import org.apache.arrow.flight.FlightStatusCode
import org.apache.arrow.flight.Location
import org.apache.arrow.flight.sql.FlightSqlClient
import org.apache.arrow.memory.RootAllocator
import org.tatrman.query.flight.FlightTestKit.RunningEdge
import org.tatrman.query.flight.FlightTestKit.ScriptedDoor

class FlightEdgeSpec :
    StringSpec({
        val base =
            """
            grpc { port = 7306, max-message-size-bytes = 33554432 }
            """.trimIndent()

        "the edge is opt-in, on 7308, bound to loopback, calling the door on this pod's gRPC port" {
            val config = FlightConfig.fromConfig(ConfigFactory.parseString(base))
            config.enabled shouldBe false
            config.port shouldBe 7308
            config.bind shouldBe "127.0.0.1"
            config.loopbackHost shouldBe "127.0.0.1"
            config.loopbackPort shouldBe 7306
        }

        "the shipped application.conf reads the same defaults" {
            val config = FlightConfig.fromConfig(ConfigFactory.load())
            config.enabled shouldBe false
            config.port shouldBe 7308
            config.bind shouldBe "127.0.0.1"
            config.pendingTtlSeconds shouldBe 60
            config.catalogTtlSeconds shouldBe 30
        }

        "auth: the issuer names Keycloak's endpoints; password login is client jdbc unless turned off" {
            val shipped = FlightConfig.fromConfig(ConfigFactory.load()).auth
            shipped.issuer shouldBe ""
            shipped.passwordClient shouldBe "jdbc"
            val auth =
                FlightConfig
                    .fromConfig(
                        ConfigFactory.parseString(
                            "$base\nflight.auth { issuer = \"https://keycloak.example/realms/kantheon/\" }",
                        ),
                    ).auth
            auth.effectiveJwksUri shouldBe "https://keycloak.example/realms/kantheon/protocol/openid-connect/certs"
            auth.effectiveTokenUrl shouldBe "https://keycloak.example/realms/kantheon/protocol/openid-connect/token"
            val bearerOnly =
                FlightConfig
                    .fromConfig(
                        ConfigFactory.parseString(
                            "$base\nflight.auth { issuer = \"https://kc/realms/r\", password-client = \"\", " +
                                "jwks-uri = \"http://keycloak:8080/realms/r/protocol/openid-connect/certs\" }",
                        ),
                    ).auth
            bearerOnly.passwordClient shouldBe ""
            bearerOnly.effectiveJwksUri shouldBe "http://keycloak:8080/realms/r/protocol/openid-connect/certs"
        }

        "the shipped worlds table is empty (one world); QUERY_WORLDS fills it" {
            org.tatrman.query.world.Worlds
                .fromConfig(ConfigFactory.load())
                .connections shouldBe emptyMap()
            org.tatrman.query.world.Worlds
                .fromConfig(ConfigFactory.parseString("query.worlds = \"hartland_us=pg-hartland-us\""))
                .connectionOf("hartland_us") shouldBe "pg-hartland-us"
        }

        "the world requirement is opt-in, including the shipped environment override" {
            val table = "query.worlds = \"a=one,b=two\""
            val default =
                org.tatrman.query.world.Worlds
                    .fromConfig(ConfigFactory.parseString(table))
            default.requireWorld shouldBe false
            default.requiresWorld shouldBe false
            val shipped = ConfigFactory.parseResources("application.conf")
            org.tatrman.query.world.Worlds
                .fromConfig(shipped.resolve())
                .requireWorld shouldBe false
            val enabled = ConfigFactory.parseString("QUERY_REQUIRE_WORLD=true\n$table").withFallback(shipped).resolve()
            val worlds =
                org.tatrman.query.world.Worlds
                    .fromConfig(enabled)
            worlds.requireWorld shouldBe true
            worlds.requiresWorld shouldBe true
        }

        "a deployment sets the bind, the port and an explicit loopback" {
            val config =
                FlightConfig.fromConfig(
                    ConfigFactory.parseString(
                        "$base\nflight { enabled = true, port = 17308, bind = \"0.0.0.0\", loopback = \"query:7306\" }",
                    ),
                )
            config.enabled shouldBe true
            config.port shouldBe 17308
            config.bind shouldBe "0.0.0.0"
            config.loopbackHost shouldBe "query"
            config.loopbackPort shouldBe 7306
        }

        "the edge starts, answers, reports itself in /status, and stops" {
            val door = ScriptedDoor { listOf(FlightTestKit.tail(0)) }
            val edge = RunningEdge(door)
            val port = edge.edge.port
            port shouldNotBe 0
            val info = edge.sql.execute("SELECT 1")
            edge.sql.getStream(info.endpoints[0].ticket).use { stream -> while (stream.next()) Unit }
            val status = edge.edge.status()
            status["enabled"]!!.jsonPrimitive.boolean shouldBe true
            status["running"]!!.jsonPrimitive.boolean shouldBe true
            status["port"]!!.jsonPrimitive.int shouldBe port
            status["bind"]!!.jsonPrimitive.content shouldBe "127.0.0.1"
            status["statements"]!!.jsonPrimitive.long shouldBe 1
            edge.close()
            edge.edge.running shouldBe false
            RootAllocator().use { allocator ->
                FlightClient.builder(allocator, Location.forGrpcInsecure("127.0.0.1", port)).build().use { client ->
                    val e = shouldThrow<FlightRuntimeException> { FlightSqlClient(client).execute("SELECT 1") }
                    e.status().code() shouldBe FlightStatusCode.UNAVAILABLE
                }
            }
        }

        "C-1 — an enabled edge that cannot listen fails its start, naming the address" {
            val door = ScriptedDoor { listOf(FlightTestKit.tail(0)) }
            RunningEdge(door).use { first ->
                val taken = FlightTestKit.testConfig().copy(port = first.edge.port)
                val second =
                    FlightEdge.create(
                        config = taken,
                        runner = door,
                        metadata = FlightTestKit.NoMetadata,
                        identity = IdentityWorldMiddleware.Factory(FlightAuthenticator(AuthTestKit.verifier)),
                    )
                val e = shouldThrow<IllegalStateException> { second.start() }
                e.message shouldContain "127.0.0.1:${first.edge.port}"
                second.running shouldBe false
                second.close()
            }
        }

        "C-1 — /ready is not ready while an enabled edge is not running" {
            val door = ScriptedDoor { listOf(FlightTestKit.tail(0)) }
            FlightEdge.ready(doorReady = true, edge = null) shouldBe true
            FlightEdge.ready(doorReady = false, edge = null) shouldBe false
            val stopped =
                FlightEdge.create(
                    config = FlightTestKit.testConfig(),
                    runner = door,
                    metadata = FlightTestKit.NoMetadata,
                    identity = IdentityWorldMiddleware.Factory(FlightAuthenticator(AuthTestKit.verifier)),
                )
            FlightEdge.ready(doorReady = true, edge = stopped) shouldBe false
            stopped.start()
            FlightEdge.ready(doorReady = true, edge = stopped) shouldBe true
            stopped.close()
            FlightEdge.ready(doorReady = true, edge = stopped) shouldBe false
        }

        "/status says the edge is off when it is not configured" {
            FlightEdge.DISABLED_STATUS["enabled"]!!.jsonPrimitive.boolean shouldBe false
        }

        "the production edge authenticates every call: no credential, no statement reaches the door" {
            val door = ScriptedDoor { listOf(FlightTestKit.tail(0)) }
            val config = FlightTestKit.testConfig()
            FlightEdge
                .create(
                    config = config,
                    runner = door,
                    metadata = FlightTestKit.NoMetadata,
                    identity = IdentityWorldMiddleware.Factory(FlightAuthenticator(AuthTestKit.verifier)),
                ).start()
                .use { edge ->
                    RootAllocator().use { clientAllocator ->
                        FlightClient
                            .builder(
                                clientAllocator,
                                Location.forGrpcInsecure("127.0.0.1", edge.port),
                            ).build()
                            .use { client ->
                                val e =
                                    shouldThrow<FlightRuntimeException> { FlightSqlClient(client).execute("SELECT 1") }
                                e.status().code() shouldBe FlightStatusCode.UNAUTHENTICATED
                                e.message shouldStartWith "missing_credentials:"
                            }
                    }
                }
            door.requests.size shouldBe 0
        }
    })
