// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.loader

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.fuzzy.config.PostgresConfig
import org.tatrman.fuzzy.core.Candidate
import org.tatrman.fuzzy.core.SourceTag
import org.tatrman.plan.v1.QualifiedName

/**
 * RG-P2.S2.T4 — alias-table ingestion (RS-12-γ). `semantics{kind: alias_table}`
 * synonym rows compose `SELECT pk, alias FROM alias_table` (reusing the SQL
 * identifier-validation discipline) and merge into the owning member category —
 * same PK space, so an alias match resolves to the same id.
 */
class AliasTableIngestionTest :
    StringSpec({

        val pg = PostgresConfig(host = "h", port = 5432, database = "db", user = "u", pass = "p")

        fun qname(
            ns: String,
            name: String,
        ) = QualifiedName
            .newBuilder()
            .setNamespace(ns)
            .setName(name)
            .build()

        "alias rows compose SELECT + merge into the owner category (same PK, MEMBER)" {
            val decl =
                AliasTableDecl(
                    ownerCategory = "db.dbo.CUSTOMER.NAME",
                    tableQname = qname("dbo", "CUSTOMER_ALIAS"),
                    pkColumn = "CUSTOMER_ID",
                    aliasColumn = "ALIAS",
                )
            var capturedSql = ""
            val result =
                composeAliasCandidates(listOf(decl), pg) { sql ->
                    capturedSql = sql
                    listOf(Candidate.fromValues("42", "Pelexy"), Candidate.fromValues("42", "Pelex Oil"))
                }

            capturedSql shouldContain "SELECT"
            capturedSql shouldContain "CUSTOMER_ALIAS"
            capturedSql shouldContain "ALIAS"

            val merged = result.getValue("db.dbo.customer.name") // lower-cased owner key
            merged.map { it.value } shouldContainExactlyInAnyOrder listOf("Pelexy", "Pelex Oil")
            merged.forEach {
                it.id shouldBe "42" // same PK space
                it.source shouldBe SourceTag.MEMBER
            }
        }

        "an invalid identifier is skipped, not fatal" {
            val bad =
                AliasTableDecl(
                    ownerCategory = "cat",
                    tableQname = qname("dbo", "t"),
                    pkColumn = "id; DROP TABLE x",
                    aliasColumn = "alias",
                )
            val result = composeAliasCandidates(listOf(bad), pg) { error("must not fetch on a rejected identifier") }
            result.getValue("cat").shouldBeEmpty()
        }
    })
