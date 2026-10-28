// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.bundle

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Bound calendar and unchanged bundle produced by published ttrp 0.18.14. */
object CalendarFixture {
    private val resource = Path.of("src/test/resources/calendar-preview")

    fun add(root: Path): Path {
        copyFixtureTree(resource.resolve("model"), root.resolve("model"))
        return root
    }

    fun copyTo(root: Path): Path {
        copyFixtureTree(Path.of("src/test/resources/bundles/hartland"), root)
        add(root)
        val bundle = root.resolve("automation/release_order/release_order.bundle")
        bundle.toFile().deleteRecursively()
        copyFixtureTree(resource.resolve("release_order.bundle"), bundle)
        Files.copy(
            resource.resolve("release_order.ttrp"),
            root.resolve("automation/release_order/release_order.ttrp"),
            StandardCopyOption.REPLACE_EXISTING,
        )
        return root
    }
}
