// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import org.tatrman.fuzzy.core.Candidate

/**
 * In-memory static catalog loader — the lean-fuzzy v1 default.
 *
 * Reads a pre-baked `Map<category, List<Candidate>>` (the entity catalog
 * shipped as JSON in `src/main/resources/fuzzy-catalog.json` and parsed by
 * `FuzzyCatalog.fromResource(...)`) and returns it as the cache contents.
 *
 * The predecessor platform's `StaticLoaderSource` ran `SELECT pk, col FROM table` against
 * a live SQL backend for each query; the kantheon lean carve-out drops the
 * DB layer entirely at v1 (the catalog is a closed test set, not a query
 * against a warehouse). The metadata-driven loader remains in code for
 * forward compatibility — it just no-ops until a real SQL backend wires up.
 */
class StaticLoaderSource(
    private val catalog: Map<String, List<Candidate>>,
) : LoaderSource {
    override suspend fun loadNextCache(): Map<String, List<Candidate>>? = catalog
}
