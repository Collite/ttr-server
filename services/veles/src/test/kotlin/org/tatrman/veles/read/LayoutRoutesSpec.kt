// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.read

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.ttr.metadata.layout.LayoutIndex
import org.tatrman.ttr.metadata.layout.ResolvedLayout
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.search.SearchAlgorithmRegistry
import org.tatrman.ttr.parser.model.LayoutPoint
import org.tatrman.veles.grpc.PackageIndex
import java.nio.file.Files
import java.nio.file.Path

/**
 * VL-P2 T4 + T5 — `GET /model/layouts` and `GET /model/layout?name=` (VL contracts §6, V-39) over the layout
 * fixture loaded through the real source + reconciler. The key sets are held to the viewer's own fixtures
 * (`src/test/resources/layouts-wire/`, A-VL-2: every field always present).
 */
class LayoutRoutesSpec :
    StringSpec({
        val fixture = loadLayoutFixture()
        val wire = Path.of("src/test/resources/layouts-wire")

        fun json(text: String) = Json.parseToJsonElement(text)

        /** every key path in [e] — `layouts[].name`, `positions{}.x` — so two bodies compare by shape */
        fun keys(
            e: JsonElement,
            at: String = "",
        ): Set<String> =
            when (e) {
                is JsonObject ->
                    if (at.endsWith("positions")) {
                        // a map keyed by qname: its values' shape, not its keys
                        e.values.flatMap { keys(it, "$at{}") }.toSet()
                    } else {
                        e.flatMap { (k, v) -> keys(v, "$at.$k") + "$at.$k" }.toSet()
                    }
                is JsonArray -> e.flatMap { keys(it, "$at[]") }.toSet()
                is JsonPrimitive -> emptySet()
            }

        suspend fun io.ktor.server.testing.ApplicationTestBuilder.get(
            path: String,
            registry: MetadataRegistry = fixture.registry,
        ): Pair<HttpStatusCode, String> {
            environment { config = MapApplicationConfig() }
            application {
                routing {
                    velesReadRoutes(
                        registry,
                        SearchAlgorithmRegistry(emptyMap()),
                        null,
                        PackageIndex(),
                    )
                }
            }
            val r = client.get(path)
            return r.status to r.bodyAsText()
        }

        "/model/layouts: the six served layouts, by schema then name, with their counts and modelVersion" {
            testApplication {
                val (status, body) = get("/model/layouts")
                status shouldBe HttpStatusCode.OK
                val o = json(body).jsonObject
                o["layouts"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content } shouldBe
                    listOf("dup_a", "authored", "auto_residue", "main_canvas", "missing", "sidecar")
                o["modelVersion"]!!.jsonPrimitive.content shouldBe fixture.model.version.value
                val missing =
                    o["layouts"]!!.jsonArray.single {
                        it.jsonObject["name"]!!.jsonPrimitive.content ==
                            "missing"
                    }
                json(missing.toString()) shouldBe
                    json(
                        """{"name":"missing","schema":"er","objectCount":1,"missingCount":1,"ambiguousCount":1,""" +
                            """"hasPositions":false,"source":"layouts/missing.ttrg"}""",
                    )
                val authored =
                    o["layouts"]!!.jsonArray.single {
                        it.jsonObject["name"]!!.jsonPrimitive.content ==
                            "authored"
                    }
                authored.jsonObject["objectCount"]!!.jsonPrimitive.content shouldBe "3"
                authored.jsonObject["hasPositions"]!!.jsonPrimitive.content shouldBe "true"
            }
        }

        "/model/layouts: every field present — the key set is the viewer's fixture's (A-VL-2)" {
            testApplication {
                val (_, body) = get("/model/layouts")
                keys(json(body)) shouldBe keys(json(Files.readString(wire.resolve("layouts.json"))))
            }
        }

        "/model/layouts and /model/layout: 503 before a model is loaded" {
            testApplication {
                val (status, body) = get("/model/layouts", MetadataRegistry())
                status shouldBe HttpStatusCode.ServiceUnavailable
                body shouldBe """{"error":"model not loaded"}"""
            }
            testApplication {
                get("/model/layout?name=authored", MetadataRegistry()).first shouldBe HttpStatusCode.ServiceUnavailable
            }
        }

        "/model/layout?name=authored: positions in-file, mode manual, every field present with its empty value" {
            testApplication {
                val (status, body) = get("/model/layout?name=authored")
                status shouldBe HttpStatusCode.OK
                json(body) shouldBe
                    json(
                        """
                        {"name":"authored","schema":"er",
                         "objects":["hartland.er.entity.item","hartland.er.entity.store","hartland.er.entity.store_sales"],
                         "positions":{"hartland.er.entity.item":{"x":320,"y":180},
                                      "hartland.er.entity.store":{"x":580,"y":180},
                                      "hartland.er.entity.store_sales":{"x":320,"y":400}},
                         "mode":"manual","skin":null,"collapsed":[],"missing":[],"ambiguous":[],
                         "ignored":["hartland.er.entity.rel_store_item"],
                         "positionsFrom":"in-file","source":"layouts/authored.ttrg",
                         "modelVersion":"${fixture.model.version.value}"}
                        """.trimIndent(),
                    )
                keys(json(body)) shouldBe keys(json(Files.readString(wire.resolve("layout-positions.json"))))
                // integral coordinates, no decimals (V-3, V-5)
                (body.contains("\"x\":320,") && !body.contains("320.0")) shouldBe true
            }
        }

        "/model/layout?name=sidecar: the sidecar's positions and skin" {
            testApplication {
                val o = json(get("/model/layout?name=sidecar").second).jsonObject
                o["positionsFrom"]!!.jsonPrimitive.content shouldBe "sidecar"
                o["skin"]!!.jsonPrimitive.content shouldBe "er.crow"
                o["positions"]!!.jsonObject["hartland.er.entity.item"] shouldBe json("""{"x":100,"y":120}""")
            }
        }

        "/model/layout?name=missing: the missing and ambiguous bodies, an auto layout" {
            testApplication {
                val o = json(get("/model/layout?name=missing").second).jsonObject
                o["missing"] shouldBe json("""["hartland.er.entity.customer"]""")
                o["ambiguous"] shouldBe
                    json("""[{"ref":"item","candidates":["hartland.er.entity.item","outlet.er.outlet.item"]}]""")
                o["objects"] shouldBe json("""["hartland.er.entity.store"]""")
                o["positions"] shouldBe json("{}")
                o["mode"]!!.jsonPrimitive.content shouldBe "auto"
                o["positionsFrom"] shouldBe json("null")
            }
        }

        "/model/layout: a name that is not an Identifier is 400, an unknown one 404" {
            testApplication {
                get("/model/layout?name=a-b") shouldBe (HttpStatusCode.BadRequest to """{"error":"bad name"}""")
            }
            testApplication {
                get("/model/layout") shouldBe (HttpStatusCode.BadRequest to """{"error":"bad name"}""")
            }
            testApplication {
                get("/model/layout?name=nope") shouldBe (HttpStatusCode.NotFound to """{"error":"not found"}""")
            }
            testApplication {
                // a file that failed (V-38) is not served
                get("/model/layout?name=broken").first shouldBe HttpStatusCode.NotFound
            }
        }

        "diacritics in a qname come back as raw UTF-8, never escaped" {
            // the fixture is ASCII: serve a hand-built index (the qname of the shared vector `diacritics`)
            val q = "hartland.er.entity.příjmové_pásmo"
            val registry = MetadataRegistry()
            val layout =
                ResolvedLayout(
                    name = "pasma",
                    schema = "er",
                    objects = listOf(q),
                    positions = mapOf(q to LayoutPoint(10.0, 20.0)),
                    skin = null,
                    collapsed = emptyList(),
                    missing = emptyList(),
                    ambiguous = emptyList(),
                    ignored = emptyList(),
                    positionsFrom = "in-file",
                    source = "layouts/pasma.ttrg",
                )
            registry.swap(fixture.model, fixture.graph, layouts = LayoutIndex(mapOf("pasma" to layout), emptyList()))
            testApplication {
                val (status, body) = get("/model/layout?name=pasma", registry)
                status shouldBe HttpStatusCode.OK
                body.contains("\"objects\":[\"$q\"]") shouldBe true
                body.contains("\\u") shouldBe false
            }
        }
    })
