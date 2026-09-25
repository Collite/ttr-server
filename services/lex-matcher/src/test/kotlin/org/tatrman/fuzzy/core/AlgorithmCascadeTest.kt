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
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

/**
 * Cascade semantics for [FuzzyMatcher.matchCascade]: precision-first,
 * recall-fallback. Asserts which algorithm wins and that the returned set is
 * the winner's (never a cross-algorithm merge).
 */
class AlgorithmCascadeTest :
    StringSpec({

        val category = "db.dbo.QSTRED_DF.KOD_STR"
        val loader =
            object : LoaderSource {
                override suspend fun loadNextCache() =
                    mapOf(
                        category to
                            listOf(
                                Candidate.fromValues("1", "QT ORLAK"),
                                Candidate.fromValues("2", "Kancelář AD"),
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

        fun withMatcher(block: suspend (FuzzyMatcher) -> Unit) {
            val repo = StringRepository(cfg, loader, telemetry = null)
            try {
                runBlocking {
                    eventually(5.seconds) { repo.isCatalogReady() shouldBe true }
                    block(FuzzyMatcher(repo))
                }
            } finally {
                repo.close()
            }
        }

        "exact code clears the strict bar — Levenshtein wins, cascade short-circuits" {
            withMatcher { matcher ->
                val steps =
                    listOf(
                        CascadeStep(AlgorithmType.LEVENSHTEIN, 0.98),
                        CascadeStep(AlgorithmType.TATRMAN, 0.50),
                    )
                val outcome = matcher.matchCascade("QT ORLAK", category, steps, 5)
                outcome.matchedAlgorithm shouldBe AlgorithmType.LEVENSHTEIN
                outcome.matches.first().candidateId shouldBe "1"
                outcome.matches.first().candidate shouldBe "QT ORLAK"
            }
        }

        "below the strict bar — cascade falls through to TATRMAN" {
            withMatcher { matcher ->
                val steps =
                    listOf(
                        CascadeStep(AlgorithmType.LEVENSHTEIN, 0.98),
                        CascadeStep(AlgorithmType.TATRMAN, 0.50),
                    )
                // Extra token tanks whole-string Levenshtein but TATRMAN's token
                // overlap with "QT ORLAK" stays strong.
                val outcome = matcher.matchCascade("qt orlak kancelar", category, steps, 5)
                outcome.matchedAlgorithm shouldBe AlgorithmType.TATRMAN
                outcome.matches.shouldNotBeEmpty()
            }
        }

        "no step qualifies — best-effort results from the last (most-recall) step" {
            withMatcher { matcher ->
                val steps =
                    listOf(
                        CascadeStep(AlgorithmType.LEVENSHTEIN, 0.99),
                        CascadeStep(AlgorithmType.TATRMAN, 0.999),
                    )
                val outcome = matcher.matchCascade("df xyz", category, steps, 5)
                outcome.matchedAlgorithm shouldBe AlgorithmType.TATRMAN
                outcome.matches.shouldNotBeEmpty()
            }
        }
    })
