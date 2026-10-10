// SPDX-License-Identifier: Apache-2.0
package org.tatrman.worker.postgres.arrow

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.arrow.vector.types.DateUnit
import org.apache.arrow.vector.types.FloatingPointPrecision
import org.apache.arrow.vector.types.TimeUnit
import org.apache.arrow.vector.types.Types
import org.apache.arrow.vector.types.pojo.ArrowType
import java.sql.JDBCType

class PostgresArrowTypeMapperSpec :
    StringSpec({
        // ---- v1 must-pass set (the five Midas curated queries read only these) ----------------

        "int2/smallint maps to INT16" {
            listOf("int2", "smallint").forEach { name ->
                PostgresArrowTypeMapper.mapType(name, JDBCType.SMALLINT, 0, 0).first shouldBe
                    Types.MinorType.SMALLINT.type
            }
        }
        "int4/integer maps to INT32" {
            listOf("int4", "integer").forEach { name ->
                PostgresArrowTypeMapper.mapType(name, JDBCType.INTEGER, 0, 0).first shouldBe
                    Types.MinorType.INT.type
            }
        }
        "int8/bigint maps to INT64" {
            listOf("int8", "bigint").forEach { name ->
                PostgresArrowTypeMapper.mapType(name, JDBCType.BIGINT, 0, 0).first shouldBe
                    Types.MinorType.BIGINT.type
            }
        }
        "bool/boolean maps to BOOL" {
            listOf("bool", "boolean").forEach { name ->
                PostgresArrowTypeMapper.mapType(name, JDBCType.BIT, 0, 0).first shouldBe
                    Types.MinorType.BIT.type
            }
        }
        "numeric preserves precision and scale" {
            val (t, _) = PostgresArrowTypeMapper.mapType("numeric", JDBCType.NUMERIC, 12, 3)
            val d = t.shouldBeInstanceOf<ArrowType.Decimal>()
            d.precision shouldBe 12
            d.scale shouldBe 3
            d.bitWidth shouldBe 128
        }
        // The precision boundary that matters: Midas money columns are NUMERIC(20,4).
        "Midas NUMERIC(20,4) maps to Decimal128(20,4)" {
            val (t, _) = PostgresArrowTypeMapper.mapType("numeric", JDBCType.NUMERIC, 20, 4)
            val d = t.shouldBeInstanceOf<ArrowType.Decimal>()
            d.precision shouldBe 20
            d.scale shouldBe 4
            d.bitWidth shouldBe 128
        }
        // #155 / #83 — a numeric with no typmod (`a / b`, `SUM(x)`, a UNION with an untyped NULL)
        // reports precision 0 and scale 0. Scale 0 there means "not declared", not "integer": the
        // metadata-only type is the widest the batch can narrow from, never Decimal128(38,0).
        "unconstrained numeric (precision 0) maps to Decimal128(38,18), not (38,0)" {
            val (t, _) = PostgresArrowTypeMapper.mapType("numeric", JDBCType.NUMERIC, 0, 0)
            val d = t.shouldBeInstanceOf<ArrowType.Decimal>()
            d.precision shouldBe 38
            d.scale shouldBe PostgresArrowTypeMapper.UNCONSTRAINED_MAX_SCALE
        }
        "unconstrained numeric: precision 0 and the old driver's 131089 both count; a declared one does not" {
            PostgresArrowTypeMapper.isUnconstrainedNumeric("numeric", JDBCType.NUMERIC, 0) shouldBe true
            PostgresArrowTypeMapper.isUnconstrainedNumeric("numeric", JDBCType.NUMERIC, 131089) shouldBe true
            PostgresArrowTypeMapper.isUnconstrainedNumeric("decimal", JDBCType.DECIMAL, 0) shouldBe true
            PostgresArrowTypeMapper.isUnconstrainedNumeric("numeric", JDBCType.NUMERIC, 18) shouldBe false
            PostgresArrowTypeMapper.isUnconstrainedNumeric("numeric", JDBCType.NUMERIC, 1000) shouldBe false
            PostgresArrowTypeMapper.isUnconstrainedNumeric("int8", JDBCType.BIGINT, 0) shouldBe false
            PostgresArrowTypeMapper.isUnconstrainedNumeric("money", JDBCType.DOUBLE, 0) shouldBe false
        }
        "a declared numeric wider than Decimal128 keeps its declared scale (unchanged)" {
            val (t, _) = PostgresArrowTypeMapper.mapType("numeric", JDBCType.NUMERIC, 50, 10)
            val d = t.shouldBeInstanceOf<ArrowType.Decimal>()
            d.precision shouldBe 38
            d.scale shouldBe 10
        }
        "varchar/text/bpchar/name map to VARCHAR (UTF8)" {
            listOf("varchar", "text", "bpchar", "char", "name").forEach { name ->
                PostgresArrowTypeMapper.mapType(name, JDBCType.VARCHAR, 50, 0).first shouldBe
                    Types.MinorType.VARCHAR.type
            }
        }
        "date maps to DATE32(DAY)" {
            val (t, _) = PostgresArrowTypeMapper.mapType("date", JDBCType.DATE, 0, 0)
            t.shouldBeInstanceOf<ArrowType.Date>().unit shouldBe DateUnit.DAY
        }
        "timestamptz maps to TIMESTAMP(NANO, UTC) with original_type metadata" {
            val (t, m) = PostgresArrowTypeMapper.mapType("timestamptz", JDBCType.TIMESTAMP_WITH_TIMEZONE, 0, 0)
            val ts = t.shouldBeInstanceOf<ArrowType.Timestamp>()
            ts.unit shouldBe TimeUnit.NANOSECOND
            ts.timezone shouldBe "UTC"
            m?.get(PostgresArrowTypeMapper.originalTypeKey()) shouldBe "timestamptz"
        }
        "uuid maps to VARCHAR with original_type metadata (Midas keys + tenant_id are UUID)" {
            val (t, m) = PostgresArrowTypeMapper.mapType("uuid", JDBCType.OTHER, 0, 0)
            t shouldBe Types.MinorType.VARCHAR.type
            m?.get(PostgresArrowTypeMapper.originalTypeKey()) shouldBe "uuid"
        }

        // ---- defensive set (none appear in the v1 Midas query catalog) -------------------------

        "real/float4 maps to FLOAT32" {
            listOf("real", "float4").forEach { name ->
                val fp =
                    PostgresArrowTypeMapper
                        .mapType(name, JDBCType.REAL, 0, 0)
                        .first
                        .shouldBeInstanceOf<ArrowType.FloatingPoint>()
                fp.precision shouldBe FloatingPointPrecision.SINGLE
            }
        }
        "double precision/float8 maps to FLOAT64" {
            listOf("float8", "double precision").forEach { name ->
                val fp =
                    PostgresArrowTypeMapper
                        .mapType(name, JDBCType.DOUBLE, 0, 0)
                        .first
                        .shouldBeInstanceOf<ArrowType.FloatingPoint>()
                fp.precision shouldBe FloatingPointPrecision.DOUBLE
            }
        }
        "money maps to Decimal128(19,2)" {
            val d =
                PostgresArrowTypeMapper
                    .mapType("money", JDBCType.DECIMAL, 0, 0)
                    .first
                    .shouldBeInstanceOf<ArrowType.Decimal>()
            d.precision shouldBe 19
            d.scale shouldBe 2
        }
        "time maps to TIME(NANO, 64)" {
            val ti =
                PostgresArrowTypeMapper
                    .mapType("time", JDBCType.TIME, 0, 0)
                    .first
                    .shouldBeInstanceOf<ArrowType.Time>()
            ti.unit shouldBe TimeUnit.NANOSECOND
            ti.bitWidth shouldBe 64
        }
        "timestamp without tz maps to TIMESTAMP(NANO, none)" {
            val ts =
                PostgresArrowTypeMapper
                    .mapType("timestamp", JDBCType.TIMESTAMP, 0, 0)
                    .first
                    .shouldBeInstanceOf<ArrowType.Timestamp>()
            ts.unit shouldBe TimeUnit.NANOSECOND
            ts.timezone shouldBe null
        }
        "bytea maps to VARBINARY with NO metadata (faithful binary, not a fallback)" {
            val (t, m) = PostgresArrowTypeMapper.mapType("bytea", JDBCType.BINARY, 0, 0)
            t shouldBe Types.MinorType.VARBINARY.type
            m shouldBe null
        }
        "json/jsonb map to VARCHAR with original_type metadata" {
            listOf("json", "jsonb").forEach { name ->
                val (t, m) = PostgresArrowTypeMapper.mapType(name, JDBCType.OTHER, 0, 0)
                t shouldBe Types.MinorType.VARCHAR.type
                m?.get(PostgresArrowTypeMapper.originalTypeKey()) shouldBe name
            }
        }
        "ranges/inet/cidr/tsvector map to opaque VARBINARY with original_type metadata" {
            listOf("numrange", "tstzrange", "inet", "cidr", "tsvector").forEach { name ->
                val (t, m) = PostgresArrowTypeMapper.mapType(name, JDBCType.OTHER, 0, 0)
                t shouldBe Types.MinorType.VARBINARY.type
                m?.get(PostgresArrowTypeMapper.originalTypeKey()) shouldBe name
            }
        }
        "array columns (leading-underscore native name) map to opaque VARBINARY with metadata" {
            val (t, m) = PostgresArrowTypeMapper.mapType("_int4", JDBCType.ARRAY, 0, 0)
            t shouldBe Types.MinorType.VARBINARY.type
            m?.get(PostgresArrowTypeMapper.originalTypeKey()) shouldBe "_int4"
        }
        "unknown native type falls back via JDBC code" {
            val (t, _) = PostgresArrowTypeMapper.mapType("not-a-real-type", JDBCType.INTEGER, 0, 0)
            t shouldBe Types.MinorType.INT.type
        }
        "unknown native type + unknown JDBC code is opaque VARBINARY with jdbc-name metadata" {
            val (t, m) = PostgresArrowTypeMapper.mapType("not-a-real-type", JDBCType.OTHER, 0, 0)
            t shouldBe Types.MinorType.VARBINARY.type
            m?.get(PostgresArrowTypeMapper.originalTypeKey()) shouldBe "other"
        }

        "unsupportedBinaryFallbacks lists only opaque-binary columns (not bytea, not uuid/json)" {
            val fields =
                listOf(
                    field("id", JDBCType.INTEGER, "int4"),
                    field("tags", JDBCType.ARRAY, "_text"),
                    field("net", JDBCType.OTHER, "inet"),
                    field("raw", JDBCType.BINARY, "bytea"), // faithful binary, NOT a fallback
                    field("key", JDBCType.OTHER, "uuid"), // VARCHAR with metadata, NOT binary
                    field("doc", JDBCType.OTHER, "jsonb"), // VARCHAR with metadata, NOT binary
                )
            val schema =
                org.apache.arrow.vector.types.pojo
                    .Schema(fields)
            val fallbacks = PostgresArrowTypeMapper.unsupportedBinaryFallbacks(schema)
            fallbacks.map { it.first }.toSet() shouldBe setOf("tags", "net")
            fallbacks.toMap()["tags"] shouldBe "_text"
            fallbacks.toMap()["net"] shouldBe "inet"
        }
    })

private fun field(
    name: String,
    jdbc: JDBCType,
    nativeType: String,
): org.apache.arrow.vector.types.pojo.Field {
    val (t, m) = PostgresArrowTypeMapper.mapType(nativeType, jdbc, 0, 0)
    val fieldType =
        org.apache.arrow.vector.types.pojo
            .FieldType(true, t, null, m)
    return org.apache.arrow.vector.types.pojo
        .Field(name, fieldType, emptyList())
}
