// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
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
import org.tatrman.nlp.v1.NerEntity
import org.tatrman.nlp.v1.NlpOp
import org.tatrman.nlp.v1.StatusResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.client.NlpClient
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.Literals
import org.tatrman.resolver.pipeline.QuoteScanner
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.pipeline.SpanProposal
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.EntityType
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ValueKind
import org.tatrman.fuzzy.v1.TargetClass as FuzzyTargetClass

/**
 * LP-P1·S2 — *"Ukaž dodací místa začínající na "Pelex""*, the question this effort started from.
 *
 * GI-2: the resolver kept proposing store MEMBERS for the quoted word, so the user's one way of
 * saying *take this string as typed* produced a clarification about which shop they meant. The
 * fix is structural rather than a threshold: a quoted span is not proposed at all, by any of the
 * six sources, and reaches the lattice as its own kind of value.
 *
 * Driven through the whole pipeline, not through `LatticeAssembler` alone, because the claim has
 * two halves and they live in different objects — *one VERBATIM value* is the assembler's, and
 * *zero candidates for Pelex* is span proposal's. A unit test of either half would pass while the
 * other regressed.
 */
class VerbatimLatticeTest :
    StringSpec({

        val text = "Ukaž dodací místa začínající na \"Pelex\""

        // The estate declares the mention facet: `semantics { name: nazev }` on the store entity,
        // resolved to a full attribute ref. ⚑ It travels on the per-request Registry override
        // because the snapshot channel has no field for it yet (⚑LPQ-5) — an archive-fed estate
        // gets "" and the literal lands headless, which the last case here pins.
        val store =
            EntityType
                .newBuilder()
                .setRef("er.entity.store")
                .addCategories("er.entity.store")
                .addAnchors("dodací místo")
                .setObjectKind("entity")
                .setNameAttributeRef("er.entity.store.name")
                .build()

        fun registryOf(vararg types: EntityType) =
            Registry
                .newBuilder()
                .addAllEntityTypes(types.toList())
                .addLocales("cs")
                .setSnapshotHash("snap-lp")
                .build()

        fun request(
            registry: Registry,
            questionText: String,
        ) = ResolveRequest
            .newBuilder()
            .setConversationId("c-lp")
            .setFresh(FreshQuestion.newBuilder().setText(questionText).setLocale("cs"))
            .setRegistry(registry)
            .build()

        val emptyDefault = SnapshotRegistry(StubRegistrySource(DeclaredVocabulary(), ""), ResolverThresholds.LIVE)
        val codec = ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1")

        fun resolve(
            registry: Registry,
            fuzzy: FakeFuzzy = FakeFuzzy(mapOf("dodací místa" to listOf(declared("er.entity.store")))),
            parse: AnalyzeResponse = heroParse(),
            questionText: String = text,
        ) = fuzzy to
            runBlocking {
                ResolverPipeline(FakeNlp(parse), fuzzy, emptyDefault, emptyMap(), codec)
                    .resolve(request(registry, questionText))
            }

        "the hero: ONE verbatim value on the store's name, and nothing was looked up for Pelex" {
            val (fuzzy, response) = resolve(registryOf(store))

            val verbatim = response.resolutionState.valuesList.single()
            verbatim.kind shouldBe ValueKind.VALUE_KIND_VERBATIM
            verbatim.verbatimText shouldBe "Pelex"
            // The SPAN is what was typed — quotes included. The TEXT is §1.4's content. Both, and
            // deliberately not one: the span is what an echo underlines and what a re-gate refuses
            // hypotheses on, the text is what travels as a bound parameter.
            verbatim.span.start shouldBe 32
            verbatim.span.end shouldBe 39
            verbatim.span.text shouldBe "\"Pelex\""
            verbatim.attributionsList.map { it.attributeRef } shouldContainExactly listOf("er.entity.store.name")
            // §2: no binding rides with it. There is no member to bind to and no layer that
            // produced it — the user quoted it.
            verbatim.attributionsList
                .single()
                .hasBinding()
                .shouldBeFalse()
            // §2.2: absent, not blank. The default predicate (contains, for a name) applies, and
            // `pred:` arrives in LP-P2.
            verbatim.hasPredicateRef().shouldBeFalse()

            // The other half, and the reason this runs through the pipeline: the matcher was never
            // asked about Pelex. Not "asked and refused" — never asked.
            // One BatchMatch, two slots for the same phrase — the gate's and the grounding
            // trigger annotation's (RV-P1.6.T6). What matters here is what is NOT in it.
            val queries = fuzzy.lastRequest!!.spansList.map { it.query }
            queries.none { it.contains("Pelex") } shouldBe true
            queries.distinct() shouldContainExactly listOf("dodací místa")
        }

        "no gap is opened for a literal the question said which column belongs to" {
            val (_, response) = resolve(registryOf(store))

            // Attributed ⇒ nothing to ask about. G1/G2 in particular would be a bug of a specific
            // kind: they are the ask-the-user gaps, and asking which shop `"Pelex"` means is
            // exactly what the quoting existed to prevent.
            response.resolutionState.gapsList
                .filter { it.span.start == 32 }
                .shouldBeEmpty()
            response.resolutionState.valuesList
                .single()
                .anchorMentionId
                .shouldBe(
                    response.resolutionState.mentionsList
                        .first { it.span.text.contains("místa") }
                        .id,
                )
        }

        "an estate that declares no name attribute gets G3, not a guess" {
            // The archive-fed case today (⚑LPQ-5): the head is found, but the model has not said
            // which of its columns carries the name. Emitting the value with zero attributions is
            // the honest answer — and `Gaps` types it G3, "nothing scoped it", which is the record
            // the ladder acts on. The alternative, picking a column, is the failure mode this
            // whole effort exists to retire.
            val silent = store.toBuilder().clearNameAttributeRef().build()

            val (_, response) = resolve(registryOf(silent))

            val verbatim = response.resolutionState.valuesList.single()
            verbatim.kind shouldBe ValueKind.VALUE_KIND_VERBATIM
            verbatim.verbatimText shouldBe "Pelex"
            verbatim.attributionsList.shouldBeEmpty()
            response.resolutionState.gapsList
                .single { it.valueId == verbatim.id }
                .kind shouldBe GapKind.GAP_KIND_G3_UNATTRIBUTED
        }

        "a quoted date is a string, not a date: the universal layer skips a literal" {
            // §2.4. `"12.5.2024"` in quotes asks for rows whose name contains those characters.
            // Grounded as a DATE it would become a half-open interval on a period column — a
            // different question, answered confidently, which is the worst of the two failures.
            val quoted = "zákazník \"12.5.2024\""
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .setLanguage("cs")
                    .setDetectedLanguage("cs")
                    .addTokens(token("zákazník", 0, 8, "zákazník", "NOUN", 0, "root"))
                    .addTokens(token("\"", 9, 10, "\"", "PUNCT", 1, "punct"))
                    .addTokens(token("12.5.2024", 10, 19, "12.5.2024", "NUM", 1, "nmod"))
                    .addTokens(token("\"", 19, 20, "\"", "PUNCT", 1, "punct"))
                    .addEntities(
                        NerEntity
                            .newBuilder()
                            .setText("12.5.2024")
                            .setLabel("DATE")
                            .setCharStart(10)
                            .setCharEnd(19)
                            .setNormalizedValue("2024-05-12")
                            .setSourceEngine("nametag3"),
                    ).build()
            val customer =
                EntityType
                    .newBuilder()
                    .setRef("er.entity.customer")
                    .addCategories("er.entity.customer")
                    .addAnchors("zákazník")
                    .setObjectKind("entity")
                    .setNameAttributeRef("er.entity.customer.name")
                    .build()

            val (_, response) =
                resolve(
                    registryOf(customer),
                    FakeFuzzy(mapOf("zákazník" to listOf(declared("er.entity.customer")))),
                    parse,
                    quoted,
                )

            val value = response.resolutionState.valuesList.single()
            value.kind shouldBe ValueKind.VALUE_KIND_VERBATIM
            value.verbatimText shouldBe "12.5.2024"
            value.hasGrounding().shouldBeFalse()
            response.resolution.bindingsList.none { it.hasUniversal() } shouldBe true
        }

        "span proposal proposes nothing inside a literal, whatever the source would have been" {
            // The invariant behind the hero, asserted directly and over every source at once:
            // the anchor index (a declared anchor word INSIDE quotes), proper nouns (b), NER (c)
            // and the n-gram floor (d) all have their own reason to reach for these tokens.
            val quoted = "zákazník \"dodací místo Pelex\""
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .setLanguage("cs")
                    .addTokens(token("zákazník", 0, 8, "zákazník", "NOUN", 0, "root"))
                    .addTokens(token("\"", 9, 10, "\"", "PUNCT", 1, "punct"))
                    .addTokens(token("dodací", 10, 16, "dodací", "ADJ", 4, "amod"))
                    .addTokens(token("místo", 17, 22, "místo", "NOUN", 1, "nmod"))
                    .addTokens(token("Pelex", 23, 28, "Pelex", "PROPN", 4, "flat"))
                    .addTokens(token("\"", 28, 29, "\"", "PUNCT", 1, "punct"))
                    .addEntities(
                        NerEntity
                            .newBuilder()
                            .setText("Pelex")
                            .setLabel("if")
                            .setCharStart(23)
                            .setCharEnd(28)
                            .setSourceEngine("nametag3"),
                    ).build()
            val types =
                listOf(
                    org.tatrman.resolver.model.ResolverEntityType(
                        ref = "er.entity.store",
                        categories = listOf("er.entity.store"),
                        anchors = listOf("dodací místo"),
                    ),
                    org.tatrman.resolver.model.ResolverEntityType(
                        ref = "er.entity.customer",
                        categories = listOf("er.entity.customer"),
                        anchors = listOf("zákazník"),
                    ),
                )
            val literals = Literals.of(quoted, parse)

            val proposed = SpanProposal.proposeDomainSpans(parse, types, literals)

            proposed.none { literals.overlaps(it.start, it.end) } shouldBe true
            // And the unquoted half is untouched — this is an exclusion, not a switch that turns
            // proposal off for the whole question.
            proposed.map { it.text } shouldContainExactly listOf("zákazník")
        }

        "with no parse at all, the literal is still found and still excluded" {
            // The degraded floor (§SpanProposal (d)): no dep parse, so every content n-gram is a
            // candidate — the loosest source there is, and the one a quoted string most needs to
            // be kept out of.
            val degraded = "zákazník \"Valmy Oil\" a Pelex"
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .setLanguage("de")
                    // The whitespace split IS the tokenisation on this path — the same one
                    // `QuoteScanner.tokens` falls back to, so the fixture cannot drift from it.
                    .addAllTokens(QuoteScanner.tokens(degraded, AnalyzeResponse.getDefaultInstance()))
                    .build()
            val types =
                listOf(
                    org.tatrman.resolver.model.ResolverEntityType(
                        ref = "er.entity.customer",
                        categories = listOf("er.entity.customer"),
                        anchors = listOf("zákazník"),
                    ),
                )
            val literals = Literals.of(degraded, parse)

            val proposed = SpanProposal.proposeDomainSpans(parse, types, literals)

            literals.spans
                .single()
                .literal.text shouldBe "Valmy Oil"
            proposed.none { literals.overlaps(it.start, it.end) } shouldBe true
            // `Pelex` outside the quotes is still a candidate: the floor still runs.
            proposed.any { it.text == "Pelex" } shouldBe true
        }
    }) {
    companion object {
        /**
         * *Ukaž dodací místa začínající na "Pelex"* — the delimiters tokenised apart, which is
         * what MorphoDiTa and Stanza both do. `Pelex` hangs off `začínající`, which hangs off
         * `místa`: two hops, inside §2.1's three.
         */
        private fun heroParse(): AnalyzeResponse =
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

        private fun token(
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

        private fun declared(targetRef: String): FuzzyMatch =
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

    private class FakeFuzzy(
        private val byQuery: Map<String, List<FuzzyMatch>>,
    ) : FuzzyClient {
        var lastRequest: BatchMatchRequest? = null

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

        override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()
    }
}
