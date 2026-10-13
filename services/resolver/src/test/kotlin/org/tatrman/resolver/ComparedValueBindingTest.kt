// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
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
import org.tatrman.fuzzy.v1.TokenHit
import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Capability
import org.tatrman.nlp.v1.NlpOp
import org.tatrman.nlp.v1.StatusResponse
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.client.NlpClient
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.LookupRoundConfig
import org.tatrman.resolver.pipeline.Literals
import org.tatrman.resolver.pipeline.OperatorWords
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.Domain
import org.tatrman.resolver.v1.EntityType
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.Reach
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolveContext
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.fuzzy.v1.TargetClass as FuzzyTargetClass

/**
 * ttr-server#207 — in a comparison, the second compared value names its own object, never the operator.
 *
 * „Porovnej tržby z tržiště a z webu v roce 2025“ came back with `webu` → `op:compare` in
 * `Resolution.bindings` (`Domain.entity_type_ref`), while its candidate — and the lattice — said
 * `er.entity.web_sales.ext_sales_price`. Neither an operator-argument rule nor the conjunction did it.
 * Three wire facts did, together:
 *
 *  1. `webu` binds in a LOOKUP ROUND, not the broad pass: the broad pass asks it in `er.entity.web_sales`
 *     (its lemma `web` is that entity's term) and that category holds nothing for it; the round asks
 *     the matcher across all categories.
 *  2. A cross-category `Lookup` answers every declared row with `category = "unknown"`: lex-matcher has
 *     no category to stamp on it (`FuzzyMatcher`'s `category ?: this.category ?: "unknown"`).
 *  3. `OperatorWords` registered the operator it confirmed for `Porovnej` — asked the same
 *     cross-category way — under that row's category. So the request's `op:compare` type claimed the
 *     category `unknown`, and the door, which names a declared row by the type that claims its
 *     category, named every round-bound row `op:compare`. With no operator word in the question the
 *     same rows were named `unknown`.
 *
 * Through the whole pipeline, over the parses hartland's nlp returned for these questions and the rows
 * its lex-matcher answered, quoted from the live wire. The matcher here keeps the three facts above:
 * each lexicon target ref is its own category; a scoped answer carries the category asked; a
 * cross-category `Lookup` answer carries `unknown`.
 *
 * **Provenance (2026-10-13, hartland, resolver 0.11.20, cs world).**
 *  - Parses (`compare-argument/<question>.parse.json`): the `parse` of each live `ResolverService/Resolve`
 *    answer, as returned (`traceId` and `elapsedMs` dropped). cs: MorphoDiTa
 *    `czech-morfflex2.0-pdtc1.0-220710` tokens, lemmas and tags, Stanza 1.10.0 dependencies, NameTag 3
 *    `nametag3-czech-cnec2.0-240830` entities. en: Stanza 1.10.0 throughout.
 *  - Matcher rows: the resolver's DEBUG log of the same requests (`FuzzyService/BatchMatch` and
 *    `FuzzyService/Lookup` responses), top rows, every field the resolver reads.
 *  - Registry: the projection of hartland's compiled lexicon (`ttr-lexicon-compiled/v5`) for the refs
 *    these questions reach — one type per ref, `categories = [ref]`, anchors = its terms, kind and
 *    owner from `targets`, as `LexiconArchiveRegistrySource` builds it. No operator is declared: the
 *    archive channel declares none.
 */
