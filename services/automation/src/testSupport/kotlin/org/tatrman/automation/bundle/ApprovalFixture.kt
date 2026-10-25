// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.bundle

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Overlay the released CLI's evidence-bearing writer bundle onto the real Server metadata fixture. */
object ApprovalFixture {
    fun copyTo(root: Path): Path {
        val resource = Path.of("src/test/resources/approval-evidence")

        fun tree(
            source: Path,
            target: Path,
        ) {
            Files.walk(source).use { paths ->
                paths.forEach { path ->
                    val dest = target.resolve(source.relativize(path).toString())
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(dest)
                    } else {
                        Files.copy(path, dest, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
        }
        tree(Path.of("src/test/resources/bundles/hartland"), root)
        Files.copy(
            resource.resolve("automation.ttrm"),
            root.resolve("automation/automation.ttrm"),
            StandardCopyOption.REPLACE_EXISTING,
        )
        val bundle = root.resolve("automation/release_order/release_order.bundle")
        bundle.toFile().deleteRecursively()
        tree(resource.resolve("release_order.bundle"), bundle)
        return root
    }
}
