// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.api

import com.typesafe.config.ConfigFactory
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.fuzzy.common.FuzzyMatchResponse
import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.core.AlgorithmType
import org.tatrman.fuzzy.core.Candidate
import org.tatrman.fuzzy.core.FuzzyMatcher
import org.tatrman.fuzzy.core.MatchVersion
import org.tatrman.fuzzy.core.RetrievalMode
import org.tatrman.fuzzy.core.StringRepository
import org.tatrman.fuzzy.loader.LoaderSource
import org.tatrman.fuzzy.telemetry.FuzzyTelemetry

/**
 * review-103 L6 — REST `/match` carries the v2 provenance (`tokenHits`, `coverage`) the gRPC path
 * carries (contracts §4.6), instead of reporting `method = TATRMAN_V2` with both dropped. A v1 row
 * carries neither, as on the wire.
 */
class MatchRestProvenanceTest :
    StringSpec({
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
        val security = ConfigFactory.parseString("""security { enabled = false }""")
        val json = Json { ignoreUnknownKeys = true }

        suspend fun rest(
            version: MatchVersion,
            query: String,
        ): Pair<FuzzyMatchResponse, FuzzyMatcher> {
            val repo =
                StringRepository(
                    AppConfig(serverPort = 0, grpcPort = 0, grpcReflectionEnabled = false, refreshIntervalSeconds = 0),
                    loader,
                )
            repo.forceRefresh()
            val matcher = FuzzyMatcher(repo, retrievalMode = RetrievalMode.INDEX_FIRST, matchVersion = version)
            var body = ""
            testApplication {
                application {
                    install(ContentNegotiation) { json() }
                    configureRoutes(matcher, repo, FuzzyTelemetry(), security)
                }
                body =
                    client
                        .post("/match") {
                            contentType(ContentType.Application.Json)
                            setBody("""{"query": "$query", "category": "customer", "limit": 3}""")
                        }.bodyAsText()
            }
            return json.decodeFromString<FuzzyMatchResponse>(body) to matcher
        }

        "v2 over REST: TATRMAN_V2 rows carry the same token hits and coverage the engine produced" {
            runBlocking {
                val (response, matcher) = rest(MatchVersion.V2, "valmi oil slovakia")
                val top = response.matches.first()
                top.candidateId shouldBe "c-valmy-sk"
                val provenance = top.provenance.shouldNotBeNull()
                provenance.method shouldBe "TATRMAN_V2"
                provenance.tokenHits.map { it.kind } shouldBe listOf("typo", "exact", "exact")
                provenance.tokenHits.first().let { h ->
                    h.queryToken shouldBe "valmi"
                    h.candidateToken shouldBe "valmy"
                    h.distance shouldBe 1
                    h.queryPos shouldBe 0
                    h.candidatePos shouldBe 0
                }
                // Field for field what the engine returned (and what gRPC writes).
                val engine =
                    matcher
                        .match("valmi oil slovakia", "customer", AlgorithmType.TATRMAN, 3)
                        .first()
                        .provenance
                provenance.tokenHits.map { listOf(it.queryToken, it.candidateToken, it.kind) } shouldBe
                    engine.tokenHits.map { listOf(it.queryToken, it.candidateToken, it.kind) }
                provenance.coverage shouldBe engine.coverage
            }
        }

        "v1 over REST: no token hits, no coverage — absent, as on the wire" {
            runBlocking {
                val provenance =
                    rest(MatchVersion.V1, "valmy oil")
                        .first.matches
                        .first()
                        .provenance
                        .shouldNotBeNull()
                provenance.method shouldBe "TATRMAN"
                provenance.tokenHits.shouldBeEmpty()
                provenance.coverage.shouldBeNull()
            }
        }
    })
