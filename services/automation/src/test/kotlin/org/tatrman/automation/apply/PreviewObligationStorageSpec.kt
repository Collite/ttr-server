// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.ApprovalFixture
import org.tatrman.automation.bundle.BundleFixture
import org.tatrman.ttr.metadata.model.World
import org.tatrman.ttr.metadata.model.WorldSchema

class PreviewObligationStorageSpec :
    StringSpec({
        val bundle =
            BundleFixture.load(
                ApprovalFixture.copyTo(tempdir().toPath().resolve("unrelated-folder"), fulfilment = true),
            )
        val schema = bundle.model.schemas.getValue("world") as WorldSchema
        val world = schema.worlds.values.single { it.qname.name == "demo" }
        val storage = world.storages.single { it.qname.name == "automation_db" }

        fun changed(value: World) =
            bundle.copy(
                model =
                    bundle.model.copy(
                        schemas =
                            bundle.model.schemas +
                                ("world" to schema.copy(worlds = schema.worlds + (world.qname to value))),
                    ),
            )

        "obligation placement uses declared logical storage without a package-shaped checkout" {
            PreviewObligationStorage.resolve(bundle, "demo") shouldBe "automation_db"
            val renamed =
                world.copy(
                    storages =
                        world.storages.map {
                            if (it ==
                                storage
                            ) {
                                it.copy(qname = it.qname.copy(name = "ledger_archive"))
                            } else {
                                it
                            }
                        },
                )
            PreviewObligationStorage.resolve(changed(renamed), "demo") shouldBe "ledger_archive"
            world.storages.single { it.qname.name == "erp_db" }.hosts shouldBe listOf("hartland")
        }

        "missing ambiguous or unbound-engine placements refuse without choosing a default storage" {
            for (value in listOf(
                world.copy(storages = world.storages - storage),
                world.copy(
                    storages =
                        world.storages +
                            storage.copy(
                                internalId = "other-ledger",
                                qname = storage.qname.copy(name = "other_ledger"),
                            ),
                ),
                world.copy(storages = world.storages.map { if (it == storage) it.copy(via = null) else it }),
                world.copy(storages = world.storages.map { if (it == storage) it.copy(via = "missing") else it }),
            )) {
                shouldThrow<AutomationException> { PreviewObligationStorage.resolve(changed(value), "demo") }
            }
            shouldThrow<AutomationException> { PreviewObligationStorage.resolve(bundle, "missing") }
        }

        "world hosts require whole declared packages even for an unbound obligation lookup" {
            for (host in listOf("not.loaded", "hart", "hartland_extra")) {
                val invalid =
                    world.copy(
                        storages =
                            world.storages.map {
                                if (it.qname.name == "erp_db") it.copy(hosts = listOf(host)) else it
                            },
                    )
                shouldThrow<AutomationException> {
                    PreviewObligationStorage.resolve(changed(invalid), "demo")
                }.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
            }
        }

        "a missing loader package map refuses instead of projecting away world hosts" {
            shouldThrow<AutomationException> {
                PreviewObligationStorage.resolve(bundle.copy(packages = emptyMap()), "demo")
            }.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
        }

        "unresolved and cyclic storage overlays refuse" {
            for (ref in listOf("missing.package.storage", "automation_db")) {
                val value =
                    world.copy(
                        storages =
                            world.storages.map {
                                if (it ==
                                    storage
                                ) {
                                    it.copy(extendsRef = ref)
                                } else {
                                    it
                                }
                            },
                    )
                shouldThrow<AutomationException> { PreviewObligationStorage.resolve(changed(value), "demo") }
            }
        }
    })
