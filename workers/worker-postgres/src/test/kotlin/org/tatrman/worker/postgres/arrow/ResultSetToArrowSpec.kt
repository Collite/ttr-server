// SPDX-License-Identifier: Apache-2.0
package org.tatrman.worker.postgres.arrow

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.DecimalVector
import org.apache.arrow.vector.VarCharVector
import org.apache.arrow.vector.types.pojo.ArrowType
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.Types

/**
 * #155 / #83 — an unconstrained NUMERIC result column (no typmod: `a / b`, `SUM(x)`, `AVG(x)`, a UNION
 * branch with an untyped NULL) is sized from the values it carries, not from the metadata's
 * "scale 0", which means "not declared". Before: every value was rescaled to 0 decimals, HALF_UP.
 */
class ResultSetToArrowSpec :
    StringSpec({
        val allocator = RootAllocator(Long.MAX_VALUE)
        afterSpec { allocator.close() }

        "an unconstrained numeric keeps its decimals: 24.335 and a 20-digit quotient, not 24 and 0" {
            val rs =
                resultSet(
                    listOf(
                        Col(
                            "r",
                            "numeric",
                            Types.NUMERIC,
                            0,
                            0,
                            listOf(bd("24.3350000000000000"), bd("0.24418604651162790698")),
                        ),
                        Col("label", "varchar", Types.VARCHAR, 64, 0, listOf("a", "b")),
                    ),
                )
            val batch = ResultSetToArrow(allocator, 100, 1024).convert(rs).single()
            batch.root!!.use { root ->
                val type =
                    root.schema
                        .findField("r")
                        .type
                        .shouldBeInstanceOf<ArrowType.Decimal>()
                type.precision shouldBe 38
                // The quotient carries 20 decimals; 18 is the cap that keeps 20 integer digits.
                type.scale shouldBe 18
                val r = root.getVector("r") as DecimalVector
                r.getObject(0).compareTo(bd("24.335")) shouldBe 0
                r.getObject(1) shouldBe bd("0.244186046511627907")
                // The columns that are not sized come through as before.
                String((root.getVector("label") as VarCharVector).get(1)) shouldBe "b"
                root.rowCount shouldBe 2
            }
            batch.rounded shouldBe emptyMap()
        }

        "a sum of money keeps its cents and adds no zeros: scale 2 when the values carry 2" {
            val rs = resultSet(listOf(Col("total", "numeric", Types.NUMERIC, 0, 0, listOf(bd("1234.56"), bd("7.50")))))
            ResultSetToArrow(allocator, 100, 1024).convert(rs).single().root!!.use { root ->
                (root.schema.findField("total").type as ArrowType.Decimal).scale shouldBe 2
                val v = root.getVector("total") as DecimalVector
                v.getObject(0) shouldBe bd("1234.56")
                v.getObject(1) shouldBe bd("7.50")
            }
        }

        "integral values stay integral (scale 0), exactly as before" {
            val rs = resultSet(listOf(Col("n", "numeric", Types.NUMERIC, 0, 0, listOf(bd("832516610"), bd("15")))))
            ResultSetToArrow(allocator, 100, 1024).convert(rs).single().root!!.use { root ->
                (root.schema.findField("n").type as ArrowType.Decimal).scale shouldBe 0
                (root.getVector("n") as DecimalVector).getObject(0) shouldBe bd("832516610")
            }
        }

        "#83's shape: a numeric(18,6) through a UNION with an untyped NULL keeps 2.103100" {
            val rs = resultSet(listOf(Col("p", "numeric", Types.NUMERIC, 0, 0, listOf(bd("2.103100"), null))))
            ResultSetToArrow(allocator, 100, 1024).convert(rs).single().root!!.use { root ->
                (root.schema.findField("p").type as ArrowType.Decimal).scale shouldBe 6
                val v = root.getVector("p") as DecimalVector
                v.getObject(0) shouldBe bd("2.103100")
                v.isNull(1) shouldBe true
            }
        }

        "no value to size from (all NULL) → the widest scale, Decimal128(38,18)" {
            val rs = resultSet(listOf(Col("x", "numeric", Types.NUMERIC, 0, 0, listOf(null, null))))
            ResultSetToArrow(allocator, 100, 1024).convert(rs).single().root!!.use { root ->
                (root.schema.findField("x").type as ArrowType.Decimal).scale shouldBe
                    PostgresArrowTypeMapper.UNCONSTRAINED_MAX_SCALE
            }
        }

        "a large integer part narrows the scale so the value still fits 38 digits" {
            val big = bd("1234567890123456789012345.12345678901234567890") // 25 integer digits, 20 decimals
            val rs = resultSet(listOf(Col("s", "numeric", Types.NUMERIC, 0, 0, listOf(big))))
            ResultSetToArrow(allocator, 100, 1024).convert(rs).single().root!!.use { root ->
                (root.schema.findField("s").type as ArrowType.Decimal).scale shouldBe 13
                (root.getVector("s") as DecimalVector).getObject(0) shouldBe
                    bd("1234567890123456789012345.1234567890123")
            }
        }

        "a later batch with more decimals than the first is rounded to the first's scale, and says so" {
            val rs =
                resultSet(listOf(Col("r", "numeric", Types.NUMERIC, 0, 0, listOf(bd("1.5"), bd("2.5"), bd("3.125")))))
            val batches = ResultSetToArrow(allocator, 2, 1024).convert(rs).toList()
            batches.size shouldBe 2
            batches[0].rounded shouldBe emptyMap()
            batches[0].root!!.use { root -> (root.schema.findField("r").type as ArrowType.Decimal).scale shouldBe 1 }
            batches[1].root!!.use { root ->
                // One schema for the whole result: the second batch keeps the first one's scale.
                (root.schema.findField("r").type as ArrowType.Decimal).scale shouldBe 1
                (root.getVector("r") as DecimalVector).getObject(0) shouldBe bd("3.1")
            }
            batches[1].rounded shouldBe mapOf("r" to 1)
        }

        "a declared numeric(20,4) is untouched" {
            val rs = resultSet(listOf(Col("amount", "numeric", Types.NUMERIC, 20, 4, listOf(bd("123.4500")))))
            ResultSetToArrow(allocator, 100, 1024).convert(rs).single().root!!.use { root ->
                val t = root.schema.findField("amount").type as ArrowType.Decimal
                t.precision shouldBe 20
                t.scale shouldBe 4
                (root.getVector("amount") as DecimalVector).getObject(0) shouldBe bd("123.4500")
            }
        }

        "more than 38 integer digits cannot be carried — a clear error, not a driver stack trace" {
            val huge = bd("1" + "0".repeat(40))
            val rs = resultSet(listOf(Col("h", "numeric", Types.NUMERIC, 0, 0, listOf(huge))))
            val e = shouldThrow<IllegalStateException> { ResultSetToArrow(allocator, 100, 1024).convert(rs).toList() }
            e.message shouldContain "h"
            e.message shouldContain "38"
        }
    })

