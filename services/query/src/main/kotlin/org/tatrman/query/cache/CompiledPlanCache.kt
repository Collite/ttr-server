// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.cache

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.RemovalCause
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.Language
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * LRU compiled-plan cache. Per Round 7 §7.C the cache stores post-Translator
 * output (decision C-3a): the Validator runs every call because security
 * predicates depend on `user_id`, and cheap re-application beats per-user
 * cache fanout.
 *
 * Key components:
 *   - `modelVersion`: snapshot the plan was compiled against. Changing the
 *     metadata version invalidates every entry.
 *   - `sourceHash`: SHA-256 of the source text (lowercase hex).
 *   - `sourceLanguage`: proto enum value as int.
 *   - `paramSignature`: SHA-256 of `name:type` pairs joined with `|`,
 *     sorted by name. Names + TYPES only — values vary per execution while
 *     the compiled plan stays valid.
 *
 * `record(key, plan)` overwrites; the eviction listener counts LRU evictions
 * separately from explicit invalidations.
 */
class CompiledPlanCache(
    private val maxEntries: Long,
    expireAfterWrite: Duration,
    private val ticker: () -> Instant = Instant::now,
) {
    private val hits = AtomicLong()
    private val misses = AtomicLong()
    private val invalidations = AtomicLong()
    private val evictions = AtomicLong()
    private val currentModelVersion =
        java.util.concurrent.atomic
            .AtomicReference("")

    private val cache: Cache<CacheKey, CachedPlan> =
        Caffeine
            .newBuilder()
            .maximumSize(maxEntries)
            .expireAfterWrite(expireAfterWrite)
            .removalListener<CacheKey, CachedPlan> { _, _, cause ->
                if (cause == RemovalCause.SIZE) evictions.incrementAndGet()
            }.recordStats()
            .build()

    fun lookup(key: CacheKey): CachedPlan? {
        invalidateIfModelVersionChanged(key.modelVersion)
        val v = cache.getIfPresent(key)
        if (v == null) {
            misses.incrementAndGet()
        } else {
            hits.incrementAndGet()
        }
        return v
    }

    fun record(
        key: CacheKey,
        plan: CachedPlan,
    ) {
        invalidateIfModelVersionChanged(key.modelVersion)
        cache.put(key, plan)
    }

    fun stats(): Stats =
        Stats(
            entries = cache.estimatedSize(),
            maxEntries = maxEntries,
            hits = hits.get(),
            misses = misses.get(),
            invalidations = invalidations.get(),
            evictions = evictions.get(),
            currentModelVersion = currentModelVersion.get(),
        )

    /**
     * Force-drop every cached plan (operator-triggered refresh via `POST /refresh`). Returns the
     * number of entries evicted. Unlike [invalidateIfModelVersionChanged] this is unconditional and
     * leaves `currentModelVersion` untouched, so the next request re-seeds it as usual.
     */
    fun clear(): Long {
        val n = cache.estimatedSize()
        cache.invalidateAll()
        invalidations.addAndGet(n)
        log.info("Cleared {} cache entries on operator refresh", n)
        return n
    }

    private fun invalidateIfModelVersionChanged(newVersion: String) {
        if (newVersion.isEmpty()) return
        val prev = currentModelVersion.get()
        if (prev.isEmpty()) {
            currentModelVersion.compareAndSet(prev, newVersion)
            return
        }
        if (prev == newVersion) return
        if (currentModelVersion.compareAndSet(prev, newVersion)) {
            val n = cache.estimatedSize()
            cache.invalidateAll()
            invalidations.addAndGet(n)
            log.info("Invalidated {} cache entries on model_version change {} → {}", n, prev, newVersion)
        }
    }

    data class Stats(
        val entries: Long,
        val maxEntries: Long,
        val hits: Long,
        val misses: Long,
        val invalidations: Long,
        val evictions: Long,
        val currentModelVersion: String,
    )

    companion object {
        private val log = LoggerFactory.getLogger(CompiledPlanCache::class.java)

        /** SHA-256 hex of [text]. Same algorithm both code and tests use to build CacheKey.sourceHash. */
        fun sourceHash(text: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /**
         * Stable hash of parameter NAMES + TYPES (not values) so executions
         * with different bound values share a compiled plan.
         */
        fun paramSignature(parameters: List<ParameterBinding>): String {
            if (parameters.isEmpty()) return "noparams"
            val canonical =
                parameters
                    .sortedBy { it.name }
                    .joinToString("|") { "${it.name}:${it.type}" }
            return MessageDigest
                .getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}

data class CacheKey(
    val modelVersion: String,
    val sourceHash: String,
    val sourceLanguage: Language,
    val paramSignature: String,
    /**
     * The layer the caller stated (`RunRequest.source_schema`): the detector decides with it, so one text stated DB
     * and stated ER are two compiled plans (review-123 R3 — the Flight edge states it per statement).
     */
    val sourceSchema: SchemaCode = SchemaCode.SCHEMA_CODE_UNSPECIFIED,
)

data class CachedPlan(
    val erPlan: PlanNode,
    val requiredParameters: List<ParameterBinding>,
    val predictedSchemaFingerprint: String,
    val cachedAt: Instant,
    val effectiveSchema: SchemaCode = SchemaCode.SCHEMA_CODE_UNSPECIFIED,
    val physicalPlan: PlanNode? = null,
    val detectionMessages: List<ResponseMessage> = emptyList(),
    /**
     * ttr-server#232 — the translator's WARNINGs on [physicalPlan] (a join it could not condition), so a cache hit
     * says what the miss said. An ER plan keeps none: its DB re-entry runs on every call and warns afresh.
     */
    val joinWarnings: List<ResponseMessage> = emptyList(),
)
