// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import com.google.protobuf.util.JsonFormat
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.fuzzy.v1.LookupRequest
import org.tatrman.fuzzy.v1.LookupResponse
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.grounding.v1.DateTimeInterval
import org.tatrman.grounding.v1.FilterRecipe
import org.tatrman.grounding.v1.GetStatusResponse
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import org.tatrman.grounding.v1.GroundingResult
import org.tatrman.grounding.v1.Normalized
import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Capability
import org.tatrman.nlp.v1.NlpOp
import org.tatrman.nlp.v1.StatusResponse
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.client.GroundingClient
import org.tatrman.resolver.client.NlpClient
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.ResolveContext
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.fuzzy.v1.TargetClass as FuzzyTargetClass

/**
 * ttr-demo#62 (ruled 2026-10-12) — a bare store word („prodejna / prodejny / prodejen“, "stores") reads
 * by its ROLE, through the whole pipeline.
 *
 * Hartland's lexicon gives the bare word two readings in one tie band: the store DIMENSION
 * (`er.entity.store`) and the Stores CHANNEL — in cs its revenue (`er.entity.store_sales.ext_sales_price`,
 * a measure), in en the channel fact (`er.entity.store_sales`). The ruling:
 *
 *  - **grouping** („podle prodejen“, "by stores") → the dimension, no ask;
 *  - **filter** („Tržby prodejen v roce 2025“, "Revenue of stores in 2025") → ASK, offering both readings.
 *    Before, the filter slot under a measure head picked the channel and nothing was asked;
 *  - **declared phrases** („tržby z prodejen“, "store revenue") → the store-channel measure, answered;
 *  - and a store word that **governs a store name** („Tržby prodejny Praha“, "stores in Praha") names
 *    the dimension, with the name its member — no ask. Before, the governed-value slot never fired (it
 *    looked for an `anchorHeadToken` span proposal does not set on a governed value), so the filter rule
 *    read these too, and would have started asking once the filter asked.
 *
 * Counting („Kolik máme prodejen?“) keeps the dimension, and the bare word alone („Prodejny“) still asks.
 * Coordination (ruled 2026-10-14): joined by „a“ / „nebo“ / "and" / "or" to a mention with ONE reading,
 * the store word takes the reading the two share — the channel beside `web` / `tržiště` (a measure
 * standing for its fact), the dimension beside `sklady` — and nothing is asked.
 *
 * **Provenance (2026-10-14).**
 *  - Parses (`store-word-role/<question>.parse.json`): hartland's nlp, as returned (`traceId` and
 *    `elapsedMs` dropped). cs: MorphoDiTa `czech-morfflex2.0-pdtc1.0-220710` tokens, lemmas and tags,
 *    Stanza 1.10.0 dependencies, NameTag 3 `nametag3-czech-cnec2.0-240830` entities. en: Stanza 1.10.0.
 *  - Registry (`store-word-role/registry.json`): the projection `LexiconArchiveRegistrySource` builds from
 *    the hartland lexicon archive that gives the bare store word both readings (ttr-demo#73 at `5842901`,
 *    `sha256:cbd74704…`): in cs the store dimension and the store-channel revenue, in en the dimension and the
 *    Stores channel fact.
 *  - Matcher rows (`store-word-role/matcher.json`): every row lex-matcher 0.11.3 answered to these
 *    questions, keyed by the folded query — the declared rows from a matcher over that archive, the
 *    member rows (`Praha`) from hartland's live member index, which the archive does not carry. Each
 *    question was run before and after this change against both, so the file holds what either asks.
 *  - The grounding kernel: chrono on a four-digit year, anchored on `er.entity.date_dim.cal_date`, as live.
 */
