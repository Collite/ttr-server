// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import com.google.protobuf.util.JsonFormat
import io.grpc.Status
import io.grpc.StatusException
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.fuzzy.v1.LookupRequest
import org.tatrman.fuzzy.v1.LookupResponse
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Capability
import org.tatrman.nlp.v1.NlpOp
import org.tatrman.nlp.v1.StatusResponse
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.client.NlpClient
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.LookupRoundConfig
import org.tatrman.resolver.pipeline.LookupRounds
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.EvidenceClass
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.fuzzy.v1.TargetClass as FuzzyTargetClass

/**
 * RV-P2.3.T1 — **the narrowing loop, end to end through the real pipeline.**
 *
 * The fixture trick throughout: the broad pass is given a matcher that *misses*, and the lexicon
 * only answers through `Lookup`. That is not artificial — it is the situation the rung exists for.
 * A broad pass asks one wide question per span and cannot express class scoping or a method
 * override; a round asks a narrow question the planner chose. Making the broad pass miss is how a
 * test can tell which of the two actually produced a binding.
 *
 * What each case pins is the QUERY, not just the result (T1's "assert the category filter on the
 * recorded query, not just the result"): a round that found the right answer by asking the whole
 * estate has not narrowed anything, and would pass a result-only assertion.
 */
