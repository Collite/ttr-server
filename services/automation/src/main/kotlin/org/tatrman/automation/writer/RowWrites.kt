// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.writer

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import org.tatrman.automation.apply.ApplyRow
import org.tatrman.automation.apply.ObligationStates
import org.tatrman.plan.v1.ColumnRef
import org.tatrman.plan.v1.ColumnValue
import org.tatrman.plan.v1.Expression
import org.tatrman.plan.v1.FunctionCall
import org.tatrman.plan.v1.ParameterBinding
import org.tatrman.plan.v1.ParameterRef
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.RowWrite
import org.tatrman.plan.v1.RowWriteOp
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.plan.v1.Value
import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.Entity

/** A row the writer refuses before any door call; the code goes to `batch_rows.error`. */
class RowRefusal(
    val code: String,
    message: String,
) : Exception(message)

/**
 * One `RowWrite` and the §4 rows (`seqs`) it carries. A transition and its action's `set:` on the same key are one
 * compare-and-set (`attribute IN (from…, to)`, D-67/D-85); a key-scoped `_current` delete is one statement per
 * declared route, because the translator requires an equality on every primary-key column (review-219 F2).
 */
data class WriteUnit(
    val seqs: List<Int>,
    val row: RowWrite,
    val kind: Kind,
    /** The transition this unit applies, when [kind] is [Kind.CAS]. */
    val transition: ApplyRow? = null,
) {
    enum class Kind { INSERT, IF_ABSENT, DELETE, UPDATE, CAS }
}

/**
 * Contracts §4 → the D-163 `row_writes` wire. Targets are ER entities (`er.entity.<name>`); Query lowers them through
 * the read binding. Every value is a parameter (never interpolated), typed from the target attribute's declared
 * type. This is a pure function of the pinned entity metadata; it neither reads nor writes.
 */
