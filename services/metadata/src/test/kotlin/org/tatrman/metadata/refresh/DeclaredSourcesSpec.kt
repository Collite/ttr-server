// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.refresh

import com.sun.net.httpserver.HttpServer
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import io.kotest.assertions.throwables.shouldThrowAny
import io.mockk.every
import io.mockk.spyk
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.eclipse.jgit.api.Git
import org.tatrman.meta.v1.GetStatusRequest
import org.tatrman.metadata.buildSources
import org.tatrman.metadata.grpc.MetadataServiceImpl
import org.tatrman.metadata.grpc.PackageIndex
import org.tatrman.metadata.health.SourceLoadBoard
import org.tatrman.metadata.module
import org.tatrman.metadata.promotionTarget
import org.tatrman.metadata.startupConfig
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream

class DeclaredSourcesSpec :
    StringSpec({
        "explicit declarations replace defaults and legacy env-backed slots remain compatible" {
            val root = Files.createTempDirectory("declared-local")
            try {
                Files.writeString(root.resolve("db.ttrm"), "model db\ndef table local {}")
                val cfg =
                    ConfigFactory
                        .parseString(
                            """
                            include classpath("application.conf")
                            metadata.sources = [{id=local, type=filesystem, path="$root"}]
                            """.trimIndent(),
                        ).resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(false))
                cfg.getString("metadata.descriptor.id") shouldBe "v1-metadata"
                cfg.getInt("grpc.port") shouldBe 7261
                val board = SourceLoadBoard()
                buildSources(cfg, board).map { it.id } shouldBe listOf("builtin-stock-roles", "local")
                board.states().map { it.id } shouldBe listOf("local")
                val shipped = ConfigFactory.parseResources("application.conf")
                val resolved = shipped.resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(false))
                resolved.getString("metadata.sources.github-model.remote-uri") shouldContain "ttr-demo"
                resolved.getString("metadata.sources.github-model.branch") shouldBe "master"
                val raw = shipped.root().render()
                listOf(
                    "METADATA_GIT_REMOTE_URI",
                    "METADATA_GIT2_REMOTE_URI",
                    "METADATA_GIT_TOKEN",
                    "METADATA_GIT2_TOKEN",
                    "METADATA_TTR_ROOT",
                ).forEach {
                    raw shouldContain it
                }
                for (entries in listOf(
                    "[{id=dup,type=filesystem,path=\"$root\"},{id=dup,type=filesystem,path=\"$root\"}]",
                    "[{id=\"../bad\",type=filesystem,path=\"$root\"}]",
                    "[{type=filesystem,path=\"$root\"}]",
                    "[{id=cn-vocabularies,type=filesystem,path=\"$root\"}]",
                    "[{id=builtin-stock,type=filesystem,path=\"$root\"}]",
                    "[{id=builtin-stock-roles,type=filesystem,path=\"$root\"}]",
                    "[{id=bad,type=filesystem,path=\"$root\",subdirectory=\"../outside\"}]",
                )) {
                    shouldThrowAny { buildSources(ConfigFactory.parseString("metadata.sources=$entries")) }
                }
                shouldThrowAny {
                    buildSources(
                        ConfigFactory.parseString(
                            """metadata.sources {a {id=dup,type=filesystem,path="$root"}, """ +
                                """b {id=dup,type=filesystem,path="$root"}}""",
                        ),
                    )
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

        "git filesystem and HTTP reconcile into one captured model with matching transport provenance" {
            BranchFixture().use { git ->
                HttpTree().use { http ->
                    val local = Files.createTempDirectory("declared-fs")
                    try {
                        Files.createDirectories(local.resolve("content"))
                        Files.writeString(local.resolve("content/db.ttrm"), "model db\ndef table mounted {}")
                        val entries =
                            listOf(
                                mapOf(
                                    "id" to "repo",
                                    "type" to "git",
                                    "remote-uri" to git.root.toUri().toString(),
                                    "branch" to "main",
                                    "subdirectory" to "model",
                                    "local-checkout-dir" to git.checkout.toString(),
                                    "token" to "private-git-token",
                                ),
                                mapOf(
                                    "id" to "disk",
                                    "type" to "filesystem",
                                    "path" to local.toString(),
                                    "subdirectory" to "content",
                                ),
                                http.entry("remote") + mapOf("subdirectory" to "tree"),
                            )
                        http.body = tarTree("tree/remote/db.ttrm" to "model db\ndef table delivered {}")
                        val f = DeclaredFixture(entries)
                        f.ref.refreshOutcome("", true).status shouldBe "ok"
                        f.names() shouldBe setOf("orders", "mounted", "delivered")
                        val result = f.ref.status()
                        result.serving!!.source shouldBe "mixed"
                        result.sources.associate { it.id to it.origin.source } shouldBe
                            mapOf("repo" to "git", "disk" to "filesystem", "remote" to "http")
                        result.sources
                            .single { it.id == "repo" }
                            .origin.commit shouldBe git.main
                        result.sources.filter { it.id != "repo" }.all {
                            it.origin.commit.isEmpty() &&
                                it.origin.branch.isEmpty()
                        } shouldBe
                            true
                        val rpc =
                            MetadataServiceImpl(
                                f.registry,
                                packageIndex = f.index,
                                loadBoard = f.board,
                                refresher = f.ref,
                            ).getStatus(GetStatusRequest.getDefaultInstance())
                        rpc.sourcesList.associate { it.id to it.source } shouldBe
                            mapOf("repo" to "git", "disk" to "filesystem", "remote" to "http")
                        f.ref.refreshOutcome("remote", true, "main").httpStatus shouldBe 400
                        promotionTarget(f.config, "repo")!!.branch shouldBe "main"
                        promotionTarget(f.config, "remote") shouldBe null
                        promotionTarget(
                            ConfigFactory.parseMap(mapOf("metadata.sources.http.url" to http.url)),
                            "http",
                        ) shouldBe null
                        http.requests.all { it.auth == "Bearer secret-remote" } shouldBe true
                        testApplication {
                            environment { config = MapApplicationConfig() }
                            application { module(startupConfig(mapOf("metadata.sources" to entries))) }
                            client.post("/refresh").status shouldBe HttpStatusCode.OK
                            client.get("/ready").status shouldBe HttpStatusCode.OK
                            val status = Json.parseToJsonElement(client.get("/status").bodyAsText()).jsonObject
                            status["serving"]!!.jsonObject["source"]!!.jsonPrimitive.content shouldBe "mixed"
                            status["sources"]!!.jsonArray.associate {
                                it.jsonObject["id"]!!.jsonPrimitive.content to
                                    it.jsonObject["source_type"]!!.jsonPrimitive.content
                            } shouldBe mapOf("repo" to "git", "disk" to "filesystem", "remote" to "http")
                            client.get("/model/graph").bodyAsText() shouldContain "db.dbo.delivered"
                        }
                    } finally {
                        local.toFile().deleteRecursively()
                    }
                }
            }
        }

        "ETag reuse revalidates refused trees and body versions are independent of validators" {
            HttpTree().use { http ->
                val f = DeclaredFixture(listOf(http.entry()))
                f.ref.refreshOutcome("", true).status shouldBe "ok"
                val first =
                    f.ref
                        .status()
                        .sources
                        .single()
                        .version
                val snapshot = f.registry.read()
                f.ref.refreshOutcome("remote", true).status shouldBe "ok"
                http.requests.last().etag shouldBe "\"v1\""
                (f.registry.read() === snapshot) shouldBe true
                http.body = tarTree("db.ttrm" to "model db\ndef table newer {}")
                http.conditional = false // A valid 200 with changed bytes must advance even with the same ETag.
                f.ref.refreshOutcome("remote", true).status shouldBe "ok"
                (
                    f.ref
                        .status()
                        .sources
                        .single()
                        .version == first
                ) shouldBe false
                f.names() shouldBe setOf("newer")
                val accepted = f.registry.read()
                http.etag = "\"bad\""
                http.body = tarTree("db.ttrm" to "model db\ndef table broken {")
                f.ref.refreshOutcome("remote", true).status shouldBe "failed"
                http.conditional = true
                f.ref.refreshOutcome("remote", true).status shouldBe "failed" // 304 must not bypass strict swap.
                http.requests.last().etag shouldBe "\"bad\""
                (f.registry.read() === accepted) shouldBe true
                f.names() shouldBe setOf("newer")
                http.etag = null
                http.body = tarTree("db.ttrm" to "model db\ndef table recovered {}")
                f.ref.refreshOutcome("remote", true).status shouldBe "ok"
                f.ref.refreshOutcome("remote", true).status shouldBe "ok"
                http.requests.last().etag shouldBe null
                f.names() shouldBe setOf("recovered")
            }
        }

        "HTTP transport archive and size failures retain accepted content while an independent source advances" {
            HttpTree().use { http ->
                val local = Files.createTempDirectory("declared-independent")
                try {
                    val model = local.resolve("db.ttrm")
                    Files.writeString(model, "model db\ndef table local_before {}")
                    val f =
                        DeclaredFixture(
                            listOf(
                                http.entry(),
                                mapOf(
                                    "id" to "disk",
                                    "type" to "filesystem",
                                    "path" to local.toString(),
                                ),
                            ),
                        )
                    f.ref.refreshOutcome("", true).status shouldBe "ok"
                    val remoteVersion =
                        f.ref
                            .status()
                            .sources
                            .single { it.id == "remote" }
                            .version
                    http.status = 503
                    Files.writeString(model, "model db\ndef table local_after {}")
                    // Force a distinct filesystem version without relying on the host clock's resolution.
                    Files.setLastModifiedTime(
                        model,
                        java.nio.file.attribute.FileTime.fromMillis(
                            System.currentTimeMillis() + 10000,
                        ),
                    )
                    f.ref.refreshOutcome("", true).status shouldBe "partial"
                    f.names() shouldBe setOf("delivered", "local_after")
                    http.status = 200
                    http.conditional = false
                    val accepted = f.registry.read()
                    for ((unsafe, code) in listOf(
                        tarTree("../escape.ttrm" to "bad") to "http_source_unsafe_path",
                        tarTree("/absolute.ttrm" to "bad") to "http_source_unsafe_path",
                        tarTree("a\\b.ttrm" to "bad") to "http_source_unsafe_path",
                        tarTree("db.ttrm" to "one", "db.ttrm" to "two") to "http_source_duplicate_path",
                        tarTree("link" to "ignored", link = true) to "http_source_unsafe_entry",
                        "not gzip".toByteArray() to "http_source_read_failed",
                    )) {
                        http.body = unsafe
                        val refused = f.ref.refreshOutcome("remote", true)
                        refused.status shouldBe "failed"
                        refused.lastAttempt!!.errors.joinToString() shouldContain code
                        (f.registry.read() === accepted) shouldBe true
                    }
                    f.ref
                        .status()
                        .sources
                        .single { it.id == "remote" }
                        .version shouldBe remoteVersion
                    http.status = 304 // Previous good cache survives malformed 200 responses.
                    f.ref.refreshOutcome("remote", true).status shouldBe "ok"
                    http.status = 200
                    http.body = tarTree("db.ttrm" to "model db\ndef table huge {}".repeat(1000))
                    val small = DeclaredFixture(listOf(http.entry() + mapOf("max-bytes" to 1024)))
                    small.ref.refreshOutcome("", true).status shouldBe "failed"
                    http.status = 304
                    val noCache = DeclaredFixture(listOf(http.entry()))
                    noCache.ref.refreshOutcome("", true).status shouldBe "failed"
                    http.status = 302
                    val before = http.requests.size
                    http.location = http.url + "?secret=redirect"
                    f.ref.refreshOutcome("remote", true).status shouldBe "failed"
                    http.requests.size shouldBe before + 1
                    (
                        f.ref
                            .status()
                            .toString()
                            .contains("secret-remote")
                    ) shouldBe false
                    http.status = 200
                    http.delayMillis = 150
                    http.body = tarTree("db.ttrm" to "model db\ndef table timely {}")
                    val timeout = DeclaredFixture(listOf(http.entry() + mapOf("read-timeout-ms" to 25)))
                    val timedOut = timeout.ref.refreshOutcome("", true)
                    timedOut.status shouldBe "failed"
                    timedOut.lastAttempt!!.errors.joinToString() shouldContain "http_source_timeout"
                } finally {
                    local.toFile().deleteRecursively()
                }
            }
        }

        "F1 accepted filesystem CN content promotes with captured source version while a Git fallback cannot" {
            BranchFixture().use { git ->
                val clinic =
                    org.tatrman.metadata.cn.CnTestKit
                        .clinicWorld()
                try {
                    Files.walk(clinic).use { paths ->
                        paths.filter { Files.isRegularFile(it) }.forEach { file ->
                            val target = git.root.resolve("model").resolve(clinic.relativize(file))
                            Files.createDirectories(target.parent)
                            Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        }
                    }
                    val store =
                        org.tatrman.metadata.cn
                            .InMemoryCandidateStore()
                    val candidates =
                        org.tatrman.metadata.cn.CandidateService(
                            store,
                            org.tatrman.metadata.cn.CnTestKit.policy,
                            org.tatrman.metadata.cn.Promoter(
                                org.tatrman.metadata.cn.StoragePromotionFiles(
                                    org.tatrman.ttr.metadata.source
                                        .LocalFsStorage("clinic", git.root.resolve("model")),
                                ),
                                modelRoot = "model",
                            ),
                        )
                    val id =
                        candidates
                            .submit(
                                org.tatrman.metadata.cn
                                    .CnCaller("clinic-evidence", false, emptySet()),
                                org.tatrman.metadata.cn.CnTestKit
                                    .candidate("evidence-phase"),
                            ).candidate.id
                    val pending =
                        candidates.promote(
                            org.tatrman.metadata.cn
                                .CnCaller("steward", true, setOf("cn-steward")),
                            id,
                        )
                    Git.open(git.root.toFile()).use {
                        it.apply().setPatch(java.io.ByteArrayInputStream(pending.promotion!!.diff.toByteArray())).call()
                    }
                    val disk =
                        mapOf(
                            "id" to "disk",
                            "type" to "filesystem",
                            "path" to git.root.resolve("model").toString(),
                        )
                    val fallback =
                        DeclaredFixture(
                            listOf(
                                mapOf(
                                    "id" to "git",
                                    "type" to "git",
                                    "remote-uri" to
                                        git.root
                                            .resolve("missing")
                                            .toUri()
                                            .toString(),
                                    "branch" to "main",
                                    "local-checkout-dir" to git.checkout.toString(),
                                    "fallback" to "disk",
                                ),
                                disk + mapOf("enabled" to false),
                            ),
                            strict = false,
                        )
                    val fallbackOutcome = fallback.ref.refreshOutcome("", true)
                    fallbackOutcome.status shouldBe "ok"
                    fallbackOutcome.sources
                        .single()
                        .origin.fallback shouldBe true
                    fallback.ref.promotionCommit("git", fallbackOutcome) shouldBe null
                    val fsFallback =
                        DeclaredFixture(
                            listOf(
                                disk +
                                    mapOf(
                                        "path" to git.root.resolve("absent-fs").toString(),
                                        "fallback" to "backup",
                                    ),
                                disk + mapOf("id" to "backup", "enabled" to false),
                            ),
                            strict = false,
                        )
                    fsFallback.ref.addPublicationListener("disk") { outcome, complete ->
                        fsFallback.ref.promotionCommit("disk", outcome)?.let { version ->
                            candidates.observe(
                                outcome.snapshot!!.model.schemas["cn"] as org.tatrman.ttr.metadata.model.CnSchema,
                                version,
                            )
                        }
                        complete(true)
                    }
                    val fsFallbackOutcome = fsFallback.ref.refreshOutcome("", true)
                    fsFallbackOutcome.status shouldBe "ok"
                    fsFallbackOutcome.sources
                        .single()
                        .origin.fallback shouldBe true
                    // S8: filesystem primary + filesystem fallback isolates the fallback exclusion.
                    store.get(id)!!.state shouldBe org.tatrman.metadata.cn.CandidateState.PENDING_MERGE
                    val fs = DeclaredFixture(listOf(disk), strict = false)
                    fs.ref.addPublicationListener("disk") { outcome, complete ->
                        fs.ref.promotionCommit("disk", outcome)?.let { version ->
                            candidates.observe(
                                outcome.snapshot!!.model.schemas["cn"] as org.tatrman.ttr.metadata.model.CnSchema,
                                version,
                            )
                        }
                        complete(true)
                    }
                    val accepted = fs.ref.refreshOutcome("", true)
                    accepted.status shouldBe "ok"
                    store.get(id)!!.state shouldBe org.tatrman.metadata.cn.CandidateState.PROMOTED
                    store
                        .get(id)!!
                        .candidate.resolution!!
                        .canonRef shouldBe
                        "${accepted.sources.single().version}:cn.IschemicPatient"
                    // Provenance is captured: a later failed load cannot alter this decision.
                    fs.ref.promotionCommit("disk", accepted) shouldBe accepted.sources.single().version
                } finally {
                    clinic.toFile().deleteRecursively()
                }
            }
        }

        "F3 whole-response deadline bounds a drip and keeps prior HTTP tree and ETag while another source advances" {
            HttpTree().use { slow ->
                HttpTree().use { clean ->
                    val f =
                        DeclaredFixture(
                            listOf(slow.entry("slow") + mapOf("read-timeout-ms" to 300), clean.entry("clean")),
                        )
                    clean.body = tarTree("db.ttrm" to "model db\ndef table clean {}")
                    f.ref.refreshOutcome("", true).status shouldBe "ok"
                    val oldVersion =
                        f.ref
                            .status()
                            .sources
                            .single { it.id == "slow" }
                            .version
                    slow.conditional = false
                    slow.etag = "\"unaccepted\""
                    // At one byte per 20 ms, this response drips for over ten seconds.
                    slow.body = tarTree("db.ttrm" to "model db\ndef table never {}") + ByteArray(500)
                    slow.dripMillis = 20
                    clean.etag = "\"new-clean\""
                    clean.body = tarTree("db.ttrm" to "model db\ndef table advanced {}")
                    // Time the storage request alone, not the independent reload/reconciliation.
                    val storage = HttpTreeStorage("bounded", slow.url, readTimeoutMs = 300)
                    val start = System.nanoTime()
                    shouldThrowAny { storage.fetchVersion() }.message shouldBe "http_source_timeout"
                    val elapsedMs = (System.nanoTime() - start) / 1_000_000
                    (elapsedMs < 3000) shouldBe true
                    val result = f.ref.refreshOutcome("", true)
                    result.status shouldBe "partial"
                    result.sources.single { it.id == "slow" }.lastError shouldContain "http_source_timeout"
                    result.sources.single { it.id == "slow" }.version shouldBe oldVersion
                    f.names() shouldBe setOf("delivered", "advanced")
                    // A quick 304 must reuse the original cache and its ETag, and reacquire the publication lock.
                    slow.dripMillis = 0
                    slow.etag = "\"v1\""
                    slow.conditional = true
                    slow.status = 304
                    f.ref.refreshOutcome("slow", true).status shouldBe "ok"
                    slow.requests.last().etag shouldBe "\"v1\""
                    f.names() shouldBe setOf("delivered", "advanced")
                }
            }
        }

        "S2 connect timeout fits the response budget and expiry during connect skips headers" {
            val connection =
                object : java.net.HttpURLConnection(java.net.URI("http://unused.invalid/").toURL()) {
                    var headerReads = 0
                    var simulatedDns = false

                    override fun usingProxy() = false

                    override fun disconnect() { /* No JDK client exists until connect finishes. */ }

                    override fun connect() {
                        Thread.sleep(if (simulatedDns) 200 else connectTimeout.toLong())
                        if (!simulatedDns) throw java.net.SocketTimeoutException()
                    }

                    override fun getResponseCode(): Int {
                        headerReads++
                        return 200
                    }
                }
            val storage =
                HttpTreeStorage(
                    "connect",
                    "http://unused.invalid/",
                    connectTimeoutMs = 10_000,
                    readTimeoutMs = 100,
                    openConnection = { connection },
                )
            val start = System.nanoTime()
            shouldThrowAny { storage.fetchVersion() }.message shouldBe "http_source_timeout"
            ((System.nanoTime() - start) / 1_000_000 < 3000) shouldBe true
            connection.headerReads shouldBe 0
            // DNS cannot be interrupted: once it returns after expiry, never enter the header phase.
            connection.simulatedDns = true
            shouldThrowAny { storage.fetchVersion() }.message shouldBe "http_source_timeout"
            connection.headerReads shouldBe 0
        }

        "S2 F3a watchdog bounds dripping headers before the body loop starts" {
            java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
                val executor = Executors.newSingleThreadExecutor()
                val accepted = java.util.concurrent.CompletableFuture<java.net.Socket>()
                val worker =
                    executor.submit {
                        runCatching {
                            server.accept().use { socket ->
                                accepted.complete(socket)
                                val output = socket.getOutputStream()
                                output.write("HTTP/1.1 200 OK\r\nX-Drip: ".toByteArray())
                                output.flush()
                                repeat(500) {
                                    output.write('x'.code)
                                    output.flush()
                                    Thread.sleep(20)
                                }
                                output.write("\r\nContent-Length: 0\r\n\r\n".toByteArray())
                                output.flush()
                            }
                        }
                    }
                try {
                    val storage =
                        HttpTreeStorage("headers", "http://127.0.0.1:${server.localPort}/", readTimeoutMs = 300)
                    val start = System.nanoTime()
                    shouldThrowAny { storage.fetchVersion() }.message shouldBe "http_source_timeout"
                    ((System.nanoTime() - start) / 1_000_000 < 3000) shouldBe true
                } finally {
                    accepted.get(3, java.util.concurrent.TimeUnit.SECONDS).close()
                    worker.cancel(true)
                    executor.shutdownNow()
                }
            }
        }

        "S2 F3b bounded reader checks its deadline between individual body reads" {
            var reads = 0
            val input =
                object : java.io.InputStream() {
                    override fun read(): Int = error("bulk reads only")

                    override fun read(
                        bytes: ByteArray,
                        off: Int,
                        len: Int,
                    ): Int {
                        reads++
                        bytes[off] = 1
                        return 1
                    }
                }
            shouldThrowAny {
                readBounded(input, 20) { check(reads < 3) { "http_source_timeout" } }
            }.message shouldBe "http_source_timeout"
            reads shouldBe 3
        }

        "S2 completed bodies keep archive diagnostics even when decoding crosses the deadline" {
            HttpTree().use { http ->
                http.body = tarTree("../evil.ttrm" to "model db")
                val real = HttpTreeStorage("unsafe", http.url, readTimeoutMs = 1000)
                shouldThrowAny { real.fetchVersion() }.message shouldBe "http_source_unsafe_path"
                // Delay only decoding: delivery completes well within the network budget.
                val slowDecode =
                    spyk(HttpTreeStorage("decode", http.url, readTimeoutMs = 300), recordPrivateCalls = true)
                every { slowDecode["decode"](any<ByteArray>()) } answers {
                    Thread.sleep(600)
                    error("http_source_unsafe_path")
                }
                shouldThrowAny { slowDecode.fetchVersion() }.message shouldBe "http_source_unsafe_path"
            }
        }

        "F7 HTTP and filesystem strip model subdirectory before deriving packages and exclude siblings" {
            HttpTree().use { http ->
                val local = Files.createTempDirectory("declared-subdir")
                try {
                    val files =
                        listOf(
                            "model/pkg/a.ttrm" to "package pkg\nmodel db\ndef table included {}",
                            "other/b.ttrm" to "package other\nmodel db\ndef table excluded {}",
                        )
                    files.forEach { (path, text) ->
                        val file = local.resolve(path)
                        Files.createDirectories(file.parent)
                        Files.writeString(file, text)
                    }
                    http.body = tarTree(*files.toTypedArray())
                    listOf(
                        http.entry() + mapOf("subdirectory" to "model"),
                        mapOf(
                            "id" to "disk",
                            "type" to "filesystem",
                            "path" to local.toString(),
                            "subdirectory" to "model",
                        ),
                    ).forEach { entry ->
                        val f = DeclaredFixture(listOf(entry))
                        val result = f.ref.refreshOutcome("", true)
                        result.status shouldBe "ok"
                        f.names() shouldBe setOf("included")
                        f.registry
                            .readOrError()
                            .model
                            .objectByQname()
                            .values
                            .filter { it.kind == "table" }
                            .map { f.index.packageOf(it.sourceFile) }
                            .toSet() shouldBe setOf("pkg")
                        (result.toString().contains("package-declaration-mismatch")) shouldBe false
                    }
                } finally {
                    local.toFile().deleteRecursively()
                }
            }
        }

        "F8 unpadded tar with post-EOF tail CRC failure preserves the accepted HTTP tree and ETag" {
            HttpTree().use { http ->
                http.conditional = false
                val f = DeclaredFixture(listOf(http.entry()))
                f.ref.refreshOutcome("", true).status shouldBe "ok"
                val accepted = f.registry.read()
                http.etag = "\"bad\""
                http.body =
                    tarTree(
                        "db.ttrm" to "model db\ndef table corrupt {}",
                        recordSize = 512,
                        trailingBytes = 16_384,
                    ).also {
                        it[it.size - 8] = (it[it.size - 8].toInt() xor 1).toByte()
                    }
                val result = f.ref.refreshOutcome("", true)
                result.status shouldBe "failed"
                (f.registry.read() === accepted) shouldBe true
                http.status = 304
                f.ref.refreshOutcome("", true).status shouldBe "ok"
                http.requests.last().etag shouldBe "\"v1\""
                f.names() shouldBe setOf("delivered")
            }
        }

        "initial HTTP failure recovers and disabled list fallback retains existing readiness semantics" {
            HttpTree().use { http ->
                http.status = 503
                val f = DeclaredFixture(listOf(http.entry()))
                f.ref.refreshOutcome("", true).serving shouldBe null
                f.ref.status().ready shouldBe false
                f.ref
                    .status()
                    .sources
                    .single()
                    .origin.source shouldBe "http"
                http.status = 200
                f.ref.refreshOutcome("remote", true).status shouldBe "ok"
                f.ref.status().ready shouldBe true
                val local = Files.createTempDirectory("declared-fallback")
                try {
                    Files.writeString(local.resolve("db.ttrm"), "model db\ndef table offline {}")
                    http.status = 503
                    val fallback =
                        DeclaredFixture(
                            listOf(
                                http.entry() + mapOf("fallback" to "offline"),
                                mapOf(
                                    "id" to "offline",
                                    "type" to "filesystem",
                                    "path" to local.toString(),
                                    "enabled" to false,
                                ),
                            ),
                        )
                    fallback.ref.refreshOutcome("", true).status shouldBe "ok"
                    fallback.names() shouldBe setOf("offline")
                    fallback.ref
                        .status()
                        .sources
                        .single()
                        .origin.source shouldBe "filesystem"
                    fallback.ref
                        .status()
                        .sources
                        .single()
                        .servingFallback shouldBe true
                    fallback.ref.status().ready shouldBe true
                } finally {
                    local.toFile().deleteRecursively()
                }
            }
        }
        "declared Git token authenticates reads and an explicit password takes precedence" {
            BranchFixture().use { git ->
                val update = ProcessBuilder("git", "update-server-info").directory(git.root.toFile()).start()
                check(update.waitFor() == 0)
                val seen = CopyOnWriteArrayList<String?>()
                var password = "token-secret"
                val executor = Executors.newCachedThreadPool()
                val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                server.executor = executor
                server.createContext("/repo.git/") { exchange ->
                    exchange.use {
                        val auth = it.requestHeaders.getFirst("Authorization")
                        seen += auth
                        val expected =
                            "Basic " +
                                java.util.Base64
                                    .getEncoder()
                                    .encodeToString("x-access-token:$password".toByteArray())
                        if (auth != expected) {
                            it.responseHeaders.add("WWW-Authenticate", "Basic realm=metadata")
                            it.sendResponseHeaders(401, -1)
                        } else {
                            val file = git.root.resolve(".git").resolve(it.requestURI.path.removePrefix("/repo.git/"))
                            if (Files.isRegularFile(file)) {
                                val bytes = Files.readAllBytes(file)
                                it.responseHeaders.add("Content-Type", "text/plain")
                                it.sendResponseHeaders(200, bytes.size.toLong())
                                it.responseBody.write(bytes)
                            } else {
                                it.sendResponseHeaders(404, -1)
                            }
                        }
                    }
                }
                server.start()
                try {
                    val entry =
                        mapOf(
                            "id" to "private",
                            "type" to "git",
                            "remote-uri" to "http://127.0.0.1:${server.address.port}/repo.git",
                            "branch" to "main",
                            "subdirectory" to "model",
                            "local-checkout-dir" to git.checkout.toString(),
                            "token" to password,
                        )
                    val f = DeclaredFixture(listOf(entry))
                    f.ref.refreshOutcome("", true).status shouldBe "ok"
                    f.names() shouldBe setOf("orders")
                    seen.any { it != null } shouldBe true
                    val secondCheckout = Files.createTempDirectory("declared-private")
                    try {
                        password = "explicit-password"
                        val explicit =
                            DeclaredFixture(
                                listOf(
                                    entry +
                                        mapOf(
                                            "password" to password,
                                            "local-checkout-dir" to secondCheckout.toString(),
                                        ),
                                ),
                            )
                        explicit.ref.refreshOutcome("", true).status shouldBe "ok"
                        explicit.names() shouldBe setOf("orders")
                    } finally {
                        secondCheckout.toFile().deleteRecursively()
                    }
                } finally {
                    server.stop(0)
                    executor.shutdownNow()
                }
            }
        }

        "HTTP credentials and validators stay isolated per declared source and diagnostics omit query secrets" {
            HttpTree().use { one ->
                HttpTree().use { two ->
                    two.body = tarTree("db.ttrm" to "model db\ndef table another {}")
                    two.etag = "\"v2\""
                    val f =
                        DeclaredFixture(
                            listOf(one.entry("one") + mapOf("url" to one.url + "?key=query-secret"), two.entry("two")),
                        )
                    f.ref.refreshOutcome("", true).status shouldBe "ok"
                    f.names() shouldBe setOf("delivered", "another")
                    f.ref.refreshOutcome("", true).status shouldBe "ok"
                    one.requests.map { it.auth }.toSet() shouldBe setOf("Bearer secret-one")
                    two.requests.map { it.auth }.toSet() shouldBe setOf("Bearer secret-two")
                    one.requests.last().etag shouldBe "\"v1\""
                    two.requests.last().etag shouldBe "\"v2\""
                    one.status = 401
                    one.body = "secret-one query-secret".toByteArray()
                    val failed = f.ref.refreshOutcome("one", true)
                    failed.status shouldBe "failed"
                    (failed.toString().contains("query-secret")) shouldBe false
                    (failed.toString().contains("secret-one")) shouldBe false
                    listOf(
                        one.entry() + mapOf("url" to "http://user:private@127.0.0.1/tree"),
                        one.entry() + mapOf("url" to "http://bad url/?private"),
                        one.entry() + mapOf("token" to "private\r\nHeader: bad"),
                        one.entry() + mapOf("connect-timeout-ms" to 0),
                        one.entry() + mapOf("max-bytes" to 0),
                    ).forEach { entry ->
                        val failure = shouldThrowAny { DeclaredFixture(listOf(entry)) }
                        (failure.message.orEmpty().contains("private")) shouldBe false
                    }
                }
            }
        }

        "HTTP rejects corrupt gzip trailers special entries excessive entries and both byte limits" {
            HttpTree().use { http ->
                http.conditional = false
                val f = DeclaredFixture(listOf(http.entry()))
                f.ref.refreshOutcome("", true).status shouldBe "ok"
                val accepted = f.registry.read()
                val valid = http.body
                val corrupt = valid.copyOf().also { it[it.size - 8] = (it[it.size - 8].toInt() xor 1).toByte() }
                listOf(
                    corrupt to "http_source_read_failed",
                    valid.copyOf(valid.size - 4) to "http_source_read_failed",
                    tarTree("hardlink" to "", link = true, linkType = '1'.code.toByte()) to "http_source_unsafe_entry",
                    tarTree("fifo" to "", link = true, linkType = '6'.code.toByte()) to "http_source_unsafe_entry",
                    tarTree(*(0..10_000).map { "entry-$it" to "" }.toTypedArray()) to "http_source_too_many_entries",
                ).forEach { (body, code) ->
                    http.body = body
                    val refused = f.ref.refreshOutcome("remote", true)
                    refused.status shouldBe "failed"
                    refused.lastAttempt!!.errors.joinToString() shouldContain code
                    (f.registry.read() === accepted) shouldBe true
                }
                http.body = ByteArray(2048) { (it % 127).toByte() }
                val small = DeclaredFixture(listOf(http.entry() + mapOf("max-bytes" to 1024)))
                small.ref
                    .refreshOutcome("", true)
                    .lastAttempt!!
                    .errors
                    .joinToString() shouldContain
                    "http_source_response_too_large"
                http.body = tarTree("db.ttrm" to "x".repeat(10_000))
                small.ref
                    .refreshOutcome("", true)
                    .lastAttempt!!
                    .errors
                    .joinToString() shouldContain
                    "http_source_expanded_too_large"
            }
        }
    })