class ComparedValueBindingTest :
    StringSpec({

        // ── the bug ───────────────────────────────────────────────────────────────────────────

        "#207 „Porovnej tržby z tržiště a z webu v roce 2025“ — `webu` names web revenue, not the operator" {
            val turn = resolve(POROVNEJ)

            // the lattice
            turn.refsOn("Porovnej") shouldContainExactly listOf(COMPARE)
            turn.refsOn("tržby z tržiště") shouldContainExactly listOf(CATALOG_REVENUE)
            turn.refsOn("webu") shouldContainExactly listOf(WEB_REVENUE)
            // the door
            turn.domainOn("Porovnej").entityTypeRef shouldBe COMPARE
            turn.domainOn("tržby z tržiště").entityTypeRef shouldBe CATALOG_REVENUE
            turn.domainOn("webu").entityTypeRef shouldBe WEB_REVENUE
            turn.domainOn("webu").candidatesList.map { it.targetRef } shouldContainExactly listOf(WEB_REVENUE)
            turn.operatorSpans() shouldContainExactly listOf("Porovnej")
            turn.doorAgreesWithLattice()
        }

        "#207 „Srovnej tržby z tržiště a z webu“" {
            val turn = resolve(SROVNEJ)

            turn.refsOn("Srovnej") shouldContainExactly listOf(COMPARE)
            turn.refsOn("tržby z tržiště") shouldContainExactly listOf(CATALOG_REVENUE)
            turn.refsOn("webu") shouldContainExactly listOf(WEB_REVENUE)
            turn.domainOn("webu").entityTypeRef shouldBe WEB_REVENUE
            turn.operatorSpans() shouldContainExactly listOf("Srovnej")
            turn.doorAgreesWithLattice()
        }

        "#207 „Srovnání tržeb z tržiště a z webu“ — the noun trigger: the lattice's operator, and `webu` named right" {
            val turn = resolve(SROVNANI)

            turn.refsOn("Srovnání") shouldContainExactly listOf(COMPARE)
            turn.refsOn("tržeb z tržiště") shouldContainExactly listOf(CATALOG_REVENUE)
            turn.refsOn("webu") shouldContainExactly listOf(WEB_REVENUE)
            // was `unknown`: no operator word here, so nothing claimed the placeholder, and the door
            // named the row by it
            turn.domainOn("webu").entityTypeRef shouldBe WEB_REVENUE
            turn.doorAgreesWithLattice()
        }

        // ── the guards ────────────────────────────────────────────────────────────────────────

        "a plain conjunction „Tržby z tržiště a z webu v roce 2025“ binds both channels, on both sides" {
            val turn = resolve(CONJUNCTION)

            turn.refsOn("Tržby z tržiště") shouldContainExactly listOf(CATALOG_REVENUE)
            turn.refsOn("webu") shouldContainExactly listOf(WEB_REVENUE)
            turn.domainOn("Tržby z tržiště").entityTypeRef shouldBe CATALOG_REVENUE
            turn.domainOn("webu").entityTypeRef shouldBe WEB_REVENUE
            turn.operatorSpans().shouldBeEmpty()
            turn.doorAgreesWithLattice()
        }

        "the en twin „Compare marketplace and web revenue in 2025“ binds both channels and one operator" {
            val turn = resolve(COMPARE_EN)

            turn.refsOn("Compare") shouldContainExactly listOf(COMPARE)
            turn.refsOn("marketplace") shouldContainExactly listOf(CATALOG_SALES)
            turn.refsOn("web revenue") shouldContainExactly listOf(WEB_REVENUE)
            turn.domainOn("Compare").entityTypeRef shouldBe COMPARE
            turn.domainOn("marketplace").entityTypeRef shouldBe CATALOG_SALES
            turn.domainOn("web revenue").entityTypeRef shouldBe WEB_REVENUE
            turn.operatorSpans() shouldContainExactly listOf("Compare")
            turn.doorAgreesWithLattice()
        }

        "share-of „Podíl webu na tržbách“ — the operator is the lattice's, and its argument names web revenue" {
            val turn = resolve(PODIL)

            turn.refsOn("Podíl") shouldContainExactly listOf(SHARE_OF)
            turn.refsOn("webu") shouldContainExactly listOf(WEB_REVENUE)
            turn.domainOn("webu").entityTypeRef shouldBe WEB_REVENUE
            turn.doorAgreesWithLattice()
        }

        "the control „Tržby z webu v roce 2025“ is unchanged: web revenue, bound in the broad pass" {
            val turn = resolve(TRZBY_Z_WEBU)

            turn.refsOn("Tržby z webu") shouldContainExactly listOf(WEB_REVENUE)
            turn.domainOn("Tržby z webu").entityTypeRef shouldBe WEB_REVENUE
            turn.operatorSpans().shouldBeEmpty()
            turn.doorAgreesWithLattice()
        }

        // ── where it went wrong ───────────────────────────────────────────────────────────────

        "an operator confirmed by asking is registered under its own ref, not the matcher's no-category placeholder" {
            val matcher = WireMatcher()
            val words =
                runBlocking {
                    OperatorWords.find(
                        matcher,
                        POROVNEJ.parse,
                        Literals.NONE,
                        ResolverThresholds.LIVE,
                        LookupRoundConfig.DEFAULT,
                    )
                }

            // the wire really does answer `unknown` here — the fact the bug stood on
            matcher.lookups
                .single { it.term == "Porovnej" }
                .categoriesList
                .shouldBeEmpty()
            val compare = words.single().operators.single()
            compare.ref shouldBe COMPARE
            compare.categories shouldContainExactly listOf(COMPARE)
        }

        "the broad pass asks an operator word in the operator's own category, never in `unknown`, and binds it there" {
            val matcher = WireMatcher()
            val turn = resolve(POROVNEJ, matcher)

            val asked = matcher.batches.flatMap { b -> b.spansList.filter { it.query == "Porovnej" } }
            asked.flatMap { it.categoriesList } shouldNotContain NO_CATEGORY
            asked.any { COMPARE in it.categoriesList } shouldBe true
            turn.refsOn("Porovnej") shouldContainExactly listOf(COMPARE)
            // bound by the broad pass, so no lookup round asks about it again (only `OperatorWords` did)
            matcher.lookups
                .filter { it.term == "Porovnej" }
                .map { it.targetClassesList } shouldContainExactly
                listOf(listOf(FuzzyTargetClass.TARGET_CLASS_OPERATOR))
        }
    }) {
    private class Question(
        val text: String,
        val lang: String,
        resource: String,
    ) {
        val parse: AnalyzeResponse =
            run {
                val url =
                    checkNotNull(ComparedValueBindingTest::class.java.getResource("/compare-argument/$resource")) {
                        "missing fixture $resource"
                    }
                AnalyzeResponse
                    .newBuilder()
                    .also {
                        com.google.protobuf.util.JsonFormat
                            .parser()
                            .merge(url.readText(), it)
                    }.build()
                    .also { p ->
                        p.tokensList.forEach { t ->
                            check(
                                text.substring(t.charStart, t.charEnd) == t.text,
                            ) { "offsets of '${t.text}' in '$text'" }
                        }
                    }
            }
    }

    private class Turn(
        val response: ResolveResponse,
    ) {
        /** The refs bound on the one lattice mention spelled [text]. */
        fun refsOn(text: String): List<String> =
            withClue("one mention spelled '$text' in ${response.resolutionState.mentionsList.map { it.span.text }}") {
                response.resolutionState.mentionsList
                    .single { it.span.text == text }
                    .bindingsList
                    .map { it.ref }
            }

        /** The door's domain binding on the span spelled [text]. */
        fun domainOn(text: String): Domain =
            withClue("one door binding spelled '$text' in ${response.resolution.bindingsList.map { it.span.text }}") {
                response.resolution.bindingsList
                    .single { it.hasDomain() && it.span.text == text }
                    .domain
            }

        /** The door's spans whose entity type is an operator. */
        fun operatorSpans(): List<String> =
            response.resolution.bindingsList
                .filter { it.hasDomain() && it.domain.entityTypeRef.startsWith("op:") }
                .map { it.span.text }

        /**
         * Every door binding over a span the lattice also bound names what the lattice bound there
         * (RV-P2.3: a caller reading `Resolution` and one reading the lattice get the same story).
         */
        fun doorAgreesWithLattice() {
            val lattice =
                response.resolutionState.mentionsList
                    .filter { it.bindingsCount > 0 }
                    .associate { (it.span.start to it.span.end) to it.bindingsList.first().ref }
            for (b in response.resolution.bindingsList.filter { it.hasDomain() }) {
                val bound = lattice[b.span.start to b.span.end] ?: continue
                withClue("door binding on '${b.span.text}'") { b.domain.entityTypeRef shouldBe bound }
            }
        }
    }

    /**
     * lex-matcher as hartland runs it, over the rows it answered for these questions.
     *
     * The three facts the resolver has to read correctly: every lexicon target ref is its own category
     * (`LexiconArchiveSource`), so a category it does not hold — `unknown` among them — answers nothing;
     * a scoped answer carries the category asked; a cross-category `Lookup` answer carries `unknown`.
     */
    private class WireMatcher : FuzzyClient {
        val batches = mutableListOf<BatchMatchRequest>()
        val lookups = mutableListOf<LookupRequest>()

        override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse {
            batches += request
            val builder = BatchMatchResponse.newBuilder()
            for (span in request.spansList) {
                val rows = rowsFor(span.query).filter { it.ref in span.categoriesList }.map { it.match(it.ref) }
                builder.addResults(FuzzyMatchResponse.newBuilder().addAllMatches(rows))
            }
            return builder.build()
        }

        override suspend fun lookup(request: LookupRequest): LookupResponse {
            lookups += request
            val crossCategory = request.categoriesCount == 0
            val rows =
                rowsFor(request.term)
                    .filter { crossCategory || it.ref in request.categoriesList }
                    .filter { request.targetClassesCount == 0 || it.targetClass in request.targetClassesList }
                    .map { it.match(if (crossCategory) NO_CATEGORY else it.ref) }
                    .take(if (request.maxCandidates > 0) request.maxCandidates else 10)
            return LookupResponse.newBuilder().addAllCandidates(rows).build()
        }

        override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()

        private fun rowsFor(query: String): List<Row> = ANSWERS[fold(query)].orEmpty()
    }

    /** One lexicon row as the matcher scored it; [algorithm] is the profile stratum that fired. */
    private data class Row(
        val candidate: String,
        val ref: String,
        val score: Double,
        val method: String,
        val targetClass: FuzzyTargetClass,
        val algorithm: String,
        val coverage: Double,
        val margin: Double? = null,
        val autoBindable: Boolean? = null,
    ) {
        fun match(category: String): FuzzyMatch {
            val b =
                FuzzyMatch
                    .newBuilder()
                    .setCandidateId("lex:$ref:${fold(candidate)}")
                    .setCandidate(candidate)
                    .setScore(score)
                    .setCategory(category)
                    .setSource(SourceTag.DECLARED)
                    .setTargetRef(ref)
                    .setMatchMethod(method)
                    .setTargetClass(targetClass)
                    .setProvenance(
                        Provenance
                            .newBuilder()
                            .setProducer("fuzzy")
                            .setMethod("TATRMAN_V2")
                            .setRawScore(score)
                            .setNorm("canonical")
                            .setAlgorithm(algorithm)
                            .setCoverage(coverage)
                            .addTokenHits(
                                TokenHit
                                    .newBuilder()
                                    .setQueryToken(fold(candidate).substringBefore(' '))
                                    .setCandidateToken(fold(candidate).substringBefore(' '))
                                    .setKind("exact"),
                            ),
                    )
            margin?.let { b.uniquenessMargin = it }
            autoBindable?.let { b.autoBindable = it }
            return b.build()
        }
    }

    private class FakeNlp(
        private val parse: AnalyzeResponse,
    ) : NlpClient {
        override suspend fun analyze(request: AnalyzeRequest): AnalyzeResponse = parse

        override suspend fun getStatus(): StatusResponse =
            StatusResponse
                .newBuilder()
                .setReady(true)
                .addCapabilities(Capability.newBuilder().setLanguage(parse.language).setOp(NlpOp.NER))
                .addCapabilities(Capability.newBuilder().setLanguage(parse.language).setOp(NlpOp.DEP_PARSE))
                .build()
    }

    private companion object {
        const val COMPARE = "op:compare"
        const val SHARE_OF = "op:share-of"
        const val CATALOG_SALES = "er.entity.catalog_sales"
        const val WEB_SALES = "er.entity.web_sales"
        const val CHANNEL_SALES = "er.entity.channel_sales"
        const val CATALOG_REVENUE = "er.entity.catalog_sales.ext_sales_price"
        const val WEB_REVENUE = "er.entity.web_sales.ext_sales_price"
        const val CHANNEL_REVENUE = "er.entity.channel_sales.ext_sales_price"
        const val DATE_DIM = "er.entity.date_dim"
        const val YEAR = "er.entity.date_dim.year"
        const val CHRONO = "ground:chrono"

        /** What lex-matcher writes as the category of a row a cross-category lookup found. */
        const val NO_CATEGORY = "unknown"

        val POROVNEJ = Question("Porovnej tržby z tržiště a z webu v roce 2025", "cs", "porovnej.parse.json")
        val SROVNEJ = Question("Srovnej tržby z tržiště a z webu", "cs", "srovnej.parse.json")
        val SROVNANI = Question("Srovnání tržeb z tržiště a z webu", "cs", "srovnani.parse.json")
        val CONJUNCTION = Question("Tržby z tržiště a z webu v roce 2025", "cs", "conjunction.parse.json")
        val PODIL = Question("Podíl webu na tržbách", "cs", "podil.parse.json")
        val TRZBY_Z_WEBU = Question("Tržby z webu v roce 2025", "cs", "trzby-z-webu.parse.json")
        val COMPARE_EN = Question("Compare marketplace and web revenue in 2025", "en", "compare-en.parse.json")

        private val OBJECT = FuzzyTargetClass.TARGET_CLASS_MODEL_OBJECT
        private val OPERATOR = FuzzyTargetClass.TARGET_CLASS_OPERATOR
        private val TRIGGER = FuzzyTargetClass.TARGET_CLASS_GROUNDING_TRIGGER

        fun op(
            term: String,
            ref: String,
        ) = Row(term, ref, 1.0, "TYPOS(1)", OPERATOR, "exact", 1.0)

        val CATALOG_REVENUE_ROWS =
            listOf(
                Row("tržba z tržiště", CATALOG_REVENUE, 1.167625, "TOKENS", OBJECT, "tokens", 1.0, 1.167625, true),
                Row("tržby z tržiště", CATALOG_REVENUE, 1.167625, "TOKENS", OBJECT, "tokens", 1.0, 1.167625, true),
                Row("tržeb z tržiště", CATALOG_REVENUE, 1.167625, "TOKENS", OBJECT, "tokens", 1.0, 1.167625, true),
                Row(
                    "obrat z tržiště",
                    CATALOG_REVENUE,
                    0.5521300726055423,
                    "TOKENS",
                    OBJECT,
                    "tokens",
                    0.55,
                    1.167625,
                    true,
                ),
            )

        /**
         * What hartland's matcher answered for `webu` — by its lemma `web` against the English alias,
         * and by surface against the Czech ones. Every row web revenue; none of them the bare entity.
         */
        val WEBU_ROWS =
            listOf(
                Row(
                    "web revenue",
                    WEB_REVENUE,
                    1.0046870428380763,
                    "TOKENS",
                    OBJECT,
                    "tokens",
                    0.4687042838076377,
                    1.0046870428380763,
                    true,
                ),
                Row(
                    "tržba z webu",
                    WEB_REVENUE,
                    1.0037746534739906,
                    "TOKENS",
                    OBJECT,
                    "tokens",
                    0.3774653473990618,
                    1.0046870428380763,
                    true,
                ),
                Row(
                    "obrat z webu",
                    WEB_REVENUE,
                    1.0036707829327178,
                    "TOKENS",
                    OBJECT,
                    "tokens",
                    0.367078293271775,
                    1.0046870428380763,
                    true,
                ),
                Row(
                    "tržby z webu",
                    WEB_REVENUE,
                    1.0036004861002135,
                    "TOKENS",
                    OBJECT,
                    "tokens",
                    0.36004861002135036,
                    1.0046870428380763,
                    true,
                ),
                Row(
                    "obratu z webu",
                    WEB_REVENUE,
                    1.003505859709591,
                    "TOKENS",
                    OBJECT,
                    "tokens",
                    0.3505859709590938,
                    1.0046870428380763,
                    true,
                ),
            )

        val ANSWERS: Map<String, List<Row>> =
            mapOf(
                "porovnej" to listOf(op("porovnej", COMPARE)),
                "srovnej" to listOf(op("srovnej", COMPARE)),
                "srovnani" to listOf(op("srovnání", COMPARE)),
                "compare" to listOf(op("compare", COMPARE)),
                "podil" to listOf(op("podíl", SHARE_OF)),
                "trzby z trziste" to CATALOG_REVENUE_ROWS,
                "trzeb z trziste" to CATALOG_REVENUE_ROWS,
                "webu" to WEBU_ROWS,
                // the lemma slot: a profile row (norm + algorithm), which `LemmaLookup` declines
                "web" to listOf(Row("web", WEB_SALES, 1.0, "EXACT", OBJECT, "exact", 1.0)),
                "roce" to
                    listOf(
                        Row("za rok", YEAR, 1.005, "TOKENS", OBJECT, "tokens", 0.5, 1.005, true),
                        Row("fiskální rok", CHRONO, 1.0044, "TOKENS", TRIGGER, "tokens", 0.44),
                        Row("roce", CHRONO, 1.0, "TYPOS(1)", TRIGGER, "exact", 1.0),
                    ),
                "trzby z webu" to
                    listOf(
                        Row("tržba z webu", WEB_REVENUE, 1.167625, "TOKENS", OBJECT, "tokens", 1.0, 1.167625, true),
                        Row("tržby z webu", WEB_REVENUE, 1.167625, "TOKENS", OBJECT, "tokens", 1.0, 1.167625, true),
                        Row("tržby z e-shopu", WEB_REVENUE, 0.6382, "TOKENS", OBJECT, "tokens", 0.64, 1.167625, true),
                        Row(
                            "obrat z webu",
                            WEB_REVENUE,
                            0.5521300726055423,
                            "TOKENS",
                            OBJECT,
                            "tokens",
                            0.55,
                            1.167625,
                            true,
                        ),
                    ),
                "marketplace" to listOf(Row("marketplace", CATALOG_SALES, 1.0, "EXACT", OBJECT, "exact", 1.0)),
                "web revenue" to
                    listOf(
                        Row("web revenue", WEB_REVENUE, 1.06, "TOKENS", OBJECT, "tokens", 1.0, 1.06, true),
                        Row("online revenue", WEB_REVENUE, 0.5815, "TOKENS", OBJECT, "tokens", 0.58, 1.06, true),
                    ),
                // `tržbách`'s lemma, a profile row again; `tržbách` itself answers nothing usable
                "trzba" to
                    listOf(
                        Row("tržba", CHANNEL_REVENUE, 1.0, "TYPOS(1)", OBJECT, "exact", 1.0),
                        Row("tržby", CHANNEL_REVENUE, 0.95, "TYPOS(1)", OBJECT, "typos", 0.95),
                    ),
            )

        val REGISTRY: Registry =
            Registry
                .newBuilder()
                .addEntityTypes(
                    type(
                        CATALOG_SALES,
                        "entity_with_measures",
                        terms =
                            listOf(
                                "tržiště",
                                "e=market",
                                "market",
                                "objednávky z tržiště",
                                "marketplace",
                                "marketplace orders",
                            ),
                    ),
                ).addEntityTypes(
                    type(
                        WEB_SALES,
                        "entity_with_measures",
                        terms = listOf("web", "online", "e-shop", "webové objednávky", "web orders"),
                    ),
                ).addEntityTypes(
                    type(
                        CHANNEL_SALES,
                        "entity_with_measures",
                        terms = listOf("prodeje napříč kanály", "all-channel sales"),
                    ),
                ).addEntityTypes(
                    type(
                        CATALOG_REVENUE,
                        "measure",
                        CATALOG_SALES,
                        listOf(
                            "tržba z tržiště",
                            "tržby z tržiště",
                            "tržeb z tržiště",
                            "obrat z tržiště",
                            "marketplace revenue",
                        ),
                    ),
                ).addEntityTypes(
                    type(
                        WEB_REVENUE,
                        "measure",
                        WEB_SALES,
                        listOf(
                            "tržba z webu",
                            "tržby z webu",
                            "tržby z e-shopu",
                            "obrat z webu",
                            "web revenue",
                            "online revenue",
                        ),
                    ),
                ).addEntityTypes(
                    type(
                        CHANNEL_REVENUE,
                        "measure",
                        CHANNEL_SALES,
                        listOf("tržba", "tržby", "tržeb", "obrat", "revenue", "revenues"),
                    ),
                ).addEntityTypes(
                    type(DATE_DIM, "entity", terms = listOf("kalendář", "calendar"))
                        .toBuilder()
                        .addAllReachedFrom(
                            listOf(CATALOG_SALES, WEB_SALES, CHANNEL_SALES).map {
                                Reach
                                    .newBuilder()
                                    .setFactRef(it)
                                    .setMandatory(true)
                                    .build()
                            },
                        ).build(),
                ).addEntityTypes(
                    type(YEAR, "attribute", DATE_DIM, listOf("za rok", "po letech", "ročně", "by year", "yearly")),
                ).addLocales("cs")
                .addLocales("en")
                .setSnapshotHash("compare-argument")
                .build()

        fun type(
            ref: String,
            kind: String,
            owner: String = "",
            terms: List<String>,
        ): EntityType =
            EntityType
                .newBuilder()
                .setRef(ref)
                .addCategories(ref)
                .addAllAnchors(terms)
                .setObjectKind(kind)
                .setOwnerRef(owner)
                .build()

        fun fold(v: String): String =
            java.text.Normalizer
                .normalize(v.lowercase().trim(), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")

        fun resolve(
            question: Question,
            matcher: WireMatcher = WireMatcher(),
        ): Turn {
            val pipeline =
                ResolverPipeline(
                    FakeNlp(question.parse),
                    matcher,
                    SnapshotRegistry(StubRegistrySource(DeclaredVocabulary(), ""), ResolverThresholds.LIVE),
                    emptyMap(),
                    ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1"),
                )
            val response =
                runBlocking {
                    pipeline.resolve(
                        ResolveRequest
                            .newBuilder()
                            .setConversationId("compare-argument")
                            .setFresh(FreshQuestion.newBuilder().setText(question.text).setLocale(question.lang))
                            .setContext(ResolveContext.newBuilder().setReferenceDatetime("2026-10-13T00:00:00Z"))
                            .setRegistry(REGISTRY)
                            .build(),
                    )
                }
            return Turn(response)
        }
    }
}
