// SPDX-License-Identifier: Apache-2.0
package consumer

import org.tatrman.grounding.core.PlanExpr
import org.tatrman.grounding.core.SqlRenderer

/** Compiles against `grounding-core`: a plan.v1 Expression built and rendered through the kernel. */
object CoreConsumer {
    fun preview(): String =
        SqlRenderer.render(
            PlanExpr.ge(PlanExpr.col("t", "date"), PlanExpr.param("from", "datetime")),
        )
}
