// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.governance

import io.lettuce.core.ScriptOutputType
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.tatrman.llmgateway.store.RedisConn
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-key rolling-window rate limiter (D-4, contracts §4): at most `limit` **admitted** requests in any 60 s,
 * the window ending now. The window is a sliding log — a sorted set per key (`llm-gateway:rlw:{keyId}`,
 * one member per admitted request, scored by its time in ms): drop what fell out of the window, count what
 * is left, and only when there is room add this request. A refused request is not recorded, so a caller
 * that retries while refused does not push its own window further out. Prune, count, add and PEXPIRE run
 * in **one Lua script** so they can never split (the atomicity the read-modify-write anti-pattern FI-6
 * warns about). State is in Redis, so the limit holds across replicas — two pods share one log.
 *
 * The log can hold more than `limit` entries — the limit was lowered, or two replicas briefly read different
 * team limits — so `Retry-After` is the time until enough entries have left to bring the count under the
 * limit, not until the oldest one leaves. An admit that a later admission step refuses (the money budget)
 * hands its entry back with [release], so a request that never went upstream does not hold a slot.
 *
 * Every refusal is counted (`llm_gateway_ratelimit_refused_total`), but only a key's first refusal after an
 * admit is logged (on each replica): a caller retrying through its refusals writes one line, not one per retry.
 *
 * Until 0.11.7 the window was two adjacent minute buckets, the current one's count plus the whole previous
 * one's, every request counted: a steady caller was held to about half its limit per minute, and one
 * retrying through its refusals stayed refused.
 *
 * **Fail-open on a Redis outage** (⚑ Bora, one boolean [failOpen]): a down Redis must not take the data
 * plane down — the request is allowed, logged, and counted on a metric; readiness already reports Redis
 * DOWN (LG-P1). Storeless boots (no Redis) simply don't rate-limit.
 */
class RateLimiter(
    private val redis: RedisConn?,
    private val metrics: MeterRegistry? = null,
    private val failOpen: Boolean = true,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    data class Decision(
        val allowed: Boolean,
        val retryAfterSeconds: Long,
        /** The log entry this admit recorded, for [release]; null when nothing was recorded. */
        val member: String? = null,
    )

    // Keys whose latest decision here was a refusal: the refusal line is written as a key enters, and the
    // next admit takes it out. Only validated key ids reach check(), so it holds at most one entry per key.
    private val refusedKeys = ConcurrentHashMap.newKeySet<String>()

    /** effectiveLimit = min(team rpm, per-key override) — min-wins (D-3); null/≤0 ⇒ unlimited. */
    fun check(
        keyId: String,
        teamRpm: Int?,
        keyRpmOverride: Int?,
    ): Decision {
        val limit = listOfNotNull(teamRpm, keyRpmOverride).minOrNull() ?: return ALLOW
        if (limit <= 0) return ALLOW
        val r = redis ?: return ALLOW // storeless dev boot — no limiting

        val now = nowMs()
        val member = "$now:${UUID.randomUUID()}"
        val waitMs =
            try {
                r.sync().eval<Long>(
                    LUA,
                    ScriptOutputType.INTEGER,
                    arrayOf("$PREFIX$keyId"),
                    now.toString(),
                    WINDOW_MS.toString(),
                    limit.toString(),
                    member,
                )
            } catch (e: Exception) {
                log.warn("rate-limit Redis error for key {} — failing {}", keyId, if (failOpen) "open" else "closed", e)
                metrics?.counter("llm_gateway_ratelimit_redis_error_total")?.increment()
                return if (failOpen) ALLOW else Decision(false, WINDOW_MS / 1000)
            }
        if (waitMs <= 0) {
            refusedKeys.remove(keyId) // the key's next refusal is logged again
            return Decision(true, 0, member)
        }
        metrics?.counter("llm_gateway_ratelimit_refused_total")?.increment()
        // not WARN: a refusal is the limiter working; the line says who is being held, and for how long
        if (refusedKeys.add(keyId)) {
            log.info(
                "rate limit: key {} refused at {} requests/min, retry in {} ms (until admitted, refusals only counted)",
                keyId,
                limit,
                waitMs,
            )
        }
        return Decision(false, (waitMs + 999) / 1000)
    }

    /**
     * Give back the slot an admit took ([Decision.member]) when the request is refused later in admission and
     * never goes upstream. Best effort: on a Redis error the slot simply stays until it ages out.
     */
    fun release(
        keyId: String,
        member: String,
    ) {
        val r = redis ?: return
        try {
            r.sync().zrem("$PREFIX$keyId", member)
        } catch (e: Exception) {
            log.warn("rate-limit Redis error releasing a slot for key {}", keyId, e)
            metrics?.counter("llm_gateway_ratelimit_redis_error_total")?.increment()
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(RateLimiter::class.java)
        const val PREFIX = "llm-gateway:rlw:"
        const val WINDOW_MS = 60_000L
        val ALLOW = Decision(true, 0)

        // KEYS[1] the key's log; ARGV now ms, window ms, limit, this request's member. Drop the requests at or
        // before now − window, and admit (ZADD, PEXPIRE the log to the window) while fewer than limit remain:
        // returns 0. Refused: nothing recorded; returns the ms until the count drops below the limit (≥ 1) —
        // when the entry at rank n − limit leaves (ranks from 0, oldest first), with n the entries left. With
        // n = limit that is the oldest. One round trip, indivisible.
        val LUA =
            """
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
            local n = redis.call('ZCARD', KEYS[1])
            if n >= limit then
              local freeing = redis.call('ZRANGE', KEYS[1], n - limit, n - limit, 'WITHSCORES')
              return math.max(1, tonumber(freeing[2]) + window - now)
            end
            redis.call('ZADD', KEYS[1], now, ARGV[4])
            redis.call('PEXPIRE', KEYS[1], window)
            return 0
            """.trimIndent()
    }
}
