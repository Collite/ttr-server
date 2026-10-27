// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.read

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.eclipse.jgit.api.Git
import org.tatrman.metadata.buildSources
import org.tatrman.metadata.grpc.PackageIndex
import org.tatrman.metadata.health.SourceLoadBoard
import org.tatrman.metadata.health.modelHealthRoutes
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.refresh.MetadataRefresher
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.ModelSource
import org.tatrman.ttr.metadata.source.SourceSnapshot
import org.tatrman.ttr.metadata.source.FallbackSource
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

class TemplateRoutesSpec :
    StringSpec({
        "configured Git source serves report metadata, exact YAML and binary workbook checksum" {
            TemplateFixture().use { fixture ->
                testApplication {
                    serve(fixture.registry)
                    val list = client.get("/model/reports")
                    list.status shouldBe HttpStatusCode.OK
                    Json.parseToJsonElement(list.bodyAsText()) shouldBe
                        Json.parseToJsonElement(
                            """{"reports":[{"id":"sales","title":{"en":"Sales","cs":"Tržby"},
                                |"program":"er.query.sales","params":[{"name":"year","kind":"int","default":2026}],
                                |"roles":["finance"],"refresh":"daily","source":"model/reports/sales/report.yaml","version":3}]}
                            """.trimMargin(),
                        )
                    val response = client.get("/model/report?id=sales")
                    response.status shouldBe HttpStatusCode.OK
                    val report = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    report.keys shouldBe setOf("id", "yaml", "template", "sha256")
                    report.getValue("id").jsonPrimitive.content shouldBe "sales"
                    report.getValue("yaml").jsonPrimitive.content shouldBe REPORT_YAML
                    Base64.getDecoder().decode(report.getValue("template").jsonPrimitive.content).toList() shouldBe
                        WORKBOOK.toList()
                    report.getValue("sha256").jsonPrimitive.content shouldBe
                        "40aff2e9d2d8922e47afd4648e6967497158785fbd1da870e7110266bf944880"
                }
            }
        }

        "forms preserve authored fields and use their relative path target, including nested targets" {
            TemplateFixture().use { fixture ->
                fixture.write(
                    "forms/er.entity.invoice.yaml",
                    "target: ignored\nfields: [{name: amount, required: true}]\n",
                )
                fixture.write("forms/admin/other.yaml", "title: {cs: Další}\ncustom: 12\n")
                fixture.commit()
                fixture.refresh()
                testApplication {
                    serve(fixture.registry)
                    val response = client.get("/model/forms")
                    response.status shouldBe HttpStatusCode.OK
                    Json.parseToJsonElement(response.bodyAsText()) shouldBe
                        Json.parseToJsonElement(
                            """{"forms":[{"target":"admin/other","title":{"cs":"Další"},"custom":12,
                                |"source":"model/forms/admin/other.yaml"},
                                |{"target":"er.entity.invoice","fields":[{"name":"amount","required":true}],
                                |"source":"model/forms/er.entity.invoice.yaml"}]}
                            """.trimMargin(),
                        )
                }
            }
        }

        "routes fail closed before loading, return 404 for unknown ids, and expose no write path" {
            testApplication {
                serve(MetadataRegistry())
                listOf("/model/reports", "/model/report?id=sales", "/model/forms").forEach { path ->
                    client.get(path).status shouldBe HttpStatusCode.ServiceUnavailable
                }
            }
            TemplateFixture().use { fixture ->
                testApplication {
                    serve(fixture.registry)
                    client.get("/model/report").status shouldBe HttpStatusCode.BadRequest
                    client.get("/model/report?id=absent").status shouldBe HttpStatusCode.NotFound
                    client.get("/model/report?id=..%2F..%2Fsecret").status shouldBe HttpStatusCode.NotFound
                    client.post("/model/reports").status.value shouldBe 405
                    client.post("/model/report?id=sales").status.value shouldBe 405
                    client.post("/model/forms").status.value shouldBe 405
                }
            }
        }

        "invalid YAML, duplicate keys, multiple documents and missing workbooks are isolated as status issues" {
            TemplateFixture().use { fixture ->
                fixture.write("reports/broken/report.yaml", "title: [unterminated\n")
                fixture.write("forms/duplicate.yaml", "fields: []\nfields: []\n")
                fixture.write("forms/documents.yaml", "fields: []\n---\nfields: []\n")
                fixture.write("forms/scalar.yaml", "hello\n")
                fixture.write("reports/missing/report.yaml", "title: {en: Missing}\n")
                fixture.commit()
                fixture.refresh()
                testApplication {
                    serve(fixture.registry)
                    val reports = Json.parseToJsonElement(client.get("/model/reports").bodyAsText()).jsonObject
                    reports.getValue("reports").jsonArray.shouldHaveSize(1)
                    client.get("/model/report?id=broken").status shouldBe HttpStatusCode.NotFound
                    client.get("/model/report?id=missing").status shouldBe HttpStatusCode.NotFound
                    client.get("/model/forms").bodyAsText() shouldBe "{\"forms\":[]}"
                    val status = Json.parseToJsonElement(client.get("/status").bodyAsText()).jsonObject
                    status
                        .getValue("issues")
                        .jsonArray
                        .map { it.jsonPrimitive.content }
                        .toSet() shouldBe
                        setOf(
                            "template model/reports/broken/report.yaml: invalid YAML mapping",
                            "template model/forms/duplicate.yaml: invalid YAML mapping",
                            "template model/forms/documents.yaml: invalid YAML mapping",
                            "template model/forms/scalar.yaml: invalid YAML mapping",
                            "template model/reports/missing/template.xlsx: missing or not a regular file",
                        )
                }
            }
        }

        "checkout updates and staged loads remain invisible until the registry swaps; deletion clears content" {
            TemplateFixture().use { fixture ->
                testApplication {
                    serve(fixture.registry)
                    val original = client.get("/model/report?id=sales").bodyAsText()
                    fixture.write("reports/sales/report.yaml", REPORT_YAML.replace("Sales", "Updated"))
                    fixture.commit()
                    val staged = fixture.slots.map { it.source.load() }
                    client.get("/model/report?id=sales").bodyAsText() shouldBe original
                    fixture.publish(staged)
                    val updated = Json.parseToJsonElement(client.get("/model/report?id=sales").bodyAsText()).jsonObject
                    updated.getValue("yaml").jsonPrimitive.content shouldBe REPORT_YAML.replace("Sales", "Updated")
                    fixture.root
                        .resolve("world/model/reports/sales")
                        .toFile()
                        .deleteRecursively() shouldBe true
                    fixture.commit()
                    fixture.refresh()
                    client.get("/model/report?id=sales").status shouldBe HttpStatusCode.NotFound
                    client.get("/model/reports").bodyAsText() shouldBe "{\"reports\":[]}"
                    client.get("/model/forms").bodyAsText() shouldBe "{\"forms\":[]}"
                }
            }
        }

        "template blobs are pinned to the source SHA even when HEAD advances during the load" {
            TemplateFixture().use { fixture ->
                val old =
                    fixture.git.repository
                        .resolve("HEAD")
                        .name
                fixture.write("reports/sales/report.yaml", REPORT_YAML.replace("Sales", "New head"))
                fixture.commit()
                val source =
                    GitTemplateSource(
                        ModelSource { SourceSnapshot("pinned", 100, old) },
                        fixture.root,
                        "world/model",
                    )
                val catalog =
                    Json.decodeFromString<TemplateCatalog>(
                        source
                            .load()
                            .exportSidecars.values
                            .single(),
                    )
                catalog.reports.single().yaml shouldBe REPORT_YAML
            }
        }

        "failed source refresh keeps the previous template contribution" {
            TemplateFixture().use { fixture ->
                testApplication {
                    serve(fixture.registry)
                    val before = client.get("/model/report?id=sales").bodyAsText()
                    Files.move(fixture.checkout, fixture.root.resolve("unavailable-checkout"))
                    // Removing the remote as well makes the re-clone fail; no new snapshot is published.
                    Files.move(fixture.root.resolve(".git"), fixture.root.resolve("unavailable-git"))
                    fixture.refresher
                        .refresh("world", true)
                        .single { it.sourceId == "world" }
                        .success shouldBe false
                    client.get("/model/report?id=sales").bodyAsText() shouldBe before
                }
            }
        }

        "a failed source keeps its templates when another source successfully swaps a new model" {
            TemplateFixture().use { fixture ->
                val initial = fixture.slots.map { it.source.load() }
                val prior = initial.single { it.sourceId == "world" }
                val other = SourceSnapshot("other", 50, "old")
                val refresher =
                    MetadataRefresher(
                        listOf(ModelSource { error("unavailable") }, ModelSource { other.copy(version = "new") }),
                        listOf("world", "other"),
                        ModelReconciler(ModelDescriptor(id = "mixed", name = "mixed")),
                        fixture.registry,
                    )
                refresher.recordInitial(listOf(prior, other))
                testApplication {
                    serve(fixture.registry)
                    val before = client.get("/model/report?id=sales").bodyAsText()
                    val result = refresher.refresh("", true)
                    result.single { it.sourceId == "world" }.success shouldBe false
                    result.single { it.sourceId == "other" }.snapshotSwapped shouldBe true
                    client.get("/model/report?id=sales").bodyAsText() shouldBe before
                }
            }
        }

        "switching to a configured fallback removes the primary templates with the primary model" {
            TemplateFixture().use { fixture ->
                val prior =
                    fixture.slots
                        .single { it.id == "world" }
                        .source
                        .load()
                var failed = false
                val source =
                    FallbackSource(
                        "world",
                        ModelSource { if (failed) error("unavailable") else prior },
                        ModelSource {
                            prior.copy(
                                sourceId = "fallback",
                                version = "fallback",
                                exportSidecars = emptyMap(),
                            )
                        },
                    )
                val refresher =
                    MetadataRefresher(
                        listOf(source),
                        listOf("world"),
                        ModelReconciler(ModelDescriptor(id = "fallback", name = "fallback")),
                        fixture.registry,
                    )
                refresher.recordInitial(listOf(source.load()))
                testApplication {
                    serve(fixture.registry)
                    client.get("/model/report?id=sales").status shouldBe HttpStatusCode.OK
                    failed = true
                    refresher.refresh("", true).single().success shouldBe true
                    client.get("/model/reports").bodyAsText() shouldBe "{\"reports\":[]}"
                    client.get("/model/report?id=sales").status shouldBe HttpStatusCode.NotFound
                }
            }
        }

        "same report id from multiple sources follows source priority for list and detail together" {
            val first =
                templateCatalog(
                    "a",
                    10,
                    mapOf(
                        "model/reports/sales/report.yaml" to "title: {en: Low}".toByteArray(),
                        "model/reports/sales/template.xlsx" to byteArrayOf(1),
                    ),
                )
            val second =
                templateCatalog(
                    "b",
                    100,
                    mapOf(
                        "model/reports/sales/report.yaml" to "title: {en: High}".toByteArray(),
                        "model/reports/sales/template.xlsx" to byteArrayOf(2),
                    ),
                )
            TemplateFixture().use { fixture ->
                val model = fixture.registry.readOrError().model
                fixture.registry.swap(
                    model,
                    ModelGraph.build(model),
                    exportSidecars =
                        mapOf(
                            TEMPLATE_SIDECAR_PREFIX + "b" to Json.encodeToString(second),
                            TEMPLATE_SIDECAR_PREFIX + "a" to Json.encodeToString(first),
                        ),
                )
                testApplication {
                    serve(fixture.registry)
                    val summaries =
                        Json
                            .parseToJsonElement(client.get("/model/reports").bodyAsText())
                            .jsonObject
                            .getValue("reports")
                            .jsonArray
                    summaries.shouldHaveSize(1)
                    summaries
                        .single()
                        .jsonObject
                        .getValue(
                            "title",
                        ).jsonObject
                        .getValue("en")
                        .jsonPrimitive.content shouldBe
                        "High"
                    val detail = Json.parseToJsonElement(client.get("/model/report?id=sales").bodyAsText()).jsonObject
                    detail.getValue("template").jsonPrimitive.content shouldBe "Ag=="
                }
            }
        }
    })

