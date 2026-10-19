// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.bundle

/**
 * The approvals a deployment's staleness reads (D-60): is there an `approved` row of this kind for this object
 * under exactly this fingerprint. Kinds: `recipe` (the text fp), `values` (the values fp), `program`, `deployment`.
 * The ledger's `approvals` table answers in the service; P2 writes the rows.
 */
fun interface ApprovalReader {
    fun approved(
        kind: String,
        obj: String,
        fp: String,
    ): Boolean
}

/** Which of a deployment's fingerprints lack an approval — empty = not stale. */
data class Staleness(
    val unapproved: List<String>,
) {
    val stale: Boolean get() = unapproved.isNotEmpty()

    companion object {
        /** D-60's three: the recipe (text + values), the program, the deployment itself. */
        fun of(
            bundle: Bundle,
            deployment: String,
            approvals: ApprovalReader,
        ): Staleness {
            val d = bundle.deployments[deployment] ?: return Staleness(listOf("deployment"))
            val missing = mutableListOf<String>()
            for (p in bundle.programsOf(d)) {
                val program = bundle.programs[p]
                val recipe = program?.recipe?.let { bundle.recipes[it] }
                if (recipe != null) {
                    val fps = recipe.recipe.fingerprints
                    if (!approvals.approved("recipe", recipe.name, fps.text)) missing += "recipe ${recipe.name}"
                    if (!approvals.approved("values", recipe.name, fps.values)) missing += "values ${recipe.name}"
                }
                val fp = bundle.programFps[p]
                if (fp == null || !approvals.approved("program", p, fp)) missing += "program $p"
            }
            val fp = bundle.deploymentFps[deployment]
            if (fp == null || !approvals.approved("deployment", deployment, fp)) missing += "deployment $deployment"
            return Staleness(missing.distinct())
        }
    }
}
