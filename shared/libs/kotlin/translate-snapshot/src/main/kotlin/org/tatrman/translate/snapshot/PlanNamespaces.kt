// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translate.snapshot

import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.translator.joiner.JoinerPlanWalker

/**
 * Request-local catalog view (AU-S25-3): Dispatch has supplied the connection's schema in the plan.
 * Calcite enumerates namespaces before looking up tables, so include those concrete DB namespaces
 * without mutating the captured snapshot or guessing a schema for a namespace-free placed read.
 */
fun withPlanNamespaces(
    handle: ModelHandle,
    plan: PlanNode,
): ModelHandle {
    val namespaces = linkedSetOf<String>()

    fun collect(name: QualifiedName) {
        if (name.schemaCode == SchemaCode.DB && name.namespace.isNotBlank()) namespaces += name.namespace
    }

    JoinerPlanWalker.forEachNode(plan) { node ->
        when (node.nodeCase) {
            PlanNode.NodeCase.TABLE_SCAN -> collect(node.tableScan.table)
            PlanNode.NodeCase.SCAN -> collect(node.scan.`object`)
            PlanNode.NodeCase.STORE -> collect(node.store.target)
            PlanNode.NodeCase.ROW_WRITES -> node.rowWrites.rowsList.forEach { collect(it.target) }
            else -> Unit
        }
    }
    val all = handle.namespaces(SchemaCode.DB) + namespaces
    return object : ModelHandle by handle {
        override fun namespaces(schemaCode: SchemaCode): Set<String> =
            if (schemaCode == SchemaCode.DB) all else handle.namespaces(schemaCode)
    }
}
