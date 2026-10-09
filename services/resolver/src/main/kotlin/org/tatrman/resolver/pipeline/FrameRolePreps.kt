// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import java.io.File

/**
 * The per-language preposition tables the frame-role rules dispatch on (RV-P2.1.T5).
 *
 * They are configuration, not source, because the Q-15 report says so: "`FILTER_PREPS` /
 * `GROUPING_PREPS` are per-language tables and belong in configuration next to the NLP
 * routing table (RV-40), not in Kotlin source — the next language is a data change".
 *
 * Shipped defaults live in `frame-roles.conf`; an estate replaces them wholesale with
 * `resolver.frame-roles.config-path`, declared in `application.conf` and bound to
 * `RESOLVER_FRAME_ROLES_PATH` there — a reader whose key no `application.conf` declares is a
 * reader no estate can reach, which is what this override was until the p2-1 review. An
 * unreadable or
 * malformed override falls back to the shipped tables with a warning rather than failing
 * the boot — the same posture the lexicon readers take (P1.4/P1.6): losing a language's
 * prepositions degrades role derivation, it does not make the core wrong.
 */
class FrameRolePreps(
    private val groupingPreps: Map<String, Set<String>>,
    private val filterPreps: Map<String, Set<String>>,
    private val defaultLang: String,
    /**
     * MH — count quantifiers (`kolik`, `how many`, `number of`). Last and defaulted, so a
     * config file written before MH keeps loading and simply never fires the count slot.
     */
    private val countHeads: Map<String, Set<String>> = emptyMap(),
    /**
     * cs LR S5 — prepositions that are GROUPING in their distributive reading and FILTER
     * otherwise (cs `po`). Last and defaulted, like [countHeads]: a config without the key never
     * fires the distributive rule.
     */
    private val distributivePreps: Map<String, Set<String>> = emptyMap(),
) {
    fun grouping(lang: String): Set<String> = table(groupingPreps, lang)

    fun filter(lang: String): Set<String> = table(filterPreps, lang)

    /** MH — the lemmas that mark a counted noun's head; empty for a pre-MH config. */
    fun countHeads(lang: String): Set<String> = table(countHeads, lang)

    /** cs LR S5 — the prepositions whose reading the distributive rule decides; see `frame-roles.conf`. */
    fun distributive(lang: String): Set<String> = table(distributivePreps, lang)

    private fun table(
        tables: Map<String, Set<String>>,
        lang: String,
    ): Set<String> = tables[key(lang)] ?: tables[defaultLang].orEmpty()

    /** `cs-CZ` and `CS` both read the `cs` table. */
    private fun key(lang: String): String = lang.substringBefore('-').lowercase()

    companion object {
        private val log = LoggerFactory.getLogger(FrameRolePreps::class.java)
        private const val OVERRIDE_PATH = "resolver.frame-roles.config-path"

        /** The tables packaged with the service. */
        fun shipped(): FrameRolePreps = from(ConfigFactory.parseResources("frame-roles.conf").resolve())

        /**
         * The tables this deployment runs on: the estate's override when one is configured
         * and readable, the shipped tables otherwise.
         */
        fun load(config: Config): FrameRolePreps {
            val path = if (config.hasPath(OVERRIDE_PATH)) config.getString(OVERRIDE_PATH) else ""
            if (path.isBlank()) return shipped()
            val file = File(path)
            if (!file.isFile) {
                log.warn("frame-role preposition override {} is not a readable file — using the shipped tables", path)
                return shipped()
            }
            return runCatching { from(ConfigFactory.parseFile(file).resolve()) }
                .getOrElse {
                    log.warn("frame-role preposition override {} could not be parsed: {}", path, it.message)
                    shipped()
                }
        }

        private fun from(config: Config): FrameRolePreps =
            FrameRolePreps(
                groupingPreps = tables(config, "frame-roles.grouping-preps"),
                filterPreps = tables(config, "frame-roles.filter-preps"),
                // Lowercased like the table keys themselves: an override that writes
                // `default-lang = "EN"` must not silently resolve to no table at all.
                defaultLang =
                    if (config.hasPath("frame-roles.default-lang")) {
                        config.getString("frame-roles.default-lang").lowercase()
                    } else {
                        "en"
                    },
                // MH: `tables` already returns an empty map for an absent path, so a config
                // that predates the key loads unchanged.
                countHeads = tables(config, "frame-roles.count-heads"),
                distributivePreps = tables(config, "frame-roles.distributive-preps"),
            )

        private fun tables(
            config: Config,
            path: String,
        ): Map<String, Set<String>> {
            if (!config.hasPath(path)) return emptyMap()
            val root = config.getConfig(path)
            return root
                .root()
                .keys
                .associate { lang ->
                    lang.lowercase() to root.getStringList(lang).map { it.lowercase() }.toSet()
                }
        }
    }
}
