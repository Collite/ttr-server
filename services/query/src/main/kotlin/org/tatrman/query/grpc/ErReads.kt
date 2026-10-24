// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.grpc

import com.google.protobuf.Descriptors.FieldDescriptor.JavaType
import com.google.protobuf.Message
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.query.v1.ErReadScope

/** Inspect the validated ER tree, never physical mappings or caller-supplied context audit entries. */
internal object ErReads {
    fun inspect(plan: PlanNode): ErReadScope {
        val objects = sortedSetOf<String>()

        fun read(name: QualifiedName) {
            require(name.schemaCode == SchemaCode.ER && name.namespace.isNotBlank() && name.name.isNotBlank()) {
                "read scope requires qualified ER objects"
            }
            objects += "er.${name.namespace}.${name.name}"
        }

        fun visit(message: Message) {
            // A newer plan/expression variant cannot silently look like an empty read set on an older Query.
            require(message.unknownFields.asMap().isEmpty()) { "unknown plan fields prevent read-scope inspection" }
            if (message is PlanNode) {
                when (message.nodeCase) {
                    PlanNode.NodeCase.SCAN -> read(message.scan.getObject())
                    PlanNode.NodeCase.PROJECT ->
                        if (message.project.hasExpandedFrom()) {
                            read(
                                message.project.expandedFrom,
                            )
                        }
                    PlanNode.NodeCase.TABLE_SCAN, PlanNode.NodeCase.WORKSPACE_REF,
                    PlanNode.NodeCase.STORE, PlanNode.NodeCase.NODE_NOT_SET,
                    ->
                        error("read scope requires a read-only ER tree")
                    PlanNode.NodeCase.FILTER, PlanNode.NodeCase.JOIN, PlanNode.NodeCase.UNION,
                    PlanNode.NodeCase.AGGREGATE, PlanNode.NodeCase.SORT, PlanNode.NodeCase.LIMIT_OFFSET,
                    PlanNode.NodeCase.SUBQUERY, PlanNode.NodeCase.VALUES,
                    -> Unit
                }
            }
            // Walk every populated message field, including nested expression subqueries, window operands,
            // unions and expanded logical bodies. This cannot omit a subtree when an operator gains a child.
            for ((field, value) in message.allFields) {
                if (field.javaType == JavaType.MESSAGE) {
                    if (field.isRepeated) {
                        (value as List<*>).forEach { visit(it as Message) }
                    } else {
                        visit(value as Message)
                    }
                }
            }
        }
        visit(plan)
        return ErReadScope.newBuilder().addAllObjects(objects).build()
    }
}
