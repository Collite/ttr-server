// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.perf

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import org.tatrman.fuzzy.core.FuzzyMatchResult
import org.tatrman.fuzzy.core.MatchVersion
import org.tatrman.fuzzy.core.RetrievalMode

/**
 * LP-P0·S3 — the `fuzzy.match:v2` gate over the FZ-P0 parity corpus + pinned query set
 * (contracts §4.7). The FZ goldens themselves are untouched; this spec only reads them.
 *
 *  - **T1, v1 pinned**: naming `fuzzy.match.version=v1` explicitly is the same engine as not naming
 *    it — LEGACY byte-identical to the goldens, INDEX_FIRST byte-identical to the implicit default.
 *  - **T2, v2 parity-or-better (i)**: wherever v1's top-1 was unique (margin ≥ 0.05), v2's top-1 is
 *    the same candidate. A difference is a DEFECT in the engine, never a golden to edit. Where v1's
 *    top-1 was NOT unique, v2 may break the tie — but only to a candidate inside v1's near-tie group
 *    (or, when v1's whole top-10 is one tie, to one v1 never showed). Measured 2026-09-23: 26/26
 *    kept; 130 tie breaks = 115 inside the group + 15 one-token queries whose v1 top-10 was all 1.0.
 */
class LpV2ParitySpec :
    StringSpec({
        val queries = PerfFixture.parityQueries()

        suspend fun run(
            mode: RetrievalMode,
            version: MatchVersion?,
        ): Map<String, List<FuzzyMatchResult>> {
            val fixture =
                if (version == null) PerfFixture.parity(mode) else PerfFixture.parity(mode, version)
            try {
                return queries.associate { it.id to fixture.matchTatrman(it.query, it.category, limit = 10) }
            } finally {
                fixture.close()
            }
        }

        fun List<FuzzyMatchResult>.pairs() = map { it.candidateId to formatGoldenScore(it.score) }

        "T1 — explicit v1 on LEGACY is byte-identical to the FZ parity goldens" {
            val goldens = loadLpGoldens().associateBy { it.queryId }
            val actual = run(RetrievalMode.LEGACY, MatchVersion.V1)
            val diffs =
                queries.filter { q ->
                    actual.getValue(q.id).pairs() != goldens.getValue(q.id).results.map { it.candidateId to it.score }
                }
            diffs.map { it.id } shouldBe emptyList()
        }

        "T1 — explicit v1 on INDEX_FIRST is byte-identical to the implicit default" {
            val implicit = run(RetrievalMode.INDEX_FIRST, null)
            val explicit = run(RetrievalMode.INDEX_FIRST, MatchVersion.V1)
            queries
                .filter {
                    implicit
                        .getValue(
                            it.id,
                        ).pairs() != explicit.getValue(it.id).pairs()
                }.map { it.id } shouldBe
                emptyList()
        }

        "T2 — v2 keeps v1's top-1 wherever v1's top-1 was unique (margin ≥ 0.05)" {
            val v1 = run(RetrievalMode.INDEX_FIRST, MatchVersion.V1)
            val v2 = run(RetrievalMode.INDEX_FIRST, MatchVersion.V2)
            var unique = 0
            var same = 0
            val defects = mutableListOf<String>()
            val reorderedAmbiguous = mutableListOf<String>()
            var inTieGroup = 0
            var outsideV1Top10 = 0
            for (q in queries) {
                val a = v1.getValue(q.id)
                val b = v2.getValue(q.id)
                if (a.isEmpty()) {
                    if (b.isNotEmpty()) reorderedAmbiguous += "[${q.id}] v1 empty, v2 reaches ${b.first().candidateId}"
                    continue
                }
                val margin = if (a.size == 1) a[0].score else a[0].score - a[1].score
                val top2 = b.firstOrNull()?.candidateId
                if (margin >= UNIQUE_MARGIN) {
                    unique++
                    if (top2 == a[0].candidateId) {
                        same++
                    } else {
                        defects +=
                            "[${q.id}] '${q.query}' (${q.cls}) v1 top-1 ${a[0].candidateId} " +
                            "(margin ${"%.4f".format(margin)}) → v2 top-1 $top2"
                    }
                } else if (top2 != a[0].candidateId) {
                    // Where did v2's pick sit in v1's ranking? Inside v1's near-tie group ⇒ the
                    // coverage tie-break chose among v1's equals (by design); outside v1's top-10 ⇒
                    // v2 reached something v1 never showed (a prefix/kind effect).
                    val v1Rank = a.indexOfFirst { it.candidateId == top2 }
                    when {
                        // v1's whole top-10 is one tie ⇒ v1 showed an arbitrary 10 of a larger tie
                        // (one-token queries on crowded postings — design §5.2's exact complaint).
                        v1Rank < 0 && a[0].score - a.last().score < UNIQUE_MARGIN -> outsideV1Top10++
                        v1Rank >= 0 && a[0].score - a[v1Rank].score < UNIQUE_MARGIN -> inTieGroup++
                        else -> defects += "[${q.id}] '${q.query}' v2 top-1 $top2 is outside v1's near-tie group"
                    }
                    reorderedAmbiguous += "[${q.id}] v1 tie/near-tie ${a[0].candidateId} → v2 $top2 (v1 rank $v1Rank)"
                }
            }
            println(
                "LP-P0·S3 v2 parity (i): $same/$unique unique v1 top-1s kept; ${defects.size} defect(s); " +
                    "${reorderedAmbiguous.size} ambiguous/empty case(s) re-ordered or newly reached " +
                    "($inTieGroup inside v1's near-tie group, $outsideV1Top10 outside v1's top-10; " +
                    "of ${queries.size} queries)\n" + reorderedAmbiguous.joinToString("\n"),
            )
            if (defects.isNotEmpty()) {
                throw AssertionError(
                    "v2 changed a unique v1 top-1 in ${defects.size} case(s):\n" + defects.joinToString("\n"),
                )
            }
            // review-103 L5 — (i) is only a gate while there is something to keep: a corpus or query
            // change that left no unique v1 top-1 would pass it vacuously (26 today).
            unique shouldBeGreaterThan 0
        }
    }) {
    companion object {
        /** Contracts §4.7 (i): the margin at which v1's top-1 counts as unique. */
        const val UNIQUE_MARGIN = 0.05
    }
}

private fun loadLpGoldens(): List<GoldenEntry> =
    Json.decodeFromString(
        LpV2ParitySpec::class.java
            .getResourceAsStream("/perf/parity-goldens.json")!!
            .bufferedReader()
            .use { it.readText() },
    )
