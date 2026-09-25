// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.config.LoaderSourceConfig
import org.tatrman.fuzzy.config.MetadataConfig
import org.tatrman.fuzzy.config.NlpConfig
import org.tatrman.fuzzy.config.TokenBasedConfig
import org.tatrman.fuzzy.loader.LoaderSource
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

/**
 * Regression for the category-key case mismatch (golem/resolver fuzzy bug).
 *
 * The metadata loader keys categories with the DB identifier's original case
 * (e.g. "db.dbo.QSTRED_DF.KOD_STR"), but every query path lowercases the
 * requested category. The per-column index was therefore never hit: TATRMAN
 * silently fell back to the GLOBAL index (serving NAZEV_STR names for a
 * KOD_STR query — "QT ORLAK" was unfindable), and LEVENSHTEIN returned empty.
 *
 * Asserts data flow, not call counts: a case-varied category resolves to the
 * right column's candidates, the wrong column is never leaked, and an
 * unknown category yields nothing (no global fallback).
 */
class CategoryCaseInsensitivityTest :
    StringSpec({

        val codeCategory = "db.dbo.QSTRED_DF.KOD_STR"
        val nameCategory = "db.dbo.QSTRED_DF.NAZEV_STR"

        // Loader returns UPPER-CASED category keys, as the real metadata loader does.
        val loader =
            object : LoaderSource {
                override suspend fun loadNextCache() =
                    mapOf(
                        codeCategory to
                            listOf(
                                Candidate.fromValues("273", "QT ORLAK"),
                                Candidate.fromValues("492", "QT DAN PO"),
                            ),
                        nameCategory to
                            listOf(
                                Candidate.fromValues("273", "Admin. náklady QT"),
                                Candidate.fromValues("492", "Daň z příjmů právnických osob QT"),
                            ),
                    )
            }

        val cfg =
            AppConfig(
                serverPort = 7103,
                grpcPort = 7203,
                grpcReflectionEnabled = false,
                refreshIntervalSeconds = 1,
                tokenBasedConfig = TokenBasedConfig(),
                nlp = NlpConfig(),
                loaderSource = LoaderSourceConfig(source = "metadata"),
                metadata = MetadataConfig(),
            )

        "category lookup is case-insensitive and stays scoped to the requested column" {
            val repo = StringRepository(cfg, loader, telemetry = null)
            val matcher = FuzzyMatcher(repo)
            try {
                eventually(5.seconds) { repo.isCatalogReady() shouldBe true }

                // getCandidates is case-insensitive regardless of caller casing.
                repo.getCandidates(codeCategory).map { it.id }.toSet() shouldBe setOf("273", "492")
                repo.getCandidates(codeCategory.lowercase()).map { it.id }.toSet() shouldBe setOf("273", "492")

                // The code "QT ORLAK" lives only in KOD_STR — querying that
                // column (mixed case) must return it exactly, not fall back to
                // the global index and serve NAZEV_STR names.
                val codeHit = runBlocking { matcher.match("QT ORLAK", codeCategory, AlgorithmType.TATRMAN, 5) }
                codeHit.first().candidateId shouldBe "273"
                codeHit.first().candidate shouldBe "QT ORLAK"

                // The same query against the NAME column must NOT surface the
                // code value — proves no cross-column leakage via global fallback.
                val nameHit = runBlocking { matcher.match("QT ORLAK", nameCategory, AlgorithmType.TATRMAN, 5) }
                nameHit.none { it.candidate == "QT ORLAK" } shouldBe true
            } finally {
                repo.close()
            }
        }

        "explicit unknown category returns nothing — no silent global fallback" {
            val repo = StringRepository(cfg, loader, telemetry = null)
            val matcher = FuzzyMatcher(repo)
            try {
                eventually(5.seconds) { repo.isCatalogReady() shouldBe true }

                repo.getCandidates("db.dbo.DOES_NOT_EXIST.COL").shouldBeEmpty()
                runBlocking {
                    matcher.match("QT ORLAK", "db.dbo.DOES_NOT_EXIST.COL", AlgorithmType.TATRMAN, 5)
                }.shouldBeEmpty()
            } finally {
                repo.close()
            }
        }
    })
