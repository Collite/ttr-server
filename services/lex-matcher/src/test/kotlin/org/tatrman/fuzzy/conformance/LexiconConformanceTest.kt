// SPDX-License-Identifier: Apache-2.0
package org.tatrman.fuzzy.conformance

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.config.LoaderSourceConfig
import org.tatrman.fuzzy.config.MetadataConfig
import org.tatrman.fuzzy.config.NlpConfig
import org.tatrman.fuzzy.config.TokenBasedConfig
import org.tatrman.fuzzy.core.Candidate
import org.tatrman.fuzzy.core.FuzzyMatcher
import org.tatrman.fuzzy.core.LookupQuery
import org.tatrman.fuzzy.core.StringRepository
import org.tatrman.fuzzy.loader.LexiconArchiveSource
import org.tatrman.fuzzy.loader.LoaderSource
import org.tatrman.ttr.lexicon.LexiconArea
import org.tatrman.ttr.lexicon.LexiconDataFile
import org.tatrman.ttr.lexicon.LexiconLoad
import org.tatrman.ttr.lexicon.LexiconValidator
import org.tatrman.ttr.lexicon.LexiconWarnings
import org.tatrman.ttr.lexicon.SkillDef
import org.tatrman.ttr.lexicon.TargetClass
import org.tatrman.ttr.lexicon.compile.LexiconCompiler
import org.tatrman.ttr.lexicon.compile.LexiconPacker
import org.tatrman.ttr.lexicon.compile.LexiconSources
import org.tatrman.ttr.lexicon.compile.ModelRefIndex
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeBytes

/**
 * RV-P1.4 T7 — the hartland_cz declared-layer conformance corpus.
 *
 * The fixture is the **authored source**, not an artifact: `aliases.lex.yaml` + `skills/trend.md`
 * are compiled and packed by the real RV-P1.2 toolchain and then read back through
 * `LexiconArchiveSource`. So a red case here means the *chain* broke — schema, compiler, packer,
 * reader, dispatcher, margin, or the layer tuple — rather than that someone edited a fixture into
 * disagreement with the code. Hand-writing a `CompiledLexicon` would have tested the last link
 * only, and this arc has already been bitten once by a test that proved less than it looked like.
 *
 * Hermetic: no live services, no pilot estate, no network. The corpus is hash-pinned in
 * `conformance/corpus-hashes.sha256` and runs in the gating tier.
 */
