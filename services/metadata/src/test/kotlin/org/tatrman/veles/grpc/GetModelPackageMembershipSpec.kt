// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.grpc

import com.typesafe.config.ConfigFactory
import org.tatrman.meta.v1.GetModelRequest
import org.tatrman.meta.v1.GetModelResponse
import org.tatrman.meta.v1.ListObjectsRequest
import org.tatrman.meta.v1.ListQueriesRequest
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.refresh.MetadataRefresher
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.veles.buildSources
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

/**
 * ttr-server#111 — GetModel and the `package` filters of ListObjects / ListQueries scope by the
 * package a file belongs to, never by its path.
 *
 * The estate is the issue's reproduction: a git source checked out to a directory named like one
 * of its packages (`<checkout>/a`, model subdirectory `model`), package `a` at the model root and
 * package `b` in `b/` — with one `b` file under a directory named `a`, one file that declares no
 * package (it belongs to the package its directory implies) and one such file at the root (it
 * belongs to none). Every one of them sits under `/a/`, so the path filter served all of them
 * as package `a`.
 */
class GetModelPackageMembershipSpec :
    StringSpec({

        fun entity(name: String) =
            """
            |model er
            |
            |def entity $name {
            |    attributes: [
            |        def attribute id { type: int, isKey: true }
            |    ]
            |}
            |
            """.trimMargin()

        fun patternQuery(name: String) =
            """
            |model query
            |
            |def query $name {
            |    language: SQL
            |    sourceText: "select 1"
            |    search {
            |        patterns: ["$name"]
            |    }
            |}
            |
            """.trimMargin()

        /** The model root, `<checkout>/a/model`. */
        fun estate(): Path {
            val root = Files.createTempDirectory("veles-111-").resolve("a").resolve("model")

            fun write(
                path: String,
                text: String,
            ) {
                val file = root.resolve(path)
                Files.createDirectories(file.parent)
                Files.writeString(file, text)
            }
            write("er/sales.ttrm", "package a\n" + entity("sale"))
            write("queries/q_a.ttrm", "package a\n" + patternQuery("q_a"))
            write("b/er/parties.ttrm", "package b\n" + entity("party"))
            write("b/queries/q_b.ttrm", "package b\n" + patternQuery("q_b"))
            // A `b` file under a directory named like package `a`.
            write("b/a/extra.ttrm", "package b\n" + entity("extra"))
            // No `package` clause: `c/` implies package `c`; the model root implies none.
            write("c/loose.ttrm", entity("loose"))
            write("orphan.ttrm", entity("orphan"))
            return root
        }

        fun service(root: Path): MetadataServiceImpl {
            val source = FileBasedSource(sourceId = "git", priority = 100, storage = LocalFsStorage("git", root))
            val snapshot = source.load()
            val result =
                ModelReconciler(ModelDescriptor(id = "test", name = "test", description = "ttr-server#111"))
                    .reconcile(listOf(snapshot))
            val registry = MetadataRegistry()
            registry.swap(result.model, ModelGraph.build(result.model), result.warnings + result.errors)
            return MetadataServiceImpl(registry, packageIndex = servedIndex(snapshot))
        }

        suspend fun MetadataServiceImpl.bundle(vararg packages: String): GetModelResponse =
            getModel(GetModelRequest.newBuilder().addAllPackages(packages.toList()).build())

        fun GetModelResponse.entities() = model.entitiesList.map { it.objectDescriptor.localName }.toSet()

        fun GetModelResponse.patternQueries() = model.patternQueriesList.map { it.objectDescriptor.localName }.toSet()

        fun GetModelResponse.hashOf(pkg: String) =
            model.packageVersionsList.single { it.packageName == pkg }.contentHash

        "GetModel([a]) serves package a alone, though the checkout directory is named a" {
            val r = service(estate()).bundle("a")

            r.entities() shouldBe setOf("sale")
            r.patternQueries() shouldBe setOf("q_a")
            r.model.packageVersionsList.map { it.packageName } shouldBe listOf("a")
        }

        "GetModel([b]) serves every b file, the one under a directory named a included" {
            val r = service(estate()).bundle("b")

            r.entities() shouldBe setOf("party", "extra")
            r.patternQueries() shouldBe setOf("q_b")
        }

        "a file without a package clause belongs to its directory's package; at the root, to none" {
            val svc = service(estate())

            svc.bundle("c").entities() shouldBe setOf("loose")
            svc.bundle("a", "b", "c").entities() shouldBe setOf("sale", "party", "extra", "loose")
        }

        "ListObjects and ListQueries filter by the same membership" {
            val svc = service(estate())

            suspend fun entitiesIn(pkg: String) =
                svc
                    .listObjects(
                        ListObjectsRequest
                            .newBuilder()
                            .setKind("entity")
                            .setPackage(pkg)
                            .build(),
                    ).itemsList
                    .map { it.localName }
                    .toSet()

            suspend fun queriesIn(pkg: String) =
                svc
                    .listQueries(ListQueriesRequest.newBuilder().setPackage(pkg).build())
                    .itemsList
                    .map { it.objectDescriptor.localName }
                    .toSet()

            entitiesIn("a") shouldBe setOf("sale")
            entitiesIn("b") shouldBe setOf("party", "extra")
            queriesIn("a") shouldBe setOf("q_a")
            queriesIn("b") shouldBe setOf("q_b")
        }

        // The production path, not a hand-fed index: Application's buildSources wraps every
        // configured slot, the refresher loads and swaps, and the index serves what was swapped in.
        "as wired in Application: every slot records, and a load is served once its model swaps in" {
            val root = estate()
            val config = ConfigFactory.parseString("metadata.sources.git { type = filesystem, path = \"$root\" }")
            val registry = MetadataRegistry()
            val index = PackageIndex().commitOnSwap(registry)
            val slots = buildSources(config, index)
            val refresher =
                MetadataRefresher(
                    sources = slots.map { it.source },
                    sourceIds = slots.map { it.id },
                    reconciler = ModelReconciler(ModelDescriptor(id = "test", name = "test", description = "wiring")),
                    registry = registry,
                )
            val svc = MetadataServiceImpl(registry, packageIndex = index)

            refresher.refresh(sourceId = "", force = true)
            svc.bundle("b").entities() shouldBe setOf("party", "extra")

            // `extra` moves to package c. Loaded, but its model is not swapped in yet: the served
            // model still has `extra` in b, so the index must still say b.
            val extra = root.resolve("b/a/extra.ttrm")
            val touched = FileTime.fromMillis(Files.getLastModifiedTime(extra).toMillis() + 60_000)
            Files.writeString(extra, "package c\n" + entity("extra"))
            Files.setLastModifiedTime(extra, touched)
            slots.forEach { it.source.load() }
            svc.bundle("b").entities() shouldBe setOf("party", "extra")
            svc.bundle("c").entities() shouldBe setOf("loose")

            refresher.refresh(sourceId = "", force = true)
            svc.bundle("b").entities() shouldBe setOf("party")
            svc.bundle("c").entities() shouldBe setOf("loose", "extra")
        }

        // A caller that gates on packages (an agent entitling what it reads) must be told the
        // package, not left to derive it from `source_file` — the derivation #111 shows is wrong.
        "every served descriptor states the package its file belongs to, not the one requested" {
            val svc = service(estate())
            val bundle = svc.bundle("a", "b", "c").model

            bundle.entitiesList.associate { it.objectDescriptor.localName to it.objectDescriptor.packageName } shouldBe
                mapOf("sale" to "a", "party" to "b", "extra" to "b", "loose" to "c")
            bundle.entitiesList
                .flatMap { e ->
                    e.attributesList.map { e.objectDescriptor.localName to it.objectDescriptor.packageName }
                }.toSet() shouldBe setOf("sale" to "a", "party" to "b", "extra" to "b", "loose" to "c")
            bundle.patternQueriesList.associate {
                it.objectDescriptor.localName to it.objectDescriptor.packageName
            } shouldBe
                mapOf("q_a" to "a", "q_b" to "b")

            val listed =
                svc
                    .listObjects(ListObjectsRequest.newBuilder().setKind("entity").build())
                    .itemsList
                    .associate { it.localName to it.packageName }
            listed shouldBe mapOf("sale" to "a", "party" to "b", "extra" to "b", "loose" to "c", "orphan" to "")
            svc
                .listQueries(ListQueriesRequest.getDefaultInstance())
                .itemsList
                .associate { it.objectDescriptor.localName to it.objectDescriptor.packageName } shouldBe
                mapOf("q_a" to "a", "q_b" to "b")
        }

        "a package's content hash covers its own files only" {
            val root = estate()
            val before = service(root).bundle("a", "b")

            Files.writeString(root.resolve("b/er/parties.ttrm"), "package b\n// edited\n" + entity("party"))
            val after = service(root).bundle("a", "b")

            after.hashOf("a") shouldBe before.hashOf("a")
            after.hashOf("b") shouldNotBe before.hashOf("b")
        }
    })
