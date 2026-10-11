// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.source.BuiltinStockSource
import org.tatrman.ttr.metadata.source.SourceSnapshot
import org.tatrman.veles.grpc.PackageIndex
import org.tatrman.veles.health.SourceLoadBoard
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * ttr-server#180 — the shipped source configuration: no fallback unless one is asked for, so a
 * git source that can't be loaded serves no model and is not ready, instead of quietly serving
 * some other model and reporting UP.
 */
class VelesSourceConfigSpec :
    StringSpec({

        val noEnv = ConfigResolveOptions.defaults().setUseSystemEnvironment(false)
        val shipped: Config = ConfigFactory.parseResources("application.conf")

        fun config(overrides: String): Config =
            ConfigFactory.parseString(overrides).withFallback(shipped).resolve(noEnv)

        /** The initial load as Application runs it: a source that throws contributes nothing. */
        fun loadAll(config: Config): Pair<SourceLoadBoard, List<SourceSnapshot>> {
            val board = SourceLoadBoard()
            val snapshots =
                buildSources(config, PackageIndex(), board).map { slot ->
                    runCatching { slot.source.load() }
                        .getOrElse { SourceSnapshot(sourceId = "<errored>", priority = 0, version = "errored") }
                }
            return board to snapshots
        }

        fun unreachable(dir: Path) = "file://${dir.resolve("no-such-repo")}"

        "the shipped configuration names no fallback and bundles no model" {
            val resolved = shipped.resolve(noEnv)
            resolved.getString("metadata.sources.github-model.fallback") shouldBe ""
            resolved.hasPath("metadata.sources.model-ttr") shouldBe false
            resolved.getBoolean("metadata.sources.local-model.enabled") shouldBe false
        }

        "the shipped default source is the public Hartland demo model" {
            val git = shipped.resolve(noEnv).getConfig("metadata.sources.github-model")
            git.getString("remote-uri") shouldBe "https://github.com/Collite/ttr-demo.git"
            git.getString("branch") shouldBe "master"
            git.getString("subdirectory") shouldBe "model"
        }

        "a git source that can't be loaded serves no model, is not ready, and says why" {
            val dir = Files.createTempDirectory("veles-src")
            val (board, snapshots) =
                loadAll(
                    config(
                        """
                        metadata.sources.github-model.remote-uri = "${unreachable(dir)}"
                        metadata.sources.github-model.local-checkout-dir = "${dir.resolve("checkout")}"
                        """.trimIndent(),
                    ),
                )

            val state = board.states().single()
            state.id shouldBe "github-model"
            state.loadedOnce shouldBe false
            state.lastError.shouldNotBeEmpty()
            board.isReady(Instant.now()) shouldBe false

            // Nothing but the built-in stock roles reaches the model.
            val reconciler = ModelReconciler(ModelDescriptor(id = "t", name = "t"))
            val served = reconciler.reconcile(snapshots).model
            val builtinsOnly = reconciler.reconcile(listOf(BuiltinStockSource().load())).model
            served.objectByQname().keys shouldBe builtinsOnly.objectByQname().keys
            served.areas.keys.shouldBeEmpty()
        }

        "an explicitly configured fallback serves, counts as loaded, and is reported" {
            val dir = Files.createTempDirectory("veles-src")
            val local =
                Path
                    .of(
                        checkNotNull(this::class.java.classLoader.getResource("fixture-model/shop")).toURI(),
                    ).parent
            val (board, _) =
                loadAll(
                    config(
                        """
                        metadata.sources.github-model.remote-uri = "${unreachable(dir)}"
                        metadata.sources.github-model.local-checkout-dir = "${dir.resolve("checkout")}"
                        metadata.sources.github-model.fallback = "local-model"
                        metadata.sources.local-model.path = "$local"
                        """.trimIndent(),
                    ),
                )

            val state = board.states().single()
            state.loadedOnce shouldBe true
            state.servingFallback shouldBe true
            state.description shouldContain "fallback 'local-model': filesystem"
        }

        "an empty remote-uri configures no source at all" {
            val (board, _) = loadAll(config("""metadata.sources.github-model.remote-uri = """""))

            board.states().shouldBeEmpty()
            board.isReady(Instant.now()) shouldBe false
        }

        "credentials in the remote-uri never reach the source description" {
            val dir = Files.createTempDirectory("veles-src")
            val (board, _) =
                loadAll(
                    config(
                        """
                        metadata.sources.github-model.remote-uri = "file://user:s3cret@${dir.resolve("no-such-repo")}"
                        metadata.sources.github-model.local-checkout-dir = "${dir.resolve("checkout")}"
                        """.trimIndent(),
                    ),
                )

            val state = board.states().single()
            state.description shouldContain "file://***@"
            (state.description + state.lastError).contains("s3cret") shouldBe false
        }
    })
