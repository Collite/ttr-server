// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

/**
 * LP-P0 — which TATRMAN scorer runs (`fuzzy.match.version`, contracts §4.1). [V1] is the pre-LP
 * [TokenBasedMatcher], byte-pinned; [V2] is [TokenBasedMatcherV2]. The LIBRARY default stays [V1]
 * (✅LP-23 — an embedder that never asks keeps the engine it was built against); the `lex-matcher`
 * SERVICE ships [V2] since LP-P3 (LPA-2) and passes that as its [fromString] default.
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
         * `v1` · `v2` (case-insensitive). Null or blank means UNSET — an env var exported empty, a
         * config with no `fuzzy.match` block — and yields the caller's [default]: [V1] for the
         * library, the shipped [V2] for the service (review-103 L1). Anything else is an ERROR, unlike
         * [RetrievalMode.fromString]'s silent default: a typo here would quietly run the engine the
         * operator believes they switched off — and so would reading "unset" as "v1" in a service
         * that ships v2.
         */
        fun fromString(
            value: String?,
            default: MatchVersion = V1,
        ): MatchVersion {
            if (value.isNullOrBlank()) return default
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
