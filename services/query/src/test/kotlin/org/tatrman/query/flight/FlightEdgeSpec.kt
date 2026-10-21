// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

import com.typesafe.config.ConfigFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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

        "/status says the edge is off when it is not configured" {
            FlightEdge.DISABLED_STATUS["enabled"]!!.jsonPrimitive.boolean shouldBe false
        }

        "an edge without a verifying authenticator refuses every call" {
            val door = ScriptedDoor { listOf(FlightTestKit.tail(0)) }
            val allocator = RootAllocator()
            val config = FlightTestKit.testConfig()
            val producer =
                TtrFlightSqlProducer(
                    runner = door,
                    catalog = CatalogSource(FlightTestKit.NoVeles, RunnerSchemaProbe(door, allocator)),
                    sessions = SessionResolver { FlightTestKit.SESSION },
                    allocator = allocator,
                    config = config,
                )
            FlightEdge(config, producer, allocator, FlightEdge.REFUSE_ALL).start().use { edge ->
                RootAllocator().use { clientAllocator ->
                    FlightClient
                        .builder(
                            clientAllocator,
                            Location.forGrpcInsecure("127.0.0.1", edge.port),
                        ).build()
                        .use { client ->
                            val e = shouldThrow<FlightRuntimeException> { FlightSqlClient(client).execute("SELECT 1") }
                            e.status().code() shouldBe FlightStatusCode.UNAUTHENTICATED
                        }
                }
            }
            door.requests.size shouldBe 0
        }
    })
