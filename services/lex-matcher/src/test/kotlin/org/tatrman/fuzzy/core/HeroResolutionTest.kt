// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.config.LoaderSourceConfig
import org.tatrman.fuzzy.config.MetadataConfig
import org.tatrman.fuzzy.config.NlpConfig
import org.tatrman.fuzzy.config.TokenBasedConfig
import org.tatrman.fuzzy.loader.DeclaredValue
import org.tatrman.fuzzy.loader.DeclaredVocabulary
import org.tatrman.fuzzy.loader.DeclaredVocabularyEntry
import org.tatrman.fuzzy.loader.LoaderSource
import org.tatrman.fuzzy.loader.SnapshotVocabularySource

/**
 * RG-P2.S2.T8 — the hero DoD: one `BatchMatch` resolves the member product via
 * the lemma axis AND the declared branch-entity term, source-tagged. Plus the
 * B-T4 loader report + GetStatus plumbing (T7).
 */
class HeroResolutionTest :
    StringSpec({

        fun cfg() =
            AppConfig(
                serverPort = 7103,
                grpcPort = 7203,
                grpcReflectionEnabled = false,
                refreshIntervalSeconds = 0,
                tokenBasedConfig = TokenBasedConfig(),
                nlp = NlpConfig(),
                loaderSource = LoaderSourceConfig(source = "static"),
                metadata = MetadataConfig(),
            )

        // Fixture MorphoDiTa: the inflected hero tokens → their folded lemma.
        val lemmatizer =
            object : Lemmatizer {
                private val m =
                    mapOf(
                        "octavie" to "octavia",
                        "octavii" to "octavia",
                        "octavia" to "octavia",
                        "škoda" to "skoda",
                        "pobočka" to "pobocka",
                        "pobočkách" to "pobocka",
                    )

                override suspend fun lemmatize(tokens: Collection<String>): Map<String, String> =
                    tokens.associateWith { m[it.lowercase()] ?: TextNormalizer.fold(it) }
            }

        "hero: ONE BatchMatch — octavie→MEMBER product (lemma), pobočkách→VOCABULARY branch term" {
            val loader =
                object : LoaderSource {
                    override suspend fun loadNextCache() =
                        mapOf("product" to listOf(Candidate.fromValues("p-octavia", "Škoda Octavia")))
                }
            val snapshot =
                object : SnapshotVocabularySource {
                    override suspend fun fetch() =
                        DeclaredVocabulary(
                            listOf(
                                DeclaredVocabularyEntry(
                                    "er.branch",
                                    "er.branch",
                                    listOf(DeclaredValue("term-pobocka", "pobočka")),
                                ),
                            ),
                        )

                    override fun hash() = "hero-v1"
                }
            val repo = StringRepository(cfg(), loader, lemmatizer = lemmatizer, snapshotSource = snapshot)
            try {
                runBlocking {
                    repo.forceRefresh()
                    val matcher = FuzzyMatcher(repo, lemmatizer = lemmatizer)
                    val out =
                        matcher.batchMatch(
                            listOf(
                                SpanQuery("octavie", listOf("product"), 5),
                                SpanQuery("pobočkách", listOf("er.branch"), 5),
                            ),
                        )

                    out.results.size shouldBe 2
                    val octavie = out.results[0].matches.first()
                    octavie.candidateId shouldBe "p-octavia"
                    octavie.source shouldBe SourceTag.MEMBER

                    val pobocka = out.results[1].matches.first()
                    pobocka.candidateId shouldBe "term-pobocka"
                    pobocka.source shouldBe SourceTag.VOCABULARY
                    pobocka.targetRef shouldBe "er.branch"

                    out.vocabularyVersion shouldContain "hero-v1"
                }
            } finally {
                repo.close()
            }
        }

        "the loader report (RG-FUZ-001) + category statuses surface via the repository (GetStatus feed)" {
            val loader =
                object : LoaderSource {
                    override suspend fun loadNextCache() =
                        mapOf("db.dbo.customer.name" to listOf(Candidate.fromValues("1", "Pelex")))

                    override fun warnings() =
                        listOf(
                            org.tatrman.fuzzy.core.LoaderWarningInfo(
                                "RG-FUZ-001",
                                "db.dbo.composite.col",
                                "declared fuzzy column skipped (composite_pk) — not searchable",
                            ),
                        )
                }
            val repo = StringRepository(cfg(), loader)
            try {
                runBlocking {
                    repo.forceRefresh()
                    val warn = repo.loaderWarnings().single()
                    warn.code shouldBe "RG-FUZ-001"
                    warn.category shouldBe "db.dbo.composite.col"

                    val status = repo.categoryStatuses().single()
                    status.category shouldBe "db.dbo.customer.name"
                    status.source shouldBe SourceTag.MEMBER
                    status.size shouldBe 1
                }
            } finally {
                repo.close()
            }
        }
    })
