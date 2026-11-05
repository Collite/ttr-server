// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

import org.tatrman.text.Normalization
import java.time.LocalDate

/**
 * The period of each unit a conversation is about (#214): what an anaphoric word ("the previous year",
 * „předchozí měsíc“) reads against, and the year a date that names none leans on.
 *
 * A text names the periods it writes out ("in 2025", "March 2025", "Q2 2025", „ve 2. čtvrtletí 2025“), the
 * ones its deictic words say ("last year", „loni“, "this quarter"), and the ones its own anaphoric and
 * ambiguous words were read as. Those are read here the way chrono read them when that question was asked:
 * against the same question's own periods first, then against the questions before it. So the words chain
 * ("2025" → "And the previous year?" is 2024 → "And the previous year?" is 2023), and a word chrono could
 * not settle then (a „minulý rok“ that asked, an anaphoric word with no anchor) leaves its question naming
 * a period nobody knows.
 *
 * Nothing here reads a clock: the reference date is the request's.
 *
 * @param texts nearest first: the question the span is in, then the user's earlier questions, newest first.
 * @param spanWords the span's own relative words. They are taken out of the first text, so a word is never
 *   read against itself.
 */
class ConversationPeriods(
    texts: List<String>,
    private val reference: LocalDate,
    spanWords: List<PeriodWord> = emptyList(),
) {
    private val texts: List<String> =
        texts.mapIndexed { i, text ->
            val n = Normalization.fold(text).replace(WHITESPACE, " ")
            if (i == 0) {
                spanWords
                    .map { it.text.replace(WHITESPACE, " ") }
                    .distinct()
                    .fold(n) { rest, word -> rest.replace(word, " ") }
            } else {
                n
            }
        }

    /** What an anaphoric word of one unit reads against. */
    sealed interface Anchor {
        /** No question names a period of the unit: the reference date. */
        data object None : Anchor

        /** The nearest question that names a period of the unit names this one. */
        data class One(
            val index: Int,
        ) : Anchor

        /** The nearest question that names a period of the unit names several, or one nobody can say. */
        data class Unclear(
            val why: String,
        ) : Anchor
    }

    /** What a relative word reads as. */
    sealed interface Reading {
        /** One period; [inferred] when it was read against the conversation rather than the reference date. */
        data class One(
            val index: Int,
            val inferred: Boolean,
        ) : Reading

        /** An ambiguous word whose deictic and anaphoric readings differ: ask. */
        data class Two(
            val deictic: Int,
            val anaphoric: Int,
        ) : Reading

        /** An anaphoric reading with nothing to read against. It is never the reference date instead. */
        data class NoAnchor(
            val why: String,
        ) : Reading
    }

    /** Read the span's relative [word] against the conversation. */
    fun read(word: PeriodWord): Reading = resolve(word, anchor(word.unit))

    /** The anchor of an anaphoric word of [unit], read in the texts from [from] on. */
    fun anchor(
        unit: PeriodUnit,
        from: Int = 0,
    ): Anchor =
        anchors.getOrPut(unit to from) {
            (from until texts.size)
                .asSequence()
                .map { mentions(unit, it) }
                .firstOrNull { it.periods.isNotEmpty() || it.unclear != null || it.bare.isNotEmpty() }
                ?.asAnchor(unit)
                ?: Anchor.None
        }

    /** The year a date that names none leans on ([leanedYear]). */
    sealed interface LeanedYear {
        /**
         * Nothing to lean on: no text names a year, or the first that does names several. A date leans on
         * neither of several, and an older text does not settle them; it reads against the reference date.
         */
        data object None : LeanedYear

        /** The first text that names a year names this one. */
        data class One(
            val year: Int,
        ) : LeanedYear

        /**
         * The first text that names a year names one nobody can say: a „minulý rok“ chrono asked about, or an
         * anaphoric year with no anchor. A date in it is in a year nobody knows, and never the reference year:
         * both readings of an asked „minulý rok“ differ from it.
         */
        data class Unclear(
            val why: String,
        ) : LeanedYear
    }

    /**
     * The year a date that names none leans on: the year of the first text that names one, from [from] on.
     * Unlike [anchor], a bare four-digit number is no year here and no obstacle: a yearless month never
     * leaned on one.
     */
    fun leanedYear(from: Int = 0): LeanedYear {
        val first =
            (from until texts.size)
                .asSequence()
                .map { mentions(PeriodUnit.YEAR, it) }
                .firstOrNull { it.periods.isNotEmpty() || it.unclear != null }
                ?: return LeanedYear.None
        first.unclear?.let { return LeanedYear.Unclear(it) }
        return first.periods.singleOrNull()?.let { LeanedYear.One(it) } ?: LeanedYear.None
    }

    /** The years text [i] names — written, said, or read from its relative words. */
    fun yearsIn(i: Int): Set<Int> = mentions(PeriodUnit.YEAR, i).periods

    // ----- one text -----

    /**
     * @property periods the period indexes the text names.
     * @property unclear why the text names a period of the unit nobody can say, if it does.
     * @property bare four-digit numbers the text does not say are years (years only).
     */
    private class Mentions(
        val periods: Set<Int>,
        val unclear: String?,
        val bare: Set<Int>,
    ) {
        fun asAnchor(unit: PeriodUnit): Anchor =
            when {
                unclear != null -> Anchor.Unclear(unclear)
                bare.isNotEmpty() ->
                    Anchor.Unclear(
                        "the nearest question that names a year has ${bare.sorted().joinToString()} in it " +
                            "without saying it is a year",
                    )
                periods.size > 1 ->
                    Anchor.Unclear(
                        "the nearest question that names a ${unit.noun} names several " +
                            "(${periods.sorted().joinToString { unit.codeOf(it) }})",
                    )
                else -> Anchor.One(periods.single())
            }
    }

    private val anchors = HashMap<Pair<PeriodUnit, Int>, Anchor>()
    private val memo = HashMap<Pair<PeriodUnit, Int>, Mentions>()

    private fun mentions(
        unit: PeriodUnit,
        i: Int,
    ): Mentions =
        memo.getOrPut(unit to i) {
            val n = texts[i]
            val named = mutableSetOf<Int>()
            var unclear: String? =
                when (unit) {
                    PeriodUnit.YEAR -> {
                        named += NamedYear.writtenYears(n)
                        null
                    }
                    PeriodUnit.MONTH -> monthsIn(n, i, named)
                    PeriodUnit.QUARTER -> quartersIn(n, i, named)
                }
            // „poslední měsíc“ asked between the trailing days and the calendar period; the answer is not in the
            // conversation, so the question names a period nobody settled — as an asked „minulý rok“ does.
            TrailingPeriods.findAll(n).firstOrNull { it.unit.period == unit }?.let { t ->
                val calendar = unit.codeOf(unit.indexOf(reference) - 1)
                unclear = unclear ?: "the nearest question that names a ${unit.noun} asked between the last " +
                    "${t.unit.days} days and $calendar"
            }
            val words = PeriodWords.findAll(n, unit)
            words.filter { it.kind == PeriodWordKind.DEICTIC }.forEach { named += unit.indexOf(reference) + it.delta }
            val bare = if (unit == PeriodUnit.YEAR) NamedYear.bareYears(n) - named else emptySet()
            // An anaphoric word reads against its own question's periods first, the way the span's does.
            val ownNamesAny = named.isNotEmpty() || unclear != null || bare.isNotEmpty()
            val own = Mentions(named.toSet(), unclear, bare).takeIf { ownNamesAny }
            for (word in words.filter { it.kind != PeriodWordKind.DEICTIC }) {
                when (val r = resolve(word, own?.asAnchor(unit) ?: anchor(unit, i + 1))) {
                    is Reading.One -> named += r.index
                    // chrono asked which one this was; the answer is not in the conversation.
                    is Reading.Two -> {
                        val asked = "${unit.codeOf(r.deictic)} and ${unit.codeOf(r.anaphoric)} („${word.text}“)"
                        unclear = unclear ?: "the nearest question that names a ${unit.noun} asked between $asked"
                    }
                    is Reading.NoAnchor -> unclear = unclear ?: r.why
                }
            }
            Mentions(named.toSet(), unclear, bare)
        }

    private fun resolve(
        word: PeriodWord,
        anchor: Anchor,
    ): Reading {
        val deictic = word.unit.indexOf(reference) + word.delta
        return when (word.kind) {
            PeriodWordKind.DEICTIC -> Reading.One(deictic, inferred = false)
            PeriodWordKind.ANAPHORIC ->
                when (anchor) {
                    Anchor.None -> Reading.One(deictic, inferred = false)
                    is Anchor.One -> Reading.One(anchor.index + word.delta, inferred = true)
                    is Anchor.Unclear -> Reading.NoAnchor(anchor.why)
                }
            PeriodWordKind.AMBIGUOUS ->
                when (anchor) {
                    Anchor.None -> Reading.One(deictic, inferred = false)
                    is Anchor.One -> {
                        val anaphoric = anchor.index + word.delta
                        // The conversation's period is the current one: both readings agree, nothing to ask.
                        if (anaphoric ==
                            deictic
                        ) {
                            Reading.One(deictic, inferred = false)
                        } else {
                            Reading.Two(deictic, anaphoric)
                        }
                    }
                    // „minulý …“ might mean the anaphoric reading, and it has nothing to read against.
                    is Anchor.Unclear -> Reading.NoAnchor(anchor.why)
                }
        }
    }

    // ----- months a text writes out -----

    /** Adds the months text [i] names to [named]; returns why one cannot be said, if one cannot. */
    private fun monthsIn(
        n: String,
        i: Int,
        named: MutableSet<Int>,
    ): String? {
        val taken = mutableListOf<IntRange>()

        fun add(
            year: Int,
            month: Int,
            range: IntRange,
        ) {
            if (year !in NamedYear.PLAUSIBLE_YEARS || month !in 1..12) return
            named += year * 12 + month - 1
            taken += range
        }
        monthYearRe.findAll(n).forEach { m ->
            Months.ofWord(m.groupValues[1], withYear = true)?.let { add(m.groupValues[2].toInt(), it, m.range) }
        }
        numericDateRe.findAll(n).forEach { add(it.groupValues[2].toInt(), it.groupValues[1].toInt(), it.range) }
        isoDateRe.findAll(n).forEach { add(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.range) }
        periodCodeRe.findAll(n).forEach { add(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.range) }
        var unclear: String? = null
        // The last month of a year is December, in its year or the year a yearless month gets (✅R215-R).
        for (found in NumberedPeriods.lastMonths(n)) {
            taken += found.range
            if (found.year == null && unreadYear(n, i)) {
                unclear = unclear ?: "the nearest question that names a month names December in a year chrono " +
                    "cannot read"
                continue
            }
            val year =
                found.year ?: when (val leaned = leanedYear(i)) {
                    is LeanedYear.One -> leaned.year
                    is LeanedYear.Unclear -> {
                        unclear = unclear ?: "the nearest question that names a month names December in a year " +
                            "nobody can say (${leaned.why})"
                        continue
                    }
                    LeanedYear.None -> reference.year.takeIf { reference.monthValue == DECEMBER }
                }
            if (year == null) {
                unclear = unclear ?: "the nearest question that names a month names December with no year"
            } else {
                named += year * 12 + DECEMBER - 1
            }
        }
        for (m in wordRe.findAll(n)) {
            if (taken.any { it.overlaps(m.range) }) continue
            val month = Months.ofWord(m.value, withYear = false) ?: continue
            if (m.value in VERB_MONTHS && !prepositionBeforeRe.containsMatchIn(n.substring(0, m.range.first))) continue
            // A month with no year is in the year chrono leaned it on; a future one with none, chrono asked about;
            // one in a year nobody settled is in a year nobody knows.
            val year =
                when (val leaned = leanedYear(i)) {
                    is LeanedYear.One -> leaned.year
                    is LeanedYear.Unclear -> {
                        unclear = unclear ?: "the nearest question that names a month names ${m.value} in a year " +
                            "nobody can say (${leaned.why})"
                        continue
                    }
                    LeanedYear.None -> reference.year.takeIf { month <= reference.monthValue }
                }
            if (year == null) {
                unclear = unclear ?: "the nearest question that names a month names ${m.value} with no year"
            } else {
                named += year * 12 + month - 1
            }
        }
        return unclear
    }

    // ----- quarters a text writes out -----

    /** Adds the quarters text [i] names to [named]; returns why one cannot be said, if one cannot. */
    private fun quartersIn(
        n: String,
        i: Int,
        named: MutableSet<Int>,
    ): String? {
        var unclear: String? = null
        // Those with a year of their own come first; a yearless one is in the year chrono leaned it on.
        for (found in NumberedPeriods.quarters(n)) {
            val q = found.number
            if (found.year == null && unreadYear(n, i)) {
                unclear = unclear ?: "the nearest question that names a quarter names Q$q in a year chrono cannot read"
                continue
            }
            val year =
                found.year ?: when (val leaned = leanedYear(i)) {
                    is LeanedYear.One -> leaned.year
                    is LeanedYear.Unclear -> {
                        unclear = unclear ?: "the nearest question that names a quarter names Q$q in a year " +
                            "nobody can say (${leaned.why})"
                        continue
                    }
                    LeanedYear.None -> reference.year.takeIf { q <= PeriodUnit.QUARTER.indexOf(reference) % 4 + 1 }
                }
            if (year == null) {
                unclear = unclear ?: "the nearest question that names a quarter names Q$q with no year"
            } else {
                named += year * 4 + q - 1
            }
        }
        return unclear
    }

    /**
     * Does text [i] say a year chrono cannot read: a phrase it reads nowhere ([YearPhrases]: "Q2 next year"), or a
     * four-digit number that is no amount where it reads no year ("Q2 sales 2025")? A quarter or December it names with
     * no year of its own is then in a year nobody can say, not in the one a yearless period leans on (RR13).
     */
    private fun unreadYear(
        n: String,
        i: Int,
    ): Boolean = YearPhrases.unreadIn(n) || (yearsIn(i).isEmpty() && NamedYear.bareYears(n).isNotEmpty())

    private companion object {
        const val DECEMBER = 12
        val VERB_MONTHS = setOf("may", "march")
        val WHITESPACE = Regex("""\s+""")

        /** A month word, then an optional day, then a year: "March 2025", „15. března 2025“, "October 15, 2025". */
        val monthYearRe = Regex("""\b([a-z]+)\.?(?:\s+\d{1,2}(?:st|nd|rd|th)?\.?,?)?\s+(\d{4})\b""")
        val numericDateRe = Regex("""\b\d{1,2}\s*[./]\s*(\d{1,2})\s*[./]\s*(\d{4})\b""")
        val isoDateRe = Regex("""\b(\d{4})-(\d{1,2})(?:-\d{1,2})?\b""")
        val periodCodeRe = Regex("""\b(?:period|obdobi)\s+(\d{4})(\d{2})\b""")
        val wordRe = Regex("""\b[a-z]+\b""")

        /** "may" and "march" are verbs too; a yearless one is a month only after a preposition. */
        val prepositionBeforeRe = Regex("""\b(?:in|for|of|during|since|until|from|through|by|to)\s+$""")
    }
}
