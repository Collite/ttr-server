// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

/**
 * LP-P0 — which TATRMAN scorer the service runs (`fuzzy.match.version`, contracts §4.1).
 * [V1] is today's [TokenBasedMatcher], byte-pinned; [V2] is [TokenBasedMatcherV2]. Default [V1]
 * until the LP-P3 golden verdict (LPA-2) — the goldens flip it, not a date.
 */
enum class MatchVersion(
    val wire: String,
) {
    V1("v1"),
    V2("v2"),
    ;

    /** Throws when this version cannot run on [retrieval] — v2 scores on index-first only. */
    fun requireCompatible(retrieval: RetrievalMode) {
        require(this != V2 || retrieval == RetrievalMode.INDEX_FIRST) {
            "fuzzy.match.version=v2 requires index-first retrieval (fuzzy.token-based.retrieval=index-first), " +
                "got ${retrieval.name.lowercase().replace('_', '-')}"
        }
    }

    companion object {
        /**
         * `v1` · `v2` (case-insensitive); blank ⇒ [V1]. Anything else is an ERROR, unlike
         * [RetrievalMode.fromString]'s silent default: a typo here would quietly run the engine the
         * operator believes they switched off.
         */
        fun fromString(value: String?): MatchVersion {
            if (value.isNullOrBlank()) return V1
            return entries.firstOrNull { it.wire == value.trim().lowercase() }
                ?: throw IllegalArgumentException("fuzzy.match.version must be v1 or v2, got '$value'")
        }
    }
}

/**
 * LP-P0 (contracts §4.6) — how one query token matched one candidate token. [kind] is the
 * [MatchKind] in wire form (`exact` · `typo` · `prefix`); positions are 0-based token indices on
 * the axis that won (surface or lemma).
 */
data class TokenHit(
    val queryToken: String,
    val candidateToken: String,
    val kind: String,
    val distance: Int,
    val queryPos: Int,
    val candidatePos: Int,
)

/** A rescored candidate: the score plus the v2 provenance (empty/null from v1). */
data class Scored(
    val candidate: Candidate,
    val score: Double,
    val tokenHits: List<TokenHit> = emptyList(),
    val coverage: Double? = null,
)

/**
 * LP-P0·S2 T1 — the rescore seam [FuzzyMatcher] selects by [MatchVersion]: exact-rescore an
 * explicit candidate list on both query axes and return the top [limit], best first (stable
 * descending sort, ties keep input order).
 */
interface TokenScorer {
    fun score(
        querySurfaceTokens: List<String>,
        queryLemmaTokens: List<String>,
        candidates: List<Candidate>,
        limit: Int,
    ): List<Scored>
}
