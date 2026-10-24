// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.automation.bundle.ManifestColumn

class CandidateScopeSpec :
    StringSpec({
        "key selection wraps a statement and binds values; embedded quotes and semicolons remain data" {
            val source = "SELECT 1 AS id, 'text;' AS note;\n"
            val attack = "a' OR true --"
            val scoped =
                CandidateScope.wrap(
                    source,
                    listOf(ManifestColumn("id", "text")),
                    listOf(mapOf("id" to attack)),
                )
            scoped.sql shouldBe
                "SELECT * FROM (SELECT 1 AS id, 'text;' AS note) AS _au_candidates WHERE \"id\" IN (:_au_port_0_0)"
            scoped.bindings shouldBe mapOf("_au_port_0_0" to BoundValue("text", attack))
        }
        "empty keys mean false; batch uses the original statement; composite keys use typed scalar conjunctions" {
            val columns = listOf(ManifestColumn("id", "int"), ManifestColumn("region", "text"))
            CandidateScope.wrap("SELECT 1", columns, null).sql shouldBe "SELECT 1"
            CandidateScope.wrap("SELECT 1", columns, emptyList()).sql shouldBe
                "SELECT * FROM (SELECT 1) AS _au_candidates WHERE FALSE"
            val scoped =
                CandidateScope.wrap(
                    "SELECT 1",
                    columns,
                    listOf(
                        mapOf("id" to 1L, "region" to "cz"),
                        mapOf(
                            "id" to 2L,
                            "region" to "us",
                        ),
                    ),
                )
            scoped.sql shouldBe
                "SELECT * FROM (SELECT 1) AS _au_candidates WHERE " +
                "(\"id\" = :_au_port_0_0 AND \"region\" = :_au_port_0_1) OR " +
                "(\"id\" = :_au_port_1_0 AND \"region\" = :_au_port_1_1)"
            DuckDbCombiner().use { db ->
                val actual =
                    CandidateScope.wrap(
                        "SELECT * FROM (VALUES (1, 'cz'), (1, 'us'), (2, 'us')) AS x(id, region)",
                        columns,
                        listOf(
                            mapOf(
                                "id" to 1L,
                                "region" to "cz",
                            ),
                            mapOf("id" to 2L, "region" to "us"),
                        ),
                    )
                db.query(actual.sql, actual.bindings).rows shouldBe listOf(listOf(1L, "cz"), listOf(2L, "us"))
            }
        }
    })
