// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.scope

import org.tatrman.metadata.grpc.toProto
import org.tatrman.translate.snapshot.BindingNames
import org.tatrman.ttr.metadata.model.ModelObject
import org.tatrman.ttr.metadata.registry.RegistrySnapshot
import org.tatrman.ttr.metadata.source.LoadedFile

/** Package provenance captured from the same load as the model. */
internal fun bindingPackages(files: List<LoadedFile>): (String) -> String =
    packagesBySource(files.map { it.storageFile.path to (it.declaredPackage ?: it.computedPackage) })

internal fun RegistrySnapshot.bindingPackages(): (String) -> String =
    packagesBySource(
        definitions.entries.map { entry ->
            val source = entry.def.source.file
            // Dialect fan-out adds #<model> to the storage path while keeping the authored span.
            val suffix = entry.file.substringAfterLast('/').removePrefix(source.substringAfterLast('/'))
            val path = if (suffix.startsWith('#')) source + suffix else source
            path to entry.pkg
        },
    )

private fun packagesBySource(entries: List<Pair<String, String>>): (String) -> String {
    val packages =
        entries.groupBy({ it.first }, { it.second }).mapValues { (_, names) ->
            names.distinct().singleOrNull().orEmpty()
        }
    return { packages[it].orEmpty() }
}

internal fun Collection<ModelObject>.bindingNames(packageOf: (String) -> String): BindingNames =
    BindingNames(map { it.qname.toProto() to packageOf(it.sourceFile) })
