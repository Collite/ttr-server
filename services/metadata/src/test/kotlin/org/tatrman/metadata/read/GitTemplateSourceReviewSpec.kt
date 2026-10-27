// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.read

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
import org.tatrman.ttr.metadata.refresh.MetadataRefresher
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
                val source = GitTemplateSource(ModelSource { current }, fixture.root, "world/model")
                val board = SourceLoadBoard()
                val recorded = board.recording("world", "test source", source)
                val initial = recorded.load()
                fixture.publish(listOf(initial))
                val refresher =
                    MetadataRefresher(
                        listOf(recorded),
                        listOf("world"),
                        ModelReconciler(ModelDescriptor(id = "read-failure", name = "read-failure")),
                        fixture.registry,
                    )
                refresher.recordInitial(listOf(initial))
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

        "R1 template errors keep the primary model and each instance keeps its own previous catalog" {
            TemplateFixture().use { fixture ->
                var current = fixture.modelSnapshot()
                val primary = GitTemplateSource(ModelSource { current }, fixture.root, "world/model")
                val original = primary.load().catalog()
                // A well-formed SHA absent from the checkout exercises a different read failure from Git.open.
                current = current.copy(version = "f".repeat(40))
                val missing = GitTemplateSource(ModelSource { current }, fixture.root, "world/model")
                missing.load().catalog().reports shouldBe emptyList()
                val composite =
                    FallbackSource("world", primary, ModelSource { error("must not invoke model fallback") })
                val loaded = composite.load()
                loaded.version shouldBe current.version
                loaded.catalog().reports shouldBe original.reports
                loaded.catalog().issues.single() shouldContain "MissingObjectException"
            }
        }

        "R1 genuine model load failures still propagate unchanged" {
            val source = GitTemplateSource(ModelSource { error("model unavailable") }, Path.of("unused"), "model")
            shouldThrow<IllegalStateException> { source.load() }.message shouldBe "model unavailable"
        }

        "R5 unchanged SHA reuses the encoded sidecar instance without opening git; new SHA attempts a fresh read" {
            TemplateFixture().use { fixture ->
                var current = fixture.modelSnapshot()
                val source = GitTemplateSource(ModelSource { current }, fixture.root, "world/model")
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
                catalog.issues.toSet() shouldBe
                    setOf(
                        "template world:model/reports/link/report.yaml: not a regular file (symlink/submodule)",
                        "template world:model/reports/sales/template.xlsx: not a regular file (symlink/submodule)",
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
                val whitespace = GitTemplateSource(ModelSource { fixture.modelSnapshot() }, fixture.root, " \t ")
                whitespace.load().catalog() shouldBe catalog
            }
        }

        "R6 canonical and noncanonical subdirectories select the same tree and parent escapes are diagnosed" {
            TemplateFixture().use { fixture ->
                val snapshot = fixture.modelSnapshot()

                fun source(root: String) = GitTemplateSource(ModelSource { snapshot }, fixture.root, root)
                val expected = source("world/model").load().catalog()
                listOf("./world/model", "world/model/.", "world//model").forEach { root ->
                    source(root).load().catalog() shouldBe expected
                }
                val invalid = source("../model").load()
                invalid.entities shouldBe snapshot.entities
                invalid.catalog().reports shouldBe emptyList()
                invalid.catalog().issues.single() shouldContain "model subdirectory escapes the repository"
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
