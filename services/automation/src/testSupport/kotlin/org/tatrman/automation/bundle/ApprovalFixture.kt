// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.bundle

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Overlay the released CLI's evidence-bearing writer bundle onto the real Server metadata fixture. */
object ApprovalFixture {
    fun copyTo(
        root: Path,
        unqualified: Boolean = false,
        generatedIds: Boolean = false,
        obligations: Boolean = false,
        fulfilment: Boolean = false,
        obligationConflict: Boolean = false,
    ): Path {
        val base = Path.of("src/test/resources/approval-evidence")
        require(listOf(unqualified, generatedIds, obligations, fulfilment).count { it } <= 1)
        require(!obligationConflict || obligations)
        val resource =
            when {
                unqualified -> base.resolve("unqualified")
                generatedIds -> base.resolve("generated-ids")
                obligations -> base.resolve("obligations")
                fulfilment -> base.resolve("fulfilment")
                else -> base
            }

        copyFixtureTree(Path.of("src/test/resources/bundles/hartland"), root)
        // Writer cases deliberately use their small target schema, not every required column of the hero model.
        for ((directory, source, target) in listOf(
            Triple("er", "model.ttrm", "orders.ttrm"),
            Triple("binding", "binding.ttrm", "er2db.ttrm"),
            Triple("db", "db.ttrm", "orders.ttrm"),
        )) {
            val destination = root.resolve("model/$directory")
            destination.toFile().deleteRecursively()
            Files.createDirectories(destination)
            Files.copy(base.resolve(source), destination.resolve(target))
        }
        if (generatedIds) {
            Files.copy(resource.resolve("binding.ttrm"), root.resolve("model/binding/proposal.ttrm"))
            Files.copy(resource.resolve("db.ttrm"), root.resolve("model/db/proposal.ttrm"))
            Files.copy(
                resource.resolve("proposal.ttrm"),
                root.resolve("model/er/proposal.ttrm"),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        Files.copy(
            resource.resolve("automation.ttrm"),
            root.resolve("automation/automation.ttrm"),
            StandardCopyOption.REPLACE_EXISTING,
        )
        val bundleResource = if (obligationConflict) resource.resolve("conflict") else resource
        val bundle = root.resolve("automation/release_order/release_order.bundle")
        bundle.toFile().deleteRecursively()
        copyFixtureTree(bundleResource.resolve("release_order.bundle"), bundle)
        return root
    }
}
