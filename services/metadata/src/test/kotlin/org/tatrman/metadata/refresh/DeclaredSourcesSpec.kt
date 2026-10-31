// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.refresh

import com.sun.net.httpserver.HttpServer
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import io.kotest.assertions.throwables.shouldThrowAny
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

class DeclaredSourcesSpec : StringSpec({
    "explicit declarations replace defaults and legacy env-backed slots remain compatible" {
        val root = Files.createTempDirectory("declared-local")
        try {
            Files.writeString(root.resolve("db.ttrm"), "model db\ndef table local {}")
            val cfg = ConfigFactory.parseString("""metadata.sources = [{id=local, type=filesystem, path="$root"}]""")
                .withFallback(ConfigFactory.parseResources("application.conf")).resolve()
            val board = SourceLoadBoard()
            buildSources(cfg, board).map { it.id } shouldBe listOf("builtin-stock", "local")
            board.states().map { it.id } shouldBe listOf("local")
            val shipped = ConfigFactory.parseResources("application.conf")
            val resolved = shipped.resolve(ConfigResolveOptions.defaults().setUseSystemEnvironment(false))
            resolved.getString("metadata.sources.github-model.remote-uri") shouldContain "ttr-demo"
            resolved.getString("metadata.sources.github-model.branch") shouldBe "master"
            val raw = shipped.root().render()
            listOf("METADATA_GIT_REMOTE_URI", "METADATA_GIT2_REMOTE_URI", "METADATA_GIT_TOKEN", "METADATA_GIT2_TOKEN", "METADATA_TTR_ROOT").forEach {
                raw shouldContain it
            }
            for (entries in listOf(
                "[{id=dup,type=filesystem,path=\"$root\"},{id=dup,type=filesystem,path=\"$root\"}]",
                "[{id=\"../bad\",type=filesystem,path=\"$root\"}]",
                "[{type=filesystem,path=\"$root\"}]",
                "[{id=cn-vocabularies,type=filesystem,path=\"$root\"}]",
                "[{id=builtin-stock,type=filesystem,path=\"$root\"}]",
                "[{id=bad,type=filesystem,path=\"$root\",subdirectory=\"../outside\"}]",
            )) {
                shouldThrowAny { buildSources(ConfigFactory.parseString("metadata.sources=$entries")) }
            }
            shouldThrowAny {
                buildSources(ConfigFactory.parseString("""metadata.sources {a {id=dup,type=filesystem,path="$root"}, b {id=dup,type=filesystem,path="$root"}}"""))
            }
        } finally { root.toFile().deleteRecursively() }
    }

    "git filesystem and HTTP reconcile into one captured model with matching transport provenance" {
        BranchFixture().use { git ->
            HttpTree().use { http ->
                val local = Files.createTempDirectory("declared-fs")
                try {
                    Files.createDirectories(local.resolve("content"))
                    Files.writeString(local.resolve("content/db.ttrm"), "model db\ndef table mounted {}")
                    val entries = listOf(
                        mapOf("id" to "repo", "type" to "git", "remote-uri" to git.root.toUri().toString(), "branch" to "main", "subdirectory" to "model", "local-checkout-dir" to git.checkout.toString(), "token" to "private-git-token"),
                        mapOf("id" to "disk", "type" to "filesystem", "path" to local.toString(), "subdirectory" to "content"),
                        http.entry("remote") + mapOf("subdirectory" to "tree"),
                    )
                    http.body = tarTree("tree/remote/db.ttrm" to "model db\ndef table delivered {}")
                    val f = DeclaredFixture(entries)
                    f.ref.refreshOutcome("", true).status shouldBe "ok"
                    f.names() shouldBe setOf("orders", "mounted", "delivered")
                    val result = f.ref.status()
                    result.serving!!.source shouldBe "mixed"
                    result.sources.associate { it.id to it.origin.source } shouldBe mapOf("repo" to "git", "disk" to "filesystem", "remote" to "http")
                    result.sources.single { it.id == "repo" }.origin.commit shouldBe git.main
                    result.sources.filter { it.id != "repo" }.all { it.origin.commit.isEmpty() && it.origin.branch.isEmpty() } shouldBe true
                    val rpc = MetadataServiceImpl(f.registry, packageIndex = f.index, loadBoard = f.board, refresher = f.ref).getStatus(GetStatusRequest.getDefaultInstance())
                    rpc.sourcesList.associate { it.id to it.source } shouldBe mapOf("repo" to "git", "disk" to "filesystem", "remote" to "http")
                    f.ref.refreshOutcome("remote", true, "main").httpStatus shouldBe 400
                    promotionTarget(f.config, "repo")!!.branch shouldBe "main"
                    http.requests.all { it.auth == "Bearer secret-remote" } shouldBe true
                    testApplication {
                        environment { config = MapApplicationConfig() }
                        application { module(startupConfig(mapOf("metadata.sources" to entries))) }
                        client.post("/refresh").status shouldBe HttpStatusCode.OK
                        client.get("/ready").status shouldBe HttpStatusCode.OK
                        val status = Json.parseToJsonElement(client.get("/status").bodyAsText()).jsonObject
                        status["serving"]!!.jsonObject["source"]!!.jsonPrimitive.content shouldBe "mixed"
                        status["sources"]!!.jsonArray.associate {
                            it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["source_type"]!!.jsonPrimitive.content
                        } shouldBe mapOf("repo" to "git", "disk" to "filesystem", "remote" to "http")
                        client.get("/model/graph").bodyAsText() shouldContain "remote.db.dbo.delivered"
                    }
                } finally { local.toFile().deleteRecursively() }
            }
        }
    }

    "ETag reuse revalidates refused trees and body versions are independent of validators" {
        HttpTree().use { http ->
            val f = DeclaredFixture(listOf(http.entry()))
            f.ref.refreshOutcome("", true).status shouldBe "ok"
            val first = f.ref.status().sources.single().version
            val snapshot = f.registry.read()
            f.ref.refreshOutcome("remote", true).status shouldBe "ok"
            http.requests.last().etag shouldBe "\"v1\""
            (f.registry.read() === snapshot) shouldBe true
            http.body = tarTree("db.ttrm" to "model db\ndef table newer {}")
            http.conditional = false // A valid 200 with changed bytes must advance even with the same ETag.
            f.ref.refreshOutcome("remote", true).status shouldBe "ok"
            (f.ref.status().sources.single().version == first) shouldBe false
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
                val f = DeclaredFixture(listOf(http.entry(), mapOf("id" to "disk", "type" to "filesystem", "path" to local.toString())))
                f.ref.refreshOutcome("", true).status shouldBe "ok"
                val remoteVersion = f.ref.status().sources.single { it.id == "remote" }.version
                http.status = 503
                Files.writeString(model, "model db\ndef table local_after {}")
                // Force a distinct filesystem version without relying on the host clock's resolution.
                Files.setLastModifiedTime(model, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 10000))
                f.ref.refreshOutcome("", true).status shouldBe "partial"
                f.names() shouldBe setOf("delivered", "local_after")
                http.status = 200
                http.conditional = false
                val accepted = f.registry.read()
                for (unsafe in listOf(
                    tarTree("../escape.ttrm" to "bad"), tarTree("/absolute.ttrm" to "bad"),
                    tarTree("a\\b.ttrm" to "bad"), tarTree("db.ttrm" to "one", "db.ttrm" to "two"),
                    tarTree("link" to "ignored", link = true), "not gzip".toByteArray(),
                )) {
                    http.body = unsafe
                    f.ref.refreshOutcome("remote", true).status shouldBe "failed"
                    (f.registry.read() === accepted) shouldBe true
                }
                f.ref.status().sources.single { it.id == "remote" }.version shouldBe remoteVersion
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
                (f.ref.status().toString().contains("secret-remote")) shouldBe false
                http.status = 200
                http.delayMillis = 150
                val timeout = DeclaredFixture(listOf(http.entry() + mapOf("read-timeout-ms" to 25)))
                timeout.ref.refreshOutcome("", true).status shouldBe "failed"
            } finally { local.toFile().deleteRecursively() }
        }
    }

    "initial HTTP failure recovers and disabled list fallback retains existing readiness semantics" {
        HttpTree().use { http ->
            http.status = 503
            val f = DeclaredFixture(listOf(http.entry()))
            f.ref.refreshOutcome("", true).serving shouldBe null
            f.ref.status().ready shouldBe false
            f.ref.status().sources.single().origin.source shouldBe "http"
            http.status = 200
            f.ref.refreshOutcome("remote", true).status shouldBe "ok"
            f.ref.status().ready shouldBe true
            val local = Files.createTempDirectory("declared-fallback")
            try {
                Files.writeString(local.resolve("db.ttrm"), "model db\ndef table offline {}")
                http.status = 503
                val fallback = DeclaredFixture(listOf(http.entry() + mapOf("fallback" to "offline"), mapOf("id" to "offline", "type" to "filesystem", "path" to local.toString(), "enabled" to false)))
                fallback.ref.refreshOutcome("", true).status shouldBe "ok"
                fallback.names() shouldBe setOf("offline")
                fallback.ref.status().sources.single().origin.source shouldBe "filesystem"
                fallback.ref.status().sources.single().servingFallback shouldBe true
                fallback.ref.status().ready shouldBe true
            } finally { local.toFile().deleteRecursively() }
        }
    }
})

