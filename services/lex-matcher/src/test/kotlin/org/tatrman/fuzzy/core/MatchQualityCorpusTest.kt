// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.config.LoaderSourceConfig
import org.tatrman.fuzzy.config.MetadataConfig
import org.tatrman.fuzzy.config.NlpConfig
import org.tatrman.fuzzy.config.TokenBasedConfig
import org.tatrman.fuzzy.loader.LoaderSource

/**
 * RG-P2.S1.T1 — the Q-17 match-quality referee corpus, run against a fixture
 * vocabulary with the lemma axis ON (a deterministic fixture [Lemmatizer], no
 * `nlp` dependency). Four classes: diacritics, inflection (needs the axis),
 * multi-word-order, typos. This is the gate for any future engine evolution
 * (B2-β) — a drift here is a regression, not a fixture to update (recompute by
 * hand first). The axis-matters proof (inflection fails with the axis OFF) is
 * [MatchQualityAxisProofTest] (T5).
 */
class MatchQualityCorpusTest :
    FunSpec({
        val (repo, matcher) = corpusMatcher(FIXTURE_LEMMATIZER)
        beforeSpec { repo.awaitReady() }
        afterSpec { repo.close() }

        for (case in loadCorpus()) {
            test("${case.cls}: '${case.query}' → ${case.expected} (${case.category})") {
                val results = matcher.match(case.query, case.category, AlgorithmType.TATRMAN, 5)
                results.firstOrNull()?.candidateId shouldBe case.expected
            }
        }
    })

// --- shared fixtures (reused by the axis-proof test) ------------------------

data class CorpusCase(
    val query: String,
    val category: String,
    val expected: String,
    val cls: String,
)

fun loadCorpus(): List<CorpusCase> {
    val stream =
        checkNotNull(object {}.javaClass.getResourceAsStream("/match-quality-corpus.jsonl")) {
            "match-quality-corpus.jsonl not on the test classpath"
        }
    return stream.bufferedReader().useLines { lines ->
        lines
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { Json.parseToJsonElement(it).jsonObject }
            .filter { it["query"] != null } // skip the _comment header line
            .map {
                CorpusCase(
                    query = it["query"]!!.jsonPrimitive.content,
                    category = it["category"]!!.jsonPrimitive.content,
                    expected = it["expected_top_id"]!!.jsonPrimitive.content,
                    cls = it["class"]!!.jsonPrimitive.content,
                )
            }.toList()
    }
}

/** The fixture vocabulary: distinct entities per category so each expected match is unambiguous. */
private val FIXTURE_VOCAB: Map<String, List<Candidate>> =
    mapOf(
        "product" to
            listOf(
                Candidate.fromValues("p-octavia", "Škoda Octavia"),
                Candidate.fromValues("p-fabia", "Škoda Fabia"),
                Candidate.fromValues("p-superb", "Škoda Superb"),
                Candidate.fromValues("p-golf", "Volkswagen Golf"),
                Candidate.fromValues("p-passat", "Volkswagen Passat"),
            ),
        "customer" to
            listOf(
                Candidate.fromValues("c-zakaznik", "Zákazník Servis"),
                Candidate.fromValues("c-dodavatel", "Dodavatel Morava"),
                Candidate.fromValues("c-pelex", "Pelex UK"),
                Candidate.fromValues("c-tesla", "Tesla Inc"),
            ),
        "branch" to
            listOf(
                Candidate.fromValues("b-praha", "Pražská pobočka"),
                Candidate.fromValues("b-brno", "Brněnská pobočka"),
                Candidate.fromValues("b-ostrava", "Ostravská pobočka"),
            ),
        "branch-term" to listOf(Candidate.fromValues("term-pobocka", "pobočka")),
        "measure-term" to
            listOf(
                Candidate.fromValues("term-trzba", "tržba"),
                Candidate.fromValues("term-obrat", "obrat"),
            ),
    )

/**
 * A deterministic fixture lemmatiser standing in for `nlp` MorphoDiTa —
 * maps raw (lower-cased, accented) inflected forms to their folded lemma, so
 * inflection cases resolve offline. Anything unmapped folds to its surface form
 * (identity axis), exactly like the degradable real lemmatiser.
 */
val FIXTURE_LEMMATIZER: Lemmatizer =
    object : Lemmatizer {
        private val lemmas =
            mapOf(
                "octavia" to "octavia",
                "octavii" to "octavia",
                "octavie" to "octavia",
                "fabia" to "fabia",
                "fabii" to "fabia",
                "superb" to "superb",
                "superbu" to "superb",
                "golf" to "golf",
                "golfu" to "golf",
                "passat" to "passat",
                "passatu" to "passat",
                "škoda" to "skoda",
                "volkswagen" to "volkswagen",
                "pobočka" to "pobocka",
                "pobočkách" to "pobocka",
                "poboček" to "pobocka",
                "pražská" to "prazska",
                "brněnská" to "brnenska",
                "ostravská" to "ostravska",
                "tržba" to "trzba",
                "tržby" to "trzba",
                "tržbu" to "trzba",
                "obrat" to "obrat",
                "obratu" to "obrat",
                "zákazník" to "zakaznik",
                "zákazníků" to "zakaznik",
                "dodavatel" to "dodavatel",
                "pelex" to "pelex",
                "uk" to "uk",
                "tesla" to "tesla",
                "inc" to "inc",
                "servis" to "servis",
                "morava" to "morava",
            )

        override suspend fun lemmatize(tokens: Collection<String>): Map<String, String> =
            tokens.associateWith { lemmas[it.lowercase()] ?: TextNormalizer.fold(it) }
    }

private fun corpusConfig() =
    AppConfig(
        serverPort = 7103,
        grpcPort = 7203,
        grpcReflectionEnabled = false,
        refreshIntervalSeconds = 3600,
        tokenBasedConfig = TokenBasedConfig(),
        nlp = NlpConfig(),
        loaderSource = LoaderSourceConfig(source = "static"),
        metadata = MetadataConfig(),
    )

/** Builds a repository (loaded synchronously) + matcher wired to [lemmatizer]. */
fun corpusMatcher(lemmatizer: Lemmatizer): Pair<StringRepository, FuzzyMatcher> {
    val loader =
        object : LoaderSource {
            override suspend fun loadNextCache(): Map<String, List<Candidate>> = FIXTURE_VOCAB
        }
    val repo = StringRepository(corpusConfig(), loader, telemetry = null, lemmatizer = lemmatizer)
    return repo to FuzzyMatcher(repo, lemmatizer = lemmatizer)
}

/** Force a synchronous load so cases don't race the background refresh loop. */
fun StringRepository.awaitReady() = runBlocking { forceRefresh() }
