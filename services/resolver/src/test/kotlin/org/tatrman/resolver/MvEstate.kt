// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.matchers.types.shouldBeInstanceOf
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
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.LexiconArchiveRegistrySource
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.ttr.lexicon.LexiconArea
import org.tatrman.ttr.lexicon.LexiconDataFile
import org.tatrman.ttr.lexicon.LexiconLoad
import org.tatrman.ttr.lexicon.LexiconValidator
import org.tatrman.ttr.lexicon.TargetClass
import org.tatrman.ttr.lexicon.compile.CompileResult
import org.tatrman.ttr.lexicon.compile.LexiconCompiler
import org.tatrman.ttr.lexicon.compile.LexiconPacker
import org.tatrman.ttr.lexicon.compile.LexiconSources
import org.tatrman.ttr.lexicon.compile.ModelRefIndex
import org.tatrman.ttr.metadata.model.Attribute
import org.tatrman.ttr.metadata.model.Cardinality
import org.tatrman.ttr.metadata.model.Entity
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.ModelVersion
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.Relation
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.metadata.model.SearchHints
import org.tatrman.ttr.semantics.semanticsblock.MeasureRef
import org.tatrman.ttr.semantics.semanticsblock.ResolvedEntitySemantics
import org.tatrman.ttr.semantics.semanticsblock.SymbolRef
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.writeBytes

/**
 * MV-T3 — the tier-M estate of [MhMembers], built the way a real estate reaches the resolver: a
 * TTR model with INDEXED attributes, compiled by the real `LexiconCompiler` into a
 * `ttr-lexicon-compiled/v5` archive, packed by the real `LexiconPacker`, and read back through
 * [LexiconArchiveRegistrySource]. No registry override anywhere — the archive channel is the
 * channel every deployed estate uses, and it is the one MH-P3·S1·T1 found could not reach a
 * member vocabulary from an anchor (MH contracts §7.5 ⚑).
 *
 * The indexed attributes carry NO lexicon terms, which is the typical shape (`store.state` — who
 * writes an alias for "state"?) and the one a v4 archive left out of the registry entirely.
 *
 * Refs are [MhMembers]'s, so the §8.5 parses and member rows apply verbatim.
 */
object MvEstate {
    const val STORE = MhMembers.STORE
    const val STORE_SALES = MhMembers.STORE_SALES
    const val CUSTOMER = MhMembers.CUSTOMER
    const val CUSTOMER_ADDRESS = MhMembers.CUSTOMER_ADDRESS
    const val WAREHOUSE = MhMembers.WAREHOUSE
    const val WEB_SALES = MhMembers.WEB_SALES

    const val STORE_STATE = MhMembers.STORE_STATE
    const val STORE_NAME = MhMembers.STORE_NAME
    const val CA_STATE = MhMembers.CA_STATE
    const val WAREHOUSE_STATE = MhMembers.WAREHOUSE_STATE

    private val modelHash = "sha256:" + "5e".repeat(32)

    private fun qn(name: String) = QualifiedName(SchemaCode.ER, "entity", name)

    private fun attr(
        owner: QualifiedName,
        local: String,
        search: SearchHints = SearchHints.EMPTY,
        type: String = "text",
    ) = Attribute(
        internalId = "a.${owner.name}.$local",
        qname = qn("${owner.name}.$local"),
        entity = owner,
        type = type,
        search = search,
    )

    private fun indexed(method: String) = SearchHints(searchable = true, indexed = true, matchMethod = method)

    private fun relation(
        name: String,
        from: QualifiedName,
        to: QualifiedName,
    ): Pair<QualifiedName, Relation> {
        val q = QualifiedName(SchemaCode.ER, "relation", name)
        return q to
            Relation(
                internalId = name,
                qname = q,
                fromEntity = from,
                toEntity = to,
                cardinality = Cardinality(0, -1, 1, 1),
            )
    }

