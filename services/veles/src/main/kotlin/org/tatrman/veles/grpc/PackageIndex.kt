// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.grpc

import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.ModelSource
import org.tatrman.ttr.metadata.source.SourceSnapshot
import java.util.concurrent.ConcurrentHashMap

/**
 * ttr-server#111 — the package each model file belongs to, as the loader decided it.
 *
 * A file's package is its declared `package` clause, else the package its directory implies:
 * `declaredPackage ?: computedPackage`, the rule the published resolver applies. The implied
 * package is the file's directory relative to the model root, dotted — `a/b/x.ttr` is `a.b`, not
 * `a`; a root-level file implies none. GetModel, the `package` filters of ListObjects /
 * ListQueries and the read routes scope by this, never by the file's path. A path matches every
 * directory named like a package, the checkout directory included: a git source checked out to
 * `/tmp/metadata-git/<package>` put every file of the repository under `/<package>/`, so a request
 * for that one package returned all of them.
 *
 * The loader knows each file's package but the reconciled model does not keep it, so the sources
 * feed the index ([recording]) and the registry publishes it ([commitOnSwap]). A load is only
 * staged: the index keeps answering for the model the registry serves until the registry swaps in
 * a new one, and only then promotes the latest successful load of every slot — the snapshots the
 * refresher has just reconciled. So a load whose model never arrives (the reconcile or the swap
 * failed) changes nothing, and neither does the stretch between a load and its swap, while the new
 * files are already on disk and the old model is still served.
 *
 * A file the index does not know belongs to no package — membership fails closed.
 */
class PackageIndex {
    /** Per source slot, its latest successful load: absolute file path → package. Not served yet. */
    private val loaded = ConcurrentHashMap<String, Map<String, String>>()

    /** Absolute file path → package, for the model the registry serves. Replaced whole by [commit]. */
    @Volatile
    private var served: Map<String, String> = emptyMap()

    /**
     * Stage the files of one load. Keyed by the source SLOT, not [SourceSnapshot.sourceId]: a
     * `FallbackSource` answers with its fallback's id, and that load must replace the primary's.
     */
    fun record(
        snapshot: SourceSnapshot,
        slotId: String = snapshot.sourceId,
    ) {
        loaded[slotId] =
            snapshot.loadedFiles.associate { it.storageFile.path to (it.declaredPackage ?: it.computedPackage) }
    }

    /** Serve the staged loads — call when their reconciled model goes live. See [commitOnSwap]. */
    fun commit() {
        served = buildMap { loaded.values.forEach { putAll(it) } }
    }

    /**
     * Commit on every model [registry] swaps in. Register this before the registry's other
     * listeners: the swap publishes the model first and notifies after, so until this listener
     * runs a request can meet the new model with the previous packages.
     */
    fun commitOnSwap(registry: MetadataRegistry): PackageIndex = apply { registry.addListener { commit() } }

    /** The package of [sourceFile] in the served model, or null when no served load contains it. */
    fun packageOf(sourceFile: String): String? = served[sourceFile]

    /** True when [sourceFile] belongs to [packageName]. A blank name matches nothing. */
    fun contains(
        packageName: String,
        sourceFile: String,
    ): Boolean = packageName.isNotEmpty() && packageOf(sourceFile) == packageName

    /** [source] with every successful load staged into this index under [slotId]. */
    fun recording(
        slotId: String,
        source: ModelSource,
    ): ModelSource = ModelSource { source.load().also { record(it, slotId) } }
}