private fun bd(s: String) = BigDecimal(s)

private data class Col(
    val name: String,
    val typeName: String,
    val jdbcType: Int,
    val precision: Int,
    val scale: Int,
    val values: List<Any?>,
)

/** A forward-only ResultSet over [cols] (all the same length), read the way [ResultSetToArrow] reads it. */
private fun resultSet(cols: List<Col>): ResultSet {
    val meta = mockk<ResultSetMetaData>()
    every { meta.columnCount } returns cols.size
    cols.forEachIndexed { i, c ->
        val idx = i + 1
        every { meta.getColumnLabel(idx) } returns c.name
        every { meta.getColumnName(idx) } returns c.name
        every { meta.getColumnTypeName(idx) } returns c.typeName
        every { meta.getColumnType(idx) } returns c.jdbcType
        every { meta.getPrecision(idx) } returns c.precision
        every { meta.getScale(idx) } returns c.scale
        every { meta.isNullable(idx) } returns ResultSetMetaData.columnNullable
    }
    val rows = cols.first().values.size
    var cursor = -1
    val rs = mockk<ResultSet>()
    every { rs.metaData } returns meta
    every { rs.next() } answers { cursor++ < rows - 1 }
    every { rs.wasNull() } returns false
    cols.forEachIndexed { i, c ->
        val idx = i + 1
        every { rs.getBigDecimal(idx) } answers { c.values[cursor] as BigDecimal? }
        every { rs.getObject(idx) } answers { c.values[cursor] }
    }
    return rs
}
