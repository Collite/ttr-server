// SPDX-License-Identifier: Apache-2.0
package org.fuzzy.common

import kotlinx.serialization.Serializable

/** S-4 confidence provenance (RG-P2). */
@Serializable
data class Provenance(
    val producer: String = "fuzzy",
    val method: String = "TATRMAN",
    val rawScore: Double = 0.0,
    // RV-44 (RV-P3.0): the winning (norm, algorithm, distance) when a declared matching profile
    // scored the row. Null everywhere else — the REST surface answers the same question the gRPC
    // one does, or a caller reading a declared score here has no way to ask why it is that number.
    val norm: String? = null,
    val algorithm: String? = null,
    val distance: Int? = null,
    // LP-P0 `fuzzy.match:v2` (contracts §4.6; review-103 L6): the per-token hits and the candidate
    // coverage the gRPC Provenance carries as `token_hits` (7) and `coverage` (8). Empty / null on a
    // v1 row, exactly as on the wire — REST reported `method = TATRMAN_V2` while dropping both.
    val tokenHits: List<TokenHit> = emptyList(),
    val coverage: Double? = null,
)

/** LP-P0 (contracts §4.6) — how one query token matched one candidate token (a v2 row only). */
@Serializable
data class TokenHit(
    val queryToken: String,
    val candidateToken: String,
    /** `exact` · `typo` · `prefix`. */
    val kind: String,
    val distance: Int = 0,
    val queryPos: Int = 0,
    val candidatePos: Int = 0,
)

@Serializable
data class FuzzyMatch(
    val candidateId: String,
    val candidate: String,
    val score: Double,
    val category: String,
    // RG-P2 additive (contracts §2, RS-15); defaulted for backward compatibility.
    val source: String = "MEMBER", // MEMBER | VOCABULARY
    val targetRef: String? = null, // set iff source = VOCABULARY
    val provenance: Provenance? = null,
)

@Serializable
data class FuzzyMatchResponse(
    val matches: List<FuzzyMatch> = emptyList(),
    val isError: Boolean = false,
    val error: String = "",
    // Which algorithm produced `matches` (cascade winner / last tried).
    // Defaulted so existing callers and payloads stay backward-compatible.
    val matchedAlgorithm: String = "",
    // RG-P2 (S-1): snapshot hash + per-category load timestamp (populated in S2).
    val vocabularyVersion: String = "",
)
