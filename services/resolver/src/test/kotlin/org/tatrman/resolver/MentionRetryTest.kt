// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotEndWith
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.fuzzy.v1.LookupRequest
import org.tatrman.fuzzy.v1.LookupResponse
import org.tatrman.fuzzy.v1.Provenance
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.fuzzy.v1.SpanQuery
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
import org.tatrman.resolver.pipeline.FrameRolePreps
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.Binding
import org.tatrman.resolver.v1.EntityType
import org.tatrman.resolver.v1.FrameRole
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.Mention
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.resolver.v1.TargetClass
import org.tatrman.fuzzy.v1.TargetClass as FuzzyTargetClass

/**
 * ttr-server#204 — an article or an adjective the lexicon does not know, folded into a mention, no
 * longer stops it binding.
 *
 * Both phrase builders fold a head noun's `det` and `amod` dependents into its mention, and the
 * matcher was then asked about the phrase: `the marketplace`, `the web`, `Total revenue`, `What
 * share`. The estate declares the word, not the phrase, so every one of them came back G1 while
 * `marketplace`, `web` and `Revenue` on their own bound. When the phrase binds nothing it is asked
 * again as its head. A word left out that restricts nothing (a closed list, held as data) vanishes and
 * the mention keeps its span; any other word left out surfaces as an unbound mention of its own, so a
 * restriction is reported rather than swallowed.
 *
 * The English trees are Stanza 1.10's (the engine the live probe of 2026-10-12 ran), trimmed to the
 * fields the resolver reads. The Czech ones keep Stanza's tree and take MorphoDiTa's dictionary
 * lemmas, the shape of the live Czech lane. The matcher is a dictionary: it answers the words it
 * holds as written and nothing else, with the provenance a profiled row carries — which is also why
 * the dictionary-form lookup (`LemmaLookup`) could not rescue `the marketplace` before this change.
 */
