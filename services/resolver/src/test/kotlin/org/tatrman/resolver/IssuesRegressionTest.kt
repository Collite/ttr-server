// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import com.google.protobuf.util.JsonFormat
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
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
import org.tatrman.resolver.v1.Disposition
import org.tatrman.resolver.v1.EvidenceClass
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.ResolveRequest

/**
 * RV-P2.5.T5 — the two `issues.md` failures of **2026-07-28**, as named regression cases.
 *
 * The case names are `issues-260728-1` and `issues-260728-2` and they are meant to be **grepped**:
 * when someone asks "did we ever fix the resolver looking in the wrong entity", this is what the
 * search should land on. The whole class of failure — *guess rather than admit* — is what the RV
 * effort exists to retire, and a phase gate that could not name the original complaint would be
 * asserting its own vocabulary rather than the user's.
 *
 * Both cases assert **both directions**, because in each the interesting failure is not a missing
 * binding but a *present wrong one*: the old resolver did not fail to answer, it answered badly.
 */
class IssuesRegressionTest :
    StringSpec({

        "issues-260728-1: `501001` binds účet.kód and offers NO středisko candidate at any strength" {
            // The live 2026-07-28 log, verbatim: `501001` reached středisko rows and the top hit was
            // `5AU 5001@0.667`. Two independent fixes had to hold for this to stop, and BOTH are
            // asserted here rather than one standing in for the other:
            //
            //   1. SCOPE (P2.1, Q-20 anchored proposal) — the user said *účtu*, so the literal is
            //      only ever asked about in the account categories. The středisko row below is
            //      offered in `er.stredisko.kod` and the fake is category-scoped like the real
            //      matcher, so a regression in scoping alone would let it through.
            //   2. CLASS (P2.2, RV-14) — and if it did get through, 0.667 is WEAK, and WEAK never
            //      binds. Asserted separately in `H1GatePairTest`, where the same row is placed
            //      INSIDE the correctly-scoped index so the class floor is the only thing left.
            val state =
                resolve(
                    "h1-cs",
                    extraRows =
                        mapOf(
                            "501001" to
                                listOf(
                                    member("5AU 5001", "er.stredisko.kod", 0.667),
                                    member("7AX 0800", "er.stredisko.kod", 0.500),
                                ),
                        ),
                )

            val code = state.valuesList.single { it.span.text == "501001" }

            // direction one — it MUST bind the account code
            val bound = code.attributionsList.single()
            bound.attributeRef shouldBe "md.dimension.Account.code"
            bound.binding.ref shouldBe "md.dimension.Account.code#501001"
            bound.binding.evidenceClass shouldBe EvidenceClass.EVIDENCE_CLASS_EXACT

            // direction two — no středisko candidate anywhere in the lattice, at any strength
            state.valuesList
                .flatMap { it.attributionsList }
                .none { it.attributeRef.contains("stredisko") || it.binding.ref.contains("5AU") }
                .shouldBeTrue()
            state.mentionsList
                .flatMap { it.bindingsList }
                .none { it.ref.contains("stredisko") }
                .shouldBeTrue()
            state.gapsList shouldBe emptyList()
        }

        "issues-260728-2: `Praze` stays an UNRESOLVED G3 and `čerpacích stanic` an empty G1" {
            // "Not admitting not knowing the entity". The old resolver offered
            // `db.dbo.STREDISKO.KOD` for *Praze* because recall was preferred everywhere; P-3
            // says recall is kept and nothing is forced, which means the honest answer is a TYPED
            // gap rather than a binding or a silence.
            val state = resolve("h2-cs")

            val praze = state.valuesList.single { it.span.text == "Praze" }
            praze.attributionsCount shouldBe 0
            val g3 = state.gapsList.single { it.valueId == praze.id }
            g3.kind shouldBe GapKind.GAP_KIND_G3_UNATTRIBUTED
            // P-3: recall is kept — the span is still IN the lattice as a grounded location hint,
            // and the disposition says the core has not settled it, not that it ignored it.
            g3.disposition shouldBe Disposition.DISPOSITION_UNRESOLVED
            praze.grounding.kind shouldBe "LOCATION"

            val stanic = state.mentionsList.single { it.span.text == "čerpacích stanic" }
            // A mention with zero bindings is the sentence "I do not know this word" — which the
            // pre-RV core had no way to say at all, and which is why RV-P4 waited for the lattice.
            stanic.bindingsCount shouldBe 0
            state.gapsList.single { it.mentionId == stanic.id }.kind shouldBe GapKind.GAP_KIND_G1_UNBOUND

            // and nothing was forced anywhere else either
            state.mentionsList
                .flatMap { it.bindingsList }
                .none { it.ref.contains("stredisko") }
                .shouldBeTrue()
        }
    }) {
    private companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val parser: JsonFormat.Parser = JsonFormat.parser().ignoringUnknownFields()

        private fun resolve(
            fixture: String,
            extraRows: Map<String, List<FuzzyMatch>> = emptyMap(),
        ): ResolutionState {
            val case = loadJson("/lattice/$fixture.case.json")
            val fuzzy = FixtureFuzzy(case, extraRows)
            val pipeline =
                ResolverPipeline(
                    FakeNlp(
                        merge(
                            loadJson("/lattice/${case["parseFile"]!!.jsonPrimitive.content}"),
                            AnalyzeResponse.newBuilder(),
                        ).build(),
                    ),
                    fuzzy,
                    SnapshotRegistry(StubRegistrySource(DeclaredVocabulary(), ""), ResolverThresholds.LIVE),
                    emptyMap(),
                    ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1"),
                    // Rounds off: these cases are about what the BROAD pass does with a bad
                    // candidate, and a round that later closed the gap would blur the assertion.
                    lookupRounds = LookupRounds(fuzzy, LookupRoundConfig.DISABLED),
                )
            return runBlocking {
                pipeline.resolve(
                    ResolveRequest
                        .newBuilder()
                        .setConversationId(fixture)
                        .setFresh(
                            FreshQuestion
                                .newBuilder()
                                .setText(case["text"]!!.jsonPrimitive.content)
                                .setLocale(case["locale"]!!.jsonPrimitive.content),
                        ).setRegistry(merge(case["registry"]!!.jsonObject, Registry.newBuilder()).build())
                        .build(),
                )
            }.resolutionState
        }

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
                    IssuesRegressionTest::class.java.getResource(resource)?.readText()
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
     * The fixture vocabulary plus whatever a case wants to add. **Category-scoped per slot, like
     * the real matcher** — that is what makes the scoping half of case 1 a real assertion rather
     * than a formality: a row in a category the slot never asked for must not come back.
     */
    private class FixtureFuzzy(
        case: JsonObject,
        extraRows: Map<String, List<FuzzyMatch>>,
    ) : FuzzyClient {
        private val byQuery: Map<String, List<FuzzyMatch>> =
            case["matcher"]!!
                .jsonObject["byQuery"]!!
                .jsonObject
                .mapValues { (_, matches) ->
                    matches.jsonArray.map { merge(it.jsonObject, FuzzyMatch.newBuilder()).build() }
                }.let { base ->
                    base + extraRows.mapValues { (query, rows) -> base[query].orEmpty() + rows }
                }

        override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse {
            val builder = BatchMatchResponse.newBuilder()
            for (span in request.spansList) {
                val scoped = span.categoriesList.toSet()
                builder.addResults(
                    FuzzyMatchResponse.newBuilder().addAllMatches(
                        byQuery[span.query].orEmpty().filter { scoped.isEmpty() || it.category in scoped },
                    ),
                )
            }
            return builder.build()
        }

        override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()
    }
}
