// SPDX-License-Identifier: Apache-2.0
package org.tatrman.llmgateway.auth

import org.slf4j.LoggerFactory
import org.tatrman.llmgateway.governance.KeyMint
import org.tatrman.llmgateway.governance.VirtualKeyRepo
import java.util.concurrent.ConcurrentHashMap

/**
 * The real, PG-backed data-plane key validator (LG-P4·S1·T4), replacing the config-backed interim as the
 * production path. A presented `ttrk-` key is SHA-256-hashed and looked up in `virtual_keys`; a row that
 * exists and is not revoked resolves to its [KeyPrincipal] (team + per-key overrides).
 *
 * **Cache (recorded choice, T4):** a tiny in-memory expiring map keyed by hash, TTL ≤ [ttlMs] (default
 * 30 s, contracts §1.8) — a hand-rolled map rather than caffeine, to add no dependency for a ~30-line need.
 * **Positive results only are cached**; a miss is never cached, so a just-issued key validates immediately
 * (regression) and a revoked key stops validating within the TTL. `last_used_at` is written at most once
 * per key per [touchThrottleMs] (default 60 s) so hot keys don't hammer PG on every request.
 *
 * **Fail closed on unconfigured teams:** a row whose team is not among [teamIds] (the `governance.yaml` teams)
 * does not validate. A team removed from the config stops its keys at the next boot; its `teams` row stays as
 * the FK target of those keys. One WARN per team id per process names the refused team.
 */
class PgKeyValidator(
    private val keys: VirtualKeyRepo,
    private val teamIds: Set<String>,
    private val ttlMs: Long = 30_000,
    private val touchThrottleMs: Long = 60_000,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : KeyValidator {
    private class Entry(
        val principal: KeyPrincipal,
        val expiresAtMs: Long,
    )

    private val cache = ConcurrentHashMap<String, Entry>() // key_hash → cached identity (positive only)
    private val lastTouchMs = ConcurrentHashMap<String, Long>() // keyId → last last_used_at write
    private val warnedTeams = ConcurrentHashMap.newKeySet<String>() // team ids already named in a WARN

    override fun validate(rawKey: String): KeyPrincipal? {
        if (!rawKey.startsWith(KeyMint.PREFIX)) return null
        val hash = KeyMint.hash(rawKey)
        val now = nowMs()

        val cached = cache[hash]?.takeIf { it.expiresAtMs > now }
        val principal =
            cached?.principal ?: run {
                val row = keys.findByHash(hash)?.takeIf { it.revokedAt == null } ?: return null // miss: NOT cached
                if (row.teamId !in teamIds) {
                    if (warnedTeams.add(row.teamId)) {
                        log.warn("refusing the keys of team '{}': it is not in the governance config", row.teamId)
                    }
                    return null // NOT cached, as a miss
                }
                KeyPrincipal(
                    keyId = row.id,
                    team = row.teamId,
                    budgetUsdOverride = row.budgetUsd,
                    rpmOverride = row.rpmLimit,
                ).also { cache[hash] = Entry(it, now + ttlMs) }
            }

        touchIfDue(principal.keyId, now)
        return principal
    }

    private fun touchIfDue(
        keyId: String,
        now: Long,
    ) {
        val last = lastTouchMs[keyId]
        if (last == null || now - last >= touchThrottleMs) {
            // Only write if we won the race to claim this window (keeps it ≤1 write/key/throttle-window).
            if (lastTouchMs.put(keyId, now).let { it == last }) keys.touchLastUsed(keyId)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(PgKeyValidator::class.java)
    }
}
