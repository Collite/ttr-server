// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.refresh

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.async
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.eclipse.jgit.api.Git
import org.tatrman.meta.v1.GetStatusRequest
import org.tatrman.meta.v1.RefreshRequest
import org.tatrman.meta.v1.RefreshResponse
import org.tatrman.metadata.buildSources
import org.tatrman.metadata.grpc.MetadataServiceImpl
import org.tatrman.metadata.grpc.PackageIndex
import org.tatrman.metadata.health.SourceLoadBoard
import org.tatrman.metadata.module
import org.tatrman.metadata.startupConfig
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import java.nio.file.Files

class BranchRefreshSpec :
    StringSpec({
        "gate refusal leaves the checkout and publication untouched and the configured default is allowed" {
            BranchFixture(allow = false).use { f ->
                f.ref.refresh("", true)
                val before = f.registry.readOrError()
                val denied = f.ref.refreshOutcome("", true, "preview/one")
                denied.status shouldBe "rejected"
                denied.httpStatus shouldBe 409
                denied.lastAttempt!!.commit shouldBe ""
                denied.lastAttempt!!.branch shouldBe "preview/one"
                denied.keptPrevious shouldBe true
                denied.lastSuccess!!.ok shouldBe true
                f.head() shouldBe f.main
                (f.registry.read() === before) shouldBe true
                f.ref.refreshOutcome("", true, "main").status shouldBe "ok"
            }
        }

        "accepted preview is sticky and HTTP and gRPC expose the accepted commit and distinct registry identity" {
            BranchFixture().use { f ->
                val service =
                    MetadataServiceImpl(f.registry, packageIndex = f.index, loadBoard = f.board, refresher = f.ref)
                f.ref.refresh("", true)
                val reply =
                    service.refresh(
                        RefreshRequest
                            .newBuilder()
                            .setBranch("preview/one")
                            .setForce(true)
                            .build(),
                    )
                RefreshResponse.parseFrom(reply.toByteArray()) shouldBe reply
                reply.status shouldBe "ok"
                reply.serving.branch shouldBe "preview/one"
                reply.serving.commit shouldBe f.preview
                reply.serving.modelVersion shouldBe
                    f.registry
                        .readOrError()
                        .model.version.value
                (reply.serving.modelVersion == f.preview) shouldBe false
                f.names() shouldBe setOf("preview")
                f.ref
                    .refreshOutcome("", true)
                    .serving!!
                    .branch shouldBe "preview/one"
                val status = service.getStatus(GetStatusRequest.getDefaultInstance())
                status.serving shouldBe reply.serving
                status.sourcesList.single().commit shouldBe f.preview
                status.sourcesList.single().branch shouldBe "preview/one"
                status.objectsByKindMap["table"] shouldBe 1
            }
        }

        "strict refusal restores exact prior checkout even when the remote default advanced" {
            BranchFixture().use { f ->
                f.ref.refresh("", true)
                val prior = f.registry.readOrError()
                f.write("model db\ndef table advanced {}")
                f.commit()
                val failed = f.ref.refreshOutcome("", true, "broken")
                failed.status shouldBe "failed"
                failed.keptPrevious shouldBe true
                failed.lastAttempt!!.branch shouldBe "broken"
                failed.lastAttempt!!.commit shouldBe f.broken
                failed.serving!!.commit shouldBe f.main
                failed.lastSuccess!!.branch shouldBe "main"
                f.head() shouldBe f.main
                f.storage().branch shouldBe "main"
                (f.registry.read() === prior) shouldBe true
                f.names() shouldBe setOf("orders")
                f.ref.refreshOutcome("", true).status shouldBe "ok"
                f.names() shouldBe setOf("advanced")
            }
        }

        "unresolvable ref retains last good with an empty attempted commit and rollback needs no network" {
            BranchFixture().use { f ->
                f.ref.refresh("", true)
                val failed = f.ref.refreshOutcome("", true, "no-such-branch")
                failed.lastAttempt!!.commit shouldBe ""
                failed.serving!!.commit shouldBe f.main
                f.head() shouldBe f.main
                val storage = f.storage()
                val point = storage.checkpoint()
                storage.branch = "preview/one"
                storage.fetchVersion() shouldBe f.preview
                val offline = f.root.resolveSibling(f.root.fileName.toString() + "-offline")
                Files.move(f.root, offline)
                try {
                    storage.restore(point)
                    f.head() shouldBe f.main
                    storage.branch shouldBe "main"
                } finally {
                    Files.move(offline, f.root)
                }
            }
        }

        "full commit SHA and same-commit branch selection preserve loaded_at on no-change" {
            BranchFixture().use { f ->
                val first = f.ref.refreshOutcome("", true)
                val pinned = f.ref.refreshOutcome("", true, f.main)
                pinned.status shouldBe "ok"
                pinned.serving!!.branch shouldBe f.main
                pinned.serving!!.commit shouldBe f.main
                pinned.serving!!.loadedAt shouldBe first.serving!!.loadedAt
                pinned.lastAttempt!!.branch shouldBe f.main
                pinned.keptPrevious shouldBe false
            }
        }

        "initial failed preview restores configured default without publishing builtins as content" {
            BranchFixture().use { f ->
                val failed = f.ref.refreshOutcome("", true, "broken")
                failed.status shouldBe "failed"
                failed.serving shouldBe null
                failed.lastSuccess shouldBe null
                failed.lastAttempt!!.commit shouldBe f.broken
                failed.lastAttempt!!.errors.joinToString() shouldContain "strict_swap_rejected"
                failed.keptPrevious shouldBe false
                f.storage().branch shouldBe "main"
                f.head() shouldBe f.main
                f.ref.refreshOutcome("", true).status shouldBe "ok"
            }
        }

        "HTTP production wiring gates branch requests and reports the same accepted model as graph reads" {
            BranchFixture().use { f ->
                val cfg = startupConfig(f.config + mapOf("metadata.allow-non-default-branch" to true))
                testApplication {
                    environment { config = MapApplicationConfig() }
                    application { module(cfg) }
                    client.post("/refresh").status shouldBe HttpStatusCode.OK
                    val reply =
                        Json
                            .parseToJsonElement(
                                client.post("/refresh?branch=preview%2Fone").bodyAsText(),
                            ).jsonObject
                    reply["status"]!!.jsonPrimitive.content shouldBe "ok"
                    reply["serving"]!!.jsonObject["commit"]!!.jsonPrimitive.content shouldBe f.preview
                    client.get("/model/graph").bodyAsText() shouldContain "db.dbo.preview"
                    val failed = Json.parseToJsonElement(client.post("/refresh?branch=broken").bodyAsText()).jsonObject
                    failed["status"]!!.jsonPrimitive.content shouldBe "failed"
                    failed["kept_previous"]!!.jsonPrimitive.content shouldBe "true"
                    val status = Json.parseToJsonElement(client.get("/status").bodyAsText()).jsonObject
                    status["serving"] shouldBe failed["serving"]
                    status["last_attempt"] shouldBe failed["last_attempt"]
                    status["commit"]!!.jsonPrimitive.content shouldBe f.preview
                    client.get("/ready").status shouldBe HttpStatusCode.OK
                }
            }
        }

        "ordinary partial refresh retains rejected Git provenance while independent filesystem content advances" {
            BranchFixture().use { f ->
                val local = Files.createTempDirectory("branch-second")
                try {
                    Files.writeString(local.resolve("db.ttrm"), "model db\ndef table second {}")
                    val second =
                        buildSources(
                            ConfigFactory.parseMap(
                                mapOf(
                                    "metadata.sources.second.type" to "filesystem",
                                    "metadata.sources.second.path" to local.toString(),
                                ),
                            ),
                            f.board,
                        ).single { it.id == "second" }
                    val slots = f.slots + second
                    val ref =
                        GuardedRefresher(
                            slots.map { it.source },
                            slots.map { it.id },
                            ModelReconciler(ModelDescriptor("test", "test")),
                            f.registry,
                            true,
                            setOf("world", "second"),
                            f.board,
                            f.index,
                            true,
                        )
                    val first = ref.refreshOutcome("", true)
                    first.serving!!.source shouldBe "mixed"
                    f.write("model db\ndef table broken {")
                    f.commit()
                    Files.writeString(local.resolve("db.ttrm"), "model db\ndef table advanced {}")
                    val partial = ref.refreshOutcome("", true)
                    partial.status shouldBe "partial"
                    partial.keptPrevious shouldBe true
                    partial.serving!!.commit shouldBe ""
                    partial.lastSuccess shouldBe first.lastSuccess
                    f.head() shouldBe f.main
                    f.names() shouldBe setOf("orders", "advanced")
                    partial.sources
                        .single { it.id == "world" }
                        .origin.commit shouldBe f.main
                } finally {
                    local.toFile().deleteRecursively()
                }
            }
        }

        "queued RPC and scheduled refreshes share one lock and earlier replies remain immutable" {
            BranchFixture().use { f ->
                kotlinx.coroutines.coroutineScope {
                    val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
                    val release = java.util.concurrent.CountDownLatch(1)
                    val block =
                        java.util.concurrent.atomic
                            .AtomicBoolean(false)
                    val inner = f.slots.single { it.id == "world" }.source
                    val outer =
                        ManagedSource(
                            "world",
                            "git",
                            org.tatrman.ttr.metadata.source.ModelSource {
                                inner.load().also {
                                    if (block.compareAndSet(true, false)) {
                                        entered.complete(Unit)
                                        check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                                    }
                                }
                            },
                            f.storage(),
                        )
                    val slots = f.slots.map { if (it.id == "world") outer else it.source }
                    val ref =
                        GuardedRefresher(
                            slots,
                            f.slots.map { it.id },
                            ModelReconciler(ModelDescriptor("test", "test")),
                            f.registry,
                            true,
                            setOf("world"),
                            f.board,
                            f.index,
                            true,
                        )
                    val first = ref.refreshOutcome("", true)
                    val service =
                        MetadataServiceImpl(f.registry, packageIndex = f.index, loadBoard = f.board, refresher = ref)
                    block.set(true)
                    val rpc =
                        async(kotlinx.coroutines.Dispatchers.IO) {
                            service.refresh(
                                RefreshRequest
                                    .newBuilder()
                                    .setBranch("preview/one")
                                    .setForce(true)
                                    .build(),
                            )
                        }
                    entered.await()
                    val scheduler = RefreshScheduler(ref, java.time.Duration.ofMillis(20)).start()
                    try {
                        ref.refresh("", false).all { it.errorMessage == "refresh_in_flight" } shouldBe true
                        val queued = async(kotlinx.coroutines.Dispatchers.IO) { ref.refreshOutcome("", true, "main") }
                        release.countDown()
                        rpc.await().serving.commit shouldBe f.preview
                        queued.await().serving!!.commit shouldBe f.main
                        first.serving!!.commit shouldBe f.main
                        first.lastAttempt!!.branch shouldBe "main"
                    } finally {
                        release.countDown()
                        scheduler.cancel()
                    }
                }
            }
        }

        "cancellation after checkout restores accepted ref and exact commit" {
            BranchFixture().use { f ->
                kotlinx.coroutines.coroutineScope {
                    val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
                    val release = java.util.concurrent.CountDownLatch(1)
                    val cancelLoad =
                        java.util.concurrent.atomic
                            .AtomicBoolean(false)
                    val inner = f.slots.single { it.id == "world" }.source
                    val outer =
                        ManagedSource(
                            "world",
                            "git",
                            org.tatrman.ttr.metadata.source.ModelSource {
                                inner.load().also {
                                    if (cancelLoad.get()) {
                                        entered.complete(Unit)
                                        check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                                        throw kotlinx.coroutines.CancellationException("cancelled load")
                                    }
                                }
                            },
                            f.storage(),
                        )
                    val ref =
                        GuardedRefresher(
                            f.slots.map {
                                if (it.id ==
                                    "world"
                                ) {
                                    outer
                                } else {
                                    it.source
                                }
                            },
                            f.slots.map { it.id },
                            ModelReconciler(ModelDescriptor("test", "test")),
                            f.registry,
                            true,
                            setOf("world"),
                            f.board,
                            f.index,
                            true,
                        )
                    ref.refresh("", true)
                    val before = f.registry.readOrError()
                    cancelLoad.set(true)
                    val pending =
                        async(kotlinx.coroutines.Dispatchers.IO) { ref.refreshOutcome("", true, "preview/one") }
                    entered.await()
                    pending.cancel()
                    release.countDown()
                    pending.join()
                    f.head() shouldBe f.main
                    f.storage().branch shouldBe "main"
                    (f.registry.read() === before) shouldBe true
                }
            }
        }

        "explicit previews never substitute a fallback while ordinary refresh preserves opt-in fallback behavior" {
            BranchFixture().use { f ->
                val local = Files.createTempDirectory("branch-fallback")
                try {
                    Files.writeString(local.resolve("db.ttrm"), "model db\ndef table fallback_content {}")
                    val slots =
                        buildSources(
                            ConfigFactory.parseMap(
                                f.config +
                                    mapOf(
                                        "metadata.sources.world.fallback" to "local",
                                        "metadata.sources.local.enabled" to false,
                                        "metadata.sources.local.type" to "filesystem",
                                        "metadata.sources.local.path" to local.toString(),
                                    ),
                            ),
                            f.board,
                        )
                    val ref =
                        GuardedRefresher(
                            slots.map { it.source },
                            slots.map { it.id },
                            ModelReconciler(ModelDescriptor("test", "test")),
                            f.registry,
                            true,
                            setOf("world"),
                            f.board,
                            f.index,
                            true,
                        )
                    ref.refresh("", true)
                    val refused = ref.refreshOutcome("", true, "broken")
                    refused.status shouldBe "failed"
                    refused.serving!!.commit shouldBe f.main
                    f.names() shouldBe setOf("orders")
                    f.write("model db\ndef table broken {")
                    f.commit()
                    val fallback = ref.refreshOutcome("", true)
                    fallback.status shouldBe "ok"
                    fallback.serving!!.source shouldBe "filesystem"
                    fallback.serving!!.commit shouldBe ""
                    fallback.serving!!.servingFallback shouldBe true
                    f.names() shouldBe setOf("fallback_content")
                } finally {
                    local.toFile().deleteRecursively()
                }
            }
        }

        "initial refusal restores configured main even when an earlier process left a preview checkout" {
            BranchFixture().use { f ->
                Git
                    .cloneRepository()
                    .setURI(f.root.toUri().toString())
                    .setDirectory(f.checkout.toFile())
                    .setBranch("preview/one")
                    .call()
                    .close()
                f.head() shouldBe f.preview
                f.ref.refreshOutcome("", true, "broken").status shouldBe "failed"
                f.head() shouldBe f.main
                f.storage().branch shouldBe "main"
            }
        }

        "rollback restores a symbolic checkout ref without moving local branch tips" {
            BranchFixture().use { f ->
                Git
                    .cloneRepository()
                    .setURI(f.root.toUri().toString())
                    .setDirectory(f.checkout.toFile())
                    .setBranch("main")
                    .call()
                    .close()
                val storage = f.storage()
                val point = storage.checkpoint()
                storage.branch = "preview/one"
                storage.fetchVersion() shouldBe f.preview
                storage.restore(point)
                Git.open(f.checkout.toFile()).use { git ->
                    git.repository.fullBranch shouldBe "refs/heads/main"
                    git.repository.resolve("refs/heads/main").name shouldBe f.main
                }
                f.head() shouldBe f.main
            }
        }

        "unreadable checkout state remains retryable and reports rollback failure" {
            BranchFixture().use { f ->
                f.ref.refresh("", true)
                val before = f.registry.readOrError()
                val saved = f.checkout.resolveSibling(f.checkout.fileName.toString() + "-git")
                Files.move(f.checkout.resolve(".git"), saved)
                Files.createDirectory(f.checkout.resolve(".git"))
                try {
                    val failed = f.ref.refreshOutcome("", true, "preview/one")
                    failed.status shouldBe "failed"
                    failed.lastAttempt!!.commit shouldBe ""
                    failed.lastAttempt!!.errors.joinToString() shouldContain "rollback_failed"
                    failed.serving!!.commit shouldBe f.main
                    (f.registry.read() === before) shouldBe true
                    f.storage().branch shouldBe "main"
                } finally {
                    f.checkout
                        .resolve(".git")
                        .toFile()
                        .deleteRecursively()
                    Files.move(saved, f.checkout.resolve(".git"))
                }
                f.ref.refreshOutcome("", true).status shouldBe "ok"
            }
        }

        "HTTP default gate refuses preview with 409 and accepts an explicit configured default" {
            BranchFixture().use { f ->
                testApplication {
                    environment { config = MapApplicationConfig() }
                    application { module(startupConfig(f.config)) }
                    client.post("/refresh").status shouldBe HttpStatusCode.OK
                    val denied = client.post("/refresh?branch=preview%2Fone")
                    denied.status shouldBe HttpStatusCode.Conflict
                    denied.bodyAsText() shouldContain "branch_not_allowed"
                    client.post("/refresh?branch=main").status shouldBe HttpStatusCode.OK
                    client.get("/model/graph").bodyAsText() shouldContain "db.dbo.orders"
                    f.head() shouldBe f.main
                }
            }
        }

        "unknown, non-Git and ambiguous targets reject explicitly before source loading" {
            BranchFixture().use { f ->
                f.ref.refreshOutcome("missing", true, "preview/one").httpStatus shouldBe 400
                f.ref.refreshOutcome("builtin-stock", true, "preview/one").status shouldBe "rejected"
                val second =
                    buildSources(
                        ConfigFactory.parseMap(f.config.mapKeys { it.key.replace("world", "second") }),
                        f.board,
                    )
                val ref =
                    GuardedRefresher(
                        f.slots.map { it.source } + second.filter { it.id == "second" }.map { it.source },
                        f.slots.map { it.id } + listOf("second"),
                        ModelReconciler(ModelDescriptor("test", "test")),
                        f.registry,
                        true,
                        setOf("world", "second"),
                        f.board,
                        f.index,
                        true,
                    )
                ref
                    .refreshOutcome("", true, "preview/one")
                    .lastAttempt!!
                    .errors
                    .single() shouldContain "ambiguous"
            }
        }
    })

