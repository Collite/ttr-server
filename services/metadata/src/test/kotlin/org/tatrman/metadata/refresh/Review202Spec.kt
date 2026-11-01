// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.refresh

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.tatrman.meta.v1.RefreshRequest
import org.tatrman.metadata.buildSources
import org.tatrman.metadata.cn.*
import org.tatrman.metadata.grpc.PackageIndex
import org.tatrman.metadata.grpc.MetadataServiceImpl
import org.tatrman.metadata.health.SourceLoadBoard
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.ModelSource
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class Review202Spec :
    StringSpec({
        "S1 configured keyed ids bypass unknown-id grammar for HTTP RPC and startup retry" {
            val root = Files.createTempDirectory("r202-keyed")
            try {
                for (id in listOf("disk model", "df:prod", "a".repeat(130))) {
                    val f =
                        R202Fixture(
                            ConfigFactory.parseMap(
                                mapOf(
                                    "metadata.sources.disk" to
                                        mapOf(
                                            "id" to id,
                                            "type" to "filesystem",
                                            "path" to root.resolve("model").toString(),
                                        ),
                                ),
                            ),
                        )
                    if (id == "disk model") {
                        f.ref.refreshOutcome("", true).status shouldBe "failed"
                        f.ref.status().ready shouldBe false
                        Files.createDirectories(root.resolve("model"))
                        Files.writeString(root.resolve("model/db.ttrm"), "model db\ndef table orders {}")
                        // retryUntilLoaded's targeted call must recover a failed initial load.
                        f.ref
                            .refresh(id, true)
                            .single { it.sourceId == id }
                            .success shouldBe true
                        f.ref.status().ready shouldBe true
                    }
                    testApplication {
                        application {
                            install(ContentNegotiation) { json(Json { prettyPrint = true }) }
                            routing { metadataRefreshRoutes(f.ref) }
                        }
                        val response = client.post("/refresh") { url { parameters.append("source_id", id) } }
                        response.status.value shouldBe 200
                        response.bodyAsText() shouldContain "\"status\": \"ok\""
                        // S7: preserve the application's negotiated charset and pretty JSON.
                        response.headers["Content-Type"]!! shouldContain "charset=UTF-8"
                        response.bodyAsText() shouldContain "\n    \""
                    }
                    val rpc =
                        MetadataServiceImpl(f.registry, packageIndex = f.index, loadBoard = f.board, refresher = f.ref)
                            .refresh(
                                RefreshRequest
                                    .newBuilder()
                                    .setSourceId(id)
                                    .setForce(true)
                                    .build(),
                            )
                    rpc.resultsList.single { it.sourceId == id }.success shouldBe true
                    f.ref
                        .status()
                        .lastAttempt!!
                        .sourceId shouldBe id
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

        "S5 internal source replies failed when strict content permits no accepted snapshot and keeps reports" {
            val root = Files.createTempDirectory("r202-empty")
            try {
                val f = R202Fixture(diskConfig(root.toString()), VocabularySource(InMemoryCandidateStore()))
                f.ref.refresh("", true)
                val before = f.ref.status()
                before.snapshot shouldBe null
                val outcome = f.ref.refreshOutcome(VocabularySource.SOURCE_ID, true)
                outcome.status shouldBe "failed"
                outcome.snapshot shouldBe null
                outcome.lastAttempt shouldBe before.lastAttempt
                outcome.lastSuccess shouldBe before.lastSuccess
                outcome.keptPrevious shouldBe before.keptPrevious
            } finally {
                root.toFile().deleteRecursively()
            }
        }

        "S6 invalid refs are not echoed and refusal precedence and valid refs remain unchanged" {
            for (gate in listOf(false, true)) {
                BranchFixture(allow = gate).use { f ->
                    val bad = f.ref.refreshOutcome("world", true, "a..b")
                    bad.httpStatus shouldBe if (gate) 400 else 409
                    bad.lastAttempt!!.branch shouldBe ""
                    bad.lastAttempt!!.errors.single() shouldContain
                        if (gate) "invalid_git_ref" else "branch_not_allowed"
                    (
                        bad.lastAttempt!!
                            .errors
                            .single()
                            .contains("a..b")
                    ) shouldBe false
                    f.ref
                        .status()
                        .lastAttempt!!
                        .branch shouldBe ""
                    val long = f.ref.refreshOutcome("world", true, "x".repeat(256))
                    long.lastAttempt!!.branch shouldBe ""
                    if (!gate) {
                        val valid = f.ref.refreshOutcome("world", true, "feature/x")
                        valid.httpStatus shouldBe 409
                        valid.lastAttempt!!.branch shouldBe "feature/x"
                    }
                }
            }
        }

        for (failure in listOf("success", "exception", "error")) {
            "S4 production CN listener acknowledges $failure and retries only failed observations" {
                val clinic = CnTestKit.clinicWorld()
                val errors = CopyOnWriteArrayList<Throwable>()
                val job = SupervisorJob()
                val scope = CoroutineScope(job + CoroutineExceptionHandler { _, error -> errors += error })
                try {
                    val f = R202Fixture(diskConfig(clinic.toString()), strict = false)
                    val calls = AtomicInteger()
                    cnPromotionListener(f.ref, "disk", scope) { schema, version ->
                        schema.concepts.isNotEmpty() shouldBe true
                        version.isNotEmpty() shouldBe true
                        if (calls.incrementAndGet() == 1) {
                            when (failure) {
                                "exception" -> error("database unavailable")
                                "error" -> throw NotImplementedError("JDBC linkage error")
                            }
                        }
                    }
                    f.ref.refresh("", true)
                    job.children.toList().joinAll()
                    calls.get() shouldBe 1
                    repeat(2) {
                        f.ref.refresh("", true)
                        job.children.toList().joinAll()
                    }
                    calls.get() shouldBe if (failure == "success") 1 else 2
                    errors.size shouldBe if (failure == "error") 1 else 0
                    if (failure == "error") (errors.single() is NotImplementedError) shouldBe true
                } finally {
                    job.cancelAndJoin()
                    clinic.toFile().deleteRecursively()
                }
            }
        }
    })

private fun diskConfig(path: String): Config =
    ConfigFactory.parseMap(
        mapOf(
            "metadata.sources.disk" to mapOf("type" to "filesystem", "path" to path),
        ),
    )

private class R202Fixture(
    config: Config,
    extra: ModelSource? = null,
    strict: Boolean = true,
) {
    val registry = MetadataRegistry()
    val board = SourceLoadBoard(strictSwap = strict)
    val index = PackageIndex().commitOnSwap(registry)
    private val slots = buildSources(config, board)
    val ref =
        GuardedRefresher(
            slots.map { it.source } + listOfNotNull(extra),
            slots.map { it.id } + (if (extra != null) listOf(VocabularySource.SOURCE_ID) else emptyList()),
            ModelReconciler(ModelDescriptor("review202", "review202")),
            registry,
            strict,
            board.states().map { it.id }.toSet(),
            board,
            index,
        )
}