    /**
     * Four indexed attributes on three entities (`store.store_name TYPOS(1)`, `store.state EXACT`,
     * `customer_address.state EXACT`, `warehouse.state EXACT`); `customer` holds none and reaches
     * `customer_address` by a declared relation — E13's shape. The two facts declare measures, so
     * they compile as `entity_with_measures`, which is what makes `store` a multi-owner anchor.
     */
    fun model(): Model {
        val store = qn("store")
        val storeSales = qn("store_sales")
        val customer = qn("customer")
        val customerAddress = qn("customer_address")
        val warehouse = qn("warehouse")
        val webSales = qn("web_sales")

        fun fact(
            q: QualifiedName,
            id: String,
        ) = q to
            Entity(
                internalId = id,
                qname = q,
                attributes = listOf(attr(q, "ext_sales_price", type = "decimal")),
                mentionSemantics =
                    ResolvedEntitySemantics(
                        measures = listOf(MeasureRef(SymbolRef("ext_sales_price"), "sum")),
                    ),
            )

        return Model(
            descriptor = ModelDescriptor(id = "mv", name = "mv"),
            version = ModelVersion("v1", Instant.EPOCH),
            schemas =
                mapOf(
                    "er" to
                        ErSchema(
                            entities =
                                mapOf(
                                    store to
                                        Entity(
                                            internalId = "1",
                                            qname = store,
                                            attributes =
                                                listOf(
                                                    attr(store, "store_name", indexed("TYPOS(1)")),
                                                    attr(store, "state", indexed("EXACT")),
                                                ),
                                        ),
                                    fact(storeSales, "2"),
                                    customer to
                                        Entity(
                                            internalId = "3",
                                            qname = customer,
                                            attributes = listOf(attr(customer, "name")),
                                        ),
                                    customerAddress to
                                        Entity(
                                            internalId = "4",
                                            qname = customerAddress,
                                            attributes = listOf(attr(customerAddress, "state", indexed("EXACT"))),
                                        ),
                                    warehouse to
                                        Entity(
                                            internalId = "5",
                                            qname = warehouse,
                                            attributes = listOf(attr(warehouse, "state", indexed("EXACT"))),
                                        ),
                                    fact(webSales, "6"),
                                ),
                            relations =
                                mapOf(
                                    relation("rel_store_sales_store", storeSales, store),
                                    relation("rel_customer_address", customer, customerAddress),
                                    relation("rel_web_sales_warehouse", webSales, warehouse),
                                ),
                        ),
                ),
            mappings = emptyList(),
            queries = emptyMap(),
        )
    }

    /**
     * The object anchors. `store`/`prodejna` name the dimension here AND the channel fact in
     * [CHANNELS] (✅MH-D6) — two files, because one file may not give one term two targets
     * (`RG-LEX-006`); across files the compiler keeps both, which is the collision MH is about.
     */
    val LEXICON =
        """
        schema: ttr-lexicon/v1
        defaults: { lang: en }
        entries:
          - terms: [ { text: "store" }, { text: "prodejna", lang: cs } ]
            target: er.entity.store
          - terms: [ { text: "customer" }, { text: "zákazník", lang: cs } ]
            target: er.entity.customer
          - terms: [ { text: "address" }, { text: "adresa", lang: cs } ]
            target: er.entity.customer_address
          - terms: [ { text: "warehouse" }, { text: "sklad", lang: cs } ]
            target: er.entity.warehouse
          - terms: [ { text: "web" } ]
            target: er.entity.web_sales
        """.trimIndent()

    val CHANNELS =
        """
        schema: ttr-lexicon/v1
        defaults: { lang: en }
        entries:
          - terms: [ { text: "store" }, { text: "sale" }, { text: "prodejna", lang: cs }, { text: "tržba", lang: cs } ]
            target: er.entity.store_sales
        """.trimIndent()

    /**
     * Compiles + packs exactly as the lexicon build does. [extra] is a third lexicon file (terms on
     * an attribute, say); [transform] rewrites the compiled result before packing — [asV4] is the
     * one use, and it is the only way to get an OLDER producer's bytes out of today's compiler.
     */
    fun writeArchive(
        dir: Path = Files.createTempDirectory("mv-estate"),
        model: Model? = model(),
        extra: String? = null,
        transform: (CompileResult) -> CompileResult = { it },
    ): Path {
        val sources =
            listOf("aliases/mv.lex.yaml" to LEXICON, "aliases/mv-channels.lex.yaml" to CHANNELS) +
                listOfNotNull(extra?.let { "aliases/mv-extra.lex.yaml" to it })
        val files =
            sources.map { (path, yaml) ->
                LexiconValidator
                    .loadDataFile(yaml, path)
                    .shouldBeInstanceOf<LexiconLoad.Ok<LexiconDataFile>>()
                    .value
            }
        val result =
            LexiconCompiler.compile(
                LexiconSources(area = LexiconArea(files, emptyList()), model = model),
                ModelRefIndex { ref -> if (ref.startsWith("er.entity.")) TargetClass.MODEL_OBJECT else null },
                modelHash,
                "2026-09-25T00:00:00Z",
            )
        return dir.resolve("lexicon.tar.zst").also {
            it.writeBytes(LexiconPacker.pack(transform(result), modelHash, "mv-t3").bytes)
        }
    }

    /**
     * What a v4 producer wrote for the same estate: `targets` only for refs a term points at, no
     * member facet, the v4 schema string. A v5 reader decodes the missing field as `false`.
     */
    fun asV4(r: CompileResult): CompileResult {
        val termRefs =
            r.lexicon.entries
                .map { it.targetRef }
                .toSet()
        return r.copy(
            lexicon =
                r.lexicon.copy(
                    header = r.lexicon.header.copy(schemaVersion = "ttr-lexicon-compiled/v4"),
                    targets =
                        r.lexicon.targets
                            .filterKeys { it in termRefs }
                            .mapValues { (_, facts) -> facts.copy(memberVocabulary = false) },
                ),
        )
    }

    fun registry(archive: Path = writeArchive()): SnapshotRegistry =
        SnapshotRegistry(LexiconArchiveRegistrySource(archive), ResolverThresholds.LIVE)

    fun entityTypes(archive: Path = writeArchive()): List<ResolverEntityType> =
        runBlocking {
            registry(archive).current().entityTypes
        }

