// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.loader.DeclaredVocabularyLoader
import org.tatrman.fuzzy.loader.LoaderSource
import org.tatrman.fuzzy.loader.SnapshotVocabularySource
import org.tatrman.fuzzy.telemetry.FuzzyTelemetry
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Per-category discovery + staleness for `GetStatus` (contracts §2). */
data class CategoryStatusInfo(
    val category: String,
    val source: SourceTag,
    val size: Int,
    val loadedAtEpochMs: Long,
    /** MV-T2 — the method the category's member rows carry; null when it has none. */
    val matchMethod: String? = null,
)

/** Loader-report entry: `RG-FUZ-001`/`003` for a vocabulary Veles could not plan, `RG-FUZ-004` for no listing. */
data class LoaderWarningInfo(
    val code: String,
    val category: String,
    val message: String,
)

class StringRepository(
    private val config: AppConfig,
    private val loaderSource: LoaderSource,
    private val telemetry: FuzzyTelemetry? = null,
    private val lemmatizer: Lemmatizer = NoopLemmatizer,
    // RG-P2.S2: declared vocabulary (lexicon terms + valueLabels) — VOCABULARY
    // categories merged alongside the member data. Null = member data only.
    private val snapshotSource: SnapshotVocabularySource? = null,
    /**
     * RV-P1.4 T6 — the estate overlay, filled at RV-P7.3. [NoopOverlayStore] for any deployment
     * without a learning store: no version in the tuple, nothing consulted, nothing loaded, results
     * untouched. Injected here rather than into [FuzzyMatcher] so the third layer has ONE home —
     * the repository owns the other two, and the matcher reaches it through `overlay()`.
     *
     * The repository drives **both** halves: it merges the store's POSITIVE candidates into the
     * index on the third clock (below), and the matcher consults the same store for NEGATIVE
     * suppressions per query. One store, so a suppression can never be applied against candidates
     * from a different overlay version.
     */
    private val overlayStore: OverlayStore = NoopOverlayStore,
    /** review-104 F1 — the first retry after a refresh that found no member layer to load; tests shorten it. */
    private val firstMemberRetryMs: Long = FIRST_MEMBER_RETRY_MS,
) : MatchRepository {
    private val logger = LoggerFactory.getLogger(StringRepository::class.java)

    private companion object {
        /** Returned for an explicit category that has no index, so the matcher yields no candidates. */
        val EMPTY_TOKEN_INDEX = TokenIndex(emptyList())

        /** FZ-P2 — vocabulary counterpart of [EMPTY_TOKEN_INDEX] for the explicit-unknown case. */
        val EMPTY_VOCABULARY = TokenVocabulary(emptyList())

        // RV-39 — 64-bit FNV-1a, used only to fold a row into a sortable primitive; the version
        // string's collision resistance comes from the SHA-256 over the sorted run, not from this.
        const val FNV_OFFSET_BASIS: Long = -3750763034362895579L // 0xcbf29ce484222325
        const val FNV_PRIME: Long = 1099511628211L

        /** Bytes of the digest kept in a category version — 16 hex chars, ample to compare on. */
        const val VERSION_BYTES: Int = 8

        /** review-104 F1 — the first retry after a boot that found no member layer to load. */
        const val FIRST_MEMBER_RETRY_MS: Long = 5_000L

        /** Caps the doubling well below overflow; the refresh interval caps the delay anyway. */
        const val MAX_BACKOFF_SHIFT: Int = 10
    }

    private val cache = ConcurrentHashMap<String, List<Candidate>>()
    private val categoryTokenIndices = ConcurrentHashMap<String, TokenIndex>()
    private val categoryVocabularies = ConcurrentHashMap<String, TokenVocabulary>()
    private val categoryDistanceCaches = ConcurrentHashMap<String, DistanceCache>()
    private val isRunning = AtomicBoolean(false)
    private val isCatalogReady = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var globalTokenIndex: TokenIndex = TokenIndex(emptyList())

    @Volatile
    private var globalVocabulary: TokenVocabulary = TokenVocabulary(emptyList())

    @Volatile
    private var globalDistanceCache: DistanceCache = DistanceCache()

    // FZ-P1 T4 — the flattened cross-category candidate list, precomputed at refresh instead of
    // re-flattening `cache.values` on every `getCandidates(null)` call. Same contents/order as the
    // per-request flatten (both iterate `cache.values`), so cross-category results are unchanged.
    @Volatile
    private var allCandidates: List<Candidate> = emptyList()

    // RG-P2.S2: the vocabulary version echoed on every response + in GetStatus
    // (S-1). Content hash of {category → size} + the member load stamp; the
    // declared-vocabulary snapshot hash folds into this in T5.
    @Volatile
    private var version: String = ""

    @Volatile
    private var loadedAtMs: Long = 0L

    // Declared vocabulary is the SECOND clock: reloaded (+ lemmatised) only when
    // its snapshot hash changes, then merged into the cache on every member
    // refresh. Stored pre-lemmatised so member refreshes don't re-lemmatise it.
    @Volatile
    private var declaredCache: Map<String, List<Candidate>> = emptyMap()

    @Volatile
    private var declaredHash: String = ""

    // RV-P7.3 T3 — the LEARNED layer's candidates, on their own clock (below). Held separately
    // from [declaredCache] because the tuple names the layers separately: folding them would make
    // "the estate authored a term" indistinguishable from "a user taught it one".
    @Volatile
    private var overlayCache: Map<String, List<Candidate>> = emptyMap()

    @Volatile
    private var overlayHash: String = ""

    /**
     * RV-P7.3 T4 — the overlay a query is answered from, **published with the index built from
     * it**.
     *
     * Not [overlayStore] directly, and this is the determinism fix rather than a nicety. The store
     * swaps its loaded archive the moment `hash()` reads a new one, but the index built from that
     * archive is only published at the end of this refresh — so a query landing in between would
     * have been answered with the OLD candidates, the NEW suppressions, and a tuple naming the new
     * version. RV-39's whole promise is that the tuple identifies the answer; a response assembled
     * from two overlays cannot be reproduced from it.
     *
     * So candidates, suppressions and version move together, at one instant, exactly as
     * [categoryKeys] and [memberVersions] already do.
     */
    @Volatile
    private var servedOverlay: OverlayStore = NoopOverlayStore

    // RV-39 — content-derived version per MEMBER category, computed at refresh. Deliberately
    // excludes the declared layer: the tuple names the layers separately, and folding them would
    // make "the artifact changed" indistinguishable from "a data column changed".
    @Volatile
    private var memberVersions: Map<String, String> = emptyMap()

    // RV-P1.4 T5 — an immutable snapshot of the cache's keys, republished after each refresh so
    // `knownCategories()` never observes the clear/putAll window. See its KDoc.
    @Volatile
    private var categoryKeys: Set<String> = emptySet()

    // review-104 F1 — the member layer as last LOADED (lower-cased keys, lemmatised). A refresh with
    // no member load to take keeps it and still runs the declared and overlay clocks on top: the
    // member layer is the one Veles can take away, and it must not take the other two with it.
    @Volatile
    private var memberCache: Map<String, List<Candidate>> = emptyMap()

    // review-104 F10 — member categories whose rows the authored-method gate narrows (EXACT,
    // TYPOS(n)). Published with the cache; read by [narrowsAfterScoring].
    @Volatile
    private var narrowingCategories: Set<String> = emptySet()

    // review-104 F1 — whether any member load has been taken, and how many refreshes in a row found
    // none before the first one did. Only the second drives the retry backoff.
    @Volatile
    private var memberLoaded: Boolean = false

    @Volatile
    private var memberMisses: Int = 0

    // review-104 F15 — one refresh at a time. `POST /refresh` and the scheduled tick used to be able
    // to overlap, interleaving two loads' writes to the cache and to each layer's clock.
    private val refreshLock = Mutex()

    init {
        startRefreshLoop()
    }

    private fun startRefreshLoop() {
        // refreshIntervalSeconds <= 0 ⇒ manual mode: no background loop, refresh
        // only via forceRefresh (deterministic for tests; also a valid "reload
        // on /refresh only" deployment posture — Q-8 open-vs-harvest line).
        if (config.refreshIntervalSeconds <= 0) return
        if (isRunning.getAndSet(true)) return

        scope.launch {
            while (isActive) {
                try {
                    refreshCache()
                } catch (e: Exception) {
                    logger.error("Failed to refresh cache", e)
                }
                delay(nextDelayMs())
            }
        }
    }

    /**
     * review-104 F1 — the refresh interval, except while no member layer has EVER loaded: then a
     * short doubling retry (5 s, 10 s, 20 s, … never past the interval).
     *
     * A lex-matcher that boots while Veles is still loading its model is told "not ready", and used
     * to wait out the whole interval (600 s on hartland, 3600 s by default) before asking again.
     * Once any member layer is served, a miss keeps it and the interval stands.
     */
    private fun nextDelayMs(): Long {
        val interval = config.refreshIntervalSeconds * 1000
        if (memberLoaded || memberMisses == 0) return interval
        val backoff = firstMemberRetryMs shl (memberMisses - 1).coerceAtMost(MAX_BACKOFF_SHIFT)
        return backoff.coerceAtMost(interval)
    }

    private suspend fun refreshCache() = refreshLock.withLock { refreshLocked() }

    private suspend fun refreshLocked() {
        logger.info("Starting cache refresh...")
        // The rows and the read-plan identities that describe them arrive together (F15).
        val loaded = loaderSource.load()

        // review-104 F1 — no member load is not the end of the refresh. It used to return here,
        // before the declared lexicon and the overlay were loaded and before readiness was set: on
        // a first boot racing Veles the whole matcher stayed NotReady for an interval, and against a
        // Veles without ListMemberVocabularies it never came up. Now the member layer is kept as it
        // was — empty on a first boot, which GetStatus' RG-FUZ-004 says — and the rest proceeds.
        var nextMembers = memberCache
        var nextMemberVersions = memberVersions
        var nextNarrowing = narrowingCategories
        if (loaded == null) {
            if (memberLoaded) {
                logger.warn("Loader signalled failure; preserving the previous member layer")
            } else {
                memberMisses++
                logger.warn(
                    "Loader signalled failure before any member layer loaded (attempt {}); serving without one",
                    memberMisses,
                )
            }
        } else {
            val planVersions = loaded.planVersions.mapKeys { it.key.lowercase() }
            // Category keys are matched case-insensitively. The query side
            // (Routes./match, FuzzyMatcher.match, getTokenIndex) lowercases the
            // requested category, so the stored key MUST be lowercase too. DB
            // identifiers arrive upper-cased from the loader (e.g.
            // "db.dbo.QSTRED_DF.KOD_STR"); without this the per-column index was
            // never hit and lookups silently fell back to the global index,
            // returning *other columns'* values (a KOD_STR query served NAZEV_STR).
            nextMembers =
                loaded.categories.entries.associate { (category, raw) ->
                    category.lowercase() to lemmatiseCandidates(raw)
                }
            nextMemberVersions =
                nextMembers.mapValues { (category, candidates) -> categoryVersion(candidates, planVersions[category]) }
            nextNarrowing = nextMembers.filterValues { rows -> rows.any { narrowedAfterScoring(it) } }.keys
            memberLoaded = true
            memberMisses = 0
            loadedAtMs = System.currentTimeMillis()
        }

        // Second clock: reload + lemmatise declared vocabulary ONLY when its
        // snapshot hash changes (T5), then merge it into the member cache.
        refreshDeclaredIfChanged()
        // Third clock (RV-P7.3): the same discipline for the LEARNED overlay, on its own hash. Its
        // cadence is genuinely different — a lexicon changes when someone authors one, an overlay
        // changes when a user answers a question — which is precisely why it gets its own clock
        // rather than riding the declared one.
        refreshOverlayIfChanged()
        val nextCache = LinkedHashMap<String, List<Candidate>>(nextMembers)
        declaredCache.forEach { (key, vocab) ->
            nextCache.merge(key, vocab) { member, declared -> member + declared }
        }
        // Merged into the SAME index as the other two layers, which is the whole point of loading
        // the positives rather than consulting for them: a learned alias is then retrieved,
        // tokenised, IDF-weighted and scored by the engine like every other row, and it matches
        // fuzzily — the estate learns `tržba` and the matcher serves it for `trzba`.
        overlayCache.forEach { (key, learned) ->
            nextCache.merge(key, learned) { existing, overlay -> existing + overlay }
        }

        cache.clear()
        cache.putAll(nextCache)
        // Published here, with the cache it matches — see [servedOverlay].
        servedOverlay = overlayStore.pinned()
        // Published only once the cache is whole — a reader mid-refresh keeps the previous keys
        // rather than seeing a half-filled map (see [knownCategories]).
        categoryKeys = java.util.Collections.unmodifiableSet(LinkedHashSet(nextCache.keys))
        memberCache = nextMembers
        memberVersions = nextMemberVersions
        narrowingCategories = nextNarrowing
        version = computeVersion(nextCache, declaredHash, loadedAtMs)
        isCatalogReady.set(true)
        rebuildIndices()
    }

    private suspend fun refreshDeclaredIfChanged() {
        val source = snapshotSource ?: return
        val hash = source.hash()
        if (hash == declaredHash && declaredCache.isNotEmpty()) return
        val raw = DeclaredVocabularyLoader.toCategories(source.fetch())
        declaredCache = raw.mapValues { lemmatiseCandidates(it.value) }
        declaredHash = hash
        logger.info("Declared vocabulary loaded (hash={}, categories={})", hash, declaredCache.size)
    }

    /**
     * RV-P7.3 T4 — the overlay's clock, in the shape [refreshDeclaredIfChanged] proved.
     *
     * `hash()` is the only call that touches the source; everything else this refresh reads is
     * whatever that call swapped in. So one reload per interval, and an unchanged overlay costs a
     * content-id comparison.
     *
     * The empty-cache condition the declared clock carries is deliberately **not** repeated here:
     * an estate that has learned nothing legitimately has an empty overlay, and re-fetching it on
     * every tick to rediscover that would be work in exchange for nothing.
     */
    private suspend fun refreshOverlayIfChanged() {
        val hash = overlayStore.hash()
        if (hash == overlayHash) return
        // Lower-cased like every other category key: the query side lowercases what it asks for,
        // and a target ref that arrived with any capital would build an index nothing ever hits.
        overlayCache =
            overlayStore
                .learned()
                .entries
                .associate { (category, rows) -> category.lowercase() to lemmatiseCandidates(rows) }
        overlayHash = hash
        logger.info(
            "Overlay layer loaded (hash={}, version={}, targets={})",
            hash,
            overlayStore.version(),
            overlayCache.size,
        )
    }

    /** The vocabulary version (S-1): content signature + load stamp. */
    override fun vocabularyVersion(): String = version

    /**
     * RV-39 — the layer-version tuple (S-1).
     *
     * Every component is content-derived, which is the point: the older [vocabularyVersion] string
     * bakes in [loadedAtMs] and therefore changes on every refresh whether or not any vocabulary
     * did, so it cannot answer the one question a version tuple is asked.
     *
     * `overlayVersion` is null, not `""` — an estate with no learning store has no overlay, and
     * "absent" and "present at an empty version" are different facts. Absence stays the contract
     * for every pre-P7 estate.
     */
    override fun layerVersions(): LayerVersions =
        LayerVersions(
            lexiconArtifactHash = snapshotSource?.artifactHash() ?: "",
            memberIndexVersions = memberVersions,
            // RV-P7.3 — the store's own version, and it rides EVERY response. Not the archive's
            // content id: this answers "which overlay produced this answer?", traceable back to a
            // row in the Golem's `rv_overlay_versions`, while the content id answers the different
            // question "is the file different?" and drives the reload. Same split, for the same
            // reason, as `LexiconArchiveSource.hash()` vs `artifactHash()`.
            overlayVersion = servedOverlay.version(),
        )

    /**
     * A category's content signature: its candidates by id+value, order-independent. Same content
     * ⇒ same version across refreshes; one added row changes it.
     *
     * MV-T2 — and the read plan that produced them, when the loader has one ([planVersion], Veles'
     * `MemberVocabulary.version`). Content alone cannot see a changed plan that happens to read the
     * same rows today (a new filter, a different key, another match method); the plan alone cannot
     * see the warehouse's rows change — the loader reads those, Veles never does. The version moves
     * when either does. Without a plan the digest is exactly the content one it always was.
     *
     * Streamed, never materialised. The first cut of this concatenated every `id`+`value` into ONE
     * string and took its `hashCode` — for a member category of a million rows that is a ~100 MB
     * transient String built on every refresh, per category. Here each row is folded to a 64-bit
     * signature, the signatures are sorted as primitives (which is what makes the result
     * order-independent), and SHA-256 covers the sorted run. Peak cost is a `LongArray`, and the
     * digest matches the sha256 every other identity in this service is expressed in — a 32-bit
     * `String.hashCode` is a weak answer to "did this layer change?".
     */
    private fun categoryVersion(
        candidates: List<Candidate>,
        planVersion: String? = null,
    ): String {
        val signatures = LongArray(candidates.size) { rowSignature(candidates[it]) }
        signatures.sort()

        val digest = MessageDigest.getInstance("SHA-256")
        planVersion?.let {
            digest.update(it.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        val row = ByteArray(Long.SIZE_BYTES)
        signatures.forEach { signature ->
            for (i in row.indices) row[i] = (signature ushr (8 * i)).toByte()
            digest.update(row)
        }
        return digest.digest().take(VERSION_BYTES).joinToString("") { "%02x".format(it) }
    }

    /** FNV-1a over `id` + a separator + `value`, without building the concatenation. */
    private fun rowSignature(candidate: Candidate): Long {
        var hash = FNV_OFFSET_BASIS

        fun mix(code: Int) {
            hash = hash xor code.toLong()
            hash *= FNV_PRIME
        }

        candidate.id.forEach { mix(it.code) }
        mix(0)
        candidate.value.forEach { mix(it.code) }
        return hash
    }

    /**
     * RV-P1.4 T5 — the loaded category keys, already lower-cased (the cache is keyed that way).
     * Lets a lookup name the categories a caller asked for that do not exist, instead of returning
     * an empty list that could equally mean "no match".
     *
     * An immutable snapshot published after each refresh, **not** `cache.keys`. That is a live view
     * of a map the refresh does `clear()` then `putAll()` on, so a lookup landing in that window saw
     * a partial key set and reported perfectly good categories as unknown — turning a transient into
     * what reads like a stale-ref diagnosis. A caller in that window now gets the previous refresh's
     * keys: stale by at most one interval, never wrong about what the estate declares.
     */
    override fun knownCategories(): Set<String> = categoryKeys

    /**
     * The **published** overlay, not the live store: what a query is answered from must be the
     * overlay the loaded index was built from. See [servedOverlay].
     */
    override fun overlay(): OverlayStore = servedOverlay

    /**
     * RV-P1.4 T4 — whether a layer that narrows AFTER scoring is loaded, read from the second and
     * third clocks' caches. O(1).
     *
     * RV-P7.3 added the overlay, because the real question is *"can a row here be narrowed after
     * scoring?"* and a learned row can: it carries a target class, so T5's class-scoped filter can
     * reject it, and without the headroom it would be truncated before that filter ever ran.
     *
     * False for every estate that has authored no lexicon and learned nothing — which is what keeps
     * the gate's scoring headroom off the member-only path entirely, and is the byte-identical
     * promise P1.4 T7 made.
     */
    override fun servesDeclaredLayer(): Boolean = declaredCache.isNotEmpty() || overlayCache.isNotEmpty()

    /**
     * review-104 F10 — the declared/learned answer above, OR a member vocabulary the gate narrows:
     * EXACT and TYPOS(n) admit fewer rows than the token scorer ranks, so they need the same
     * headroom a declared term gets. Per category, so a TOKENS vocabulary — and a store whose member
     * vocabularies are all TOKENS — keeps the byte-identical path.
     */
    override fun narrowsAfterScoring(category: String?): Boolean =
        servesDeclaredLayer() ||
            if (category == null) narrowingCategories.isNotEmpty() else category.lowercase() in narrowingCategories

    /** A row the authored-method gate may reject after scoring: EXACT or TYPOS(n), never TOKENS. */
    private fun narrowedAfterScoring(c: Candidate): Boolean =
        c.authoredMethod != null && c.authoredMethod != MatchMethod.Tokens

    /** Per-category discovery + staleness for `GetStatus` (contracts §2). */
    fun categoryStatuses(): List<CategoryStatusInfo> =
        cache
            .map { (category, candidates) ->
                CategoryStatusInfo(
                    category = category,
                    source = candidates.firstOrNull()?.source ?: SourceTag.MEMBER,
                    size = candidates.size,
                    loadedAtEpochMs = loadedAtMs,
                    matchMethod = candidates.firstOrNull { it.source == SourceTag.MEMBER }?.matchMethod,
                )
            }.sortedBy { it.category }

    /** Loader report: vocabularies listed but not loaded (`RG-FUZ-001`/`003`), or no listing at all (`RG-FUZ-004`). */
    fun loaderWarnings(): List<LoaderWarningInfo> = loaderSource.warnings()

    private fun computeVersion(
        content: Map<String, List<Candidate>>,
        vocabHash: String,
        stamp: Long,
    ): String {
        // Order-independent content signature: sorted (category → size) pairs +
        // the declared-vocabulary snapshot hash + the member load stamp (S-1).
        val sig =
            content.entries
                .sortedBy { it.key }
                .joinToString("|") { "${it.key}:${it.value.size}" }
                .hashCode()
        val vocab = if (vocabHash.isBlank()) "-" else vocabHash
        return "member:%08x/vocab:%s@%d".format(sig, vocab, stamp)
    }

    /**
     * Operator-triggered immediate reload (via `POST /refresh`), bypassing the background interval
     * wait. Reuses [refreshCache] — and its lock, so it waits for a scheduled refresh in flight
     * rather than interleaving with it; on loader failure it preserves the previous member layer
     * (and throws nothing) just like the scheduled path.
     */
    suspend fun forceRefresh() = refreshCache()

    /**
     * Populates each candidate's [Candidate.lemmaTokens] (the folded lemmas of its surface tokens)
     * so inflected query forms can land an exact lemma match — without disturbing the surface
     * tokens, so a diacritic-stripped exact phrase still scores as a surface match. With
     * [NoopLemmatizer] the candidates already have `lemmaTokens == tokens`, so this is a no-op.
     * Czech lemmatisation is context-sensitive; we batch unrelated tokens per category, which is
     * good enough for short entity-name tokens — a known v1 limitation. We feed the lemmatiser the
     * raw (lower-cased, accented) tokens so MorphoDiTa gets proper Czech, then fold its lemmas.
     */
    private suspend fun lemmatiseCandidates(candidates: List<Candidate>): List<Candidate> {
        if (lemmatizer is NoopLemmatizer || candidates.isEmpty()) return candidates
        val rawByCandidate = candidates.associateWith { Candidate.tokenizeRaw(it.value) }
        val uniqueRaw = rawByCandidate.values.flatMapTo(HashSet()) { it }
        if (uniqueRaw.isEmpty()) return candidates
        val lemmaMap = lemmatizer.lemmatize(uniqueRaw)
        return candidates.map { c ->
            val lemmaTokens = (rawByCandidate[c] ?: emptyList()).map { lemmaMap[it] ?: TextNormalizer.fold(it) }
            Candidate.withLemmas(
                c.id,
                c.value,
                surfaceTokens = c.tokens,
                lemmaTokens = lemmaTokens,
                source = c.source,
                targetRef = c.targetRef,
                matchMethod = c.matchMethod,
                targetClass = c.targetClass,
                matchProfile = c.matchProfile,
                category = c.category,
            )
        }
    }

    private fun rebuildIndices() {
        logger.info("Rebuilding indices for all categories...")

        cache.forEach { (category, candidates) ->
            logger.debug("Building token index for category '$category' with ${candidates.size} candidates...")
            categoryTokenIndices[category] = TokenIndex(candidates)
            // FZ-P2 — interned vocabulary alongside the legacy index (index-first retrieval path).
            categoryVocabularies[category] = TokenVocabulary(candidates)
            // We can optionally reuse distance cache if we want to keep it across refreshes,
            // but the original behavior was to reset it.
            categoryDistanceCaches[category] = DistanceCache()
        }

        // Cleanup categories no longer in cache
        categoryTokenIndices.keys.removeIf { !cache.containsKey(it) }
        categoryVocabularies.keys.removeIf { !cache.containsKey(it) }
        categoryDistanceCaches.keys.removeIf { !cache.containsKey(it) }

        val flattened = cache.values.flatten()
        allCandidates = flattened
        logger.info("Rebuilding global token index for ${flattened.size} candidates...")
        globalTokenIndex = TokenIndex(flattened)
        globalVocabulary = TokenVocabulary(flattened)
        globalDistanceCache = DistanceCache()
        logger.info(
            "Indices rebuilt for ${cache.size} categories and global index with ${globalTokenIndex.getAllCandidateIds().size} candidates",
        )
    }

    fun isCatalogReady(): Boolean = isCatalogReady.get()

    // Category keys are normalised to lowercase on both write (refreshCache)
    // and read (here) so lookups are case-insensitive. DB-identifier categories
    // arrive upper-cased (e.g. "db.dbo.QSTRED_DF.KOD_STR"); without this the
    // per-column index was missed and lookups leaked other columns' values.
    override fun getCandidates(category: String?): List<Candidate> =
        if (category != null) {
            cache[category.lowercase()] ?: emptyList()
        } else {
            // FZ-P1 T4 — precomputed at refresh (see [allCandidates]); no per-request flatten.
            allCandidates
        }

    override fun getTokenIndex(category: String?): TokenIndex =
        if (category != null) {
            // An explicit-but-unknown category must NOT silently fall back to
            // the global index — that returns every other column's candidates
            // and is exactly how a case-mismatched key served the wrong column.
            // Mirror getCandidates' empty-on-miss contract. Global is only for
            // the deliberate null (cross-category) lookup.
            categoryTokenIndices[category.lowercase()] ?: EMPTY_TOKEN_INDEX
        } else {
            globalTokenIndex
        }

    override fun getVocabulary(category: String?): TokenVocabulary =
        if (category != null) {
            // Same discipline as getTokenIndex: an explicit-but-unknown category must NOT fall back
            // to the global vocabulary — that would score every other column's candidates and is
            // exactly how a case-mismatched key served the wrong column. Empty-on-miss; the global
            // vocabulary is only for the deliberate null (cross-category) lookup.
            categoryVocabularies[category.lowercase()] ?: EMPTY_VOCABULARY
        } else {
            globalVocabulary
        }

    // FZ-P2 — consumed only by the `legacy` retrieval path (index-first rescores with a throwaway
    // cache). See [DistanceCache].
    override fun getDistanceCache(category: String?): DistanceCache =
        if (category != null) {
            categoryDistanceCaches[category.lowercase()] ?: globalDistanceCache
        } else {
            globalDistanceCache
        }

    fun close() {
        scope.cancel()
    }
}
