// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.apply

import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.runner.RunContext
import org.tatrman.automation.runner.BoundValue
import java.time.ZoneOffset
import org.tatrman.ttr.metadata.model.AutomationAction
import org.tatrman.ttr.parser.model.AutoValue

/** Explicit defaults carried by the published metadata's free-form action properties. */
internal object PreviewWriterDefaults {
    data class Defaults(
        val expressions: Map<String, String>,
        val values: Map<String, BoundValue>,
    )

    fun resolve(
        action: AutomationAction,
        context: RunContext,
    ): Defaults {
        val declarations = action.properties.filter { it.first == "writer_defaults" }
        if (declarations.isEmpty()) return Defaults(emptyMap(), emptyMap())
        if (declarations.size != 1 || action.sinks["native"] != "writer") unsupported()
        val defaults = (declarations.single().second as? AutoValue.Obj)?.entries ?: unsupported()
        if (defaults.map { it.key }.distinct().size != defaults.size) unsupported()
        val expressions = linkedMapOf<String, String>()
        val values = linkedMapOf<String, BoundValue>()
        for (entry in defaults) {
            // Optional at the call site; target key/nullability requirements still apply after defaults.
            val column = action.columns.singleOrNull { it.name == entry.key } ?: unsupported()
            if (!column.optional) unsupported()
            val source = (entry.value as? AutoValue.Obj)?.entries?.singleOrNull() ?: unsupported()
            when (source.key) {
                "expression" ->
                    expressions[entry.key] =
                        (source.value as? AutoValue.Str)?.raw?.takeIf { it.isNotBlank() } ?: unsupported()
                "context" ->
                    values[entry.key] =
                        when ((source.value as? AutoValue.Id)?.parts?.joinToString(".")) {
                            "run_id" -> BoundValue("text", context.id.toString())
                            // Preserve the exact committed Open instant; no preview clock or timestamp parsing.
                            "started_at" -> BoundValue("datetime", context.startedAt.atOffset(ZoneOffset.UTC))
                            else -> unsupported()
                        }
                else -> unsupported()
            }
        }
        return Defaults(expressions, values)
    }

    private fun unsupported(): Nothing =
        throw AutomationException(ErrorCode.PREVIEW_UNSUPPORTED, "unsupported native writer defaults")
}
