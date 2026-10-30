// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.ttr.metadata.model.Entity

/** Both native obligation paths require the same compiler-injected schema. */
internal object ObligationSchema {
    // Core's ModelIndex predicate uses this private root too; synthetic legacy metadata has no prepared index.
    private const val STDLIB_ROOT = "/automation-stdlib/"

    fun resolve(metadata: PreviewMetadata): Entity {
        val entity = metadata.entities.singleOrNull { it.qname.dotted() == "er.entity.obligation" } ?: unsupported()
        if (!(metadata.isAutomationStdlib?.invoke(entity) ?: entity.sourceFile.startsWith(STDLIB_ROOT)) ||
            PreviewObligation.FIELDS.any { (name, type) ->
                entity.attributes.singleOrNull { it.qname.name.substringAfterLast('.') == name }?.type != type
            } ||
            entity.attributes.filter { it.isKey }.map { it.qname.name.substringAfterLast('.') } != listOf("id")
        ) {
            unsupported()
        }
        return entity
    }

    private fun unsupported(): Nothing =
        throw AutomationException(ErrorCode.PREVIEW_UNSUPPORTED, "unsupported stdlib obligation schema")
}
