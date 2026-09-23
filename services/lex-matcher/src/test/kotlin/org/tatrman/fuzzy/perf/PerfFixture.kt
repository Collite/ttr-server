// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.perf

import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.core.AlgorithmType
import org.tatrman.fuzzy.core.Candidate
import org.tatrman.fuzzy.core.FuzzyMatchResult
import org.tatrman.fuzzy.core.FuzzyMatcher
import org.tatrman.fuzzy.core.MatchVersion
import org.tatrman.fuzzy.core.RetrievalMode
import org.tatrman.fuzzy.core.StringRepository
import org.tatrman.fuzzy.loader.StaticLoaderSource

/**
 * The perf/parity harness fixture. It drives the **production** code paths — a real
 * [StringRepository] (which does the real folding, per-category [org.tatrman.fuzzy.core.TokenIndex]
 * + [org.tatrman.fuzzy.core.DistanceCache] build, and cross-category flatten) feeding a real
 * [FuzzyMatcher] — rather than re-implementing the matcher. The repository runs in **manual-refresh
 * mode** (`refreshIntervalSeconds = 0`, so no background loop): we [StringRepository.forceRefresh]
 * exactly once at build time, which makes every golden deterministic.
 *
 * Two (corpus, query-set) pairs share this one generator:
 *  - **parity** — small ([PARITY_PRODUCTS] / [PARITY_CUSTOMERS]) so `ParityGateSpec` runs on every
 *    `./gradlew test`, yet large enough to exercise token reuse, the all-typo whole-corpus
 *    fallback, diacritic folding, and IDF weighting. The goldens are captured over THIS pair.
 *  - **benchmark** — product-name scale ([BENCH_PRODUCTS] / [BENCH_CUSTOMERS]) where the
 *    quadratic-ish seeding actually hurts. Timing only; opt-in via `-DincludePerf=true`.
 */
class PerfFixture private constructor(
    private val repository: StringRepository,
    private val matcher: FuzzyMatcher,
) {
    /** Routes a query through the real [FuzzyMatcher] on the TATRMAN (token-based) path. */
    suspend fun matchTatrman(
        query: String,
        category: String?,
        limit: Int = 10,
    ): List<FuzzyMatchResult> = matcher.match(query, category, AlgorithmType.TATRMAN, limit)

    /**
     * The exact-token seed-set size the engine would score for [query] — the dominant cost driver
     * the performance review flags. Reads the production per-category (or global, when `category`
     * is null) [org.tatrman.fuzzy.core.TokenIndex]; a size of 0 means the engine falls back to
     * scoring the whole category. Reporting-only (benchmark table), never asserted.
     */
    fun seedSize(
        query: String,
        category: String?,
    ): Int {
        val tokens = Candidate.tokenize(query)
        return repository.getTokenIndex(category).findCandidatesWithAnyToken(tokens).size
    }

    fun close() = repository.close()

    companion object {
        const val PRODUCTS_CATEGORY = "products"
        const val CUSTOMERS_CATEGORY = "customers"

        /** Fixed seeds — the harness's only entropy source. Changing either invalidates the goldens. */
        const val CORPUS_SEED = 42L
        const val QUERY_SEED = 20260721L

        const val PARITY_PRODUCTS = 2000
        const val PARITY_CUSTOMERS = 500

        const val BENCH_PRODUCTS = 100_000
        const val BENCH_CUSTOMERS = 20_000

        fun parityProducts(): List<Candidate> = CorpusGenerator.products(PARITY_PRODUCTS, CORPUS_SEED)

        fun parityCustomers(): List<Candidate> = CorpusGenerator.customers(PARITY_CUSTOMERS, CORPUS_SEED)

        fun parityCorpus(): Map<String, List<Candidate>> =
            mapOf(
                PRODUCTS_CATEGORY to parityProducts(),
                CUSTOMERS_CATEGORY to parityCustomers(),
            )

        /** The pinned query set — derived deterministically from the parity product corpus. */
        fun parityQueries(): List<PerfQuery> = QuerySet.build(parityProducts(), PRODUCTS_CATEGORY, QUERY_SEED)

        fun benchCorpus(): Map<String, List<Candidate>> =
            mapOf(
                PRODUCTS_CATEGORY to CorpusGenerator.products(BENCH_PRODUCTS, CORPUS_SEED),
                CUSTOMERS_CATEGORY to CorpusGenerator.customers(BENCH_CUSTOMERS, CORPUS_SEED),
            )

        fun benchQueries(products: List<Candidate>): List<PerfQuery> =
            QuerySet.build(products, PRODUCTS_CATEGORY, QUERY_SEED)

        /**
         * Builds a fixture over an explicit [corpus] using the real repository + matcher. Manual
         * refresh mode ⇒ [StringRepository.forceRefresh] is called once here; the caller [close]s it.
         */
        suspend fun of(
            corpus: Map<String, List<Candidate>>,
            retrievalMode: RetrievalMode = RetrievalMode.LEGACY,
            matchVersion: MatchVersion = MatchVersion.V1,
        ): PerfFixture {
            val cfg =
                AppConfig(
                    serverPort = 0,
                    grpcPort = 0,
                    grpcReflectionEnabled = false,
                    // Manual mode: no background refresh loop; we drive one forceRefresh below.
                    refreshIntervalSeconds = 0,
                )
            val repo = StringRepository(cfg, StaticLoaderSource(corpus), telemetry = null)
            repo.forceRefresh()
            return PerfFixture(repo, FuzzyMatcher(repo, retrievalMode = retrievalMode, matchVersion = matchVersion))
        }

        /** Convenience: a fixture over the parity corpus (LEGACY by default; pass INDEX_FIRST for the gate). */
        suspend fun parity(
            retrievalMode: RetrievalMode = RetrievalMode.LEGACY,
            matchVersion: MatchVersion = MatchVersion.V1,
        ): PerfFixture = of(parityCorpus(), retrievalMode, matchVersion)
    }
}
