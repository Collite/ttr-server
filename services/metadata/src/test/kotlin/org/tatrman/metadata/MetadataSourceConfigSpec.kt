// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import org.tatrman.ttr.metadata.layout.LayoutIndex
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.source.BuiltinStockSource
import org.tatrman.ttr.metadata.source.SourceSnapshot
import org.tatrman.metadata.health.SourceLoadBoard
import org.tatrman.metadata.read.LAYOUT_FIXTURE
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * ttr-server#180 — the shipped source configuration: no fallback unless one is asked for, so a
 * git source that can't be loaded serves no model and is not ready, instead of quietly serving
 * some other model and reporting UP.
 */
class MetadataSourceConfigSpec :
    StringSpec({

        val noEnv = ConfigResolveOptions.defaults().setUseSystemEnvironment(false)
        val shipped: Config = ConfigFactory.parseResources("application.conf")

        fun config(overrides: String): Config =
            ConfigFactory.parseString(overrides).withFallback(shipped).resolve(noEnv)

        /** Inspect source construction independently of the strict publication policy. */
        fun loadAll(config: Config): Pair<SourceLoadBoard, List<SourceSnapshot>> {
            val board = SourceLoadBoard()
            val snapshots =
                buildSources(config, board).map { slot ->
                    runCatching {
                        if (slot.id == BuiltinStockSource.SOURCE_ID) {
                            slot.source.load()
                        } else {
                            board.recording(slot.id, slot.description, slot.source).load()
                        }
                    }.getOrElse { SourceSnapshot(sourceId = "<errored>", priority = 0, version = "errored") }
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
            val dir = Files.createTempDirectory("metadata-src")
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
            val dir = Files.createTempDirectory("metadata-src")
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

        "lexicon source ids come from built Git sources before loading, excluding blank remotes" {
            val dir = Files.createTempDirectory("lexicon-ids")

            fun ids(secondRemote: String): Set<String> {
                val ids = mutableSetOf<String>()
                buildSources(
                    config(
                        """
                        metadata.sources.github-model.remote-uri = "${unreachable(dir)}"
                        metadata.sources.github-model.local-checkout-dir = "${dir.resolve("first")}"
                        metadata.sources.second-model.remote-uri = "$secondRemote"
                        metadata.sources.second-model.local-checkout-dir = "${dir.resolve("second")}"
                        """.trimIndent(),
                    ),
                    lexiconSourceIds = ids,
                )
                return ids
            }
            ids("") shouldBe setOf("github-model")
            ids(unreachable(dir)) shouldBe setOf("github-model", "second-model")
        }

        "credentials in the remote-uri never reach the source description" {
            val dir = Files.createTempDirectory("metadata-src")
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

        "the second git source is off unless its repository is set" {
            val dir = Files.createTempDirectory("metadata-src")
            val resolved = shipped.resolve(noEnv)
            resolved.getString("metadata.sources.second-model.remote-uri") shouldBe ""
            val (board, _) =
                loadAll(
                    config(
                        """
                        metadata.sources.github-model.remote-uri = "${unreachable(dir)}"
                        metadata.sources.github-model.local-checkout-dir = "${dir.resolve("checkout")}"
                        """.trimIndent(),
                    ),
                )
            board.states().map { it.id } shouldBe listOf("github-model")
        }

        "a second git repository is a source of its own, merged into one model" {
            val dir = Files.createTempDirectory("metadata-src")
            val shop =
                Path.of(checkNotNull(this::class.java.classLoader.getResource("fixture-model/shop")).toURI())
            val first =
                gitRepo(dir.resolve("first")) { model -> shop.toFile().copyRecursively(model.resolve("shop").toFile()) }
            val second =
                gitRepo(dir.resolve("second")) { model ->
                    Files.writeString(Files.createDirectories(model.resolve("books")).resolve("books.ttr"), BOOKS)
                }
            val (board, snapshots) =
                loadAll(
                    config(
                        """
                        metadata.sources.github-model.remote-uri = "file://$first"
                        metadata.sources.github-model.local-checkout-dir = "${dir.resolve("checkout-1")}"
                        metadata.sources.second-model.remote-uri = "file://$second"
                        metadata.sources.second-model.local-checkout-dir = "${dir.resolve("checkout-2")}"
                        """.trimIndent(),
                    ),
                )

            board.states().map { it.id } shouldContainExactlyInAnyOrder listOf("github-model", "second-model")
            board.states().all { it.loadedOnce } shouldBe true
            board.states().single { it.id == "second-model" }.description shouldContain "branch master, sub-path model"
            val served =
                ModelReconciler(ModelDescriptor(id = "t", name = "t"))
                    .reconcile(snapshots)
                    .model
                    .objectByQname()
                    .keys
                    .map { it.toString() }
            served.any { "Customer" in it } shouldBe true
            served.any { "Book" in it } shouldBe true
        }

        "a configured git source is read for its layouts — the files Metadata serves (VL V-37)" {
            val dir = Files.createTempDirectory("metadata-src")
            val repo = gitRepo(dir.resolve("repo")) { model -> LAYOUT_FIXTURE.toFile().copyRecursively(model.toFile()) }
            val (_, snapshots) =
                loadAll(
                    config(
                        """
                        metadata.sources.github-model.remote-uri = "file://$repo"
                        metadata.sources.github-model.local-checkout-dir = "${dir.resolve("checkout")}"
                        """.trimIndent(),
                    ),
                )

            val model = ModelReconciler(ModelDescriptor(id = "t", name = "t")).reconcile(snapshots).model
            LayoutIndex.build(snapshots, model).all.map { it.name } shouldBe
                listOf("tables", "authored", "auto_residue", "main_canvas", "missing", "sidecar")
        }

        "a blank-branch git fallback is skipped while the primary serves from a legal blank subdirectory" {
            val root = Files.createTempDirectory("metadata-blank-fallback")
            try {
                val primary =
                    gitRepo(root.resolve("repo")) { model ->
                        Files.writeString(model.resolve("db.ttrm"), "model db\ndef table primary_content {}")
                    }
                val cfg =
                    startupConfig(
                        mapOf(
                            "metadata.sources.github-model.remote-uri" to primary.toUri().toString(),
                            "metadata.sources.github-model.branch" to "master",
                            "metadata.sources.github-model.subdirectory" to "",
                            "metadata.sources.github-model.local-checkout-dir" to root.resolve("checkout").toString(),
                            "metadata.sources.github-model.fallback" to "blank-fallback",
                            "metadata.sources.blank-fallback.enabled" to false,
                            "metadata.sources.blank-fallback.type" to "git",
                            "metadata.sources.blank-fallback.remote-uri" to "file:///unused-metadata-repo",
                            "metadata.sources.blank-fallback.branch" to " ",
                        ),
                    )
                val (board, snapshots) = loadAll(cfg)
                board.states().single().loadedOnce shouldBe true
                board.states().single().servingFallback shouldBe false
                board.states().single().description shouldContain "sub-path /"
                ModelReconciler(ModelDescriptor(id = "t", name = "t"))
                    .reconcile(snapshots)
                    .model
                    .objectByQname()
                    .keys
                    .any { it.name == "primary_content" } shouldBe true
                promotionTarget(cfg, "blank-fallback") shouldBe null
            } finally {
                root.toFile().deleteRecursively()
            }
        }

        "the second source reads the first one's token unless it is given its own" {
            fun password(vararg lines: String) =
                config(lines.joinToString("\n")).getString("metadata.sources.second-model.password")
            password("METADATA_GIT_TOKEN = t1") shouldBe "t1"
            password("METADATA_GIT_TOKEN = t1", "METADATA_GIT2_TOKEN = t2") shouldBe "t2"
        }
    })

/** A second package for the second repository — one entity, so the merged model can be told apart. */
private const val BOOKS = """package books

model er
def entity Book {
    description: "A book"
    attributes: [
        def attribute id { type: int, isKey: true }
    ]
}
"""

/** A one-commit git repository on `master` whose `model/` [fill] writes. */
private fun gitRepo(
    root: Path,
    fill: (Path) -> Unit,
): Path {
    val model = Files.createDirectories(root.resolve("model"))
    fill(model)

    fun git(vararg args: String) {
        val p =
            ProcessBuilder("git", "-c", "user.name=t", "-c", "user.email=t@t", *args)
                .directory(root.toFile())
                .redirectErrorStream(true)
                .start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $out" }
    }
    git("init", "-q", "-b", "master")
    git("add", ".")
    git("commit", "-q", "-m", "model")
    return root
}
