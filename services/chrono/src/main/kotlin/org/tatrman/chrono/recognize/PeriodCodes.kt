// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

/**
 * The period codes a recognition carries and the grain each names: `yyyyMM` a month, `yyyyQn` a quarter (Q-18),
 * `yyyyHn` a half-year (TS-6). A period table's `code_format` names a grain too, and a code meets the table only
 * when the two agree ([fits]): a quarter against a monthly table matches no row.
 */
internal object PeriodCodes {
    enum class Grain { MONTH, QUARTER, HALF }

    val MONTH = Regex("""(\d{4})(\d{2})""")
    val QUARTER = Regex("""(\d{4})Q([1-4])""")
    val HALF = Regex("""(\d{4})H([12])""")

    /** The code of quarter or half [number] of [year]: `2026Q2`, `2026H1`; [letter] is `Q` or `H`. */
    fun numbered(
        year: Int,
        letter: Char,
        number: Int,
    ): String = "%04d%c%d".format(year, letter, number)

    /** The grain of a canonical code; null for a code that is none of the three. */
    fun grainOf(code: String): Grain? =
        when {
            MONTH.matches(code) -> Grain.MONTH
            QUARTER.matches(code) -> Grain.QUARTER
            HALF.matches(code) -> Grain.HALF
            else -> null
        }

    /**
     * The grain of a period table's `code_format`: one with a `Q` is quarterly, one with an `H` half-yearly, and
     * anything else monthly — `yyyyMM`, `yyyy-MM`, `yyyy.MM`, and the default when the table declares none.
     */
    fun grainOfFormat(format: String): Grain =
        when {
            'Q' in format -> Grain.QUARTER
            'H' in format -> Grain.HALF
            else -> Grain.MONTH
        }

    /** Does [code] name a period of the grain a table with [format] holds? */
    fun fits(
        code: String,
        format: String,
    ): Boolean = grainOf(code) == grainOfFormat(format)
}
