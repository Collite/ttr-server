// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

/**
 * review-103 F3 · ruling 2 · decision D3 — how `fuzzy.match:v2` keeps a row that is NOT an all-exact
 * match under 1.0.
 *
 * §4.3's `S = P · orderBonus + ε·C` gives the order bonus to every matched position, prefix and typo
 * hits included, so a partial or typo match of a multi-token query reached ≥ 1.0 (`kovo brno stav`,
 * prefix · exact · prefix, scored 1.061). Every consumer reads ≥ 1.0 as "ordered exact" (design §5.5):
 * the resolver's member-EXACT class sits at 0.9999, so a unique partial member match was bound as
 * EXACT. This bounds every row whose query tokens are not ALL `exact`:
 *
 *  - [Mode.SCALE] (the default): `S' = min(ceiling, S / S_perfect(n))`, where `S_perfect(n)` is the
 *    ORDER BONUS an all-exact, in-order match of the same n-token query earns —
 *    `min(mult^(n(n−1)/2), maxOrderBonus)`, deliberately WITHOUT §4.3's `+ ε·C` (the lead's
 *    calibration, 2026-09-25). One linear factor per query, so the ORDER among the non-exact rows is
 *    unchanged. `S_perfect(1) = 1`: a one-token non-exact row (q ≤ 0.86 for a typo or a short
 *    prefix) can never reach 1.0 anyway, so it keeps §4.3's S byte for byte — dividing it by
 *    `1 + ε` only pushed single-token typo/prefix scores across the resolver's thresholds (an ED-2
 *    typo, 0.70 + ε·C, fell under `LIVE_STRONG` 0.70). Without the ε a multi-token row whose P is
 *    near 1 CAN scale to ≥ 1.0, so the CEILING is what guarantees every non-exact row lands below
 *    1.0 — it is the guard, and the configurable half of it. ⚠ Two non-exact rows that both scale to
 *    or above the ceiling tie at it; the stable sort then orders them by candidate order, not by S.
 *    It takes a query whose non-exact tokens carry a small share of the IDF weight.
 *  - [Mode.CAP]: `min(S, ceiling)` — the raw number, clipped.
 *  - [Mode.OFF]: §4.3's S untouched — the pre-fix engine, kept for calibration and rollback.
 *
 * A row whose query tokens are all `exact` keeps §4.3's S in EVERY mode, so ≥ 1.0 still means
 * "ordered exact" and an all-exact row always outranks a non-exact one.
 *
 * The library default is [DEFAULT] (scale, 0.99), the same the `lex-matcher` service ships (env
 * `FUZZY_V2_NORMALIZE_MODE` / `FUZZY_V2_NORMALIZE_CEILING`). v1 never runs this code, so it stays
 * byte-pinned whatever is configured here.
 */
data class V2Normalization(
    val mode: Mode = Mode.SCALE,
    val ceiling: Double = DEFAULT_CEILING,
) {
    init {
        // Checked in every mode: a ceiling nobody can use today is still a misconfiguration, and the
        // day someone flips `off` → `scale` is the wrong day to find it.
        require(ceiling > 0.0 && ceiling < 1.0) {
            "fuzzy.match.v2.normalize.ceiling must be > 0 and < 1.0, got $ceiling"
        }
    }

    enum class Mode(
        val wire: String,
    ) {
        SCALE("scale"),
        CAP("cap"),
        OFF("off"),
        ;

        companion object {
            /**
             * `scale` · `cap` · `off` (case-insensitive); blank ⇒ [default] (blank is an env var exported
             * empty — unset, not a choice). Anything else is an ERROR, never a silent default.
             */
            fun fromString(
                value: String?,
                default: Mode = SCALE,
            ): Mode {
                if (value.isNullOrBlank()) return default
                return entries.firstOrNull { it.wire == value.trim().lowercase() }
                    ?: throw IllegalArgumentException(
                        "fuzzy.match.v2.normalize.mode must be scale, cap or off, got '$value'",
                    )
            }
        }
    }

    /**
     * The score of a row that is NOT all-exact: [raw] is its §4.3 S, [perfect] the query's
     * `S_perfect(n)` (the all-exact in-order order bonus, no ε). An all-exact row never comes here.
     */
    fun normalize(
        raw: Double,
        perfect: Double,
    ): Double =
        when (mode) {
            Mode.SCALE -> minOf(ceiling, raw / perfect)
            Mode.CAP -> minOf(raw, ceiling)
            Mode.OFF -> raw
        }

    /** `mode=scale ceiling=0.99` — the startup log line's shape. */
    override fun toString(): String = "mode=${mode.wire} ceiling=$ceiling"

    companion object {
        const val DEFAULT_CEILING: Double = 0.99

        /** Scale under 0.99 — the library and the service default. */
        val DEFAULT: V2Normalization = V2Normalization()

        /** §4.3's S as specified before review-103 — the pre-fix numbers. */
        val OFF: V2Normalization = V2Normalization(Mode.OFF)
    }
}
