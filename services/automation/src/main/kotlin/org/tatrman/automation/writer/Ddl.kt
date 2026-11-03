// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.writer

import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.Bundle
import org.tatrman.automation.apply.ObligationStates
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttrp.resolve.ColumnType
import javax.sql.DataSource

/**
 * Contracts §3: transactional, additive DDL on the service connection, before a served bundle swaps.
 * Schema names come from the deployment's explicit world → schema map, never from a default search_path.
 */
class Ddl(
    private val dataSource: DataSource,
    private val schemas: (Bundle) -> List<String>,
    private val doorRole: String? = null,
) {
    fun refresh(bundle: Bundle) {
        val er = bundle.model.schemas["er"] as? ErSchema ?: invalid("missing ER catalog")
        val wanted =
            bundle.recipes.values.flatMap { loaded ->
                loaded.recipe.verdictSchema
                    ?.let { listOfNotNull(it.entity, it.detail, it.current) }
                    .orEmpty()
            } + listOf("obligation", "state_history")
        val entities =
            wanted.distinct().map { name ->
                er.entities.values.singleOrNull { it.qname.name == name.removePrefix("er.entity.") }
                    ?: invalid("missing typed catalog entity $name")
            }
        val verdicts =
            bundle.recipes.values
                .mapNotNull {
                    it.recipe.verdictSchema?.entity?.removePrefix(
                        "er.entity.",
                    )
                }.toSet()
        val names = schemas(bundle).distinct()
        if (names.isEmpty()) invalid("automation connection has no world schema")
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { sql ->
                    // All served refresh DDL on this database serializes.
                    sql.execute("SELECT pg_advisory_xact_lock(1096107092)")
                    for (schema in names) {
                        sql.execute("CREATE SCHEMA IF NOT EXISTS ${quote(schema)}")
                        sql.execute("SET LOCAL search_path = ${quote(schema)}, pg_catalog")
                        doorRole?.takeIf { it.isNotBlank() }?.let { role ->
                            sql.execute("GRANT USAGE ON SCHEMA ${quote(schema)} TO ${quote(role)}")
                        }
                        for (entity in entities) {
                            table(connection, schema, entity, entity.qname.name in verdicts)
                            doorRole?.takeIf { it.isNotBlank() }?.let { role ->
                                sql.execute(
                                    "GRANT SELECT, INSERT, UPDATE, DELETE ON ${quote(
                                        schema,
                                    )}.${quote(entity.qname.name)} TO ${quote(role)}",
                                )
                            }
                        }
                    }
                }
                connection.commit()
            } catch (e: Exception) {
                try {
                    connection.rollback()
                } catch (rollback: Exception) {
                    e.addSuppressed(rollback)
                }
                throw e
            }
        }
    }

    companion object {
        fun configured(
            dataSource: DataSource,
            schemas: Map<String, String>,
            doorRole: String?,
        ): Ddl =
            Ddl(dataSource, { bundle ->
                bundle.worlds.keys.sorted().map { world ->
                    schemas[world] ?: throw AutomationException(
                        ErrorCode.BUNDLE_INVALID,
                        "automation_pg schema is not mapped for world $world",
                    )
                }
            }, doorRole)
    }

    private fun table(
        connection: java.sql.Connection,
        schema: String,
        entity: Entity,
        immutable: Boolean,
    ) {
        val table = entity.qname.name
        val columns =
            entity.attributes.associate { attribute ->
                val name = attribute.qname.name.substringAfterLast('.')
                name to Column(type(attribute), attribute.nullable && !attribute.isKey)
            }
        if (columns.isEmpty()) invalid("empty catalog entity $table")
        val keys = entity.attributes.filter { it.isKey }.map { quote(it.qname.name.substringAfterLast('.')) }
        val qualified = "${quote(schema)}.${quote(table)}"
        connection.createStatement().use { sql ->
            val definitions =
                columns.map { (name, column) ->
                    "${quote(name)} ${column.type}${if (column.nullable) "" else " NOT NULL"}"
                } +
                    if (keys.isEmpty()) emptyList() else listOf("PRIMARY KEY (${keys.joinToString()})")
            sql.execute("CREATE TABLE IF NOT EXISTS $qualified (${definitions.joinToString()})")
            val actualKeys =
                connection.metaData.getPrimaryKeys(null, schema, table).use { rows ->
                    buildList { while (rows.next()) add(rows.getInt("KEY_SEQ") to rows.getString("COLUMN_NAME")) }
                        .sortedBy { it.first }
                        .map { quote(it.second) }
                }
            if (actualKeys != keys) invalid("destructive primary key change $schema.$table: ${keys.joinToString()}")
            val existing =
                connection
                    .prepareStatement(
                        "SELECT a.attname, format_type(a.atttypid,a.atttypmod), a.attnotnull FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname=? AND c.relname=? AND a.attnum>0 AND NOT a.attisdropped",
                    ).use { statement ->
                        statement.setString(1, schema)
                        statement.setString(2, table)
                        statement.executeQuery().use { rows ->
                            buildMap {
                                while (rows.next()) {
                                    put(
                                        rows.getString(1),
                                        Column(rows.getString(2), !rows.getBoolean(3)),
                                    )
                                }
                            }
                        }
                    }
            for ((name, actual) in existing) {
                val expected = columns[name] ?: invalid("destructive removal $schema.$table.$name")
                if (expected !=
                    actual
                ) {
                    invalid("destructive column change $schema.$table.$name (${actual.type} -> ${expected.type})")
                }
            }
            for ((name, column) in columns - existing.keys) {
                if (!column.nullable &&
                    sql.executeQuery("SELECT EXISTS (SELECT 1 FROM $qualified)").use {
                        it.next()
                        it.getBoolean(1)
                    }
                ) {
                    invalid("non-null column added to populated table $schema.$table.$name")
                }
                sql.execute(
                    "ALTER TABLE $qualified ADD COLUMN ${quote(
                        name,
                    )} ${column.type}${if (column.nullable) "" else " NOT NULL"}",
                )
            }
            if (immutable) {
                sql.execute(
                    "CREATE OR REPLACE FUNCTION ${quote(schema)}.automation_verdict_immutable() " +
                        "RETURNS trigger LANGUAGE plpgsql AS " +
                        "'BEGIN RAISE EXCEPTION ''verdict rows are immutable''; END'",
                )
                val exists =
                    connection
                        .prepareStatement(
                            "SELECT 1 FROM pg_trigger WHERE tgrelid=?::regclass AND tgname='automation_verdict_immutable'",
                        ).use { s ->
                            s.setString(1, qualified)
                            s.executeQuery().use { it.next() }
                        }
                if (!exists) {
                    sql.execute(
                        "CREATE TRIGGER automation_verdict_immutable BEFORE UPDATE ON $qualified " +
                            "FOR EACH ROW EXECUTE FUNCTION ${quote(schema)}.automation_verdict_immutable()",
                    )
                }
            }
            if (table ==
                "obligation"
            ) {
                val active = ObligationStates.active.joinToString { "'$it'" }
                sql.execute(
                    "CREATE UNIQUE INDEX IF NOT EXISTS obligation_one_active ON $qualified " +
                        "(entity, key, kind) WHERE state IN ($active)",
                )
            }
        }
    }

    private data class Column(
        val type: String,
        val nullable: Boolean,
    )

    private fun type(attribute: org.tatrman.ttr.metadata.model.Attribute): String {
        val raw = attribute.type
        val parsed =
            if (attribute.length != null || attribute.precision != null) {
                ColumnType.of(raw, attribute.length, attribute.precision)
            } else {
                ColumnType.parse(raw)
            }
        if (parsed == null) invalid("unsupported catalog type $raw")
        return when (parsed.kind) {
            ColumnType.Kind.INT -> "integer"
            ColumnType.Kind.BIGINT -> "bigint"
            ColumnType.Kind.SMALLINT, ColumnType.Kind.TINYINT -> "smallint"
            ColumnType.Kind.TEXT -> parsed.length?.let { "character varying($it)" } ?: "text"
            ColumnType.Kind.BOOL -> "boolean"
            ColumnType.Kind.DATE -> "date"
            ColumnType.Kind.TIME -> "time without time zone"
            ColumnType.Kind.DATETIME, ColumnType.Kind.TIMESTAMP -> "timestamp without time zone"
            ColumnType.Kind.DECIMAL ->
                if (attribute.length != null ||
                    '(' in raw ||
                    raw.lowercase() in setOf("money", "smallmoney")
                ) {
                    "numeric(${parsed.precision},${parsed.scale})"
                } else {
                    "numeric"
                }
            ColumnType.Kind.FLOAT -> "double precision"
            ColumnType.Kind.REAL -> "real"
            else -> invalid("unsupported catalog type $raw")
        }
    }

    private fun quote(name: String): String {
        if (name.isBlank() ||
            '\u0000' in name ||
            name.toByteArray(Charsets.UTF_8).size > 63
        ) {
            invalid("invalid database identifier")
        }
        return "\"${name.replace("\"", "\"\"")}\""
    }

    private fun invalid(message: String): Nothing = throw AutomationException(ErrorCode.BUNDLE_INVALID, message)
}
