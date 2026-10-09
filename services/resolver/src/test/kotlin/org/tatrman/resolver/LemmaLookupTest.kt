// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.api.GrpcService
import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.core.FuzzyMatcher
import org.tatrman.fuzzy.core.Lemmatizer
import org.tatrman.fuzzy.core.MatchProfile
import org.tatrman.fuzzy.core.MatchVersion
import org.tatrman.fuzzy.core.Norm
import org.tatrman.fuzzy.core.NormRule
import org.tatrman.fuzzy.core.RetrievalMode
import org.tatrman.fuzzy.core.SourceTag
import org.tatrman.fuzzy.core.StringRepository
import org.tatrman.fuzzy.core.TargetClass
import org.tatrman.fuzzy.core.TextNormalizer
import org.tatrman.fuzzy.core.TyposRule
import org.tatrman.fuzzy.loader.DeclaredValue
import org.tatrman.fuzzy.loader.DeclaredVocabulary
import org.tatrman.fuzzy.loader.DeclaredVocabularyEntry
import org.tatrman.fuzzy.loader.LexiconArchiveSource
import org.tatrman.fuzzy.loader.LoaderSource
import org.tatrman.fuzzy.loader.SnapshotVocabularySource
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.FuzzyServiceGrpcKt
import org.tatrman.fuzzy.v1.FuzzyStatusRequest
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.fuzzy.v1.LookupRequest
import org.tatrman.fuzzy.v1.LookupResponse
import org.tatrman.fuzzy.v1.Provenance
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.NerEntity
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.Binder
import org.tatrman.resolver.pipeline.DomainSpanCandidate
import org.tatrman.resolver.pipeline.EvidenceClasses
import org.tatrman.resolver.pipeline.LemmaLookup
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.EvidenceClass
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ValueKind
import org.tatrman.ttr.lexicon.LexiconArea
import org.tatrman.ttr.lexicon.compile.LexiconCompiler
import org.tatrman.ttr.lexicon.compile.LexiconPacker
import org.tatrman.ttr.lexicon.compile.LexiconSources
import org.tatrman.ttr.lexicon.compile.LexiconStdlib
import org.tatrman.ttr.lexicon.compile.ModelRefIndex
import java.nio.file.Files
import kotlin.io.path.writeBytes
import org.tatrman.fuzzy.v1.SourceTag as WireSourceTag
import org.tatrman.resolver.registry.DeclaredVocabulary as ResolverDeclaredVocabulary
import org.tatrman.resolver.v1.EntityType as RegistryEntityType

/**
 * ✅MH-D5, the matcher half — a mention is also looked up by its dictionary form (`LemmaLookup`).
 *
 * The pipeline cases run against a REAL `FuzzyMatcher` (v2, index-first, the service default) with
 * its own lemma axis switched on, over the rows the hartland lexicon actually ships for these
 * objects — same terms, same authored methods, same sources, same RV-44 profiles where the estate
 * wrote one — and the parse is MorphoDiTa + UDPipe's own answer for each question (hartland CZ
 * world, 2026-10-09). The matcher therefore holds the written form to `EXACT` exactly as live does:
 * `produkty` never meets the term `produkt`, which is the failure being fixed.
 */
