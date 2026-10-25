// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.health

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.tatrman.ttr.metadata.model.AreaRecord
import org.tatrman.ttr.metadata.source.LoadWarning
import org.tatrman.ttr.metadata.source.ModelSource
import org.tatrman.ttr.metadata.source.SourceSnapshot
import java.time.Instant

/** ttr-server#180 — what counts as a configured source having loaded, and what /ready makes of it. */
class SourceLoadBoardSpec :
    StringSpec({

        val area = AreaRecord(name = "hartland", description = "", tags = emptyList(), packages = listOf("hartland"))

        fun snapshot(
            objects: Boolean,
            warnings: List<LoadWarning> = emptyList(),
        ) = SourceSnapshot(
            sourceId = "github-model",
            priority = 100,
            version = "abc123",
            areas = if (objects) mapOf("hartland" to area) else emptyMap(),
            warnings = warnings,
        )

        "a source that has never loaded is not ready, and no source at all is not ready either" {
            val board = SourceLoadBoard()
            board.isReady(Instant.now()) shouldBe false
            board.register("github-model", "git x")
            board.isReady(Instant.now()) shouldBe false
            board.notLoaded() shouldBe listOf("github-model")
        }

        "a load that throws is recorded as a failure and rethrown" {
            val board = SourceLoadBoard()
            val source =
                board.recording(
                    "github-model",
                    "git x",
                    ModelSource { error("Remote branch 'nope' not found") },
                )

            shouldThrow<IllegalStateException> { source.load() }
            shouldThrow<IllegalStateException> { source.load() }

            val state = board.states().single()
            state.loadedOnce shouldBe false
            state.lastError shouldContain "Remote branch 'nope' not found"
            state.consecutiveFailures shouldBe 2
            state.failuresTotal shouldBe 2L
            board.isReady(Instant.now()) shouldBe false
        }

        "a load that finds no model objects is a failure, not a load" {
            val board = SourceLoadBoard()
            board.recording("github-model", "git x", ModelSource { snapshot(objects = false) }).load()

            val state = board.states().single()
            state.loadedOnce shouldBe false
            state.lastError shouldContain "no model objects"
            board.isReady(Instant.now()) shouldBe false
        }

        "ready once the source has loaded and a model built after that load is swapped in" {
            val board = SourceLoadBoard()
            val source = board.recording("github-model", "git x", ModelSource { snapshot(objects = true) })
            val swappedBefore = Instant.now().minusSeconds(60)

            source.load()

            val state = board.states().single()
            state.loadedOnce shouldBe true
            state.version shouldBe "abc123"
            state.objectCount shouldBe 1
            state.lastError shouldBe ""
            // The model on show predates the load (e.g. the built-in roles only): not ready yet.
            board.isReady(swappedBefore) shouldBe false
            board.isReady(null) shouldBe false
            board.isReady(Instant.now().plusSeconds(1)) shouldBe true
        }

        "a later failure does not take readiness away, but it is counted" {
            var fail = false
            val board = SourceLoadBoard()
            val source =
                board.recording(
                    "github-model",
                    "git x",
                    ModelSource { if (fail) error("network down") else snapshot(objects = true) },
                )
            source.load()
            fail = true
            runCatching { source.load() }

            val state = board.states().single()
            state.loadedOnce shouldBe true
            state.consecutiveFailures shouldBe 1
            state.lastError shouldContain "network down"
            board.isReady(Instant.now().plusSeconds(1)) shouldBe true
        }

        "an explicitly configured fallback that served is reported as such" {
            val board = SourceLoadBoard()
            val warning =
                LoadWarning(
                    "github-model",
                    SourceLoadBoard.FALLBACK_MARKER,
                    0,
                    0,
                    "Primary source 'github-model' unavailable",
                )
            board
                .recording(
                    "github-model",
                    "git x",
                    ModelSource { snapshot(objects = true, warnings = listOf(warning)) },
                ).load()

            val state = board.states().single()
            state.loadedOnce shouldBe true
            state.servingFallback shouldBe true
            state.lastError shouldContain "unavailable"
        }

        "failures and loads are metrics" {
            val metrics = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
            val board = SourceLoadBoard(metrics)
            val source = board.recording("github-model", "git x", ModelSource { error("boom") })
            runCatching { source.load() }

            val scrape = metrics.scrape()
            scrape shouldContain """veles_model_source_load_failures_total{source="github-model"} 1.0"""
            scrape shouldContain """veles_model_source_loaded{source="github-model"} 0.0"""
        }

        "credentials in a URI never reach the recorded error" {
            val board = SourceLoadBoard()
            val source =
                board.recording(
                    "github-model",
                    "git x",
                    ModelSource {
                        error(
                            "https://x-access-token:ghp_secret@github.com/acme/model.git: not authorized",
                        )
                    },
                )
            runCatching { source.load() }

            val error = board.states().single().lastError
            error shouldNotContain "ghp_secret"
            error shouldContain "https://***@github.com/acme/model.git"
        }
    })
