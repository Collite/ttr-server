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
        obligationConflict: Boolean = false,
    ): Path {
        val base = Path.of("src/test/resources/approval-evidence")
        require(listOf(unqualified, generatedIds, obligations).count { it } <= 1)
        require(!obligationConflict || obligations)
        val resource =
            when {
                unqualified -> base.resolve("unqualified")
                generatedIds -> base.resolve("generated-ids")
                obligations -> base.resolve("obligations")
                else -> base
            }

        copyFixtureTree(Path.of("src/test/resources/bundles/hartland"), root)
        if (generatedIds) {
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
