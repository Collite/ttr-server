// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

import org.tatrman.text.Normalization

/** One contextual month vocabulary for recognition, conversation anchors and Scan's blanking. */
internal object MonthWords {
    data class Mention(
        val month: Int,
        val range: IntRange,
    )

    private val word = Regex("""[\p{L}\p{M}]+|\d+(?:st|nd|rd|th)?""")
    private val adjective =
        Regex("""(?:ledn|unor|brezn|dubn|kvetn|cervn|cervenc|srpn|zarij|rijn|listopad|prosinc)ov\w*""")
    private val prepositions =
        setOf(
            "in",
            "for",
            "of",
            "during",
            "since",
            "until",
            "till",
            "from",
            "through",
            "thru",
            "by",
            "to",
            "before",
            "after",
            "on",
            "v",
            "ve",
            "za",
            "od",
            "do",
        )
    private val pronouns = setOf("i", "we", "you", "they", "he", "she", "it", "one")
    private val joinWord = Regex("""and|or|to|through|thru|till|until|vs|versus|a|i|nebo|az""")
    private val joinNoise = Regex("""[\s\p{P}&+]""")
    private val day = Regex("""\d{1,2}(?:st|nd|rd|th)?""")
    private val dayOrYear = Regex("""\d{1,2}(?:st|nd|rd|th)?|\d{4}""")

    /** Month pairs may be separated only by punctuation/space, or by one joining word. */
    private fun joinedBy(between: String): Boolean {
        val joining = joinNoise.replace(between, "")
        return joining.isEmpty() || joinWord.matches(joining)
    }

    fun find(text: String): List<Mention> {
        val tokens = word.findAll(text).toList()
        val folded = tokens.map { Normalization.fold(it.value) }
        val forms =
            folded.map {
                Months.ofWord(it, withYear = true)
                    ?: if (adjective.matches(it)) Months.find(it) else null
            }
        val joined = mutableSetOf<Int>()
        forms.indices.filter { forms[it] != null }.zipWithNext().forEach { (left, right) ->
            val between = Normalization.fold(text.substring(tokens[left].range.last + 1, tokens[right].range.first))
            if (joinedBy(between)) {
                joined += left
                joined += right
            }
        }
        return tokens.indices.mapNotNull { i ->
            val month = forms[i] ?: return@mapNotNull null
            val w = folded[i]
            val previous = folded.getOrNull(i - 1)
            val next = folded.getOrNull(i + 1)
            val beside = previous?.let(day::matches) == true || next?.let(dayOrYear::matches) == true
            val paired = i in joined
            val afterPreposition = previous in prepositions
            val sentenceStart =
                i == 0 || text.substring(0, tokens[i].range.first).trimEnd().lastOrNull() in setOf('.', '?', '!', ':')
            val isMonth =
                when {
                    w == "may" && next in pronouns -> false
                    w == "may" ->
                        sentenceStart ||
                            (tokens[i].value.first().isUpperCase() && !sentenceStart) ||
                            afterPreposition ||
                            beside ||
                            paired
                    Months.ofWord(w, withYear = false) == null && !adjective.matches(w) ->
                        afterPreposition ||
                            beside ||
                            paired
                    else -> true
                }
            if (isMonth) Mention(month, tokens[i].range) else null
        }
    }
}
