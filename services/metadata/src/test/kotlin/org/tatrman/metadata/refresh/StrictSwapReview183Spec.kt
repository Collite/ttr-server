// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.refresh

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.tatrman.meta.v1.GetStatusRequest
import org.tatrman.metadata.grpc.MetadataServiceImpl
import org.tatrman.metadata.health.SourceLoadBoard
import org.tatrman.metadata.read.GitTemplateSource
import org.tatrman.metadata.read.TEMPLATE_SIDECAR_PREFIX
import org.tatrman.metadata.read.TemplateFixture
import org.tatrman.metadata.read.templateCatalogs
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.BuiltinStockSource
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LoadWarning
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.metadata.source.ModelSource
import org.tatrman.ttr.metadata.source.SourceSnapshot
import java.nio.file.Files

/** review-183: the library strict verdict, per-source isolation and publication boundaries. */
class StrictSwapReview183Spec :
    StringSpec({
        "Flexible package layout accepts Hartland-shaped declarations at the strict default" {
            val candidate =
                content(
                    "world",
                    mapOf(
                        "er/sales.ttrm" to "package x\nmodel er\ndef entity sale {}",
                        "db/sales.ttrm" to "package x\nmodel db\ndef table sales {}",
                    ),
                )
            candidate.errors.any { it.message.contains("package-declaration-mismatch") } shouldBe true
            val f = ReviewCoordinator(mapOf("world" to ModelSource { candidate }))
            f.refresh().all { it.success } shouldBe true
            f.board.isReady(f.registry.readOrError().swappedAt) shouldBe true
            f.board
                .states()
                .single()
                .consecutiveFailures shouldBe 0
            f.registry
                .readOrError()
                .warnings
                .any { it.message.contains("package-declaration-mismatch") } shouldBe true
        }

        "strict ERROR categories and md-hop errors remain blocking" {
            val base = table("world", "orders", "v1")
            val path =
                base.loadedFiles
                    .single()
                    .storageFile.path
            for (message in listOf(
                "ttr/parse-error",
                "ttr/unresolved-reference",
                "protected qname",
                "ttr/binding-inconsistency",
            )) {
                val candidate = base.copy(errors = listOf(LoadWarning("world", path, 1, 1, message)))
                val f = ReviewCoordinator(mapOf("world" to ModelSource { candidate }))
                f.refresh().single { it.sourceId == "world" }.errorMessage shouldContain message
                f.registry.read() shouldBe null
            }
            val candidate =
                base.copy(
                    mdHopIssues =
                        listOf(
                            org.tatrman.ttr.metadata.LoadIssue(
                                org.tatrman.ttr.metadata.LoadIssue.Category.BINDING_INCONSISTENCY,
                                org.tatrman.ttr.metadata.LoadIssue.Severity.ERROR,
                                path,
                                "md-hop-blocker",
                            ),
                        ),
                )
            val f = ReviewCoordinator(mapOf("world" to ModelSource { candidate }))
            f.refresh().single { it.sourceId == "world" }.errorMessage shouldContain "md-hop-blocker"
            f.registry.read() shouldBe null
        }

        "one culprit is refused while an independent change advances" {
            val a = table("a", "orders", "a1")
            var b = table("b", "products", "b1")
            var c = table("c", "customers", "c1")
            val f =
                ReviewCoordinator(mapOf("a" to ModelSource { a }, "b" to ModelSource { b }, "c" to ModelSource { c }))
            f.refresh()
            b = table("b", "orders", "b2")
            c = table("c", "customers_new", "c2")
            repeat(3) { f.refresh() }
            f.tables() shouldBe setOf("orders", "products", "customers_new")
            f.board.states().associate { it.id to it.version } shouldBe mapOf("a" to "a1", "b" to "b1", "c" to "c2")
            f.board
                .states()
                .single { it.id == "b" }
                .consecutiveFailures shouldBe 3
            f.board
                .states()
                .filter { it.id != "b" }
                .all { it.consecutiveFailures == 0 } shouldBe true
            b = table("b", "products_fixed", "b2") // rejected SHA is not cached
            f.refresh().single { it.sourceId == "b" }.snapshotSwapped shouldBe true
            f.tables() shouldBe setOf("orders", "products_fixed", "customers_new")
        }

        "library duplicate-qname verdict rejects same-priority duplicates missing from typed carriers" {
            val one = content("one", mapOf("md.ttrm" to "model md\ndef dimension Buyer {}"))
            val two = content("two", mapOf("md.ttrm" to "model md\ndef dimension Buyer {}"))
            val f = ReviewCoordinator(mapOf("one" to ModelSource { one }, "two" to ModelSource { two }))
            val results = f.refresh()
            results.filter { it.sourceId != BuiltinStockSource.SOURCE_ID }.any {
                !it.success &&
                    it.errorMessage.contains("duplicate-qname")
            } shouldBe
                true
            f.registry.read() shouldBe null
        }

        "unattributable error refuses the transaction without charging unchanged or internal sources" {
            val a = table("a", "orders", "a1")
            var b = table("b", "products", "b1")
            val f = ReviewCoordinator(mapOf("a" to ModelSource { a }, "b" to ModelSource { b }))
            f.refresh()
            val prior = f.registry.read()
            b =
                b.copy(
                    version = "b2",
                    errors = listOf(LoadWarning("unknown", "<reconcile>", 0, 0, "unattributable failure")),
                )
            val results = f.refresh()
            results.single { it.sourceId == "b" }.errorMessage shouldContain "reconcile_rejected"
            results.filter { it.sourceId != "b" }.all { it.success && !it.snapshotSwapped } shouldBe true
            f.board
                .states()
                .single { it.id == "a" }
                .consecutiveFailures shouldBe 0
            (f.registry.read() === prior) shouldBe true
        }

        "configured startup rejection leaves model_loaded false until stock and repaired source publish together" {
            var candidate =
                table(
                    "world",
                    "orders",
                    "v1",
                ).copy(droppedFiles = listOf(LoadWarning("world", "broken.ttrm", 1, 1, "broken")))
            val f = ReviewCoordinator(mapOf("world" to ModelSource { candidate }))
            f.refresh().single { it.sourceId == "world" }.errorMessage shouldContain "dropped-file broken.ttrm"
            f.registry.read() shouldBe null
            MetadataServiceImpl(
                f.registry,
                packageIndex =
                    org.tatrman.metadata.grpc
                        .PackageIndex(),
                loadBoard = f.board,
            ).getStatus(GetStatusRequest.getDefaultInstance()).modelLoaded shouldBe
                false
            candidate = table("world", "orders", "v1")
            f.refresher.refresh("world", true).all { it.success && it.snapshotSwapped } shouldBe true
            f.registry
                .readOrError()
                .model
                .objectByQname()
                .values
                .count { it.kind == "role" } shouldBe 6
            f.tables() shouldBe setOf("orders")
        }

        "no configured sources retains the stock-only publication" {
            val f = ReviewCoordinator(emptyMap())
            f.refresh().single().snapshotSwapped shouldBe true
            f.registry
                .readOrError()
                .graph
                .size() shouldBe 6
            f.board.isReady(f.registry.readOrError().swappedAt) shouldBe false
        }

        "a rejected template catalog cannot become a later failed-read fallback, including through FallbackSource" {
            TemplateFixture().use { fixture ->
                val world = fixture.slots.single { it.id == "world" }.source
                var current = world.load().copy(exportSidecars = emptyMap())
                val registry = MetadataRegistry()
                val templates =
                    GitTemplateSource(ModelSource { current }, fixture.root, "world/model", "world", 100) {
                        registry.read()?.exportSidecars?.get(TEMPLATE_SIDECAR_PREFIX + "world")
                    }
                val wrapped =
                    org.tatrman.ttr.metadata.source.FallbackSource(
                        "world",
                        templates,
                        ModelSource {
                            error("unexpected fallback")
                        },
                    )
                val f = ReviewCoordinator(mapOf("world" to wrapped), registry)
                f.refresh()
                val original = registry.readOrError().templateCatalogs().single()
                fixture.write("bad.ttrm", "model db\ndef table broken {")
                fixture.write("reports/sales/report.yaml", "title: {en: REJECTED_TITLE}")
                fixture.commit()
                current = world.load().copy(exportSidecars = emptyMap())
                f.refresh().single { it.sourceId == "world" }.success shouldBe false
                Files.delete(fixture.root.resolve("world/model/bad.ttrm"))
                fixture.write("extra.ttrm", "model er\ndef entity extra {}")
                fixture.commit()
                current = world.load().copy(exportSidecars = emptyMap())
                val hidden = fixture.root.resolve("hidden-git")
                Files.move(fixture.root.resolve(".git"), hidden)
                try {
                    f.refresh().single { it.sourceId == "world" }.success shouldBe true
                    val served = registry.readOrError().templateCatalogs().single()
                    served.version shouldBe original.version
                    served.reports shouldBe original.reports
                    served.issues.single() shouldContain "serving templates of ${original.version.take(8)}"
                    served.toString() shouldNotContain "REJECTED_TITLE"
                } finally {
                    Files.move(hidden, fixture.root.resolve(".git"))
                }
            }
        }

        "non-Exception errors and active-source cancellations are contained and retryable" {
            for (error in listOf(
                NotImplementedError("not implemented"),
                CancellationException("source cancelled"),
                NullPointerException(),
            )) {
                var fail = true
                val f =
                    ReviewCoordinator(
                        mapOf(
                            "world" to ModelSource { if (fail) throw error else table("world", "orders", "v1") },
                        ),
                    )
                f.refresh().single { it.sourceId == "world" }.success shouldBe false
                f.board
                    .states()
                    .single()
                    .lastError shouldContain error.javaClass.simpleName
                f.board
                    .states()
                    .single()
                    .issues
                    .single()
                    .isNotBlank() shouldBe true
                fail = false
                f.refresher
                    .refresh("world", true)
                    .single { it.sourceId == "world" }
                    .snapshotSwapped shouldBe true
            }
        }

        "a cancelled caller still cancels and fatal VM errors are rethrown" {
            val job = Job()
            val f =
                ReviewCoordinator(
                    mapOf(
                        "world" to
                            ModelSource {
                                job.cancel()
                                throw CancellationException("caller cancelled")
                            },
                    ),
                )
            shouldThrow<CancellationException> { withContext(job) { f.refresh() } }
            f.board
                .states()
                .single()
                .lastAttemptAt shouldBe null
            val fatal = ReviewCoordinator(mapOf("world" to ModelSource { throw InternalError("fatal VM") }))
            shouldThrow<InternalError> { fatal.refresh() }
        }

        "lexicon-only and project-only sources are empty, while directive-less md and cn concepts count" {
            val variants =
                listOf(
                    "model lexicon locale en\ndef term word { forms: [\"word\"] }" to false,
                    "def project Example {}" to false,
                    "def dimension Buyer {}" to true,
                    "model cn\ndef kind Buyer {}" to true,
                )
            for ((text, expected) in variants) {
                val candidate = content("world", mapOf("content.ttrm" to text))
                candidate.droppedFiles shouldBe emptyList()
                candidate.loadedFiles.flatMap { it.definitions }.isNotEmpty() shouldBe true
                val f = ReviewCoordinator(mapOf("world" to ModelSource { candidate }))
                f.refresh().single { it.sourceId == "world" }.success shouldBe expected
                f.board.isReady(f.registry.read()?.swappedAt) shouldBe expected
                if (!expected) {
                    f.registry.read() shouldBe null
                    f.board
                        .states()
                        .single()
                        .lastError shouldContain "no model objects"
                }
            }
        }

        "persistent refusal logs details once, repeats briefly, and resets after acceptance" {
            val logger = LoggerFactory.getLogger(GuardedRefresher::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            try {
                val good = table("world", "orders", "v1")
                val bad =
                    good.copy(
                        version = "v2",
                        droppedFiles = listOf(LoadWarning("world", "UNIQUE_BAD.ttrm", 1, 1, "broken")),
                    )
                var current = good
                val f = ReviewCoordinator(mapOf("world" to ModelSource { current }))
                f.refresh()
                current = bad
                repeat(3) { f.refresh() }
                current = good
                f.refresh()
                current = bad
                f.refresh()
                val lines = appender.list.map { it.formattedMessage }
                lines.count { it.contains("rejected version 'v2'") && it.contains("UNIQUE_BAD") } shouldBe 2
                lines.count { it.contains("still refusing 'v2'") } shouldBe 2
                f.board
                    .states()
                    .single()
                    .lastError shouldContain "UNIQUE_BAD"
            } finally {
                logger.detachAppender(appender)
                appender.stop()
            }
        }
    })

private class ReviewCoordinator(
    sources: Map<String, ModelSource>,
    val registry: MetadataRegistry = MetadataRegistry(),
) {
    val board = SourceLoadBoard().apply { sources.keys.forEach { register(it, it) } }
    val refresher =
        GuardedRefresher(
            listOf(BuiltinStockSource()) + sources.values,
            listOf(BuiltinStockSource.SOURCE_ID) + sources.keys,
            ModelReconciler(ModelDescriptor("review", "review")),
            registry,
            true,
            sources.keys,
            board,
        )

    suspend fun refresh() = refresher.refresh("", true)

    fun tables() =
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

private fun table(
    id: String,
    name: String,
    version: String,
) = content(
    id,
    mapOf(
        "db.ttrm" to "model db\ndef table $name {}",
    ),
).copy(version = version)

private fun content(
    id: String,
    files: Map<String, String>,
): SourceSnapshot {
    val root = Files.createTempDirectory("strict-review183")
    return try {
        files.forEach { (path, text) ->
            val target = root.resolve(path)
            Files.createDirectories(target.parent)
            Files.writeString(target, text)
        }
        FileBasedSource(id, 100, LocalFsStorage(id, root)).load()
    } finally {
        root.toFile().deleteRecursively()
    }
}
