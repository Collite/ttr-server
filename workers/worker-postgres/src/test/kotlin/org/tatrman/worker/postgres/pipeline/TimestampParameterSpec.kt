// SPDX-License-Identifier: Apache-2.0
package org.tatrman.worker.postgres.pipeline

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
 * The wire format carries every temporal slot in `datetime_value` — there is no `date_value` — so
 * what arrives for a slot a model declares `date` is a date-ONLY ISO string. Binding one used to
 * fail the whole statement.
 */
class TimestampParameterSpec :
    StringSpec({

        "an ISO instant binds as that instant" {
            parseTimestampParam("2026-09-18T07:50:11Z") shouldBe
                Timestamp.from(Instant.parse("2026-09-18T07:50:11Z"))
        }

        // ⛔ The case that failed live: a `date` slot, a valid ISO date, refused with
        // "Text '2026-09-18' could not be parsed at index 10" — one past the end of the string.
        "a date-ONLY value binds as LOCAL midnight — the form a declared `date` slot arrives in" {
            parseTimestampParam("2026-09-18") shouldBe
                Timestamp.valueOf(LocalDate.parse("2026-09-18").atStartOfDay())
        }

        // Local midnight and not UTC midnight: the value is compared against a `date` column, and
        // going through UTC moves the day for any negative offset.
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
