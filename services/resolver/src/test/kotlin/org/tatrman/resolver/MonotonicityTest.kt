// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import com.google.protobuf.util.JsonFormat
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
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
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.ResolveRequest

/**
 * RV-P2.3.T5 — **rounds add or confirm; they never remove** (RV-9).
 *
 * Monotonicity is the property that makes an iterative core safe to reason about: a caller reading
 * an intermediate lattice must never find that the next round took something away, or "what the
 * core understood" becomes a moving target and every downstream decision is provisional. Here it is
 * structural rather than defended by a check — the planner only ever asks about spans the lattice
 * reports as EMPTY (an unbound mention, an unattributed value), so a round has nothing to displace,
 * and results are appended rather than merged-and-re-decided. This asserts the property holds;
 * `LookupRounds`' class doc explains why it cannot not hold.
 *
 * The other half of the same rule: a candidate the gate REFUSED is not a weaker annotation to be
 * recorded beside the good ones. It goes to the round's log, where a ladder deciding whether to
 * escalate can read it, and never to the lattice, where a reader might be tempted to overrule the
 * gate with it.
 */
class MonotonicityTest :
    StringSpec({

        "a round that finds only WEAK candidates changes the lattice not at all" {
            val before = resolve(RoundFuzzy(broadMisses = setOf("501001")))
            val after =
                resolve(
                    RoundFuzzy(
                        broadMisses = setOf("501001"),
                        // 0.62 is below the RV-14 class floor: real rows, refused evidence.
                        answers = mapOf("501001" to listOf(member("5AU 5001", 0.62), member("7AX 0800", 0.55))),
                    ),
                )

            // Same mentions, same values, same gaps — the round learned nothing it was allowed to use.
            after.mentionsList shouldBe before.mentionsList
            after.valuesList shouldBe before.valuesList
            after.gapsList.map { it.kind } shouldBe before.gapsList.map { it.kind }
        }

        "…but it is not silent about it: the refused candidates are in the round's log" {
            val state =
                resolve(
                    RoundFuzzy(
                        broadMisses = setOf("501001"),
                        answers = mapOf("501001" to listOf(member("5AU 5001", 0.62), member("7AX 0800", 0.55))),
                    ),
                )
            val round = state.rungLogList.single { it.rung == LookupRounds.RUNG }
            round.bindingsAdded shouldBe 0
            round.hypothesesList.map { it.ref } shouldContainExactly
                listOf("M:md.dimension.Account.code#5AU 5001", "M:md.dimension.Account.code#7AX 0800")
            round.hypothesesList.all { it.proposingRung == LookupRounds.RUNG }.shouldBeTrue()
            round.hypothesesList.all { it.span.text == "501001" }.shouldBeTrue()

            // and NOT in the lattice: no attribution, no binding, nothing a reader could mistake
            // for evidence the gate accepted.
            val value = state.valuesList.single { it.span.text == "501001" }
            value.attributionsCount shouldBe 0
        }

        "the still-open gap carries what was tried — discharging P2.1.T6's `hypotheses_tried`" {
            val state =
                resolve(
                    RoundFuzzy(
                        broadMisses = setOf("501001"),
                        answers = mapOf("501001" to listOf(member("5AU 5001", 0.62))),
                    ),
                )
            val gap = state.gapsList.single { it.span.text == "501001" }
            gap.kind shouldBe GapKind.GAP_KIND_G4_METHOD_MISS
            // This is what a ladder reads to decide whether escalating is worth it: the cheapest
            // rung has already been here, and this is what it found.
            gap.hypothesesTriedList.map { it.ref } shouldContainExactly listOf("M:md.dimension.Account.code#5AU 5001")
            gap.hypothesesTriedList.single().proposingRung shouldBe LookupRounds.RUNG
        }

        "every annotation the broad pass made survives the rounds, byte for byte" {
            val withoutRounds =
                resolve(
                    RoundFuzzy(broadMisses = setOf("účtu")),
                    config = LookupRoundConfig.DEFAULT.copy(budgetMs = 0),
                )
            val withRounds =
                resolve(
                    RoundFuzzy(
                        broadMisses = setOf("účtu"),
                        answers = mapOf("účtu" to listOf(declared("md.dimension.Account"))),
                    ),
                )

            // Additive on both layers: the round-fed lattice CONTAINS everything the broad pass
            // produced, and the one span it filled is the only difference.
            withRounds.mentionsList.map { it.id } shouldContainAll withoutRounds.mentionsList.map { it.id }
            withRounds.valuesList.map { it.span.text } shouldContainAll withoutRounds.valuesList.map { it.span.text }
            withoutRounds.mentionsList
                .filter { it.bindingsCount > 0 }
                .forEach { before ->
                    val after = withRounds.mentionsList.single { it.id == before.id }
                    after.bindingsList shouldContainAll before.bindingsList
                }
            // gaps only ever close, never open
            (withRounds.gapsCount <= withoutRounds.gapsCount).shouldBeTrue()
        }
    }) {
    private companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val parser: JsonFormat.Parser = JsonFormat.parser().ignoringUnknownFields()
        private val CASE: JsonObject = loadJson("/lattice/h1-cs.case.json")

        private fun resolve(
            fuzzy: FuzzyClient,
            config: LookupRoundConfig = LookupRoundConfig.DEFAULT,
        ): ResolutionState {
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
                    .setConversationId("rv-p2-3-mono")
                    .setFresh(
                        FreshQuestion
                            .newBuilder()
                            .setText(CASE["text"]!!.jsonPrimitive.content)
                            .setLocale(CASE["locale"]!!.jsonPrimitive.content),
                    ).setRegistry(merge(CASE["registry"]!!.jsonObject, Registry.newBuilder()).build())
                    .build()
            return runBlocking { pipeline.resolve(request) }.resolutionState
        }

        private fun member(
            id: String,
            score: Double,
        ): FuzzyMatch =
            FuzzyMatch
                .newBuilder()
                .setCandidateId(id)
                .setCandidate(id)
                .setScore(score)
                .setCategory("md.dimension.Account.code")
                .setSource(SourceTag.MEMBER)
                .build()

        private fun declared(targetRef: String): FuzzyMatch =
            FuzzyMatch
                .newBuilder()
                .setCandidateId("lex:$targetRef")
                .setCandidate(targetRef)
                .setScore(1.0)
                .setCategory(targetRef)
                .setSource(SourceTag.DECLARED)
                .setTargetRef(targetRef)
                .setMatchMethod("EXACT")
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
                    MonotonicityTest::class.java.getResource(resource)?.readText()
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

    private class RoundFuzzy(
        private val broadMisses: Set<String>,
        private val answers: Map<String, List<FuzzyMatch>> = emptyMap(),
    ) : FuzzyClient {
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

        override suspend fun lookup(request: LookupRequest): LookupResponse =
            LookupResponse.newBuilder().addAllCandidates(answers[request.term].orEmpty()).build()

        override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()
    }
}
