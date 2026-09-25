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
import java.security.MessageDigest

/** One row a read plan returned, in the plan's column order: the owning entity's key, then the value. */
data class ReadRow(
    val key: String,
    val value: String,
)

/**
 * Metadata-driven member loader (MV-T2, member-vocabulary contracts §4).
 *
 * Asks Veles for the estate's **member vocabularies** — one per indexed attribute, each with the
 * read plan the translator rendered from its entity for this warehouse's dialect — runs every plan
 * via [fetchRows], and returns the member load for `StringRepository.refreshCache` to swap in
 * atomically. The category is the vocabulary's own (`er.<ns>.<entity>.<attribute>`, or
 * `db.<ns>.<table>.<column>` on a db-only estate), taken verbatim; every candidate carries the
 * vocabulary's match method, so the dispatcher holds an `EXACT` code to exact equality.
 *
 * **A member is a VALUE (A-MV-15, review-104 F2).** A vocabulary's candidates are the attribute's
 * DISTINCT values, and each one's id is the value itself, exactly as the warehouse stores it. The
 * read plan returns one row per entity row — `TN` once for every store in Tennessee — and a
 * governed value is a filter on the attribute (`state = 'TN'`), not a pick of one store: N rows of
 * one label reached the resolver as N identities in one tie band, and "stores in TN" asked the user
 * to choose between identical options. The id is also what the consumer compares the attribute
 * against — Golem renders a member filter as `<attribute column> = <id>` — so a key there filtered
 * `state = '7'`. The key column is read and not kept: it is what makes the plan's `DISTINCT` range
 * over the entity's population.
 *
 * This loader knows nothing about tables, keys or SQL any more. Which vocabularies exist, whose rows
 * they read and how to spell that in SQL are Veles' and the translator's decisions — the same ones a
 * query over the entity is subject to. (It used to walk fuzzy-tagged COLUMNS and compose
 * `SELECT pk, col FROM table`, so two entities over one table shared one index, and a view- or
 * query-backed entity was indexed over its whole base table.)
 *
 * **An outage never wipes a vocabulary (review-104 F5).** No listing at all is `null`: the
 * repository keeps its whole member layer. And one vocabulary that is listed but cannot be read this
 * time — no read plan yet (a query-backed entity in Veles' parse window), or a read plan the
 * warehouse refused — keeps the rows, plan version and method of its last load. Only a vocabulary
 * Veles no longer LISTS leaves the layer.
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
    private val fetchRows: (String) -> List<ReadRow>,
    private val telemetry: FuzzyTelemetry? = null,
    // RS-12-γ alias tables (`semantics{kind: alias_table}`). PENDING COUPLING
    // (rule 6): Veles does not yet report alias-table declarations (no
    // `semantics` in `org.tatrman.meta.v1`); this provider is stubbed to empty
    // until the RG-P4 metadata work lands. The ingestion + merge logic is live
    // and tested (composeAliasCandidates); only the source of `decls` is stubbed.
    private val aliasTables: () -> List<AliasTableDecl> = { emptyList() },
) : LoaderSource {
    private val logger = LoggerFactory.getLogger(MetadataLoaderSource::class.java)

    /** A vocabulary as it was last loaded — what a listed-but-unreadable vocabulary keeps serving. */
    private data class Loaded(
        val candidates: List<Candidate>,
        val planVersion: String,
        val method: String,
    )

    // Loader report: why a declared vocabulary is not loaded (Veles' RG-FUZ-001/003 per item), or
    // why nothing was (RG-FUZ-004). Retrievable via GetStatus.
    @Volatile
    private var lastWarnings: List<LoaderWarningInfo> = emptyList()

    // The last load, by category as Veles spells it (F5's carry-over). Written only at the end of a
    // load; the repository serialises refreshes, so no two loads interleave here.
    @Volatile
    private var previous: Map<String, Loaded> = emptyMap()

    // Whether any listing has ever been taken — what RG-FUZ-004 says is being served meanwhile.
    @Volatile
    private var listedOnce: Boolean = false

    override fun warnings(): List<LoaderWarningInfo> = lastWarnings

    override suspend fun loadNextCache(): Map<String, List<Candidate>>? = load()?.categories

    override suspend fun load(): MemberLoad? {
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
        listedOnce = true

        val warnings = mutableListOf<LoaderWarningInfo>()
        val kept = LinkedHashMap<String, Loaded>()

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
            val carried = previous[category]

            // Listed but not loadable: no single key (RG-FUZ-001) or no read plan (RG-FUZ-003).
            // Veles says which and why; the loader passes it on and never reads the rows another way.
            if (item.readSql.isEmpty()) {
                val suffix = if (carried != null) " $SERVING_PREVIOUS" else ""
                val diagnostics =
                    item.diagnosticsList.map { LoaderWarningInfo(it.code, category, it.humanMessage + suffix) }
                warnings +=
                    diagnostics.ifEmpty {
                        listOf(LoaderWarningInfo(NO_READ_PLAN, category, "Veles listed it with no read plan$suffix"))
                    }
                telemetry?.recordSkipped(if (diagnostics.any { it.code == NO_KEY }) "no_key" else "no_read_plan")
                logger.debug("Skipping member vocabulary {} — {}", category, diagnostics.map { it.code })
                carried?.let { kept[category] = it }
                continue
            }

            val method = methodOf(item)
            try {
                val rows = fetchRows(item.readSql)
                val members = members(rows, method, category)
                kept[category] = Loaded(members, planIdentity(item, method), method)
                logger.info(
                    "Loaded {} candidates for '{}' ({}) from {} rows",
                    members.size,
                    category,
                    method,
                    rows.size,
                )
            } catch (e: Exception) {
                telemetry?.recordSkipped("sql_failed")
                logger.error("Read plan failed for '{}': {}", category, item.readSql, e)
                if (carried != null) {
                    logger.warn(
                        "Member vocabulary '{}' keeps its previous load ({} rows)",
                        category,
                        carried.candidates.size,
                    )
                    kept[category] = carried
                }
            }
        }
        logSharedReadPlans(items)

        val result = kept.mapValuesTo(LinkedHashMap()) { it.value.candidates }
        mergeAliases(result, kept)

        previous = kept
        lastWarnings = warnings

        val durationSeconds = (System.nanoTime() - start) / 1_000_000_000.0
        telemetry?.recordRefreshDuration(durationSeconds)
        telemetry?.updateCategories(result.mapValues { it.value.size })

        return MemberLoad(result, kept.mapValues { it.value.planVersion })
    }

    /**
     * A vocabulary's candidates: its DISTINCT values, each its own id (A-MV-15), in the plan's order,
     * built once with the vocabulary's method and category (review-104 F13).
     */
    private fun members(
        rows: List<ReadRow>,
        method: String,
        category: String,
    ): List<Candidate> {
        val seen = HashSet<String>()
        val out = ArrayList<Candidate>()
        for (row in rows) {
            if (seen.add(row.value)) out += Candidate.fromValues(row.value, row.value, method, category)
        }
        return out
    }

    /**
     * RS-12-γ: merge estate alias-table synonyms into their owning member category (same key space),
     * matched under the owner's method — a synonym is the same member spelled another way.
     * `composeAliasCandidates` reuses the SQL identifier-validation discipline; a rejected declaration
     * contributes nothing rather than aborting the load.
     *
     * Merged under the member category's OWN spelling (review-104 F14): the composition keys its
     * groups lower-cased, and a mixed-case category (`er.df.Zákazník.Název`) merged under the
     * lower-cased key made a second entry, of which the repository's case-folding kept only one.
     *
     * ⚑ PENDING COUPLING (A-MV-15). An alias row still carries the owner's KEY as its id, and a member
     * row now carries its VALUE, so an alias match would not resolve to its member. The alias plan
     * must project the member's value (a join to the owner) before RG-P4 un-stubs [aliasTables].
     */
    private fun mergeAliases(
        result: MutableMap<String, List<Candidate>>,
        kept: Map<String, Loaded>,
    ) {
        val decls = aliasTables()
        if (decls.isEmpty()) return
        val spelled = kept.keys.associateBy { it.lowercase() }
        val aliasByCategory =
            composeAliasCandidates(decls, dialect) { sql ->
                try {
                    fetchRows(sql)
                } catch (e: Exception) {
                    telemetry?.recordSkipped("alias_sql_failed")
                    logger.error("Alias-table SQL failed: $sql", e)
                    emptyList()
                }
            }
        aliasByCategory.forEach { (lowered, rows) ->
            val category = spelled[lowered] ?: lowered
            val method = kept[category]?.method ?: MatchMethodNames.EXACT
            val aliases = rows.map { Candidate.fromValues(it.key, it.value, method, category) }
            result.merge(category, aliases) { primary, alias -> primary + alias }
        }
    }

    /**
     * No listing to load: keep serving the previous cache, and say so in GetStatus (RG-FUZ-004) —
     * beside the previous load's own warnings, which still describe the cache being served. Before
     * any listing was ever taken there is no previous load, and the warning says that instead
     * (review-104 F1) rather than promising one.
     */
    private fun unavailable(reason: String): MemberLoad? {
        val served = if (listedOnce) SERVING_PREVIOUS_LOAD else NOTHING_LOADED_YET
        lastWarnings =
            lastWarnings.filterNot { it.code == UNAVAILABLE } +
            LoaderWarningInfo(
                UNAVAILABLE,
                "",
                RgDiagnostics.render(UNAVAILABLE, "reason" to reason, "served" to served),
            )
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

    /**
     * A-MV-10, refined (review-104 F8) — what identifies how this vocabulary's rows were read and are
     * matched: its read plan, key and effective method.
     *
     * NOT Veles' `MemberVocabulary.version`, which also hashes the whole MODEL version: any unrelated
     * edit (a new measure, a changed description) moved every member category's version on the next
     * refresh although nothing about its rows, plan or method had changed — and `member_index_versions`
     * is read as "did this layer change?".
     */
    private fun planIdentity(
        item: MemberVocabulary,
        method: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        for (part in listOf(item.readSql, item.keyAttribute, method)) {
            digest.update(part.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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

        const val SERVING_PREVIOUS = "Its previous load is still served."
        const val SERVING_PREVIOUS_LOAD = "the previous load is still served"
        const val NOTHING_LOADED_YET = "no member vocabulary has been loaded yet"
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
