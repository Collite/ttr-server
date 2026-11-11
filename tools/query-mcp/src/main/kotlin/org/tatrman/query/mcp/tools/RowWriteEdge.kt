// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.mcp.tools

import org.tatrman.plan.v1.PlanNode
import org.tatrman.translate.v1.Language

/** Human-facing tools never accept the door's service-only write wire. */
internal fun isRowWriteSource(
    source: String,
    language: Language,
): Boolean =
    language == Language.REL_NODE &&
        runCatching {
            PlanNode.parseFrom(source.toByteArray(Charsets.ISO_8859_1)).nodeCase == PlanNode.NodeCase.ROW_WRITES
        }.getOrDefault(false)
