// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

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
import org.tatrman.nlp.v1.NerEntity
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.DeclaredLayer
import org.tatrman.resolver.registry.LexiconArchiveRegistrySource
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.ResolveResponse
import org.tatrman.text.Normalization
import org.tatrman.ttr.lexicon.CompiledLexicon
import org.tatrman.ttr.lexicon.TargetClass
import org.tatrman.ttr.lexicon.compile.LexiconBuild
import org.tatrman.ttr.lexicon.compile.LexiconBuildOutcome
import org.tatrman.ttr.metadata.LoadIssue
import org.tatrman.ttr.metadata.MetadataLoader
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.source.BuiltinStockSource
import org.tatrman.ttr.metadata.source.FileBasedSource
import org.tatrman.ttr.metadata.source.LocalFsStorage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import org.tatrman.ttr.lexicon.SourceTag as LexiconSourceTag

/**
 * CN-P4 S4.4 — the conceptual tier's shared fixture estate (`shared/testdata/cn-estate`), compiled HERE by the
 * real producer at the pinned grammar (`LexiconBuild.run`, without the stdlib — the golden's own setting), packed
 * by the real packer and read back through [LexiconArchiveRegistrySource]: the channel every deployed estate uses.
 * lex-matcher reads the same estate the same way (S4.3), so the two readers see one archive.
 *
 * [Matcher] stands in for lex-matcher: it serves the archive's own object rows (concept terms included, each
 * under its own ref, as S4.3 pinned) and a data layer of member values the test names.
 */
object CnEstate {
    /** What the golden was compiled with: `expected/lexicon.json`'s header. */
    const val SNAPSHOT_ID = "sha256:abababababababababababababababababababababababababababababababab"
    const val BUILT_AT = "2026-10-22T00:00:00Z"

    val root: Path = Path.of(System.getProperty("cnEstate") ?: error("cnEstate not set"))

    /** The model as the CLI loads it: stock roles + the estate's `model/`, the package/directory mismatch tolerated. */
    private fun model(root: Path): Model {
        val result =
            MetadataLoader(
                listOf(
                    BuiltinStockSource(),
                    FileBasedSource("repo", 100, LocalFsStorage("repo", root.resolve("model"))),
                ),
            ).load()
        val fatal = result.errors.filter { it.category != LoadIssue.Category.PACKAGE_MISMATCH }
        check(fatal.isEmpty()) { fatal.joinToString("\n") { "${it.file}:${it.line} ${it.message}" } }
        return result.model!!
    }

    fun build(
        root: Path = this.root,
        snapshotId: String = SNAPSHOT_ID,
    ): LexiconBuildOutcome =
        LexiconBuild.run(root, model(root), snapshotId, BUILT_AT, "cn-estate-test", includeStdlib = false).also {
            check(it.ok) { "the estate's lexicon files do not parse: ${it.violations}" }
        }

    /** The v6 lexicon ttr-core's `CnArchiveV6Spec` holds byte-stable over this estate. */
    fun golden(): CompiledLexicon = CompiledLexicon.fromJson(root.resolve("expected/lexicon.json").readText())

    /** [outcome]'s archive, written to [path]. */
    fun pack(
        outcome: LexiconBuildOutcome = build(),
        path: Path = Files.createTempDirectory("cn-estate").resolve("lexicon.tar.zst"),
    ): Path = path.also { it.writeBytes(outcome.packed.bytes) }

    /**
     * CN-P5 S5.1 — the estate's archive as a deployed build writes it: the header names the content id of the model it
     * was compiled from (ttr-lexicon-cli's `ModelContentId`), which is what the cn doors pin their model to. The golden
     * names a placeholder instead ([SNAPSHOT_ID]), so a door over [pack]'s archive refuses the model as skewed.
     */
    fun pinnedArchive(root: Path = this.root): Path =
        pack(
            build(
                root,
                org.tatrman.ttr.lexicon.cli.ModelContentId
                    .of(root.resolve("model")),
            ),
        )

    /** A writable copy of the estate, [edit]ed. */
    fun variant(edit: (Path) -> Unit): Path {
        val to = Files.createTempDirectory("cn-estate")
        Files.walk(root).use { paths ->
            for (p in paths.toList()) {
                val dest = to.resolve(root.relativize(p).toString())
                if (Files.isDirectory(p)) dest.createDirectories() else Files.copy(p, dest)
            }
        }
        return to.also(edit)
    }

    /** One textual replacement in an estate file, which must happen. */
    fun Path.replace(
        old: String,
        new: String,
    ) {
        val text = readText()
        check(old in text) { "'$old' is not in $this" }
        writeText(text.replace(old, new))
    }

