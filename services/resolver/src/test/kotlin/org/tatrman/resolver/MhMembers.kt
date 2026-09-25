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
import org.tatrman.nlp.v1.NerEntity
import org.tatrman.nlp.v1.NlpOp
import org.tatrman.nlp.v1.StatusResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.client.NlpClient
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.FrameRolePreps
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.EntityType
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.Reach
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse

/**
 * MH-P3 tier M — the member-vs-member estate of contracts §8.5, shared by `MhPipelineTest`,
 * `SpanProposalTest` and the observation spec.
 *
 * One word (`TN`) is a MEMBER of three attributes owned by three different entities, which is
 * the shape T2/T3 cannot see: three `M:` identities are a SAME-kind tie, and neither the slot
 * nor reachability is asked of a data row. What the sentence offers instead is the GOVERNOR.
 *
 * ⛔ The registry is modelled on what the ARCHIVE channel projects: every declared vocabulary
 * entry carries `category == targetRef`, so a member vocabulary is its own type, gated by its
 * attribute ref — and since MV-T3 (a v5 archive) it is there whether or not it has terms, flagged
 * `member_vocabulary` and owned by its entity. `membersOf` reads that pair, which is how a value
 * governed by `store` reaches `store.state`; `GateSpans` names a member's ENTITY as its
 * `entity_type_ref` and its attribute as `member_of`. The `.state` types below exist for that
 * reason and are not decoration. [MvEstate] builds the same estate through the real compiler.
 */
object MhMembers {
    const val STORE = "er.entity.store"
    const val CUSTOMER = "er.entity.customer"
    const val CUSTOMER_ADDRESS = "er.entity.customer_address"
    const val WAREHOUSE = "er.entity.warehouse"
    const val STORE_SALES = "er.entity.store_sales"
    const val STORE_RETURNS = "er.entity.store_returns"
    const val WEB_SALES = "er.entity.web_sales"
    const val CATALOG_SALES = "er.entity.catalog_sales"
    const val ITEM = "er.entity.item"

    const val STORE_STATE = "er.entity.store.state"
    const val CA_STATE = "er.entity.customer_address.state"
    const val WAREHOUSE_STATE = "er.entity.warehouse.state"
    const val STORE_NAME = "er.entity.store.store_name"

    /**
     * The three `TN` members and the one `Nashville` member, by the category that holds them — as
     * lex-matcher answers since A-MV-15: one row per VALUE of a vocabulary, its id the value itself.
     * So the three `TN`s share an id and differ ONLY by category, which is what review-104 F3 was:
     * an identity without the category collapsed them into one.
     */
    val MEMBERS: Map<String, List<Triple<String, String, String>>> =
        mapOf(
            // query → [(candidateId, candidate, category)]
            "tn" to
                listOf(
                    Triple("TN", "TN", STORE_STATE),
                    Triple("TN", "TN", CA_STATE),
                    Triple("TN", "TN", WAREHOUSE_STATE),
                ),
            "nashville" to listOf(Triple("Nashville", "Nashville", STORE_NAME)),
        )

    private fun et(
        ref: String,
        anchors: List<String>,
        kind: String,
        owner: String = "",
        reach: List<Pair<String, Boolean>> = emptyList(),
        member: Boolean = false,
    ): EntityType.Builder {
        val b =
            EntityType
                .newBuilder()
                .setRef(ref)
                .addCategories(ref)
                .addAllAnchors(anchors)
                .setObjectKind(kind)
                .setMemberVocabulary(member)
        if (owner.isNotBlank()) b.ownerRef = owner
        for ((f, m) in reach) b.addReachedFrom(Reach.newBuilder().setFactRef(f).setMandatory(m))
        return b
    }

    /** contracts §8.5 — six objects, four fuzzy member columns, three declared reaches. */
    val REGISTRY: Registry =
        Registry
            .newBuilder()
            // `store` and `store_sales` share the `store`/`prodejna` anchor: the multi-owner
            // anchor M1's dedupe hazard needs, and the hartland collision the whole feature is about.
            .addEntityTypes(
                et(
                    STORE,
                    listOf("store", "prodejna"),
                    "entity",
                    reach = listOf(STORE_SALES to true, STORE_RETURNS to true),
                ),
            ).addEntityTypes(
                et(STORE_SALES, listOf("sale", "tržba", "store", "prodejna"), "entity_with_measures"),
            ).addEntityTypes(et(CUSTOMER, listOf("customer", "zákazník"), "entity"))
            .addEntityTypes(
                et(CUSTOMER_ADDRESS, listOf("address", "adresa"), "entity", reach = listOf(CUSTOMER to true)),
            ).addEntityTypes(
                et(
                    WAREHOUSE,
                    listOf("warehouse", "sklad"),
                    "entity",
                    reach = listOf(CATALOG_SALES to true, WEB_SALES to true),
                ),
            ).addEntityTypes(et(WEB_SALES, listOf("web"), "entity_with_measures"))
            .addEntityTypes(et(ITEM, listOf("item", "položka"), "entity"))
            // The member vocabularies — what a v5 archive projects for an indexed attribute with
            // no terms (MV-T3): gated by their own ref, owned by their entity, flagged. `membersOf`
            // reads the flag + owner pair; that is how a value governed by `store` reaches them.
            .addEntityTypes(et(STORE_STATE, listOf(), "attribute", owner = STORE, member = true))
            .addEntityTypes(et(CA_STATE, listOf(), "attribute", owner = CUSTOMER_ADDRESS, member = true))
            .addEntityTypes(et(WAREHOUSE_STATE, listOf(), "attribute", owner = WAREHOUSE, member = true))
            .addEntityTypes(et(STORE_NAME, listOf(), "attribute", owner = STORE, member = true))
            .addLocales("cs")
            .addLocales("en")
            .setSnapshotHash("snap-mh-m")
            .build()

