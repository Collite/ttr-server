// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translate.snapshot

import org.tatrman.plan.v1.QualifiedName

/**
 * Core can retain a binding reference's package while storing the declaration under a bare key.
 * In that case the declaration's provenance must establish that the reference names that object.
 */
class BindingNames(
    declarations: Iterable<Pair<QualifiedName, String>>,
) {
    private val packages = declarations.groupBy({ it.first }, { it.second })

    fun resolve(
        reference: QualifiedName,
        keepMissingBare: Boolean = false,
    ): QualifiedName? {
        packages[reference]?.let { matches -> return reference.takeIf { matches.size == 1 } }
        if (reference.`package`.isEmpty()) return reference.takeIf { keepMissingBare }
        val bare = reference.toBuilder().clearPackage().build()
        return bare.takeIf { packages[it]?.singleOrNull() == reference.`package` }
    }
}