class LemmaLookupTest :
    StringSpec({

        // ── the demo questions, through the pipeline ──────────────────────────────────────────

        "„za produkty“ binds the PRODUCT, not the English alias *product category* it met by typo" {
            val state = resolve(Q21, q21())
            val produkty = state.mentionsList.single { it.span.text == "produkty" }

            produkty.bindingsList.map { it.ref } shouldContainExactly listOf(ITEM)
            val binding = produkty.bindingsList.single()
            // the estate's term met through morphology: an alias, never EXACT
            binding.evidenceClass shouldBe EvidenceClass.EVIDENCE_CLASS_DECLARED_ALIAS
            binding.producer.algorithm shouldEndWith LemmaLookup.ALGORITHM_SUFFIX
        }

        "the quoted „Voltaic“ is a prefix of the product NAME, no longer of the category" {
            val value = resolve(Q21, q21()).valuesList.single { it.kind == ValueKind.VALUE_KIND_VERBATIM }

            value.verbatimText shouldBe "Voltaic"
            value.attributionsList.map { it.attributeRef } shouldContainExactly listOf(PRODUCT_NAME)
            value.predicateRef shouldBe "pred:starts_with"
        }

        "„podle kategorie“ still groups by the category — written, so untouched" {
            val kategorie = resolve(Q21, q21()).mentionsList.single { it.span.text == "kategorie" }

            kategorie.bindingsList.map { it.ref } shouldContainExactly listOf(CATEGORY)
            kategorie.bindingsList
                .single()
                .producer.algorithm
                .endsWith(LemmaLookup.ALGORITHM_SUFFIX) shouldBe false
        }

        "„portfolií našich klientů“ — both objects bind, the determiner left out of the form asked" {
            val state = resolve(Q53, q53())
            val bound = state.mentionsList.associate { it.span.text to it.bindingsList.map { b -> b.ref } }

            bound["portfolií"] shouldBe listOf(PORTFOLIO)
            bound["našich klientů"] shouldBe listOf(CLIENT)
            state.gapsList.filter { it.kind == GapKind.GAP_KIND_G1_UNBOUND }.map { it.span.text } shouldContainExactly
                listOf("hodnota")
        }

        "„podle prodejen“ — the written alias *tržba z prodejen* still speaks; the dictionary form adds nothing" {
            // `prodejna` is declared for the store AND the store-sales fact, but both rows carry an
            // RV-44 profile (canonical only): the estate said which forms count, and this lookup
            // does not overrule it. So 2.4 does not start asking; it binds what it bound before.
            val prodejen = resolve(Q24, q24()).mentionsList.single { it.span.text == "prodejen" }

            prodejen.bindingsList.map { it.ref } shouldContainExactly listOf(STORE_SALES_REVENUE)
            prodejen.bindingsList
                .single()
                .producer.algorithm
                .endsWith(LemmaLookup.ALGORITHM_SUFFIX) shouldBe false
        }

        // ── the plan: what is asked ───────────────────────────────────────────────────────────

        fun anchor(
            text: String,
            start: Int,
            origin: DomainSpanCandidate.Origin = DomainSpanCandidate.Origin.ANCHOR_PHRASE,
        ) = DomainSpanCandidate(text, start, start + text.length, listOf(ITEM), listOf(ITEM), true, origin)

        "one content word whose lemma differs ⇒ its lemma is asked" {
            LemmaLookup.plan(listOf(anchor("produkty", 31)), q21Parse()) shouldBe
                listOf(LemmaLookup.Query(0, "produkt"))
        }

        "a determiner is not part of the form asked" {
            LemmaLookup.plan(listOf(anchor("našich klientů", 26)), q53Parse()) shouldBe
                listOf(LemmaLookup.Query(0, "klient"))
        }

        "a word already in its dictionary form asks nothing" {
            LemmaLookup.plan(listOf(anchor("kategorie", 70)), q21Parse()).shouldBeEmpty()
        }

        "a phrase of several content words asks nothing — agreement and case are not token-wise lemmas" {
            LemmaLookup.plan(listOf(anchor("Tržby z tržiště", 0)), q21Parse()).shouldBeEmpty()
        }

        "a value span asks nothing — member data is lemmatised by the matcher itself" {
            LemmaLookup
                .plan(listOf(anchor("produkty", 31, DomainSpanCandidate.Origin.PROPER_NOUN)), q21Parse())
                .shouldBeEmpty()
        }

        "no lemma in the parse (the floor) asks nothing" {
            val floor = AnalyzeResponse.newBuilder().addAllTokens(q21().map { it.toBuilder().clearLemma().build() })
            LemmaLookup.plan(listOf(anchor("produkty", 31)), floor.build()).shouldBeEmpty()
        }

        // ── the merge: what a dictionary-form row may do ──────────────────────────────────────

        fun row(
            target: String,
            score: Double,
            method: String? = "EXACT",
            source: WireSourceTag = WireSourceTag.METADATA,
            profile: Boolean = false,
        ): FuzzyMatch {
            val provenance = Provenance.newBuilder().setProducer("fuzzy").setMethod("TATRMAN_V2")
            if (profile) provenance.setNorm("canonical").setAlgorithm("exact")
            val b =
                FuzzyMatch
                    .newBuilder()
                    .setCandidateId(target)
                    .setCandidate(target)
                    .setCategory(target)
                    .setTargetRef(target)
                    .setScore(score)
                    .setSource(source)
                    .setProvenance(provenance)
            if (method != null) b.matchMethod = method
            return b.build()
        }

        fun response(vararg slots: List<FuzzyMatch>) =
            BatchMatchResponse
                .newBuilder()
                .addAllResults(slots.map { FuzzyMatchResponse.newBuilder().addAllMatches(it).build() })
                .build()

        val plan = listOf(LemmaLookup.Query(0, "produkt"))

        "the trailing slots go; every other slot is where it was" {
            val trigger = listOf(row("ground:chrono", 1.0))
            val merged = LemmaLookup.merge(response(emptyList(), trigger, listOf(row(ITEM, 1.01))), plan, offset = 2)

            merged.resultsCount shouldBe 2
            merged.getResults(1).matchesList shouldBe trigger
            merged.getResults(0).matchesList.map { it.targetRef } shouldContainExactly listOf(ITEM)
        }

        "a dictionary-form row: penalised, stamped, and classed an alias — never EXACT" {
            val merged = LemmaLookup.merge(response(emptyList(), listOf(row(ITEM, 1.01))), plan, offset = 1)
            val hit = merged.getResults(0).getMatches(0)

            hit.score shouldBe (1.01 - LemmaLookup.PENALTY)
            LemmaLookup.isLemmaHit(hit) shouldBe true
            EvidenceClasses.of(hit, anchored = true, ResolverThresholds.LIVE) shouldBe
                EvidenceClass.EVIDENCE_CLASS_DECLARED_ALIAS
            EvidenceClasses.of(
                row(ITEM, 1.01, source = WireSourceTag.LEARNED).let {
                    LemmaLookup.merge(response(emptyList(), listOf(it)), plan, 1).getResults(0).getMatches(0)
                },
                anchored = true,
                ResolverThresholds.LIVE,
            ) shouldBe EvidenceClass.EVIDENCE_CLASS_LEARNED_ALIAS
        }

        "an equally good WRITTEN row wins outright — the penalty is wider than the tie band" {
            val written = row(CATEGORY, 1.0, method = "TYPOS(1)", source = WireSourceTag.DECLARED)
            val merged = LemmaLookup.merge(response(listOf(written), listOf(row(ITEM, 1.0))), plan, offset = 1)
            val classed =
                merged.getResults(0).matchesList.map {
                    Binder.ClassedMatch(it, EvidenceClasses.of(it, true, ResolverThresholds.LIVE))
                }

            val verdict = Binder.decide(classed, ResolverThresholds.LIVE)
            (verdict as Binder.Bind).winner.match.targetRef shouldBe CATEGORY
        }

        "a row the written form already reached is not added twice" {
            val merged = LemmaLookup.merge(response(listOf(row(ITEM, 1.0)), listOf(row(ITEM, 1.01))), plan, 1)

            merged.getResults(0).matchesList shouldHaveSize 1
            LemmaLookup.isLemmaHit(merged.getResults(0).getMatches(0)) shouldBe false
        }

        "member rows, TOKENS rows, profile rows and rows with no authored method are not admitted" {
            val refused =
                listOf(
                    row("m", 1.0, method = null, source = WireSourceTag.MEMBER),
                    row("t", 1.0, method = "TOKENS", source = WireSourceTag.DECLARED),
                    row("p", 1.0, source = WireSourceTag.DECLARED, profile = true),
                    row("n", 1.0, method = null),
                )
            LemmaLookup
                .merge(response(emptyList(), refused), plan, 1)
                .getResults(0)
                .matchesList
                .shouldBeEmpty()
        }

        "a TYPOS(n) row on a profile-less declared term is admitted like an EXACT one" {
            val typos = row(ITEM, 1.0, method = "TYPOS(1)", source = WireSourceTag.DECLARED)
            LemmaLookup.merge(response(emptyList(), listOf(typos)), plan, 1).getResults(0).matchesCount shouldBe 1
        }

        "no plan ⇒ the response is returned as it came" {
            val r = response(listOf(row(ITEM, 1.0)))
            (LemmaLookup.merge(r, emptyList(), 1) === r) shouldBe true
        }
    }) {
    private class InProcessFuzzy(
        private val stub: FuzzyServiceGrpcKt.FuzzyServiceCoroutineStub,
    ) : FuzzyClient {
        override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse = stub.batchMatch(request)

        override suspend fun lookup(request: LookupRequest): LookupResponse = stub.lookup(request)

        override suspend fun getStatus(): FuzzyStatusResponse = stub.getStatus(FuzzyStatusRequest.getDefaultInstance())
    }

    /** The shipped `pred:` slice as lex-matcher reads it off a real archive, plus the estate's rows. */
    private class SliceWithEstate(
        private val slice: LexiconArchiveSource,
    ) : SnapshotVocabularySource {
        override suspend fun fetch(): DeclaredVocabulary =
            DeclaredVocabulary(
                slice.fetch().entries +
                    ROWS.groupBy { it.target }.map { (target, rows) ->
                        DeclaredVocabularyEntry(
                            category = target,
                            targetRef = target,
                            values =
                                rows.mapIndexed { i, r ->
                                    DeclaredValue(
                                        id = "$target#$i",
                                        value = r.term,
                                        source = r.source,
                                        matchMethod = r.method,
                                        targetClass = TargetClass.MODEL_OBJECT,
                                        matchProfile = r.profile,
                                    )
                                },
                        )
                    },
            )

        override fun hash(): String = "lemma-lookup"
    }

    /**
     * The matcher's OWN lemma axis, from the same parses — `lex-matcher` lemmatises the query and the
     * candidates through nlp in production; this answers what nlp answered.
     */
    private class ParseLemmatizer : Lemmatizer {
        private val lemmas =
            (q21() + q24() + q53())
                .associate { it.text.lowercase() to TextNormalizer.fold(it.lemma) }

        override suspend fun lemmatize(tokens: Collection<String>): Map<String, String> =
            tokens.associateWith { lemmas[it.lowercase()] ?: TextNormalizer.fold(it) }
    }

    private data class Row(
        val target: String,
        val term: String,
        val method: String,
        val source: SourceTag,
        val profile: MatchProfile? = null,
    )

    companion object {
        private const val ITEM = "er.entity.item"
        private const val CATEGORY = "er.entity.item.category"
        private const val PRODUCT_NAME = "er.entity.item.product_name"
        private const val PORTFOLIO = "er.entity.portfolio"
        private const val CLIENT = "er.entity.client"
        private const val STORE = "er.entity.store"
        private const val STORE_SALES = "er.entity.store_sales"
        private const val STORE_SALES_REVENUE = "er.entity.store_sales.ext_sales_price"
        private const val CHANNEL_REVENUE = "er.entity.channel_sales.ext_sales_price"
        private const val MARKETPLACE_REVENUE = "er.entity.catalog_sales.ext_sales_price"

        private val TOKENS_ONLY = MatchProfile(listOf(NormRule(Norm.CANONICAL, tokens = true)))
        private val CANONICAL_ONLY = MatchProfile(listOf(NormRule(Norm.CANONICAL, exact = 1.0)))
        private val TYPOS_1 =
            MatchProfile(
                listOf(
                    NormRule(Norm.CANONICAL, exact = 1.0, typos = TyposRule(1, 0.05)),
                    NormRule(Norm.FOLDED, exact = 0.9),
                ),
            )

        /** The hartland lexicon's rows for these objects (archive `29b01c53…`), as shipped. */
        private val ROWS =
            listOf(
                Row(ITEM, "produkt", "EXACT", SourceTag.METADATA),
                Row(ITEM, "products", "EXACT", SourceTag.METADATA),
                Row(ITEM, "product", "EXACT", SourceTag.METADATA),
                Row(CATEGORY, "product category", "TOKENS", SourceTag.DECLARED, TOKENS_ONLY),
                Row(CATEGORY, "kategorie", "TYPOS(1)", SourceTag.DECLARED, TYPOS_1),
                Row(CATEGORY, "kategorií", "TYPOS(1)", SourceTag.DECLARED, TYPOS_1),
                Row(CATEGORY, "category", "TYPOS(1)", SourceTag.DECLARED, TYPOS_1),
                Row(PORTFOLIO, "portfolio", "EXACT", SourceTag.METADATA),
                Row(PORTFOLIO, "portfolios", "EXACT", SourceTag.METADATA),
                Row(CLIENT, "klient", "EXACT", SourceTag.METADATA),
                Row(CLIENT, "clients", "EXACT", SourceTag.METADATA),
                Row(STORE, "prodejna", "EXACT", SourceTag.DECLARED, CANONICAL_ONLY),
                Row(STORE, "stores", "EXACT", SourceTag.METADATA),
                Row(STORE_SALES, "prodejna", "EXACT", SourceTag.DECLARED, CANONICAL_ONLY),
                Row(STORE_SALES, "tržby z prodejen", "EXACT", SourceTag.METADATA),
                Row(STORE_SALES_REVENUE, "tržba z prodejen", "TOKENS", SourceTag.DECLARED, TOKENS_ONLY),
                Row(STORE_SALES_REVENUE, "obrat z prodejen", "TOKENS", SourceTag.DECLARED, TOKENS_ONLY),
                Row(CHANNEL_REVENUE, "tržby", "TYPOS(1)", SourceTag.DECLARED, TYPOS_1),
                Row(CHANNEL_REVENUE, "tržba", "TYPOS(1)", SourceTag.DECLARED, TYPOS_1),
                Row(MARKETPLACE_REVENUE, "tržby z tržiště", "TOKENS", SourceTag.DECLARED, TOKENS_ONLY),
                Row(MARKETPLACE_REVENUE, "tržba z tržiště", "TOKENS", SourceTag.DECLARED, TOKENS_ONLY),
            )

        private fun entityType(
            ref: String,
            kind: String,
            owner: String = "",
            nameRef: String = "",
        ) = RegistryEntityType
            .newBuilder()
            .setRef(ref)
            .addCategories(ref)
            .addAllAnchors(ROWS.filter { it.target == ref }.map { it.term })
            .setObjectKind(kind)
            .setOwnerRef(owner)
            .setNameAttributeRef(nameRef)
            .build()

        /** As the archive channel projects it: one type per target, `category == ref`, anchors = terms. */
        private val registry: Registry =
            Registry
                .newBuilder()
                .addEntityTypes(entityType(ITEM, "entity", nameRef = PRODUCT_NAME))
                .addEntityTypes(entityType(CATEGORY, "attribute", owner = ITEM))
                .addEntityTypes(entityType(PORTFOLIO, "entity"))
                .addEntityTypes(entityType(CLIENT, "entity"))
                .addEntityTypes(entityType(STORE, "entity"))
                .addEntityTypes(entityType(STORE_SALES, "entity"))
                .addEntityTypes(entityType(STORE_SALES_REVENUE, "measure", owner = STORE_SALES))
                .addEntityTypes(entityType(CHANNEL_REVENUE, "measure", owner = "er.entity.channel_sales"))
                .addEntityTypes(entityType(MARKETPLACE_REVENUE, "measure", owner = "er.entity.catalog_sales"))
                .addLocales("cs")
                .setSnapshotHash("snap-lemma")
                .build()

        private fun tok(
            text: String,
            start: Int,
            lemma: String,
            upos: String,
            head: Int,
            rel: String,
            number: String? = null,
            pronType: String? = null,
        ): Token {
            val b = MhMembers.tok(text, start, start + text.length, lemma, upos, head, rel).toBuilder()
            if (number != null) b.putFeats("Number", number)
            if (pronType != null) b.putFeats("PronType", pronType)
            return b.build()
        }

        private const val Q21 = "Tržby z tržiště v roce 2025 za produkty začínající na „Voltaic“ podle kategorie"
        private const val Q53 = "Jaká je hodnota portfolií našich klientů?"
        private const val Q24 = "Tržby podle prodejen v roce 2025"

        /** MorphoDiTa + UDPipe on [Q21], hartland 2026-10-09. */
        private fun q21(): List<Token> =
            listOf(
                tok("Tržby", 0, "tržba", "NOUN", 0, "root", "Plur"),
                tok("z", 6, "z", "ADP", 3, "case"),
                tok("tržiště", 8, "tržiště", "NOUN", 1, "nmod", "Sing"),
                tok("v", 16, "v", "ADP", 5, "case"),
                tok("roce", 18, "rok", "NOUN", 1, "nmod", "Sing"),
                tok("2025", 23, "2025", "NUM", 5, "nummod"),
                tok("za", 28, "za", "ADP", 8, "case"),
                tok("produkty", 31, "produkt", "NOUN", 1, "dep", "Plur"),
                tok("začínající", 40, "začínající", "ADJ", 8, "amod", "Plur"),
                tok("na", 51, "na", "ADP", 12, "case"),
                tok("„", 54, "„", "PUNCT", 12, "punct"),
                tok("Voltaic", 55, "Voltaic", "X", 9, "obl:arg"),
                tok("“", 62, "“", "PUNCT", 12, "punct"),
                tok("podle", 64, "podle", "ADP", 15, "case"),
                tok("kategorie", 70, "kategorie", "NOUN", 1, "dep", "Sing"),
            )

        /** MorphoDiTa + UDPipe on [Q53]. */
        private fun q53(): List<Token> =
            listOf(
                tok("Jaká", 0, "jaký", "DET", 0, "root", "Sing", "Int,Rel"),
                tok("je", 5, "být", "AUX", 1, "cop", "Sing"),
                tok("hodnota", 8, "hodnota", "NOUN", 1, "nsubj", "Sing"),
                tok("portfolií", 16, "portfolio", "NOUN", 3, "nmod", "Plur"),
                tok("našich", 26, "náš", "DET", 6, "det", "Plur", "Prs"),
                tok("klientů", 33, "klient", "NOUN", 4, "nmod", "Plur"),
                tok("?", 40, "?", "PUNCT", 1, "punct"),
            )

        /** MorphoDiTa + UDPipe on [Q24]. */
        private fun q24(): List<Token> =
            listOf(
                tok("Tržby", 0, "tržba", "NOUN", 0, "root", "Plur"),
                tok("podle", 6, "podle", "ADP", 3, "case"),
                tok("prodejen", 12, "prodejna", "NOUN", 1, "nmod", "Plur"),
                tok("v", 21, "v", "ADP", 5, "case"),
                tok("roce", 23, "rok", "NOUN", 1, "dep", "Sing"),
                tok("2025", 28, "2025", "NUM", 5, "nummod"),
            )

        private fun parseOf(tokens: List<Token>) =
            AnalyzeResponse
                .newBuilder()
                .setLanguage("cs")
                .addAllTokens(tokens)
                .build()

        private fun q21Parse() = parseOf(q21())

        private fun q53Parse() = parseOf(q53())

        private fun year(start: Int): NerEntity = MhMembers.ner("2025", start, start + 4, "DATE", "cnec:ty")

        private val archive by lazy {
            val result =
                LexiconCompiler.compile(
                    LexiconSources(area = LexiconArea(LexiconStdlib.predicateSlices(), emptyList())),
                    ModelRefIndex { null },
                    "sha256:" + "ef".repeat(32),
                    "2026-10-09T00:00:00Z",
                )
            Files.createTempDirectory("lemma-lookup").resolve("lexicon.tar.zst").also {
                it.writeBytes(LexiconPacker.pack(result, "sha256:" + "ef".repeat(32), "test").bytes)
            }
        }

        private fun resolve(
            text: String,
            tokens: List<Token>,
        ): ResolutionState =
            runBlocking {
                val entities =
                    when (text) {
                        Q21 -> listOf(year(23))
                        Q24 -> listOf(year(28))
                        else -> emptyList()
                    }
                val parse =
                    MhMembers
                        .parse(
                            text,
                            tokens.toTypedArray(),
                            "cs",
                        ).toBuilder()
                        .addAllEntities(entities)
                        .build()
                val config =
                    AppConfig(serverPort = 0, grpcPort = 0, grpcReflectionEnabled = false, refreshIntervalSeconds = 0)
                val empty =
                    object : LoaderSource {
                        override suspend fun loadNextCache() =
                            emptyMap<String, List<org.tatrman.fuzzy.core.Candidate>>()
                    }
                val lemmatizer = ParseLemmatizer()
                val repo =
                    StringRepository(
                        config,
                        empty,
                        lemmatizer = lemmatizer,
                        snapshotSource = SliceWithEstate(LexiconArchiveSource(archive)),
                    )
                repo.forceRefresh()
                val service =
                    GrpcService(
                        FuzzyMatcher(
                            repo,
                            lemmatizer = lemmatizer,
                            retrievalMode = RetrievalMode.INDEX_FIRST,
                            matchVersion = MatchVersion.V2,
                        ),
                        repo,
                    )
                val name = "lemma-lookup-${System.nanoTime()}"
                val server =
                    InProcessServerBuilder
                        .forName(name)
                        .directExecutor()
                        .addService(service)
                        .build()
                        .start()
                val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
                try {
                    ResolverPipeline(
                        MhMembers.FakeNlp(parse, "cs"),
                        InProcessFuzzy(FuzzyServiceGrpcKt.FuzzyServiceCoroutineStub(channel)),
                        SnapshotRegistry(StubRegistrySource(ResolverDeclaredVocabulary(), ""), ResolverThresholds.LIVE),
                        emptyMap(),
                        ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1"),
                    ).resolve(
                        ResolveRequest
                            .newBuilder()
                            .setConversationId("lemma-lookup")
                            .setFresh(FreshQuestion.newBuilder().setText(text).setLocale("cs"))
                            .setRegistry(registry)
                            .build(),
                    ).resolutionState
                } finally {
                    channel.shutdownNow()
                    server.shutdownNow()
                    repo.close()
                }
            }
    }
}
