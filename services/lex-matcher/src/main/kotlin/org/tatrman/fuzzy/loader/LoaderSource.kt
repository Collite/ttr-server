// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import org.tatrman.fuzzy.core.Candidate
import org.tatrman.fuzzy.core.LoaderWarningInfo

/**
 * One member load and what describes it, handed over together (review-104 F15).
 *
 * @property categories the member rows by category, keyed as the source spells them.
 * @property planVersions MV-T2 — per category, the identity of the read plan that produced it
 *   (A-MV-10). The repository folds it into the category's RV-39 version beside the content, so a
 *   changed plan moves the version even when it happens to read the same rows. Empty for a source
 *   with no read plans, which versions by content alone.
 */
data class MemberLoad(
    val categories: Map<String, List<Candidate>>,
    val planVersions: Map<String, String> = emptyMap(),
)

interface LoaderSource {
    /**
     * Returns the next cache contents, or `null` if the load failed and the previous
     * cache should be preserved.
     */
    suspend fun loadNextCache(): Map<String, List<Candidate>>?

    /**
     * The next member load, or `null` when there is none to take (the previous member layer is kept).
     *
     * What the repository calls. A source whose load carries more than rows overrides this, so the
     * extra facts arrive WITH the rows they describe — never through a getter read afterwards, which
     * another load could have overwritten in between. The default is [loadNextCache] alone.
     */
    suspend fun load(): MemberLoad? = loadNextCache()?.let { MemberLoad(it) }

    /**
     * B-T4 loader report: structured warnings from the last load (e.g. a declared
     * member vocabulary skipped for lacking a single key — `RG-FUZ-001`). Surfaced via
     * `GetStatus` so estates learn which declared vocabularies aren't searchable.
     * Default: none (the static source has no skips).
     */
    fun warnings(): List<LoaderWarningInfo> = emptyList()
}
