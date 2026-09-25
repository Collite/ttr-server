// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.api.GrpcService
import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.core.FuzzyMatcher
import org.tatrman.fuzzy.core.MatchVersion
import org.tatrman.fuzzy.core.RetrievalMode
import org.tatrman.fuzzy.core.SourceTag
import org.tatrman.fuzzy.core.StringRepository
import org.tatrman.fuzzy.core.TargetClass
import org.tatrman.fuzzy.loader.DeclaredValue
import org.tatrman.fuzzy.loader.DeclaredVocabulary
import org.tatrman.fuzzy.loader.DeclaredVocabularyEntry
import org.tatrman.fuzzy.loader.LexiconArchiveSource
import org.tatrman.fuzzy.loader.LoaderSource
import org.tatrman.fuzzy.loader.SnapshotVocabularySource
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyServiceGrpcKt
import org.tatrman.fuzzy.v1.FuzzyStatusRequest
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.fuzzy.v1.LookupRequest
import org.tatrman.fuzzy.v1.LookupResponse
import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.StatusResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.client.NlpClient
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ValueFinding
import org.tatrman.resolver.v1.ValueKind
import org.tatrman.ttr.lexicon.LexiconArea
import org.tatrman.ttr.lexicon.compile.LexiconCompiler
import org.tatrman.ttr.lexicon.compile.LexiconPacker
import org.tatrman.ttr.lexicon.compile.LexiconSources
import org.tatrman.ttr.lexicon.compile.LexiconStdlib
import org.tatrman.ttr.lexicon.compile.ModelRefIndex
import java.nio.file.Files
import kotlin.io.path.writeBytes
import org.tatrman.resolver.registry.DeclaredVocabulary as ResolverDeclaredVocabulary

/**
 * review-103 — the quoted-literal path end to end, against the **shipped** `pred:` slice.
 *
 * Every earlier LP test built its predicate rows by hand, and a hand-built row says what its
 * author believed the slice held. This one compiles `LexiconStdlib.predicateSlices()` — the file
 * that ships in the `ttr-lexicon-compile` jar this build pins — packs it, reads it back through
 * lex-matcher's own archive reader, and serves it from a REAL `FuzzyMatcher` (v2, index-first,
 * the service default since ✅LP-23) over in-process gRPC. The parse is the **floor** parse: the
 * `\w+|[^\w\s]` tokenisation with no dependency tree, which is what hartland runs on today while
 * ttr-server#8 is open, and the parse on which every one of F1 and F2 was live.
 *
 * Each case is one of the review's probes. The assertions are on the VERBATIM value the pipeline
 * emits — what kantheon reads — never on an intermediate.
 */
