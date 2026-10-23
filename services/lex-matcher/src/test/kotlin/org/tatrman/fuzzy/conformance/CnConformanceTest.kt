// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.conformance

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.config.LoaderSourceConfig
import org.tatrman.fuzzy.config.MetadataConfig
import org.tatrman.fuzzy.config.NlpConfig
import org.tatrman.fuzzy.config.TokenBasedConfig
import org.tatrman.fuzzy.core.Candidate
import org.tatrman.fuzzy.core.FuzzyMatchResult
import org.tatrman.fuzzy.core.FuzzyMatcher
import org.tatrman.fuzzy.core.LookupQuery
import org.tatrman.fuzzy.core.SourceTag
import org.tatrman.fuzzy.core.StringRepository
import org.tatrman.fuzzy.loader.CnEstate
import org.tatrman.fuzzy.loader.LexiconArchiveSource
import org.tatrman.fuzzy.loader.LoaderSource
import java.nio.file.Files

/**
 * CN-P4 S4.3 T2 / T4 (cn contracts §5, A-CN-10) — the cn-estate declared-layer conformance corpus.
 *
 * The matcher serves a concept's terms as it serves an er object's: `fuzzy.match:v1` keeps its shape, and a concept
 * reading is one more row whose category is its `cn.*` ref (category is data). The estate is compiled by the real
 * producer ([CnEstate]) and read back through [LexiconArchiveSource]; every case runs through Lookup (the resolver's
 * path) and through Match (the door's), and both must give the same exact-form targets. Hermetic, hash-pinned in
 * `conformance/corpus-hashes.sha256`, and run in the gating tier.
 */
class CnConformanceTest :
    StringSpec({
        val json = Json { ignoreUnknownKeys = true }

        val cases =
            (
                CnConformanceTest::class.java
                    .getResourceAsStream("/conformance/cn-estate/cases.jsonl")
                    ?.bufferedReader()
                    ?.readText()
                    ?: error("missing conformance resource: cn-estate/cases.jsonl")
            ).lineSequence()
                .filter { it.isNotBlank() }
                .map { json.parseToJsonElement(it) as JsonObject }
                .filter { it.containsKey("query") }
                .toList()

        val repo by lazy {
            val archive =
                CnEstate.pack(
                    CnEstate.build(),
                    Files.createTempDirectory("cn-conf").resolve("lexicon.tar.zst"),
                )
            StringRepository(
                AppConfig(
                    serverPort = 0,
                    grpcPort = 0,
                    grpcReflectionEnabled = false,
                    refreshIntervalSeconds = 0,
                    tokenBasedConfig = TokenBasedConfig(),
                    nlp = NlpConfig(),
                    loaderSource = LoaderSourceConfig(source = "static"),
                    metadata = MetadataConfig(),
                ),
                object : LoaderSource {
                    override suspend fun loadNextCache(): Map<String, List<Candidate>> = emptyMap()
                },
                snapshotSource = LexiconArchiveSource(archive),
            ).also { runBlocking { it.forceRefresh() } }
        }

        /** The hits whose candidate IS the query's form: the readings of that form, not its partial neighbours. */
        fun exact(
            hits: List<FuzzyMatchResult>,
            query: String,
        ) = hits.filter { it.candidate.equals(query, ignoreCase = true) }

        cases.forEach { case ->
            val query = case["query"]!!.jsonPrimitive.content
            val cls = case["class"]!!.jsonPrimitive.content
            val expected = case["expect_targets"]!!.jsonArray.map { it.jsonPrimitive.content }

            "[$cls] $query" {
                val looked =
                    runBlocking { FuzzyMatcher(repo).lookup(LookupQuery(term = query, maxCandidates = 10)).candidates }
                val matched = runBlocking { FuzzyMatcher(repo).match(query, null, null, 10) }

                for ((surface, hits) in listOf("lookup" to looked, "match" to matched)) {
                    withClue(surface) {
                        val readings = exact(hits, query)
                        // Distinct targets: one target legitimately carries the form in two languages (`vip`).
                        readings.mapNotNull { it.targetRef }.distinct() shouldContainExactlyInAnyOrder expected
                        // A concept's terms are authored; an element's may come from its model (METADATA).
                        readings.filter { it.targetRef.orEmpty().startsWith("cn.") }.forEach {
                            it.source shouldBe SourceTag.DECLARED
                        }
                        // Two readings of one form are rivals the matcher does not order: one score.
                        readings.map { it.score }.distinct().size shouldBe 1
                        if (cls == "member") hits.filter { it.targetRef.orEmpty().startsWith("cn.") }.shouldBeEmpty()
                    }
                }
                // The door narrows by category, and a declared row's category is its ref: asking for one reading of a
                // shared form returns that reading alone (category is data, a concept's as an element's).
                for (target in expected) {
                    withClue("match in $target") {
                        val narrowed = runBlocking { FuzzyMatcher(repo).match(query, target, null, 10) }
                        exact(narrowed, query).map { it.targetRef }.distinct() shouldBe listOf(target)
                        exact(narrowed, query).forEach { it.category shouldBe target }
                    }
                }
            }
        }
    })