class LookupRoundsTest :
    StringSpec({

        "(a) H1 — the code is re-asked against the account axis the user named, and ONLY that axis" {
            val fuzzy =
                RoundFuzzy(
                    // the broad pass finds the mention but misses the code
                    broadMisses = setOf("501001"),
                    answers = mapOf("501001" to listOf(member("501001", "md.dimension.Account.code", 1.0))),
                )
            val state = resolve(fuzzy)

            val request = fuzzy.lookups.single()
            request.term shouldBe "501001"
            // The narrowing itself: account categories, not the whole estate. Without this
            // assertion the test would pass on a round that asked about everything and got lucky.
            request.categoriesList shouldContainExactlyInAnyOrder
                listOf("md.dimension.Account", "md.dimension.Account.code", "md.dimension.Account.name")
            request.categoriesList.none { it.startsWith("md.dimension.Calendar") }.shouldBeTrue()

            // and it landed, through the P2.2 gate, as an EXACT member attribution
            val value = state.valuesList.single { it.span.text == "501001" }
            value.attributionsList
                .single()
                .binding.evidenceClass shouldBe EvidenceClass.EVIDENCE_CLASS_EXACT
            state.gapsList.none { it.valueId == value.id }.shouldBeTrue()
        }

        "(b) an unanchored value widens only AFTER the anchored rounds are exhausted, and is capped" {
            val fuzzy =
                RoundFuzzy(
                    // both the code and its axis miss the broad pass: two gaps of different kinds
                    broadMisses = setOf("501001", "účtu"),
                    answers = emptyMap(),
                )
            resolve(fuzzy)

            val tiers = fuzzy.lookups.map { it.categoriesList.isEmpty() to it.targetClassesList }
            // The unbound MENTION (`účtu`) is asked before anything widens, and it is class-scoped: an object, an
            // operator, or an authored word for a member (HP-H) — never a data value, which carries no class.
            tiers.first().second shouldContainExactly
                listOf(
                    FuzzyTargetClass.TARGET_CLASS_MODEL_OBJECT,
                    FuzzyTargetClass.TARGET_CLASS_OPERATOR,
                    FuzzyTargetClass.TARGET_CLASS_MEMBER,
                )
            // The broad round comes last, unscoped and capped.
            val broad = fuzzy.lookups.last()
            broad.categoriesList shouldBe emptyList()
            broad.targetClassesList shouldBe emptyList()
            broad.maxCandidates shouldBe LookupRoundConfig.DEFAULT.broadMaxCandidates
        }

        "(c) two-hop: binding the MENTION in round 1 is what lets round 2 narrow into it" {
            val fuzzy =
                RoundFuzzy(
                    broadMisses = setOf("501001", "účtu"),
                    answers =
                        mapOf(
                            "účtu" to listOf(declared("md.dimension.Account", "md.dimension.Account")),
                            "501001" to listOf(member("501001", "md.dimension.Account.code", 1.0)),
                        ),
                )
            val state = resolve(fuzzy)

            fuzzy.lookups.map { it.term } shouldContainExactly listOf("účtu", "501001")
            // Round 1 could not have known the account categories — it asked cross-category.
            fuzzy.lookups[0].categoriesList shouldBe emptyList()
            // Round 2 knows them, and knows them *because round 1 bound the mention*. This is the
            // GI-14 narrowing the list calls the two-hop: mention → its categories → the value.
            fuzzy.lookups[1].categoriesList shouldContainExactlyInAnyOrder
                listOf("md.dimension.Account", "md.dimension.Account.code", "md.dimension.Account.name")

            state.mentionsList.single { it.span.text == "účtu" }.bindingsCount shouldBe 1
            state.valuesList.single { it.span.text == "501001" }.attributionsCount shouldBe 1
            state.gapsList shouldBe emptyList()
        }

        "(d) the loop reaches a fixpoint: a round that answers nothing is asked once, never twice" {
            val fuzzy = RoundFuzzy(broadMisses = setOf("501001", "účtu"))
            val state = resolve(fuzzy)

            // Every question is asked at most once — the asked-set, which is what makes the loop
            // finite even when the clock never runs out.
            val keys = fuzzy.lookups.map { it.term to it.categoriesList.sorted() }
            keys.size shouldBe keys.distinct().size

            // One log entry per round, and the gaps it could not close are still open and honest.
            val rounds = state.rungLogList.filter { it.rung == LookupRounds.RUNG }
            rounds.size shouldBe fuzzy.lookups.size
            rounds.map { it.round } shouldContainExactly (1..rounds.size).toList()
            rounds.all { it.bindingsAdded == 0 }.shouldBeTrue()
            state.gapsList.map { it.kind } shouldContainExactlyInAnyOrder
                listOf(GapKind.GAP_KIND_G1_UNBOUND, GapKind.GAP_KIND_G3_UNATTRIBUTED)
        }

        "(d′) a spent wall-clock budget runs zero rounds — the rung is bounded, not best-effort" {
            val fuzzy =
                RoundFuzzy(
                    broadMisses = setOf("501001"),
                    answers = mapOf("501001" to listOf(member("501001", "md.dimension.Account.code", 1.0))),
                )
            val state = resolve(fuzzy, config = LookupRoundConfig.DEFAULT.copy(budgetMs = 0))

            fuzzy.lookups shouldBe emptyList()
            state.rungLogList.none { it.rung == LookupRounds.RUNG }.shouldBeTrue()
            // the gap the rung would have closed is simply still there, typed
            state.gapsList.single().kind shouldBe GapKind.GAP_KIND_G4_METHOD_MISS
        }

        "ttr-server#227 — a round that reads the code as a model object leaves it unattributed, on the door too" {
            // A round admits contenders exactly as the gate does, and the column is not a reading a value can
            // take. The first cut refused it only where the lattice writes attributions, so the lattice said
            // G4 while `Resolution.bindings` bound `501001` to the column.
            val code = "md.dimension.Account.code"
            val fuzzy =
                RoundFuzzy(
                    broadMisses = setOf("501001"),
                    answers = mapOf("501001" to listOf(declared(code, code))),
                )
            val response = respond(fuzzy)
            val state = response.resolutionState

            fuzzy.lookups.map { it.term } shouldContainExactly listOf("501001")
            val value = state.valuesList.single { it.span.text == "501001" }
            value.attributionsList shouldBe emptyList()
            state.gapsList.single { it.valueId == value.id }.kind shouldBe GapKind.GAP_KIND_G4_METHOD_MISS
            val door =
                response.resolution.bindingsList
                    .filter { it.hasDomain() }
                    .map { it.span.text }
            door shouldContain "účtu"
            door shouldNotContain "501001"
        }

        "a question the broad pass already resolved costs ZERO rounds — no gaps, nothing to ask" {
            val fuzzy = RoundFuzzy(broadMisses = emptySet())
            val state = resolve(fuzzy)
            state.gapsList shouldBe emptyList()
            fuzzy.lookups shouldBe emptyList()
        }

        "(review) a matcher that FAILS is not an empty vocabulary — the gap stays open and typed" {
            // The answer is right there and would bind; the matcher just cannot be reached. The
            // rung has no move but to leave the gap alone, and the thing being pinned is that it
            // leaves it TYPED rather than quietly emitting a lattice that looks settled.
            val fuzzy =
                RoundFuzzy(
                    broadMisses = setOf("501001"),
                    answers = mapOf("501001" to listOf(member("501001", "md.dimension.Account.code", 1.0))),
                    failLookups = true,
                )
            val state = resolve(fuzzy)

            fuzzy.lookups.isNotEmpty().shouldBeTrue()
            val value = state.valuesList.single { it.span.text == "501001" }
            value.attributionsList shouldBe emptyList()
            state.gapsList.single { it.valueId == value.id }.kind shouldBe GapKind.GAP_KIND_G4_METHOD_MISS
            // The rounds are in the log, honestly reporting that they added nothing — a failure is
            // absorbed, never hidden.
            val rounds = state.rungLogList.filter { it.rung == LookupRounds.RUNG }
            rounds.isNotEmpty().shouldBeTrue()
            rounds.all { it.bindingsAdded == 0 }.shouldBeTrue()
        }

        "(review) a matcher slower than the budget cannot outlive it — the rung bounds LATENCY too" {
            // Before the fix the clock was only consulted BETWEEN rounds, so the real bound was the
            // matcher stub's 30s deadline and a rung advertised at 150ms could add 10s to a resolve.
            val fuzzy =
                RoundFuzzy(
                    broadMisses = setOf("501001"),
                    answers = mapOf("501001" to listOf(member("501001", "md.dimension.Account.code", 1.0))),
                    lookupDelayMs = 10_000,
                )
            val elapsedMs =
                measureTimeMillis {
                    resolve(fuzzy, config = LookupRoundConfig.DEFAULT.copy(budgetMs = 150))
                }

            // Deliberately loose: the point is two orders of magnitude, not a stopwatch.
            elapsedMs shouldBeLessThan 5_000L
            // it did ask — the round is bounded, not skipped
            fuzzy.lookups.isNotEmpty().shouldBeTrue()
        }
    }) {
    private companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val parser: JsonFormat.Parser = JsonFormat.parser().ignoringUnknownFields()
        private val CASE: JsonObject = loadJson("/lattice/h1-cs.case.json")

        private fun resolve(
            fuzzy: FuzzyClient,
            config: LookupRoundConfig = LookupRoundConfig.DEFAULT,
        ): ResolutionState = respond(fuzzy, config).resolutionState

        private fun respond(
            fuzzy: FuzzyClient,
            config: LookupRoundConfig = LookupRoundConfig.DEFAULT,
        ): ResolveResponse {
            val pipeline =
                ResolverPipeline(
                    FakeNlp(
                        merge(
                            loadJson("/lattice/${CASE["parseFile"]!!.jsonPrimitive.content}"),
                            AnalyzeResponse.newBuilder(),
                        ).build(),
                    ),
                    fuzzy,
                    SnapshotRegistry(StubRegistrySource(DeclaredVocabulary(), ""), ResolverThresholds.LIVE),
                    emptyMap(),
                    ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1"),
                    lookupRounds = LookupRounds(fuzzy, config),
                )
            val request =
                ResolveRequest
                    .newBuilder()
                    .setConversationId("rv-p2-3")
                    .setFresh(
                        FreshQuestion
                            .newBuilder()
                            .setText(CASE["text"]!!.jsonPrimitive.content)
                            .setLocale(CASE["locale"]!!.jsonPrimitive.content),
                    ).setRegistry(merge(CASE["registry"]!!.jsonObject, Registry.newBuilder()).build())
                    .build()
            return runBlocking { pipeline.resolve(request) }
        }

        private fun declared(
            targetRef: String,
            category: String,
        ): FuzzyMatch =
            FuzzyMatch
                .newBuilder()
                .setCandidateId("lex:$targetRef")
                .setCandidate(targetRef)
                .setScore(1.0)
                .setCategory(category)
                .setSource(SourceTag.DECLARED)
                .setTargetRef(targetRef)
                .setTargetClass(FuzzyTargetClass.TARGET_CLASS_MODEL_OBJECT)
                .setMatchMethod("EXACT")
                .build()

        private fun member(
            id: String,
            category: String,
            score: Double,
        ): FuzzyMatch =
            FuzzyMatch
                .newBuilder()
                .setCandidateId(id)
                .setCandidate(id)
                .setScore(score)
                .setCategory(category)
                .setSource(SourceTag.MEMBER)
                .build()

        private fun <T : com.google.protobuf.Message.Builder> merge(
            source: JsonObject,
            builder: T,
        ): T {
            parser.merge(source.toString(), builder)
            return builder
        }

        private fun loadJson(resource: String): JsonObject =
            json
                .parseToJsonElement(
                    LookupRoundsTest::class.java.getResource(resource)?.readText()
                        ?: error("missing test resource $resource"),
                ).jsonObject
    }

    private class FakeNlp(
        private val parse: AnalyzeResponse,
    ) : NlpClient {
        override suspend fun analyze(request: AnalyzeRequest): AnalyzeResponse = parse

        override suspend fun getStatus(): StatusResponse =
            StatusResponse
                .newBuilder()
                .setReady(true)
                .addCapabilities(
                    Capability
                        .newBuilder()
                        .setOp(NlpOp.NER)
                        .setLanguage("cs")
                        .setEngine("nametag3"),
                ).addCapabilities(
                    Capability
                        .newBuilder()
                        .setOp(NlpOp.DEP_PARSE)
                        .setLanguage("cs")
                        .setEngine("stanza"),
                ).build()
    }

    /**
     * The h1 vocabulary, with chosen queries knocked out of the BROAD pass and an independent
     * `Lookup` table — so a binding's provenance is unambiguous: if it is there, a round put it
     * there. Records every `LookupRequest` in order, which is what the tier assertions read.
     */
    private class RoundFuzzy(
        private val broadMisses: Set<String>,
        private val answers: Map<String, List<FuzzyMatch>> = emptyMap(),
        /** The matcher is unreachable: every round question throws, as a live one would. */
        private val failLookups: Boolean = false,
        /** A matcher slower than the rung's whole budget. */
        private val lookupDelayMs: Long = 0,
    ) : FuzzyClient {
        /** Every round question, in the order the loop asked it — what the tier assertions read. */
        val lookups: MutableList<LookupRequest> = mutableListOf()

        private val byQuery: Map<String, List<FuzzyMatch>> =
            CASE["matcher"]!!
                .jsonObject["byQuery"]!!
                .jsonObject
                .mapValues { (_, matches) ->
                    matches.jsonArray.map { merge(it.jsonObject, FuzzyMatch.newBuilder()).build() }
                }

        override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse {
            val builder = BatchMatchResponse.newBuilder()
            for (span in request.spansList) {
                val scoped = span.categoriesList.toSet()
                val matches =
                    if (span.query in broadMisses) {
                        emptyList()
                    } else {
                        byQuery[span.query].orEmpty().filter { scoped.isEmpty() || it.category in scoped }
                    }
                builder.addResults(FuzzyMatchResponse.newBuilder().addAllMatches(matches))
            }
            return builder.build()
        }

        override suspend fun lookup(request: LookupRequest): LookupResponse {
            lookups += request
            if (lookupDelayMs > 0) delay(lookupDelayMs)
            if (failLookups) throw StatusException(Status.UNAVAILABLE.withDescription("lex-matcher is down"))
            return LookupResponse
                .newBuilder()
                .addAllCandidates(answers[request.term].orEmpty())
                .build()
        }

        override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()
    }
}