private fun ApplicationTestBuilder.serve(registry: MetadataRegistry) {
    environment { config = MapApplicationConfig() }
    application {
        routing {
            templateRoutes(registry)
            modelHealthRoutes(registry, SourceLoadBoard())
        }
    }
}

private val WORKBOOK = ByteArray(256) { it.toByte() }
private val REPORT_YAML =
    """# Preserve comments and Unicode on the detail route.
        |id: sales
        |title: {en: Sales, cs: Tržby}
        |version: 3
        |program: er.query.sales
        |params: [{name: year, kind: int, default: 2026}]
        |roles: [finance]
        |refresh: daily
        |
    """.trimMargin()

/** Real local GitArchiveStorage + production source wiring + library refresher, with no network. */
private class TemplateFixture : AutoCloseable {
    val root: Path = Files.createTempDirectory("template-source-")
    val checkout: Path = root.resolveSibling(root.fileName.toString() + "-checkout")
    val git: Git =
        Git
            .init()
            .setDirectory(root.toFile())
            .setInitialBranch("main")
            .call()
    val registry = MetadataRegistry()
    private val reconciler = ModelReconciler(ModelDescriptor(id = "templates", name = "templates"))

    init {
        write("er.ttrm", "model er\ndef entity item { attributes: [def attribute id { type: int, isKey: true }] }\n")
        write("reports/sales/report.yaml", REPORT_YAML)
        write("reports/sales/template.xlsx", WORKBOOK)
        commit()
    }

