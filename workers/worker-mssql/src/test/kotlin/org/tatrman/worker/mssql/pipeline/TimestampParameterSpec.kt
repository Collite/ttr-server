// SPDX-License-Identifier: Apache-2.0
package org.tatrman.worker.mssql.pipeline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime

/**
 * The `datetime` parameter arm of [ExecutePipeline.bindParameters], which had no test at all.
 *
 * The twin of worker-postgres's spec, and deliberately identical: both workers carried the same
 * one-line parse, so both refused a date-only value the wire format is specified to send. Fixing
 * one and not the other is how the two drift.
 */
class TimestampParameterSpec :
    StringSpec({

        "an ISO instant binds as that instant" {
            parseTimestampParam("2026-09-18T07:50:11Z") shouldBe
                Timestamp.from(Instant.parse("2026-09-18T07:50:11Z"))
        }

        "a date-ONLY value binds as LOCAL midnight — the form a declared `date` slot arrives in" {
            parseTimestampParam("2026-09-18") shouldBe
                Timestamp.valueOf(LocalDate.parse("2026-09-18").atStartOfDay())
        }

        "a date-only value is NOT shifted through UTC" {
            parseTimestampParam("2026-09-18").toLocalDateTime().toLocalDate() shouldBe
                LocalDate.parse("2026-09-18")
        }

        "a local date-time binds without a zone" {
            parseTimestampParam("2026-09-18T00:00:00") shouldBe
                Timestamp.valueOf(LocalDateTime.parse("2026-09-18T00:00:00"))
        }

        "an offset date-time binds at its own offset" {
            parseTimestampParam("2026-09-18T00:00:00+02:00") shouldBe
                Timestamp.from(OffsetDateTime.parse("2026-09-18T00:00:00+02:00").toInstant())
        }

        "a value that is no timestamp at all is refused, and the message names it" {
            val thrown = shouldThrow<IllegalArgumentException> { parseTimestampParam("not-a-date") }
            thrown.message!! shouldBe
                "cannot read 'not-a-date' as a timestamp parameter — expected an ISO instant, " +
                "offset date-time, local date-time, or date"
        }
    })
