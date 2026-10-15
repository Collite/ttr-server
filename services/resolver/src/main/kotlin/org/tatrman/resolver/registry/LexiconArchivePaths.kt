// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.registry

import com.typesafe.config.Config
import com.typesafe.config.ConfigValueType

/**
 * ttr-server#209 — which compiled lexicon archives the declared layer reads.
 *
 * Two keys, one answer:
 *  - `resolver.lexicon-archive-paths` (`RESOLVER_LEXICON_ARCHIVE_PATHS`) — the LIST. A HOCON list,
 *    or a comma-separated string, which is how a list arrives from the environment.
 *  - `resolver.lexicon-archive-path` (`RESOLVER_LEXICON_ARCHIVE_PATH`) — the pre-#209 key, read as a
 *    one-element list. Every existing deployment sets only this one and keeps working unchanged.
 *
 * When the list is set it is the whole answer, and the singular key is not added to it. The chart
 * renders BOTH on a multi-archive deployment — the singular one naming the first archive — so that
 * an older image, which knows only the singular key, still boots on the first archive during a
 * rolling deploy; a new image must then not read that archive twice. A singular path the list does
 * not contain is reported by [read]'s `warn`, because then the two keys disagree about the estate.
 */
object LexiconArchivePaths {
    const val LIST_KEY: String = "resolver.lexicon-archive-paths"
    const val SINGLE_KEY: String = "resolver.lexicon-archive-path"

    fun read(
        config: Config,
        warn: (String) -> Unit = {},
    ): List<String> {
        val list = strings(config, LIST_KEY).distinct()
        val single = if (config.hasPath(SINGLE_KEY)) config.getString(SINGLE_KEY).trim().ifEmpty { null } else null
        if (list.isEmpty()) return listOfNotNull(single)
        if (single != null && single !in list) {
            warn(
                "$SINGLE_KEY ('$single') is not in $LIST_KEY ($list) — the list wins and '$single' is NOT read. " +
                    "Name every archive in the list (ttr-server#209).",
            )
        }
        return list
    }

    /** A list, or a comma-separated string; blanks dropped, so an unset `${?VAR}` reads as empty. */
    private fun strings(
        config: Config,
        key: String,
    ): List<String> {
        if (!config.hasPath(key)) return emptyList()
        val raw =
            when (config.getValue(key).valueType()) {
                ConfigValueType.LIST -> config.getStringList(key)
                else -> config.getString(key).split(',')
            }
        return raw.map { it.trim() }.filter { it.isNotEmpty() }
    }
}