private class DeclaredFixture(entries: List<Map<String, Any>>) {
    val config = ConfigFactory.parseMap(mapOf("metadata.sources" to entries))
    val registry = MetadataRegistry()
    val board = SourceLoadBoard()
    val index = PackageIndex().commitOnSwap(registry)
    val slots = buildSources(config, board)
    val ref = GuardedRefresher(slots.map { it.source }, slots.map { it.id }, ModelReconciler(ModelDescriptor("declared", "declared")), registry, true, board.states().map { it.id }.toSet(), board, index)
    fun names() = registry.readOrError().model.objectByQname().values.filter { it.kind == "table" }.map { it.qname.name }.toSet()
}

private class HttpTree : AutoCloseable {
    @Volatile var body = tarTree("db.ttrm" to "model db\ndef table delivered {}")
    @Volatile var etag: String? = "\"v1\""
    @Volatile var status = 200
    @Volatile var conditional = true
    @Volatile var location: String? = null
    @Volatile var delayMillis = 0L
    data class Request(val auth: String?, val etag: String?)
    val requests = CopyOnWriteArrayList<Request>()
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        this.executor = this@HttpTree.executor
        createContext("/tree") { exchange ->
            exchange.use {
                val request = Request(it.requestHeaders.getFirst("Authorization"), it.requestHeaders.getFirst("If-None-Match"))
                requests += request
                if (delayMillis > 0) Thread.sleep(delayMillis)
                val code = if (status == 200 && conditional && etag != null && request.etag == etag) 304 else status
                etag?.let { value -> it.responseHeaders.add("ETag", value) }
                location?.let { value -> it.responseHeaders.add("Location", value) }
                it.sendResponseHeaders(code, if (code == 304) -1 else body.size.toLong())
                if (code != 304) runCatching { it.responseBody.write(body) }
            }
        }
        start()
    }
    val url get() = "http://127.0.0.1:${server.address.port}/tree"
    fun entry(id: String = "remote"): Map<String, Any> = mapOf("id" to id, "type" to "http", "url" to url, "token" to "secret-$id")
    override fun close() { server.stop(0); executor.shutdownNow() }
}

private fun tarTree(vararg files: Pair<String, String>, link: Boolean = false): ByteArray = ByteArrayOutputStream().also { bytes ->
    GZIPOutputStream(bytes).use { gzip ->
        TarArchiveOutputStream(gzip).use { tar ->
            files.forEach { (name, text) ->
                val content = text.toByteArray()
                val entry = if (link) TarArchiveEntry(name, '2'.code.toByte()).apply { linkName = "../../outside" } else TarArchiveEntry(name, true).apply { size = content.size.toLong() }
                tar.putArchiveEntry(entry)
                if (!link) tar.write(content)
                tar.closeArchiveEntry()
            }
        }
    }
}.toByteArray()