private class DeclaredFixture(
    entries: List<Map<String, Any>>,
    strict: Boolean = true,
) {
    val config = ConfigFactory.parseMap(mapOf("metadata.sources" to entries))
    val registry = MetadataRegistry()
    val board = SourceLoadBoard()
    val index = PackageIndex().commitOnSwap(registry)
    val slots = buildSources(config, board)
    val ref =
        GuardedRefresher(
            slots.map {
                it.source
            },
            slots.map {
                it.id
            },
            ModelReconciler(ModelDescriptor("declared", "declared")),
            registry,
            strict,
            board
                .states()
                .map {
                    it.id
                }.toSet(),
            board,
            index,
        )

    fun names() =
        registry
            .readOrError()
            .model
            .objectByQname()
            .values
            .filter {
                it.kind == "table"
            }.map { it.qname.name }
            .toSet()
}

private class HttpTree : AutoCloseable {
    @Volatile var body = tarTree("db.ttrm" to "model db\ndef table delivered {}")

    @Volatile var etag: String? = "\"v1\""

    @Volatile var status = 200

    @Volatile var conditional = true

    @Volatile var location: String? = null

    @Volatile var delayMillis = 0L

    @Volatile var dripMillis = 0L

    data class Request(
        val auth: String?,
        val etag: String?,
    )

