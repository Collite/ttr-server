// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.governance

import io.lettuce.core.ScriptOutputType
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.tatrman.llmgateway.store.RedisConn
import java.util.UUID

/**
 * Per-key rolling-window rate limiter (D-4, contracts §4): at most `limit` **admitted** requests in any 60 s,
 * the window ending now. The window is a sliding log — a sorted set per key (`llm-gateway:rlw:{keyId}`,
 * one member per admitted request, scored by its time in ms): drop what fell out of the window, count what
 * is left, and only when there is room add this request. A refused request is not recorded, so a caller
 * that retries while refused does not push its own window further out. Prune, count, add and PEXPIRE run
 * in **one Lua script** so they can never split (the atomicity the read-modify-write anti-pattern FI-6
 * warns about). State is in Redis, so the limit holds across replicas — two pods share one log.
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
    )

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
        val waitMs =
            try {
                r.sync().eval<Long>(
                    LUA,
                    ScriptOutputType.INTEGER,
                    arrayOf("$PREFIX$keyId"),
                    now.toString(),
                    WINDOW_MS.toString(),
                    limit.toString(),
                    "$now:${UUID.randomUUID()}",
                )
            } catch (e: Exception) {
                log.warn("rate-limit Redis error for key {} — failing {}", keyId, if (failOpen) "open" else "closed", e)
                metrics?.counter("llm_gateway_ratelimit_redis_error_total")?.increment()
                return if (failOpen) ALLOW else Decision(false, WINDOW_MS / 1000)
            }
        if (waitMs <= 0) return ALLOW
        // not WARN: a refusal is the limiter working; the line and the counter say who is being held, and for how long
        log.info("rate limit: key {} refused at {} requests/min, retry in {} ms", keyId, limit, waitMs)
        metrics?.counter("llm_gateway_ratelimit_refused_total")?.increment()
        return Decision(false, (waitMs + 999) / 1000)
    }

    private companion object {
        val log = LoggerFactory.getLogger(RateLimiter::class.java)
        const val PREFIX = "llm-gateway:rlw:"
        const val WINDOW_MS = 60_000L
        val ALLOW = Decision(true, 0)

        // KEYS[1] the key's log; ARGV now ms, window ms, limit, this request's member. Drop the requests at or
        // before now − window, and admit (ZADD, PEXPIRE the log to the window) while fewer than limit remain:
        // returns 0. Refused: nothing recorded; returns the ms until the oldest admitted request leaves the
        // window (≥ 1). One round trip, indivisible.
        val LUA =
            """
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now - window)
            if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[3]) then
              local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
              return math.max(1, tonumber(oldest[2]) + window - now)
            end
            redis.call('ZADD', KEYS[1], now, ARGV[4])
            redis.call('PEXPIRE', KEYS[1], window)
            return 0
            """.trimIndent()
    }
}