    fun entityTypes(): List<ResolverEntityType> =
        ResolverPipeline.fromProto(REGISTRY, ResolverThresholds.LIVE).entityTypes

    fun preps(): FrameRolePreps = FrameRolePreps.shipped()

    fun tok(
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

    // ── the E-case parses of contracts §8.5 (hand-built; the drill replaces any that differ) ──

    fun e11En() =
        arrayOf(
            tok("Stores", 0, 6, "store", "NOUN", 0, "root"),
            tok("in", 7, 9, "in", "ADP", 3, "case"),
            tok("TN", 10, 12, "TN", "PROPN", 1, "nmod"),
        )

    fun e11Cs() =
        arrayOf(
            tok("Prodejny", 0, 8, "prodejna", "NOUN", 0, "root"),
            tok("v", 9, 10, "v", "ADP", 3, "case"),
            tok("TN", 11, 13, "TN", "PROPN", 1, "nmod"),
        )

    /** E11 with the anchor in a COUNT slot, so MH T2 resolves the object homonym too. */
    fun e11Count() =
        arrayOf(
            tok("How", 0, 3, "how", "ADV", 2, "advmod"),
            tok("many", 4, 8, "many", "ADJ", 3, "amod"),
            tok("stores", 9, 15, "store", "NOUN", 0, "root"),
            tok("in", 16, 18, "in", "ADP", 5, "case"),
            tok("TN", 19, 21, "TN", "PROPN", 3, "nmod"),
        )

    /** The REAL hartland parse: Czech Stanza tags `TN` NOUN, not PROPN (drill, 2026-09-04). */
    fun e11CsReal() =
        arrayOf(
            tok("Prodejny", 0, 8, "prodejna", "NOUN", 0, "root"),
            tok("v", 9, 10, "v", "ADP", 3, "case"),
            tok("TN", 11, 13, "TN", "NOUN", 1, "nmod"),
        )

    /** Likewise for E13-cs. */
    fun e13CsReal() =
        arrayOf(
            tok("Zákazníci", 0, 9, "zákazník", "NOUN", 0, "root"),
            tok("v", 10, 11, "v", "ADP", 3, "case"),
            tok("TN", 12, 14, "TN", "NOUN", 1, "nmod"),
        )

    fun e12En() =
        arrayOf(
            tok("Sales", 0, 5, "sale", "NOUN", 0, "root"),
            tok("in", 6, 8, "in", "ADP", 3, "case"),
            tok("TN", 9, 11, "TN", "PROPN", 1, "nmod"),
        )

    fun e12Bare() = arrayOf(tok("TN", 0, 2, "TN", "PROPN", 0, "root"))

    fun e13En() =
        arrayOf(
            tok("Customers", 0, 9, "customer", "NOUN", 0, "root"),
            tok("in", 10, 12, "in", "ADP", 3, "case"),
            tok("TN", 13, 15, "TN", "PROPN", 1, "nmod"),
        )

    fun e13Cs() =
        arrayOf(
            tok("Zákazníci", 0, 9, "zákazník", "NOUN", 0, "root"),
            tok("v", 10, 11, "v", "ADP", 3, "case"),
            tok("TN", 12, 14, "TN", "PROPN", 1, "nmod"),
        )

    fun e4En() =
        arrayOf(
            tok("Stores", 0, 6, "store", "NOUN", 0, "root"),
            tok("in", 7, 9, "in", "ADP", 3, "case"),
            tok("Nashville", 10, 19, "Nashville", "PROPN", 1, "nmod"),
        )

    fun parse(
        text: String,
        tokens: Array<Token>,
        lang: String = "en",
    ): AnalyzeResponse =
        AnalyzeResponse
            .newBuilder()
            .setLanguage(lang)
            .setDetectedLanguage(lang)
            .setTraceId("mh-m")
            .addAllTokens(tokens.toList())
            .build()

    fun ner(
        text: String,
        start: Int,
        end: Int,
        label: String,
    ): NerEntity =
        NerEntity
            .newBuilder()
            .setText(text)
            .setCharStart(start)
            .setCharEnd(end)
            .setLabel(label)
            .build()

    fun resolve(
        text: String,
        tokens: Array<Token>,
        lang: String = "en",
        registry: Registry = REGISTRY,
        entities: List<NerEntity> = emptyList(),
    ): ResolveResponse {
        val parse = parse(text, tokens, lang).toBuilder().addAllEntities(entities).build()
        val pipeline =
            ResolverPipeline(
                FakeNlp(parse, lang),
                MemberFuzzy(),
                SnapshotRegistry(StubRegistrySource(DeclaredVocabulary(), ""), ResolverThresholds.LIVE),
                emptyMap(),
                ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1"),
            )
        val request =
            ResolveRequest
                .newBuilder()
                .setConversationId("mh-m")
                .setFresh(FreshQuestion.newBuilder().setText(text).setLocale(lang))
                .setRegistry(registry)
                .build()
        return runBlocking { pipeline.resolve(request) }
    }

    /**
     * A matcher holding exactly two kinds of row: the four MEMBER values above, and one DECLARED
     * row per OBJECT category whose anchors contain the query's lemma-ish surface. It answers a
     * span only about the categories the span asked for, which is the whole point — a governed
     * value asks about its owner's category and nothing else.
     */
    class MemberFuzzy : FuzzyClient {
        override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse {
            val builder = BatchMatchResponse.newBuilder()
            for (span in request.spansList) {
                val asked = span.categoriesList.toSet()
                val matches = mutableListOf<FuzzyMatch>()
                for ((id, label, category) in MEMBERS[span.query.lowercase()].orEmpty()) {
                    if (category in asked) matches += member(id, label, category)
                }
                for (etype in REGISTRY.entityTypesList) {
                    if (etype.ref !in asked) continue
                    if (etype.anchorsList.none { stemMatch(it, span.query) }) continue
                    matches += declared(span.query, etype.ref)
                }
                builder.addResults(FuzzyMatchResponse.newBuilder().addAllMatches(matches))
            }
            return builder.build()
        }

        override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()

        /**
         * A deliberately crude stand-in for the real matcher's fold + lemma handling: an anchor
         * and a surface match when they share a long enough stem, so `Prodejny` finds `prodejna`
         * and `stores` finds `store` without this fixture having to carry a morphology table.
         * It is a FIXTURE heuristic and nothing here depends on where it draws the line.
         */
        private fun stemMatch(
            anchor: String,
            query: String,
        ): Boolean {
            val a = fold(anchor)
            // per TOKEN, so a phrase span (`many stores`) still finds its head's anchor the way
            // the real matcher's token index does
            return fold(query).split(' ').any { q ->
                if (a == q) {
                    true
                } else {
                    val shared = a.commonPrefixWith(q).length
                    shared >= 4 && shared >= minOf(a.length, q.length) - 2
                }
            }
        }

        private fun fold(v: String) =
            java.text.Normalizer
                .normalize(v.lowercase(), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")

        private fun member(
            id: String,
            label: String,
            category: String,
        ): FuzzyMatch =
            base(label, category)
                .setCandidateId(id)
                .setSource(SourceTag.MEMBER)
                .build()

        private fun declared(
            query: String,
            ref: String,
        ): FuzzyMatch =
            base(query, ref)
                .setCandidateId("lex:$ref")
                .setTargetRef(ref)
                .setSource(SourceTag.DECLARED)
                .build()

        private fun base(
            candidate: String,
            category: String,
        ): FuzzyMatch.Builder =
            FuzzyMatch
                .newBuilder()
                .setCandidate(candidate)
                .setScore(1.0)
                .setCategory(category)
                .setMatchMethod("EXACT")
                .setProvenance(
                    Provenance
                        .newBuilder()
                        .setProducer("fuzzy")
                        .setMethod("TATRMAN")
                        .setRawScore(1.0),
                )
    }

    class FakeNlp(
        private val parse: AnalyzeResponse,
        private val lang: String,
    ) : NlpClient {
        override suspend fun analyze(request: AnalyzeRequest): AnalyzeResponse = parse

        override suspend fun getStatus(): StatusResponse =
            StatusResponse
                .newBuilder()
                .setReady(true)
                .addCapabilities(
                    Capability
                        .newBuilder()
                        .setLanguage(lang)
                        .setOp(NlpOp.NER)
                        .setEngine("nametag3")
                        .setModelVersion("cnec2.0"),
                ).addCapabilities(
                    Capability
                        .newBuilder()
                        .setLanguage(lang)
                        .setOp(NlpOp.DEP_PARSE)
                        .setEngine("udpipe")
                        .setModelVersion("pdt-2.5"),
                ).build()
    }
}