    /**
     * The same estate without its conceptual tier: no `model cn`, no bindings, no concept terms — a v5-shaped build of
     * the same package. The concept term lines are BLANKED, not deleted, so every other row keeps its provenance line.
     */
    fun withoutCn(): Path =
        variant { dir ->
            dir.resolve("model/cn").toFile().deleteRecursively()
            dir.resolve("model/binding").toFile().deleteRecursively()
            for (lang in listOf("cs", "en")) {
                val terms = dir.resolve("model/lexicon/$lang/terms.ttrm")
                terms.writeText(
                    terms.readLines().joinToString("\n", postfix = "\n") {
                        if ("for: cn." in
                            it
                        ) {
                            ""
                        } else {
                            it
                        }
                    },
                )
            }
        }

    fun registry(archive: Path = pack()): SnapshotRegistry =
        SnapshotRegistry(LexiconArchiveRegistrySource(archive), ResolverThresholds.LIVE)

    fun entityTypes(archive: Path = pack()): List<ResolverEntityType> =
        runBlocking {
            registry(archive).current().entityTypes
        }

    /** A data-layer member row: [id] in the vocabulary of attribute [category], spelled [label]. */
    data class Member(
        val id: String,
        val label: String,
        val category: String,
    )

    /**
     * The data layer the estate's indexed attributes would hold, keyed by the folded forms a question may spell a
     * value in. "Praha" is a city of a customer address AND of a warehouse (Hartland's `TN` shape: the resolver's
     * tier M has a tie to break); "Brno" only of a customer address.
     */
    val MEMBERS: Map<String, List<Member>> =
        run {
            val praha =
                listOf(
                    Member("Praha", "Praha", "er.entity.customer_address.city"),
                    Member("Praha", "Praha", "er.entity.warehouse.city"),
                )
            val brno = listOf(Member("Brno", "Brno", "er.entity.customer_address.city"))
            mapOf(
                "praha" to praha,
                "prahy" to praha,
                "brno" to brno,
                "brna" to brno,
                "vip" to listOf(Member("VIP", "VIP", "er.entity.customer.segment")),
                "web" to listOf(Member("web", "web", "er.entity.channel_sales.channel")),
            )
        }

    /** A resolve through the archive channel, and the matcher that answered it (for its request log). */
    fun resolve(
        text: String,
        tokens: Array<Token>,
        lang: String = "cs",
        archive: Path = pack(),
        members: Map<String, List<Member>> = MEMBERS,
        entities: List<NerEntity> = emptyList(),
        /** BatchMatch questions (by the categories they ask) the matcher answers with NOTHING — the broad pass's only. */
        blindBatch: (List<String>) -> Boolean = { false },
    ): Pair<ResolveResponse, Matcher> {
        val registry = registry(archive)
        val matcher = Matcher(lexiconOf(archive), members, blindBatch)
        val parse =
            MhMembers
                .parse(text, tokens, lang)
                .toBuilder()
                .addAllEntities(entities)
                .build()
        val pipeline =
            ResolverPipeline(
                MhMembers.FakeNlp(parse, lang),
                matcher,
                registry,
                emptyMap(),
                ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1"),
            )
        val request =
            ResolveRequest
                .newBuilder()
                .setConversationId("cn-p4")
                .setFresh(FreshQuestion.newBuilder().setText(text).setLocale(lang))
                .build()
        return runBlocking { pipeline.resolve(request) } to matcher
    }

    private fun pipeline(
        archive: Path,
        matcher: Matcher,
        parse: org.tatrman.nlp.v1.AnalyzeResponse = MhMembers.parse("", emptyArray()),
    ) = ResolverPipeline(
        MhMembers.FakeNlp(parse, "cs"),
        matcher,
        registry(archive),
        emptyMap(),
        ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1"),
    )

    /** A resume of [token] picking [optionId], keyed like [resolve]'s pipeline; a signed pin calls no fake. */
    fun resume(
        token: String,
        optionId: String,
        archive: Path,
    ): ResolveResponse =
        runBlocking {
            pipeline(archive, Matcher(lexiconOf(archive), emptyMap())).resolve(
                ResolveRequest
                    .newBuilder()
                    .setConversationId("cn-p4")
                    .setResume(
                        org.tatrman.resolver.v1.ResumeAnswer
                            .newBuilder()
                            .setToken(token)
                            .setSelectedOptionId(optionId),
                    ).build(),
            )
        }

