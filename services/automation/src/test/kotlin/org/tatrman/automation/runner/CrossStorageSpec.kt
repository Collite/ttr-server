// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.automation.bundle.ManifestColumn
import java.math.BigDecimal
import java.time.LocalDate

class CrossStorageSpec :
    StringSpec({
        "typed part tables preserve empty and nullable columns and join text and integer keys" {
            DuckDbCombiner().use { db ->
                db.load(
                    "erp",
                    Table(
                        listOf(ManifestColumn("id", "int"), ManifestColumn("amount", "decimal")),
                        listOf(listOf(12L, BigDecimal("1234.50")), listOf(13L, null)),
                    ),
                )
                db.load(
                    "ledger",
                    Table(
                        listOf(ManifestColumn("key", "text"), ManifestColumn("due", "date")),
                        listOf(listOf("12", LocalDate.parse("2026-10-22"))),
                    ),
                )
                db.load("empty", Table(listOf(ManifestColumn("id", "int")), emptyList()))
                val result =
                    db.query(
                        "SELECT e.id, e.amount, l.due FROM erp e JOIN ledger l ON CAST(e.id AS VARCHAR) = l.key WHERE e.amount > :floor",
                        mapOf("floor" to BoundValue("decimal", BigDecimal("1000"))),
                    )
                result.rows.single()[0] shouldBe 12L
                (result.rows.single()[1] as BigDecimal).compareTo(BigDecimal("1234.50")) shouldBe 0
                result.rows.single()[2] shouldBe LocalDate.parse("2026-10-22")
                db.query("SELECT COUNT(*) AS n FROM empty", emptyMap()).rows.single()[0] shouldBe 0L
                db
                    .query(
                        "SELECT amount IS NULL AS missing FROM erp WHERE id = 13",
                        emptyMap(),
                    ).rows
                    .single()[0] shouldBe
                    true
            }
        }
        "Arrow and DuckDB preserve dates, zoned timestamps and exact integer aggregates" {
            val input =
                Table(
                    listOf(
                        ManifestColumn("date", "date"),
                        ManifestColumn("at", "timestamptz"),
                        ManifestColumn("n", "decimal"),
                    ),
                    listOf(
                        listOf(
                            LocalDate.parse("2026-10-22"),
                            java.time.OffsetDateTime.parse("2026-10-22T08:00:00Z"),
                            BigDecimal("12345678901234567890.125"),
                        ),
                    ),
                )
            val decoded = ArrowTables.decode(listOf(ArrowTables.encode(input)))
            decoded.rows shouldBe input.rows
            DuckDbCombiner().use { db ->
                db.load("typed", decoded)
                val roundTrip = db.query("SELECT * FROM typed", emptyMap())
                roundTrip.rows shouldBe input.rows
                val instant = java.time.Instant.parse("2026-10-22T08:00:00Z")
                val bound =
                    db.query(
                        "SELECT CAST(:at AS TIMESTAMP) AS at",
                        mapOf(
                            "at" to BoundValue("datetime", instant),
                        ),
                    )
                bound.rows.single().single() shouldBe java.time.LocalDateTime.parse("2026-10-22T08:00:00")
            }
        }

        "integer SUM keeps its 128-bit numeric result across Arrow and another combine" {
            DuckDbCombiner().use { db ->
                val aggregate =
                    db.query(
                        "SELECT SUM(x) AS n FROM (VALUES (9223372036854775807::BIGINT), (2::BIGINT)) t(x)",
                        emptyMap(),
                    )
                (aggregate.rows.single().single() as BigDecimal).compareTo(BigDecimal("9223372036854775809")) shouldBe 0
                val decoded = ArrowTables.decode(listOf(ArrowTables.encode(aggregate)))
                db.load("sums", decoded)
                db
                    .query("SELECT n > 9 AS numeric_order FROM sums", emptyMap())
                    .rows
                    .single()
                    .single() shouldBe true
            }
        }

        "an undeclared combine binding refuses instead of substituting text" {
            DuckDbCombiner().use { db ->
                shouldThrow<IllegalArgumentException> { db.query("SELECT :missing", emptyMap()) }
            }
        }
        "Arrow round trip retains empty schemas, nulls, decimal precision, dates and timestamps" {
            val input =
                Table(
                    listOf(
                        ManifestColumn("id", "int"),
                        ManifestColumn("amount", "decimal"),
                        ManifestColumn("label", "text"),
                    ),
                    listOf(listOf(12L, BigDecimal("12345678901234567890.125"), "x"), listOf(13L, null, null)),
                )
            val decoded = ArrowTables.decode(listOf(ArrowTables.encode(input)), input.columns)
            decoded.rows shouldBe input.rows
            ArrowTables
                .decode(
                    listOf(ArrowTables.encode(input.copy(rows = emptyList()))),
                    input.columns,
                ).columns shouldBe
                input.columns
        }
    })
