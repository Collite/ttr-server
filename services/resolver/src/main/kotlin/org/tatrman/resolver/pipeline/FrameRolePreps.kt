// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver.pipeline

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import org.tatrman.text.Normalization.fold
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
    /**
     * ttr-server#206 — the prepositions that put a PERIOD on a year (`za 2025`, `for 2025`), read by
     * `MistypedYears` to offer a year the NER typed a number to the date kernel. Last and defaulted,
     * like the two above: a config without the key never offers one.
     */
    private val temporalPreps: Map<String, Set<String>> = emptyMap(),
    /**
     * ttr-server#204 — the words a mention may lose silently when it is asked again as its head
     * ([isSilent]). Last and defaulted; see `frame-roles.conf` for why an override without the key
     * keeps the shipped table rather than an empty one.
     */
    private val silentModifiers: Map<String, Set<String>> = emptyMap(),
    /**
     * ttr-server#152 — the calendar adjectives R6" reads as a grain (`monthly`). Last and defaulted,
     * like [countHeads]: a config without the key leaves R6" the NER's signal alone.
     */
    private val grainAdjectives: Map<String, Set<String>> = emptyMap(),
) {
    fun grouping(lang: String): Set<String> = table(groupingPreps, lang)

    /**
     * ttr-server#204 — whether a word left out of a mention's retry restricts nothing in [lang]
     * (`the`, `total`, `celkové`): matched against its [surface] or its [lemma], case and diacritics
     * folded, a trailing `*` matching a prefix.
     */
    fun isSilent(
        lang: String,
        surface: String,
        lemma: String,
    ): Boolean = listed(silentModifiers, lang, surface, lemma)

    /**
     * MH / ttr-server#237 — whether a word is a count quantifier in [lang] (`many`, `kolik`): its surface
     * or lemma on the `count-heads` table, matched as [isSilent] matches. `SlotHints` reads the word as
     * the counted noun's COUNT_HEAD, so a mention never surfaces it as a word of its own.
     */
    fun isCountHead(
        lang: String,
        surface: String,
        lemma: String,
    ): Boolean = listed(countHeads, lang, surface, lemma)

    /**
     * ttr-server#204 / #237 — whether a word a mention stands without restricts nothing: a silent
     * modifier ([isSilent]) or a count quantifier ([isCountHead]), which the slot hint has already read.
     * THE test both `MentionRetry` (a phrase asked again as its head) and `MentionLayer` (the words a
     * declared phrase never took) apply to the dependents [SpanProposal.detachable] names.
     */
    fun restrictsNothing(
        lang: String,
        surface: String,
        lemma: String,
    ): Boolean = isSilent(lang, surface, lemma) || isCountHead(lang, surface, lemma)

    /**
     * ttr-server#152 / #237 — whether a word is a calendar adjective in [lang] (`monthly`, `měsíčních`):
     * its surface or lemma on the `grain-adjectives` table, matched as [isSilent] matches, so a trailing
     * `*` reaches every case form — a parse that left the lemma blank or unrestored (`mesicnich`) still
     * reads as the grain its surface is.
     */
    fun isGrainAdjective(
        lang: String,
        surface: String,
        lemma: String,
    ): Boolean = listed(grainAdjectives, lang, surface, lemma)

    /**
     * Whether [surface] or [lemma] is on [tables]' list for [lang]: case and diacritics folded, a trailing
     * `*` matching a prefix. One matcher for every word list in `frame-roles.conf`.
     */
    private fun listed(
        tables: Map<String, Set<String>>,
        lang: String,
        surface: String,
        lemma: String,
    ): Boolean {
        val forms = listOf(fold(surface), fold(lemma)).filter { it.isNotBlank() }
        return table(tables, lang).any { entry ->
            val word = fold(entry)
            if (word.endsWith("*")) {
                val prefix = word.dropLast(1)
                forms.any { it.startsWith(prefix) }
            } else {
                word in forms
            }
        }
    }

    fun filter(lang: String): Set<String> = table(filterPreps, lang)

    /** MH — the lemmas that mark a counted noun's head; empty for a pre-MH config. */
    fun countHeads(lang: String): Set<String> = table(countHeads, lang)

    /** cs LR S5 — the prepositions whose reading the distributive rule decides; see `frame-roles.conf`. */
    fun distributive(lang: String): Set<String> = table(distributivePreps, lang)

    /** ttr-server#206 — the prepositions after which a four-digit number is a year; see `frame-roles.conf`. */
    fun temporal(lang: String): Set<String> = table(temporalPreps, lang)

    private fun table(
        tables: Map<String, Set<String>>,
        lang: String,
    ): Set<String> = tables[key(lang)] ?: tables[defaultLang].orEmpty()

    /** `cs-CZ` and `CS` both read the `cs` table. */
    private fun key(lang: String): String = lang.substringBefore('-').lowercase()

    companion object {
        private val log = LoggerFactory.getLogger(FrameRolePreps::class.java)
        private const val OVERRIDE_PATH = "resolver.frame-roles.config-path"
        private const val SILENT_MODIFIERS = "frame-roles.silent-modifiers"

        private fun shippedConfig(): Config = ConfigFactory.parseResources("frame-roles.conf").resolve()

        /** The tables packaged with the service. */
        fun shipped(): FrameRolePreps = from(shippedConfig())

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
            // ttr-server#204 — an override written before `silent-modifiers` existed keeps the shipped
            // table: an EMPTY one would surface every article as an unbound word of its own.
            return runCatching { from(ConfigFactory.parseFile(file).resolve(), silentFallback = shippedConfig()) }
                .getOrElse {
                    log.warn("frame-role preposition override {} could not be parsed: {}", path, it.message)
                    shipped()
                }
        }

        private fun from(
            config: Config,
            silentFallback: Config? = null,
        ): FrameRolePreps =
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
                temporalPreps = tables(config, "frame-roles.temporal-preps"),
                silentModifiers =
                    if (config.hasPath(SILENT_MODIFIERS) || silentFallback == null) {
                        tables(config, SILENT_MODIFIERS)
                    } else {
                        tables(silentFallback, SILENT_MODIFIERS)
                    },
                grainAdjectives = tables(config, "frame-roles.grain-adjectives"),
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