    /**
     * A resolve through the archive channel, and the matcher that answered it (for its request log).
     * [blind] names questions (by the categories they ask) the matcher answers with NOTHING — the
     * drill's way of proving a value bound through one path and not the other.
     */
    fun resolve(
        text: String,
        tokens: Array<Token>,
        lang: String = "en",
        archive: Path = writeArchive(),
        blind: (List<String>) -> Boolean = { false },
    ): Pair<ResolveResponse, MemberIndex> {
        val registry = registry(archive)
        val index = MemberIndex(runBlocking { registry.current().entityTypes }, blind)
        val pipeline =
            ResolverPipeline(
                MhMembers.FakeNlp(MhMembers.parse(text, tokens, lang), lang),
                index,
                registry,
                emptyMap(),
                ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1"),
            )
        val request =
            ResolveRequest
                .newBuilder()
                .setConversationId("mv-t3")
                .setFresh(FreshQuestion.newBuilder().setText(text).setLocale(lang))
                .build()
        return runBlocking { pipeline.resolve(request) } to index
    }

    /**
     * lex-matcher as MV-T2 left it: the member rows keyed by ATTRIBUTE ref (the category Veles
     * lists), each carrying its vocabulary's method; plus one DECLARED row per object whose anchors
     * the query stems to. Categories are an exact-key filter (the leak guard): a span answers only
     * about what it asked. `Lookup` with no categories is the cross-category ask, and a class-scoped
     * one excludes members (fuzzy.proto `LookupRequest`) — so the BROAD round is modelled as the
     * real matcher answers it, not as a no-op.
     *
     * Every question is logged, so a test can say WHICH question found a member.
     */
    class MemberIndex(
        private val types: List<ResolverEntityType>,
        private val blind: (List<String>) -> Boolean = { false },
    ) : FuzzyClient {
        data class Asked(
            val via: String,
            val query: String,
            val categories: List<String>,
            val members: List<String>,
            val blinded: Boolean = false,
        )

        val log = mutableListOf<Asked>()

        private val methods = mapOf(STORE_NAME to "TYPOS(1)")

        override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse {
            val builder = BatchMatchResponse.newBuilder()
            for (span in request.spansList) {
                val blinded = blind(span.categoriesList)
                val matches = if (blinded) emptyList() else answer(span.query, span.categoriesList.toSet())
                log += Asked("batch", span.query, span.categoriesList, matches.memberRefs(), blinded)
                builder.addResults(FuzzyMatchResponse.newBuilder().addAllMatches(matches))
            }
            return builder.build()
        }

        override suspend fun lookup(request: LookupRequest): LookupResponse {
            val scope = request.categoriesList.toSet().ifEmpty { null }
            val blinded = blind(request.categoriesList)
            val matches =
                if (blinded) {
                    emptyList()
                } else {
                    answer(request.term, scope)
                        // a class-scoped lookup excludes members: member rows carry no class
                        .filter { request.targetClassesCount == 0 || it.source != SourceTag.MEMBER }
                }
            log += Asked("lookup", request.term, request.categoriesList, matches.memberRefs(), blinded)
            return LookupResponse.newBuilder().addAllCandidates(matches).build()
        }

        override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()

        /** `scope` null ⇒ every category. */
        private fun answer(
            query: String,
            scope: Set<String>?,
        ): List<FuzzyMatch> {
            val out = mutableListOf<FuzzyMatch>()
            for ((id, label, category) in MhMembers.MEMBERS[query.lowercase()].orEmpty()) {
                if (scope == null || category in scope) out += member(id, label, category)
            }
            for (t in types) {
                if (scope != null && t.ref !in scope) continue
                if (t.anchors.none { stemMatch(it, query) }) continue
                out += declared(query, t.ref)
            }
            return out
        }

        private fun List<FuzzyMatch>.memberRefs() =
            filter { it.source == SourceTag.MEMBER }.map { "${it.category}#${it.candidateId}" }

        /** [MhMembers.MemberFuzzy]'s stem heuristic — a fixture rule, nothing depends on its line. */
        private fun stemMatch(
            anchor: String,
            query: String,
        ): Boolean {
            val a = fold(anchor)
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
            base(label, category, methods[category] ?: "EXACT")
                .setCandidateId(id)
                .setSource(SourceTag.MEMBER)
                .build()

        private fun declared(
            query: String,
            ref: String,
        ): FuzzyMatch =
            base(query, ref, "EXACT")
                .setCandidateId("lex:$ref")
                .setTargetRef(ref)
                .setSource(SourceTag.DECLARED)
                .build()

        private fun base(
            candidate: String,
            category: String,
            method: String,
        ): FuzzyMatch.Builder =
            FuzzyMatch
                .newBuilder()
                .setCandidate(candidate)
                .setScore(1.0)
                .setCategory(category)
                .setMatchMethod(method)
                .setProvenance(
                    Provenance
                        .newBuilder()
                        .setProducer("fuzzy")
                        .setMethod("TATRMAN")
                        .setRawScore(1.0),
                )
    }
}
