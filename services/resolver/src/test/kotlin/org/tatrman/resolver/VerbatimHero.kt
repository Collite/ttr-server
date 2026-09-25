// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import kotlinx.coroutines.runBlocking
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
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.fuzzy.v1.TargetClass as FuzzyTargetClass

/**
 * LP-P1 — *"Ukaž dodací místa začínající na "Pelex""*, resolved by the REAL pipeline.
 *
 * Shared by `VerbatimLatticeTest` (what the lattice says) and `VerbatimClosedToRungsTest` (what a
 * rung may then do with it), because the second is about a lattice the first produced and a
 * hand-built stand-in would let each test choose the facts the other is asserting.
 */
internal object VerbatimHero {
    const val TEXT: String = "Ukaž dodací místa začínající na \"Pelex\""

    /** Offsets of the literal in [TEXT], delimiters included. */
    const val LITERAL_START: Int = 32
    const val LITERAL_END: Int = 39

    /**
     * The store entity, with the model's mention facet declared.
     *
     * On the per-request Registry override, which is the channel a fixture can state facts
     * through. Since LP-P2b the snapshot channel carries the same facet — that half is asserted
     * where it belongs, through the real packer, in `LexiconArchiveRegistrySourceTest`.
     */
    val STORE: EntityType =
        EntityType
            .newBuilder()
            .setRef("er.entity.store")
            .addCategories("er.entity.store")
            .addAnchors("dodací místo")
            .setObjectKind("entity")
            .setNameAttributeRef("er.entity.store.name")
            .build()

    fun registryOf(vararg types: EntityType): Registry =
        Registry
            .newBuilder()
            .addAllEntityTypes(types.toList())
            .addLocales("cs")
            .setSnapshotHash("snap-lp")
            .build()

    fun request(
        registry: Registry,
        questionText: String = TEXT,
    ): ResolveRequest =
        ResolveRequest
            .newBuilder()
            .setConversationId("c-lp")
            .setFresh(FreshQuestion.newBuilder().setText(questionText).setLocale("cs"))
            .setRegistry(registry)
            .build()

    fun pipeline(
        fuzzy: FuzzyClient,
        parse: AnalyzeResponse = parse(),
    ): ResolverPipeline =
        ResolverPipeline(
            FakeNlp(parse),
            fuzzy,
            SnapshotRegistry(StubRegistrySource(DeclaredVocabulary(), ""), ResolverThresholds.LIVE),
            emptyMap(),
            ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1"),
        )

    fun resolve(
        registry: Registry = registryOf(STORE),
        fuzzy: FakeFuzzy = FakeFuzzy(mapOf("dodací místa" to listOf(declared("er.entity.store")))),
        parse: AnalyzeResponse = parse(),
        questionText: String = TEXT,
    ): Pair<FakeFuzzy, ResolveResponse> =
        fuzzy to runBlocking { pipeline(fuzzy, parse).resolve(request(registry, questionText)) }

    /**
     * The delimiters tokenised apart, which is what MorphoDiTa and Stanza both do. `Pelex` hangs
     * off `začínající`, which hangs off `místa`: two hops, inside §2.1's three.
     */
    fun parse(): AnalyzeResponse =
        AnalyzeResponse
            .newBuilder()
            .setLanguage("cs")
            .setDetectedLanguage("cs")
            .addTokens(token("Ukaž", 0, 4, "ukázat", "VERB", 0, "root"))
            .addTokens(token("dodací", 5, 11, "dodací", "ADJ", 3, "amod"))
            .addTokens(token("místa", 12, 17, "místo", "NOUN", 1, "obj"))
            .addTokens(token("začínající", 18, 29, "začínající", "ADJ", 3, "amod"))
            .addTokens(token("na", 30, 32, "na", "ADP", 7, "case"))
            .addTokens(token("\"", 32, 33, "\"", "PUNCT", 7, "punct"))
            .addTokens(token("Pelex", 33, 38, "Pelex", "PROPN", 4, "obl"))
            .addTokens(token("\"", 38, 39, "\"", "PUNCT", 7, "punct"))
            .build()

    fun token(
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
            .setText(text)
            .setCharStart(start)
            .setCharEnd(end)
            .setLemma(lemma)
            .setUpos(upos)
            .setDepHead(depHead)
            .setDepRelation(depRelation)
            .build()

    fun declared(targetRef: String): FuzzyMatch =
        FuzzyMatch
            .newBuilder()
            .setCandidateId("lex:$targetRef")
            .setCandidate(targetRef)
            .setScore(1.0)
            .setCategory(targetRef)
            .setSource(SourceTag.DECLARED)
            .setTargetRef(targetRef)
            .setTargetClass(FuzzyTargetClass.TARGET_CLASS_MODEL_OBJECT)
            .setProvenance(Provenance.newBuilder().setProducer("lex-matcher").setMethod("TATRMAN"))
            .build()

    /**
     * LP-P2b — a `pred:` row, as `lex-matcher` serves one off the stdlib slice.
     *
     * [form] is the row's `candidate` — the authored FORM the matcher found (*začínající na*), not
     * the ref: that is what a real matcher returns, and since review-103 F1 the resolver reads it
     * to tell a whole form from a fragment. (This fake used to put the REF there, a shape no
     * producer emits.) `EXACT` by default, because every form in the slice is EXACT since
     * ruling 1; the fake does not match, it answers, so the method only has to be the truth about
     * the row a real matcher would have returned.
     */
    fun predicate(
        ref: String,
        form: String,
        method: String = "EXACT",
    ): FuzzyMatch =
        FuzzyMatch
            .newBuilder()
            .setCandidateId("lex:$ref:$form")
            .setCandidate(form)
            .setScore(1.0)
            .setCategory(ref)
            .setSource(SourceTag.DECLARED)
            .setTargetRef(ref)
            .setMatchMethod(method)
            .setTargetClass(FuzzyTargetClass.TARGET_CLASS_STRING_PREDICATE)
            .setProvenance(Provenance.newBuilder().setProducer("lex-matcher").setMethod("TATRMAN"))
            .build()

    fun member(
        id: String,
        category: String,
    ): FuzzyMatch =
        FuzzyMatch
            .newBuilder()
            .setCandidateId(id)
            .setCandidate(id)
            .setScore(1.0)
            .setCategory(category)
            .setSource(SourceTag.MEMBER)
            .setProvenance(Provenance.newBuilder().setProducer("lex-matcher").setMethod("TATRMAN"))
            .build()

    class FakeNlp(
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

    class FakeFuzzy(
        private val byQuery: Map<String, List<FuzzyMatch>>,
    ) : FuzzyClient {
        var lastRequest: BatchMatchRequest? = null

        /** Every lookup this double was asked to make — the rung-facing half of "never asked". */
        val lookups: MutableList<String> = mutableListOf()

        override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse {
            lastRequest = request
            val builder = BatchMatchResponse.newBuilder()
            for (span in request.spansList) {
                val scoped = span.categoriesList.toSet()
                builder.addResults(
                    FuzzyMatchResponse
                        .newBuilder()
                        .addAllMatches(
                            byQuery[span.query].orEmpty().filter { scoped.isEmpty() || it.category in scoped },
                        ).setMatchedAlgorithm("TATRMAN"),
                )
            }
            return builder.build()
        }

        override suspend fun lookup(request: org.tatrman.fuzzy.v1.LookupRequest): org.tatrman.fuzzy.v1.LookupResponse {
            lookups += request.term
            return org.tatrman.fuzzy.v1.LookupResponse
                .newBuilder()
                .addAllCandidates(byQuery[request.term].orEmpty())
                .build()
        }

        override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()
    }
}
