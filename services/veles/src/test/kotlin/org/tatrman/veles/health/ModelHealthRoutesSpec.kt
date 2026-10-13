// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.health

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.AreaRecord
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.ModelSource
import org.tatrman.ttr.metadata.source.SourceSnapshot
import org.tatrman.veles.read.loadLayoutFixture
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** ttr-server#180 — `/ready` waits for the configured source; `/status` says what each source did. */
class ModelHealthRoutesSpec :
    StringSpec({

        val area = AreaRecord(name = "hartland", description = "", tags = emptyList(), packages = listOf("hartland"))

        fun swap(
            registry: MetadataRegistry,
            vararg snapshots: SourceSnapshot,
        ) {
            val model = ModelReconciler(ModelDescriptor(id = "t", name = "t")).reconcile(snapshots.toList()).model
            registry.swap(model, ModelGraph.build(model))
        }

        "no configured source: not ready, and /status says so" {
            testApplication {
                environment { config = MapApplicationConfig() }
                val registry = MetadataRegistry()
                swap(registry)
                application { routing { modelHealthRoutes(registry, SourceLoadBoard()) } }

                client.get("/ready").let {
                    it.status shouldBe HttpStatusCode.ServiceUnavailable
                    it.bodyAsText() shouldContain NO_MODEL_SOURCE
                }
                client.get("/status").bodyAsText().let {
                    it shouldContain "\"ready\":false"
                    it shouldContain "\"error\":\"$NO_MODEL_SOURCE\""
                }
            }
        }

        "a configured source that failed: an (empty) model is swapped in, yet not ready, and the error shows" {
            testApplication {
                environment { config = MapApplicationConfig() }
                val registry = MetadataRegistry()
                val board = SourceLoadBoard()
                val source =
                    board.recording(
                        "github-model",
                        "git https://example.invalid/model.git (branch master, sub-path model)",
                        ModelSource { error("Remote branch 'master' not found") },
                    )
                runCatching { source.load() }
                swap(registry)
                application { routing { modelHealthRoutes(registry, board) } }

                client.get("/ready").let {
                    it.status shouldBe HttpStatusCode.ServiceUnavailable
                    it.bodyAsText() shouldContain "source 'github-model' has not loaded"
                    it.bodyAsText() shouldContain "Remote branch 'master' not found"
                }
                client.get("/status").bodyAsText().let {
                    it shouldContain "\"model_loaded\":true"
                    it shouldContain "\"ready\":false"
                    it shouldContain "\"id\":\"github-model\""
                    it shouldContain
                        "\"source\":\"git https://example.invalid/model.git (branch master, sub-path model)\""
                    it shouldContain "\"loaded\":false"
                    it shouldContain "\"consecutive_failures\":1"
                }
            }
        }

        "the configured source loaded and its model is swapped in: ready" {
            testApplication {
                environment { config = MapApplicationConfig() }
                val registry = MetadataRegistry()
                val board = SourceLoadBoard()
                val snapshot =
                    SourceSnapshot(
                        sourceId = "github-model",
                        priority = 100,
                        version = "abc",
                        areas =
                            mapOf(
                                "hartland" to area,
                            ),
                    )
                board.recording("github-model", "git x", ModelSource { snapshot }).load()
                swap(registry, snapshot)
                application { routing { modelHealthRoutes(registry, board) } }

                client.get("/ready").let {
                    it.status shouldBe HttpStatusCode.OK
                    it.bodyAsText() shouldContain "\"status\":\"UP\""
                }
                client.get("/status").bodyAsText().let {
                    it shouldContain "\"ready\":true"
                    it shouldContain "\"loaded\":true"
                    it shouldContain "\"version\":\"abc\""
                    // a model with no layout files: nothing left out
                    it shouldContain "\"issues\":[]"
                }
            }
        }

        // VL-P2 T3 (contracts V-38) — a layout file that fails is an issue; the model stays loaded and whole
        "/status lists the layout files left out of the index, and the model stays loaded" {
            testApplication {
                environment { config = MapApplicationConfig() }
                val fixture = loadLayoutFixture()
                application { routing { modelHealthRoutes(fixture.registry, SourceLoadBoard()) } }

                val status = Json.parseToJsonElement(client.get("/status").bodyAsText()).jsonObject
                status["model_loaded"]!!.jsonPrimitive.content shouldBe "true"
                status["object_count"]!!.jsonPrimitive.content shouldBe fixture.graph.size().toString()
                status["issues"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe
                    listOf(
                        "layout layouts/broken.ttrg: line 6: expected `,` or `]` in objects",
                        "layout layouts/dup_b.ttrg: the name dup_a is already taken by layouts/dup_a.ttrg",
                    )
            }
        }
    })
