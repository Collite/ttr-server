// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.read

import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.ttr.metadata.layout.LayoutIndex
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Path

/**
 * VL-P2 — `src/test/resources/layouts`, loaded the way the service loads a model: packages `hartland` and
 * `outlet` (a second `item`) and eight `.ttrg` files under `layouts/` — six served, `broken` and `dup_b`
 * (a taken name) as issues. The test working dir is the module dir.
 */
internal val LAYOUT_FIXTURE: Path = Path.of("src/test/resources/layouts").toAbsolutePath()

internal data class LoadedLayoutFixture(
    val registry: MetadataRegistry,
    val model: Model,
    val graph: ModelGraph,
)

internal fun loadLayoutFixture(): LoadedLayoutFixture {
    val snapshots = listOf(FileBasedSource("fixture", 100, LocalFsStorage("fixture", LAYOUT_FIXTURE)).load())
    val model = ModelReconciler(ModelDescriptor(id = "layouts", name = "layouts")).reconcile(snapshots).model
    val graph = ModelGraph.build(model)
    val registry = MetadataRegistry()
    registry.swap(model, graph, layouts = LayoutIndex.build(snapshots, model))
    return LoadedLayoutFixture(registry, model, graph)
}
