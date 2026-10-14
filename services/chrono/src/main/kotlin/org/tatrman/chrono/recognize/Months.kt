// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

import org.tatrman.text.Normalization

/**
 * Month-name recognition for cs + en. Czech is heavily inflected — a date says "15. května"
 * (genitive) and an adjective period says "květnové období" — so we match by the longest
 * diacritic-insensitive **stem** that uniquely identifies a month rather than an exact word.
 *
 * Order matters for the červen/červenec (June/July) pair: "červenec" must be tried before
 * "červen" because the latter is a prefix of the former. [ordered] encodes that.
 */
object Months {
    /** Stems (diacritic-stripped, lower-case) → month number, longest/most-specific first. */
    private val ordered: List<Pair<String, Int>> =
        listOf(
            // English full + 3-letter (jun/jul before generic handled by exact set below)
            "january" to 1,
            "february" to 2,
            "march" to 3,
            "april" to 4,
            "june" to 6,
            "july" to 7,
            "august" to 8,
            "september" to 9,
            "october" to 10,
            "november" to 11,
            "december" to 12,
            // "may" is a substring risk (e.g. "maybe") — handled as a whole-token match below, not a stem.
            // Czech — červenec (July) BEFORE červen (June).
            "cervenec" to 7,
            "cervenc" to 7,
            "cerven" to 6,
            "cervn" to 6,
            "leden" to 1,
            "ledna" to 1,
            "lednu" to 1,
            "unor" to 2,
            "unora" to 2,
            "brezen" to 3,
            "brezna" to 3,
            "breznu" to 3,
            "breznov" to 3,
            "duben" to 4,
            "dubna" to 4,
            "dubnu" to 4,
            "dubnov" to 4,
            "kveten" to 5,
            "kvetna" to 5,
            "kvetnu" to 5,
            "kvetnov" to 5,
            "srpen" to 8,
            "srpna" to 8,
            "srpnu" to 8,
            "srpnov" to 8,
            "zari" to 9,
            "rijen" to 10,
            "rijna" to 10,
            "rijnu" to 10,
            "rijnov" to 10,
            "listopad" to 11,
            "listopadu" to 11,
            "prosinec" to 12,
            "prosince" to 12,
            "prosinci" to 12,
            // English adjective/short forms + Czech leden/unor adjective stems
            "lednov" to 1,
            "unorov" to 2,
            // Instrumental case ("v srpnem", "lednem", …). Full forms, not bare "-n" stems, so they
            // can't collide mid-word (e.g. a bare "ledn" would false-match "poslední" = "last").
            "srpnem" to 8,
            "lednem" to 1,
            "breznem" to 3,
            "dubnem" to 4,
            "kvetnem" to 5,
            "rijnem" to 10,
        )

    /** English 3-letter abbreviations (exact token match). */
    private val enAbbrev: Map<String, Int> =
        mapOf(
            "jan" to 1,
            "feb" to 2,
            "mar" to 3,
            "apr" to 4,
            "may" to 5,
            "jun" to 6,
            "jul" to 7,
            "aug" to 8,
            "sep" to 9,
            "sept" to 9,
            "oct" to 10,
            "nov" to 11,
            "dec" to 12,
        )

    /**
     * Every form of a month a question writes as a word of its own, folded: the English names and the Czech
     * noun in each case. Exact words, not stems — [find] looks for a stem anywhere, which is right for a
     * span that is a date but would read *zařízení* (a device) as September and *červená* (red) as June in a
     * whole question.
     */
    private val wordForms: Map<String, Int> =
        buildMap {
            listOf(
                "january",
                "february",
                "march",
                "april",
                "may",
                "june",
                "july",
                "august",
                "september",
                "october",
                "november",
                "december",
            ).forEachIndexed { i, name -> put(name, i + 1) }
            listOf(
                "leden ledna lednu lednem",
                "unor unora unoru unorem",
                "brezen brezna breznu breznem",
                "duben dubna dubnu dubnem",
                "kveten kvetna kvetnu kvetnem",
                "cerven cervna cervnu cervnem",
                "cervenec cervence cervenci cervencem",
                "srpen srpna srpnu srpnem",
                "zari",
                "rijen rijna rijnu rijnem",
                "listopad listopadu listopadem",
                "prosinec prosince prosinci prosincem",
            ).forEachIndexed { i, forms -> forms.split(' ').forEach { put(it, i + 1) } }
        }

    /**
     * The month a single folded [word] is, read strictly ([wordForms]). The English abbreviations ("jan",
     * "mar", "dec") count only [withYear] — after them a year makes them a month; alone, "Jan" is a name.
     */
    fun ofWord(
        word: String,
        withYear: Boolean,
    ): Int? = wordForms[word] ?: if (withYear) enAbbrev[word] else null

    /**
     * Recognize the month named anywhere in [text] (a span or token). Returns the month number
     * 1..12, or null. Diacritics are stripped first; "may"/"máj"/"květen" all resolve to 5.
     */
    fun find(text: String): Int? {
        val norm = Normalization.fold(text)
        // whole-token exact matches first (handles the short/ambiguous "may", "maj", abbrevs)
        val tokens = norm.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        for (t in tokens) {
            if (t == "may" || t == "maj") return 5
            enAbbrev[t]?.let { return it }
        }
        // then stem containment (inflected Czech + English full names)
        for ((stem, month) in ordered) {
            if (norm.contains(stem)) return month
        }
        return null
    }
}