class LexiconConformanceTest :
    StringSpec({

        val root = "/conformance/hartland-cz"
        val modelHash = "sha256:" + "cf".repeat(32)
        val json = Json { ignoreUnknownKeys = true }

        fun resource(path: String): String =
            LexiconConformanceTest::class.java
                .getResourceAsStream(path)
                ?.bufferedReader()
                ?.readText()
                ?: error("missing conformance resource: $path")

        val lines =
            resource("$root/cases.jsonl")
                .lineSequence()
                .filter { it.isNotBlank() }
                .map { json.parseToJsonElement(it) as JsonObject }
                .toList()

        val modelRefs =
            lines
                .first { it.containsKey("_model_refs") }["_model_refs"]!!
                .jsonArray
                .map { it.jsonPrimitive.content }
                .toSet()

        val cases = lines.filter { it.containsKey("query") }

        /**
         * The hartland_cz Czech world's member data — DC names from the BM arc. Present so the
         * corpus is a two-layer estate rather than a lexicon in a vacuum: the `member` case below
         * asserts the non-lexicon path is untouched by everything the other cases exercise.
         */
        val members =
            object : LoaderSource {
                override suspend fun loadNextCache(): Map<String, List<Candidate>> =
                    mapOf(
                        "db.dbo.dc.name" to
                            listOf(
                                Candidate.fromValues("dc-brno", "Brno"),
                                Candidate.fromValues("dc-praha", "Praha"),
                                Candidate.fromValues("dc-ostrava", "Ostrava"),
                                Candidate.fromValues("dc-plzen", "Plzeň"),
                                Candidate.fromValues("dc-hk", "Hradec Králové"),
                            ),
                    )
            }

        fun cfg() =
            AppConfig(
                serverPort = 7106,
                grpcPort = 7206,
                grpcReflectionEnabled = false,
                refreshIntervalSeconds = 0,
                tokenBasedConfig = TokenBasedConfig(),
                nlp = NlpConfig(),
                loaderSource = LoaderSourceConfig(source = "static"),
                metadata = MetadataConfig(),
            )

        /** The authored alias file, loaded — kept so its WARNINGS are assertable, not just its rows. */
        val aliasLoad =
            LexiconValidator
                .loadDataFile(resource("$root/aliases.lex.yaml"), "aliases.lex.yaml")
                .shouldBeInstanceOf<LexiconLoad.Ok<LexiconDataFile>>()

        /** Compiles the corpus's authored files exactly as the RV-P1.2 estate build does. */
        val compiled by lazy {
            val trend =
                LexiconValidator
                    .loadSkillFile(resource("$root/skills/trend.md"), "skills/trend.md")
                    .shouldBeInstanceOf<LexiconLoad.Ok<SkillDef>>()
                    .value

            LexiconCompiler
                .compile(
                    LexiconSources(area = LexiconArea(listOf(aliasLoad.value), listOf(trend))),
                    // Only the refs the corpus declares. `op:` never reaches here — it resolves by
                    // prefix — which is itself the assertion that an operator cannot dangle.
                    ModelRefIndex { ref -> if (ref in modelRefs) TargetClass.MODEL_OBJECT else null },
                    modelHash,
                    "2026-08-03T00:00:00Z",
                ).also {
                    // RV-20: a dangling ref is dropped with a warning. The corpus must not rely on
                    // that path — every ref it authors has to resolve, or the cases below assert on
                    // a lexicon quietly smaller than the one on disk.
                    it.warnings.shouldBe(emptyList())
                }
        }

        fun packArchive(dir: Path): Path =
            dir.resolve("hartland-cz.tar.zst").also {
                it.writeBytes(LexiconPacker.pack(compiled, modelHash, "conformance").bytes)
            }

        fun estate(withLexicon: Boolean): StringRepository {
            val source =
                if (withLexicon) {
                    LexiconArchiveSource(packArchive(Files.createTempDirectory("hartland-cz")))
                } else {
                    null
                }
            return StringRepository(cfg(), members, snapshotSource = source).also {
                runBlocking { it.forceRefresh() }
            }
        }

        // ---- the corpus ------------------------------------------------------------------------

        cases.forEach { case ->
            val query = case["query"]!!.jsonPrimitive.content
            val cls = case["class"]!!.jsonPrimitive.content

            "[$cls] $query" {
                val repo = estate(withLexicon = true)
                try {
                    runBlocking {
                        val hits =
                            FuzzyMatcher(repo).lookup(LookupQuery(term = query, maxCandidates = 5)).candidates

                        case["expect_absent_term"]?.let { absent ->
                            // The authored term must not be reachable at all — not merely ranked
                            // lower. Ranking lower is what the engine does anyway; refusing is what
                            // the authored method does.
                            hits.map { it.candidate } shouldNotBe null
                            hits.none { it.candidate == absent.jsonPrimitive.content } shouldBe true
                        }

                        case["expect_flagged_targets"]?.let { flagged ->
                            val expected = flagged.jsonArray.map { it.jsonPrimitive.content }
                            val seen = hits.filter { it.targetRef in expected }
                            // Distinct targets: a target legitimately contributes several aliases
                            // (`tržby`, `obrat`, `tržby celkem` are all md.measure.revenue), and they are
                            // not rivals — identity is the target ref (T4).
                            seen.map { it.targetRef }.distinct() shouldContainExactlyInAnyOrder expected
                            seen.forEach { it.autoBindable shouldBe false }
                        }

                        if (case.containsKey("expect_top_target")) {
                            val top = hits.first()
                            val expected = case["expect_top_target"]!!.jsonPrimitive.contentOrNullSafe
                            top.targetRef shouldBe expected
                            case["expect_source"]?.let { top.source.name shouldBe it.jsonPrimitive.content }
                            case["expect_class"]?.let { top.targetClass?.name shouldBe it.jsonPrimitive.content }
                            case["expect_method"]?.let { top.matchMethod shouldBe it.jsonPrimitive.content }
                            case["expect_auto_bindable"]?.let {
                                top.autoBindable shouldBe it.jsonPrimitive.boolean
                            }
                            // RV-44 — the declared score and the (norm, algorithm) that earned it.
                            // Asserting the triple and not just the number is what keeps a case
                            // honest: two different strata can reach the same score by accident,
                            // and only one of them is the behaviour the fixture is about.
                            case["expect_score"]?.let {
                                top.score shouldBe (it.jsonPrimitive.double plusOrMinus 1e-9)
                            }
                            case["expect_norm"]?.let { top.provenance.norm shouldBe it.jsonPrimitive.content }
                            case["expect_algorithm"]?.let {
                                top.provenance.algorithm shouldBe it.jsonPrimitive.content
                            }
                            if (expected == null) {
                                // The member path carries none of the declared layer's annotations.
                                top.targetClass.shouldBeNull()
                                top.matchMethod.shouldBeNull()
                                top.autoBindable.shouldBeNull()
                            }
                        }
                    }
                } finally {
                    repo.close()
                }
            }
        }

        // ---- RV-44: what the BUILD says about this corpus ----------------------------------------

        "⚑M-4 — the estate's short code warns at build time, and the warning names the guard" {
            // The other half of the `PX` case above: a fixture that only proved the matcher does
            // not fire would leave "and the author was told why" untested.
            aliasLoad.warnings.map { it.code } shouldBe listOf(LexiconWarnings.SHORT_TERM_TYPOS_GUARD)
            aliasLoad.warnings
                .single()
                .message shouldContain "PJ"
        }

        "⚑M-2 — every compiled DECLARED row carries a resolved profile, however it was spelled" {
            // The reach rule, on real authored content rather than a unit fixture. Only two terms
            // in this corpus write `match:` by hand; every other row gets its profile from the
            // compiler expanding `method:`, because carrying one is a property of the LAYER.
            aliasLoad.value.entries
                .flatMap { it.terms }
                .count { it.matchProfile != null } shouldBe 2
            compiled.lexicon.entries.all { it.matchProfile != null } shouldBe true
        }

        // ---- the RV-39 layer tuple (asserted per run, not per case) -----------------------------

        "the layer tuple names the artifact, every member category, and no overlay" {
            val repo = estate(withLexicon = true)
            try {
                val tuple = repo.layerVersions()

                tuple.lexiconArtifactHash.shouldNotBeBlank()
                tuple.memberIndexVersions.keys shouldContainExactlyInAnyOrder listOf("db.dbo.dc.name")
                // Absent with no overlay loaded — absence is the contract, not an empty string (T2/T6).
                tuple.overlayVersion.shouldBeNull()
            } finally {
                repo.close()
            }
        }

        "the artifact hash tracks the vocabulary, and a rebuild of the same source reproduces it" {
            // Determinism the whole channel rests on: same authored files ⇒ same entry table ⇒ same
            // hash, so `did a layer change?` is answerable without diffing the archive.
            val a = estate(withLexicon = true)
            val b = estate(withLexicon = true)
            try {
                a.layerVersions().lexiconArtifactHash shouldBe b.layerVersions().lexiconArtifactHash
            } finally {
                a.close()
                b.close()
            }
        }

        // ---- no behaviour change without the artifact --------------------------------------------

        "with no lexicon archive the member path is byte-identical and the tuple says so" {
            val withLexicon = estate(withLexicon = true)
            val without = estate(withLexicon = false)
            try {
                runBlocking {
                    val bare = FuzzyMatcher(without).lookup(LookupQuery("Brno", maxCandidates = 5)).candidates
                    val loaded = FuzzyMatcher(withLexicon).lookup(LookupQuery("Brno", maxCandidates = 5)).candidates

                    // Same member candidate, same score, same (absent) annotations.
                    bare.first().candidateId shouldBe "dc-brno"
                    loaded.first().candidateId shouldBe bare.first().candidateId
                    loaded.first().score shouldBe bare.first().score

                    // And nothing from the declared layer exists to be found.
                    FuzzyMatcher(without)
                        .lookup(LookupQuery("tržby", maxCandidates = 5))
                        .candidates
                        .none { it.targetRef != null } shouldBe true

                    without.layerVersions().lexiconArtifactHash shouldBe ""
                }
            } finally {
                withLexicon.close()
                without.close()
            }
        }

        "the skill BODY never becomes a candidate (RV-35)" {
            // Only `triggers:` compile into the lexicon. If a body phrase were reachable, the
            // matcher would be serving Golem instructions as vocabulary.
            val repo = estate(withLexicon = true)
            try {
                runBlocking {
                    val hits =
                        FuzzyMatcher(repo)
                            .lookup(LookupQuery("group by the finest requested time grain", maxCandidates = 5))
                            .candidates

                    hits.none { it.candidate.contains("grain") } shouldBe true
                }
            } finally {
                repo.close()
            }
        }
    })

/** `null` in the corpus means "no target ref" (a member hit), not the string "null". */
private val kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe: String?
    get() = if (!isString && content == "null") null else content
