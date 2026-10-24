// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.automation.bundle.ManifestColumn
import org.tatrman.automation.staging.LocalStagingStore
import org.tatrman.automation.staging.Staging
import java.nio.file.Files
import java.util.UUID

class StagingSpec :
    StringSpec({
        "published Arrow and ledger metadata agree; repeated publication is immutable" {
            val root = Files.createTempDirectory("au-staging")
            val store = LocalStagingStore(root)
            val records = mutableListOf<org.tatrman.automation.ledger.RunOutput>()
            val stage = Staging(store, records::add)
            val id = UUID.randomUUID()
            val table = Table(listOf(ManifestColumn("id", "int")), listOf(listOf(42L)))
            val output = stage.put(id, "i", "rows", table)
            stage.read(output) shouldBe table
            stage.put(id, "i", "rows", table).output shouldBe output.output
            shouldThrow<IllegalStateException> { stage.put(id, "i", "rows", table.copy(rows = listOf(listOf(43L)))) }
            val bytes = store.get(output.output.path!!)
            ArrowTables.decode(listOf(bytes)).rows shouldBe table.rows
            output.output.sha256 shouldBe
                "sha256:" +
                java.security.MessageDigest
                    .getInstance("SHA-256")
                    .digest(bytes)
                    .joinToString("") { "%02x".format(it) }
        }
        "page reads refuse corrupt Arrow and changed journal counts instead of using cached rows" {
            val root = Files.createTempDirectory("au-staging-corrupt")
            val store = LocalStagingStore(root)
            val stage = Staging(store) {}
            val table = Table(listOf(ManifestColumn("id", "int")), listOf(listOf(42L)))
            val output = stage.put(UUID.randomUUID(), "i", "rows", table)
            shouldThrow<IllegalStateException> {
                stage.read(output.copy(output = output.output.copy(rows = 2)))
            }
            val path = output.output.path!!
            val bytes = store.get(path)
            bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
            Files.write(root.resolve(path), bytes)
            shouldThrow<IllegalStateException> { stage.read(output) }
        }
        "failed ledger publication never deletes a blob another claimant may already reference" {
            val root = Files.createTempDirectory("au-staging-failed")
            val store = LocalStagingStore(root)
            val id = UUID.randomUUID()
            val table = Table(listOf(ManifestColumn("id", "int")), emptyList())
            val failed = Staging(store) { error("database down") }
            shouldThrow<IllegalStateException> { failed.put(id, "i", "rows", table) }
            Files.exists(root.resolve("runs/$id/i.rows.arrow")) shouldBe true
            val original = Staging(store) {}.put(id, "i", "rows", table)
            shouldThrow<IllegalStateException> { failed.put(id, "i", "rows", table) }
            store.get(original.output.path!!).isNotEmpty() shouldBe true
        }
        "local paths and manifest output names cannot traverse outside staging" {
            val root = Files.createTempDirectory("au-staging-safe")
            shouldThrow<IllegalArgumentException> { LocalStagingStore(root).get("../outside") }
            shouldThrow<IllegalArgumentException> {
                Staging(
                    LocalStagingStore(root),
                ) {}.put(UUID.randomUUID(), "../outside", "rows", Table(emptyList(), emptyList()))
            }
        }
        "per-run deletion collects journalled, orphan and temporary blobs without touching siblings" {
            val root = Files.createTempDirectory("au-staging-delete")
            val store = LocalStagingStore(root)
            val id = UUID.randomUUID()
            val other = UUID.randomUUID()
            store.putIfAbsent("runs/$id/i.rows.arrow", byteArrayOf(1))
            store.putIfAbsent("runs/$id/.stage-orphan.tmp", byteArrayOf(2))
            store.putIfAbsent("runs/$id/nested/orphan.arrow", byteArrayOf(3))
            store.putIfAbsent("runs/$other/i.rows.arrow", byteArrayOf(4))
            store.deleteRun(id)
            store.deleteRun(id)
            Files.exists(root.resolve("runs/$id")) shouldBe false
            store.get("runs/$other/i.rows.arrow").toList() shouldBe listOf<Byte>(4)
        }
        "per-run deletion never follows links to another directory" {
            val root = Files.createTempDirectory("au-staging-links")
            val outside = Files.createTempDirectory("au-staging-outside")
            Files.write(outside.resolve("keep.arrow"), byteArrayOf(5))
            val store = LocalStagingStore(root)
            val id = UUID.randomUUID()
            Files.createDirectories(root.resolve("runs/$id"))
            Files.createSymbolicLink(root.resolve("runs/$id/link"), outside)
            store.deleteRun(id)
            Files.readAllBytes(outside.resolve("keep.arrow")).toList() shouldBe listOf<Byte>(5)
            Files.createSymbolicLink(root.resolve("runs/$id"), outside)
            shouldThrow<IllegalArgumentException> { store.deleteRun(id) }
            shouldThrow<IllegalArgumentException> { store.get("runs/$id/keep.arrow") }
            shouldThrow<IllegalArgumentException> { store.putIfAbsent("runs/$id/new.arrow", byteArrayOf(1)) }
            Files.exists(outside.resolve("new.arrow")) shouldBe false
            Files.delete(root.resolve("runs/$id"))
            Files.delete(root.resolve("runs"))
            Files.createSymbolicLink(root.resolve("runs"), outside)
            shouldThrow<IllegalArgumentException> { store.deleteRun(id) }
            Files.exists(outside.resolve("keep.arrow")) shouldBe true
        }
    })
