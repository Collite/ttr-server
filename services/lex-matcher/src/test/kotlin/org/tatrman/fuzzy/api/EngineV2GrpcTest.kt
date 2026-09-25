// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.api

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThan
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
import org.tatrman.fuzzy.core.V2Normalization
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
                                Candidate.fromValues("c-valmy", "Valmy Oil, s.r.o."),
                                Candidate.fromValues("c-valmy-sk", "Valmy Oil Slovakia, s.r.o."),
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

        fun valmy() =
            BatchMatchRequest
                .newBuilder()
                .addSpans(
                    SpanQuery
                        .newBuilder()
                        .setQuery("Valmy")
                        .addCategories("customer")
                        .setLimit(5),
                ).build()

        "v2 over gRPC: TATRMAN_V2 rows carry token hits + coverage, ordered by coverage; status says v2" {
            runBlocking {
                withStub({
                    FuzzyMatcher(it, retrievalMode = RetrievalMode.INDEX_FIRST, matchVersion = MatchVersion.V2)
                }) { stub ->
                    val matches = stub.batchMatch(valmy()).getResults(0).matchesList
                    matches.map { it.candidateId } shouldBe listOf("c-valmy", "c-valmy-sk")
                    val top = matches.first().provenance
                    top.method shouldBe "TATRMAN_V2"
                    top.hasCoverage() shouldBe true
                    top.tokenHitsList.single().let { h ->
                        h.queryToken shouldBe "valmy"
                        h.candidateToken shouldBe "valmy"
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
                            .batchMatch(valmy())
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

        "config: fuzzy.match.version is read, absent/blank is the shipped v2, a bad pair or value stops startup" {
            fun fuzzy(hocon: String) = ConfigFactory.parseString(hocon)

            val indexFirst = TokenBasedConfig(retrieval = RetrievalMode.INDEX_FIRST)
            // review-103 L1 — no key, no block, or an env var exported empty: unset ⇒ shipped v2, not v1.
            ConfigLoader.withMatchVersion(indexFirst, fuzzy("")).matchVersion shouldBe MatchVersion.V2
            ConfigLoader.withMatchVersion(indexFirst, fuzzy("match { }")).matchVersion shouldBe MatchVersion.V2
            ConfigLoader.withMatchVersion(indexFirst, fuzzy("match.version = \"\"")).matchVersion shouldBe
                MatchVersion.V2
            ConfigLoader.withMatchVersion(indexFirst, fuzzy("match.version = v1")).matchVersion shouldBe MatchVersion.V1
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

        // LP-P3 T3 (ruling LPA-2). The SHIPPED value moved v1 -> v2. Since review-103 L1 an absent
        // or blank key ALSO runs v2 in the service (the case above) — deleting the key used to fall
        // back to the library's v1 silently. The rollback is FUZZY_MATCH_VERSION=v1, explicitly, with
        // no image involved.
        "application.conf ships v2 with the FUZZY_MATCH_VERSION override" {
            val conf = ConfigFactory.parseResources("application.conf").resolve()
            conf.getString("fuzzy.match.version") shouldBe "v2"
            // The pair has to be checkable together: v2 + legacy is a startup error, so the
            // shipped conf is only startable because retrieval ships index-first beside it.
            conf.getString("fuzzy.token-based.retrieval") shouldBe "index-first"
            ConfigLoader
                .withMatchVersion(
                    TokenBasedConfig(retrieval = RetrievalMode.INDEX_FIRST),
                    conf.getConfig("fuzzy"),
                ).matchVersion shouldBe MatchVersion.V2
        }

        "config: fuzzy.match.v2.normalize — default scale/0.99, blank is the default, bad values stop startup" {
            fun fuzzy(hocon: String) = ConfigFactory.parseString(hocon)

            fun norm(hocon: String) = ConfigLoader.withV2Normalization(TokenBasedConfig(), fuzzy(hocon)).v2Normalization

            norm("") shouldBe V2Normalization.DEFAULT
            norm("match.v2.normalize { mode = cap, ceiling = 0.95 }") shouldBe
                V2Normalization(V2Normalization.Mode.CAP, 0.95)
            norm("match.v2.normalize { mode = OFF }") shouldBe V2Normalization(V2Normalization.Mode.OFF, 0.99)
            // An env var exported empty is unset, not a value.
            norm("match.v2.normalize { mode = \"\", ceiling = \"\" }") shouldBe V2Normalization.DEFAULT
            norm("match.v2.normalize.ceiling = \"0.9\"").ceiling shouldBe 0.9

            for (bad in listOf("1.0", "1", "1.5", "0", "-0.2")) {
                val refused = shouldThrow<IllegalArgumentException> { norm("match.v2.normalize.ceiling = $bad") }
                refused.message!! shouldContain "fuzzy.match.v2.normalize.ceiling"
            }
            shouldThrow<IllegalArgumentException> { norm("match.v2.normalize.ceiling = high") }.message!! shouldContain
                "fuzzy.match.v2.normalize.ceiling must be a number"
            shouldThrow<IllegalArgumentException> { norm("match.v2.normalize.mode = clip") }.message!! shouldContain
                "fuzzy.match.v2.normalize.mode"
        }

        "application.conf ships scale/0.99 and wires FUZZY_V2_NORMALIZE_MODE / _CEILING" {
            val raw = ConfigFactory.parseResources("application.conf")
            val shipped = raw.resolve().getConfig("fuzzy")
            ConfigLoader.withV2Normalization(TokenBasedConfig(), shipped).v2Normalization shouldBe
                V2Normalization.DEFAULT
            // The env names as the pod sets them: root-level keys a `${?…}` substitution resolves to.
            val env =
                ConfigFactory.parseMap(
                    mapOf("FUZZY_V2_NORMALIZE_MODE" to "cap", "FUZZY_V2_NORMALIZE_CEILING" to "0.97"),
                )
            ConfigLoader
                .withV2Normalization(TokenBasedConfig(), raw.withFallback(env).resolve().getConfig("fuzzy"))
                .v2Normalization shouldBe V2Normalization(V2Normalization.Mode.CAP, 0.97)
        }

        "v2 over gRPC: the configured normalization reaches the wire (off vs the default)" {
            suspend fun score(normalization: V2Normalization): Double =
                withStub({
                    FuzzyMatcher(
                        it,
                        retrievalMode = RetrievalMode.INDEX_FIRST,
                        matchVersion = MatchVersion.V2,
                        v2Normalization = normalization,
                    )
                }) { stub ->
                    stub
                        .batchMatch(
                            BatchMatchRequest
                                .newBuilder()
                                .addSpans(
                                    SpanQuery.newBuilder().setQuery("valmi oil slovakia").addCategories("customer"),
                                ).build(),
                        ).getResults(0)
                        .getMatches(0)
                        .also { it.candidateId shouldBe "c-valmy-sk" }
                        .score
                }
            runBlocking {
                // typo · exact · exact, in order: ≥ 1.0 as §4.3 wrote it, under 1.0 as shipped.
                score(V2Normalization.OFF) shouldBeGreaterThanOrEqual 1.0
                score(V2Normalization.DEFAULT) shouldBeLessThan 1.0
            }
        }

        // LP-P3 T3 — the consequence of the flip that is easiest to miss: reaching for the FZ-P2
        // retrieval escape hatch ALONE now stops the service, where before it silently downgraded
        // the scorer too. Pinned so the README's warning cannot drift away from the behaviour.
        "the retrieval escape hatch alone is now a startup error, and v1 beside it is the way out" {
            val conf = ConfigFactory.parseResources("application.conf").resolve().getConfig("fuzzy")
            val legacy = TokenBasedConfig(retrieval = RetrievalMode.LEGACY)
            shouldThrow<IllegalArgumentException> {
                ConfigLoader.withMatchVersion(legacy, conf)
            }.message!! shouldContain "v2 requires index-first"
            ConfigLoader
                .withMatchVersion(legacy, conf.withValue("match.version", ConfigValueFactory.fromAnyRef("v1")))
                .matchVersion shouldBe MatchVersion.V1
        }
    })
