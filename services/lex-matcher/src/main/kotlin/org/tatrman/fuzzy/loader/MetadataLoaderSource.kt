// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import org.slf4j.LoggerFactory
import org.tatrman.diagnostics.RgDiagnostics
import org.tatrman.fuzzy.config.DatabaseConfig
import org.tatrman.fuzzy.config.MssqlConfig
import org.tatrman.fuzzy.config.PostgresConfig
import org.tatrman.fuzzy.core.Candidate
import org.tatrman.fuzzy.core.LoaderWarningInfo
import org.tatrman.fuzzy.core.MatchMethod
import org.tatrman.fuzzy.telemetry.FuzzyTelemetry
import org.tatrman.meta.v1.MemberVocabulary
import org.tatrman.plan.v1.QualifiedName

/**
 * Metadata-driven member loader (MV-T2, member-vocabulary contracts §4).
 *
 * Asks Veles for the estate's **member vocabularies** — one per indexed attribute, each with the
 * read plan the translator rendered from its entity for this warehouse's dialect — runs every plan
 * via [fetchCandidates], and returns the candidate map for `StringRepository.refreshCache` to swap
 * in atomically. The category is the vocabulary's own (`er.<ns>.<entity>.<attribute>`, or
 * `db.<ns>.<table>.<column>` on a db-only estate), taken verbatim; every candidate carries the
 * vocabulary's match method, so the dispatcher holds an `EXACT` code to exact equality.
 *
 * This loader knows nothing about tables, keys or SQL any more. Which vocabularies exist, whose rows
 * they read and how to spell that in SQL are Veles' and the translator's decisions — the same ones a
 * query over the entity is subject to. (It used to walk fuzzy-tagged COLUMNS and compose
 * `SELECT pk, col FROM table`, so two entities over one table shared one index, and a view- or
 * query-backed entity was indexed over its whole base table.)
 *
 * The gRPC channel and the [MetadataServiceClient] are constructed and owned by
 * `Application.module(...)` — this class never builds or closes them.
 *
 * The `sourceNamespace` guard enforces the v1 "single source, asserted" rule on the ATTRIBUTE's
 * namespace: if non-empty, a vocabulary whose attribute lives elsewhere is skipped with reason
 * `wrong_source`. Empty disables the guard.
 */
