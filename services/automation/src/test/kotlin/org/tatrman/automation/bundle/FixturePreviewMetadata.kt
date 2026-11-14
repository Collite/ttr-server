// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.bundle

import org.tatrman.ttr.metadata.model.AutomationColumn
import org.tatrman.ttr.metadata.model.AutomationDuration
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttr.metadata.model.LocalizedText
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Path

/**
 * Synthetic data for the existing typed planner tests, retained until S3.2 removes the legacy planner paths.
 * These values never pass through /refresh. Since S3.1 (⚑AU-D3-1, review-247 N4) the engine actions keep their real
 * engine-only sinks: the preview projects them as `engine` actions with no batch rows, the path the hero bundles take.
 */
fun legacyPreviewMetadata(bundle: Bundle): org.tatrman.automation.apply.PreviewMetadata {
    val er =
        FileBasedSource(
            "typed-preview",
            100,
            LocalFsStorage("typed-preview", Path.of("src/test/resources/typed-preview")),
        ).load()
    val schema = bundle.model.schemas.getValue("er") as ErSchema
    val model =
        bundle.model.copy(
            schemas =
                bundle.model.schemas + ("er" to schema.copy(entities = schema.entities + er.entities)),
        )
    val actions = bundle.actions.filterKeys { it !in setOf("release_confirmed", "decide_way") }.toMutableMap()
    val prototype = actions.getValue("notify")
    actions["ask"] =
        prototype.copy(
            internalId = "typed-preview-ask",
            qname = prototype.qname.copy(name = "ask"),
            properties = emptyList(),
            columns =
                listOf(
                    AutomationColumn("who", "role"),
                    AutomationColumn("about", "text"),
                    AutomationColumn("action", "action"),
                    AutomationColumn("detail", "rows", optional = true),
                ),
            sinks = mapOf("process_engine" to "user task"),
            approval = "required",
            deliverable = false,
            due = AutomationDuration(2.0, "working_days"),
            dryRun = LocalizedText.EMPTY,
        )
    actions["task"] =
        actions.getValue("ask").copy(
            internalId = "typed-preview-task",
            qname = prototype.qname.copy(name = "task"),
            columns = actions.getValue("ask").columns.filterNot { it.name == "action" },
            approval = "none",
            due = null,
        )
    actions["start"] =
        prototype.copy(
            internalId = "typed-preview-start",
            qname = prototype.qname.copy(name = "start"),
            properties = emptyList(),
            columns = listOf(AutomationColumn("process", "text")),
            sinks = mapOf("process_engine" to "call activity"),
            deliverable = false,
            dryRun = LocalizedText.EMPTY,
        )
    val synthetic =
        bundle
            .copy(
                model = model,
                packages = bundle.packages + er.entities.values.associate { it.sourceFile to "hartland" },
            ).withActions(actions)
    val recipe = ManifestRecipe("preview_fixture", wiring = actions.keys.map { ManifestWiring(action = it) })
    val checked = ApprovalDependencies.check(synthetic, recipe)
    require(checked.refusal == null || checked.defaultsWithoutEvidence) { checked.refusal.orEmpty() }
    return org.tatrman.automation.apply
        .PreviewMetadata(checked.actions, synthetic.entities, checked.targets)
}
