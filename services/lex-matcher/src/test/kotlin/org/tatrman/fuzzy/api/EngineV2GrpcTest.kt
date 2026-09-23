// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.api

import com.typesafe.config.ConfigFactory
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.config.ConfigLoader
import org.tatrman.fuzzy.config.LoaderSourceConfig
import org.tatrman.fuzzy.config.MetadataConfig
import org.tatrman.fuzzy.config.NlpConfig
import org.tatrman.fuzzy.config.TokenBasedConfig
import org.tatrman.fuzzy.core.Candidate
import org.tatrman.fuzzy.core.FuzzyMatcher
import org.tatrman.fuzzy.core.MatchVersion
import org.tatrman.fuzzy.core.RetrievalMode
import org.tatrman.fuzzy.core.StringRepository
import org.tatrman.fuzzy.loader.LoaderSource
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.FuzzyServiceGrpcKt
import org.tatrman.fuzzy.v1.FuzzyStatusRequest
import org.tatrman.fuzzy.v1.SpanQuery

/**
 * LP-P0·S2 T5/T6 — `fuzzy.match.version` end to end: the selector, the startup refusal, the
 * status echo, and the v2 provenance on the gRPC wire (absent on a v1 row).
 */
class EngineV2GrpcTest :
    StringSpec({

        fun cfg() =
            AppConfig(
                serverPort = 7104,
                grpcPort = 7204,
                grpcReflectionEnabled = false,
                refreshIntervalSeconds = 0,
                tokenBasedConfig = TokenBasedConfig(),
                nlp = NlpConfig(),
                loaderSource = LoaderSourceConfig(source = "static"),
                metadata = MetadataConfig(),
            )

        val loader =
            object : LoaderSource {
                override suspend fun loadNextCache() =
                    mapOf(
                        "customer" to
                            listOf(
                                Candidate.fromValues("c-marvy", "Marvy Oil, s.r.o."),
                                Candidate.fromValues("c-marvy-sk", "Marvy Oil Slovakia, s.r.o."),
                                Candidate.fromValues("c-benzina", "Benzina s.r.o."),
                            ),
                    )
            }

        suspend fun <T> withStub(
            matcher: (StringRepository) -> FuzzyMatcher,
            block: suspend (FuzzyServiceGrpcKt.FuzzyServiceCoroutineStub) -> T,
        ): T {
            val repo = StringRepository(cfg(), loader)
            repo.forceRefresh()
            val service = GrpcService(matcher(repo), repo)
            val name = "fuzzy-v2-${System.identityHashCode(service)}"
            val server =
                InProcessServerBuilder
                    .forName(name)
                    .directExecutor()
                    .addService(service)
                    .build()
                    .start()
            val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
            try {
                return block(FuzzyServiceGrpcKt.FuzzyServiceCoroutineStub(channel))
            } finally {
                channel.shutdownNow()
                server.shutdownNow()
                repo.close()
            }
        }

        fun marvy() =
            BatchMatchRequest
                .newBuilder()
                .addSpans(
                    SpanQuery
                        .newBuilder()
                        .setQuery("Marvy")
                        .addCategories("customer")
                        .setLimit(5),
                ).build()

        "v2 over gRPC: TATRMAN_V2 rows carry token hits + coverage, ordered by coverage; status says v2" {
            runBlocking {
                withStub({
                    FuzzyMatcher(it, retrievalMode = RetrievalMode.INDEX_FIRST, matchVersion = MatchVersion.V2)
                }) { stub ->
                    val matches = stub.batchMatch(marvy()).getResults(0).matchesList
                    matches.map { it.candidateId } shouldBe listOf("c-marvy", "c-marvy-sk")
                    val top = matches.first().provenance
                    top.method shouldBe "TATRMAN_V2"
                    top.hasCoverage() shouldBe true
                    top.tokenHitsList.single().let { h ->
                        h.queryToken shouldBe "marvy"
                        h.candidateToken shouldBe "marvy"
                        h.kind shouldBe "exact"
                        h.queryPos shouldBe 0
                        h.candidatePos shouldBe 0
                    }
                    (top.coverage > matches[1].provenance.coverage) shouldBe true

                    stub.getStatus(FuzzyStatusRequest.getDefaultInstance()).engineVersion shouldBe "v2"
                }
            }
        }

        "v1 (the default) writes no v2 provenance and reports v1" {
            runBlocking {
                withStub({ FuzzyMatcher(it, retrievalMode = RetrievalMode.INDEX_FIRST) }) { stub ->
                    val top =
                        stub
                            .batchMatch(marvy())
                            .getResults(0)
                            .getMatches(0)
                            .provenance
                    top.method shouldBe "TATRMAN"
                    top.tokenHitsCount shouldBe 0
                    top.hasCoverage() shouldBe false
                    stub.getStatus(FuzzyStatusRequest.getDefaultInstance()).engineVersion shouldBe "v1"
                }
            }
        }

        "v2 with legacy retrieval refuses to construct (contracts §4.1)" {
            val repo = StringRepository(cfg(), loader)
            try {
                shouldThrow<IllegalArgumentException> {
                    FuzzyMatcher(repo, retrievalMode = RetrievalMode.LEGACY, matchVersion = MatchVersion.V2)
                }.message!! shouldContain "v2 requires index-first"
            } finally {
                repo.close()
            }
        }

        "config: fuzzy.match.version is read, defaults to v1, and a bad pair or value is a startup error" {
            fun fuzzy(hocon: String) = ConfigFactory.parseString(hocon)

            val indexFirst = TokenBasedConfig(retrieval = RetrievalMode.INDEX_FIRST)
            ConfigLoader.withMatchVersion(indexFirst, fuzzy("")).matchVersion shouldBe MatchVersion.V1
            ConfigLoader
                .withMatchVersion(indexFirst, fuzzy("match.version = v2"))
                .matchVersion shouldBe MatchVersion.V2
            shouldThrow<IllegalArgumentException> {
                ConfigLoader.withMatchVersion(TokenBasedConfig(), fuzzy("match.version = v2"))
            }.message!! shouldContain "v2 requires index-first"
            shouldThrow<IllegalArgumentException> {
                ConfigLoader.withMatchVersion(indexFirst, fuzzy("match.version = v9"))
            }
        }

        "application.conf ships v1 with the FUZZY_MATCH_VERSION override" {
            val conf = ConfigFactory.parseResources("application.conf").resolve()
            conf.getString("fuzzy.match.version") shouldBe "v1"
        }
    })