    val requests = CopyOnWriteArrayList<Request>()
    private val executor = Executors.newCachedThreadPool()
    private val server =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@HttpTree.executor
            createContext("/tree") { exchange ->
                exchange.use {
                    val request =
                        Request(
                            it.requestHeaders.getFirst("Authorization"),
                            it.requestHeaders.getFirst("If-None-Match"),
                        )
                    requests += request
                    if (delayMillis > 0) Thread.sleep(delayMillis)
                    val code = if (status == 200 && conditional && etag != null && request.etag == etag) 304 else status
                    etag?.let { value -> it.responseHeaders.add("ETag", value) }
                    location?.let { value -> it.responseHeaders.add("Location", value) }
                    it.sendResponseHeaders(code, if (code == 304) -1 else body.size.toLong())
                    if (code != 304) {
                        runCatching {
                            val content = body
                            if (dripMillis == 0L) {
                                it.responseBody.write(content)
                            } else {
                                content.forEach { byte ->
                                    it.responseBody.write(byte.toInt())
                                    it.responseBody.flush()
                                    Thread.sleep(dripMillis)
                                }
                            }
                        }
                    }
                }
            }
            start()
        }
    val url get() = "http://127.0.0.1:${server.address.port}/tree"

    fun entry(id: String = "remote"): Map<String, Any> =
        mapOf(
            "id" to id,
            "type" to "http",
            "url" to url,
            "token" to "secret-$id",
        )

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }
}

private fun tarTree(
    vararg files: Pair<String, String>,
    link: Boolean = false,
    linkType: Byte = '2'.code.toByte(),
    recordSize: Int = 10_240,
    trailingBytes: Int = 0,
): ByteArray =
    ByteArrayOutputStream()
        .also { bytes ->
            GZIPOutputStream(bytes).use { gzip ->
                TarArchiveOutputStream(gzip, recordSize).use { tar ->
                    files.forEach { (name, text) ->
                        val content = text.toByteArray()
                        val entry =
                            if (link) {
                                TarArchiveEntry(name, linkType).apply { linkName = "../../outside" }
                            } else {
                                TarArchiveEntry(name, true).apply {
                                    size =
                                        content.size.toLong()
                                }
                            }
                        tar.putArchiveEntry(entry)
                        if (!link) tar.write(content)
                        tar.closeArchiveEntry()
                    }
                    if (trailingBytes > 0) {
                        tar.finish()
                        // Valid post-EOF data leaves the gzip trailer beyond the tar reader's lookahead.
                        gzip.write(ByteArray(trailingBytes))
                    }
                }
            }
        }.toByteArray()
