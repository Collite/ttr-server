// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translate.grpc

/**
 * BI (contracts C-11) — a refusal as a person reads it: one line, no Java exception class, said once, with where in
 * the statement it points as fields rather than prose. Calcite's validator wraps its message as
 *
 * `org.apache.calcite.runtime.CalciteContextException: From line 1, column 15 to line 1, column 26: Object 'warehouse'
 * not found within 'er': From line 1, column 15 to line 1, column 26: Object 'warehouse' not found within 'er': Object
 * 'warehouse' not found within 'er'`
 *
 * and its parser lists every token it would have accepted (`Encountered "= {" at line 1, column 79. Was expecting one
 * of: …`, dozens of lines). [of] keeps the sentence and the first position; a message with neither shape comes back
 * as it was, on one line.
 */
internal object RefusalText {
    data class Clean(
        val text: String,
        val line: Int = 0,
        val column: Int = 0,
    )

    private val EXCEPTION_PREFIX = Regex("""^(?:[a-z_$][\w$]*\.)+[A-Z][\w$]*(?:Exception|Error):\s*""")
    private val RANGE = Regex("""From line (\d+), column (\d+) to line \d+, column \d+:\s*""")
    private val AT = Regex("""\s*at line (\d+), column (\d+)""")
    private val EXPECTING = Regex("""\s*Was expecting(?: one of)?:.*""", RegexOption.DOT_MATCHES_ALL)
    private val ENCOUNTERED = Regex("""^Encountered (".*?")\s*\.?$""")
    private val WHITESPACE = Regex("""\s+""")

    fun of(raw: String): Clean {
        var text = raw.trim()
        while (true) {
            val stripped = EXCEPTION_PREFIX.replaceFirst(text, "")
            if (stripped == text) break
            text = stripped
        }
        var line = 0
        var column = 0
        val range = RANGE.find(text)
        if (range != null) {
            line = range.groupValues[1].toInt()
            column = range.groupValues[2].toInt()
            text = RANGE.replace(text, "")
        } else {
            text = EXPECTING.replace(text, "")
            AT.find(text)?.let { at ->
                line = at.groupValues[1].toInt()
                column = at.groupValues[2].toInt()
                text = text.removeRange(at.range)
            }
        }
        text = WHITESPACE.replace(text, " ").trim()
        text = withoutRepeats(text)
        ENCOUNTERED.matchEntire(text)?.let { text = "Syntax error near ${it.groupValues[1]}." }
        return Clean(text.ifEmpty { WHITESPACE.replace(raw, " ").trim() }, line, column)
    }

    /** `a: a: a — b` → `a — b`: Calcite says the innermost message once per wrapping layer. */
    private fun withoutRepeats(text: String): String {
        var out = text
        while (true) {
            val separator = out.indexOf(": ")
            if (separator <= 0) return out
            val head = out.substring(0, separator)
            val rest = out.substring(separator + 2)
            if (!rest.startsWith(head)) return out
            out = rest
        }
    }
}
