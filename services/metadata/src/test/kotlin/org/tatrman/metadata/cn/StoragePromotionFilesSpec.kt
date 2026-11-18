// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.cn

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.http.HttpStatusCode
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.metadata.source.ModelStorage
import org.tatrman.ttr.metadata.source.StorageFile
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * review-134 F3 / F4 / F9 — what a promotion reads: every file the loader reads (`.ttr` and `.ttrm`); a git checkout's
 * working tree after ONE fetch; a source that cannot be read as a refusal, never an exception.
 */
class StoragePromotionFilesSpec :
    FunSpec({
        val temps = mutableListOf<Path>()
        afterSpec { temps.forEach { it.toFile().deleteRecursively() } }

        fun tree(): Path =
            Files.createTempDirectory("cn-promotion-files").also { temps.add(it) }.also { root ->
                root.resolve("legacy.ttr").writeText("model er\n")
                root.resolve("cn").createDirectories()
                root.resolve("cn/cn.ttrm").writeText("model cn\n")
                root.resolve("cn/layout.ttrl").writeText("not model text\n")
            }

        /** A storage that counts its fetches and must not be listed. */
        class Counting(
            private val versions: Iterator<String>,
        ) : ModelStorage {
            var fetches = 0
            override val id = "counting"

            override fun fetchVersion(): String {
                fetches++
                return versions.next()
            }

            override fun listFiles(
                extensions: List<String>,
                prefixes: List<String>,
            ): List<StorageFile> = error("a checkout's tree is read from disk, not listed through the storage")

            override fun read(file: StorageFile): String = error("not read through the storage")
        }

        test("every model file the loader reads — .ttr and .ttrm — by its path under the model root (F4)") {
            val text = StoragePromotionFiles(LocalFsStorage(id = "t", rootPath = tree())).read()
            text.files.keys shouldBe setOf("legacy.ttr", "cn/cn.ttrm")
            text.files["cn/cn.ttrm"] shouldBe "model cn\n"
        }

        test("a git checkout: one fetch gives the version, the files come from its working tree (F9)") {
            val storage = Counting(generateSequence { "c0ffee" }.iterator())
            val text = StoragePromotionFiles(storage, localRoot = tree()).read()
            storage.fetches shouldBe 1
            text.version shouldBe "c0ffee"
            text.files.keys shouldBe setOf("legacy.ttr", "cn/cn.ttrm")
        }

        test("a source that cannot be read is 503 CN_PROMOTION_SOURCE_UNAVAILABLE, not an exception (F3)") {
            val down =
                object : ModelStorage {
                    override val id = "down"

                    override fun fetchVersion(): String = throw IOException("connection refused")

                    override fun listFiles(
                        extensions: List<String>,
                        prefixes: List<String>,
                    ): List<StorageFile> = emptyList()

                    override fun read(file: StorageFile): String = ""
                }
            for (files in listOf(StoragePromotionFiles(down), StoragePromotionFiles(down, localRoot = tree()))) {
                val e = shouldThrow<CnRefused> { files.read() }
                e.status shouldBe HttpStatusCode.ServiceUnavailable
                e.code shouldBe Promoter.CN_PROMOTION_SOURCE_UNAVAILABLE
                e.message shouldBe "the promotion source could not be read: connection refused"
            }
        }

        test("without a checkout, a version that moves under every listing is 503 CN_PROMOTION_SOURCE_MOVING") {
            var n = 0
            val root = tree()
            val moving =
                object : ModelStorage {
                    private val files = LocalFsStorage(id = "m", rootPath = root)
                    override val id = "moving"

                    override fun fetchVersion(): String = "v${n++}"

                    override fun listFiles(
                        extensions: List<String>,
                        prefixes: List<String>,
                    ): List<StorageFile> = files.listFiles(extensions, prefixes)

                    override fun read(file: StorageFile): String = files.read(file)
                }
            shouldThrow<CnRefused> { StoragePromotionFiles(moving).read() }.code shouldBe
                Promoter.CN_PROMOTION_SOURCE_MOVING
        }

        test("MW S4.2 — a yaml source read through a dialect is 409 promotion_target_dialect; canonical passes") {
            val declared = { profile: String ->
                tree().also { it.resolve("ttr.yaml").writeText("profile: $profile\ngrammar: '>=0.22 <1.0'\n") }
            }

            fun refused(files: StoragePromotionFiles) =
                shouldThrow<CnRefused> { files.read() }.let { it.status to it.code }
            val dialect = HttpStatusCode.Conflict to StoragePromotionFiles.PROMOTION_TARGET_DIALECT
            // The tree's ttr.yaml, through the storage and through a checkout's working tree.
            refused(
                StoragePromotionFiles(LocalFsStorage(id = "d", rootPath = declared("shop/1")), yaml = true),
            ) shouldBe
                dialect
            val checkout = Counting(generateSequence { "c0ffee" }.iterator())
            refused(StoragePromotionFiles(checkout, localRoot = declared("shop/1"), yaml = true)) shouldBe dialect
            // The configured profile, on a tree without ttr.yaml.
            refused(
                StoragePromotionFiles(LocalFsStorage(id = "p", rootPath = tree()), yaml = true, profile = "shop/1"),
            ) shouldBe
                dialect
            // Canonical: no declaration, or the bundled identity. A ttr source never reads ttr.yaml.
            for (root in listOf(tree(), declared("canonical/1"))) {
                StoragePromotionFiles(LocalFsStorage(id = "c", rootPath = root), yaml = true).read().files.keys shouldBe
                    setOf("legacy.ttr", "cn/cn.ttrm")
            }
            StoragePromotionFiles(LocalFsStorage(id = "t", rootPath = declared("shop/1"))).read().version shouldNotBe ""
        }
    })
