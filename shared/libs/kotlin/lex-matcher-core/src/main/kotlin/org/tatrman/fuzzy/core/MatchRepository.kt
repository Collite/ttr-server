// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

/**
 * FZ-P3 — the read-side seam the engine needs from a candidate store: per-category candidates,
 * the legacy token index, the (legacy-path) distance cache, the interned vocabulary, and the version
 * stamp. The service's `StringRepository` (refresh loop, loaders, config — all service concerns)
 * implements this, so the engine ([FuzzyMatcher]) stays free of that machinery. Category keys are
 * resolved case-insensitively by the implementation; an explicit-but-unknown category returns an
 * empty index/vocabulary (never the global one) — the per-column leak guard.
 */
interface MatchRepository {
    fun getCandidates(category: String?): List<Candidate>

    fun getTokenIndex(category: String? = null): TokenIndex

    fun getDistanceCache(category: String? = null): DistanceCache

    fun getVocabulary(category: String? = null): TokenVocabulary

    fun vocabularyVersion(): String

    /**
     * RV-39 — the layer-version tuple (S-1). Defaulted so the many existing fakes in the suites
     * keep compiling: a repository that knows about no layers reports none, which is the honest
     * answer for a member-only store.
     */
    fun layerVersions(): LayerVersions = LayerVersions()

    /**
     * RV-P1.4 T5 — every category key this store can answer for (lower-cased), or **null when the
     * store does not report them**.
     *
     * Null rather than an empty set on purpose: "I don't publish my categories" and "I have none"
     * are different facts, and a lookup that conflated them would report every requested category
     * as unknown. The many existing fakes take the default and simply say nothing.
     */
    fun knownCategories(): Set<String>? = null

    /**
     * RV-P1.4 T6 — the estate overlay, filled at RV-P7.3. Empty by default.
     *
     * Hung off the repository because the repository is the **layer owner**: it already owns the
     * member index and the declared source, and hanging the third layer somewhere else would give
     * the same tuple two homes. [FuzzyMatcher] consults it; the repository reports its version.
     */
    fun overlay(): OverlayStore = NoopOverlayStore

    /**
     * RV-P1.4 T4 — true when this store serves a declared layer, i.e. when some candidate can carry
     * an authored method or a target class.
     *
     * [FuzzyMatcher] scores wider than the caller's limit when this is true, so the authored-method
     * gate and T5's class filter have rows to reject without emptying the answer. That widening is
     * **not** free of consequence — `TokenBasedMatcher` sizes its defensive non-seed sample by the
     * limit it is given, so a wider limit changes which candidates get fuzzy-scored. Gating it here
     * is what keeps T7's promise exact: with no artifact loaded, the member path is byte-identical
     * to the pre-RV engine, goldens and all.
     *
     * Defaults to false — the honest answer for a member-only store, and for the many fakes.
     */
    fun servesDeclaredLayer(): Boolean = false

    /**
     * MV (review-104 F10) — true when some row [category] (null: any category) would be narrowed
     * AFTER scoring, which is the question [FuzzyMatcher] scores wide for.
     *
     * Until MV that was exactly [servesDeclaredLayer]: only a declared or learned row carried an
     * authored method, a class or a profile. A member row now carries its vocabulary's method, and
     * `EXACT`/`TYPOS(n)` narrow — so an admissible member ranked just past the caller's limit by the
     * recall-oriented token scorer was truncated before the gate saw it, and the query answered
     * nothing. `TOKENS` narrows nothing, so a store whose member vocabularies are all TOKENS keeps
     * the byte-identical path.
     *
     * Defaults to [servesDeclaredLayer], so every store that predates member methods — and every
     * fake — answers exactly as before.
     */
    fun narrowsAfterScoring(category: String?): Boolean = servesDeclaredLayer()
}
