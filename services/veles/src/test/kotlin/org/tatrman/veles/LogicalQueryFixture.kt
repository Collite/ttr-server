// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles

import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.reconcile.ModelReconciler
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Path

/**
 * LQ — the `fixture-lq/` model: the Hartland logical-query sample of the ttr-core repo (`samples/2.1/logical-query`:
 * the logical query `er.query.shopper_revenue` over `sale ⋈ shopper`, the entity stacked on it and its `join:`
 * relation) plus the shapes the parse worker refuses (`refusals.ttrm`) and two db-lane queries in a `model query`
 * file, the way the Hartland model declares its saved queries (`queries.ttrm`).
 */
internal object LogicalQueryFixture {
    fun model(): Model = load("fixture-lq")

    /**
     * LQ §8 — `fixture-lq-refs/`: the same sample plus saved queries that read other saved queries as
     * `obj.query.<name>` (`refs.ttrm`, logical; `queries.ttrm`, `model query`) and the shapes the worker refuses.
     */
    fun refsModel(): Model = load("fixture-lq-refs")

    private fun load(dir: String): Model {
        val sample = checkNotNull(LogicalQueryFixture::class.java.classLoader.getResource("$dir/hartland"))
        val root = Path.of(sample.toURI()).parent
        val source =
            FileBasedSource(
                sourceId = dir,
                priority = 100,
                storage = LocalFsStorage(id = dir, rootPath = root),
            )
        return ModelReconciler(ModelDescriptor(id = "lq", name = "lq", description = "logical-query fixture"))
            .reconcile(listOf(source.load()))
            .model
    }
}
