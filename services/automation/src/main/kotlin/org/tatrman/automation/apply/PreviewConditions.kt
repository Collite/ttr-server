// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

import org.tatrman.automation.runner.BoundValue
import org.tatrman.automation.runner.Table

/** Compiler-lowered predicates over one verified route snapshot; no door or model connection. */
internal object PreviewConditions {
    fun select(
        expression: String,
        table: Table,
        bindings: Map<String, BoundValue>,
    ): Set<Int> = PreviewExpressions.select(expression, table, bindings)
}
