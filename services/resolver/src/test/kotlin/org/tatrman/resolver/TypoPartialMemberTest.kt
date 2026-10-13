// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.api.GrpcService
import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.core.Candidate
import org.tatrman.fuzzy.core.FuzzyMatcher
import org.tatrman.fuzzy.core.MatchVersion
import org.tatrman.fuzzy.core.RetrievalMode
import org.tatrman.fuzzy.core.SourceTag
import org.tatrman.fuzzy.core.StringRepository
import org.tatrman.fuzzy.core.TargetClass
import org.tatrman.fuzzy.loader.DeclaredValue
import org.tatrman.fuzzy.loader.DeclaredVocabulary
import org.tatrman.fuzzy.loader.DeclaredVocabularyEntry
import org.tatrman.fuzzy.loader.LoaderSource
import org.tatrman.fuzzy.loader.SnapshotVocabularySource
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyServiceGrpcKt
import org.tatrman.fuzzy.v1.FuzzyStatusRequest
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.fuzzy.v1.LookupRequest
import org.tatrman.fuzzy.v1.LookupResponse
import org.tatrman.nlp.v1.NerEntity
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.EntityType
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.Reach
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.resolver.v1.ValueKind
import org.tatrman.resolver.registry.DeclaredVocabulary as ResolverDeclaredVocabulary

/**
 * ttr-server#208 — a typo on ONE word of a longer member value is not a reading of that value.
 *
 * `Marketplace revenue in 2025 for Allen`: the NER types `Allen` a person, the marketplace measure
 * governs it, and the open half of its dual reading asks every member vocabulary the fact reaches. Two
 * rows came back and both were offered: the warehouse `Allentown DC` (`allen` a PREFIX of `allentown`)
 * and the product `ablen stpriationought` (`allen` one edit from `ablen`, the other word untouched).
 * `fuzzy.match:v2` scores a query-side match — a one-word query that hits any word of any value by one
 * edit scores 0.85 whatever the rest of the value says — so the two sat 0.01 apart, in one tie band.
 *
 * Everything here runs through a REAL lex-matcher (`fuzzy.match:v2`, index-first — the shipped engine)
 * over in-process gRPC, with the hartland US warehouses as they are named and a sample of its product
 * catalogue. The matcher's own numbers decide; nothing below hand-writes a score.
 */
