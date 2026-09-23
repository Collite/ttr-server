// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

/**
 * LP-P0 (`fuzzy.match:v2`, contracts §4.2) — how a query token matched a vocabulary token.
 *
 * v1 knows only an edit distance; the kind is derived from it ([EXACT] at 0, else [TYPO]) so v1
 * results carry a kind without changing a single score. [PREFIX] is v2-only.
 */
enum class MatchKind {
    /** `t == c` — quality 1.0. */
    EXACT,

    /** `1 ≤ d ≤ budget(len t)` — quality `1 − 0.15·d`. */
    TYPO,

    /** `c.startsWith(t)`, `len t ≥ 3`, `len t < len c` — quality `max(0.80, len t / len c)`. */
    PREFIX,
}

/**
 * The Elasticsearch `AUTO` fuzziness ladder: how many edits a query token of a given length may
 * carry. Short tokens get none — a 2-letter token one edit away from anything matches everything.
 */
object EditBudget {
    fun of(length: Int): Int =
        when {
            length <= 2 -> 0
            length <= 5 -> 1
            else -> 2
        }
}
