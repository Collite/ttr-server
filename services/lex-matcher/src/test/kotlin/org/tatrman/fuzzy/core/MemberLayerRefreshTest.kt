// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.config.NlpConfig
import org.tatrman.fuzzy.config.TokenBasedConfig
import org.tatrman.fuzzy.loader.LoaderSource
import org.tatrman.fuzzy.loader.MemberLoad
import org.tatrman.fuzzy.loader.StaticLoaderSource
import java.util.concurrent.atomic.AtomicInteger

/**
 * review-104 — the member layer's refresh and scoring, now that member rows carry their
 * vocabulary's method (MV-T2): F10's scoring headroom and F15's one-refresh-at-a-time.
 */
class MemberLayerRefreshTest :
    StringSpec({

        fun cfg() =
            AppConfig(
                serverPort = 7131,
                grpcPort = 7231,
                grpcReflectionEnabled = false,
                refreshIntervalSeconds = 0,
                tokenBasedConfig = TokenBasedConfig(),
                nlp = NlpConfig(),
            )

        val city = "er.entity.city.name"

        "F10 — an EXACT member vocabulary gets the gate's headroom: the exact value past the limit is found" {
            // Folded, the two spellings are one word, so the recall-oriented scorer ties them; EXACT
            // compares the AUTHORED form and admits only `Plzeň`. Scored at exactly `limit = 1`,
            // the look-alike took the one slot, the gate rejected it, and the answer was nothing.
            val rows =
                listOf("Plzen", "PLZEN", "Plzeň").map { Candidate.fromValues(it, it, "EXACT", city) }
            val repo = StringRepository(cfg(), StaticLoaderSource(mapOf(city to rows)))
            try {
                runBlocking { repo.forceRefresh() }

                repo.narrowsAfterScoring(city) shouldBe true
                val hits = runBlocking { FuzzyMatcher(repo).match("Plzeň", city, AlgorithmType.TATRMAN, 1) }
                hits.map { it.candidateId } shouldContainExactly listOf("Plzeň")
            } finally {
                repo.close()
            }
        }

        "F10 — a TOKENS member vocabulary narrows nothing, so it keeps the byte-identical path" {
            val rows =
                listOf(
                    "Nashville Central",
                    "Nashville East",
                ).map { Candidate.fromValues(it, it, "TOKENS", city) }
            val repo = StringRepository(cfg(), StaticLoaderSource(mapOf(city to rows)))
            try {
                runBlocking { repo.forceRefresh() }

                repo.narrowsAfterScoring(city) shouldBe false
                repo.narrowsAfterScoring(null) shouldBe false
            } finally {
                repo.close()
            }
        }

        "F15 — refreshes never overlap: POST /refresh waits for the scheduled one in flight" {
            val inFlight = AtomicInteger()
            val widest = AtomicInteger()
            val loader =
                object : LoaderSource {
                    override suspend fun loadNextCache(): Map<String, List<Candidate>> = load().categories

                    override suspend fun load(): MemberLoad {
                        widest.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                        delay(50)
                        inFlight.decrementAndGet()
                        return MemberLoad(mapOf(city to listOf(Candidate.fromValues("Praha", "Praha", "EXACT", city))))
                    }
                }
            val repo = StringRepository(cfg(), loader)
            try {
                runBlocking { List(4) { async { repo.forceRefresh() } }.awaitAll() }

                widest.get() shouldBe 1
            } finally {
                repo.close()
            }
        }
    })