class TypoPartialMemberTest :
    StringSpec({

        "#208 `for Allen` binds Allentown DC, and the product one edit from `Allen` is no option" {
            val r = resolveFor("Allen", ::oneWord, person = true)

            // Before: an ask, `Allentown DC` (prefix, 0.867) beside `ablen stpriationought` (typo on
            // one of its two words, 0.855), both UNANCHORED_FUZZY_STRONG — one tie band.
            r.boundTo("Allen", "$WAREHOUSE_NAME#Allentown DC")
        }

        "#208 — a name only a misspelt word of a product answers is not that product (`for Ablin`)" {
            // Alone in its band the same row did not ask, it BOUND: a person's name read as a filter on
            // one product. Now the member reading found nothing, so it is withdrawn and the name stands.
            val r = resolveFor("Ablin", ::oneWord, person = true)

            r.awaiting.optionsList.shouldBeEmpty()
            r.resolutionState.valuesList
                .flatMap { it.attributionsList }
                .map { it.binding.ref }
                .filter { it.startsWith(PRODUCT_NAME) }
                .shouldBeEmpty()
            r.resolution.bindingsList.count { it.hasUniversal() } shouldBe 1
        }

        // ── the readings that must not move ─────────────────────────────────────────────────────

        "guard — one EXACT word of a two-word name is that name (`for Memphis` → Memphis DC)" {
            resolveFor("Memphis", ::oneWord).boundTo("Memphis", "$WAREHOUSE_NAME#Memphis DC")
        }

        "guard — a typo on the word that names the DC still reads it (`for Alentown` → Allentown DC)" {
            // `DC` is in every warehouse name, so `allentown` carries most of what tells this value from
            // the other four (coverage 0.68): one edit on it is a misspelt name, not a word of another one.
            resolveFor("Alentown", ::oneWord).boundTo("Alentown", "$WAREHOUSE_NAME#Allentown DC")
        }

        "guard — a typo inside the whole name (`for Dalas DC` → Dallas DC)" {
            resolveFor("Dalas DC", ::dcName).boundTo("Dalas DC", "$WAREHOUSE_NAME#Dallas DC")
        }

        "guard — the whole name, verbatim (`for Memphis DC`)" {
            resolveFor("Memphis DC", ::dcName).boundTo("Memphis DC", "$WAREHOUSE_NAME#Memphis DC")
        }
    }) {
    private class InProcessFuzzy(
        private val stub: FuzzyServiceGrpcKt.FuzzyServiceCoroutineStub,
    ) : FuzzyClient {
        override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse = stub.batchMatch(request)

        override suspend fun lookup(request: LookupRequest): LookupResponse = stub.lookup(request)

        override suspend fun getStatus(): FuzzyStatusResponse = stub.getStatus(FuzzyStatusRequest.getDefaultInstance())
    }

    private class Anchors : SnapshotVocabularySource {
        override suspend fun fetch(): DeclaredVocabulary =
            DeclaredVocabulary(
                ANCHORS.map { (ref, forms) ->
                    DeclaredVocabularyEntry(
                        category = ref,
                        targetRef = ref,
                        values =
                            forms.mapIndexed { i, form ->
                                DeclaredValue(
                                    id = "$ref#$i",
                                    value = form,
                                    source = SourceTag.DECLARED,
                                    matchMethod = "EXACT",
                                    targetClass = TargetClass.MODEL_OBJECT,
                                )
                            },
                    )
                },
            )

        override fun hash(): String = "typo-partial"
    }

    companion object {
        private const val CATALOG_SALES = "er.entity.catalog_sales"
        private const val MARKETPLACE_REVENUE = "er.entity.catalog_sales.ext_sales_price"
        private const val WAREHOUSE = "er.entity.warehouse"
        private const val WAREHOUSE_NAME = "er.entity.warehouse.warehouse_name"
        private const val ITEM = "er.entity.item"
        private const val PRODUCT_NAME = "er.entity.item.product_name"

        private val ANCHORS =
            mapOf(
                CATALOG_SALES to listOf("catalog"),
                MARKETPLACE_REVENUE to listOf("marketplace revenue"),
                WAREHOUSE to listOf("warehouse"),
                ITEM to listOf("item", "product"),
            )

        /** The hartland US distribution centres, as the world names them. */
        private val WAREHOUSES = listOf("Memphis DC", "Columbus DC", "Dallas DC", "Reno DC", "Allentown DC")

        /**
         * A sample of the hartland US product catalogue, plus the benchmark-generated name #208 was
         * offered. Its two words are each in one product only, as on hartland.
         */
        private val PRODUCTS =
            listOf(
                "Farrow Running Shoe MX-MRS",
                "Voltaic Television MX-C4B",
                "Lonewave Rock Album MX-295",
                "Summitline Binoculars MX-JWS",
                "Ashgrove Swimsuit MX-9JD",
                "Auravelle Bridal Set MX-NL8",
                "Ashgrove Fragrance MX-7R7",
                "Lonewave Classical Album MX-FXH",
                "Redridge Pool Float MX-94U",
                "Bramblewick Layette Set MX-E5Q",
                "Summitline Baseball Glove MX-QXS",
                "Rowantree Belt MX-DOF",
                "Marrowgate Wallpaper Roll MX-KBJ",
                "Bramblewick Onesie MX-76V",
                "Cinderpath Country Album MX-FYW",
                "Pippet Layette Set MX-FQO",
                "Voltaic Monitor MX-OGD",
                "Marrowgate Glassware Set MX-LCG",
                "Ironclad Pants MX-QQI",
                "Kentaur Portable Radio MX-L1Q",
                "Ironclad Track Jacket MX-ESU",
                "Thistlecroft Flatware Set MX-DN8",
                "Kentaur Stereo System MX-54O",
                "Willowmere Dress MX-Z62",
                "ablen stpriationought",
            )

        private fun et(
            ref: String,
            kind: String,
            owner: String = "",
            reachedFrom: List<String> = emptyList(),
            member: Boolean = false,
        ): EntityType {
            val b =
                EntityType
                    .newBuilder()
                    .setRef(ref)
                    .addCategories(ref)
                    .addAllAnchors(ANCHORS[ref].orEmpty())
                    .setObjectKind(kind)
                    .setMemberVocabulary(member)
            if (owner.isNotBlank()) b.ownerRef = owner
            for (f in reachedFrom) b.addReachedFrom(Reach.newBuilder().setFactRef(f).setMandatory(true))
            return b.build()
        }

        /** The marketplace fact reaches both the warehouse and the item, as on hartland. */
        private val REGISTRY: Registry =
            Registry
                .newBuilder()
                .addEntityTypes(et(CATALOG_SALES, "entity_with_measures"))
                .addEntityTypes(et(MARKETPLACE_REVENUE, "measure", owner = CATALOG_SALES))
                .addEntityTypes(et(WAREHOUSE, "entity", reachedFrom = listOf(CATALOG_SALES)))
                .addEntityTypes(et(WAREHOUSE_NAME, "attribute", owner = WAREHOUSE, member = true))
                .addEntityTypes(et(ITEM, "entity", reachedFrom = listOf(CATALOG_SALES)))
                .addEntityTypes(et(PRODUCT_NAME, "attribute", owner = ITEM, member = true))
                .addLocales("en")
                .setSnapshotHash("snap-typo-partial")
                .build()

        /** Both name vocabularies are authored `search { searchable method: TOKENS }` on hartland. */
        private val members: LoaderSource =
            object : LoaderSource {
                override suspend fun loadNextCache() =
                    mapOf(
                        WAREHOUSE_NAME to WAREHOUSES.map { Candidate.fromValues(it, it, "TOKENS", WAREHOUSE_NAME) },
                        PRODUCT_NAME to PRODUCTS.map { Candidate.fromValues(it, it, "TOKENS", PRODUCT_NAME) },
                    )
            }

        // `Marketplace revenue in 2025 for <name>` as Stanza parses it: the measure phrase heads the
        // sentence, the year and the name hang off its head.
        private const val PREFIX = "Marketplace revenue in 2025 for "

        private fun oneWord(
            word: String,
            start: Int,
        ): List<Token> = listOf(MhMembers.tok(word, start, start + word.length, word, "PROPN", 2, "nmod"))

        private fun dcName(
            name: String,
            start: Int,
        ): List<Token> {
            val town = name.substringBefore(' ')
            val dcStart = start + town.length + 1
            return listOf(
                MhMembers.tok(town, start, start + town.length, town, "PROPN", 2, "nmod"),
                MhMembers.tok("DC", dcStart, dcStart + 2, "DC", "PROPN", 6, "flat"),
            )
        }

        private fun resolveFor(
            name: String,
            nameTokens: (String, Int) -> List<Token>,
            person: Boolean = false,
        ): ResolveResponse {
            val text = PREFIX + name
            val start = PREFIX.length
            val tokens =
                arrayOf(
                    MhMembers.tok("Marketplace", 0, 11, "marketplace", "NOUN", 2, "compound"),
                    MhMembers.tok("revenue", 12, 19, "revenue", "NOUN", 0, "root"),
                    MhMembers.tok("in", 20, 22, "in", "ADP", 4, "case"),
                    MhMembers.tok("2025", 23, 27, "2025", "NUM", 2, "nmod"),
                    MhMembers.tok("for", 28, 31, "for", "ADP", 6, "case"),
                ) + nameTokens(name, start)
            val entity: NerEntity = MhMembers.ner(name, start, start + name.length, if (person) "PERSON" else "GPE")
            return resolve(text, tokens, entity)
        }

        private fun resolve(
            text: String,
            tokens: Array<Token>,
            entity: NerEntity,
        ): ResolveResponse =
            runBlocking {
                val config =
                    AppConfig(serverPort = 0, grpcPort = 0, grpcReflectionEnabled = false, refreshIntervalSeconds = 0)
                val repo = StringRepository(config, members, snapshotSource = Anchors())
                repo.forceRefresh()
                val service =
                    GrpcService(
                        FuzzyMatcher(repo, retrievalMode = RetrievalMode.INDEX_FIRST, matchVersion = MatchVersion.V2),
                        repo,
                    )
                val name = "typo-partial-${System.nanoTime()}"
                val server =
                    InProcessServerBuilder
                        .forName(name)
                        .directExecutor()
                        .addService(service)
                        .build()
                        .start()
                val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
                try {
                    val parse =
                        MhMembers
                            .parse(text, tokens)
                            .toBuilder()
                            .addEntities(entity)
                            .build()
                    ResolverPipeline(
                        MhMembers.FakeNlp(parse, "en"),
                        InProcessFuzzy(FuzzyServiceGrpcKt.FuzzyServiceCoroutineStub(channel)),
                        SnapshotRegistry(
                            StubRegistrySource(ResolverDeclaredVocabulary(), ""),
                            ResolverThresholds.LIVE,
                        ),
                        emptyMap(),
                        ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1"),
                    ).resolve(
                        ResolveRequest
                            .newBuilder()
                            .setConversationId("typo-partial")
                            .setFresh(FreshQuestion.newBuilder().setText(text).setLocale("en"))
                            .setRegistry(REGISTRY)
                            .build(),
                    )
                } finally {
                    channel.shutdownNow()
                    server.shutdownNow()
                    repo.close()
                }
            }

        private fun ResolveResponse.boundTo(
            text: String,
            ref: String,
        ) {
            val options = awaiting.optionsList.map { "${it.memberOf}#${it.resolvedId}" }
            options.shouldBeEmpty()
            val finding = resolutionState.valuesList.single { it.span.text == text }
            finding.kind shouldBe ValueKind.VALUE_KIND_LITERAL
            finding.attributionsList.map { it.binding.ref } shouldContainExactly listOf(ref)
            resolution.bindingsList.count { it.hasUniversal() } shouldBe 0
        }
    }
}
