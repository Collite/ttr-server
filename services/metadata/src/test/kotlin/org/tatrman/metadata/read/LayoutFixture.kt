// SPDX-License-Identifier: Apache-2.0
package org.tatrman.metadata.read

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
 * VL-P2 — `src/test/resources/layouts`, loaded the way the service loads a model (a source asked for its
 * layouts, as `buildSources` builds it): packages `hartland` and `outlet` (a second `item`) and nine `.ttrg`
 * files under `layouts/` — six served; `broken`, and `dup_a` + `dup_b` (one name, two files: A-VL-7) as issues.
 * The same tree as ttr-core's `ttr-metadata` `layout-fixture`. The test working dir is the module dir.
 */
internal val LAYOUT_FIXTURE: Path = Path.of("src/test/resources/layouts").toAbsolutePath()

internal data class LoadedLayoutFixture(
    val registry: MetadataRegistry,
    val model: Model,
    val graph: ModelGraph,
)

internal fun loadLayoutFixture(): LoadedLayoutFixture {
    val source = FileBasedSource("fixture", 100, LocalFsStorage("fixture", LAYOUT_FIXTURE), layouts = true)
    val snapshots = listOf(source.load())
    val model = ModelReconciler(ModelDescriptor(id = "layouts", name = "layouts")).reconcile(snapshots).model
    val graph = ModelGraph.build(model)
    val registry = MetadataRegistry()
    registry.swap(model, graph, layouts = LayoutIndex.build(snapshots, model))
    return LoadedLayoutFixture(registry, model, graph)
}
