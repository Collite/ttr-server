// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.query

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.runner.Table
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The lane's answer state (D-146): only a Golem question answers other than [ANSWERED]. */
enum class QueryVerdict(
    val wire: String,
) {
    ANSWERED("answered"),
    CLARIFY("clarify"),
    REFUSED("refused"),
}

/** One request-time derivation; [name] is its variable (for `row`, its id — the row's columns are the variables). */
data class VariableSpec(
    val name: String,
    val kind: String,
    val column: String? = null,
    val fn: String? = null,
    val round: Int? = null,
    val nullIsZero: Boolean = false,
    val distinct: JsonElement? = null,
    val prefix: String? = null,
    val columns: List<String>? = null,
)

/**
 * The derived variables: [values] as the JSON types of §1 (integer → long, number → double, date → ISO), and
 * [exact] the same with every number as its exact decimal string, for the protocol record.
 */
data class DerivedVariables(
    val values: JsonObject,
    val exact: JsonObject,
)

/**
 * D-146's eight kinds over one result frame (`scalar` · `row_count` · `exists` · `aggregate` · `column` · `row` ·
 * `table` · `verdict`), golden semantics where the golden runner has them (rounding half away from zero,
 * `null_is_zero`, "one cell is exactly one row"). Pure: no I/O, no clock.
 */
object Variables {
    const val DEFAULT_MAX_TABLE_ROWS = 500
    private const val MAX_VARIABLES = 64
    private val NAME = Regex("^[a-z][a-z0-9_]*$")
    private val KINDS = setOf("scalar", "row_count", "exists", "aggregate", "column", "row", "table", "verdict")
    private val FNS = setOf("sum", "avg", "count", "min", "max")
    private val NUMERIC = setOf("int", "integer", "bigint", "decimal", "numeric", "float", "double", "real")
    private val INTEGER = setOf("int", "integer", "bigint")

    /** Reads a request's `variables` object; every malformed entry is `400 variable` or `400 variable-name`. */
    fun parse(block: JsonObject?): List<VariableSpec> {
        if (block == null) return emptyList()
        if (block.size > MAX_VARIABLES) bad("at most $MAX_VARIABLES variables per query")
        return block.map { (name, raw) ->
            if (!NAME.matches(name)) badName(name)
            val spec =
                when (raw) {
                    is JsonPrimitive ->
                        if (raw.isString) {
                            mapOf(
                                "kind" to raw,
                            )
                        } else {
                            bad("variable $name: a kind or an object")
                        }
                    is JsonObject -> raw
                    else -> bad("variable $name: a kind or an object")
                }
            val known = setOf("kind", "column", "fn", "round", "null_is_zero", "distinct", "prefix", "columns")
            (spec.keys - known).firstOrNull()?.let { bad("variable $name: unknown field $it") }
            val kind = spec["kind"]?.text() ?: bad("variable $name: kind is required")
            if (kind !in KINDS) bad("variable $name: unknown kind $kind")
            val round =
                spec["round"]?.let {
                    (it as? JsonPrimitive)?.intOrNull
                        ?: bad("variable $name: round is an integer")
                }
            if (round != null && round !in 0..12) bad("variable $name: round is 0..12")
            val prefix = spec["prefix"]?.text()
            if (prefix != null && prefix.isNotEmpty() && !Regex("^[a-z][a-z0-9_]*$").matches(prefix)) badName(prefix)
            VariableSpec(
                name = name,
                kind = kind,
                column = spec["column"]?.text(),
                fn = spec["fn"]?.text(),
                round = round,
                nullIsZero =
                    spec["null_is_zero"]?.let {
                        (it as? JsonPrimitive)?.booleanOrNull ?: bad("variable $name: null_is_zero is a boolean")
                    } ?: false,
                distinct = spec["distinct"],
                prefix = prefix,
                columns =
                    spec["columns"]?.let { c ->
                        (c as? JsonArray)?.map { it.text() } ?: bad("variable $name: columns is a list of names")
                    },
            ).also { check(it) }
        }
    }

    private fun check(spec: VariableSpec) {
        val n = spec.name
        when (spec.kind) {
            "aggregate" -> {
                spec.column ?: bad("variable $n: aggregate needs column")
                if ((spec.fn ?: "sum") !in FNS) bad("variable $n: fn is one of ${FNS.joinToString()}")
            }
            "column" -> spec.column ?: bad("variable $n: column needs column")
            "row_count" ->
                spec.distinct?.let { d ->
                    if (d !is JsonPrimitive || !d.isString) bad("variable $n: row_count distinct names a column")
                }
            "row", "table", "scalar", "exists", "verdict" -> Unit
        }
        if (spec.kind == "column") {
            spec.distinct?.let { d ->
                if ((d as? JsonPrimitive)?.booleanOrNull == null) bad("variable $n: column distinct is a boolean")
            }
        }
        if (spec.round != null && spec.kind != "aggregate") bad("variable $n: round applies to aggregate only")
        if (spec.nullIsZero && spec.kind != "aggregate") bad("variable $n: null_is_zero applies to aggregate only")
        if (spec.prefix != null && spec.kind != "row") bad("variable $n: prefix applies to row only")
        if (spec.columns != null &&
            spec.kind !in setOf("row", "table")
        ) {
            bad("variable $n: columns applies to row and table")
        }
    }

