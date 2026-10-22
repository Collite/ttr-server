// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

/** Lexical replacement of recipe :name tokens. Quoted text, dollar strings and comments are opaque. */
object SqlParameters {
    fun rewrite(
        sql: String,
        parameter: (String) -> String,
    ): String =
        buildString {
            var i = 0
            while (i < sql.length) {
                val start = i
                val c = sql[i]
                when {
                    sql.startsWith("--", i) -> {
                        i = sql.indexOf('\n', i).takeIf { it >= 0 } ?: sql.length
                        append(sql, start, i)
                    }
                    sql.startsWith("/*", i) -> {
                        i += 2
                        var depth = 1
                        while (i < sql.length && depth > 0) {
                            when {
                                sql.startsWith("/*", i) -> {
                                    depth++
                                    i += 2
                                }
                                sql.startsWith("*/", i) -> {
                                    depth--
                                    i += 2
                                }
                                else -> i++
                            }
                        }
                        append(sql, start, i)
                    }
                    c == '\'' || c == '"' -> {
                        i++
                        while (i < sql.length) {
                            if (sql[i++] == c) {
                                if (i < sql.length && sql[i] == c) i++ else break
                            }
                        }
                        append(sql, start, i)
                    }
                    c == '$' && DOLLAR.find(sql, i)?.range?.first == i -> {
                        val delimiter = DOLLAR.find(sql, i)!!.value
                        i =
                            sql.indexOf(delimiter, i + delimiter.length).takeIf { it >= 0 }?.plus(delimiter.length)
                                ?: sql.length
                        append(sql, start, i)
                    }
                    c == ':' &&
                        sql.getOrNull(i - 1) != ':' &&
                        sql.getOrNull(i + 1)?.let { it.isLetter() || it == '_' } == true -> {
                        i += 2
                        while (i < sql.length && (sql[i].isLetterOrDigit() || sql[i] == '_')) i++
                        append(parameter(sql.substring(start + 1, i)))
                    }
                    else -> {
                        append(c)
                        i++
                    }
                }
            }
        }

    private val DOLLAR = Regex("\\$[A-Za-z_0-9]*\\$")
}
