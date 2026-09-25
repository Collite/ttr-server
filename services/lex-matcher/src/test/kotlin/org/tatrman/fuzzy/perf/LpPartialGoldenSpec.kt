// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.perf

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.tatrman.fuzzy.core.Candidate
import org.tatrman.fuzzy.core.FuzzyMatchResult
import org.tatrman.fuzzy.core.MatchVersion
import org.tatrman.fuzzy.core.RetrievalMode
import org.tatrman.fuzzy.core.V2Normalization

/**
 * LP-P0·S3 T3 — the `lp-partial` golden slice (contracts §4.7 (ii)): partial names, prefixes,
 * one-token queries on crowded postings, legal-form noise, typos at every budget boundary, order
 * and all-typo cases, over an anonymised customer corpus (`goldens/lp-partial-corpus.json`).
 *
 * Each case pins, UNDER v2: the top-1, the ORDER of the top-3, and the kind of every token hit on
 * the top-1 (`exact` · `typo` · `prefix`, in query order). `v1Top1` is the improvement ledger —
 * what v1 answered for the same query — and is recorded, and asserted for drift. A case with
 * `pending` set names an open question; it is reported, not asserted, so the slice cannot quietly
 * pin a known gap as "expected". (All 57 are pinned since ✅LP-7…9 — 2026-09-23; LP-58…60, the
 * three-token member partials, were added by review-103 F3 — 60 pinned.)
 *
 * The full v1/v2 table is printed on every run (the XML report's system-out).
 */
class LpPartialGoldenSpec :
    StringSpec({
        val json = Json { ignoreUnknownKeys = true }
        val corpus =
            json
                .decodeFromString<List<CorpusRow>>(resource("/goldens/lp-partial-corpus.json"))
                .map { Candidate.fromValues(it.id, it.value) }
        val cases =
            resource("/goldens/lp-partial.jsonl")
                .lines()
                .filter { it.isNotBlank() }
                .map { json.decodeFromString<LpCase>(it) }

        suspend fun answers(
            version: MatchVersion,
            normalization: V2Normalization = V2Normalization.DEFAULT,
        ): Map<String, List<FuzzyMatchResult>> {
            val fixture = PerfFixture.of(mapOf(CATEGORY to corpus), RetrievalMode.INDEX_FIRST, version, normalization)
            try {
                return cases.associate { it.id to fixture.matchTatrman(it.query, CATEGORY, limit = 10) }
            } finally {
                fixture.close()
            }
        }

        "the slice has ≥ 40 cases, each family represented" {
            cases.size shouldBeGreaterThanOrEqual 40
            cases.map { it.family }.toSet() shouldBe
                setOf("partial", "prefix", "one-token", "legal-form", "typo", "order", "all-typo")
        }

        "v2 answers every pinned case: top-1, top-3 order, top-1 hit kinds" {
            val v1 = answers(MatchVersion.V1)
            val v2 = answers(MatchVersion.V2)
            val failures = mutableListOf<String>()
            val table = StringBuilder("| id | family | query | v2 top-3 | v2 top-1 kinds | v1 top-1 | pinned |\n")
            for (c in cases) {
                val got = v2.getValue(c.id)
                val top3 = got.take(3).map { it.candidateId }
                val kinds =
                    got
                        .firstOrNull()
                        ?.provenance
                        ?.tokenHits
                        ?.map { it.kind }
                        .orEmpty()
                val v1Top1 = v1.getValue(c.id).firstOrNull()?.candidateId
                val pinned =
                    if (c.pending != null) {
                        "PENDING ${c.pending}"
                    } else if (c.top3 != null) {
                        "yes"
                    } else {
                        "no"
                    }
                table.append("| ${c.id} | ${c.family} | ${c.query} | $top3 | $kinds | $v1Top1 | $pinned |\n")
                if (c.pending != null || c.top3 == null) continue
                if (top3 != c.top3) failures += "[${c.id}] '${c.query}' top-3: expected ${c.top3}, got $top3"
                if (kinds != c.kinds) failures += "[${c.id}] '${c.query}' top-1 kinds: expected ${c.kinds}, got $kinds"
                if (c.v1Top1 != v1Top1) {
                    failures += "[${c.id}] '${c.query}' v1 ledger drifted: recorded ${c.v1Top1}, v1 now $v1Top1"
                }
            }
            println("\n### LP-P0·S3 lp-partial slice (v2, index-first)\n$table")
            cases.count { it.top3 == null && it.pending == null } shouldBe 0
            if (failures.isNotEmpty()) {
                throw AssertionError("lp-partial: ${failures.size} failure(s):\n" + failures.joinToString("\n"))
            }
        }

        // review-103 F3 — the class of drift `LpHeroVerdictDiffTest` could not see (it has no
        // multi-token non-exact MEMBER query): the resolver reads a member row at ≥ 0.9999 as EXACT,
        // so ≥ 1.0 must mean "every query token hit EXACT", on every row the engine returns.
        "F3 — member-EXACT boundary: over every row the slice returns, score ≥ 1.0 ⇔ every query token EXACT" {
            val v2 = answers(MatchVersion.V2)
            var exactRows = 0
            var otherRows = 0
            val failures = mutableListOf<String>()
            for (c in cases) {
                val n = Candidate.tokenizeRaw(c.query).size
                for (row in v2.getValue(c.id)) {
                    val kinds = row.provenance.tokenHits.map { it.kind }
                    val allExact = kinds.size == n && kinds.all { it == "exact" }
                    if (allExact) exactRows++ else otherRows++
                    if ((row.score >= 1.0) != allExact) {
                        failures += "[${c.id}] '${c.query}' → ${row.candidateId} score ${row.score} kinds $kinds"
                    }
                }
            }
            println("F3 boundary: $exactRows all-exact row(s) ≥ 1.0, $otherRows other row(s) < 1.0")
            exactRows shouldBeGreaterThan 0
            otherRows shouldBeGreaterThan 0
            failures shouldBe emptyList()
        }

        "F3 — not vacuous: under `off` the multi-token partial member cases DID read as exact" {
            val off = answers(MatchVersion.V2, V2Normalization.OFF)
            val shipped = answers(MatchVersion.V2)
            for (id in MEMBER_PARTIALS) {
                val before = off.getValue(id).first()
                val now = shipped.getValue(id).first()
                before.provenance.tokenHits.any { it.kind != "exact" } shouldBe true
                before.score shouldBeGreaterThanOrEqual 1.0 // the resolver's member-EXACT reading
                now.candidateId shouldBe before.candidateId
                now.score shouldBeLessThan 1.0
            }
        }
    }) {
    companion object {
        const val CATEGORY = "customer"

        /** Three-token member partials (a prefix or a typo among exact hits, in order) — review-103 F3. */
        val MEMBER_PARTIALS = listOf("LP-58", "LP-59", "LP-60")
    }
}

@Serializable
private data class CorpusRow(
    val id: String,
    val value: String,
)

/**
 * One slice case. [top3] is the expected top-3 in ORDER (shorter when fewer candidates answer;
 * empty = no answer at all); [kinds] the top-1's hit kinds in query order; [v1Top1] the ledger.
 */
@Serializable
private data class LpCase(
    val id: String,
    val family: String,
    val query: String,
    val top3: List<String>? = null,
    val kinds: List<String> = emptyList(),
    val v1Top1: String? = null,
    val note: String? = null,
    val pending: String? = null,
)

private fun resource(path: String): String =
    LpPartialGoldenSpec::class.java
        .getResourceAsStream(path)!!
        .bufferedReader()
        .use { it.readText() }
