// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.grpc

import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Message
import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.plan.v1.ParameterRef
import org.tatrman.plan.v1.PipelineContext
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.Value

/**
 * BI (contracts C-11) — every parameter the plan that runs reads has a value. A `ParameterRef` whose binding in
 * `PipelineContext.parameters` is missing, or present without a value, is `parameter_unbound`: the worker would
 * bind it as NULL and answer zero rows without a word (`city = NULL` matches nothing). An explicit NULL
 * (`Value.is_null`) is a value — the caller asked for it. Run (and so RunSavedQuery) refuses; Compile does not,
 * because it reports referenced parameters (`required_parameters`) with only the current caller's values.
 * Binding names match exactly, as in the worker binder; only plan reference names are unbraced.
 *
 * The plan is read by protobuf reflection, so a parameter is found wherever an expression can hold one (a
 * projection, a filter, a join condition, a subquery, an aggregate's or a window's operands). A positional name the
 * translator could not restore (`?N`) has no name to bind by and is not checked.
 */
internal object ParameterCheck {
    const val PARAMETER_UNBOUND = "parameter_unbound"

    /** The names of the plan's parameters with no value, in the order the plan first reads them. */
    fun unbound(
        plan: PlanNode,
        context: PipelineContext,
    ): List<String> {
        val bindings = context.parametersList.associateBy { it.name }
        return referencedNames(plan).filter { name -> bindings[name]?.hasUsableValue() != true }
    }

    /** Referenced names in plan order; types may come from this parse, values only from this caller. */
    fun required(
        plan: PlanNode,
        context: PipelineContext,
        translatedContext: PipelineContext = PipelineContext.getDefaultInstance(),
    ): List<ParameterBinding> {
        val bindings = context.parametersList.associateBy { it.name }
        val translated = translatedContext.parametersList.associateBy { it.name }
        return referencedNames(plan).map { name ->
            val own = bindings[name]
            ParameterBinding
                .newBuilder()
                .setName(name)
                .setType(own?.type ?: translated[name]?.type.orEmpty())
                .apply { if (own?.hasValue() == true) setValue(own.value) }
                .build()
        }
    }

    fun message(names: List<String>): String =
        if (names.size == 1) {
            "Parameter '${names.single()}' has no value; bind it, or send an explicit NULL."
        } else {
            "Parameters ${names.joinToString { "'$it'" }} have no value; bind them, or send an explicit NULL."
        }

    fun referencedNames(plan: PlanNode): List<String> {
        val names = LinkedHashSet<String>()

        fun visit(message: Message) {
            if (message is ParameterRef) {
                message.name
                    .unbraced()
                    .takeIf { it.isNotEmpty() && !it.startsWith("?") }
                    ?.let(names::add)
                return
            }
            message.allFields.forEach { (field, value) ->
                when {
                    field.javaType != FieldDescriptor.JavaType.MESSAGE -> Unit
                    field.isRepeated -> (value as List<*>).forEach { visit(it as Message) }
                    else -> visit(value as Message)
                }
            }
        }
        visit(plan)
        return names.toList()
    }

    private fun ParameterBinding.hasUsableValue(): Boolean =
        hasValue() && (value.isNull || value.vCase != Value.VCase.V_NOT_SET)

    private fun String.unbraced(): String = removePrefix("{").removeSuffix("}")
}
