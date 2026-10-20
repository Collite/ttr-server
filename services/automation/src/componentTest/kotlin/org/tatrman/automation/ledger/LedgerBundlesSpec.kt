// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.ledger

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tatrman.automation.LedgerTestDb
import org.tatrman.automation.bundle.BundleKind
import org.tatrman.automation.bundle.BundleLoader
import org.tatrman.automation.bundle.Checkout
import org.tatrman.automation.identity.AuthorMap
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * AU-server S0.2 — the ledger's side of the bundle registry on a real Postgres: one `bundles` row per loaded
 * bundle (a reload of the commit updates it, a preview's `last_used_at` moves on use), and the `approvals` read a
 * deployment's staleness asks — an `approved` row of the kind, for the object, under exactly the fingerprint.
 */
class LedgerBundlesSpec :
    StringSpec({
        LedgerTestDb.ready()
        beforeTest { LedgerTestDb.reset() }

        val book = LedgerBundles()
        val bundle =
            BundleLoader(AuthorMap(emptyMap())).load(
                Checkout(
                    Path.of("src/test/resources/bundles/hartland"),
                    "master",
                    "c".repeat(40),
                    "Ops",
                    "ops@hartland.example",
                ),
                BundleKind.SERVED,
            )

        "a loaded bundle is one row; recording it again updates the row" {
            book.record(bundle)
            book.record(bundle)
            transaction {
                val rows = Bundles.selectAll().toList()
                rows.size shouldBe 1
                rows.single()[Bundles.id] shouldBe bundle.id
                rows.single()[Bundles.kind] shouldBe "served"
                rows.single()[Bundles.commit] shouldBe bundle.commit
                rows.single()[Bundles.modelFp] shouldBe bundle.modelFp
            }
        }

        "touch moves last_used_at" {
            book.record(bundle)
            val at = Instant.parse("2026-10-20T08:00:00Z")
            book.touch(bundle.id, at)
            transaction { Bundles.selectAll().single()[Bundles.lastUsedAt]!!.toInstant() shouldBe at }
        }

        "an approval counts for its kind, object and exact fingerprint, and only while approved" {
            fun approve(
                kind: String,
                obj: String,
                fp: String,
                status: String = "approved",
            ) = transaction {
                Approvals.insert {
                    it[id] = UUID.randomUUID()
                    it[objectKind] = if (kind == "values") "recipe" else kind
                    it[Approvals.obj] = obj
                    when (kind) {
                        "recipe" -> it[fpText] = fp
                        "values" -> it[fpValues] = fp
                        else -> it[Approvals.fp] = fp
                    }
                    it[Approvals.kind] = kind
                    it[approver] = "po"
                    it[Approvals.at] = Instant.parse("2026-10-19T06:00:00Z").atOffset(ZoneOffset.UTC)
                    it[Approvals.status] = status
                }
            }
            approve("recipe", "release_order", "sha256:t1")
            approve("values", "release_order", "sha256:v1")
            approve("deployment", "release_n8n", "sha256:d1", status = "superseded")
            book.approved("recipe", "release_order", "sha256:t1") shouldBe true
            book.approved("recipe", "release_order", "sha256:t2") shouldBe false
            book.approved("values", "release_order", "sha256:v1") shouldBe true
            book.approved("values", "settle", "sha256:v1") shouldBe false
            book.approved("deployment", "release_n8n", "sha256:d1") shouldBe false
        }
    })
