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
    })
