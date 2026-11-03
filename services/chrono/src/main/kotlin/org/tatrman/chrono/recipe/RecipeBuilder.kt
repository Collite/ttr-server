// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recipe

import org.tatrman.grounding.v1.DateTimeInterval
import org.tatrman.grounding.v1.FilterRecipe
import org.tatrman.grounding.v1.GroundingResult
import org.tatrman.grounding.v1.JoinRecipe
import org.tatrman.grounding.v1.Normalized
import org.tatrman.grounding.v1.ValueBinding
import org.tatrman.plan.v1.JoinType
import org.tatrman.grounding.core.PlanExpr
import org.tatrman.grounding.core.SqlRenderer
import org.tatrman.chrono.discover.ColumnRef
import org.tatrman.chrono.discover.PeriodTable
import org.tatrman.chrono.discover.SemanticDiscovery
import org.tatrman.chrono.recognize.ChronoKind
import org.tatrman.chrono.recognize.ChronoRecognition
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Turns a [ChronoRecognition] + the package's metadata into a [GroundingResult] (A8.4). Four shapes:
 *  - PERIOD with a table-backed period entity → **JoinRecipe** (`fact.date >= ap.start AND < ap.end`,
 *    filter `ap.code = {p}`);
 *  - PERIOD with no period table (calendar-aligned) → **FilterRecipe** over the `period_start`/
 *    `period_end` catalog functions;
 *  - a plain interval (ABSOLUTE / RELATIVE / FISCAL_YEAR) → **FilterRecipe** with datetime bounds;
 *  - no anchor date column and no period-code route → **ValueBinding** (`start`, `end`, and `p` when the
 *    recognition carries a period code): the interval is known, where it goes is the caller's (TS-1).
 *
 * `sql_preview` is rendered by [SqlRenderer] from the recipe's own Expression tree (single source of
 * truth); A8.5 round-trips it through the Translator. A ValueBinding has no column to render against,
 * so its `sql_preview` is empty. Never null: a recognised span always yields its interval.
 */
