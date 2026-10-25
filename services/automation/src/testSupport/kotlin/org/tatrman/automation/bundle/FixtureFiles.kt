// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.bundle

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttr.metadata.model.AutomationSchema

fun copyFixtureTree(
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

/** Test snapshot mutations must update the actual reconciled model before resolving fresh coverage. */
fun Bundle.withActions(actions: Map<String, AutomationAction>): Bundle {
    val schema = model.schemas.getValue("automation") as AutomationSchema
    return ApprovalDependencies.prepare(
        copy(
            actions = actions,
            model =
                model.copy(
                    schemas =
                        model.schemas +
                            (
                                "automation" to
                                    schema.copy(
                                        objects =
                                            schema.objects.filterValues { it !is AutomationAction } +
                                                actions.values.associateBy { it.qname },
                                    )
                            ),
                ),
        ),
    )
}
