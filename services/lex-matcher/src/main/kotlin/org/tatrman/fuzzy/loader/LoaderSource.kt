// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import org.tatrman.fuzzy.core.Candidate
import org.tatrman.fuzzy.core.LoaderWarningInfo

interface LoaderSource {
    /**
     * Returns the next cache contents, or `null` if the load failed and the previous
     * cache should be preserved.
     */
    suspend fun loadNextCache(): Map<String, List<Candidate>>?

    /**
     * B-T4 loader report: structured warnings from the last load (e.g. a declared
     * member vocabulary skipped for lacking a single key — `RG-FUZ-001`). Surfaced via
     * `GetStatus` so estates learn which declared vocabularies aren't searchable.
     * Default: none (the static source has no skips).
     */
    fun warnings(): List<LoaderWarningInfo> = emptyList()

    /**
     * MV-T2 — per member category, the identity of the read plan that produced it (Veles'
     * `MemberVocabulary.version`: model version, read plan, key, method), from the last successful
     * load, keyed as [loadNextCache] returned the categories. The repository folds it into the
     * category's RV-39 version beside the content, so a changed plan moves the version even when it
     * happens to read the same rows. Default: none — a source with no read plan versions by content.
     */
    fun planVersions(): Map<String, String> = emptyMap()
}