class MentionRetryTest :
    StringSpec({

        // ── the issue's minimal pairs ─────────────────────────────────────────────────────────────

        "`the marketplace` binds the marketplace channel, as bare `marketplace` does" {
            val bare = resolve(REVENUE_FROM_MARKETPLACE)
            bare.refsOn("marketplace") shouldContainExactly listOf(CATALOG)

            val r = resolve(REVENUE_FROM_THE_MARKETPLACE)

            r.refsOn("the marketplace") shouldContainExactly listOf(CATALOG)
            r.refsOn("Revenue") shouldContainExactly listOf(REVENUE)
            r.gapKinds() shouldNotContain GapKind.GAP_KIND_G1_UNBOUND
            // the span is the user's words; the producer says how it was reached
            r.bindingOn("the marketplace").producer.algorithm shouldEndWith "+core"
            bare.bindingOn("marketplace").producer.algorithm shouldNotEndWith "+core"
        }

        "`the web` binds the web channel, as bare `web` does" {
            resolve(REVENUE_ON_WEB).refsOn("web") shouldContainExactly listOf(WEB)

            val r = resolve(REVENUE_ON_THE_WEB)

            r.refsOn("the web") shouldContainExactly listOf(WEB)
            r.gapKinds() shouldNotContain GapKind.GAP_KIND_G1_UNBOUND
        }

        "`Total revenue` binds revenue, and `Total` is no mention, value or filter of its own" {
            val r = resolve(TOTAL_REVENUE)

            r.resolutionState.mentionsList.map { it.span.text } shouldContainExactly listOf("Total revenue")
            r.refsOn("Total revenue") shouldContainExactly listOf(REVENUE)
            r.mentionOn("Total revenue").frameRolesList shouldContain FrameRole.FRAME_ROLE_MEASURE
            r.mentionOn("Total revenue").frameRolesList shouldNotContain FrameRole.FRAME_ROLE_FILTER
            r.resolutionState.valuesList.map { it.span.text } shouldNotContain "Total"
            r.resolutionState.gapsList.shouldBeEmpty()
            // the door says the same: one domain binding, revenue
            r.resolution.bindingsList
                .filter { it.hasDomain() }
                .map { it.domain.rawText } shouldContainExactly
                listOf("Total revenue")
        }

        // ── the corpus cases ──────────────────────────────────────────────────────────────────────

        "n2-ratio — `What share of total revenue came from the marketplace in 2025?` binds all three" {
            val r = resolve(WHAT_SHARE)

            // the ratio operator, reached through the lookup round (`share` is no anchor of the estate)
            r.refsOn("What share") shouldContainExactly listOf(SHARE_OF)
            r.bindingOn("What share").targetClass shouldBe TargetClass.TARGET_CLASS_OPERATOR
            r.refsOn("total revenue") shouldContainExactly listOf(REVENUE)
            r.refsOn("the marketplace") shouldContainExactly listOf(CATALOG)
            r.gapKinds() shouldNotContain GapKind.GAP_KIND_G1_UNBOUND
        }

        "a3-4 — `the highest share`: `share` binds the ratio operator, and `highest` surfaces as its own G1" {
            // `highest` is no word of the estate and not one that restricts nothing: the head binds
            // over its own word, and `highest` is reported, not swallowed. `the` vanishes.
            val r = resolve(HIGHEST_SHARE)

            r.refsOn("share") shouldContainExactly listOf(SHARE_OF)
            r.bindingOn("share").producer.algorithm shouldEndWith "+core"
            r.shouldHaveResidue("highest")
            r.resolutionState.mentionsList.map { it.span.text } shouldNotContain "the highest share"
            r.refsOn("distribution center") shouldContainExactly listOf(WAREHOUSE)
        }

        "r2b — `And on the web?` binds the web channel, with no gap left to ask about" {
            val r = resolve(AND_ON_THE_WEB)

            r.refsOn("the web") shouldContainExactly listOf(WEB)
            r.resolutionState.gapsList.shouldBeEmpty()
        }

        // ── what the retry must not do ────────────────────────────────────────────────────────────

        "a folded phrase that binds keeps its own answer: the head is never asked in its place" {
            // An estate that declared `total revenue` itself. The phrase binds as written, so the
            // head's answer — present in the same batch — is never read.
            val lexicon = Lexicon(ROWS + row("total revenue", REVENUE))
            val r = resolve(TOTAL_REVENUE, lexicon)

            r.refsOn("Total revenue") shouldContainExactly listOf(REVENUE)
            r.bindingOn("Total revenue").producer.algorithm shouldNotEndWith "+core"
        }

        "…and a folded phrase the lookup round binds keeps that answer too" {
            // The round asks the phrase first and its head beside it; the phrase's answer wins.
            val lexicon = Lexicon(ROWS + row("the highest share", TOP_N, FuzzyTargetClass.TARGET_CLASS_OPERATOR))
            val r = resolve(HIGHEST_SHARE, lexicon)

            r.refsOn("the highest share") shouldContainExactly listOf(TOP_N)
            r.bindingOn("the highest share").producer.algorithm shouldNotEndWith "+core"
        }

        "a modifier the lexicon knows is never thrown away: `the largest stores` is not asked as `stores`" {
            // `largest` is an op:top-n trigger. Without its number it is no operator slot, so it was
            // folded into the phrase — and dropping it would answer "which stores" for "which are
            // the largest". The phrase stays whole and stays a G1, exactly as before.
            val lexicon = Lexicon(ROWS)
            val r = resolve(LARGEST_STORES, lexicon)

            r.mentionOn("the largest stores").bindingsList.shouldBeEmpty()
            r.gapsOn("the largest stores") shouldContainExactly listOf(GapKind.GAP_KIND_G1_UNBOUND)
            lexicon.lookups.map { it.term } shouldNotContain "stores"
        }

        "a declared modifier is a mention of its own, never folded: `Monthly revenue`" {
            val lexicon = Lexicon(ROWS)
            val r = resolve(MONTHLY_REVENUE, lexicon)

            r.refsOn("revenue") shouldContainExactly listOf(REVENUE)
            r.bindingOn("revenue").producer.algorithm shouldNotEndWith "+core"
            // nothing was dropped, so the lexicon was not asked whether it knows the word (a modifier's
            // question is the one slot with no categories; `Monthly` may still be asked as a mention)
            lexicon.batch.filter { it.categoriesCount == 0 }.map { it.query } shouldNotContain "Monthly"
        }

        "an adjective with words of its own is kept: `How many stores` is not asked as `stores`" {
            // `many` governs `How`: together they ask for a count, and the phrase is not `stores`.
            val lexicon = Lexicon(ROWS)
            val r = resolve(HOW_MANY_STORES, lexicon)

            r.gapsOn("many stores") shouldContainExactly listOf(GapKind.GAP_KIND_G1_UNBOUND)
            lexicon.batch.map { it.query } shouldNotContain "stores"
            lexicon.lookups.map { it.term } shouldNotContain "stores"
        }

        // ── Czech ─────────────────────────────────────────────────────────────────────────────────

        "cs — „Tržby na webu“ binds as it did: nothing is folded, nothing is asked again" {
            val lexicon = Lexicon(ROWS)
            val r = resolve(TRZBY_NA_WEBU, lexicon)

            r.refsOn("Tržby") shouldContainExactly listOf(REVENUE)
            r.refsOn("webu") shouldContainExactly listOf(WEB)
            r.allBindings().map { it.producer.algorithm }.forEach { it shouldNotEndWith "+core" }
            lexicon.batch.filter { it.categoriesCount == 0 }.shouldBeEmpty()
        }

        "cs — „Celkové tržby z tržiště“: the declared phrase binds whole and `Celkové` is no part of it" {
            val lexicon = Lexicon(ROWS)
            val r = resolve(CELKOVE_TRZBY, lexicon)

            r.refsOn("tržby z tržiště") shouldContainExactly listOf(MARKETPLACE_REVENUE)
            r.resolutionState.mentionsList
                .map { it.span.text }
                .forEach { it.contains("Celkové") shouldBe false }
            r.allBindings().map { it.producer.algorithm }.forEach { it shouldNotEndWith "+core" }
            lexicon.batch.filter { it.categoriesCount == 0 }.shouldBeEmpty()
            r.gapKinds() shouldNotContain GapKind.GAP_KIND_G1_UNBOUND
        }

        "cs — the rule is the language's too: „nejvyšší podíl“ binds `podíl` and surfaces `nejvyšší`" {
            val r = resolve(NEJVYSSI_PODIL)

            r.refsOn("podíl") shouldContainExactly listOf(SHARE_OF)
            r.shouldHaveResidue("nejvyšší")
        }

        "cs — „Podíl akčních tržeb“: revenue and the ratio bind, and `akčních` is a G1 of its own" {
            // The shape the first cut got wrong: `akčních` (promotional) is a restriction. Swallowed
            // into a bound `akčních tržeb`, nothing downstream would have known the answer must be
            // the promotional revenue only.
            val r = resolve(PODIL_AKCNICH_TRZEB)

            r.refsOn("tržeb") shouldContainExactly listOf(REVENUE)
            r.mentionOn("tržeb").frameRolesList shouldContain FrameRole.FRAME_ROLE_MEASURE
            r.refsOn("Podíl") shouldContainExactly listOf(SHARE_OF)
            r.shouldHaveResidue("akčních")
            r.resolutionState.mentionsList.map { it.span.text } shouldNotContain "akčních tržeb"
        }

        "cs — „Celkové tržby v roce 2025“: `celkové` is on the silent list, so revenue binds with no gap" {
            val r = resolve(CELKOVE_TRZBY_V_ROCE)

            r.refsOn("Celkové tržby") shouldContainExactly listOf(REVENUE)
            r.gapKinds() shouldNotContain GapKind.GAP_KIND_G1_UNBOUND
        }

        // ── what may vanish and what must surface ────────────────────────────────────────────────

        "`Promotional revenue`: revenue binds over its own word, and `Promotional` surfaces" {
            val r = resolve(PROMOTIONAL_REVENUE)

            r.refsOn("revenue") shouldContainExactly listOf(REVENUE)
            r.shouldHaveResidue("Promotional")
            // a residue is not a filter the door could honour by accident, nor a subject to answer
            r.resolutionState.mentionsList.map { it.span.text } shouldNotContain "Promotional revenue"
        }

        "`Stores with no returns`: a determiner off the list surfaces — `no` is a G1, `returns` binds" {
            val r = resolve(STORES_WITH_NO_RETURNS)

            r.refsOn("returns") shouldContainExactly listOf(RETURNS)
            r.refsOn("Stores") shouldContainExactly listOf(STORE)
            r.shouldHaveResidue("no")
        }

        "a residue takes no frame role, even where it is the only word left to be the subject" {
            // An elliptic follow-up: `stores` is a FILTER (after `in`), so R9 would hand SUBJECT to the
            // next candidate — the residue — if it were one. It is not: the gap reads as a word
            // nothing placed, which is what keeps a consumer from answering around it.
            val r = resolve(AND_IN_PROMOTIONAL_STORES)

            r.refsOn("stores") shouldContainExactly listOf(STORE)
            r.shouldHaveResidue("promotional")
        }

        "the silent list is DATA: without `total` in it, `Total` surfaces instead of vanishing" {
            val override =
                kotlin.io.path
                    .createTempFile("frame-roles", ".conf")
                    .toFile()
            try {
                override.writeText("""frame-roles { silent-modifiers { en = ["the", "a", "an", "what", "which"] } }""")
                val preps =
                    FrameRolePreps.load(
                        com.typesafe.config.ConfigFactory
                            .parseMap(mapOf("resolver.frame-roles.config-path" to override.absolutePath)),
                    )
                val r = resolve(TOTAL_REVENUE, preps = preps)

                r.refsOn("revenue") shouldContainExactly listOf(REVENUE)
                r.shouldHaveResidue("Total")
            } finally {
                override.delete()
            }
        }

        "the shipped table holds the closed list, per language, and nothing else" {
            val shipped = FrameRolePreps.shipped()

            for (word in listOf("the", "A", "an", "What", "which", "Total", "overall", "entire", "whole")) {
                shipped.isSilent("en", word, word) shouldBe true
            }
            for (word in listOf("no", "all", "this", "these", "promotional", "highest", "largest")) {
                shipped.isSilent("en", word, word) shouldBe false
            }
            for (word in listOf("Celkové", "celkových", "úhrnné", "veškeré", "Které", "jaký")) {
                shipped.isSilent("cs", word, word) shouldBe true
            }
            for (word in listOf("akčních", "nejvyšší", "všechny", "ten")) {
                shipped.isSilent("cs", word, word) shouldBe false
            }
            // matched by lemma too, and per language: `the` is no Czech word
            shipped.isSilent("cs", "celk", "celkový") shouldBe true
            shipped.isSilent("cs", "the", "the") shouldBe false
        }

        "an override written before the table existed keeps the shipped one: `the` still vanishes" {
            val override =
                kotlin.io.path
                    .createTempFile("frame-roles", ".conf")
                    .toFile()
            try {
                override.writeText("""frame-roles { grouping-preps { en = ["by"] } }""")
                val preps =
                    FrameRolePreps.load(
                        com.typesafe.config.ConfigFactory
                            .parseMap(mapOf("resolver.frame-roles.config-path" to override.absolutePath)),
                    )

                preps.isSilent("en", "the", "the") shouldBe true
                resolve(REVENUE_FROM_THE_MARKETPLACE, preps = preps).gapKinds() shouldNotContain
                    GapKind.GAP_KIND_G1_UNBOUND
            } finally {
                override.delete()
            }
        }
    }) {
    private data class Tok(
        val text: String,
        val lemma: String,
        val upos: String,
        val head: Int,
        val relation: String,
        val feats: Map<String, String> = emptyMap(),
    )

    private class Question(
        val text: String,
        val lang: String,
        val dates: List<String>,
        vararg toks: Tok,
    ) {
        val parse: AnalyzeResponse =
            run {
                val builder =
                    AnalyzeResponse
                        .newBuilder()
                        .setLanguage(lang)
                        .setDetectedLanguage(lang)
                var at = 0
                for (t in toks) {
                    val start = text.indexOf(t.text, at)
                    require(start >= 0) { "`${t.text}` is not in `$text` after $at" }
                    at = start + t.text.length
                    builder.addTokens(
                        Token
                            .newBuilder()
                            .setText(t.text)
                            .setCharStart(start)
                            .setCharEnd(at)
                            .setLemma(t.lemma)
                            .setUpos(t.upos)
                            .setDepHead(t.head)
                            .setDepRelation(t.relation)
                            .putAllFeats(t.feats),
                    )
                }
                for (d in dates) {
                    val start = text.indexOf(d)
                    builder.addEntities(
                        NerEntity
                            .newBuilder()
                            .setText(d)
                            .setLabel("DATE")
                            .setCharStart(start)
                            .setCharEnd(start + d.length),
                    )
                }
                builder.build()
            }
    }

    /**
     * The matcher as a dictionary: a query meets the rows of the words it holds, as written (case and
     * diacritics folded), and nothing else. A span asked with categories meets only those; one asked
     * with none meets every row, as lex-matcher's global index does. A `Lookup` honours its classes.
     */
    private class Lexicon(
        rows: List<FuzzyMatch>,
    ) : FuzzyClient {
        private val byTerm = rows.groupBy { fold(it.candidate) }

        val batch: MutableList<SpanQuery> = mutableListOf()
        val lookups: MutableList<LookupRequest> = mutableListOf()

        override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse {
            val builder = BatchMatchResponse.newBuilder()
            for (span in request.spansList) {
                batch += span
                val asked = span.categoriesList.toSet()
                builder.addResults(
                    FuzzyMatchResponse
                        .newBuilder()
                        .addAllMatches(
                            byTerm[fold(span.query)].orEmpty().filter {
                                asked.isEmpty() ||
                                    it.category in asked
                            },
                        ),
                )
            }
            return builder.build()
        }

        override suspend fun lookup(request: LookupRequest): LookupResponse {
            lookups += request
            val rows =
                byTerm[fold(request.term)]
                    .orEmpty()
                    .filter { request.categoriesCount == 0 || it.category in request.categoriesList }
                    .filter { request.targetClassesCount == 0 || it.targetClass in request.targetClassesList }
            return LookupResponse.newBuilder().addAllCandidates(rows).build()
        }

        override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()
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
                        .setLanguage(parse.language)
                        .setOp(NlpOp.DEP_PARSE)
                        .setEngine("stanza"),
                ).build()
    }

    private companion object {
        const val CATALOG = "er.entity.catalog_sales"
        const val WEB = "er.entity.web_sales"
        const val CHANNEL_SALES = "er.entity.channel_sales"
        const val REVENUE = "er.entity.channel_sales.ext_sales_price"
        const val MARKETPLACE_REVENUE = "er.entity.catalog_sales.ext_sales_price"
        const val WAREHOUSE = "er.entity.warehouse"
        const val STORE = "er.entity.store"
        const val RETURNS = "er.entity.store_returns"
        const val MONTH = "er.entity.date_dim.month"
        const val SHARE_OF = "op:share-of"
        const val TOP_N = "op:top-n"

        fun fold(v: String): String =
            java.text.Normalizer
                .normalize(v.lowercase(), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")

        /**
         * A declared row as lex-matcher serves one off a profiled term: the term it met, an exact hit on
         * the canonical stratum. [norm] `lemma` is the matcher meeting an inflected form (`webu`) on its
         * dictionary-form stratum.
         */
        fun row(
            term: String,
            ref: String,
            targetClass: FuzzyTargetClass = FuzzyTargetClass.TARGET_CLASS_MODEL_OBJECT,
            norm: String = "canonical",
        ): FuzzyMatch =
            FuzzyMatch
                .newBuilder()
                .setCandidateId("lex:$ref:${fold(term)}")
                .setCandidate(term)
                .setScore(1.0)
                .setCategory(ref)
                .setSource(SourceTag.DECLARED)
                .setTargetRef(ref)
                .setTargetClass(targetClass)
                .setMatchMethod(if (targetClass == FuzzyTargetClass.TARGET_CLASS_OPERATOR) "TYPOS(1)" else "EXACT")
                .setProvenance(
                    Provenance
                        .newBuilder()
                        .setProducer("lex-matcher")
                        .setMethod("TATRMAN")
                        .setNorm(norm)
                        .setAlgorithm("exact"),
                ).build()

        /** Hartland's words for these objects (model + lexicon), and two stdlib operators. */
        val ROWS: List<FuzzyMatch> =
            listOf(
                row("marketplace", CATALOG),
                row("tržiště", CATALOG),
                row("web", WEB),
                row("webu", WEB, norm = "lemma"),
                row("revenue", REVENUE),
                row("revenues", REVENUE),
                row("tržby", REVENUE),
                row("tržba", REVENUE),
                row("tržeb", REVENUE),
                row("marketplace revenue", MARKETPLACE_REVENUE),
                row("tržby z tržiště", MARKETPLACE_REVENUE),
                row("distribution center", WAREHOUSE),
                row("stores", STORE),
                row("returns", RETURNS),
                row("monthly", MONTH),
                row("share", SHARE_OF, FuzzyTargetClass.TARGET_CLASS_OPERATOR),
                row("podíl", SHARE_OF, FuzzyTargetClass.TARGET_CLASS_OPERATOR),
                row("largest", TOP_N, FuzzyTargetClass.TARGET_CLASS_OPERATOR),
                row("top", TOP_N, FuzzyTargetClass.TARGET_CLASS_OPERATOR),
            )

        fun etype(
            ref: String,
            kind: String,
            owner: String,
            vararg anchors: String,
        ): EntityType =
            EntityType
                .newBuilder()
                .setRef(ref)
                .addCategories(ref)
                .addAllAnchors(anchors.toList())
                .setObjectKind(kind)
                .setOwnerRef(owner)
                .build()

        /** The archive channel's shape: model objects with their terms as anchors, no operator. */
        val REGISTRY: Registry =
            Registry
                .newBuilder()
                .addEntityTypes(etype(CATALOG, "entity_with_measures", "", "marketplace", "tržiště"))
                .addEntityTypes(etype(WEB, "entity_with_measures", "", "web"))
                .addEntityTypes(
                    etype(REVENUE, "measure", CHANNEL_SALES, "revenue", "revenues", "tržby", "tržba", "tržeb"),
                ).addEntityTypes(
                    etype(MARKETPLACE_REVENUE, "measure", CATALOG, "marketplace revenue", "tržby z tržiště"),
                ).addEntityTypes(etype(WAREHOUSE, "entity", "", "distribution center"))
                .addEntityTypes(etype(STORE, "entity", "", "stores"))
                .addEntityTypes(etype(RETURNS, "entity", "", "returns"))
                .addEntityTypes(etype(MONTH, "attribute", "er.entity.date_dim", "monthly"))
                .addLocales("en")
                .addLocales("cs")
                .setSnapshotHash("mention-retry")
                .build()

        fun resolve(
            question: Question,
            lexicon: Lexicon = Lexicon(ROWS),
            preps: FrameRolePreps = FrameRolePreps.shipped(),
        ): ResolveResponse {
            val pipeline =
                ResolverPipeline(
                    FakeNlp(question.parse),
                    lexicon,
                    SnapshotRegistry(StubRegistrySource(DeclaredVocabulary(), ""), ResolverThresholds.LIVE),
                    emptyMap(),
                    ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1"),
                    preps = preps,
                )
            val request =
                ResolveRequest
                    .newBuilder()
                    .setConversationId("mention-retry")
                    .setFresh(FreshQuestion.newBuilder().setText(question.text).setLocale(question.lang))
                    .setRegistry(REGISTRY)
                    .build()
            return runBlocking { pipeline.resolve(request) }
        }

        fun ResolveResponse.mentionOn(text: String): Mention =
            resolutionState.mentionsList.single {
                it.span.text ==
                    text
            }

        fun ResolveResponse.bindingOn(text: String): Binding = mentionOn(text).bindingsList.first()

        fun ResolveResponse.refsOn(text: String): List<String> = mentionOn(text).bindingsList.map { it.ref }

        fun ResolveResponse.gapKinds(): List<GapKind> = resolutionState.gapsList.map { it.kind }

        fun ResolveResponse.gapsOn(text: String): List<GapKind> =
            resolutionState.gapsList.filter { it.span.text == text }.map { it.kind }

        fun ResolveResponse.allBindings(): List<Binding> = resolutionState.mentionsList.flatMap { it.bindingsList }

        /**
         * [text] reached the lattice as a mention of its own that nothing bound, with a G1 over exactly
         * its words and no frame role — not one inherited from the head it was cut from.
         */
        fun ResolveResponse.shouldHaveResidue(text: String) {
            val residue = mentionOn(text)
            residue.bindingsList.shouldBeEmpty()
            residue.frameRolesList.shouldBeEmpty()
            gapsOn(text) shouldContainExactly listOf(GapKind.GAP_KIND_G1_UNBOUND)
            resolutionState.gapsList
                .single { it.span.text == text }
                .frameRolesList
                .shouldBeEmpty()
        }

        val ART = mapOf("PronType" to "Art")
        val INT = mapOf("PronType" to "Int")

        // ── English (Stanza 1.10) ────────────────────────────────────────────────────────────────

        val REVENUE_FROM_THE_MARKETPLACE =
            Question(
                "Revenue from the marketplace in 2025",
                "en",
                listOf("2025"),
                Tok("Revenue", "revenue", "NOUN", 0, "root"),
                Tok("from", "from", "ADP", 4, "case"),
                Tok("the", "the", "DET", 4, "det", ART),
                Tok("marketplace", "marketplace", "NOUN", 1, "nmod"),
                Tok("in", "in", "ADP", 6, "case"),
                Tok("2025", "2025", "NUM", 4, "nmod"),
            )

        val REVENUE_FROM_MARKETPLACE =
            Question(
                "Revenue from marketplace in 2025",
                "en",
                listOf("2025"),
                Tok("Revenue", "revenue", "NOUN", 0, "root"),
                Tok("from", "from", "ADP", 3, "case"),
                Tok("marketplace", "marketplace", "NOUN", 1, "nmod"),
                Tok("in", "in", "ADP", 5, "case"),
                Tok("2025", "2025", "NUM", 3, "nmod"),
            )

        val REVENUE_ON_THE_WEB =
            Question(
                "Revenue on the web in 2025",
                "en",
                listOf("2025"),
                Tok("Revenue", "revenue", "NOUN", 0, "root"),
                Tok("on", "on", "ADP", 4, "case"),
                Tok("the", "the", "DET", 4, "det", ART),
                Tok("web", "web", "NOUN", 1, "nmod"),
                Tok("in", "in", "ADP", 6, "case"),
                Tok("2025", "2025", "NUM", 4, "nmod"),
            )

        val REVENUE_ON_WEB =
            Question(
                "Revenue on web in 2025",
                "en",
                listOf("2025"),
                Tok("Revenue", "revenue", "NOUN", 0, "root"),
                Tok("on", "on", "ADP", 3, "case"),
                Tok("web", "web", "NOUN", 1, "nmod"),
                Tok("in", "in", "ADP", 5, "case"),
                Tok("2025", "2025", "NUM", 3, "nmod"),
            )

        val TOTAL_REVENUE =
            Question(
                "Total revenue in 2025",
                "en",
                listOf("2025"),
                Tok("Total", "total", "ADJ", 2, "amod", mapOf("Degree" to "Pos")),
                Tok("revenue", "revenue", "NOUN", 0, "root"),
                Tok("in", "in", "ADP", 4, "case"),
                Tok("2025", "2025", "NUM", 2, "nmod"),
            )

        val WHAT_SHARE =
            Question(
                "What share of total revenue came from the marketplace in 2025?",
                "en",
                listOf("2025"),
                Tok("What", "what", "DET", 2, "det", INT),
                Tok("share", "share", "NOUN", 6, "nsubj"),
                Tok("of", "of", "ADP", 5, "case"),
                Tok("total", "total", "ADJ", 5, "amod", mapOf("Degree" to "Pos")),
                Tok("revenue", "revenue", "NOUN", 2, "nmod"),
                Tok("came", "come", "VERB", 0, "root", mapOf("Mood" to "Ind", "VerbForm" to "Fin")),
                Tok("from", "from", "ADP", 9, "case"),
                Tok("the", "the", "DET", 9, "det", ART),
                Tok("marketplace", "marketplace", "NOUN", 6, "obl"),
                Tok("in", "in", "ADP", 11, "case"),
                Tok("2025", "2025", "NUM", 6, "obl"),
                Tok("?", "?", "PUNCT", 6, "punct"),
            )

        val HIGHEST_SHARE =
            Question(
                "Which distribution center had the highest share of returns with the reason Did not get it on time " +
                    "in the second half of 2025?",
                "en",
                listOf("the second half of 2025"),
                Tok("Which", "which", "DET", 3, "det", INT),
                Tok("distribution", "distribution", "NOUN", 3, "compound"),
                Tok("center", "center", "NOUN", 4, "nsubj"),
                Tok("had", "have", "VERB", 0, "root", mapOf("Mood" to "Ind", "VerbForm" to "Fin")),
                Tok("the", "the", "DET", 7, "det", ART),
                Tok("highest", "high", "ADJ", 7, "amod", mapOf("Degree" to "Sup")),
                Tok("share", "share", "NOUN", 4, "obj"),
                Tok("of", "of", "ADP", 9, "case"),
                Tok("returns", "return", "NOUN", 7, "nmod"),
                Tok("with", "with", "ADP", 12, "case"),
                Tok("the", "the", "DET", 12, "det", ART),
                Tok("reason", "reason", "NOUN", 9, "nmod"),
                Tok("Did", "do", "AUX", 15, "aux", mapOf("Mood" to "Ind", "VerbForm" to "Fin")),
                Tok("not", "not", "PART", 15, "advmod"),
                Tok("get", "get", "VERB", 12, "acl:relcl", mapOf("VerbForm" to "Inf")),
                Tok("it", "it", "PRON", 15, "obj", mapOf("PronType" to "Prs")),
                Tok("on", "on", "ADP", 18, "case"),
                Tok("time", "time", "NOUN", 15, "obl"),
                Tok("in", "in", "ADP", 22, "case"),
                Tok("the", "the", "DET", 22, "det", ART),
                Tok("second", "second", "ADJ", 22, "amod", mapOf("Degree" to "Pos")),
                Tok("half", "half", "NOUN", 15, "obl"),
                Tok("of", "of", "ADP", 24, "case"),
                Tok("2025", "2025", "NUM", 22, "nmod"),
                Tok("?", "?", "PUNCT", 4, "punct"),
            )

        val AND_ON_THE_WEB =
            Question(
                "And on the web?",
                "en",
                emptyList(),
                Tok("And", "and", "CCONJ", 4, "cc"),
                Tok("on", "on", "ADP", 4, "case"),
                Tok("the", "the", "DET", 4, "det", ART),
                Tok("web", "web", "NOUN", 0, "root"),
                Tok("?", "?", "PUNCT", 4, "punct"),
            )

        val LARGEST_STORES =
            Question(
                "What are the largest stores?",
                "en",
                emptyList(),
                Tok("What", "what", "PRON", 0, "root", INT),
                Tok("are", "be", "AUX", 1, "cop", mapOf("Mood" to "Ind", "VerbForm" to "Fin")),
                Tok("the", "the", "DET", 5, "det", ART),
                Tok("largest", "large", "ADJ", 5, "amod", mapOf("Degree" to "Sup")),
                Tok("stores", "store", "NOUN", 1, "nsubj"),
                Tok("?", "?", "PUNCT", 1, "punct"),
            )

        val MONTHLY_REVENUE =
            Question(
                "Monthly revenue in 2025",
                "en",
                listOf("Monthly", "2025"),
                Tok("Monthly", "monthly", "ADJ", 2, "amod", mapOf("Degree" to "Pos")),
                Tok("revenue", "revenue", "NOUN", 0, "root"),
                Tok("in", "in", "ADP", 4, "case"),
                Tok("2025", "2025", "NUM", 2, "nmod"),
            )

        val HOW_MANY_STORES =
            Question(
                "How many stores sold over a million?",
                "en",
                emptyList(),
                Tok("How", "how", "ADV", 2, "advmod", INT),
                Tok("many", "many", "ADJ", 3, "amod", mapOf("Degree" to "Pos")),
                Tok("stores", "store", "NOUN", 4, "nsubj"),
                Tok("sold", "sell", "VERB", 0, "root", mapOf("Mood" to "Ind", "VerbForm" to "Fin")),
                Tok("over", "over", "ADP", 7, "case"),
                Tok("a", "a", "DET", 7, "det", ART),
                Tok("million", "million", "NUM", 4, "obl"),
                Tok("?", "?", "PUNCT", 4, "punct"),
            )

        // ── Czech (Stanza 1.10's tree, MorphoDiTa's lemmas) ─────────────────────────────────────

        val TRZBY_NA_WEBU =
            Question(
                "Tržby na webu v roce 2025",
                "cs",
                listOf("roce 2025"),
                Tok("Tržby", "tržba", "NOUN", 0, "root"),
                Tok("na", "na", "ADP", 3, "case"),
                Tok("webu", "web", "NOUN", 1, "nmod"),
                Tok("v", "v", "ADP", 5, "case"),
                Tok("roce", "rok", "NOUN", 1, "nmod"),
                Tok("2025", "2025", "NUM", 5, "nummod"),
            )

        val CELKOVE_TRZBY =
            Question(
                "Celkové tržby z tržiště v roce 2025",
                "cs",
                listOf("roce 2025"),
                Tok("Celkové", "celkový", "ADJ", 2, "amod", mapOf("Degree" to "Pos")),
                Tok("tržby", "tržba", "NOUN", 0, "root"),
                Tok("z", "z", "ADP", 4, "case"),
                Tok("tržiště", "tržiště", "NOUN", 2, "nmod"),
                Tok("v", "v", "ADP", 6, "case"),
                Tok("roce", "rok", "NOUN", 4, "nmod"),
                Tok("2025", "2025", "NUM", 6, "nummod"),
            )

        val PROMOTIONAL_REVENUE =
            Question(
                "Promotional revenue in 2025",
                "en",
                listOf("2025"),
                Tok("Promotional", "promotional", "ADJ", 2, "amod", mapOf("Degree" to "Pos")),
                Tok("revenue", "revenue", "NOUN", 0, "root"),
                Tok("in", "in", "ADP", 4, "case"),
                Tok("2025", "2025", "NUM", 2, "nmod"),
            )

        val STORES_WITH_NO_RETURNS =
            Question(
                "Stores with no returns in 2025",
                "en",
                listOf("2025"),
                Tok("Stores", "store", "NOUN", 0, "root"),
                Tok("with", "with", "ADP", 4, "case"),
                Tok("no", "no", "DET", 4, "det", mapOf("PronType" to "Neg")),
                Tok("returns", "return", "NOUN", 1, "nmod"),
                Tok("in", "in", "ADP", 6, "case"),
                Tok("2025", "2025", "NUM", 4, "nmod"),
            )

        /** Hand-built in the UD shape the trees above share: no engine was asked for this one. */
        val AND_IN_PROMOTIONAL_STORES =
            Question(
                "And in promotional stores?",
                "en",
                emptyList(),
                Tok("And", "and", "CCONJ", 4, "cc"),
                Tok("in", "in", "ADP", 4, "case"),
                Tok("promotional", "promotional", "ADJ", 4, "amod", mapOf("Degree" to "Pos")),
                Tok("stores", "store", "NOUN", 0, "root"),
                Tok("?", "?", "PUNCT", 4, "punct"),
            )

        val PODIL_AKCNICH_TRZEB =
            Question(
                "Podíl akčních tržeb",
                "cs",
                emptyList(),
                Tok("Podíl", "podíl", "NOUN", 0, "root"),
                Tok("akčních", "akční", "ADJ", 3, "amod", mapOf("Degree" to "Pos")),
                Tok("tržeb", "tržba", "NOUN", 1, "nmod"),
            )

        val CELKOVE_TRZBY_V_ROCE =
            Question(
                "Celkové tržby v roce 2025",
                "cs",
                listOf("roce 2025"),
                Tok("Celkové", "celkový", "ADJ", 2, "amod", mapOf("Degree" to "Pos")),
                Tok("tržby", "tržba", "NOUN", 0, "root"),
                Tok("v", "v", "ADP", 4, "case"),
                Tok("roce", "rok", "NOUN", 2, "nmod"),
                Tok("2025", "2025", "NUM", 4, "nummod"),
            )

        val NEJVYSSI_PODIL =
            Question(
                "Které distribuční centrum mělo ve 2. pololetí 2025 nejvyšší podíl reklamací s důvodem Nedorazilo včas?",
                "cs",
                listOf("2. pololetí 2025"),
                Tok("Které", "který", "DET", 3, "det", mapOf("PronType" to "Int,Rel")),
                Tok("distribuční", "distribuční", "ADJ", 3, "amod", mapOf("Degree" to "Pos")),
                Tok("centrum", "centrum", "NOUN", 4, "nsubj"),
                Tok("mělo", "mít", "VERB", 0, "root", mapOf("VerbForm" to "Part")),
                Tok("ve", "v", "ADP", 8, "case"),
                Tok("2", "2", "NUM", 8, "nummod"),
                Tok(".", ".", "PUNCT", 6, "punct"),
                Tok("pololetí", "pololetí", "NOUN", 4, "obl"),
                Tok("2025", "2025", "NUM", 8, "nummod"),
                Tok("nejvyšší", "vysoký", "ADJ", 11, "amod", mapOf("Degree" to "Sup")),
                Tok("podíl", "podíl", "NOUN", 15, "obj"),
                Tok("reklamací", "reklamace", "NOUN", 11, "nmod"),
                Tok("s", "s", "ADP", 14, "case"),
                Tok("důvodem", "důvod", "NOUN", 11, "nmod"),
                Tok("Nedorazilo", "dorazit", "VERB", 4, "conj", mapOf("VerbForm" to "Part")),
                Tok("včas", "včas", "ADV", 15, "advmod"),
                Tok("?", "?", "PUNCT", 4, "punct"),
            )
    }
}
