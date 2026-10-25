// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.bundle

/** Legacy preview tests explicitly construct their metadata from a checked synthetic wiring scope. */
fun legacyPreviewMetadata(bundle: Bundle): org.tatrman.automation.apply.PreviewMetadata {
    val recipe = ManifestRecipe("preview_fixture", wiring = bundle.actions.keys.map { ManifestWiring(action = it) })
    val checked = ApprovalDependencies.check(bundle, recipe)
    require(checked.refusal == null || checked.defaultsWithoutEvidence) { checked.refusal.orEmpty() }
    return org.tatrman.automation.apply
        .PreviewMetadata(checked.actions, bundle.entities, checked.targets)
}
