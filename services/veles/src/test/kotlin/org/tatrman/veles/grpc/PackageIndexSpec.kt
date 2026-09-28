// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.grpc

import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.LoadedFile
import org.tatrman.ttr.metadata.source.ModelSource
import org.tatrman.ttr.metadata.source.SourceSnapshot
import org.tatrman.ttr.metadata.source.StorageFile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** ttr-server#111 — the file → package index GetModel scopes by. */
class PackageIndexSpec :
    StringSpec({

        fun file(
            path: String,
            computed: String,
            declared: String? = null,
        ) = LoadedFile(
            storageFile = StorageFile(path = path, sizeBytes = 0),
            computedPackage = computed,
            declaredPackage = declared,
            imports = emptyList(),
            definitions = emptyList(),
            schemaCode = "",
            namespace = "",
        )

        fun snapshot(
            sourceId: String,
            vararg files: LoadedFile,
        ) = SourceSnapshot(sourceId = sourceId, priority = 100, version = "v", loadedFiles = files.toList())

        /** A model swap, as the refresher does it after a successful reconcile. */
        fun MetadataRegistry.swapIn() {
            val reconciler = ModelReconciler(ModelDescriptor(id = "t", name = "t", description = "t"))
            val model = reconciler.reconcile(emptyList()).model
            swap(model, ModelGraph.build(model))
        }

        "a declared package wins over the one the directory implies" {
            val index = PackageIndex()
            index.record(
                snapshot(
                    "git",
                    // `package a` declared by a file at the model root, in `er/`
                    file("/tmp/metadata-git/a/model/er/sales.ttrm", computed = "er", declared = "a"),
                    file("/tmp/metadata-git/a/model/c/loose.ttrm", computed = "c"),
                ),
            )
            index.commit()

            index.packageOf("/tmp/metadata-git/a/model/er/sales.ttrm") shouldBe "a"
            index.packageOf("/tmp/metadata-git/a/model/c/loose.ttrm") shouldBe "c"
        }

        "an unknown file and a blank package match nothing" {
            val index = PackageIndex()
            index.record(snapshot("git", file("/m/orphan.ttrm", computed = "")))
            index.commit()

            index.packageOf("/m/elsewhere.ttrm") shouldBe null
            index.contains("", "/m/orphan.ttrm") shouldBe false
            index.contains("m", "/m/orphan.ttrm") shouldBe false
        }

        // FallbackSource answers with the FALLBACK's source id; its load must replace the primary's.
        "a load answered under another source id replaces the slot's files" {
            val index = PackageIndex()
            var next = snapshot("github-model", file("/git/a/x.ttr", computed = "a"))
            val source = index.recording("github-model", ModelSource { next })

            source.load()
            index.commit()
            index.packageOf("/git/a/x.ttr") shouldBe "a"

            next = snapshot("model-ttr", file("/bundled/b/y.ttr", computed = "b"))
            source.load()
            index.commit()
            index.packageOf("/git/a/x.ttr") shouldBe null
            index.packageOf("/bundled/b/y.ttr") shouldBe "b"
        }

        "a failed load keeps the slot's previous files, as the refresher keeps its snapshot" {
            val index = PackageIndex()
            var fail = false
            val source =
                index.recording(
                    "git",
                    ModelSource {
                        if (fail) error("clone failed")
                        snapshot("git", file("/git/a/x.ttr", computed = "a"))
                    },
                )

            source.load()
            fail = true
            shouldThrow<IllegalStateException> { source.load() }
            index.commit()
            index.packageOf("/git/a/x.ttr") shouldBe "a"
        }

        "a load is served only once its model swaps in" {
            val registry = MetadataRegistry()
            val index = PackageIndex().commitOnSwap(registry)

            index.record(snapshot("git", file("/git/a/x.ttr", computed = "a")))
            index.packageOf("/git/a/x.ttr") shouldBe null

            registry.swapIn()
            index.packageOf("/git/a/x.ttr") shouldBe "a"
        }

        // The refresher's reconcile or swap failed: the registry keeps serving the previous model,
        // so the index keeps serving that model's packages, not the load's.
        "a load whose model never swaps in leaves the served packages alone" {
            val registry = MetadataRegistry()
            val index = PackageIndex().commitOnSwap(registry)
            index.record(snapshot("git", file("/git/m/x.ttr", computed = "m", declared = "a")))
            registry.swapIn()

            index.record(snapshot("git", file("/git/m/x.ttr", computed = "m", declared = "b")))
            index.packageOf("/git/m/x.ttr") shouldBe "a"
            index.contains("b", "/git/m/x.ttr") shouldBe false
        }
    })
