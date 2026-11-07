// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.scan

import org.tatrman.chrono.recognize.ChronoKind
import org.tatrman.chrono.recognize.ChronoReading
import org.tatrman.chrono.recognize.ChronoRecognition
import org.tatrman.chrono.recognize.DateRecognizer
import org.tatrman.chrono.recognize.MonthWords
import org.tatrman.chrono.recognize.Months
import org.tatrman.chrono.recognize.NamedYear
import org.tatrman.chrono.recognize.PeriodUnit
import org.tatrman.chrono.recognize.PeriodWords
import org.tatrman.chrono.recognize.SpanReading
import org.tatrman.chrono.recognize.WindowPeriods
import org.tatrman.grounding.lexicon.GroundingSlice
import org.tatrman.text.Normalization
import java.time.LocalDate

/**
 * TS-P0 S0.3 (contracts §3) — the time phrases in a text, found by chrono's own rules, so that no rule about which
 * words are a time phrase lives outside chrono (I-3). Every window of one to [MAX_WINDOW] tokens is read by
 * [DateRecognizer] exactly as `Ground` reads a span, with the text as the question and the earlier questions after
 * it. A window is a candidate when:
 * - it reads as a period ([ChronoReading.Recognized] at or above [threshold]) or as one the conversation gives
 *   nothing to read against ([ChronoReading.Unanchored]) (§3.2);
 * - none of its words is idle. A smaller window inside it reading the same makes the rest idle, unless the rest is
 *   part of another time read across it („od začátku roku“ in "od začátku roku 2025", in „loni od začátku roku“) or
 *   a period noun of the phrase (an adjective and a noun before it, a noun after it: "loňského roku", "Q2 of the
 *   year", "fiscal year 2025 Q4"). "za minulý měsíc" is "minulý měsíc", and so is "minulý měsíc po týdnech" (a
 *   breakdown). Which column a word targets („splatné“) is not part of the reading;
 * - it is no number: not a bare six-digit run in a longer text (a document number; a period only with *období* /
 *   *period* or as the whole text, §3.4), not a four-digit run the words around it make an amount ("more than 2000
 *   units", as [NamedYear.bareYears] reads it), and not a wider window that reads only such a run as a year beside a
 *   year word ("last year for 2000"). A bare four-digit year alone is a span: the consumer decides (⚑TS-4).
 *
 * A window that names no one period ([SpanReading.NoOnePeriod]: "next Q2", "excluding Q4", "Q2 since 2020", "Q2 vs
 * the same quarter last year", „1. pololetí letos a loni“) keeps every candidate it touches out: a part read alone
 * („Q2“, "Q4", "last year") would be a confident wrong period. Of the candidates left, the longest wins over any it
 * overlaps, then the earliest (§3.3). A window that names several periods side by side ([SpanReading.Several]) keeps
 * the spans it touches only when they are all of it — every time word in it lies in one, and each reads the same
 * without the question: "Q2 2025 vs Q2 2024" and "this quarter and last month" give two spans each; „leden až březen
 * 2025“ (*leden* borrows the year), „1. a 2. čtvrtletí“ and „minulý a tento měsíc“ (a word left out) give none. A
 * window of seven tokens can cut through a phrase: the phrase it cuts is read whole, and that reading decides
 * ("2025 vs Q2 2024" is a cut of "Q2 2025 vs Q2 2024"; "month of the year a year ago" of "the last month of the year
 * a year ago").
 *
 * Three kinds of word are blanked before the windows are read, as the caller blanks a quoted literal: a word with a
 * month's stem inside that is no month (*zařízení*, *červená* — [Months.find] reads a stem anywhere, which is right
 * for a span already known to be a date, not for a whole question), an English month abbreviation with no day or
 * year beside it ("Jan Novák"), and *may* / *march* as a verb ("may we see …"). Offsets are UTF-16 code units into
 * the text as given: it is split at whitespace and blanked in place, never rewritten, so a decomposed letter or a
 * surrogate pair moves no offset.
 *
 * The slice narrows nothing a rule reads on its own: a window with a slice term may read scope-free, as `Ground` does
 * (RV-42), and [Found.trigger] names the term, else the kind of period read. `Scan` never calls the LLM fallback.
 */