class RecipeBuilder(
    private val discovery: SemanticDiscovery,
    private val defaultTimezone: String = "UTC",
) {
    private companion object {
        const val FACT_ALIAS = "t"

        /** A `yyyyQn` quarter period code (Q-18), e.g. "2026Q2" — distinct from the `yyyyMM` month code. */
        val QUARTER_CODE = Regex("""(\d{4})Q([1-4])""")
    }

    suspend fun build(
        span: String,
        recognition: ChronoRecognition,
        pkg: String,
        timezone: String,
    ): GroundingResult {
        val zone = runCatching { ZoneId.of(timezone.ifEmpty { defaultTimezone }) }.getOrDefault(ZoneOffset.UTC)
        val startIso = isoAtStartOfDay(recognition.startInclusive, zone)
        val endIso = isoAtStartOfDay(recognition.endExclusive, zone)
        val normalized =
            Normalized
                .newBuilder()
                .setInterval(DateTimeInterval.newBuilder().setStart(startIso).setEnd(endIso))
                .build()

        val pt = discovery.periodTable(pkg)
        val anchor = discovery.anchorColumn(pkg, recognition.target)

        // Date-less, period-coded fact (issue #140): the fact has no date column to bound
        // (e.g. an accounting ledger keyed only by period code), but the package declares a
        // period table with a code column and the recognition maps to a period. Bind the
        // period code directly (`code = {p}`) — the period table IS the date dimension, so
        // no fact anchor date column is required.
        if (anchor == null && recognition.periodCode != null && pt?.code != null) {
            val code = reformatCode(recognition.periodCode, pt.codeFormat)
            return periodCodeRecipe(pt.code, code, recognition, normalized, span)
        }
        // No fact date column and no period code to fall back on: the interval still stands — it was
        // read from the reference date alone, the model contributes nothing to it (TS-1). Hand over
        // the values; which attribute they restrict is the caller's call.
        if (anchor == null) return valuesRecipe(recognition, pt, normalized, startIso, endIso, pkg)

        // Short, non-reserved fact alias (entity names like Transaction/Order/User are SQL reserved
        // words — an unquoted entity-name alias would make the sql_preview unparseable). Golem's
        // cascade (A13) rebinds this to the merged query's actual fact alias; `anchor_column` carries
        // the resolvable column identity.
        val factCol = PlanExpr.col(FACT_ALIAS, anchor.columnName)

        return if (recognition.kind == ChronoKind.PERIOD) {
            val code = reformatCode(recognition.periodCode!!, pt?.codeFormat ?: "yyyyMM")
            when {
                pt.hasColumns() -> joinRecipe(pt!!, factCol, code, recognition, normalized, span)
                // A yyyyQn quarter (Q-18) can't lower through the period_start/period_end catalog
                // functions (the translator supports only yyyyMM/yyyyMMdd), so a table-less quarter
                // degrades to the honest calendar-quarter datetime-bounds FilterRecipe.
                isQuarterCode(recognition.periodCode) ->
                    intervalRecipe(anchor, factCol, startIso, endIso, recognition, normalized, span)
                else -> periodFunctionRecipe(anchor, factCol, code, recognition, normalized, span)
            }
        } else {
            intervalRecipe(anchor, factCol, startIso, endIso, recognition, normalized, span)
        }
    }

    // ----- no anchor column → ValueBinding (params only; the caller places them) -----

    private fun valuesRecipe(
        recognition: ChronoRecognition,
        pt: PeriodTable?,
        normalized: Normalized,
        startIso: String,
        endIso: String,
        pkg: String,
    ): GroundingResult {
        val values =
            ValueBinding
                .newBuilder()
                .addParameters(PlanExpr.datetimeParam("start", startIso, "Interval start (inclusive)"))
                .addParameters(PlanExpr.datetimeParam("end", endIso, "Interval end (exclusive)"))
                .apply {
                    recognition.periodCode?.let { code ->
                        addParameters(
                            PlanExpr.textParam("p", reformatCode(code, pt?.codeFormat ?: "yyyyMM"), "Period code"),
                        )
                    }
                }.build()
        return GroundingResult
            .newBuilder()
            .setNormalized(normalized)
            .setValues(values)
            .setSqlPreview("")
            .setConfidence(recognition.confidence.toFloat())
            .setSource(GroundingResult.Source.RULES)
            .setExplanation(
                "interval computed; no anchor date column declared in package '$pkg' — placement is the caller's",
            ).build()
    }

    // ----- date-less period-coded fact → FilterRecipe on the period code column -----

    private fun periodCodeRecipe(
        codeColumn: ColumnRef,
        code: String,
        recognition: ChronoRecognition,
        normalized: Normalized,
        span: String,
    ): GroundingResult {
        // `code = {p}` on the period table's own code column. The fact has no date column;
        // the query already joins the period entity (or filters its code directly), so only
        // the `p` parameter is load-bearing for Golem's pattern rail.
        val codeCol = PlanExpr.col(FACT_ALIAS, codeColumn.columnName, "text")
        val condition = PlanExpr.eq(codeCol, PlanExpr.param("p", "text"))
        val filter =
            FilterRecipe
                .newBuilder()
                .setCondition(condition)
                .addParameters(PlanExpr.textParam("p", code, "Accounting period"))
                .setAnchorColumn(codeColumn.qname)
                .build()
        return result(normalized, recognition, SqlRenderer.render(condition), "accounting period $code") {
            setFilter(filter)
        }
    }

    // ----- table-backed period → JoinRecipe -----

    private fun joinRecipe(
        pt: PeriodTable,
        factCol: org.tatrman.plan.v1.Expression,
        code: String,
        recognition: ChronoRecognition,
        normalized: Normalized,
        span: String,
    ): GroundingResult {
        val alias = "ap"
        val onCondition =
            PlanExpr.and(
                PlanExpr.ge(factCol, PlanExpr.col(alias, pt.start!!.columnName)),
                PlanExpr.lt(factCol, PlanExpr.col(alias, pt.end!!.columnName)),
            )
        val filter = PlanExpr.eq(PlanExpr.col(alias, pt.code!!.columnName, "text"), PlanExpr.param("p", "text"))
        val join =
            JoinRecipe
                .newBuilder()
                .setEntity(pt.entity)
                .setJoinType(JoinType.INNER)
                .setOnCondition(onCondition)
                .setFilter(filter)
                .addParameters(PlanExpr.textParam("p", code, "Accounting period"))
                .setSuggestedAlias(alias)
                .build()
        val sql = SqlRenderer.renderJoin(pt.entityName, alias, onCondition, filter)
        return result(normalized, recognition, sql, "accounting period $code") { setJoin(join) }
    }

    // ----- calendar-aligned period → FilterRecipe with catalog functions -----

    private fun periodFunctionRecipe(
        anchor: ColumnRef,
        factCol: org.tatrman.plan.v1.Expression,
        code: String,
        recognition: ChronoRecognition,
        normalized: Normalized,
        span: String,
    ): GroundingResult {
        val condition =
            PlanExpr.and(
                PlanExpr.ge(factCol, PlanExpr.periodStart(PlanExpr.param("p", "text"))),
                PlanExpr.lt(factCol, PlanExpr.periodEnd(PlanExpr.param("p", "text"))),
            )
        val filter =
            FilterRecipe
                .newBuilder()
                .setCondition(condition)
                .addParameters(PlanExpr.textParam("p", code, "Accounting period"))
                .setAnchorColumn(anchor.qname)
                .build()
        return result(normalized, recognition, SqlRenderer.render(condition), "accounting period $code") {
            setFilter(filter)
        }
    }

    // ----- plain interval → FilterRecipe with datetime bounds -----

    private fun intervalRecipe(
        anchor: ColumnRef,
        factCol: org.tatrman.plan.v1.Expression,
        startIso: String,
        endIso: String,
        recognition: ChronoRecognition,
        normalized: Normalized,
        span: String,
    ): GroundingResult {
        val condition =
            PlanExpr.and(
                PlanExpr.ge(factCol, PlanExpr.param("start", "datetime")),
                PlanExpr.lt(factCol, PlanExpr.param("end", "datetime")),
            )
        val filter =
            FilterRecipe
                .newBuilder()
                .setCondition(condition)
                .addParameters(PlanExpr.datetimeParam("start", startIso, "Interval start (inclusive)"))
                .addParameters(PlanExpr.datetimeParam("end", endIso, "Interval end (exclusive)"))
                .setAnchorColumn(anchor.qname)
                .build()
        val desc = "[${recognition.startInclusive}, ${recognition.endExclusive})"
        return result(normalized, recognition, SqlRenderer.render(condition), desc) { setFilter(filter) }
    }

    // ----- helpers -----

    private fun result(
        normalized: Normalized,
        recognition: ChronoRecognition,
        sqlPreview: String,
        desc: String,
        application: GroundingResult.Builder.() -> GroundingResult.Builder,
    ): GroundingResult =
        GroundingResult
            .newBuilder()
            .setNormalized(normalized)
            .apply { application() }
            .setSqlPreview(sqlPreview)
            .setConfidence(recognition.confidence.toFloat())
            .setSource(GroundingResult.Source.RULES)
            .setExplanation("Resolved the time span to $desc.")
            .build()

    private fun isoAtStartOfDay(
        date: LocalDate,
        zone: ZoneId,
    ): String = date.atStartOfDay(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private fun PeriodTable?.hasColumns(): Boolean = this != null && start != null && end != null && code != null

    /**
     * Re-render the recognizer's canonical `yyyyMM` code per the period table's `code_format`.
     * Supports the common `yyyyMM` / `yyyy-MM` / `yyyy/MM` shapes; unknown formats pass through
     * the canonical code unchanged.
     */
    private fun isQuarterCode(code: String): Boolean = QUARTER_CODE.matches(code)

    private fun reformatCode(
        canonical: String,
        format: String,
    ): String {
        // Quarter codes (yyyyQn) are their own canonical form — the monthly reformatting below doesn't
        // apply. Honour the common separator variants; default to the compact yyyyQn.
        QUARTER_CODE.matchEntire(canonical)?.let { m ->
            val (year, q) = m.destructured
            return if (format == "yyyy-Qn" || format == "yyyy-Q") "$year-Q$q" else "${year}Q$q"
        }
        if (canonical.length != 6) return canonical
        val year = canonical.substring(0, 4)
        val month = canonical.substring(4, 6)
        return when (format) {
            "yyyyMM", "" -> canonical
            "yyyy-MM" -> "$year-$month"
            "yyyy/MM" -> "$year/$month"
            else -> format.replace("yyyy", year).replace("MM", month)
        }
    }
}
