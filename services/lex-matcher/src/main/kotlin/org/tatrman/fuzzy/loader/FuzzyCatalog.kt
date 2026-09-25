// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tatrman.fuzzy.core.Candidate
import org.slf4j.LoggerFactory

/**
 * In-repo entity catalog for the lean-fuzzy v1.
 *
 * The catalog is a single JSON file shipped at `src/main/resources/fuzzy-catalog.json`:
 *
 * ```json
 * {
 *   "version": 1,
 *   "methods": { "product": "TOKENS" },
 *   "categories": {
 *     "product": [
 *       { "id": "P001", "value": "Widget Mk I" },
 *       { "id": "P002", "value": "Widget Mk II" }
 *     ],
 *     "customer": [
 *       { "id": "C001", "value": "Acme Corp" }
 *     ]
 *   }
 * }
 * ```
 *
 * MV-T2 — `methods` gives a category's member rows their match method (`EXACT` · `TYPOS(n)` ·
 * `TOKENS`), as Veles gives a member vocabulary its own. A category it does not name is `EXACT`: a
 * member value nobody declared a slack for is matched exactly, the same rule an indexed attribute
 * with no authored method follows (above, `customer`). Declared-term rows (`targetRef`) are not
 * member rows and carry no category method.
 *
 * Each `value` is folded (NFD + lowercase) at load time so the matcher sees
 * the same tokens it would see for a query. The ai-platform `fuzzy.queries`
 * SQL block is gone — the catalog is a closed test set, not a query against
 * a warehouse.
 */
object FuzzyCatalog {
    private val log = LoggerFactory.getLogger(FuzzyCatalog::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class CatalogFile(
        val version: Int = 1,
        val methods: Map<String, String> = emptyMap(),
        val categories: Map<String, List<CatalogEntry>> = emptyMap(),
    )

    @Serializable
    private data class CatalogEntry(
        val id: String,
        val value: String,
        // Optional VOCABULARY tag: when set, the entry is a declared lexicon term
        // (source=VOCABULARY) carrying this target_ref — e.g. a branch/measure term —
        // instead of a data MEMBER. Absent ⇒ MEMBER (Candidate.fromValues).
        val targetRef: String? = null,
    )

    /**
     * Loads the catalog from the given classpath resource. Returns an empty
     * catalog (and logs a warning) if the resource is absent — Fuzzy will
     * boot, `isCatalogReady()` stays `false`, and `/ready` returns 503.
     */
    fun fromResource(resourcePath: String): Map<String, List<Candidate>> {
        val url = FuzzyCatalog::class.java.classLoader.getResource(resourcePath.removePrefix("/"))
        if (url == null) {
            log.warn("Fuzzy catalog resource not found: $resourcePath — service boots with empty catalog")
            return emptyMap()
        }
        val raw =
            try {
                url.openStream().use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (e: Exception) {
                log.error("Failed to read fuzzy catalog from $resourcePath", e)
                return emptyMap()
            }
        val parsed =
            try {
                json.decodeFromString(CatalogFile.serializer(), raw)
            } catch (e: Exception) {
                log.error("Failed to parse fuzzy catalog from $resourcePath", e)
                return emptyMap()
            }
        val out =
            parsed.categories.mapValues { (category, entries) ->
                val method = parsed.methods[category] ?: MatchMethodNames.EXACT
                entries.map {
                    if (it.targetRef != null) {
                        Candidate.vocabulary(it.id, it.value, it.targetRef)
                    } else {
                        // Built once with its method and category (review-104 F13): `copy` re-ran
                        // the constructor's fold, method parse and NFC form for every row.
                        Candidate.fromValues(it.id, it.value, method, category)
                    }
                }
            }
        log.info(
            "Fuzzy catalog loaded from $resourcePath: ${out.size} categories, ${out.values.sumOf {
                it.size
            }} candidates",
        )
        return out
    }
}