class MetadataLoaderSource(
    private val client: MetadataServiceClient,
    private val dialect: DatabaseConfig,
    private val sourceNamespace: String,
    private val fetchCandidates: (String) -> List<Candidate>,
    private val telemetry: FuzzyTelemetry? = null,
    // RS-12-γ alias tables (`semantics{kind: alias_table}`). PENDING COUPLING
    // (rule 6): Veles does not yet report alias-table declarations (no
    // `semantics` in `org.tatrman.meta.v1`); this provider is stubbed to empty
    // until the RG-P4 metadata work lands. The ingestion + merge logic is live
    // and tested (composeAliasCandidates); only the source of `decls` is stubbed.
    private val aliasTables: () -> List<AliasTableDecl> = { emptyList() },
) : LoaderSource {
    private val logger = LoggerFactory.getLogger(MetadataLoaderSource::class.java)

    // Loader report: why a declared vocabulary is not loaded (Veles' RG-FUZ-001/003 per item), or
    // why nothing was (RG-FUZ-004). Retrievable via GetStatus.
    @Volatile
    private var lastWarnings: List<LoaderWarningInfo> = emptyList()

    // T3 — the read-plan identity of every category the last successful load produced.
    @Volatile
    private var lastPlanVersions: Map<String, String> = emptyMap()

    override fun warnings(): List<LoaderWarningInfo> = lastWarnings

    override fun planVersions(): Map<String, String> = lastPlanVersions

    override suspend fun loadNextCache(): Map<String, List<Candidate>>? {
        val start = System.nanoTime()
        val listing =
            try {
                client.listMemberVocabularies(dialect.translatorDialect())
            } catch (e: Exception) {
                telemetry?.recordMetadataFailure()
                logger.error("Metadata (veles) call failed; preserving previous cache", e)
                return unavailable("the ListMemberVocabularies call failed (${e.message})")
            }
        val items =
            when (listing) {
                is MemberVocabularyListing.Listed -> listing.items
                is MemberVocabularyListing.Unavailable -> {
                    telemetry?.recordMetadataFailure()
                    logger.warn(
                        "Veles lists no member vocabularies ({}: {}); preserving previous cache",
                        listing.reason,
                        listing.detail,
                    )
                    return unavailable("${listing.reason}: ${listing.detail}")
                }
            }

        val warnings = mutableListOf<LoaderWarningInfo>()
        val result = mutableMapOf<String, List<Candidate>>()
        val planVersions = mutableMapOf<String, String>()
        val methods = mutableMapOf<String, String>()

        for (item in items) {
            val category = item.category
            if (!sourceMatchesConfig(item.attribute)) {
                telemetry?.recordSkipped("wrong_source")
                logger.error(
                    "Member vocabulary {} (namespace='{}') is outside configured sourceNamespace='{}' — skipped",
                    category,
                    item.attribute.namespace,
                    sourceNamespace,
                )
                continue
            }

            // Listed but not loadable: no single key (RG-FUZ-001) or no read plan (RG-FUZ-003).
            // Veles says which and why; the loader passes it on and never reads the rows another way.
            if (item.readSql.isEmpty()) {
                val diagnostics = item.diagnosticsList.map { LoaderWarningInfo(it.code, category, it.humanMessage) }
                warnings +=
                    diagnostics.ifEmpty {
                        listOf(LoaderWarningInfo(NO_READ_PLAN, category, "Veles listed it with no read plan"))
                    }
                telemetry?.recordSkipped(if (diagnostics.any { it.code == NO_KEY }) "no_key" else "no_read_plan")
                logger.debug("Skipping member vocabulary {} — {}", category, diagnostics.map { it.code })
                continue
            }

            val method = methodOf(item)
            try {
                val rows = fetchCandidates(item.readSql)
                result[category] = rows.map { it.copy(matchMethod = method) }
                planVersions[category] = item.version
                methods[category.lowercase()] = method
                logger.info("Loaded {} candidates for '{}' ({})", rows.size, category, method)
            } catch (e: Exception) {
                telemetry?.recordSkipped("sql_failed")
                logger.error("Read plan failed for '{}': {}", category, item.readSql, e)
            }
        }
        logSharedReadPlans(items)

        // RS-12-γ: merge estate alias-table synonyms into their owning member category (same key
        // space), matched under the owner's method — a synonym is the same member spelled another
        // way. `composeAliasCandidates` reuses the SQL identifier-validation discipline; a rejected
        // declaration contributes nothing rather than aborting the load.
        val aliasByCategory =
            composeAliasCandidates(aliasTables(), dialect) { sql ->
                try {
                    fetchCandidates(sql)
                } catch (e: Exception) {
                    telemetry?.recordSkipped("alias_sql_failed")
                    logger.error("Alias-table SQL failed: $sql", e)
                    emptyList()
                }
            }
        aliasByCategory.forEach { (category, aliases) ->
            val stamped = aliases.map { it.copy(matchMethod = methods[category] ?: MatchMethodNames.EXACT) }
            result.merge(category, stamped) { primary, alias -> primary + alias }
        }

        lastWarnings = warnings
        lastPlanVersions = planVersions

        val durationSeconds = (System.nanoTime() - start) / 1_000_000_000.0
        telemetry?.recordRefreshDuration(durationSeconds)
        telemetry?.updateCategories(result.mapValues { it.value.size })

        return result
    }

    /**
     * No listing to load: keep serving the previous cache, and say so in GetStatus (RG-FUZ-004) —
     * beside the previous load's own warnings, which still describe the cache being served.
     */
    private fun unavailable(reason: String): Map<String, List<Candidate>>? {
        lastWarnings =
            lastWarnings.filterNot { it.code == UNAVAILABLE } +
            LoaderWarningInfo(UNAVAILABLE, "", RgDiagnostics.render(UNAVAILABLE, "reason" to reason))
        return null
    }

    /**
     * The vocabulary's match method, as the dispatcher will parse it. Veles always sends one (an
     * indexed attribute with no authored method is `EXACT`); a method this matcher does not know is
     * matched EXACT with a WARN (contracts §8, `ttr/unknown-match-method`), never left unauthored —
     * unauthored rows pass the dispatcher unrestricted, which is partial matching nobody declared.
     */
    private fun methodOf(item: MemberVocabulary): String {
        if (MatchMethod.parse(item.matchMethod) != null) return item.matchMethod
        logger.warn(
            "Member vocabulary '{}' declares match method '{}', which this matcher does not know — matched EXACT",
            item.category,
            item.matchMethod,
        )
        return MatchMethodNames.EXACT
    }

    /** Contracts §4 / ⚑MV-4: two vocabularies reading the same rows the same way are logged, not merged. */
    private fun logSharedReadPlans(items: List<MemberVocabulary>) {
        items
            .filter { it.readSql.isNotEmpty() }
            .groupBy { Triple(it.readSql, it.keyAttribute, it.matchMethod) }
            .values
            .filter { it.size > 1 }
            .forEach { shared ->
                logger.info("member vocabularies share one read plan: {}", shared.joinToString { it.category })
            }
    }

    /**
     * `sourceNamespace = ""` disables the check (single-source v1 default).
     * Non-empty asserts that every vocabulary's attribute lives in that namespace.
     */
    private fun sourceMatchesConfig(attribute: QualifiedName): Boolean =
        sourceNamespace.isEmpty() || attribute.namespace == sourceNamespace

    private companion object {
        const val NO_KEY = "RG-FUZ-001"
        const val NO_READ_PLAN = "RG-FUZ-003"
        const val UNAVAILABLE = "RG-FUZ-004"
    }
}

/** The authored-method spellings the loader writes itself. */
internal object MatchMethodNames {
    const val EXACT = "EXACT"
}

/**
 * The translator's name for this warehouse's dialect — what Veles renders a read plan for. Always
 * sent: `""` would mean the translator's default, MSSQL, whatever the warehouse is (A-MV-7).
 */
internal fun DatabaseConfig.translatorDialect(): String =
    when (this) {
        is PostgresConfig -> "POSTGRESQL"
        is MssqlConfig -> "MSSQL"
    }
