// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.fuzzy.v1.Provenance
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Capability
import org.tatrman.nlp.v1.EngineVersion
import org.tatrman.nlp.v1.NerEntity
import org.tatrman.nlp.v1.NlpOp
import org.tatrman.nlp.v1.StatusResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.client.NlpClient
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.EntityType
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.UniversalEntityType
import kotlinx.coroutines.runBlocking

/**
 * RG-P5.S1.T5 — the deterministic pipeline end-to-end at the component level.
 * The hero resolves with ZERO LLM: nlp + fuzzy are the only upstreams, injected
 * here as fakes so the Q-20 shapes flow through parse → extractUniversal →
 * proposeDomainSpans → ONE gateSpans BatchMatch → assemble.
 */
class ResolverPipelineTest :
    StringSpec({

        val branch =
            EntityType
                .newBuilder()
                .setRef(
                    "er.branch",
                ).addCategories("er.branch")
                .addAnchors("pobočka")
                .build()
        val product =
            EntityType
                .newBuilder()
                .setRef("er.product")
                .addCategories("er.product")
                .build()
        val registryOverride =
            Registry
                .newBuilder()
                .addEntityTypes(
                    branch,
                ).addEntityTypes(product)
                .addLocales("cs")
                .setSnapshotHash("snap-hero")
                .build()

        fun freshRequest() =
            ResolveRequest
                .newBuilder()
                .setConversationId("c-1")
                .setFresh(FreshQuestion.newBuilder().setText(HERO_TEXT).setLocale("cs"))
                .setRegistry(registryOverride)
                .build()

        val emptyDefault = SnapshotRegistry(StubRegistrySource(DeclaredVocabulary(), ""), ResolverThresholds.LIVE)
        val codec = ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1")

        "hero: Octavie (MEMBER) + pražských pobočkách (VOCABULARY) bind, DATE universal, ZERO LLM" {
            val fuzzy = FakeFuzzy(HERO_BATCH)
            val pipeline = ResolverPipeline(FakeNlp(heroParse()), fuzzy, emptyDefault, emptyMap(), codec)

            val resp = runBlocking { pipeline.resolve(freshRequest()) }

            resp.hasResolution().shouldBeTrue()
            resp.hasParse().shouldBeTrue() // E-T1 passthrough
            fuzzy.calls shouldBe 1 // ONE BatchMatch (B-T1)

            val bindings = resp.resolution.bindingsList
            // universal DATE binding for `poslední fiskální čtvrtletí`
            val universal = bindings.single { it.hasUniversal() }
            universal.universal.entityType shouldBe UniversalEntityType.DATE

            val branchBinding = bindings.single { it.hasDomain() && it.domain.entityTypeRef == "er.branch" }
            branchBinding.domain.candidatesList
                .single()
                .targetRef shouldBe "er.branch#term-pobocka"
            branchBinding.provenance.vocabularySource shouldBe "VOCABULARY"

            val productBinding = bindings.single { it.hasDomain() && it.domain.entityTypeRef == "er.product" }
            productBinding.domain.resolvedId shouldBe "p-octavia"
            productBinding.provenance.snapshotHash shouldBe "snap-hero"

            // capability echo reflects the fake matrix (F-T3 honesty)
            resp.capabilities.language shouldBe "cs"
            resp.capabilities.csNer.shouldBeTrue()
            resp.capabilities.depParse.shouldBeTrue()
        }

        "an empty request (neither fresh nor resume) returns an empty resolution, not a crash" {
            val pipeline =
                ResolverPipeline(FakeNlp(heroParse()), FakeFuzzy(HERO_BATCH), emptyDefault, emptyMap(), codec)
            val resp = runBlocking { pipeline.resolve(ResolveRequest.newBuilder().setConversationId("c-2").build()) }
            resp.hasResolution().shouldBeTrue()
            resp.resolution.bindingsList shouldBe emptyList()
        }

        "resume with a signed pin binds at confidence 1.0 with NO re-fuzzy (RS-26)" {
            val fuzzy = FakeFuzzy(HERO_BATCH)
            val pipeline = ResolverPipeline(FakeNlp(heroParse()), fuzzy, emptyDefault, emptyMap(), codec)
            val token =
                codec.sign(
                    org.tatrman.resolver.token.ResumePayload(
                        conversationId = "c-3",
                        parseRef = "trace-hero",
                        options =
                            listOf(
                                org.tatrman.resolver.token
                                    .ResumeOption("M:qt-orlak", "QT ORLAK", resolvedId = "qt-orlak"),
                            ),
                        issuedAt = 1_752_000_000,
                        keyId = "k1",
                    ),
                )
            val req =
                ResolveRequest
                    .newBuilder()
                    .setConversationId("c-3")
                    .setResume(
                        org.tatrman.resolver.v1.ResumeAnswer
                            .newBuilder()
                            .setToken(
                                token,
                            ).setSelectedOptionId("M:qt-orlak"),
                    ).build()

            val resp = runBlocking { pipeline.resolve(req) }
            resp.resolution.confidence shouldBe 1.0
            resp.resolution.bindingsList
                .single()
                .domain.resolvedId shouldBe "qt-orlak"
            fuzzy.calls shouldBe 0 // NO re-fuzzy on a signed pin
        }

        "a tampered resume token is refused (RG-RES-002)" {
            val pipeline =
                ResolverPipeline(FakeNlp(heroParse()), FakeFuzzy(HERO_BATCH), emptyDefault, emptyMap(), codec)
            val good =
                codec.sign(
                    org.tatrman.resolver.token.ResumePayload(
                        "c-4",
                        "p",
                        listOf(
                            org.tatrman.resolver.token
                                .ResumeOption("o1", "L"),
                        ),
                        1,
                        "k1",
                    ),
                )
            // graft a signature computed over a DIFFERENT payload → guaranteed mismatch.
            val otherSig =
                codec
                    .sign(
                        org.tatrman.resolver.token.ResumePayload(
                            "c-4",
                            "p",
                            listOf(
                                org.tatrman.resolver.token
                                    .ResumeOption("o1", "L"),
                            ),
                            999,
                            "k1",
                        ),
                    ).substringAfter('.')
            val tampered = good.substringBefore('.') + "." + otherSig
            val req =
                ResolveRequest
                    .newBuilder()
                    .setResume(
                        org.tatrman.resolver.v1.ResumeAnswer
                            .newBuilder()
                            .setToken(tampered)
                            .setSelectedOptionId("o1"),
                    ).build()
            io.kotest.assertions.throwables.shouldThrow<org.tatrman.resolver.token.ResumeTokenException> {
                runBlocking { pipeline.resolve(req) }
            }
        }
    }) {
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
                        .setLanguage(
                            "cs",
                        ).setOp(NlpOp.NER)
                        .setEngine("nametag3")
                        .setModelVersion("cnec2.0"),
                ).addCapabilities(
                    Capability
                        .newBuilder()
                        .setLanguage(
                            "cs",
                        ).setOp(NlpOp.DEP_PARSE)
                        .setEngine("udpipe")
                        .setModelVersion("pdt-2.5"),
                ).build()
    }

    private class FakeFuzzy(
        private val response: BatchMatchResponse,
    ) : FuzzyClient {
        var calls = 0

        override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse {
            calls++
            return response
        }

        override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()
    }

    companion object {
        private const val HERO_TEXT =
            "Kolik jsme utržili za Octavie v pražských pobočkách za poslední fiskální čtvrtletí?"

        // Positional to proposeDomainSpans output: [0]=`pražských pobočkách`, [1]=`Octavie`.
        private val HERO_BATCH: BatchMatchResponse =
            BatchMatchResponse
                .newBuilder()
                .addResults(
                    fmr(
                        fm(
                            "term-pobocka",
                            "pobočka",
                            0.88,
                            "er.branch",
                            SourceTag.VOCABULARY,
                            "er.branch#term-pobocka",
                        ),
                    ),
                ).addResults(
                    fmr(fm("p-octavia", "Škoda Octavia", 0.95, "er.product", SourceTag.MEMBER, "")),
                ).build()

        private fun heroParse(): AnalyzeResponse =
            AnalyzeResponse
                .newBuilder()
                .setLanguage("cs")
                .setDetectedLanguage("cs")
                .setTraceId("trace-hero")
                .addAllTokens(
                    listOf(
                        tok("Kolik", 0, 5, "kolik", "ADV", 3, "advmod"),
                        tok("jsme", 6, 10, "být", "AUX", 3, "aux"),
                        tok("utržili", 11, 18, "utržit", "VERB", 0, "root"),
                        tok("za", 19, 21, "za", "ADP", 5, "case"),
                        tok("Octavie", 22, 29, "Octavie", "PROPN", 3, "obl"),
                        tok("v", 30, 31, "v", "ADP", 8, "case"),
                        tok("pražských", 32, 41, "pražský", "ADJ", 8, "amod"),
                        tok("pobočkách", 42, 51, "pobočka", "NOUN", 3, "obl"),
                        tok("za", 52, 54, "za", "ADP", 12, "case"),
                        tok("poslední", 55, 63, "poslední", "ADJ", 12, "amod"),
                        tok("fiskální", 64, 72, "fiskální", "ADJ", 12, "amod"),
                        tok("čtvrtletí", 73, 82, "čtvrtletí", "NOUN", 3, "obl"),
                        tok("?", 82, 83, "?", "PUNCT", 3, "punct"),
                    ),
                ).addEntities(
                    NerEntity
                        .newBuilder()
                        .setText(
                            "poslední fiskální čtvrtletí",
                        ).setCharStart(55)
                        .setCharEnd(82)
                        .setLabel("DATE")
                        .setSourceEngine("nametag3")
                        .build(),
                ).addUsed(
                    EngineVersion
                        .newBuilder()
                        .setOp(
                            "NER",
                        ).setEngine("nametag3")
                        .setModel("cnec2.0")
                        .setModelVersion("240830")
                        .build(),
                ).build()

        private fun tok(
            text: String,
            start: Int,
            end: Int,
            lemma: String,
            upos: String,
            depHead: Int,
            depRelation: String,
        ): Token =
            Token
                .newBuilder()
                .setText(
                    text,
                ).setCharStart(
                    start,
                ).setCharEnd(end)
                .setLemma(lemma)
                .setUpos(upos)
                .setDepHead(depHead)
                .setDepRelation(depRelation)
                .build()

        private fun fm(
            id: String,
            candidate: String,
            score: Double,
            category: String,
            source: SourceTag,
            targetRef: String,
        ): FuzzyMatch {
            val b =
                FuzzyMatch
                    .newBuilder()
                    .setCandidateId(id)
                    .setCandidate(candidate)
                    .setScore(score)
                    .setCategory(category)
                    .setSource(source)
                    .setProvenance(
                        Provenance
                            .newBuilder()
                            .setProducer("fuzzy")
                            .setMethod("TATRMAN")
                            .setRawScore(score),
                    )
            if (targetRef.isNotBlank()) b.targetRef = targetRef
            return b.build()
        }

        private fun fmr(vararg matches: FuzzyMatch): FuzzyMatchResponse =
            FuzzyMatchResponse.newBuilder().addAllMatches(matches.toList()).build()
    }
}
