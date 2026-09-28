// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.grpc

import org.tatrman.ttr.metadata.source.ModelSource
import org.tatrman.ttr.metadata.source.SourceSnapshot
import java.util.concurrent.ConcurrentHashMap

/**
 * ttr-server#111 — the package each model file belongs to, as the loader decided it.
 *
 * A file's package is its declared `package` clause, else the package its directory implies:
 * `declaredPackage ?: computedPackage`, the rule the published resolver applies. GetModel and the
 * `package` filters of ListObjects / ListQueries scope by this, never by the file's path. A path
 * matches every directory named like a package, the checkout directory included: a git source
 * checked out to `/tmp/metadata-git/<package>` put every file of the repository under
 * `/<package>/`, so a request for that one package returned all of them.
 *
 * The loader knows each file's package but the reconciled model does not keep it, so the index is
 * fed by the sources themselves ([recording]). It holds the latest successful load of each source
 * slot, which is what the refresher reconciles: a failed load keeps the prior snapshot on both
 * sides. A file the index does not know belongs to no package — membership fails closed.
 */
class PackageIndex {
    /** Per source slot: absolute file path → package. */
    private val bySlot = ConcurrentHashMap<String, Map<String, String>>()

    /**
     * Record the files of one load. Keyed by the source SLOT, not [SourceSnapshot.sourceId]: a
     * `FallbackSource` answers with its fallback's id, and that load must replace the primary's.
     */
    fun record(
        snapshot: SourceSnapshot,
        slotId: String = snapshot.sourceId,
    ) {
        bySlot[slotId] =
            snapshot.loadedFiles.associate { it.storageFile.path to (it.declaredPackage ?: it.computedPackage) }
    }

    /** The package of [sourceFile], or null when no recorded load contains that file. */
    fun packageOf(sourceFile: String): String? = bySlot.values.firstNotNullOfOrNull { it[sourceFile] }

    /** True when [sourceFile] belongs to [packageName]. A blank name matches nothing. */
    fun contains(
        packageName: String,
        sourceFile: String,
    ): Boolean = packageName.isNotEmpty() && packageOf(sourceFile) == packageName

    /** [source] with every successful load recorded into this index under [slotId]. */
    fun recording(
        slotId: String,
        source: ModelSource,
    ): ModelSource = ModelSource { source.load().also { record(it, slotId) } }
}