    /** `resolve.gate:v1` over [lattice] with [hypotheses], answered by the archive's rows and [members]. */
    fun gate(
        lattice: org.tatrman.resolver.v1.ResolutionState,
        hypotheses: List<org.tatrman.resolver.v1.Hypothesis>,
        archive: Path,
        members: Map<String, List<Member>> = MEMBERS,
    ): org.tatrman.resolver.v1.GateResponse =
        runBlocking {
            pipeline(archive, Matcher(lexiconOf(archive), members)).gate(
                org.tatrman.resolver.v1.GateRequest
                    .newBuilder()
                    .setLattice(lattice)
                    .addAllHypotheses(hypotheses)
                    .build(),
            )
        }

    /** The lexicon inside a packed archive, decoded the way the readers decode it. */
    fun lexiconOf(archive: Path): CompiledLexicon =
        (DeclaredLayer.ArchiveSet(listOf(archive)).pass() as DeclaredLayer.Pass.Served).archives.single().lexicon

    /**
     * lex-matcher, as far as the resolver can tell: each object row of the archive under its own ref (its category),
     * with the archive's method, its term as the candidate and its class (`MODEL_OBJECT`), as the declared layer serves it; and the member
     * values of [members], each in its attribute's vocabulary. Categories are an exact-key filter, and no categories
     * is the cross-category ask. A row answers a query whose words match its term word for word — a folded word, or
     * one sharing a long enough stem (the inflection the lemma lookup would otherwise reach).
     */
    class Matcher(
        private val lexicon: CompiledLexicon,
        private val members: Map<String, List<Member>>,
        private val blindBatch: (List<String>) -> Boolean = { false },
    ) : FuzzyClient {
        data class Asked(
            val via: String,
            val query: String,
            val categories: List<String>,
            val answered: List<String>,
        )

        val log = mutableListOf<Asked>()

        override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse {
            val builder = BatchMatchResponse.newBuilder()
            for (span in request.spansList) {
                val matches =
                    if (blindBatch(
                            span.categoriesList,
                        )
                    ) {
                        emptyList()
                    } else {
                        answer(span.query, span.categoriesList.toSet().ifEmpty { null })
                    }
                log += Asked("batch", span.query, span.categoriesList, matches.map(::refOf))
                builder.addResults(FuzzyMatchResponse.newBuilder().addAllMatches(matches))
            }
            return builder.build()
        }

        override suspend fun lookup(request: LookupRequest): LookupResponse {
            val matches =
                answer(request.term, request.categoriesList.toSet().ifEmpty { null })
                    .filter { request.targetClassesCount == 0 || it.source != SourceTag.MEMBER }
            log += Asked("lookup", request.term, request.categoriesList, matches.map(::refOf))
            return LookupResponse.newBuilder().addAllCandidates(matches).build()
        }

        override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()

        private fun refOf(m: FuzzyMatch) =
            if (m.source ==
                SourceTag.MEMBER
            ) {
                "${m.category}#${m.candidateId}"
            } else {
                m.targetRef
            }

        private fun answer(
            query: String,
            scope: Set<String>?,
        ): List<FuzzyMatch> {
            val out = mutableListOf<FuzzyMatch>()
            val words = fold(query).split(' ').filter { it.isNotBlank() }
            if (words.size == 1) {
                for (m in members[words.single()].orEmpty()) {
                    if (scope == null || m.category in scope) out += member(m)
                }
            }
            lexicon.entries
                .filter { it.targetClass == TargetClass.MODEL_OBJECT }
                .filter { scope == null || it.targetRef in scope }
                .filter { matches(it.termNormalized, words) }
                .distinctBy { it.targetRef to it.termNormalized }
                .forEach { out += declared(it.termNormalized, it.targetRef, it.method, it.sourceTag) }
            return out
        }

        private fun matches(
            term: String,
            words: List<String>,
        ): Boolean {
            val t = fold(term).split(' ').filter { it.isNotBlank() }
            return t.size == words.size && t.zip(words).all { (a, q) -> stem(a, q) }
        }

        private fun stem(
            a: String,
            q: String,
        ): Boolean {
            if (a == q) return true
            val shared = a.commonPrefixWith(q).length
            return shared >= 4 && shared >= minOf(a.length, q.length) - 2
        }

        private fun fold(v: String) = Normalization.fold(v)

        private fun member(m: Member): FuzzyMatch =
            base(m.label, m.category, "EXACT")
                .setCandidateId(m.id)
                .setSource(SourceTag.MEMBER)
                .build()

        private fun declared(
            term: String,
            ref: String,
            method: String,
            source: LexiconSourceTag,
        ): FuzzyMatch =
            base(term, ref, method)
                .setCandidateId("lex:$ref")
                .setTargetRef(ref)
                .setTargetClass(org.tatrman.fuzzy.v1.TargetClass.TARGET_CLASS_MODEL_OBJECT)
                .setSource(if (source == LexiconSourceTag.METADATA) SourceTag.METADATA else SourceTag.DECLARED)
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
