// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.writer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.automation.apply.PlanPlacement
import org.tatrman.automation.bundle.BundleFixture
import org.tatrman.automation.bundle.ApprovalFixture
import kotlinx.serialization.json.*
import org.tatrman.automation.apply.*

class PlanPlacementSpec :
    StringSpec({
        "H-1's already resolved order effects and verdicts have separate storage groups" {
            val bundle = BundleFixture.load()
            val source = PlanPlacement.source(bundle, "demo", bundle.recipes.getValue("release_order"))
            source.ledgerStorage shouldBe "automation_db"
            source.storages.getValue("er.entity.order") shouldBe "erp_db"
        }

        "ERP uses hosts even when an attribute matches a ledger writable entry" {
            val root = BundleFixture.copy()
            try {
                val files =
                    java.nio.file.Files.walk(root.resolve("model/er")).use { paths ->
                        paths
                            .filter {
                                java.nio.file.Files
                                    .isRegularFile(it)
                            }.toList()
                    }
                val orderFile =
                    files.single {
                        java.nio.file.Files
                            .readString(it)
                            .contains("def entity order {")
                    }
                java.nio.file.Files.writeString(
                    orderFile,
                    java.nio.file.Files
                        .readString(
                            orderFile,
                        ).replace(
                            "def entity order {",
                            "def entity order {\n    // bare approvals must not place the ERP entity\n",
                        ).replace(
                            "attributes: [",
                            "attributes: [def attribute approvals { type: text, optional: true },",
                        ),
                )
                val bundle = BundleFixture.load(root)
                val source = PlanPlacement.source(bundle, "demo", bundle.recipes.getValue("release_order"))
                source.storages.getValue("er.entity.order") shouldBe "erp_db"
                val second = PlanPlacement.source(bundle, "demo", bundle.recipes.getValue("release_order"))
                (second === source) shouldBe true
            } finally {
                root.toFile().deleteRecursively()
            }
        }

        "obligation group uses its own world placement separately from the verdict" {
            val root = BundleFixture.copy()
            try {
                ApprovalFixture.copyTo(root, obligations = true)
                val file = root.resolve("model/worlds/demo.ttrm")
                java.nio.file.Files.writeString(
                    file,
                    java.nio.file.Files
                        .readString(file)
                        .replace("writable: [obligation,", "writable: [")
                        .replace(
                            "def storage stage",
                            "def storage obligation_db { type: postgres, via: automation, writable: [obligation] }\n    def storage stage",
                        ),
                )
                val bundle = BundleFixture.load(root)
                val recipe = bundle.recipes.getValue("release_order")
                val source = PlanPlacement.source(bundle, "demo", recipe)
                source.ledgerStorage shouldBe "automation_db"
                source.obligationStorage shouldBe "obligation_db"
                val key = buildJsonObject { put("id", "ob-1") }
                val rows =
                    listOf(
                        ApplyRow(
                            1,
                            "er.entity.obligation",
                            "insert",
                            key,
                            row =
                                JsonObject(
                                    key + ("state" to JsonPrimitive("open")),
                                ),
                        ),
                    )
                val plan =
                    ApplyPlanner(
                        BundleFixture.clock,
                    ).plan(PreviewPlan("batch", "run", rows, emptyList(), source = source))
                plan.groups.single().storage shouldBe "obligation_db"
            } finally {
                root.toFile().deleteRecursively()
            }
        }
    })
