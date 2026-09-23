// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.perf

import io.kotest.core.spec.style.StringSpec
import org.tatrman.fuzzy.core.Candidate
import org.tatrman.fuzzy.core.MatchVersion
import org.tatrman.fuzzy.core.RetrievalMode
import org.tatrman.fuzzy.core.TokenVocabulary
import org.tatrman.fuzzy.core.VocabularyResolver

/**
 * T6 — the perf artefact. Excluded from the default `test`; runs only under `-DincludePerf=true`:
 *
 *   ./gradlew :services:lex-matcher:test -DincludePerf=true
 *
 * Builds the product-name-scale corpus ([PerfFixture.BENCH_PRODUCTS] products + customers), warms
 * up, then times each query class (10 measured rounds) under BOTH retrieval modes (legacy vs
 * index-first) and prints markdown tables of p50/p95 wall time. Plus a resolver-only micro-bench
 * (FZ-P2·A T6). No hard assertions: numbers are environment-relative; the tables are the deliverable
 * pasted into `baseline.md`.
 */
class BenchmarkSpec :
    StringSpec({
        val includePerf = System.getProperty("includePerf") == "true"

        "TATRMAN benchmark — legacy vs index-first".config(enabled = includePerf) {
            val benchProducts = CorpusGenerator.products(PerfFixture.BENCH_PRODUCTS, PerfFixture.CORPUS_SEED)
            val corpus =
                mapOf(
                    PerfFixture.PRODUCTS_CATEGORY to benchProducts,
                    PerfFixture.CUSTOMERS_CATEGORY to
                        CorpusGenerator.customers(PerfFixture.BENCH_CUSTOMERS, PerfFixture.CORPUS_SEED),
                )
            val queries = PerfFixture.benchQueries(benchProducts)
            val byClass = queries.groupBy { it.cls }

            suspend fun benchmark(mode: RetrievalMode): String {
                val fixture = PerfFixture.of(corpus, mode)
                try {
                    repeat(3) { for (q in queries) fixture.matchTatrman(q.query, q.category, limit = 10) }
                    val rows = StringBuilder()
                    rows.appendLine("| query class | n | p50 ms | p95 ms | mean ms |")
                    rows.appendLine("|---|---:|---:|---:|---:|")
                    for (cls in QueryClass.values()) {
                        val qs = byClass[cls] ?: continue
                        val timesMs = ArrayList<Double>(qs.size * 10)
                        repeat(10) {
                            for (q in qs) {
                                val t0 = System.nanoTime()
                                fixture.matchTatrman(q.query, q.category, limit = 10)
                                timesMs += (System.nanoTime() - t0) / 1_000_000.0
                            }
                        }
                        rows.appendLine(
                            "| %s | %d | %.2f | %.2f | %.2f |".format(
                                cls.name,
                                qs.size,
                                percentile(timesMs, 50.0),
                                percentile(timesMs, 95.0),
                                timesMs.average(),
                            ),
                        )
                    }
                    return rows.toString()
                } finally {
                    fixture.close()
                }
            }

            val legacyTable = benchmark(RetrievalMode.LEGACY)
            val indexFirstTable = benchmark(RetrievalMode.INDEX_FIRST)

            // Resolver micro-bench (FZ-P2·A T6): resolve every ALL_TYPO token against the products vocab.
            val resolverTable =
                run {
                    val vocab = TokenVocabulary(benchProducts)
                    val allTypoTokens =
                        (byClass[QueryClass.ALL_TYPO] ?: emptyList())
                            .flatMap { Candidate.tokenize(it.query) }
                    repeat(3) {
                        val r = VocabularyResolver(vocab)
                        allTypoTokens.forEach { r.resolve(it) }
                    }
                    val timesMs = ArrayList<Double>(allTypoTokens.size * 10)
                    repeat(10) {
                        val r = VocabularyResolver(vocab)
                        for (tok in allTypoTokens) {
                            val t0 = System.nanoTime()
                            r.resolve(tok)
                            timesMs += (System.nanoTime() - t0) / 1_000_000.0
                        }
                    }
                    "vocabulary size=${vocab.size}; ${allTypoTokens.size} all-typo tokens resolved; " +
                        "p50=%.3f ms  p95=%.3f ms".format(percentile(timesMs, 50.0), percentile(timesMs, 95.0))
                }
            // LP-P0·S3 T5 — the same micro-bench through resolveV2 (budget ladder + prefix range scan).
            val resolverV2Table =
                run {
                    val vocab = TokenVocabulary(benchProducts)
                    val allTypoTokens =
                        (byClass[QueryClass.ALL_TYPO] ?: emptyList())
                            .flatMap { Candidate.tokenize(it.query) }
                    repeat(3) {
                        val r = VocabularyResolver(vocab)
                        allTypoTokens.forEach { r.resolveV2(it) }
                    }
                    val timesMs = ArrayList<Double>(allTypoTokens.size * 10)
                    repeat(10) {
                        val r = VocabularyResolver(vocab)
                        for (tok in allTypoTokens) {
                            val t0 = System.nanoTime()
                            r.resolveV2(tok)
                            timesMs += (System.nanoTime() - t0) / 1_000_000.0
                        }
                    }
                    "resolveV2: p50=%.3f ms  p95=%.3f ms".format(percentile(timesMs, 50.0), percentile(timesMs, 95.0))
                }

            println(
                buildString {
                    appendLine()
                    appendLine("### FZ benchmark — TATRMAN path (legacy vs index-first)")
                    appendLine(
                        "corpus: ${PerfFixture.BENCH_PRODUCTS} products + ${PerfFixture.BENCH_CUSTOMERS} customers; " +
                            "${queries.size} queries; 3 warmup + 10 measured rounds",
                    )
                    appendLine()
                    appendLine("#### legacy")
                    append(legacyTable)
                    appendLine()
                    appendLine("#### index-first")
                    append(indexFirstTable)

                    appendLine()
                    appendLine("#### resolver micro-bench")
                    appendLine(resolverTable)
                    appendLine(resolverV2Table)
                },
            )
        }

        // LP-P0·S3 T5 — contracts §4.7 (iii): v2 p95 ≤ v1 p95, both index-first. Measured
        // INTERLEAVED: running one engine's whole pass before the other's biases whichever runs
        // second (warmer JIT, settled GC) by about the size of the difference being measured — seen
        // both ways on 2026-09-23. Here both engines are loaded side by side, every query is timed
        // on both back to back, and which goes first alternates per round.
        "LP-P0 — fuzzy.match v1 vs v2, index-first, interleaved".config(enabled = includePerf) {
            val benchProducts = CorpusGenerator.products(PerfFixture.BENCH_PRODUCTS, PerfFixture.CORPUS_SEED)
            val corpus =
                mapOf(
                    PerfFixture.PRODUCTS_CATEGORY to benchProducts,
                    PerfFixture.CUSTOMERS_CATEGORY to
                        CorpusGenerator.customers(PerfFixture.BENCH_CUSTOMERS, PerfFixture.CORPUS_SEED),
                )
            val queries = PerfFixture.benchQueries(benchProducts)
            val v1 = PerfFixture.of(corpus, RetrievalMode.INDEX_FIRST, MatchVersion.V1)
            val v2 = PerfFixture.of(corpus, RetrievalMode.INDEX_FIRST, MatchVersion.V2)
            try {
                repeat(3) {
                    for (q in queries) {
                        v1.matchTatrman(q.query, q.category, limit = 10)
                        v2.matchTatrman(q.query, q.category, limit = 10)
                    }
                }
                val times =
                    mapOf(
                        MatchVersion.V1 to HashMap<QueryClass, ArrayList<Double>>(),
                        MatchVersion.V2 to HashMap(),
                    )

                suspend fun time(
                    version: MatchVersion,
                    fixture: PerfFixture,
                    q: PerfQuery,
                ) {
                    val t0 = System.nanoTime()
                    fixture.matchTatrman(q.query, q.category, limit = 10)
                    times.getValue(version).getOrPut(q.cls) { ArrayList() } += (System.nanoTime() - t0) / 1_000_000.0
                }
                repeat(20) { round ->
                    for (q in queries) {
                        if (round % 2 == 0) {
                            time(MatchVersion.V1, v1, q)
                            time(MatchVersion.V2, v2, q)
                        } else {
                            time(MatchVersion.V2, v2, q)
                            time(MatchVersion.V1, v1, q)
                        }
                    }
                }
                println(
                    buildString {
                        appendLine()
                        appendLine("### LP-P0 benchmark — fuzzy.match v1 vs v2 (index-first, interleaved)")
                        appendLine(
                            "corpus: ${PerfFixture.BENCH_PRODUCTS} products + " +
                                "${PerfFixture.BENCH_CUSTOMERS} customers; " +
                                "${queries.size} queries; 3 warmup + 20 interleaved rounds",
                        )
                        appendLine()
                        appendLine("| query class | n | v1 p50 | v2 p50 | v1 p95 | v2 p95 | v2 ≤ v1 (p95) |")
                        appendLine("|---|---:|---:|---:|---:|---:|---|")
                        for (cls in QueryClass.values()) {
                            val a = times.getValue(MatchVersion.V1)[cls] ?: continue
                            val b = times.getValue(MatchVersion.V2).getValue(cls)
                            val a95 = percentile(a, 95.0)
                            val b95 = percentile(b, 95.0)
                            appendLine(
                                "| %s | %d | %.2f | %.2f | %.2f | %.2f | %s |".format(
                                    cls.name,
                                    a.size,
                                    percentile(a, 50.0),
                                    percentile(b, 50.0),
                                    a95,
                                    b95,
                                    if (b95 <= a95) "yes" else "NO (+%.1f%%)".format(100 * (b95 / a95 - 1)),
                                ),
                            )
                        }
                    },
                )
            } finally {
                v1.close()
                v2.close()
            }
        }
    })

/** Linear-interpolated percentile over an unsorted sample. */
private fun percentile(
    samples: List<Double>,
    p: Double,
): Double {
    if (samples.isEmpty()) return 0.0
    val sorted = samples.sorted()
    if (sorted.size == 1) return sorted[0]
    val rank = (p / 100.0) * (sorted.size - 1)
    val lo = rank.toInt()
    val hi = minOf(lo + 1, sorted.size - 1)
    val frac = rank - lo
    return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
}
