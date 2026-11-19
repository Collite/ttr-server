// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.refresh

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.tatrman.metadata.cn.CnRefused
import org.tatrman.metadata.cn.StoragePromotionFiles
import org.tatrman.ttr.metadata.source.LocalFsStorage
import org.tatrman.ttr.yaml.CanonicalYaml
import java.nio.file.Files
import java.nio.file.Path

/**
 * MW S4.2 review-380 — the heap cap (✅D-MW-96), the promotion guard (contracts §1, ✅MW-52) and the reported profile
 * read a `yaml` tree as the loader does: a content file's own `ttr` header wins over the tree's declaration (block or
 * flow `ttr.yaml`) or the configured profile, `model.ignore` and the walk decide what is content, and only files a
 * dialect profile reads pay the cap. Each fixture first loads uncapped, so a refusal is the cap's or the guard's.
 */
class DialectInputsSpec :
    StringSpec({
        fun source(
            root: Path,
            cap: Long = Long.MAX_VALUE,
        ) = YamlKindSource("s", 100, LocalFsStorage("s", root), null, maxInputCodePoints = cap)

        fun guard(root: Path) =
            shouldThrow<CnRefused> { StoragePromotionFiles(LocalFsStorage("s", root), yaml = true).read() }.code

        "B1 — a content file's own dialect header pays the cap" {
            shopTree { root ->
                Files.writeString(
                    root.resolve("data.yaml"),
                    "ttr: {profile: shop/1, grammar: '>=0.22 <1.0'}\n$WAREHOUSE",
                )
                source(root).load().tables.size shouldBe 1
                shouldThrow<IllegalStateException> { source(root, cap = 1).load() }.message!! shouldStartWith
                    "yaml_input_budget: source 's'"
            }
        }

        "B2 — a content file's own dialect header, .yaml or .yml, is refused as a promotion target" {
            for (name in listOf("data.yaml", "data.yml")) {
                shopTree { root ->
                    Files.writeString(root.resolve(name), "ttr: {profile: shop/1, grammar: '>=0.22 <1.0'}\n$WAREHOUSE")
                    source(root).load().tables.size shouldBe 1
                    guard(root) shouldBe StoragePromotionFiles.PROMOTION_TARGET_DIALECT
                }
            }
        }

        "B2 — a flow-style ttr.yaml reports its profile and is refused as a promotion target" {
            shopTree { root ->
                Files.writeString(root.resolve("ttr.yaml"), "{profile: shop/1, grammar: '>=0.22 <1.0'}\n")
                Files.writeString(root.resolve("data.yaml"), WAREHOUSE)
                val source = source(root)
                source.load().tables.size shouldBe 1
                source.profileName shouldBe "shop/1"
                guard(root) shouldBe StoragePromotionFiles.PROMOTION_TARGET_DIALECT
            }
        }

        "B3 — a tree declaring canonical/1 runs no profile and pays no cap" {
            shopTree { root ->
                Files.writeString(root.resolve("ttr.yaml"), "profile: canonical/1\ngrammar: '>=0.22 <1.0'\n")
                Files.writeString(
                    root.resolve("data.yaml"),
                    CanonicalYaml.write(CanonicalYaml.fromTtr("model db\ndef table Warehouse {}", "data.yaml")),
                )
                source(root).load().tables.size shouldBe 1
                source(root, cap = 1).load().tables.size shouldBe 1
            }
        }

        "B3 — a file model.ignore excludes pays no cap; the dialect content still does" {
            shopTree { root ->
                Files.writeString(root.resolve("ttr.yaml"), "profile: shop/1\ngrammar: '>=0.22 <1.0'\n")
                Files.writeString(root.resolve("data.yaml"), WAREHOUSE)
                Files.writeString(root.resolve("model.ignore"), "profiles/\nignored.yaml\n")
                Files.writeString(root.resolve("ignored.yaml"), "x".repeat(1000))
                val content = WAREHOUSE.codePointCount(0, WAREHOUSE.length).toLong()
                source(root).load().tables.size shouldBe 1
                source(root, cap = content).load().tables.size shouldBe 1
                shouldThrow<IllegalStateException> { source(root, cap = content - 1).load() }
            }
        }
    })

private const val WAREHOUSE = "tabulka:\n  Warehouse: {}\n"

/** An invented dialect: `shop/1` reads `tabulka` as tables. */
private fun shopTree(block: suspend (Path) -> Unit) {
    val root = Files.createTempDirectory("dialect-inputs-")
    try {
        Files.createDirectories(root.resolve("profiles"))
        Files.writeString(
            root.resolve("profiles/shop.yaml"),
            """
            profile: shop/1
            grammar: '>=0.22 <1.0'
            operations:
              - {op: package, mapping: {}, default: shop}
              - {op: kinds, at: /tabulka, mapping: {tabulka: table}}
            """.trimIndent() + "\n",
        )
        Files.writeString(root.resolve("model.ignore"), "profiles/\n")
        kotlinx.coroutines.runBlocking { block(root) }
    } finally {
        root.toFile().deleteRecursively()
    }
}
