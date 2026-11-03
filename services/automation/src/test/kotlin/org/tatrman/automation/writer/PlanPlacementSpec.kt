// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.writer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.automation.apply.PlanPlacement
import org.tatrman.automation.bundle.BundleFixture

class PlanPlacementSpec :
    StringSpec({
        "H-1's already resolved order effects and verdicts have separate storage groups" {
            val bundle = BundleFixture.load()
            val source = PlanPlacement.source(bundle, "demo", bundle.recipes.getValue("release_order"))
            source.ledgerStorage shouldBe "automation_db"
            source.storages.getValue("er.entity.order") shouldBe "erp_db"
        }
    })
