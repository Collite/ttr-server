// SPDX-License-Identifier: Apache-2.0
package org.tatrman.validate.stages

import com.google.protobuf.Message
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType
import org.tatrman.plan.v1.ColumnRef

/** All referenced predicate columns, including nested expressions. No write expression is rewritten. */
internal object RowWriteColumns {
    fun names(message: Message): Set<String> =
        buildSet {
            fun visit(value: Message) {
                if (value is ColumnRef) add(value.name)
                for ((field, child) in value.allFields) {
                    if (field.javaType == JavaType.MESSAGE) {
                        if (field.isRepeated) {
                            (child as List<*>).forEach { visit(it as Message) }
                        } else {
                            visit(child as Message)
                        }
                    }
                }
            }
            visit(message)
        }
}
