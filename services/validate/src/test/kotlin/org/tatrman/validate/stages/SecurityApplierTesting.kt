// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.stages

import io.kotest.matchers.types.shouldBeInstanceOf
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode

/** [SecurityApplier.apply], expected to allow: the test fails, naming the result, when it was denied. */
internal suspend fun SecurityApplier.applied(
    plan: PlanNode,
    context: PipelineContext,
): SecurityApplier.Applied = apply(plan, context).shouldBeInstanceOf<SecurityApplier.Applied>()
