// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.read

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.metadata.health.SourceLoadBoard
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.metadata.refresh.GuardedRefresher
import org.tatrman.ttr.metadata.source.FallbackSource
import org.tatrman.ttr.metadata.source.ModelSource
import org.tatrman.ttr.metadata.source.SourceSnapshot
import java.nio.file.Files
import java.nio.file.Path

class GitTemplateSourceReviewSpec :
    StringSpec({
        "R1 an initial template read failure keeps the model ready and reports the source and attempted SHA" {
            TemplateFixture().use { fixture ->
                val model = fixture.modelSnapshot()
                val source =
                    GitTemplateSource(
                        ModelSource { model },
                        fixture.checkout.resolveSibling("missing-repository"),
                        "world/model",
                        "world",
                        100,
                    )
                val board = SourceLoadBoard()
                val loaded = board.recording("world", "test source", source).load()
                loaded.version shouldBe model.version
                loaded.entities shouldBe model.entities
                val catalog = loaded.catalog()
                catalog.reports shouldBe emptyList()
                catalog.issues.single().apply {
                    this shouldContain "template source world: could not read templates at ${model.version.take(8)}"
                    this shouldContain "RepositoryNotFoundException"
                    this shouldContain "no previous templates"
                }
                fixture.publish(listOf(loaded))
                testApplication {
                    serve(fixture.registry, board)
                    client.get("/ready").status shouldBe HttpStatusCode.OK
                    val status = Json.parseToJsonElement(client.get("/status").bodyAsText()).jsonObject
                    status
                        .getValue("issues")
                        .jsonArray
                        .single()
                        .jsonPrimitive.content shouldBe catalog.issues.single()
                    board.states().single().lastError shouldBe ""
                    board.states().single().consecutiveFailures shouldBe 0
                }
            }
        }

        "R1 read failure serves the previous catalog with a new live model; same-SHA recovery waits for a later swap" {
            TemplateFixture().use { fixture ->
                var current = fixture.modelSnapshot()
                val source =
                    GitTemplateSource(ModelSource { current }, fixture.root, "world/model", "world", 100) {
                        fixture.registry.read()?.exportSidecars?.get(
                            TEMPLATE_SIDECAR_PREFIX + "world",
                        )
                    }
                val board = SourceLoadBoard()
                board.register("world", "test source")
                val refresher =
                    GuardedRefresher(
                        listOf(source),
                        listOf("world"),
                        ModelReconciler(ModelDescriptor(id = "read-failure", name = "read-failure")),
                        fixture.registry,
                        true,
                        setOf("world"),
                        board,
                    )
                refresher.refresh("", true).single().success shouldBe true
                val initial = current.copy(exportSidecars = fixture.registry.readOrError().exportSidecars)
                val oldVersion = current.version
                fixture.write("reports/sales/report.yaml", "title: {en: Updated}\n")
                fixture.write("extra.ttrm", "model er\ndef entity extra { attributes: [] }\n")
                fixture.commit()
                current = fixture.modelSnapshot()
                val newVersion = current.version
                current.entities.size shouldBe initial.entities.size + 1
                val hiddenGit = fixture.root.resolve("hidden-git")
                Files.move(fixture.root.resolve(".git"), hiddenGit)
                testApplication {
                    serve(fixture.registry, board)
                    refresher.refresh("", true).single().success shouldBe true
                    fixture.registry
                        .readOrError()
                        .model
                        .objectByQname()
                        .keys
                        .containsAll(current.entities.keys) shouldBe true
                    val stale =
                        fixture.registry
                            .readOrError()
                            .templateCatalogs()
                            .single()
                    stale.version shouldBe oldVersion
                    stale.reports shouldBe initial.catalog().reports
                    stale.issues.single().apply {
                        this shouldContain newVersion.take(8)
                        this shouldContain "serving templates of ${oldVersion.take(8)}"
                    }
                    client.get("/ready").status shouldBe HttpStatusCode.OK
                    board.states().single().lastError shouldBe ""
                    val beforeRecovery = client.get("/model/report?id=sales").bodyAsText()
                    Files.move(hiddenGit, fixture.root.resolve(".git"))
                    // Recovery is retried at the failed SHA, but the unchanged-version refresher skips publication.
                    refresher.refresh("", true).single().snapshotSwapped shouldBe false
                    source.load().catalog().version shouldBe newVersion
                    source.load().catalog().issues shouldBe emptyList()
                    client.get("/model/report?id=sales").bodyAsText() shouldBe beforeRecovery
                    fixture.registry
                        .readOrError()
                        .templateCatalogs()
                        .single()
                        .issues shouldBe stale.issues
                    fixture.write("forms/recovered.yaml", "fields: []\n")
                    fixture.commit()
                    current = fixture.modelSnapshot()
                    refresher.refresh("", true).single().success shouldBe true
                    fixture.registry
                        .readOrError()
                        .templateCatalogs()
                        .single()
                        .issues shouldBe emptyList()
                    val detail = Json.parseToJsonElement(client.get("/model/report?id=sales").bodyAsText()).jsonObject
                    detail.getValue("yaml").jsonPrimitive.content shouldBe "title: {en: Updated}\n"
                }
            }
        }

        "R1 template errors keep the primary model and fallback uses its published catalog" {
            TemplateFixture().use { fixture ->
                var current = fixture.modelSnapshot()
                val primary =
                    GitTemplateSource(ModelSource { current }, fixture.root, "world/model", "world", 100) {
                        fixture.registry.read()?.exportSidecars?.get(
                            TEMPLATE_SIDECAR_PREFIX + "world",
                        )
                    }
                val first = primary.load()
                fixture.publish(listOf(first))
                val original = first.catalog()
                // A well-formed SHA absent from the checkout exercises a different read failure from Git.open.
                current = current.copy(version = "f".repeat(40))
                val missing =
                    GitTemplateSource(ModelSource { current }, fixture.root, "world/model", "world", 100) {
                        fixture.registry.read()?.exportSidecars?.get(
                            TEMPLATE_SIDECAR_PREFIX + "world",
                        )
                    }
                missing.load().catalog().reports shouldBe original.reports
                val composite =
                    FallbackSource("world", primary, ModelSource { error("must not invoke model fallback") })
                val loaded = composite.load()
                loaded.version shouldBe current.version
                loaded.catalog().reports shouldBe original.reports
                loaded.catalog().issues.single() shouldContain "MissingObjectException"
            }
        }

        "R1 genuine model load failures still propagate unchanged" {
            val source =
                GitTemplateSource(ModelSource { error("model unavailable") }, Path.of("unused"), "model", "world", 100)
            shouldThrow<IllegalStateException> { source.load() }.message shouldBe "model unavailable"
        }

        "R5 unchanged SHA reuses the encoded sidecar instance without opening git; new SHA attempts a fresh read" {
            TemplateFixture().use { fixture ->
                var current = fixture.modelSnapshot()
                val source =
                    GitTemplateSource(ModelSource { current }, fixture.root, "world/model", "world", 100) {
                        fixture.registry.read()?.exportSidecars?.get(
                            TEMPLATE_SIDECAR_PREFIX + "world",
                        )
                    }
                val first =
                    source
                        .load()
                        .exportSidecars.values
                        .single()
                Files.move(fixture.root.resolve(".git"), fixture.root.resolve("hidden-git"))
                source
                    .load()
                    .exportSidecars.values
                    .single()
                    .shouldBeSameInstanceAs(first)
                current = current.copy(version = "e".repeat(40))
                source
                    .load()
                    .catalog()
                    .issues
                    .single() shouldContain "RepositoryNotFoundException"
                Files.move(fixture.root.resolve("hidden-git"), fixture.root.resolve(".git"))
                // Retrying the failed SHA now reaches the missing object in the restored repository.
                source
                    .load()
                    .catalog()
                    .issues
                    .single() shouldContain "MissingObjectException"
            }
        }

        "N1 deep flow and block forms are content issues; the live model and valid form stay available" {
            TemplateFixture().use { fixture ->
                fixture.write("forms/valid.yaml", "fields: []\n")
                fixture.write("forms/deep-flow.yaml", "x: " + "[".repeat(5000) + "0" + "]".repeat(5000))
                fixture.write("forms/deep-block.yaml", (0..5000).joinToString("\n") { " ".repeat(it) + "x:" })
                fixture.commit()
                val model = fixture.modelSnapshot()
                val source = GitTemplateSource(ModelSource { model }, fixture.root, "world/model", "world", 100)
                val board = SourceLoadBoard()
                val loaded = board.recording("world", "deep forms", source).load()
                loaded.entities shouldBe model.entities
                loaded.version shouldBe model.version
                loaded.catalog().issues.toSet() shouldBe
                    setOf(
                        "template world:model/forms/deep-flow.yaml: nesting deeper than $MAX_TEMPLATE_NESTING",
                        "template world:model/forms/deep-block.yaml: nesting deeper than $MAX_TEMPLATE_NESTING",
                    )
                fixture.publish(listOf(loaded))
                // A content issue is a successful catalog and is memoized at this SHA.
                Files.move(fixture.root.resolve(".git"), fixture.root.resolve("hidden-git"))
                source.load().exportSidecars.values.single().shouldBeSameInstanceAs(
                    loaded.exportSidecars.values.single(),
                )
                testApplication {
                    serve(fixture.registry, board)
                    client.get("/ready").status shouldBe HttpStatusCode.OK
                    val forms =
                        Json
                            .parseToJsonElement(client.get("/model/forms").bodyAsText())
                            .jsonObject
                            .getValue("forms")
                            .jsonArray
                    forms
                        .single()
                        .jsonObject
                        .getValue("name")
                        .jsonPrimitive.content shouldBe "valid"
                    val status = Json.parseToJsonElement(client.get("/status").bodyAsText()).jsonObject
                    status.getValue("issues").jsonArray.size shouldBe 2
                    status.getValue("issues").jsonArray.forEach {
                        it.jsonPrimitive.content shouldContain
                            "nesting deeper than"
                    }
                    board.states().single().lastError shouldBe ""
                    board.states().single().consecutiveFailures shouldBe 0
                }
            }
        }

        "N1 model Errors stay outside the template safety net" {
            val overflow = StackOverflowError("inner model")
            val source = GitTemplateSource(ModelSource { throw overflow }, Path.of("unused"), "model", "world", 100)
            shouldThrow<StackOverflowError> { source.load() }.shouldBeSameInstanceAs(overflow)
            val vmError = object : VirtualMachineError("inner VM error") {}
            val other = GitTemplateSource(ModelSource { throw vmError }, Path.of("unused"), "model", "world", 100)
            shouldThrow<VirtualMachineError> { other.load() }.shouldBeSameInstanceAs(vmError)
        }

        "N3 identical failures reuse encoding and warn once, while every load still retries git" {
            TemplateFixture().use { fixture ->
                var model = fixture.modelSnapshot()
                val source = GitTemplateSource(ModelSource { model }, fixture.root, "world/model", "world", 100)
                val good = source.load().catalog()
                val missingSha = "e".repeat(40)
                model = model.copy(version = missingSha)
                Files.move(fixture.root.resolve(".git"), fixture.root.resolve("hidden-git"))
                withTemplateLogs { events ->
                    val first =
                        source
                            .load()
                            .exportSidecars.values
                            .single()
                    val second =
                        source
                            .load()
                            .exportSidecars.values
                            .single()
                    second.shouldBeSameInstanceAs(first)
                    events.count { it.level == Level.WARN } shouldBe 1
                    // Restore access between calls: the same attempted SHA now produces a different cause.
                    Files.move(fixture.root.resolve("hidden-git"), fixture.root.resolve(".git"))
                    val differentCause =
                        source
                            .load()
                            .exportSidecars.values
                            .single()
                    (differentCause === second) shouldBe false
                    Json.decodeFromString<TemplateCatalog>(differentCause).issues.single() shouldContain
                        "MissingObjectException"
                    events.count { it.level == Level.WARN } shouldBe 2
                    source
                        .load()
                        .exportSidecars.values
                        .single()
                        .shouldBeSameInstanceAs(differentCause)
                    // Returning to the successful SHA clears the remembered failure.
                    model = model.copy(version = good.version)
                    source.load().catalog() shouldBe good
                    model = model.copy(version = missingSha)
                    val afterSuccess =
                        source
                            .load()
                            .exportSidecars.values
                            .single()
                    (afterSuccess === differentCause) shouldBe false
                    events.count { it.level == Level.WARN } shouldBe 3
                    model = model.copy(version = "f".repeat(40))
                    (
                        source
                            .load()
                            .exportSidecars.values
                            .single() === afterSuccess
                    ) shouldBe false
                    events.count { it.level == Level.WARN } shouldBe 4
                }
            }
        }

        "N4 symlinked folders and model roots are diagnosed once without hiding other templates" {
            TemplateFixture().use { fixture ->
                val root = fixture.root.resolve("world/model")
                Files.createSymbolicLink(root.resolve("reports/shared"), Path.of("sales"))
                Files.createDirectories(root.resolve("forms"))
                fixture.write("forms/valid.yaml", "fields: []\n")
                Files.createSymbolicLink(root.resolve("forms/common"), Path.of("../reports"))
                Files.createSymbolicLink(fixture.root.resolve("current"), Path.of("world/model"))
                fixture.commit()
                val model = fixture.modelSnapshot()
                val normal =
                    GitTemplateSource(
                        ModelSource { model },
                        fixture.root,
                        "world/model",
                        "world",
                        100,
                    ).load().catalog()
                normal.reports.size shouldBe 1
                normal.forms.size shouldBe 1
                normal.issues.sorted() shouldBe
                    listOf(
                        "template world:model/forms/common: not a regular file (symlink/submodule)",
                        "template world:model/reports/shared: not a regular file (symlink/submodule)",
                    )
                val rootLink = GitTemplateSource(ModelSource { model }, fixture.root, "current", "world", 100).load()
                rootLink.entities shouldBe model.entities
                rootLink.catalog().reports shouldBe emptyList()
                rootLink.catalog().forms shouldBe emptyList()
                rootLink.catalog().issues.single() shouldBe
                    "template world:current: is a symlink/submodule; templates under it are not served"
                // A symlink further up a configured nested root is reported at the first blocked component.
                GitTemplateSource(ModelSource { model }, fixture.root, "current/nested", "world", 100)
                    .load()
                    .catalog()
                    .issues
                    .single() shouldBe rootLink.catalog().issues.single()
                // Missing report/form trees are normal.
                GitTemplateSource(ModelSource { model }, fixture.root, "absent", "world", 100)
                    .load()
                    .catalog()
                    .issues shouldBe emptyList()
            }
        }

        "N5 invalid roots warn at construction and reuse one empty catalog without git access or SHA diagnostics" {
            listOf("../model" to "escapes the repository", "/model" to "absolute").forEach { (raw, reason) ->
                val model = SourceSnapshot("configured", 50, "not-even-a-git-sha")
                withTemplateLogs { events ->
                    val source =
                        GitTemplateSource(ModelSource { model }, Path.of("missing-repository"), raw, "configured", 50)
                    events.count { it.level == Level.WARN } shouldBe 1
                    val first = source.load()
                    first.entities shouldBe model.entities
                    first.catalog().sourceId shouldBe "configured"
                    first.catalog().priority shouldBe 50
                    first.catalog().reports shouldBe emptyList()
                    first.catalog().issues.single() shouldBe
                        "template source configured: invalid subdirectory '$raw' ($reason); no templates served"
                    source.load().exportSidecars.values.single().shouldBeSameInstanceAs(
                        first.exportSidecars.values.single(),
                    )
                    events.count { it.level == Level.WARN } shouldBe 1
                }
            }
        }

        "R4 symlinked report, form and workbook paths are diagnosed without following them" {
            TemplateFixture().use { fixture ->
                val root = fixture.root.resolve("world/model")
                fixture.write("reports/link/report.yaml", "title: {en: Ignored}\n")
                Files.delete(root.resolve("reports/link/report.yaml"))
                Files.createSymbolicLink(root.resolve("reports/link/report.yaml"), Path.of("../sales/report.yaml"))
                Files.createSymbolicLink(root.resolve("reports/sales/link.xlsx"), Path.of("template.xlsx"))
                Files.delete(root.resolve("reports/sales/template.xlsx"))
                Files.createSymbolicLink(root.resolve("reports/sales/template.xlsx"), Path.of("link.xlsx"))
                Files.createDirectories(root.resolve("forms"))
                Files.createSymbolicLink(root.resolve("forms/link.yaml"), Path.of("../reports/sales/report.yaml"))
                fixture.commit()
                fixture.refresh()
                val catalog =
                    fixture.registry
                        .readOrError()
                        .templateCatalogs()
                        .single()
                catalog.reports shouldBe emptyList()
                catalog.forms shouldBe emptyList()
                catalog.issues.size shouldBe 4
                catalog.issues.toSet() shouldBe
                    setOf(
                        "template world:model/reports/link/report.yaml: not a regular file (symlink/submodule)",
                        "template world:model/reports/sales/template.xlsx: not a regular file (symlink/submodule)",
                        "template world:model/reports/sales/link.xlsx: not a regular file (symlink/submodule)",
                        "template world:model/forms/link.yaml: not a regular file (symlink/submodule)",
                    )
            }
        }

        "R6 blank model root serves only root reports and never pairs files from the nested model tree" {
            TemplateFixture("").use { fixture ->
                fixture.write("model/reports/sales/report.yaml", "title: {en: Wrong tree}\n")
                fixture.write("model/reports/sales/template.xlsx", byteArrayOf(2))
                fixture.write("reports/incomplete/report.yaml", "title: {en: Incomplete}\n")
                fixture.write("model/reports/incomplete/template.xlsx", byteArrayOf(3))
                fixture.commit()
                fixture.refresh()
                val catalog =
                    fixture.registry
                        .readOrError()
                        .templateCatalogs()
                        .single()
                catalog.reports.map {
                    it.summary
                        .getValue("id")
                        .jsonPrimitive.content
                } shouldBe listOf("sales")
                catalog.reports.single().yaml shouldContain "en: Sales"
                catalog.issues.single() shouldBe
                    "template world:model/reports/incomplete/template.xlsx: missing workbook"
                // GitArchiveStorage treats whitespace-only values as the repository root too.
                val whitespace =
                    GitTemplateSource(ModelSource { fixture.modelSnapshot() }, fixture.root, " \t ", "world", 100)
                whitespace.load().catalog() shouldBe catalog
            }
        }

        "R6 canonical and noncanonical subdirectories select the same tree" {
            TemplateFixture().use { fixture ->
                val snapshot = fixture.modelSnapshot()

                fun source(root: String) = GitTemplateSource(ModelSource { snapshot }, fixture.root, root, "world", 100)
                val expected = source("world/model").load().catalog()
                listOf("./world/model", "world/model/.", "world//model").forEach { root ->
                    source(root).load().catalog() shouldBe expected
                }
            }
        }
    })

private fun TemplateFixture.modelSnapshot(): SourceSnapshot =
    slots
        .single {
            it.id == "world"
        }.source
        .load()
        .copy(exportSidecars = emptyMap())

private fun SourceSnapshot.catalog(): TemplateCatalog = Json.decodeFromString(exportSidecars.values.single())

private inline fun withTemplateLogs(block: (List<ILoggingEvent>) -> Unit) {
    val logger = LoggerFactory.getLogger(GitTemplateSource::class.java) as Logger
    val appender = ListAppender<ILoggingEvent>()
    appender.start()
    logger.addAppender(appender)
    try {
        block(appender.list)
    } finally {
        logger.detachAppender(appender)
        appender.stop()
    }
}
