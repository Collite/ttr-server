// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import com.google.protobuf.TextFormat
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.api.GrpcService
import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.core.Candidate
import org.tatrman.fuzzy.core.FuzzyMatcher
import org.tatrman.fuzzy.core.LookupQuery
import org.tatrman.fuzzy.core.MatchVersion
import org.tatrman.fuzzy.core.RetrievalMode
import org.tatrman.fuzzy.core.StringRepository
import org.tatrman.fuzzy.core.TargetClass as CoreTargetClass
import org.tatrman.fuzzy.loader.LexiconArchiveSource
import org.tatrman.fuzzy.loader.LoaderSource
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyServiceGrpcKt
import org.tatrman.fuzzy.v1.FuzzyStatusRequest
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.fuzzy.v1.LookupRequest
import org.tatrman.fuzzy.v1.LookupResponse
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
import org.tatrman.resolver.pipeline.LookupRoundConfig
import org.tatrman.resolver.pipeline.LookupRounds
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.LexiconArchiveRegistrySource
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.Mention
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.ResolveContext
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.resolver.v1.TargetClass
import java.nio.file.Path
import java.nio.file.Paths
import org.tatrman.fuzzy.v1.TargetClass as FuzzyTargetClass

/**
 * md/hop HP-H — an unbound mention may be a VALUE LABEL: the estate's own word for one member.
 *
 * ttr-demo#116 made the channel words value labels of `sales.channel` (`tržiště` → `marketplace`). Live on hartland,
 * „Tržby z tržiště v roce 2025 podle kategorie“ then answered every channel's revenue: `Tržby` is a measure, which
 * governs no value, so nothing proposed `tržiště` under `sales`; the mention layer took it, left it G1, and the
 * lookup rung asked it in the MODEL_OBJECT and OPERATOR classes only. A value label is a MEMBER row, so it was never
 * among the answers, the mention stayed unbound, and nothing narrowed the sum.
 *
 * The rung's class scope keeps data out of a mention's answers ("which object is this word?" must not be answered by
 * a row of data that reads alike). A data row carries no class at all, so MEMBER in the scope admits exactly the
 * rows an author wrote for a member — value labels and value aliases — and still never a data value (the last case).
 *
 * **Provenance (2026-10-24, hartland, resolver 0.11.25, cs world, ttr-demo#116 live).**
 *  - Parses (`value-label-mention/<question>.parse.txtpb`): the nlp `Analyze` answer of each live resolve, verbatim
 *    from the resolver's DEBUG log (MorphoDiTa `czech-morfflex2.0-pdtc1.0-220710`, Stanza dependencies, NameTag).
 *  - Archive (`value-label-mention/lexicon.tar.zst`): the compiled lexicon of ttr-demo `a9dc039b` (#116), the one the
 *    live resolver and lex-matcher loaded (`sha256:01b95054…`, 791 entries). The registry is projected from it by
 *    [LexiconArchiveRegistrySource], and a real lex-matcher (v2, index-first) serves it — so the rows, their classes
 *    and the class filter are lex-matcher's own, not a fake's.
 *  - No member data is loaded: none of these questions names a stored value.
 *
 * The lattice is asserted, because it is what the golem reads (`ResolveResponse.resolution_state`). A mention a
 * lookup round binds is not in `Resolution.bindings` — true of an object a round binds (`letech` below) as much as of
 * a member, and a gap of the door's own, not of this rung.
 */