class StoreWordRoleTest :
    StringSpec({

        // ── filter: ask ─────────────────────────────────────────────────────────────────────────

        "„Tržby prodejen v roce 2025“ — a store word in a filter ASKS: the store dimension or the store revenue" {
            val turn = resolve(TRZBY_PRODEJEN)

            turn.lattice.summary() shouldContainExactly
                listOf(
                    "M Tržby → $REVENUE [SUBJECT, MEASURE]",
                    "M prodejen → $STORE, $STORE_CHANNEL_REVENUE []",
                    "M roce → $CHRONO [FILTER]",
                    "V 2025 GROUNDED → $CAL_DATE ⟵ $CHRONO",
                )
            turn.asks("prodejen", STORE_CHANNEL_REVENUE to "measure")
        }

        "\"Revenue of stores in 2025\" — the same question in English (its channel reading is the fact)" {
            val turn = resolve(REVENUE_OF_STORES)

            turn.lattice.summary() shouldContainExactly
                listOf(
                    "M Revenue → $REVENUE [SUBJECT, MEASURE]",
                    "M stores → $STORE, $STORE_SALES [FILTER]",
                    "V 2025 GROUNDED → $CAL_DATE",
                )
            turn.asks("stores", STORE_SALES to "entity_with_measures")
        }

        // ── a governed store name: the dimension and its member, no ask ────────────────────────

        "„Tržby prodejny Praha v roce 2025“ — a store word that governs a store name reads the DIMENSION" {
            val turn = resolve(TRZBY_PRODEJNY_PRAHA)

            turn.lattice.summary() shouldContainExactly
                listOf(
                    "M Tržby → $REVENUE [SUBJECT, MEASURE]",
                    "M prodejny → $STORE []",
                    "M roce → $CHRONO [FILTER]",
                    "V Praha LITERAL → $STORE_NAME#Praha",
                    "V 2025 GROUNDED → $CAL_DATE ⟵ $CHRONO",
                )
            turn.answers()
        }

        "\"Revenue of stores in Praha in 2025\" — the same in English" {
            val turn = resolve(REVENUE_OF_STORES_IN_PRAHA)

            turn.lattice.summary() shouldContainExactly
                listOf(
                    "M Revenue → $REVENUE [SUBJECT, MEASURE]",
                    "M stores → $STORE [FILTER]",
                    "V Praha LITERAL → $STORE_NAME#Praha",
                    "V 2025 GROUNDED → $CAL_DATE",
                )
            turn.answers()
        }

        // ── unchanged ───────────────────────────────────────────────────────────────────────────

        "grouping — „Tržby podle prodejen v roce 2025“ and \"Revenue by stores in 2025\" group by the dimension" {
            resolve(PODLE_PRODEJEN).also { it.answers() }.lattice.summary() shouldContainExactly
                listOf(
                    "M Tržby → $REVENUE [SUBJECT, MEASURE]",
                    "M prodejen → $STORE [GROUPING]",
                    "M roce → $CHRONO [FILTER]",
                    "V 2025 GROUNDED → $CAL_DATE ⟵ $CHRONO",
                )
            resolve(REVENUE_BY_STORES).also { it.answers() }.lattice.summary() shouldContainExactly
                listOf(
                    "M Revenue → $REVENUE [SUBJECT, MEASURE]",
                    "M stores → $STORE [GROUPING]",
                    "V 2025 GROUNDED → $CAL_DATE",
                )
        }

        "declared phrases — „Tržby z prodejen za 2025“ and \"Store revenue in 2025\" are the store-channel measure" {
            resolve(Z_PRODEJEN).also { it.answers() }.lattice.summary() shouldContainExactly
                listOf(
                    "M Tržby z prodejen → $STORE_CHANNEL_REVENUE [SUBJECT, MEASURE]",
                    "V 2025 GROUNDED → $CAL_DATE",
                )
            resolve(STORE_REVENUE).also { it.answers() }.lattice.summary() shouldContainExactly
                listOf(
                    "M Store revenue → $STORE_CHANNEL_REVENUE [SUBJECT, MEASURE]",
                    "V 2025 GROUNDED → $CAL_DATE",
                )
        }

        "count keeps the dimension; the bare word still asks" {
            resolve(KOLIK).also { it.answers() }.lattice.summary() shouldContainExactly
                listOf("M prodejen → $STORE [SUBJECT]")
            resolve(PRODEJNY).asks("Prodejny", STORE_CHANNEL_REVENUE to "measure")
        }

        // ── coordination (ruled 2026-10-14): the reading where the two mentions intersect ──────────
        //
        // Two mentions joined by „a“ / „nebo“ / "and" / "or", one with two readings and the other with
        // one: the ambiguous one takes the reading they share, and nothing is asked. A filter does not
        // override it — coordination is read first. In coordination a measure stands for its fact, so
        // the cs store word's revenue reading meets `web` / `tržiště`, which are channel facts.

        "coordination — „Tržby prodejen a/nebo webu / tržiště v roce 2025“, either order: the store revenue, no ask" {
            for ((question, sibling) in listOf(
                TRZBY_PRODEJEN_A_WEBU to "M webu → $WEB_REVENUE [MEASURE]",
                TRZBY_PRODEJEN_NEBO_WEBU to "M webu → $WEB_REVENUE [MEASURE]",
                TRZBY_PRODEJEN_A_TRZISTE to "M tržiště → $CATALOG_SALES []",
            )) {
                withClue(question.text) {
                    val turn = resolve(question)
                    turn.lattice.summary() shouldContainExactly
                        listOf(
                            "M Tržby → $REVENUE [SUBJECT, MEASURE]",
                            "M prodejen → $STORE_CHANNEL_REVENUE [MEASURE]",
                            sibling,
                            "M roce → $CHRONO [FILTER]",
                            "V 2025 GROUNDED → $CAL_DATE ⟵ $CHRONO",
                        )
                    turn.answers()
                }
            }
            val reversed = resolve(TRZBY_WEBU_A_PRODEJEN)
            reversed.lattice.summary() shouldContainExactly
                listOf(
                    "M Tržby → $REVENUE [SUBJECT, MEASURE]",
                    "M webu → $WEB_REVENUE [MEASURE]",
                    "M prodejen → $STORE_CHANNEL_REVENUE [MEASURE]",
                    "M roce → $CHRONO [FILTER]",
                    "V 2025 GROUNDED → $CAL_DATE ⟵ $CHRONO",
                )
            reversed.answers()
        }

        "coordination — „Srovnej prodejny a/nebo web“, „Srovnej prodejny a tržiště“: the store revenue, no ask" {
            for ((question, sibling) in listOf(
                SROVNEJ to "M web → $WEB_SALES []",
                SROVNEJ_NEBO to "M web → $WEB_SALES []",
                SROVNEJ_TRZISTE to "M tržiště → $CATALOG_SALES []",
            )) {
                withClue(question.text) {
                    val turn = resolve(question)
                    turn.lattice.summary() shouldContainExactly
                        listOf(
                            "M Srovnej → op:compare []",
                            "M prodejny → $STORE_CHANNEL_REVENUE [SUBJECT, MEASURE]",
                            sibling,
                        )
                    turn.answers()
                }
            }
        }

        "coordination — \"Revenue of stores and/or web\", either order: the store channel, no ask" {
            for (question in listOf(REVENUE_OF_STORES_AND_WEB, REVENUE_OF_STORES_OR_WEB, REVENUE_OF_WEB_AND_STORES)) {
                withClue(question.text) {
                    val turn = resolve(question)
                    turn.lattice.summary().filter { it.startsWith("M stores") } shouldContainExactly
                        listOf("M stores → $STORE_SALES []")
                    turn.lattice.summary() shouldContain "M web → $WEB_SALES []"
                    turn.answers()
                }
            }
        }

        "coordination — \"Compare stores and web / the marketplace\": channel with channel, no ask" {
            resolve(COMPARE_STORES_AND_WEB).also { it.answers() }.lattice.summary() shouldContainExactly
                listOf(
                    "M Compare → op:compare []",
                    "M stores → $STORE_SALES [SUBJECT]",
                    "M web → $WEB_SALES []",
                )
            resolve(COMPARE_STORES_AND_MARKETPLACE).also { it.answers() }.lattice.summary() shouldContainExactly
                listOf(
                    "M Compare → op:compare []",
                    "M stores → $STORE_SALES [SUBJECT]",
                    "M the marketplace → $CATALOG_SALES []",
                )
        }

        "coordination — „Srovnej prodejny a sklady“: a dimension sibling makes the store word the DIMENSION" {
            // `sklady`'s one declared reading is the warehouse dimension, so the store word reads the
            // store dimension. (`sklady` itself then binds the stock measure through a token of „není
            // skladem“ — the matcher's business, not this rule's.)
            resolve(SROVNEJ_SKLADY)
                .lattice
                .mentionsList
                .single { it.span.text == "prodejny" }
                .bindingsList
                .map { it.ref } shouldContainExactly listOf(STORE)
        }

        "coordination — a sibling that is no mention, or is ambiguous itself, decides nothing: it asks" {
            // "Compare stores and warehouses": `warehouses` has no reading in this lexicon, and a
            // coordinated noun is not a value the store word governs.
            resolve(COMPARE_STORES_AND_WAREHOUSES).let { turn ->
                turn.response.hasAwaiting() shouldBe true
                turn.response.awaiting.optionsList
                    .map { it.targetRef } shouldContainExactlyInAnyOrder
                    listOf(STORE, STORE_SALES)
            }
            // „Srovnej prodejny a prodejny“: each side has two readings, so neither lends one.
            resolve(SROVNEJ_PRODEJNY_A_PRODEJNY).lattice.gapsList.map { it.kind } shouldContainExactly
                listOf(GapKind.GAP_KIND_G2_AMBIGUOUS, GapKind.GAP_KIND_G2_AMBIGUOUS)
        }
    }) {
    private class Question(
        val text: String,
        val lang: String,
        resource: String,
    ) {
        val parse: AnalyzeResponse =
            AnalyzeResponse
                .newBuilder()
                .also { JsonFormat.parser().merge(resourceText("$resource.parse.json"), it) }
                .build()
                .also { p ->
                    p.tokensList.forEach { t ->
                        check(text.substring(t.charStart, t.charEnd) == t.text) { "offsets of '${t.text}' in '$text'" }
                    }
                }
    }

    private class Turn(
        val response: ResolveResponse,
    ) {
        val lattice: ResolutionState get() = response.resolutionState

        /** The question is asked: one G2 over [span], offering exactly the dimension and [channel] (ref to kind). */
        fun asks(
            span: String,
            channel: Pair<String, String>,
        ) {
            lattice.gapsList.map { it.kind to it.span.text } shouldContainExactly
                listOf(GapKind.GAP_KIND_G2_AMBIGUOUS to span)
            response.hasAwaiting() shouldBe true
            val options = response.awaiting.optionsList
            options.map { it.targetRef to it.objectKind } shouldContainExactlyInAnyOrder
                listOf(STORE to "entity", channel)
            options.map { it.span.text }.distinct() shouldContainExactly listOf(span)
        }

        /** Nothing is asked and nothing is left open. */
        fun answers() {
            withClue("gaps ${lattice.gapsList.map { it.kind to it.span.text }}") {
                response.hasAwaiting() shouldBe false
                lattice.gapsList shouldBe emptyList()
            }
        }
    }

    /**
     * lex-matcher as recorded (see the class doc). A scoped question answers the rows whose category it
     * asked; an unscoped one answers every row, and a declared row found that way carries `unknown`, the
     * category lex-matcher stamps on a row it has no category for. A class filter keeps the rows of those
     * classes. Rows come best first, cut at the limit asked.
     */
    private class WireMatcher : FuzzyClient {
        override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse {
            val builder = BatchMatchResponse.newBuilder()
            for (span in request.spansList) {
                val rows = answer(span.query, span.categoriesList, emptyList()).take(limitOf(span.limit, 20))
                builder.addResults(FuzzyMatchResponse.newBuilder().addAllMatches(rows))
            }
            return builder.build()
        }

        override suspend fun lookup(request: LookupRequest): LookupResponse {
            val rows =
                answer(request.term, request.categoriesList, request.targetClassesList)
                    .take(limitOf(request.maxCandidates, 10))
            return LookupResponse.newBuilder().addAllCandidates(rows).build()
        }

        override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()

        private fun limitOf(
            asked: Int,
            default: Int,
        ) = if (asked > 0) asked else default

        private fun answer(
            query: String,
            categories: List<String>,
            classes: List<FuzzyTargetClass>,
        ): List<FuzzyMatch> =
            ROWS[fold(query)]
                .orEmpty()
                .filter { categories.isEmpty() || it.category in categories }
                .filter { classes.isEmpty() || it.targetClass in classes }
                .sortedByDescending { it.score }
                .map {
                    if (categories.isEmpty() && it.source != SourceTag.MEMBER) {
                        it.toBuilder().setCategory(NO_CATEGORY).build()
                    } else {
                        it
                    }
                }
    }

    /** chrono on a four-digit year: the calendar year, on the estate's event-date column, as live. */
    private class YearKernel : GroundingClient {
        override suspend fun ground(request: GroundRequest): GroundResponse {
            val year =
                request.spanText
                    .trim()
                    .toIntOrNull()
                    ?.takeIf { it in 1900..2999 }
                    ?: return GroundResponse.newBuilder().setStatus(GroundResponse.Status.UNGROUNDABLE).build()
            return GroundResponse
                .newBuilder()
                .setStatus(GroundResponse.Status.OK)
                .setResult(
                    GroundingResult
                        .newBuilder()
                        .setNormalized(
                            Normalized.newBuilder().setInterval(
                                DateTimeInterval
                                    .newBuilder()
                                    .setStart("$year-01-01T00:00:00Z")
                                    .setEnd("${year + 1}-01-01T00:00:00Z"),
                            ),
                        ).setFilter(
                            FilterRecipe.newBuilder().setAnchorColumn(
                                QualifiedName
                                    .newBuilder()
                                    .setPackage("hartland")
                                    .setSchemaCode(SchemaCode.ER)
                                    .setNamespace("entity")
                                    .setName("date_dim.cal_date"),
                            ),
                        ),
                ).build()
        }

        override suspend fun getStatus(): GetStatusResponse = GetStatusResponse.getDefaultInstance()
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
        const val STORE = "er.entity.store"
        const val STORE_SALES = "er.entity.store_sales"
        const val STORE_NAME = "er.entity.store.store_name"
        const val WEB_SALES = "er.entity.web_sales"
        const val WEB_REVENUE = "er.entity.web_sales.ext_sales_price"
        const val CATALOG_SALES = "er.entity.catalog_sales"
        const val STORE_CHANNEL_REVENUE = "er.entity.store_sales.ext_sales_price"
        const val REVENUE = "er.entity.channel_sales.ext_sales_price"
        const val CAL_DATE = "er.entity.date_dim.cal_date"
        const val CHRONO = "ground:chrono"

        /** What lex-matcher writes as the category of a declared row an unscoped question found. */
        const val NO_CATEGORY = "unknown"

        val TRZBY_PRODEJEN = Question("Tržby prodejen v roce 2025", "cs", "trzby-prodejen")
        val REVENUE_OF_STORES = Question("Revenue of stores in 2025", "en", "revenue-of-stores")
        val TRZBY_PRODEJNY_PRAHA = Question("Tržby prodejny Praha v roce 2025", "cs", "trzby-prodejny-praha")
        val REVENUE_OF_STORES_IN_PRAHA =
            Question("Revenue of stores in Praha in 2025", "en", "revenue-of-stores-in-praha")
        val PODLE_PRODEJEN = Question("Tržby podle prodejen v roce 2025", "cs", "podle-prodejen")
        val REVENUE_BY_STORES = Question("Revenue by stores in 2025", "en", "revenue-by-stores")
        val Z_PRODEJEN = Question("Tržby z prodejen za 2025", "cs", "z-prodejen")
        val STORE_REVENUE = Question("Store revenue in 2025", "en", "store-revenue")
        val KOLIK = Question("Kolik máme prodejen?", "cs", "kolik-prodejen")
        val SROVNEJ = Question("Srovnej prodejny a web", "cs", "srovnej")
        val PRODEJNY = Question("Prodejny", "cs", "prodejny")
        val REVENUE_OF_STORES_AND_WEB =
            Question("Revenue of stores and web in 2025", "en", "coord-revenue-stores-and-web")
        val REVENUE_OF_STORES_OR_WEB = Question("Revenue of stores or web in 2025", "en", "coord-revenue-stores-or-web")
        val TRZBY_PRODEJEN_A_WEBU = Question("Tržby prodejen a webu v roce 2025", "cs", "coord-trzby-a-webu")
        val TRZBY_PRODEJEN_NEBO_WEBU = Question("Tržby prodejen nebo webu v roce 2025", "cs", "coord-trzby-nebo-webu")
        val COMPARE_STORES_AND_WEB = Question("Compare stores and web", "en", "coord-compare-stores-and-web")
        val SROVNEJ_NEBO = Question("Srovnej prodejny nebo web", "cs", "coord-srovnej-nebo")
        val SROVNEJ_SKLADY = Question("Srovnej prodejny a sklady", "cs", "coord-srovnej-sklady")
        val SROVNEJ_TRZISTE = Question("Srovnej prodejny a tržiště", "cs", "coord-srovnej-trziste")
        val SROVNEJ_PRODEJNY_A_PRODEJNY =
            Question("Srovnej prodejny a prodejny", "cs", "coord-srovnej-prodejny-a-prodejny")
        val TRZBY_WEBU_A_PRODEJEN = Question("Tržby webu a prodejen v roce 2025", "cs", "coord-trzby-webu-a-prodejen")
        val TRZBY_PRODEJEN_A_TRZISTE = Question("Tržby prodejen a tržiště v roce 2025", "cs", "coord-trzby-a-trziste")
        val REVENUE_OF_WEB_AND_STORES =
            Question("Revenue of web and stores in 2025", "en", "coord-revenue-web-and-stores")
        val COMPARE_STORES_AND_MARKETPLACE =
            Question("Compare stores and the marketplace", "en", "coord-compare-stores-and-marketplace")
        val COMPARE_STORES_AND_WAREHOUSES =
            Question("Compare stores and warehouses", "en", "coord-stores-and-warehouses")

        val REGISTRY: Registry =
            Registry.newBuilder().also { JsonFormat.parser().merge(resourceText("registry.json"), it) }.build()

        /** The recorded matcher rows, by folded query. */
        val ROWS: Map<String, List<FuzzyMatch>> =
            Json.parseToJsonElement(resourceText("matcher.json")).jsonObject.mapValues { (_, rows) ->
                rows.jsonArray.map { row ->
                    FuzzyMatch.newBuilder().also { JsonFormat.parser().merge(row.toString(), it) }.build()
                }
            }

        fun resourceText(name: String): String =
            checkNotNull(StoreWordRoleTest::class.java.getResource("/store-word-role/$name")) {
                "missing fixture $name"
            }.readText()

        fun fold(v: String): String =
            java.text.Normalizer
                .normalize(v.lowercase().trim(), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")

        /** One line per mention (`M text → refs [roles]`) and per value (`V text KIND → attributions ⟵ narrowing`). */
        fun ResolutionState.summary(): List<String> =
            mentionsList.map { m ->
                "M ${m.span.text} → ${m.bindingsList.joinToString { it.ref }} " +
                    m.frameRolesList.map { it.name.removePrefix("FRAME_ROLE_") }
            } +
                valuesList.map { v ->
                    "V ${v.span.text} ${v.kind.name.removePrefix("VALUE_KIND_")} → " +
                        v.attributionsList.joinToString { if (it.hasBinding()) it.binding.ref else it.attributeRef } +
                        v.grounding.ref
                            .ifBlank { null }
                            ?.let { " ⟵ $it" }
                            .orEmpty()
                }

        fun resolve(question: Question): Turn {
            val pipeline =
                ResolverPipeline(
                    FakeNlp(question.parse),
                    WireMatcher(),
                    SnapshotRegistry(StubRegistrySource(DeclaredVocabulary(), ""), ResolverThresholds.LIVE),
                    emptyMap(),
                    ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1"),
                    grounding = YearKernel(),
                    defaultPackage = "hartland",
                )
            val response =
                runBlocking {
                    pipeline.resolve(
                        ResolveRequest
                            .newBuilder()
                            .setConversationId("store-word-role")
                            .setFresh(FreshQuestion.newBuilder().setText(question.text).setLocale(question.lang))
                            .setContext(ResolveContext.newBuilder().setReferenceDatetime("2026-10-14T10:00:00Z"))
                            .setRegistry(REGISTRY)
                            .build(),
                    )
                }
            return Turn(response)
        }
    }
}