class RowWrites(
    private val entities: List<Entity>,
    /** The recipe's declared routes: the `_current` delete covers each of them (D-105, R217-C). */
    private val routes: List<String>,
    /**
     * S2.3 `-rev`: an inverse transition restores a recorded prior state, which the authored `transitions:` graph
     * usually does not lead to (placed → held has no held → placed). Its compare-and-set on the recorded `to` still
     * guards it; the declared graph is not consulted (D-187).
     */
    private val inverse: Boolean = false,
) {
    private var next = 0
    private val bindings = mutableListOf<ParameterBinding>()

    val parameters: List<ParameterBinding> get() = bindings.toList()

    fun entity(name: String): Entity =
        entities.singleOrNull { it.qname.dotted() == name || "er.entity.${it.qname.name}" == name }
            ?: throw RowRefusal("unknown-entity", "no pinned entity $name")

    /**
     * The rows no `writable:` entry covers. An entry matches the entity (`obligation`, `"*_verdict"`) or
     * `<entity>.<attribute>` (`order.state`); an `update`/`transition` needs every attribute it assigns covered.
     */
    fun unwritable(
        unit: List<ApplyRow>,
        patterns: List<String>,
    ): List<ApplyRow> =
        unit.filterNot { row ->
            val entity = entity(row.entity).qname.name
            val attributes =
                when (row.op) {
                    "update" -> requireNotNull(row.set).keys
                    "transition" -> setOf(requireNotNull(row.attribute))
                    else -> emptySet()
                }
            if (attributes.isEmpty()) {
                patterns.any { matches(it, entity) }
            } else {
                attributes.all { a -> patterns.any { matches(it, entity) || matches(it, "$entity.$a") } }
            }
        }

    fun insert(
        row: ApplyRow,
        ifAbsent: Boolean,
    ): WriteUnit {
        val entity = entity(row.entity)
        val values = requireNotNull(row.row)
        return WriteUnit(
            listOf(row.seq),
            RowWrite
                .newBuilder()
                .setTarget(target(entity))
                .setOp(RowWriteOp.INSERT)
                .setIfAbsent(ifAbsent)
                .addAllValues(
                    values.map { (name, value) ->
                        ColumnValue
                            .newBuilder()
                            .setName(name)
                            .setValue(param(entity, name, value))
                            .build()
                    },
                ).build(),
            if (ifAbsent) WriteUnit.Kind.IF_ABSENT else WriteUnit.Kind.INSERT,
        )
    }

    /** D-105: a key-scoped delete; the key's missing primary-key column may only be `route`. */
    fun delete(row: ApplyRow): List<WriteUnit> {
        val entity = entity(row.entity)
        val key = requireNotNull(row.key)
        val missing = keys(entity).filter { it !in key.keys }
        val expanded =
            when {
                missing.isEmpty() -> listOf(key)
                missing == listOf("route") -> routes.map { JsonObject(key + ("route" to JsonPrimitive(it))) }
                else -> throw RowRefusal("row_writes_key", "a delete on ${row.entity} misses key columns $missing")
            }
        return expanded.map { full ->
            WriteUnit(
                listOf(row.seq),
                RowWrite
                    .newBuilder()
                    .setTarget(target(entity))
                    .setOp(RowWriteOp.DELETE)
                    .setWhere(where(entity, full))
                    .build(),
                WriteUnit.Kind.DELETE,
            )
        }
    }

    /**
     * An `update` alone, or a `transition` with the `update`s of the same action and key. `read_before` holds the
     * attribute and the `set:` columns: their `prior` is the history row's `from` and S2.3's `-rev`.
     */
    fun update(
        updates: List<ApplyRow>,
        transition: ApplyRow?,
    ): WriteUnit {
        val anchor = transition ?: updates.single()
        val entity = entity(anchor.entity)
        val key = requireNotNull(anchor.key)
        require((updates + listOfNotNull(transition)).all { it.entity == anchor.entity && it.key == key })
        val set = linkedMapOf<String, JsonElement>()
        var predicate = where(entity, key)
        if (transition != null) {
            val name = requireNotNull(transition.attribute)
            val to = requireNotNull(transition.to as? JsonPrimitive).content
            set[name] = JsonPrimitive(to)
            allowedFrom(entity, transition, to)?.let { from ->
                predicate =
                    call(
                        "and",
                        predicate,
                        call(
                            "in",
                            column(name),
                            *(from + to).distinct().map { param(entity, name, JsonPrimitive(it)) }.toTypedArray(),
                        ),
                    )
            }
        }
        for (update in updates) {
            for ((name, value) in requireNotNull(update.set)) {
                if (name in set) throw RowRefusal("row_writes_values", "${anchor.entity}.$name is assigned twice")
                set[name] = value
            }
        }
        val readBefore = set.keys.toList()
        return WriteUnit(
            (updates.map { it.seq } + listOfNotNull(transition?.seq)).sorted(),
            RowWrite
                .newBuilder()
                .setTarget(target(entity))
                .setOp(RowWriteOp.UPDATE)
                .addAllValues(
                    set.map { (name, value) ->
                        ColumnValue
                            .newBuilder()
                            .setName(name)
                            .setValue(param(entity, name, value))
                            .build()
                    },
                ).setWhere(predicate)
                .addAllReadBefore(readBefore)
                .addAllPriorKeys(readBefore)
                .build(),
            if (transition == null) WriteUnit.Kind.UPDATE else WriteUnit.Kind.CAS,
            transition,
        )
    }

    /**
     * One `state_history` insert (stdlib shape); [values] are already the row's columns. `if_absent` on the row's
     * deterministic id: a resend finds a row that already landed instead of inserting it (review-244 B1).
     */
    fun history(values: JsonObject): RowWrite =
        insert(ApplyRow(1, "er.entity.state_history", "insert", row = values), true).row

    /**
     * The states a transition may leave: the action's `from:` (or every state that declares `to`), intersected with the
     * attribute's declared `transitions:`. A declared graph that does not lead to `to` → `bad-transition`. No graph:
     * the action's `from:`, an obligation's active states (D-98), else no state predicate (null).
     */
    private fun allowedFrom(
        entity: Entity,
        transition: ApplyRow,
        to: String,
    ): List<String>? {
        val attribute = attribute(entity, requireNotNull(transition.attribute))
        if (attribute.states.isNotEmpty() && to !in attribute.states) bad(transition, to)
        if (inverse) return transition.from.ifEmpty { null }
        val graph = attribute.transitions
        if (graph.isEmpty()) {
            return transition.from.ifEmpty { null }
                ?: if (entity.qname.name == "obligation") ObligationStates.active.sorted() else null
        }
        val declared = graph.filterValues { to in it }.keys
        if (transition.from.any { it != to && it !in declared }) bad(transition, to)
        val allowed = transition.from.ifEmpty { declared.sorted() }.filter { it != to }
        if (allowed.isEmpty()) bad(transition, to)
        return allowed
    }

    private fun bad(
        transition: ApplyRow,
        to: String,
    ): Nothing =
        throw RowRefusal(
            "bad-transition",
            "${transition.entity}.${transition.attribute} declares no transition ${transition.from} -> $to",
        )

    private fun keys(entity: Entity) =
        entity.attributes.filter { it.isKey }.map { it.qname.name.substringAfterLast('.') }

    private fun attribute(
        entity: Entity,
        name: String,
    ): Attribute =
        entity.attributes.singleOrNull { it.qname.name.substringAfterLast('.') == name }
            ?: throw RowRefusal("unknown-attribute", "${entity.qname.dotted()} has no attribute $name")

    private fun where(
        entity: Entity,
        key: JsonObject,
    ): Expression {
        val equalities = key.map { (name, value) -> call("eq", column(name), param(entity, name, value)) }
        return equalities.singleOrNull() ?: call("and", *equalities.toTypedArray())
    }

    private fun target(entity: Entity): QualifiedName =
        QualifiedName
            .newBuilder()
            .setSchemaCode(SchemaCode.ER)
            .setNamespace("entity")
            .setName(entity.qname.name)
            .build()

    private fun column(name: String) =
        Expression.newBuilder().setColumnRef(ColumnRef.newBuilder().setName(name)).build()

    private fun call(
        op: String,
        vararg operands: Expression,
    ) = Expression
        .newBuilder()
        .setFunction(FunctionCall.newBuilder().setOperation(op).addAllOperands(operands.toList()))
        .build()

    /** The value as a typed parameter of the request (`w0`, `w1`, …), from the target attribute's declared type. */
    private fun param(
        entity: Entity,
        name: String,
        json: JsonElement,
    ): Expression {
        val tag = tag(attribute(entity, name).type)
        val value = Value.newBuilder()
        val primitive =
            json as? JsonPrimitive ?: throw RowRefusal("bad-value", "${entity.qname.name}.$name is not a scalar")
        try {
            when {
                json == JsonNull -> value.isNull = true
                tag == "int" ->
                    value.intValue =
                        primitive.longOrNull ?: primitive.content.toBigDecimal().longValueExact()
                tag == "float" -> value.floatValue = primitive.content.toDouble()
                tag == "bool" -> value.boolValue = primitive.booleanOrNull ?: error("not a boolean")
                tag == "datetime" -> value.datetimeValue = primitive.content
                else -> value.stringValue = primitive.content
            }
        } catch (e: RowRefusal) {
            throw e
        } catch (e: Exception) {
            throw RowRefusal("bad-value", "${entity.qname.name}.$name cannot be bound as $tag")
        }
        val parameter = "w${next++}"
        bindings +=
            ParameterBinding
                .newBuilder()
                .setName(parameter)
                .setType(tag)
                .setValue(value)
                .build()
        return Expression.newBuilder().setParameter(ParameterRef.newBuilder().setName(parameter)).build()
    }

    companion object {
        /** The worker's binding tags (ExecutePipeline.bindParameters). The wire has no decimal arm: decimals go as float. */
        fun tag(type: String): String {
            val base = type.substringBefore('(').trim().lowercase()
            return when (base) {
                "int", "integer", "bigint", "smallint", "tinyint", "long" -> "int"
                "decimal", "numeric", "float", "double", "real", "money", "smallmoney" -> "float"
                "bool", "boolean" -> "bool"
                "date", "datetime", "timestamp", "timestamptz", "time" -> "datetime"
                else -> "text"
            }
        }

        /** The compiler's `writable:` glob (RecipePlacement): a `*` spans anything, the rest is literal. */
        fun matches(
            pattern: String,
            name: String,
        ): Boolean =
            pattern
                .split('*')
                .joinToString(".*") { Regex.escape(it) }
                .toRegex()
                .matches(name)
    }
}