class LpRealSliceTest :
    StringSpec({

        "F1 — *named* is not *named exactly*: a fragment of a form does not fire it" {
            val value = verbatimOf(resolve("Show customers named \"Valmy\""))

            value.attributionsList.single().attributeRef shouldBe CUSTOMER_NAME
            // Before: `pred:equals` off the fragment `named` of *named exactly*, so a partial name
            // the user quoted became `name = ?`. Now no trigger fired, and §2.2's default is sent
            // explicitly (D5): a name is searched inside.
            value.predicateRef shouldBe "pred:contains"
        }

        "F1 — *s názvem* is not *s názvem přesně*" {
            val value = verbatimOf(resolve("Ukaž zákazníky s názvem \"Valmy\""))

            value.attributionsList.single().attributeRef shouldBe CUSTOMER_NAME
            value.predicateRef shouldNotBe "pred:equals"
        }

        "F1 — a one-word fragment (`se` of *rovná se*) fires nothing" {
            // *customers with the name "Valmy"*. Before: `se` alone scored 1.0 against the form
            // *rovná se*, unopposed, and the literal became `= ?`.
            val value = verbatimOf(resolve("Ukaž zákazníky se jménem \"Valmy\""))

            value.attributionsList.single().attributeRef shouldBe CUSTOMER_NAME
            value.predicateRef shouldBe "pred:contains"
        }

        "F2 — the hero attributes on the floor parse (the opening quote no longer counts)" {
            val state = resolve("Ukaž dodací místa začínající na \"Pelex\"")
            val value = verbatimOf(state)

            // Before: d = 5 from the first CONTENT token to the head's FIRST word ⇒ G3.
            value.attributionsList.single().attributeRef shouldBe STORE_NAME
            value.predicateRef shouldBe "pred:starts_with"
            state.gapsList.none { it.kind == GapKind.GAP_KIND_G3_UNATTRIBUTED } shouldBe true
        }

        "F2 — the shorter trigger phrasing attributes too" {
            val value = verbatimOf(resolve("Ukaž prodejny začínající na \"Pelex\""))

            value.attributionsList.single().attributeRef shouldBe STORE_NAME
            value.predicateRef shouldBe "pred:starts_with"
        }

        "F2 — English, the P2c·T7 ① phrasing, attributes to the store" {
            val value = verbatimOf(resolve("Show stores starting with «abl»"))

            value.attributionsList.single().attributeRef shouldBe STORE_NAME
            // `starting with` reaches the slice with the ttr-core fix (F17); on a slice that does
            // not list it yet the default applies. Either way never `equals`, and never headless.
            (value.predicateRef in setOf("pred:starts_with", "pred:contains")) shouldBe true
        }

        "F2b — a head to the LEFT beats a nearer-or-equal one to the right" {
            val value = verbatimOf(resolve("Ukaž prodejny začínající na \"Pel\" podle zákazníků"))

            // Before: attributed to the customer — a store question filtering the customer column.
            value.attributionsList.single().attributeRef shouldBe STORE_NAME
        }

        "F13 — two literals, one trigger text: each gets its own trigger and its own head" {
            val state = resolve("Ukaž prodejny začínající na \"Ax\" a zákazníky začínající na \"Bx\"")
            val values = state.valuesList.filter { it.kind == ValueKind.VALUE_KIND_VERBATIM }

            values shouldHaveSize 2
            val (ax, bx) = values.sortedBy { it.span.start }
            ax.verbatimText shouldBe "Ax"
            ax.predicateRef shouldBe "pred:starts_with"
            ax.attributionsList.single().attributeRef shouldBe STORE_NAME
            bx.verbatimText shouldBe "Bx"
            // Before: ABSENT — the second window's text was deduplicated away with its tokens.
            bx.predicateRef shouldBe "pred:starts_with"
            bx.attributionsList.single().attributeRef shouldBe CUSTOMER_NAME
        }

        "D2 — *not containing* is not_contains, and so is a negator in front of *containing*" {
            val value = verbatimOf(resolve("Show stores not containing \"abl\""))

            value.attributionsList.single().attributeRef shouldBe STORE_NAME
            value.predicateRef shouldBe "pred:not_contains"
        }
    }) {
    private class FloorNlp(
        private val parse: AnalyzeResponse,
    ) : NlpClient {
        override suspend fun analyze(request: AnalyzeRequest): AnalyzeResponse = parse

        // No capabilities at all: the floor, exactly as a language nlp cannot parse reports it.
        override suspend fun getStatus(): StatusResponse = StatusResponse.newBuilder().setReady(true).build()
    }

    private class InProcessFuzzy(
        private val stub: FuzzyServiceGrpcKt.FuzzyServiceCoroutineStub,
    ) : FuzzyClient {
        override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse = stub.batchMatch(request)

        override suspend fun lookup(request: LookupRequest): LookupResponse = stub.lookup(request)

        override suspend fun getStatus(): FuzzyStatusResponse = stub.getStatus(FuzzyStatusRequest.getDefaultInstance())
    }

    /** The stdlib slice as lex-matcher reads it off a real archive, plus the estate's anchors. */
    private class SliceWithAnchors(
        private val slice: LexiconArchiveSource,
    ) : SnapshotVocabularySource {
        override suspend fun fetch(): DeclaredVocabulary =
            DeclaredVocabulary(slice.fetch().entries + ANCHORS.map { (ref, forms) -> anchorEntry(ref, forms) })

        override fun hash(): String = "lp-real-slice"
    }

    companion object {
        private const val STORE = "er.entity.store"
        private const val STORE_NAME = "er.entity.store.name"
        private const val CUSTOMER = "er.entity.customer"
        private const val CUSTOMER_NAME = "er.entity.customer.name"

        /** The anchor words each head is declared under — every surface form the probes use. */
        private val ANCHORS =
            mapOf(
                STORE to listOf("prodejna", "prodejny", "dodací místa", "stores", "store"),
                CUSTOMER to listOf("zákazník", "zákazníky", "zákazníků", "customers", "customer"),
            )

        private fun anchorEntry(
            ref: String,
            forms: List<String>,
        ) = DeclaredVocabularyEntry(
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

        private val registry: Registry =
            Registry
                .newBuilder()
                .addEntityTypes(entityType(STORE, STORE_NAME))
                .addEntityTypes(entityType(CUSTOMER, CUSTOMER_NAME))
                .build()

        private fun entityType(
            ref: String,
            nameRef: String,
        ) = org.tatrman.resolver.v1.EntityType
            .newBuilder()
            .setRef(ref)
            .addCategories(ref)
            .addAllAnchors(ANCHORS.getValue(ref))
            .setObjectKind("entity")
            .setNameAttributeRef(nameRef)
            .build()

        private val archive by lazy {
            val result =
                LexiconCompiler.compile(
                    LexiconSources(area = LexiconArea(LexiconStdlib.predicateSlices(), emptyList())),
                    ModelRefIndex { null },
                    "sha256:" + "cd".repeat(32),
                    "2026-09-25T00:00:00Z",
                )
            Files.createTempDirectory("lp-real-slice").resolve("lexicon.tar.zst").also {
                it.writeBytes(LexiconPacker.pack(result, "sha256:" + "cd".repeat(32), "test").bytes)
            }
        }

        /** The floor tokenizer — `\w+|[^\w\s]` — with no lemma, no POS and no tree. */
        private fun floorParse(text: String): AnalyzeResponse {
            val builder = AnalyzeResponse.newBuilder().setLanguage("cs")
            Regex("(?U)\\w+|[^\\w\\s]").findAll(text).forEach { m ->
                builder.addTokens(
                    Token
                        .newBuilder()
                        .setText(m.value)
                        .setCharStart(m.range.first)
                        .setCharEnd(m.range.last + 1),
                )
            }
            return builder.build()
        }

        private fun verbatimOf(state: ResolutionState): ValueFinding =
            state.valuesList.single { it.kind == ValueKind.VALUE_KIND_VERBATIM }

        private fun resolve(text: String): ResolutionState =
            runBlocking {
                val config =
                    AppConfig(serverPort = 0, grpcPort = 0, grpcReflectionEnabled = false, refreshIntervalSeconds = 0)
                val empty =
                    object : LoaderSource {
                        override suspend fun loadNextCache() =
                            emptyMap<String, List<org.tatrman.fuzzy.core.Candidate>>()
                    }
                val repo =
                    StringRepository(config, empty, snapshotSource = SliceWithAnchors(LexiconArchiveSource(archive)))
                repo.forceRefresh()
                val service =
                    GrpcService(
                        FuzzyMatcher(repo, retrievalMode = RetrievalMode.INDEX_FIRST, matchVersion = MatchVersion.V2),
                        repo,
                    )
                val name = "lp-real-slice-${System.nanoTime()}"
                val server =
                    InProcessServerBuilder
                        .forName(name)
                        .directExecutor()
                        .addService(service)
                        .build()
                        .start()
                val channel = InProcessChannelBuilder.forName(name).directExecutor().build()
                try {
                    val pipeline =
                        ResolverPipeline(
                            FloorNlp(floorParse(text)),
                            InProcessFuzzy(FuzzyServiceGrpcKt.FuzzyServiceCoroutineStub(channel)),
                            SnapshotRegistry(
                                StubRegistrySource(ResolverDeclaredVocabulary(), ""),
                                ResolverThresholds.LIVE,
                            ),
                            emptyMap(),
                            ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1"),
                        )
                    pipeline
                        .resolve(
                            ResolveRequest
                                .newBuilder()
                                .setConversationId("lp-real-slice")
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
