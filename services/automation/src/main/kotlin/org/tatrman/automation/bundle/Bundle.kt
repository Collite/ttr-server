// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.bundle

import org.tatrman.automation.identity.GrantDecl
import org.tatrman.automation.identity.IdentityDecl
import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttr.metadata.model.AutomationProgram
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.WorldDeployment
import org.tatrman.ttr.metadata.model.WorldProcess
import java.nio.file.Path
import java.time.Instant

/** What a bundle is to the registry (D-111): the one the deployments run, or a branch loaded beside it. */
enum class BundleKind(
    val wire: String,
) {
    SERVED("served"),
    PREVIEW("preview"),
}

/**
 * One checked-out commit of the automation repo: [root] holds `model/` and `automation/` (the config's
 * `bundle.git.path` under the work tree). The author is the commit's — the deployer of D-90.
 */
data class Checkout(
    val root: Path,
    val branch: String,
    val commit: String,
    val authorName: String?,
    val authorEmail: String?,
)

/**
 * A recipe bundle as loaded (`automation/<r>/<r>.bundle/`): its manifest, the recipe block, and every island
 * statement read into memory, keyed by the file name the manifest gives (`islands/overdue.rows.sql`).
 */
data class LoadedRecipe(
    val name: String,
    val manifest: RecipeManifest,
    val recipe: ManifestRecipe,
    val statements: Map<String, String>,
)

/**
 * The served view of one commit — immutable; `/refresh` swaps whole bundles, never parts (D-47). [model] is the
 * `ttr-metadata` snapshot of `model/` + `automation/` (the generated `catalog/<recipe>.ttrm` of every recipe bundle
 * included); the typed lists below are read out of it. [programFps]/[deploymentFps] are D-60's program and
 * deployment fingerprints ([Fingerprints]); a recipe's two are in its manifest. [author] is the deployer's
 * principal (the commit author through `AUTOMATION_AUTHOR_MAP`). [warnings] are the load findings that do not
 * refuse the bundle (the model's own issues are Veles's to serve).
 */
data class Bundle(
    val id: String,
    val kind: BundleKind,
    val branch: String,
    val commit: String,
    val author: String,
    val modelFp: String,
    val loadedAt: Instant,
    val model: Model,
    val recipes: Map<String, LoadedRecipe>,
    val programs: Map<String, AutomationProgram>,
    val actions: Map<String, AutomationAction>,
    val deployments: Map<String, WorldDeployment>,
    val processes: Map<String, WorldProcess>,
    val identities: List<IdentityDecl>,
    val grants: List<GrantDecl>,
    /** World executor name → its executor type id (`n8n` → `n8n_l1`); `native` is implicit. */
    val executors: Map<String, String>,
    val programFps: Map<String, String>,
    val deploymentFps: Map<String, String>,
    val warnings: List<String>,
) {
    /** The executor type a deployment's `target:` runs on: `native`, or its world executor's `extends:`. */
    fun targetType(deployment: WorldDeployment): String? =
        if (deployment.target == NATIVE) NATIVE else executors[deployment.target]

    /** The programs a deployment runs: its own, or every step's of its process. */
    fun programsOf(deployment: WorldDeployment): List<String> =
        deployment.program?.let { listOf(it) }
            ?: processes[deployment.process]?.steps?.mapNotNull { it.program }.orEmpty()

    companion object {
        const val NATIVE = "native"
    }
}

/** `422 bundle-invalid`: the refresh refused the commit; [diagnostics] say why, one finding each. */
class BundleInvalidException(
    val diagnostics: List<String>,
) : RuntimeException("bundle invalid: ${diagnostics.size} finding(s): ${diagnostics.take(3).joinToString("; ")}")
