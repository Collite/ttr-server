// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.refresh

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.eclipse.jgit.api.Git
import org.tatrman.meta.v1.GetStatusRequest
import org.tatrman.meta.v1.OverallStatus
import org.tatrman.meta.v1.RefreshRequest
import org.tatrman.metadata.buildSources
import org.tatrman.metadata.grpc.MetadataServiceImpl
import org.tatrman.metadata.grpc.PackageIndex
import org.tatrman.metadata.health.SourceLoadBoard
import org.tatrman.metadata.health.modelHealthRoutes
import org.tatrman.metadata.strictSwap
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LoadWarning
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.metadata.source.ModelSource
import org.tatrman.ttr.metadata.source.SourceSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** #258: real parse/reconcile failures through the production Git source construction and publication policy. */
class GuardedRefresherSpec :
    StringSpec({
        "strict swap is shipped on and advertises the configured legacy opt-out" {
            strictSwap(ConfigFactory.empty()) shouldBe true
            val shipped = ConfigFactory.parseResources("application.conf").resolve()
            strictSwap(shipped) shouldBe true
            strictSwap(ConfigFactory.parseString("metadata.strict-swap=false")) shouldBe false
            SwapFixture(strict = false).use { f ->
                f.refresh()
                testApplication {
                    environment { config = MapApplicationConfig() }
                    application { routing { modelHealthRoutes(f.registry, f.board) } }
                    client.get("/status").bodyAsText() shouldContain "\"strict_swap\":false"
                }
            }
        }

        "startup parse failure exposes file issues and never counts as a successful source" {
            SwapFixture().use { f ->
                f.write("bad.ttrm", "model db\ndef table broken {")
                f.commit()
                f.refresh().single { it.sourceId == "world" }.success shouldBe false
                val state = f.board.states().single()
                state.loadedOnce shouldBe false
                state.consecutiveFailures shouldBe 1
                state.lastSuccessAt shouldBe null
                state.lastError shouldContain "strict_swap_rejected"
                state.issues.joinToString() shouldContain "bad.ttrm"
                f.names() shouldBe emptySet()
                testApplication {
                    environment { config = MapApplicationConfig() }
                    application { routing { modelHealthRoutes(f.registry, f.board) } }
                    client.get("/ready").status shouldBe HttpStatusCode.ServiceUnavailable
                    val status = Json.parseToJsonElement(client.get("/status").bodyAsText()).jsonObject
                    status["strict_swap"]!!.jsonPrimitive.content shouldBe "true"
                    status["issues"]!!.jsonArray.toString() shouldContain "bad.ttrm"
                }
            }
        }

        "bad refresh keeps every old object package and sidecar, then the repair publishes together" {
            SwapFixture().use { f ->
                f.write("reports/sales/report.yaml", "title: {en: Original}")
                f.write("reports/sales/template.xlsx", "workbook")
                f.commit()
                f.refresh()
                val prior = f.registry.readOrError()
                val state = f.board.states().single()
                val sourcePath = f.checkout.resolve("model/db.ttrm").toString()
                f.index.packageOf(sourcePath) shouldBe ""
                Files.delete(f.root.resolve("model/db.ttrm"))
                f.write("moved/db.ttrm", "model db\ndef table replacement {}")
                f.write("bad.ttrm", "def table broken {")
                f.write("reports/sales/report.yaml", "title: {en: Bad commit}")
                f.commit()
                repeat(2) { f.refresh().single { it.sourceId == "world" }.success shouldBe false }
                (f.registry.read() === prior) shouldBe true
                f.names() shouldBe setOf("orders")
                f.index.packageOf(sourcePath) shouldBe ""
                f.board.states().single().let {
                    it.version shouldBe state.version
                    it.lastSuccessAt shouldBe state.lastSuccessAt
                    it.consecutiveFailures shouldBe 2
                }
                f.board.isReady(prior.swappedAt) shouldBe true
                Files.delete(f.root.resolve("model/bad.ttrm"))
                f.commit()
                f.refresh().single { it.sourceId == "world" }.snapshotSwapped shouldBe true
                f.names() shouldBe setOf("replacement")
                f.index.packageOf(sourcePath) shouldBe null
                f.index.packageOf(f.checkout.resolve("model/moved/db.ttrm").toString()) shouldBe "moved"
                (f.registry.readOrError().exportSidecars == prior.exportSidecars) shouldBe false
                f.board
                    .states()
                    .single()
                    .consecutiveFailures shouldBe 0
                f.board
                    .states()
                    .single()
                    .issues shouldBe emptyList()
            }
        }

        "an empty source cannot erase a previously served model" {
            SwapFixture().use { f ->
                f.refresh()
                val prior = f.registry.readOrError()
                Files.delete(f.root.resolve("model/db.ttrm"))
                f.write("reports/sales/report.yaml", "title: still not a model")
                f.commit()
                f.refresh().single { it.sourceId == "world" }.errorMessage shouldContain "no model objects"
                (f.registry.read() === prior) shouldBe true
                f.board
                    .states()
                    .single()
                    .consecutiveFailures shouldBe 1
            }
        }

        "pure md content is a model source even though the typed model has no md carriers" {
            SwapFixture().use { f ->
                f.write("db.ttrm", "model md\ndef dimension Buyer { attributes: [def attribute id { type: int }] }")
                f.commit()
                f.refresh().all { it.success } shouldBe true
                f.board
                    .states()
                    .single()
                    .loadedOnce shouldBe true
                f.board.isReady(f.registry.readOrError().swappedAt) shouldBe true
            }
        }

        "non-model template errors stay diagnostic and do not reject a good model" {
            SwapFixture().use { f ->
                f.write("reports/sales/report.yaml", "title: [broken")
                f.commit()
                f.refresh().all { it.success } shouldBe true
                f.names() shouldBe setOf("orders")
                f.board.isReady(f.registry.readOrError().swappedAt) shouldBe true
            }
        }

        "legacy mode publishes partial parse failures and an empty replacement" {
            SwapFixture(strict = false).use { f ->
                f.refresh()
                f.write("other.ttrm", "def table broken {")
                f.write("db.ttrm", "model db\ndef table replacement {}")
                f.commit()
                f.refresh().all { it.success } shouldBe true
                f.names() shouldBe setOf("replacement")
                Files.delete(f.root.resolve("model/db.ttrm"))
                Files.delete(f.root.resolve("model/other.ttrm"))
                f.commit()
                f.refresh().all { it.success } shouldBe true
                f.names() shouldBe emptySet()
            }
        }

        "a rejected version is retried successfully at exactly the same source version" {
            val clean = parsed("one", "orders")
            var next = clean.copy(version = "old")
            val board = SourceLoadBoard().apply { register("one", "one") }
            val registry = MetadataRegistry()
            val refresher = coordinator(listOf(ModelSource { next }), listOf("one"), registry, board)
            refresher.refresh("", true)
            next = clean.copy(version = "new", errors = listOf(LoadWarning("one", "x.ttrm", 1, 1, "bad")))
            refresher.refresh("", true).single().success shouldBe false
            next = clean.copy(version = "new")
            refresher.refresh("", true).single().snapshotSwapped shouldBe true
            board.states().single().version shouldBe "new"
            board.states().single().consecutiveFailures shouldBe 0
        }

        "successful same-version sidecar retries remain cached until a later model swap" {
            var one = parsed("one", "orders").copy(version = "v1", exportSidecars = mapOf("sentinel" to "old"))
            var two = parsed("two", "products").copy(version = "v2")
            val board =
                SourceLoadBoard().apply {
                    register("one", "one")
                    register("two", "two")
                }
            val registry = MetadataRegistry()
            val refresher =
                coordinator(listOf(ModelSource { one }, ModelSource { two }), listOf("one", "two"), registry, board)
            refresher.refresh("", true)
            one = one.copy(exportSidecars = mapOf("sentinel" to "repaired"))
            refresher.refresh("one", true).single { it.sourceId == "one" }.snapshotSwapped shouldBe false
            registry.readOrError().exportSidecars["sentinel"] shouldBe "old"
            two = two.copy(version = "v3")
            refresher.refresh("two", true)
            registry.readOrError().exportSidecars["sentinel"] shouldBe "repaired"
        }

        "source-local rejection keeps last good while a separate source advances" {
            var one = parsed("one", "orders").copy(version = "old1")
            var two = parsed("two", "products").copy(version = "old2")
            val board =
                SourceLoadBoard().apply {
                    register("one", "one")
                    register("two", "two")
                }
            val registry = MetadataRegistry()
            val refresher =
                coordinator(listOf(ModelSource { one }, ModelSource { two }), listOf("one", "two"), registry, board)
            refresher.refresh("", true)
            one = one.copy(version = "bad", droppedFiles = listOf(LoadWarning("one", "bad.ttrm", 1, 1, "dropped")))
            two = parsed("two", "new_products").copy(version = "new2")
            val results = refresher.refresh("", true)
            results.single { it.sourceId == "one" }.success shouldBe false
            results.single { it.sourceId == "two" }.snapshotSwapped shouldBe true
            tableNames(registry) shouldBe setOf("orders", "new_products")
            board.states().single { it.id == "one" }.version shouldBe "old1"
        }

        "reconcile errors reject the proposed transaction without poisoning accepted source versions" {
            var one = parsed("one", "orders").copy(version = "old1")
            var two = parsed("two", "products").copy(version = "old2")
            val board =
                SourceLoadBoard().apply {
                    register("one", "one")
                    register("two", "two")
                }
            val registry = MetadataRegistry()
            val refresher =
                coordinator(listOf(ModelSource { one }, ModelSource { two }), listOf("one", "two"), registry, board)
            refresher.refresh("", true)
            val prior = registry.readOrError()
            one = parsed("one", "duplicate").copy(version = "new1")
            two = parsed("two", "duplicate").copy(version = "new2")
            refresher.refresh("", true).all { !it.success && it.errorMessage.contains("reconcile_rejected") } shouldBe
                true
            (registry.read() === prior) shouldBe true
            board.states().map { it.version } shouldBe listOf("old1", "old2")
            board.states().all { it.consecutiveFailures == 1 && it.lastError.contains("duplicate") } shouldBe true
            // Retry source one alone: the rejected copy of source two must not be reused.
            refresher.refresh("one", true).single { it.sourceId == "one" }.snapshotSwapped shouldBe true
            tableNames(registry) shouldBe setOf("duplicate", "products")
            board.states().single { it.id == "two" }.consecutiveFailures shouldBe 1
        }

        "gRPC source health includes initial failure last-good degradation and recovery" {
            SwapFixture().use { f ->
                val service =
                    MetadataServiceImpl(
                        f.registry,
                        packageIndex = f.index,
                        loadBoard = f.board,
                        refresher = f.refresher,
                    )
                var status = service.getStatus(GetStatusRequest.getDefaultInstance())
                status.modelLoaded shouldBe false
                status.sourcesList.single().status shouldBe OverallStatus.DOWN
                f.refresh()
                status = service.getStatus(GetStatusRequest.getDefaultInstance())
                val success = status.sourcesList.single()
                success.status shouldBe OverallStatus.OK
                success.version shouldBe
                    f.board
                        .states()
                        .single()
                        .version
                success.lastSuccess.isNotEmpty() shouldBe true
                f.write("bad.ttrm", "def table broken {")
                f.commit()
                service.refresh(
                    RefreshRequest
                        .newBuilder()
                        .setSourceId("world")
                        .setForce(true)
                        .build(),
                )
                status = service.getStatus(GetStatusRequest.getDefaultInstance())
                status.overallStatus shouldBe OverallStatus.DEGRADED
                status.sourcesList.single().let {
                    it.status shouldBe OverallStatus.DEGRADED
                    it.version shouldBe success.version
                    it.lastSuccess shouldBe success.lastSuccess
                    it.lastErrorAt.isNotEmpty() shouldBe true
                }
                Files.delete(f.root.resolve("model/bad.ttrm"))
                f.commit()
                f.refresh()
                status = service.getStatus(GetStatusRequest.getDefaultInstance())
                status.sourcesList.single().status shouldBe OverallStatus.OK
                status.sourcesList.single().lastErrorAt shouldBe ""
            }
        }

        "fallback source ids are cached by the configured slot, including targeted refreshes" {
            var next = parsed("fallback", "orders").copy(version = "v1")
            val board = SourceLoadBoard().apply { register("primary", "fallback configured") }
            val registry = MetadataRegistry()
            val refresher = coordinator(listOf(ModelSource { next }), listOf("primary"), registry, board)
            refresher.refresh("", true)
            next = next.copy(version = "v2", errors = listOf(LoadWarning("fallback", "bad", 1, 1, "bad")))
            refresher.refresh("primary", true).single().oldVersion shouldBe "v1"
            tableNames(registry) shouldBe setOf("orders")
            board.states().single().version shouldBe "v1"
        }

        "accepted source health is visible to downstream listeners only after publication" {
            val board = SourceLoadBoard().apply { register("one", "one") }
            val registry = MetadataRegistry()
            val refresher = coordinator(listOf(ModelSource { parsed("one", "orders") }), listOf("one"), registry, board)
            var observedVersion = ""
            var readyInListener = false
            registry.addListener { snapshot ->
                observedVersion = board.states().single().version
                readyInListener = board.isReady(snapshot.swappedAt)
            }
            refresher.refresh("", true)
            observedVersion shouldBe board.states().single().version
            readyInListener shouldBe true
        }

        "failed loads redact credentials in results and diagnostic issues" {
            val board = SourceLoadBoard().apply { register("one", "one") }
            val refresher =
                coordinator(
                    listOf(
                        ModelSource {
                            error("https://user:secret@example.invalid/model.git")
                        },
                    ),
                    listOf("one"),
                    MetadataRegistry(),
                    board,
                )
            refresher.refresh("", true).single().errorMessage shouldNotContain "secret"
            board
                .states()
                .single()
                .issues
                .joinToString() shouldNotContain "secret"
        }

        "startup and refresh serialize, with non-forced calls reporting in-flight" {
            coroutineScope {
                val entered = CompletableDeferred<Unit>()
                val release = CountDownLatch(1)
                val source =
                    ModelSource {
                        entered.complete(Unit)
                        check(release.await(5, TimeUnit.SECONDS))
                        parsed("one", "orders")
                    }
                val refresher = coordinator(listOf(source), listOf("one"), MetadataRegistry(), SourceLoadBoard())
                val first = async(Dispatchers.IO) { refresher.refresh("", true) }
                entered.await()
                try {
                    refresher.refresh("", false).single().errorMessage shouldBe "refresh_in_flight"
                    refresher.refresh("unknown", true).single().errorMessage shouldContain "unknown_source_id"
                } finally {
                    release.countDown()
                }
                first.await().single().success shouldBe true
            }
        }
    })

