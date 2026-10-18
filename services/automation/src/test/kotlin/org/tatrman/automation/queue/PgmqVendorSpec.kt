// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.queue

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.security.MessageDigest

/**
 * D-130: pgmq is vendored UNCHANGED as a Flyway migration. The header records the upstream file's sha256; the
 * body below the header must still hash to it, so a hand edit — or a half-applied upgrade — fails here.
 */
class PgmqVendorSpec :
    StringSpec({
        val migrations = File("src/main/resources/db/migration")
        val vendored = migrations.listFiles { f -> f.name.startsWith("V2__pgmq_") }!!.single()

        "the body below the header hashes to the sha256 the header records" {
            val text = vendored.readText()
            val recorded =
                Regex("""--\s+sha256:\s+([0-9a-f]{64})""")
                    .find(text)
                    ?.groupValues
                    ?.get(1)
                    .shouldNotBeNull()
            val body = text.substring(text.indexOf("\n\n") + 2)
            MessageDigest
                .getInstance("SHA-256")
                .digest(body.toByteArray())
                .joinToString("") { "%02x".format(it) } shouldBe recorded
        }

        "the version in the file name is the one the header names" {
            val version =
                vendored.name
                    .removePrefix("V2__pgmq_")
                    .removeSuffix(".sql")
                    .replace('_', '.')
            vendored.readText() shouldContain "pgmq $version — the SQL-only install"
            vendored.readText() shouldContain "/blob/v$version/"
        }

        "the license travels with it" {
            File("src/main/resources/db/LICENSE-pgmq").readText() shouldContain "The PostgreSQL License"
        }

        "the migrations are the ledger, pgmq, the queues — in that order" {
            migrations.list()!!.sorted() shouldContainExactly listOf("V1__ledger.sql", vendored.name, "V3__queues.sql")
        }
    })
