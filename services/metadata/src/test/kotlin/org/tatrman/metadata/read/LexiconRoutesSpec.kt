// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.read

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.ModelSource
import java.nio.file.Files
import java.util.Base64
import kotlinx.serialization.json.Json
import org.tatrman.ttr.metadata.refresh.MetadataRefresher
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.model.ModelDescriptor

class LexiconRoutesSpec :
    StringSpec({
        "exact committed bytes and ETag move only with the registry swap; conditional GET accepts weak lists and star" {
            TemplateFixture().use { f ->
                val archive = f.root.resolve("generated/lexicon.tar.zst")
                Files.createDirectories(archive.parent)
                val first = ByteArray(256) { it.toByte() }
                Files.write(archive, first)
                f.commit()
                f.refresh()
                val version =
                    f.registry
                        .read()!!
                        .servedLexicons()
                        .single()
                        .version
                val validator = "$version.${f.registry.read()!!.servedLexicons().single().blobId}"
                testApplication {
                    serve(f.registry)
                    val initial = client.get("/model/lexicon.tar.zst")
                    initial.status shouldBe HttpStatusCode.OK
                    initial.headers[HttpHeaders.ETag] shouldBe "\"$validator\""
                    initial.headers[HttpHeaders.ContentType] shouldBe "application/zstd"
                    initial.bodyAsBytes().toList() shouldBe first.toList()
                    // Even committed HEAD changes cannot leak into the old served snapshot.
                    Files.write(archive, byteArrayOf(3, 2, 1))
                    f.commit()
                    client.get("/model/lexicon.tar.zst").bodyAsBytes().toList() shouldBe first.toList()
                    val validators =
                        listOf(
                            "\"$validator\"",
                            validator,
                            "W/\"$validator\"",
                            "\"other\", W/\"$validator\"",
                            "*",
                        )
                    validators.forEach { token ->
                        val cached = client.get("/model/lexicon.tar.zst") { header(HttpHeaders.IfNoneMatch, token) }
                        cached.status shouldBe HttpStatusCode.NotModified
                        cached.bodyAsBytes().size shouldBe 0
                        cached.headers[HttpHeaders.ETag] shouldBe "\"$validator\""
                    }
                    f.refresh()
                    val changed =
                        client.get(
                            "/model/lexicon.tar.zst",
                        ) { header(HttpHeaders.IfNoneMatch, "\"$validator\"") }
                    changed.status shouldBe HttpStatusCode.OK
                    changed.bodyAsBytes().toList() shouldBe listOf<Byte>(3, 2, 1)
                    changed.headers[HttpHeaders.ETag] shouldBe
                        "\"${f.registry.read()!!.servedLexicons().single().let { "${it.version}.${it.blobId}" }}\""
                    client.post("/model/lexicon.tar.zst").status shouldBe HttpStatusCode.MethodNotAllowed
                }
            }
        }

        "before model is 503, missing archive or source is 404 even with wildcard; invalid selectors are 400" {
            testApplication {
                serve(MetadataRegistry())
                client.get("/model/lexicon.tar.zst").status shouldBe HttpStatusCode.ServiceUnavailable
            }
            TemplateFixture().use { f ->
                testApplication {
                    serve(f.registry)
                    client.get("/model/lexicon.tar.zst") { header(HttpHeaders.IfNoneMatch, "*") }.status shouldBe
                        HttpStatusCode.NotFound
                    client.get("/model/lexicon.tar.zst?source=unknown").status shouldBe HttpStatusCode.NotFound
                    listOf("?source=", "?source=a&source=b", "?other=x").forEach {
                        client.get("/model/lexicon.tar.zst$it").status shouldBe HttpStatusCode.BadRequest
                    }
                }
            }
        }

        "multi-source bare URLs require selectors even when a configured source is not loaded" {
            TemplateFixture().use { f ->
                val model = f.modelSnapshot()

                fun served(
                    id: String,
                    priority: Int,
                    byte: Byte,
                ) = model.copy(
                    sourceId = id,
                    priority = priority,
                    exportSidecars =
                        mapOf(
                            LEXICON_SIDECAR_PREFIX + id to
                                Json.encodeToString(
                                    ServedLexicon(
                                        id,
                                        priority,
                                        id.repeat(40).take(40),
                                        Base64.getEncoder().encodeToString(byteArrayOf(byte)),
                                        blobId = byte.toString(),
                                    ),
                                ),
                        ),
                )
                f.publish(listOf(served("a", 100, 1), served("z", 100, 2), served("b", 50, 3)))
                testApplication {
                    serve(f.registry, lexiconSourceIds = setOf("a", "z", "b", "unloaded"))
                    val ambiguous = client.get("/model/lexicon.tar.zst")
                    ambiguous.status shouldBe HttpStatusCode.BadRequest
                    ambiguous.bodyAsText() shouldContain "a, b, unloaded, z"
                    val unloaded = client.get("/model/lexicon.tar.zst?source=unloaded")
                    unloaded.status shouldBe HttpStatusCode.ServiceUnavailable
                    unloaded.bodyAsText() shouldContain "lexicon source not loaded"
                    val unknown = client.get("/model/lexicon.tar.zst?source=typo")
                    unknown.status shouldBe HttpStatusCode.NotFound
                    unknown.bodyAsText() shouldContain "unknown lexicon source"
                    val chosen = client.get("/model/lexicon.tar.zst?source=b")
                    chosen.bodyAsBytes().toList() shouldBe listOf<Byte>(3)
                    chosen.headers[HttpHeaders.ETag] shouldBe "\"${"b".repeat(40)}.3\""
                }
            }
        }

        "changing archive path at the same SHA changes its ETag; blank disables it cleanly" {
            TemplateFixture().use { f ->
                Files.write(f.root.resolve("one.zst"), byteArrayOf(1))
                Files.write(f.root.resolve("two.zst"), byteArrayOf(2))
                f.commit()
                val model = f.modelSnapshot()

                fun publish(path: String) =
                    f.publish(listOf(GitLexiconSource(ModelSource { model }, f.checkout, path).load()))
                publish("one.zst")
                testApplication {
                    serve(f.registry)
                    val first = client.get("/model/lexicon.tar.zst").headers[HttpHeaders.ETag]!!
                    publish("two.zst")
                    val second = client.get("/model/lexicon.tar.zst") { header(HttpHeaders.IfNoneMatch, first) }
                    second.status shouldBe HttpStatusCode.OK
                    second.bodyAsBytes().toList() shouldBe listOf<Byte>(2)
                    publish("  ")
                    client.get("/model/lexicon.tar.zst").status shouldBe HttpStatusCode.NotFound
                    f.registry
                        .read()!!
                        .servedLexicons()
                        .single()
                        .issue shouldBe null
                }
            }
        }

        "same-SHA read repair waits for a real refresher swap" {
            TemplateFixture().use { f ->
                Files.write(f.root.resolve("lexicon.zst"), byteArrayOf(7))
                f.commit()
                var model = f.modelSnapshot()
                val gitDir = f.checkout.resolve(".git")
                val movedGit = f.checkout.resolve(".git-away")
                val source = GitLexiconSource(ModelSource { model }, f.checkout, "lexicon.zst")
                Files.move(gitDir, movedGit)
                val failed =
                    try {
                        source.load()
                    } finally {
                        Files.move(movedGit, gitDir)
                    }
                f.publish(listOf(failed))
                val refresher =
                    MetadataRefresher(
                        listOf(source),
                        listOf("world"),
                        ModelReconciler(ModelDescriptor(id = "repair", name = "repair")),
                        f.registry,
                    )
                refresher.recordInitial(listOf(failed))
                testApplication {
                    serve(f.registry)
                    refresher.refresh("", true)
                    client.get("/model/lexicon.tar.zst").status shouldBe HttpStatusCode.ServiceUnavailable
                    client.get("/status").bodyAsText() shouldContain "lexicon source world"
                    Files.write(f.root.resolve("lexicon.zst"), byteArrayOf(8))
                    f.commit()
                    model = f.modelSnapshot()
                    refresher.refresh("", true)
                    val repaired = client.get("/model/lexicon.tar.zst")
                    repaired.status shouldBe HttpStatusCode.OK
                    repaired.bodyAsBytes().toList() shouldBe listOf<Byte>(8)
                    repaired.headers[HttpHeaders.ETag]!! shouldContain model.version
                }
            }
        }

        "read failures and symlinked ancestors do not take the model down or relabel an old archive" {
            TemplateFixture().use { f ->
                val model = f.modelSnapshot()
                val source = GitLexiconSource(ModelSource { model }, f.checkout, "generated/lexicon.tar.zst")
                val missing = source.load()
                val encoded = missing.exportSidecars.getValue(LEXICON_SIDECAR_PREFIX + model.sourceId)
                source.load().exportSidecars.getValue(LEXICON_SIDECAR_PREFIX + model.sourceId) shouldBeSameInstanceAs
                    encoded
                val archive = f.root.resolve("generated/lexicon.tar.zst")
                Files.createDirectories(archive.parent)
                Files.write(archive, byteArrayOf(1))
                f.commit()
                f.refresh()
                val good = f.modelSnapshot()
                var current = good
                val failing = GitLexiconSource(ModelSource { current }, f.checkout, "generated/lexicon.tar.zst")
                failing.load()
                current = good.copy(version = "f".repeat(40))
                val failed = failing.load()
                failed.version shouldBe current.version
                f.publish(listOf(failed))
                testApplication {
                    serve(f.registry)
                    client.get("/model/lexicon.tar.zst") { header(HttpHeaders.IfNoneMatch, "*") }.status shouldBe
                        HttpStatusCode.ServiceUnavailable
                    client.get("/status").bodyAsText() shouldContain "lexicon source world"
                }
                // New links avoid JGit 6.8's add-command bug when replacing an indexed directory.
                Files.createSymbolicLink(
                    f.root.resolve("linked"),
                    java.nio.file.Path
                        .of("generated"),
                )
                Files.createSymbolicLink(
                    archive.parent.resolve("linked.zst"),
                    java.nio.file.Path
                        .of("lexicon.tar.zst"),
                )
                f.commit()
                f.refresh()
                val linkedModel = f.modelSnapshot()
                listOf("linked/lexicon.tar.zst", "generated/linked.zst").forEach { path ->
                    val linked = GitLexiconSource(ModelSource { linkedModel }, f.checkout, path).load()
                    f.publish(listOf(linked))
                    f.registry
                        .read()!!
                        .servedLexicons()
                        .single()
                        .issue!! shouldContain "symlink/submodule"
                }
            }
        }
    })

private fun TemplateFixture.modelSnapshot() =
    slots
        .single {
            it.id == "world"
        }.source
        .load()
        .copy(exportSidecars = emptyMap())