private fun coordinator(
    sources: List<ModelSource>,
    ids: List<String>,
    registry: MetadataRegistry,
    board: SourceLoadBoard,
) = GuardedRefresher(sources, ids, ModelReconciler(ModelDescriptor("test", "test")), registry, board)

private fun parsed(
    id: String,
    name: String,
): SourceSnapshot {
    val dir = Files.createTempDirectory("strict-source")
    return try {
        Files.writeString(dir.resolve("db.ttrm"), "model db\ndef table $name {}")
        FileBasedSource(id, 100, LocalFsStorage(id, dir)).load()
    } finally {
        dir.toFile().deleteRecursively()
    }
}

private fun tableNames(registry: MetadataRegistry): Set<String> =
    registry
        .read()
        ?.model
        ?.objectByQname()
        ?.values
        ?.filter { it.kind == "table" }
        ?.map { it.qname.name }
        ?.toSet()
        .orEmpty()

private class SwapFixture(
    strict: Boolean = true,
) : AutoCloseable {
    val root: Path = Files.createTempDirectory("strict-swap-git")
    val checkout: Path = Files.createTempDirectory("strict-swap-checkout")
    private val git =
        Git
            .init()
            .setDirectory(root.toFile())
            .setInitialBranch("main")
            .call()
    val registry = MetadataRegistry()
    val board = SourceLoadBoard(strictSwap = strict)
    val index = PackageIndex().commitOnSwap(registry)

    init {
        write("db.ttrm", "model db\ndef table orders {}")
        commit()
    }

    private val slots =
        buildSources(
            ConfigFactory.parseMap(
                mapOf(
                    "metadata.sources.world.type" to "git",
                    "metadata.sources.world.remote-uri" to root.toUri().toString(),
                    "metadata.sources.world.local-checkout-dir" to checkout.toString(),
                    "metadata.sources.world.branch" to "main",
                    "metadata.sources.world.subdirectory" to "model",
                ),
            ),
            board,
        )
    val refresher =
        GuardedRefresher(
            slots.map {
                it.source
            },
            slots.map { it.id },
            ModelReconciler(ModelDescriptor("test", "test")),
            registry,
            board,
            index,
        )

    suspend fun refresh() = refresher.refresh("", true)

    fun names() = tableNames(registry)

    fun write(
        path: String,
        content: String,
    ) {
        val file = root.resolve("model").resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
    }

    fun commit() {
        git.add().addFilepattern(".").call()
        git
            .add()
            .addFilepattern(".")
            .setUpdate(true)
            .call()
        git
            .commit()
            .setMessage(
                "fixture",
            ).setAuthor("Test", "test@example.com")
            .setCommitter("Test", "test@example.com")
            .call()
    }

    override fun close() {
        git.close()
        root.toFile().deleteRecursively()
        checkout.toFile().deleteRecursively()
    }
}