private class BranchFixture(
    allow: Boolean = true,
) : AutoCloseable {
    val root = Files.createTempDirectory("branch-remote")
    val checkout = Files.createTempDirectory("branch-checkout")
    private val git =
        Git
            .init()
            .setDirectory(root.toFile())
            .setInitialBranch("main")
            .call()
    val main: String
    val preview: String
    val broken: String

    init {
        write("model db\ndef table orders {}")
        main = commit()
        git
            .checkout()
            .setCreateBranch(true)
            .setName("preview/one")
            .call()
        write("model db\ndef table preview {}")
        preview = commit()
        git
            .checkout()
            .setCreateBranch(true)
            .setName("broken")
            .call()
        write("model db\ndef table bad {")
        broken = commit()
        git.checkout().setName("main").call()
    }

    val config =
        mapOf(
            "metadata.sources.world.type" to "git",
            "metadata.sources.world.remote-uri" to root.toUri().toString(),
            "metadata.sources.world.local-checkout-dir" to checkout.toString(),
            "metadata.sources.world.branch" to "main",
            "metadata.sources.world.subdirectory" to "model",
        )
    val registry = MetadataRegistry()
    val board = SourceLoadBoard()
    val index = PackageIndex().commitOnSwap(registry)
    val slots = buildSources(ConfigFactory.parseMap(config), board)
    val ref =
        GuardedRefresher(
            slots.map { it.source },
            slots.map { it.id },
            ModelReconciler(ModelDescriptor("test", "test")),
            registry,
            true,
            setOf("world"),
            board,
            index,
            allow,
        )

    fun storage() = (slots.single { it.id == "world" }.source as ManagedSource).git!!

    fun head() = Git.open(checkout.toFile()).use { it.repository.resolve("HEAD").name }

    fun names() =
        registry
            .readOrError()
            .model
            .objectByQname()
            .values
            .filter { it.kind == "table" }
            .map { it.qname.name }
            .toSet()

    fun write(text: String) {
        Files.createDirectories(root.resolve("model"))
        Files.writeString(root.resolve("model/db.ttrm"), text)
    }

    fun commit(): String {
        git.add().addFilepattern(".").call()
        return git
            .commit()
            .setMessage(
                "fixture",
            ).setAuthor("Test", "test@example.com")
            .setCommitter("Test", "test@example.com")
            .call()
            .name
    }

    override fun close() {
        git.close()
        root.toFile().deleteRecursively()
        checkout.toFile().deleteRecursively()
    }
}