    /**
     * Derives [specs] over [frame]. [truncated]: the frame holds only the first rows of the answer, so a counting kind
     * cannot be answered from it. [verdict] ≠ [QueryVerdict.ANSWERED]: there is no frame and every variable but
     * `verdict` is null (a `row` yields its declared columns, else none).
     */
    fun derive(
        specs: List<VariableSpec>,
        frame: Table?,
        truncated: Boolean,
        verdict: QueryVerdict,
        maxTableRows: Int = DEFAULT_MAX_TABLE_ROWS,
    ): DerivedVariables {
        require(maxTableRows > 0)
        val values = linkedMapOf<String, JsonElement>()
        val exact = linkedMapOf<String, JsonElement>()

        fun put(
            name: String,
            value: Any?,
        ) {
            if (name in values) badName(name, "variable $name is derived twice")
            values[name] = json(value, exact = false)
            exact[name] = json(value, exact = true)
        }
        for (spec in specs) {
            if (spec.kind == "verdict") {
                put(spec.name, verdict.wire)
                continue
            }
            if (verdict != QueryVerdict.ANSWERED || frame == null) {
                if (spec.kind == "row") {
                    spec.columns.orEmpty().forEach { put(rowName(spec, it), null) }
                } else {
                    put(spec.name, null)
                }
                continue
            }
            when (spec.kind) {
                "exists" -> put(spec.name, frame.rows.isNotEmpty())
                "row_count" -> {
                    whole(spec, truncated)
                    val distinct = spec.distinct?.jsonPrimitive?.content
                    put(
                        spec.name,
                        if (distinct == null) {
                            frame.rows.size.toLong()
                        } else {
                            val i = index(frame, distinct, spec)
                            frame.rows
                                .mapNotNull { it[i] }
                                .map(::comparable)
                                .distinct()
                                .size
                                .toLong()
                        },
                    )
                }
                "aggregate" -> {
                    whole(spec, truncated)
                    put(spec.name, aggregate(spec, frame))
                }
                "scalar" -> {
                    single(spec, frame)
                    val i =
                        when {
                            spec.column != null -> index(frame, spec.column, spec)
                            frame.columns.size == 1 -> 0
                            else -> bad("variable ${spec.name}: scalar over ${frame.columns.size} columns needs column")
                        }
                    put(spec.name, frame.rows.firstOrNull()?.get(i))
                }
                "row" -> {
                    single(spec, frame)
                    val names = spec.columns ?: frame.columns.map { it.name }
                    val row = frame.rows.firstOrNull()
                    names.forEach { c -> put(rowName(spec, c), row?.get(index(frame, c, spec))) }
                }
                "column" -> {
                    whole(spec, truncated)
                    val i = index(frame, requireNotNull(spec.column), spec)
                    val all = frame.rows.map { it[i] }
                    val list =
                        if (spec.distinct?.jsonPrimitive?.booleanOrNull == true) {
                            all.filterNotNull().distinctBy(::comparable).sortedWith(::order)
                        } else {
                            all
                        }
                    if (list.size > maxTableRows) tooLarge(spec, list.size, maxTableRows)
                    put(spec.name, list)
                }
                "table" -> {
                    whole(spec, truncated)
                    if (frame.rows.size > maxTableRows) tooLarge(spec, frame.rows.size, maxTableRows)
                    val names = spec.columns ?: frame.columns.map { it.name }
                    val idx = names.map { index(frame, it, spec) }
                    put(spec.name, frame.rows.map { r -> names.zip(idx).associate { (n, i) -> n to r[i] } })
                }
            }
        }
        return DerivedVariables(JsonObject(values), JsonObject(exact))
    }

    private fun aggregate(
        spec: VariableSpec,
        frame: Table,
    ): Any? {
        val i = index(frame, requireNotNull(spec.column), spec)
        val type = frame.columns[i].type.lowercase()
        val fn = spec.fn ?: "sum"
        val raw = frame.rows.map { it[i] }
        if (fn == "count") return (if (spec.nullIsZero) raw.size else raw.count { it != null }).toLong()
        if (fn in setOf("min", "max") && type !in NUMERIC) {
            val present = raw.filterNotNull()
            if (present.isEmpty()) return null
            return if (fn == "min") present.minWith(::order) else present.maxWith(::order)
        }
        if (type !in NUMERIC) bad("variable ${spec.name}: $fn over a $type column")
        val numbers = raw.mapNotNull { v -> decimal(v) ?: if (spec.nullIsZero) BigDecimal.ZERO else null }
        val result: BigDecimal =
            if (numbers.isEmpty()) {
                if (spec.nullIsZero && fn == "sum") BigDecimal.ZERO else return null
            } else {
                when (fn) {
                    "sum" -> numbers.fold(BigDecimal.ZERO, BigDecimal::add)
                    "avg" ->
                        numbers
                            .fold(
                                BigDecimal.ZERO,
                                BigDecimal::add,
                            ).divide(BigDecimal(numbers.size), 16, RoundingMode.HALF_UP)
                    "min" -> numbers.min()
                    else -> numbers.max()
                }
            }
        val rounded = spec.round?.let { result.setScale(it, RoundingMode.HALF_UP) } ?: result
        // HALF_UP on BigDecimal is half away from zero for negative values too (golden ROUND_HALF_UP).
        return if (type in INTEGER && fn != "avg") rounded.toLong() else rounded
    }