class SpanScanner(
    private val recognizer: DateRecognizer = DateRecognizer(),
    private val threshold: Double = 0.6,
) {
    /** One time phrase: `text[start, end)`, UTF-16 offsets. */
    data class Found(
        val start: Int,
        val end: Int,
        val text: String,
        val confidence: Double,
        val trigger: String,
    )

    /** A window of tokens [first]..[last], inclusive. */
    private data class Window(
        val first: Int,
        val last: Int,
    ) {
        val size: Int get() = last - first + 1

        fun contains(other: Window): Boolean = first <= other.first && other.last <= last

        fun overlaps(other: Window): Boolean = first <= other.last && other.first <= last
    }

    private class Span(
        val window: Window,
        val reading: ChronoReading,
        val confidence: Double,
    )

    /**
     * The time phrases in [text], in text order, read against [reference]. [priorQuestions] are the user's earlier
     * questions, newest first, as `GroundingContext.prior_questions` carries them.
     */
    fun scan(
        text: String,
        reference: LocalDate,
        triggers: GroundingSlice = GroundingSlice.empty(CHRONO_KIND),
        priorQuestions: List<String> = emptyList(),
    ): List<Found> {
        val working = blankNonMonths(text)
        val tokens = tokens(working)
        if (tokens.isEmpty()) return emptyList()
        val words = tokens.map { Normalization.fold(working.substring(it.first, it.last + 1)) }
        val timeWords = words.indices.map { timeWord(words, it, working, tokens) }
        val context = listOf(text) + priorQuestions
        val anchored =
            tokens.indices.map {
                anchor(words[it], working.substring(tokens[it].first, tokens[it].last + 1), triggers)
            }
        val read = mutableMapOf<Window, SpanReading>()
        for (first in tokens.indices) {
            for (last in first until minOf(tokens.size, first + MAX_WINDOW)) {
                // No rule reads a window without one anchor word: the rest would only read as nothing.
                if ((first..last).none { anchored[it] }) continue
                val w = Window(first, last)
                recognizer.readSpan(textOf(working, tokens, w), reference, triggers, context)?.let { read[w] = it }
            }
        }
        val textOfWindow = { w: Window -> textOf(working, tokens, w) }
        val candidates =
            read.mapNotNull { (w, r) ->
                val reading = (r as? SpanReading.Read)?.reading ?: return@mapNotNull null
                val confidence = confidenceOf(reading) ?: return@mapNotNull null
                when {
                    !phraseWords(w, words, timeWords) -> null
                    bareCode(working, tokens, w) -> null
                    amount(working, tokens, w, reading) -> null
                    // A year no word places is a span only as the bare number (§3.4): "last year for 2000" is not 2000.
                    w.size > 1 &&
                        unplacedNumber(r, textOfWindow(w)) &&
                        PeriodWords.findAll(Normalization.fold(textOfWindow(w)), PeriodUnit.YEAR).isNotEmpty() -> null
                    read.any { (inner, r2) -> idle(w, inner, r2, reading, read, words, textOfWindow) } -> null
                    else -> Span(w, reading, confidence)
                }
            }

        // The phrase a window cuts through: the window and every candidate crossing its edge, read whole. A window of
        // seven tokens may end inside a longer phrase ("the last month of the year a year ago"), or begin inside the
        // phrase before it ("2025 vs Q2 2024" out of "Q2 2025 vs Q2 2024").
        fun whole(w: Window): Pair<Window, SpanReading?> {
            val crossing = candidates.filter { it.window.overlaps(w) && !w.contains(it.window) }
            if (crossing.isEmpty()) return w to read[w]
            val u =
                Window(
                    minOf(w.first, crossing.minOf { it.window.first }),
                    maxOf(w.last, crossing.maxOf { it.window.last }),
                )
            return u to (read[u] ?: recognizer.readSpan(textOfWindow(u), reference, triggers, context))
        }

        // Only the phrases a linking word connects can change or suppress one another.
        fun linked(w: Window): Boolean {
            val times = (w.first..w.last).filter { timeWords[it] }
            return times.zipWithNext().all { (left, right) ->
                val gap = words.subList(left + 1, right)
                val relation = gap.joinToString(" ")
                // Use the same linking vocabulary for one-word and multi-word bounds ("up to Q3").
                // A bound's own unread phrase cannot reach back across an unrelated clause.
                val bound =
                    (left + 1 until right).lastOrNull { start ->
                        val tail = words.subList(start, right).joinToString(" ")
                        LINK.containsMatchIn(tail) &&
                            !COMPARISON_LINK.containsMatchIn(tail) &&
                            (right..w.last).any { end -> read[Window(start, end)] == SpanReading.NoOnePeriod }
                    }
                val separateBound =
                    bound != null &&
                        (left + 1 until bound).any { !SMALL.matches(words[it]) }
                gap.all { SMALL.matches(it) } ||
                    (LINK.containsMatchIn(relation) && (!separateBound || COMPARISON_LINK.containsMatchIn(relation))) ||
                    JOIN_TO_TIME.containsMatchIn(relation)
            }
        }

        // A phrase that names no one period keeps every linked part of it out.
        val noOne =
            read.filterValues { it == SpanReading.NoOnePeriod }.keys.filter(::linked).mapNotNull { w ->
                whole(w).let { (u, r) -> u.takeIf { r == SpanReading.NoOnePeriod && linked(u) } }
            }
        val spans = candidates.filter { c -> noOne.none { it.overlaps(c.window) } }
        val chosen = mutableListOf<Span>()
        for (s in spans.sortedWith(compareByDescending<Span> { it.window.size }.thenBy { it.window.first })) {
            if (chosen.none { it.window.overlaps(s.window) }) chosen += s
        }
        // A phrase naming several periods keeps its parts only when they are all of it: every time word in it is in
        // a part, and no part reads otherwise without the question — „leden“ in „leden až březen 2025“ borrows its
        // year, and „1. a 2. čtvrtletí“ leaves „1.“ out.
        for (w0 in read.filterValues { it == SpanReading.Several }.keys) {
            if (!linked(w0)) continue
            val (w, r) = whole(w0)
            if (!linked(w)) continue
            val parts = chosen.filter { w.overlaps(it.window) }
            if (parts.isEmpty()) continue
            val covered =
                (w.first..w.last).all { i ->
                    !timeWords[i] || parts.any { i in it.window.first..it.window.last }
                }
            val alone =
                parts.all { p ->
                    val own = recognizer.readSpan(textOfWindow(p.window), reference, triggers, priorQuestions)
                    own != null && sameReading(own, p.reading)
                }
            if (r == SpanReading.NoOnePeriod || !covered || !alone) chosen.removeAll(parts)
        }
        return chosen.sortedBy { it.window.first }.map { s ->
            val start = tokens[s.window.first].first
            val end = tokens[s.window.last].last + 1
            val span = text.substring(start, end)
            Found(start, end, span, s.confidence, triggers.matched(span)?.text ?: ruleOf(s.reading))
        }
    }

    /**
     * Is [inner], a smaller window inside [w] that reads the same, all [w] says — the words [w] adds idle? They are
     * not when a window across them reads another time: „od začátku roku“ in "od začátku roku 2025" and in „loni od
     * začátku roku“ (a number no word places as a year is none: "for 2000" in "Q2 last year for 2000 customers"). Nor
     * when they are a period noun of the phrase: an adjective and a noun before it ("fiscal year 2025 Q4"), or a noun
     * after it, with *of / the / that / in / for* between ("Q2 of the year", "loňského roku") — not the first *year*
     * of "year over year".
     */
    private fun idle(
        w: Window,
        inner: Window,
        innerReading: SpanReading,
        reading: ChronoReading,
        read: Map<Window, SpanReading>,
        words: List<String>,
        textOf: (Window) -> String,
    ): Boolean {
        if (inner == w || !w.contains(inner) || !sameReading(innerReading, reading)) return false
        val prefix = w.first until inner.first
        val suffix = inner.last + 1..w.last
        val extra = { i: Int -> i < inner.first || i > inner.last }
        // Another time the extra words are part of: one that reads otherwise, and that no smaller window clear of the
        // extra words reads the same ("last year by region" is "last year"'s; „od začátku roku“ is its own).
        val anotherTime =
            read.any { (v, r) ->
                r is SpanReading.Read &&
                    v != w &&
                    w.contains(v) &&
                    (v.first..v.last).any(extra) &&
                    !sameInterval(r, reading) &&
                    !unplacedNumber(r, textOf(w)) &&
                    read.none { (u, ru) -> u != v && v.contains(u) && (u.first..u.last).none(extra) && alike(ru, r) }
            }
        if (anotherTime) return false
        val nounBefore =
            prefix.all { i ->
                val calendarNoun =
                    i > prefix.first && YEAR_NOUN.matches(words[i]) && CALENDAR_NAME.matches(words[i - 1])
                val ownNoun = NOUN.matches(words[i]) && nounOfReading(words[i], reading)
                ADJECTIVE.matches(words[i]) || ownNoun || calendarNoun
            }
        val nounAfter =
            suffix.isEmpty() ||
                (
                    NOUN.matches(words[suffix.last]) &&
                        words.getOrNull(suffix.last + 1) !in MEASURE_AFTER &&
                        suffix.all { phraseWord(words[it]) || GLUE.matches(words[it]) }
                )
        return !(nounBefore && nounAfter)
    }

    /** A grain before a phrase belongs to it only when it names the interval's own unit. */
    private fun nounOfReading(word: String, reading: ChronoReading): Boolean {
        val r = (reading as? ChronoReading.Recognized)?.recognition ?: return false
        val months = java.time.temporal.ChronoUnit.MONTHS.between(r.startInclusive, r.endExclusive)
        val days = java.time.temporal.ChronoUnit.DAYS.between(r.startInclusive, r.endExclusive)
        return when {
            Regex("""years?|rok|roku|roce|rokem""").matches(word) -> months == 12L
            Regex("""months?|mesic\w*""").matches(word) -> months == 1L
            Regex("""quarters?|ctvrtlet\w*|kvartal\w*""").matches(word) -> months == 3L
            Regex("""half|pololet\w*""").matches(word) -> months == 6L
            Regex("""weeks?|tyden|tydn\w*""").matches(word) -> days == 7L
            else -> false
        }
    }

    private fun phraseWord(folded: String): Boolean = NOUN.matches(folded) || ADJECTIVE.matches(folded)

    /** Two readings of one kind: the same period by [sameInterval], or both no one period of the same kind. */
    private fun alike(
        a: SpanReading,
        b: SpanReading,
    ): Boolean =
        when {
            a is SpanReading.Read && b is SpanReading.Read -> sameInterval(a, b.reading)
            else -> a == b
        }

    /** One period by its interval, whatever the confidence: „v říjnu“ leaning on 2025 is October 2025 too. */
    private fun sameInterval(
        other: SpanReading,
        reading: ChronoReading,
    ): Boolean {
        val o = ((other as? SpanReading.Read)?.reading as? ChronoReading.Recognized)?.recognition ?: return false
        val r = (reading as? ChronoReading.Recognized)?.recognition ?: return false
        return keyOf(o.copy(confidence = r.confidence, alternatives = emptyList())) ==
            keyOf(r.copy(alternatives = emptyList()))
    }

    /**
     * A bare four-digit window that the words around it make an amount ("more than 2000 units", „nad 2000 Kč“), as
     * [NamedYear.bareYears] reads them: no year, so no span (§3.4 returns a bare year, not a number).
     */
    private fun amount(
        text: String,
        tokens: List<IntRange>,
        w: Window,
        reading: ChronoReading,
    ): Boolean {
        val rec = (reading as? ChronoReading.Recognized)?.recognition ?: return false
        if (w.size != 1 || rec.kind != ChronoKind.CALENDAR_YEAR) return false
        val around = Window(maxOf(0, w.first - 2), minOf(tokens.lastIndex, w.last + 1))
        return rec.startInclusive.year !in NamedYear.bareYears(Normalization.fold(textOf(text, tokens, around)))
    }

    /** A calendar year read from a four-digit run no word in [text] places as one ("for 2000"). */
    private fun unplacedNumber(
        r: SpanReading,
        text: String,
    ): Boolean {
        val rec = ((r as? SpanReading.Read)?.reading as? ChronoReading.Recognized)?.recognition ?: return false
        return rec.kind == ChronoKind.CALENDAR_YEAR &&
            rec.startInclusive.year !in NamedYear.writtenYears(Normalization.fold(text))
    }

    /**
     * Can [folded] be part of a time phrase? Every rule reads one of these, so a window with none reads as nothing
     * and need not be read: a digit, a month as [Months.find] reads one (a stem anywhere), a period, day or year word
     * in any case, a scope word, or a slice term. A superset on purpose — it only saves reads.
     */
    private fun anchor(
        folded: String,
        token: String,
        triggers: GroundingSlice,
    ): Boolean =
        folded.any { it.isDigit() } ||
            ANCHOR_STEMS.any { folded.contains(it) } ||
            WindowPeriods.timeWordRe.matches(folded) ||
            Months.find(folded) != null ||
            triggers.matches(token)

    /** The single vocabulary used for returned spans, phrase links and Several coverage. */
    private fun timeWord(
        words: List<String>,
        i: Int,
        text: String,
        tokens: List<IntRange>,
    ): Boolean {
        val w = words[i]
        if (w.all(Char::isDigit) || ORDINAL.matches(w.removeSuffix("."))) {
            if (SIX_DIGITS.matches(w)) return words.getOrNull(i - 1) in setOf("period", "obdobi") || tokens.size == 1
            if (Regex("""\d{4}""").matches(w)) {
                val around = Window(maxOf(0, i - 2), minOf(words.lastIndex, i + 1))
                return w.toInt() in NamedYear.bareYears(Normalization.fold(textOf(text, tokens, around)))
            }
            val previous = words.getOrNull(i - 1).orEmpty()
            val next = words.getOrNull(i + 1).orEmpty()
            val monthBeside = Months.ofWord(previous, withYear = true) != null || Months.ofWord(next, withYear = true) != null
            val ordinal = w.endsWith(".") &&
                (NOUN.matches(next) || (w.removeSuffix(".") in setOf("1", "2", "3", "4") &&
                    words.subList(maxOf(0, i - 3), minOf(words.size, i + 4)).any { NOUN.matches(it) }))
            val numericDate = Regex("""\d{1,2}\.\s*\d{1,2}\.\s*\d{4}""").containsMatchIn(
                words.subList(maxOf(0, i - 2), minOf(words.size, i + 3)).joinToString(" "),
            )
            val count = Regex("""last|past|posledn\w*|minul\w*""").matches(previous) && (NOUN.matches(next) || Regex("""days?|den|dn[iu]|dny""").matches(next))
            val shortYear = Regex("""q[1-4]|h[12]""").matches(previous) &&
                (next.isEmpty() || WindowPeriods.timeWordRe.matches(next))
            return monthBeside || ordinal || numericDate || count || shortYear
        }
        return WindowPeriods.timeWordRe.matches(w) || phraseWord(w) ||
            Months.ofWord(w, withYear = true) != null || MonthWords.find(w).isNotEmpty() || YEAR_WORD.matches(w) ||
            EXTRA_TIME.matches(w) || COMPACT_TIME.matches(w) ||
            (w.contains(Regex("""[-–/]""")) && w.split(Regex("""[-–/]""")).all { COMPACT_TIME.matches(it) })
    }

    private fun phraseWords(w: Window, words: List<String>, timeWords: List<Boolean>): Boolean =
        (w.first..w.last).all { i ->
            timeWords[i] || SMALL.matches(words[i]) ||
                (words[i] == "by" && Regex("""by (?:the )?end of .*""").matches(words.subList(i, w.last + 1).joinToString(" ")))
        }

    /** At or above the threshold, its confidence; an unanchored reading carries the threshold it passed by rule. */
    private fun confidenceOf(reading: ChronoReading): Double? =
        when (reading) {
            is ChronoReading.Recognized -> reading.recognition.confidence.takeIf { it >= threshold }
            is ChronoReading.Unanchored -> threshold
        }

    /** Two readings of one period: the same interval, kind, code, confidence and alternatives; not the target. */
    private fun sameReading(
        other: SpanReading,
        reading: ChronoReading,
    ): Boolean {
        val o = (other as? SpanReading.Read)?.reading ?: return false
        return when {
            o is ChronoReading.Unanchored && reading is ChronoReading.Unanchored -> true
            o is ChronoReading.Recognized && reading is ChronoReading.Recognized ->
                keyOf(o.recognition) == keyOf(reading.recognition)
            else -> false
        }
    }

    private fun keyOf(r: ChronoRecognition): List<Any?> =
        listOf(
            r.startInclusive,
            r.endExclusive,
            r.kind,
            r.confidence,
            r.periodCode,
            r.optionId,
            r.alternatives.map { listOf(it.startInclusive, it.endExclusive, it.periodCode, it.optionId) },
        )

    /** The kind of period read, for [Found.trigger] when no slice term fired: `period`, `relative`, … */
    private fun ruleOf(reading: ChronoReading): String =
        when (reading) {
            is ChronoReading.Recognized ->
                reading.recognition.kind.name
                    .lowercase()
            is ChronoReading.Unanchored -> "conversation"
        }

    /** A window that is only a six-digit run, in a text that is more than it (§3.4). */
    private fun bareCode(
        text: String,
        tokens: List<IntRange>,
        w: Window,
    ): Boolean = w.size == 1 && SIX_DIGITS.matches(textOf(text, tokens, w)) && textOf(text, tokens, w) != text.trim()

    private fun textOf(
        text: String,
        tokens: List<IntRange>,
        w: Window,
    ): String = text.substring(tokens[w.first].first, tokens[w.last].last + 1)

    companion object {
        const val CHRONO_KIND = "chrono"

        /**
         * The longest window read: "od začátku roku loni do konce září", "Q2 vs the same quarter last year", „od roku
         * 2020 ve 2. čtvrtletí“ — a phrase that names no one period must fit in one window to keep its parts out.
         */
        const val MAX_WINDOW = 7

        private val SIX_DIGITS = Regex("""\d{6}""")

        /** The stems the rules read a period, a day or a year by, folded; [anchor] asks them as substrings. */
        private val ANCHOR_STEMS =
            listOf(
                "today",
                "dnes",
                "yesterday",
                "vcer",
                "tomorrow",
                "zitr",
                "now",
                "ted",
                "week",
                "tyden",
                "tydn",
                "month",
                "mesic",
                "year",
                "rok",
                "roce",
                "roku",
                "ctvrtlet",
                "kvartal",
                "quarter",
                "pololet",
                "half",
                "ytd",
                "obdobi",
                "period",
                "letos",
                "tohorocn",
                "loni",
                "lonsk",
                "minulorocn",
                "predlon",
                "predminul",
                "fisk",
                "fisc",
                "financ",
                "ucetn",
            )

        /** A period noun, folded, in any case: what may close a phrase after it ("Q2 of the year", „loňského roku“). */
        private val NOUN =
            Regex(
                """years?|months?|weeks?|quarters?|half|rok|roku|roce|rokem|mesic\w*|tyden|tydn\w*""" +
                    """|ctvrtlet\w*|kvartal\w*|pololet\w*|obdobi|period""",
            )
        private val ADJECTIVE =
            Regex(
                """fiscal|financial|accounting|calendar|present""" +
                    """|(?:fiskaln|financn|ucetn|kalendarn|hospodarsk|bezn|stavajic|probihajic)\w*""",
            )
        private val COMPARISON_LINK = Regex("""\b(?:vs|versus|oproti|proti|compared|against|than|nez)\b""")
        private val LINK =
            Regex(
                """\b(?:vs|versus|oproti|proti|compared\s+(?:to|with)|against|than|nez|since|after|before|until|through|up to|by|excluding|except|without|od|po|pred|do|krome|bez|mimo|vyjma|onwards|ago|earlier|az|o)\b""",
            )
        private val JOIN_TO_TIME = Regex("""\b(?:and|or|a|i|nebo)(?: the)?$""")
        private val YEAR_NOUN = Regex("""years?|rok|roku|roce|rokem""")
        private val CALENDAR_NAME =
            Regex(
                """fiscal|financial|accounting|calendar|""" +
                    """(?:fiskaln|financn|ucetn|hospodarsk|kalendarn)\w*""",
            )
        private val SMALL = Regex("""of|the|in|for|during|to|v|ve|za|z|ze|od|do|na|that""")
        private val EXTRA_TIME = Regex("""zacatku|konce|beginning|start|end|date|dneska|dodnes|today|now|so|far|dosud|zatim|past|tento|tato|toto|tenhle|each|every|kazd\w*|uplynul\w*|posledn\w*""")
        private val COMPACT_TIME = Regex("""q[1-4]|h[12]|fy\d{2,4}|\d{4}-\d{1,2}(?:-\d{1,2})?|\d{1,2}[./]\d{1,2}(?:[./]\d{4})?\.?""")
        private val GLUE = Regex("""of|the|that|in|for""")

        /** What makes "year" a measure after it: "year over year", "year on year". */
        private val MEASURE_AFTER = setOf("over", "on", "to")

        /** A one-word year: „letos“, „loni“, „loňského“, „předloni“, „tohoroční“, "YTD". */
        private val YEAR_WORD =
            Regex(
                """letos\w*|v?loni|v?lonsk\w*|predlon\w*|tohorocn\w*|(?:pred)?minulorocn\w*|ytd""" +
                    // an ordinal: the elided period of „1. a 2. čtvrtletí“ is "first" in "first and second quarter"
                    """|first|second|third|fourth|1st|2nd|3rd|4th|prvn\w*|druh(?:y|e|a|eho|emu|em|ou|ych|ym|ymi)|tret\w*|ctvrt(?!let)\w*""",
            )

        /**
         * [text] with the words that are no month blanked to spaces, length kept: a word [Months.find] reads by a stem
         * inside it that is neither a month nor a month adjective (*zařízení*); an English abbreviation with no day or
         * year beside it ("Jan Novák"); and *may* / *march* as a verb — not after a preposition or a day, not before a
         * day or a year, and not capitalised mid-sentence.
         */
        internal fun blankNonMonths(text: String): String {
            val tokens = tokens(text)
            val months = MonthWords.find(text)
            val out = StringBuilder(text)
            for (t in tokens) {
                val w = Normalization.fold(text.substring(t.first, t.last + 1))
                if (Months.find(w) == null) continue
                if (months.none { it.range.first >= t.first && it.range.last <= t.last }) for (k in t) out.setCharAt(k, ' ')
            }
            return out.toString()
        }

        /**
         * The tokens of [text], as ranges of UTF-16 offsets: the runs between whitespace, less the punctuation at
         * either end ("měsíci?" → "měsíci", "(Q2)" → "Q2"). A one- or two-digit run keeps its dot: „15.“ and „2.“ are
         * a day and an ordinal. Trimmed by code point, so a surrogate pair is kept or dropped whole, and a combining
         * mark stays with its letter.
         */
        internal fun tokens(text: String): List<IntRange> {
            val out = mutableListOf<IntRange>()
            var i = 0
            while (i < text.length) {
                while (i < text.length && Character.isWhitespace(text.codePointAt(i))) {
                    i += Character.charCount(text.codePointAt(i))
                }
                if (i >= text.length) break
                var end = i
                while (end < text.length && !Character.isWhitespace(text.codePointAt(end))) {
                    end += Character.charCount(text.codePointAt(end))
                }
                var s = i
                var e = end
                while (s < e && !isWord(text.codePointAt(s))) s += Character.charCount(text.codePointAt(s))
                while (e > s && !isWord(text.codePointBefore(e))) e -= Character.charCount(text.codePointBefore(e))
                if (e > s) {
                    if (e < end && text[e] == '.' && ORDINAL.matches(text.substring(s, e))) e += 1
                    out += s until e
                }
                i = end
            }
            return out
        }

        private val ORDINAL = Regex("""\d{1,2}""")

        /** A letter, a digit or a combining mark: a decomposed „ý“ ends its word with its accent. */
        private fun isWord(cp: Int): Boolean =
            Character.isLetterOrDigit(cp) ||
                Character.getType(cp).toByte().let {
                    it == Character.NON_SPACING_MARK ||
                        it == Character.COMBINING_SPACING_MARK ||
                        it == Character.ENCLOSING_MARK
                }
    }
}