class ValueLabelMentionTest :
    StringSpec({

        "HP-H „Tržby z tržiště v roce 2025 podle kategorie“ — `tržiště` binds the marketplace member, FILTER" {
            val lookups = mutableListOf<LookupRequest>()
            val response = resolve(PODLE_KATEGORIE, lookups = lookups)

            response.resolutionState.summary() shouldContainExactly
                listOf(
                    "M Tržby → $REVENUE [SUBJECT, MEASURE]",
                    "M tržiště → $MARKETPLACE [FILTER]",
                    "M roce → ground:chrono [FILTER]",
                    "M kategorie → er.entity.item.category [GROUPING]",
                    "V 2025 GROUNDED → $CAL_DATE ⟵ ground:chrono",
                )
            response.resolutionState.gapsList shouldBe emptyList()
            response.resolutionState
                .mention("tržiště")
                .bindingsList
                .single()
                .targetClass shouldBe TargetClass.TARGET_CLASS_MEMBER
            // the lookup rung found it: the unbound mention, asked across categories, MEMBER among the classes
            lookups
                .single { it.term == "tržiště" }
                .let {
                    it.categoriesList shouldBe emptyList()
                    it.targetClassesList shouldContain FuzzyTargetClass.TARGET_CLASS_MEMBER
                }
        }

        "HP-H — the same word under the other measure phrasings binds the same member" {
            for (question in listOf(PO_MESICICH, OBRAT)) {
                withClue(question.text) {
                    val lattice = resolve(question).resolutionState
                    lattice.summary() shouldContain "M tržiště → $MARKETPLACE [FILTER]"
                    lattice.gapsList shouldBe emptyList()
                }
            }
        }

        "HP-H „Porovnej tržby z webu a z tržiště …“ — each channel word binds its own member" {
            resolve(POROVNEJ).resolutionState.summary() shouldContainExactly
                listOf(
                    "M Porovnej → op:compare []",
                    "M tržby → $REVENUE [SUBJECT, MEASURE]",
                    "M webu → er.entity.sales.channel#web [FILTER]",
                    "M tržiště → $MARKETPLACE [FILTER]",
                    "M letech → er.entity.date_dim.year [FILTER]",
                    "V 2024 GROUNDED → $CAL_DATE",
                    "V 2025 GROUNDED → $CAL_DATE",
                )
        }

        "„Tržby podle kanálu v roce 2025“ is unchanged — the channel attribute groups" {
            resolve(PODLE_KANALU).resolutionState.summary() shouldContainExactly
                listOf(
                    "M Tržby → $REVENUE [SUBJECT, MEASURE]",
                    "M kanálu → er.entity.sales.channel [GROUPING]",
                    "M roce → ground:chrono [FILTER]",
                    "V 2025 GROUNDED → $CAL_DATE ⟵ ground:chrono",
                )
        }

        "„Tržby z prodejen za 2025“ is unchanged — `prodejen` binds the store object, not the store channel" {
            // the homonym of ttr-demo#62: the broad pass binds the object, so the mention is never G1 and the
            // rung never asks; which reading the question means is that issue's, not this one's
            resolve(Z_PRODEJEN).resolutionState.summary() shouldContainExactly
                listOf(
                    "M Tržby → $REVENUE [SUBJECT, MEASURE]",
                    "M prodejen → er.entity.store [FILTER]",
                    "V 2025 GROUNDED → $CAL_DATE",
                )
        }

        "a DATA value spelled like the word still never answers an unbound mention" {
            // A stored product named `Tržiště`, which an unfiltered lookup of the word finds (a data row, no class)
            // and the rung's class scope does not: lex-matcher keeps only rows of the classes asked.
            val members = mapOf(PRODUCT_NAME to listOf(Candidate.fromValues("Tržiště", "Tržiště")))
            val repo = repository(members)
            try {
                val matcher =
                    FuzzyMatcher(repo, retrievalMode = RetrievalMode.INDEX_FIRST, matchVersion = MatchVersion.V2)
                matcher.lookup(LookupQuery("tržiště")).candidates.map { it.candidateId to it.targetClass } shouldContain
                    ("Tržiště" to null)
                val rungClasses = setOf(CoreTargetClass.MODEL_OBJECT, CoreTargetClass.OPERATOR, CoreTargetClass.MEMBER)
                matcher
                    .lookup(LookupQuery("tržiště", targetClasses = rungClasses))
                    .candidates
                    .map { it.candidateId } shouldNotContain "Tržiště"
            } finally {
                repo.close()
            }
            // So the mention binds the label, never `product_name#Tržiště`.
            val lattice = resolve(PODLE_KATEGORIE, members = members).resolutionState
            lattice.mention("tržiště").bindingsList.map { it.ref } shouldContainExactly listOf(MARKETPLACE)
        }
    }) {
    private class Question(
        val text: String,
        resource: String,
    ) {
        val parse: AnalyzeResponse =
            AnalyzeResponse
                .newBuilder()
                .also { TextFormat.getParser().merge(resourceText("$resource.parse.txtpb"), it) }
                .build()
                .also { p ->
                    p.tokensList.forEach { t ->
                        check(text.substring(t.charStart, t.charEnd) == t.text) { "offsets of '${t.text}' in '$text'" }
                    }
                }
    }

    /** The resolver's [FuzzyClient] over an in-process channel to a real lex-matcher; every `Lookup` is recorded. */
    private class InProcessFuzzy(
        private val stub: FuzzyServiceGrpcKt.FuzzyServiceCoroutineStub,
        private val lookups: MutableList<LookupRequest>,
    ) : FuzzyClient {
        override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse = stub.batchMatch(request)

        override suspend fun lookup(request: LookupRequest): LookupResponse {
            lookups += request
            return stub.lookup(request)
        }

        override suspend fun getStatus(): FuzzyStatusResponse = stub.getStatus(FuzzyStatusRequest.getDefaultInstance())
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
        const val REVENUE = "er.entity.sales.ext_sales_price"
        const val MARKETPLACE = "er.entity.sales.channel#marketplace"
        const val CAL_DATE = "er.entity.date_dim.cal_date"
        const val PRODUCT_NAME = "er.entity.item.product_name"

        val PODLE_KATEGORIE = Question("Tržby z tržiště v roce 2025 podle kategorie", "podle-kategorie")
        val PO_MESICICH = Question("Jaké byly tržby z tržiště v roce 2025 po měsících?", "po-mesicich")
        val OBRAT = Question("Obrat z tržiště po čtvrtletích v roce 2025", "obrat-po-ctvrtletich")
        val PODLE_KANALU = Question("Tržby podle kanálu v roce 2025", "podle-kanalu")
        val Z_PRODEJEN = Question("Tržby z prodejen za 2025", "z-prodejen-za-2025")
        val POROVNEJ = Question("Porovnej tržby z webu a z tržiště v letech 2024 a 2025", "porovnej")

        val ARCHIVE: Path =
            Paths.get(
                checkNotNull(
                    ValueLabelMentionTest::class.java.getResource("/value-label-mention/lexicon.tar.zst"),
                ).toURI(),
            )

        fun resourceText(name: String): String =
            checkNotNull(ValueLabelMentionTest::class.java.getResource("/value-label-mention/$name")) {
                "missing fixture $name"
            }.readText()

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

        fun ResolutionState.mention(text: String): Mention =
            withClue("one mention spelled '$text' in ${mentionsList.map { it.span.text }}") {
                mentionsList.single { it.span.text == text }
            }

        /** lex-matcher's index over the archive, with [members] as its member data. */
        fun repository(members: Map<String, List<Candidate>>): StringRepository =
            StringRepository(
                AppConfig(serverPort = 0, grpcPort = 0, grpcReflectionEnabled = false, refreshIntervalSeconds = 0),
                object : LoaderSource {
                    override suspend fun loadNextCache(): Map<String, List<Candidate>> = members
                },
                snapshotSource = LexiconArchiveSource(ARCHIVE),
            ).also { runBlocking { it.forceRefresh() } }

        /**
         * One resolve over the HP-H estate as hartland served it: the registry projected from the archive by
         * [LexiconArchiveRegistrySource], and a real lex-matcher (v2, index-first) loaded from the same archive,
         * with [members] as its member data (none, as live for these questions).
         */
        fun resolve(
            question: Question,
            members: Map<String, List<Candidate>> = emptyMap(),
            lookups: MutableList<LookupRequest> = mutableListOf(),
        ): ResolveResponse =
            runBlocking {
                val repo = repository(members)
                val name = "value-label-mention-${System.nanoTime()}"
                val server =
                    InProcessServerBuilder
                        .forName(name)
                        .directExecutor()
                        .addService(
                            GrpcService(
                                FuzzyMatcher(
                                    repo,
                                    retrievalMode = RetrievalMode.INDEX_FIRST,
                                    matchVersion = MatchVersion.V2,
                                ),
                                repo,
                            ),
                        ).build()
                        .start()
                val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
                try {
                    val fuzzy = InProcessFuzzy(FuzzyServiceGrpcKt.FuzzyServiceCoroutineStub(channel), lookups)
                    ResolverPipeline(
                        FakeNlp(question.parse),
                        fuzzy,
                        SnapshotRegistry(LexiconArchiveRegistrySource(ARCHIVE), ResolverThresholds.LIVE),
                        emptyMap(),
                        ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1"),
                        // the rung as configured live, with a budget a cold test JVM cannot run out of
                        lookupRounds = LookupRounds(fuzzy, LookupRoundConfig.DEFAULT.copy(budgetMs = 30_000)),
                        grounding = YearKernel(),
                        defaultPackage = "hartland",
                    ).resolve(
                        ResolveRequest
                            .newBuilder()
                            .setConversationId("value-label-mention")
                            .setFresh(FreshQuestion.newBuilder().setText(question.text).setLocale("cs"))
                            .setContext(ResolveContext.newBuilder().setReferenceDatetime("2026-10-24T10:12:00Z"))
                            .build(),
                    )
                } finally {
                    channel.shutdownNow()
                    server.shutdownNow()
                    repo.close()
                }
            }
    }
}