    private fun decimal(v: Any?): BigDecimal? =
        when (v) {
            null -> null
            is BigDecimal -> v
            is Long -> BigDecimal.valueOf(v)
            is Int -> BigDecimal.valueOf(v.toLong())
            is Double -> BigDecimal(v.toString())
            is Float -> BigDecimal(v.toString())
            is Number -> BigDecimal(v.toString())
            else -> null
        }

    private fun comparable(v: Any): Any = decimal(v)?.stripTrailingZeros() ?: v

    @Suppress("UNCHECKED_CAST")
    private fun order(
        a: Any?,
        b: Any?,
    ): Int {
        val da = a?.let(::decimal)
        val db = b?.let(::decimal)
        if (da != null && db != null) return da.compareTo(db)
        return kotlin.comparisons.compareValues(a as Comparable<Any>?, b as Comparable<Any>?)
    }

    private fun rowName(
        spec: VariableSpec,
        column: String,
    ): String {
        val name = (spec.prefix.orEmpty()) + fold(column)
        if (!NAME.matches(name)) badName(name, "row column $column folds to $name, not a variable name")
        return name
    }

    /** `název` → `nazev`; anything outside `[a-z0-9_]` → `_` (C-30). */
    internal fun fold(column: String): String =
        Normalizer
            .normalize(column, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z0-9_]"), "_")

    private fun index(
        frame: Table,
        column: String,
        spec: VariableSpec,
    ): Int =
        frame.columns.indexOfFirst { it.name == column }.takeIf { it >= 0 }
            ?: bad("variable ${spec.name}: the frame has no column $column")

    private fun single(
        spec: VariableSpec,
        frame: Table,
    ) {
        if (frame.rows.size > 1) {
            throw AutomationException(
                ErrorCode.ROW_NOT_SINGLE,
                "variable ${spec.name} (${spec.kind}) needs at most one row; the frame has ${frame.rows.size}",
                buildJsonObject {
                    put("variable", spec.name)
                    put("rows", frame.rows.size)
                },
            )
        }
    }

    private fun whole(
        spec: VariableSpec,
        truncated: Boolean,
    ) {
        if (truncated) {
            throw AutomationException(
                ErrorCode.VARIABLE_TOO_LARGE,
                "variable ${spec.name} (${spec.kind}) cannot be derived from a truncated frame",
                buildJsonObject {
                    put("variable", spec.name)
                    put("reason", "frame-truncated")
                },
            )
        }
    }

    private fun tooLarge(
        spec: VariableSpec,
        rows: Int,
        max: Int,
    ): Nothing =
        throw AutomationException(
            ErrorCode.VARIABLE_TOO_LARGE,
            "variable ${spec.name} would carry $rows rows; at most $max",
            buildJsonObject {
                put("variable", spec.name)
                put("rows", rows)
                put("max", max)
            },
        )

    /** §1's JSON types; [exact] keeps every number as its exact decimal string (the protocol record). */
    internal fun json(
        value: Any?,
        exact: Boolean,
    ): JsonElement =
        when (value) {
            null -> JsonNull
            is Boolean -> JsonPrimitive(value)
            is Long -> if (exact) JsonPrimitive(value.toString()) else JsonPrimitive(value)
            is Int -> if (exact) JsonPrimitive(value.toString()) else JsonPrimitive(value.toLong())
            is BigDecimal -> if (exact) JsonPrimitive(value.toPlainString()) else JsonPrimitive(value.toDouble())
            is Double ->
                if (exact) {
                    JsonPrimitive(
                        BigDecimal(value.toString()).toPlainString(),
                    )
                } else {
                    JsonPrimitive(value)
                }
            is Float -> json(value.toDouble(), exact)
            is LocalDate -> JsonPrimitive(value.toString())
            is LocalDateTime -> JsonPrimitive(value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
            is OffsetDateTime -> JsonPrimitive(value.withOffsetSameInstant(ZoneOffset.UTC).toInstant().toString())
            is java.time.Instant -> JsonPrimitive(value.toString())
            is List<*> -> JsonArray(value.map { json(it, exact) })
            is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to json(v, exact) })
            else -> JsonPrimitive(value.toString())
        }

    private fun JsonElement.text(): String =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content ?: bad("a variable field must be a string")

    private fun bad(message: String): Nothing = throw AutomationException(ErrorCode.VARIABLE, message)

    private fun badName(
        name: String,
        message: String = "variable name $name must match ^[a-z][a-z0-9_]*$",
    ): Nothing = throw AutomationException(ErrorCode.VARIABLE_NAME, message, buildJsonObject { put("name", name) })
}
