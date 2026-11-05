// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

/**
 * The period codes a recognition carries and the grain each names: `yyyyMM` a month, `yyyyQn` a quarter (Q-18),
 * `yyyyHn` a half-year (TS-6). A period table's `code_format` names a grain too, and a code meets the table only
 * when the two agree ([fits]): a quarter against a monthly table matches no row, and a month against a yearly one
 * would match the whole year's.
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
     * The grain of a period table's `code_format`, or null for a format of no grain chrono reads. A month's has `MM`
     * and no day, week, quarter or half — `yyyyMM`, `yyyy-MM`, `yyyy/MM`, `yyyy.MM` — and so does an empty one, the
     * default when the table declares none. A quarter's has a `Q`, a half-year's an `H`, and neither has a month, a
     * day or a week. Anything else — `yyyy`, `yyyyMMdd`, `yyyy-ww` — holds periods chrono does not name.
     */
    fun grainOfFormat(format: String): Grain? {
        if (format.isEmpty()) return Grain.MONTH
        if ('d' in format || 'w' in format) return null
        return when {
            'M' !in format && 'Q' in format && 'H' !in format -> Grain.QUARTER
            'M' !in format && 'H' in format && 'Q' !in format -> Grain.HALF
            "MM" in format && 'Q' !in format && 'H' !in format -> Grain.MONTH
            else -> null
        }
    }

    /** Does [code] name a period of the grain a table with [format] holds? A format of no grain fits no code. */
    fun fits(
        code: String,
        format: String,
    ): Boolean = grainOf(code)?.let { it == grainOfFormat(format) } == true
}