    val slots =
        buildSources(
            ConfigFactory.parseMap(
                mapOf(
                    "metadata.sources.world.type" to "git",
                    "metadata.sources.world.remote-uri" to root.toUri().toString(),
                    "metadata.sources.world.local-checkout-dir" to checkout.toString(),
                    "metadata.sources.world.branch" to "main",
                    "metadata.sources.world.subdirectory" to "world/model",
                ),
            ),
            PackageIndex(),
        )
    val refresher = MetadataRefresher(slots.map { it.source }, slots.map { it.id }, reconciler, registry)

    init {
        publish(slots.map { it.source.load() })
    }

    fun write(
        path: String,
        text: String,
    ) = write(path, text.toByteArray())

    fun write(
        path: String,
        bytes: ByteArray,
    ) {
        val file = root.resolve("world/model").resolve(path)
        Files.createDirectories(file.parent)
        Files.write(file, bytes)
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

    fun publish(snapshots: List<SourceSnapshot>) {
        refresher.recordInitial(snapshots)
        val result = reconciler.reconcile(snapshots)
        registry.swap(
            result.model,
            ModelGraph.build(result.model),
            exportSidecars =
                snapshots
                    .flatMap { it.exportSidecars.entries }
                    .associate { it.toPair() },
        )
    }

    suspend fun refresh() {
        refresher.refresh("", true).all { it.success } shouldBe true
    }

    override fun close() {
        git.close()
        checkout.toFile().deleteRecursively()
        root.toFile().deleteRecursively()
    }
}
