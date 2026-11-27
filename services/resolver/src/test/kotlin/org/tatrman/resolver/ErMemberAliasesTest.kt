// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.runBlocking
import org.tatrman.fuzzy.api.GrpcService
import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.core.Candidate
import org.tatrman.fuzzy.core.FuzzyMatcher
import org.tatrman.fuzzy.core.MatchProfile
import org.tatrman.fuzzy.core.MatchVersion
import org.tatrman.fuzzy.core.Norm
import org.tatrman.fuzzy.core.NormRule
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
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.fuzzy.v1.LookupRequest
import org.tatrman.fuzzy.v1.LookupResponse
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.NerEntity
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.NamedCategorySource
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.registry.DeclaredVocabulary as ResolverDeclaredVocabulary
import org.tatrman.resolver.v1.EntityType as RegistryEntityType

// End-to-end verification of ER declared member aliases (lexicon/values + valueLabels)
// and inflected person names (both ungoverned NAMED_MEMBER and governed dual readings
// with nameLemma) through a real StringRepository + FuzzyMatcher + ResolverPipeline.
class ErMemberAliasesTest :
    StringSpec({
        val empName = "er.entity.employee.full_name"
        val empEntity = "er.entity.employee"
        val utilEntity = "er.entity.employee_utilization"

        fun token(
            text: String,
            start: Int,
            lemma: String = text.lowercase(),
            upos: String = "NOUN",
            dep: String = "nmod",
            depHead: Int = 0,
        ): Token =
            Token
                .newBuilder()
                .setText(text)
                .setCharStart(start)
                .setCharEnd(start + text.length)
                .setLemma(lemma)
                .setUpos(upos)
                .setDepRelation(dep)
                .setDepHead(depHead)
                .build()

        fun personEntity(
            text: String,
            start: Int,
        ): NerEntity =
            NerEntity
                .newBuilder()
                .setText(text)
                .setLabel("PERSON")
                .setNormalizedValue("cnec:P|B-pf")
                .setCharStart(start)
                .setCharEnd(start + text.length)
                .setSourceEngine("nametag3")
                .build()

        fun resolveThroughRealMatcher(
            question: String,
            parse: AnalyzeResponse,
            withAnchorOnUtil: Boolean = false,
        ) = runBlocking {
            val memberLoader =
                object : LoaderSource {
                    override suspend fun loadNextCache(): Map<String, List<Candidate>> =
                        mapOf(
                            // Include Jan Novák alongside several Jana/Nováková colleagues that
                            // otherwise crowd out top-10 lookup on Czech genitive "Jana Nováka".
                            empName to
                                listOf(
                                    "Jan Novák",
                                    "Jana Malá",
                                    "Jana Veselá",
                                    "Jana Dvořáková",
                                    "Petra Malá",
                                    "Petra Veselá",
                                    "Petra Dvořáková",
                                    "Petr Malý",
                                ).map { name ->
                                    val toks = Candidate.tokenize(name)
                                    Candidate
                                        .withLemmas(
                                            name,
                                            name,
                                            toks,
                                            toks,
                                            matchMethod = "TOKENS",
                                            category = empName,
                                        )
                                },
                        )
                }

            val profile =
                MatchProfile(
                    listOf(
                        NormRule(Norm.CANONICAL, exact = 1.0),
                        NormRule(Norm.FOLDED, exact = 0.90),
                    ),
                )
            val vocabSource =
                object : SnapshotVocabularySource {
                    override suspend fun fetch(): DeclaredVocabulary =
                        DeclaredVocabulary(
                            listOf(
                                DeclaredVocabularyEntry(
                                    category = utilEntity,
                                    targetRef = utilEntity,
                                    values =
                                        listOf(
                                            DeclaredValue(
                                                id = "lex:$utilEntity:cs:utilizace",
                                                value = "utilizace",
                                                source = SourceTag.DECLARED,
                                                matchMethod = "EXACT",
                                                targetClass = TargetClass.MODEL_OBJECT,
                                            ),
                                        ),
                                ),
                                DeclaredVocabularyEntry(
                                    category = "$empName.Jan Novák",
                                    targetRef = "$empName.Jan Novák",
                                    values =
                                        listOf(
                                            DeclaredValue(
                                                id = "lex:$empName.Jan Novák:cs:honza novák",
                                                value = "honza novák",
                                                source = SourceTag.DECLARED,
                                                matchMethod = "EXACT",
                                                targetClass = TargetClass.MEMBER,
                                                matchProfile = profile,
                                            ),
                                        ),
                                ),
                            ),
                        )

                    override fun hash(): String = "test-er-aliases-v1"
                }

            val config =
                AppConfig(serverPort = 0, grpcPort = 0, grpcReflectionEnabled = false, refreshIntervalSeconds = 0)
            val repo =
                StringRepository(
                    config = config,
                    loaderSource = memberLoader,
                    snapshotSource = vocabSource,
                )
            repo.forceRefresh()

            val matcher =
                FuzzyMatcher(
                    repository = repo,
                    retrievalMode = RetrievalMode.INDEX_FIRST,
                    matchVersion = MatchVersion.V2,
                )

            val serverName = "er-aliases-${System.nanoTime()}"
            val server =
                InProcessServerBuilder
                    .forName(serverName)
                    .directExecutor()
                    .addService(GrpcService(matcher, repo))
                    .build()
                    .start()
            val channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
            val stub = FuzzyServiceGrpcKt.FuzzyServiceCoroutineStub(channel)

            val personStatus = MhMembers.profiles(empName to listOf("PERSON" to "cnec:P|B-pf"))
            val fuzzyClient =
                object : FuzzyClient {
                    override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse =
                        stub.batchMatch(request)

                    override suspend fun lookup(request: LookupRequest): LookupResponse = stub.lookup(request)

                    override suspend fun getStatus(): FuzzyStatusResponse = personStatus
                }

            val utilBuilder =
                RegistryEntityType
                    .newBuilder()
                    .setRef(utilEntity)
                    .addCategories(utilEntity)
                    .setObjectKind("entity")
            if (withAnchorOnUtil) {
                utilBuilder.addAnchors("utilizace")
            }

            val registry =
                Registry
                    .newBuilder()
                    .addEntityTypes(utilBuilder.build())
                    .addEntityTypes(
                        RegistryEntityType
                            .newBuilder()
                            .setRef(empEntity)
                            .addCategories(empEntity)
                            .setObjectKind("entity")
                            .addReachedFrom(
                                org.tatrman.resolver.v1.Reach
                                    .newBuilder()
                                    .setFactRef(utilEntity)
                                    .setMandatory(true)
                                    .build(),
                            ).build(),
                    ).addEntityTypes(
                        RegistryEntityType
                            .newBuilder()
                            .setRef(empName)
                            .addCategories(empName)
                            .setObjectKind("attribute")
                            .setOwnerRef(empEntity)
                            .setMemberVocabulary(true)
                            .build(),
                    ).build()

            val pipeline =
                ResolverPipeline(
                    nlp = MhMembers.FakeNlp(parse, "cs"),
                    fuzzy = fuzzyClient,
                    registry =
                        SnapshotRegistry(
                            StubRegistrySource(ResolverDeclaredVocabulary(), ""),
                            ResolverThresholds.LIVE,
                        ),
                    siblings = emptyMap(),
                    tokenCodec = ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1"),
                    namedCategories = NamedCategorySource(fuzzyClient),
                )

            try {
                pipeline.resolve(
                    ResolveRequest
                        .newBuilder()
                        .setConversationId("test-er-aliases")
                        .setFresh(FreshQuestion.newBuilder().setText(question).setLocale("cs"))
                        .setRegistry(registry)
                        .build(),
                )
            } finally {
                channel.shutdownNow()
                server.shutdownNow()
                repo.close()
            }
        }

        "ungoverned inflected Czech genitive „Jana Nováka“ binds Jan Novák" {
            val q = "Jaká je utilizace Jana Nováka?"
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .setLanguage("cs")
                    .addTokens(token("Jaká", 0, "jaký", "DET", "root"))
                    .addTokens(token("je", 5, "být", "AUX", "cop"))
                    .addTokens(token("utilizace", 8, "utilizace", "NOUN", "nsubj"))
                    .addTokens(token("Jana", 18, "Jan", "PROPN", "nmod"))
                    .addTokens(token("Nováka", 23, "Novák", "PROPN", "flat"))
                    .addEntities(personEntity("Jana Nováka", 18))
                    .build()

            val resp = resolveThroughRealMatcher(q, parse)
            val person = resp.resolutionState.valuesList.single { it.span.text == "Jana Nováka" }
            person.attributionsList.map { it.binding.ref } shouldContainExactly listOf("$empName#Jan Novák")
        }

        "ungoverned declared nickname „Honza Novák“ binds Jan Novák via ER member alias" {
            val q = "Jaká je utilizace Honza Novák?"
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .setLanguage("cs")
                    .addTokens(token("Jaká", 0, "jaký", "DET", "root"))
                    .addTokens(token("je", 5, "být", "AUX", "cop"))
                    .addTokens(token("utilizace", 8, "utilizace", "NOUN", "nsubj"))
                    .addTokens(token("Honza", 18, "Honza", "PROPN", "nmod"))
                    .addTokens(token("Novák", 24, "Novák", "PROPN", "flat"))
                    .addEntities(personEntity("Honza Novák", 18))
                    .build()

            val resp = resolveThroughRealMatcher(q, parse)
            val person = resp.resolutionState.valuesList.single { it.span.text == "Honza Novák" }
            person.attributionsList.map { it.binding.ref } shouldContainExactly listOf("$empName#Jan Novák")
        }

        "ungoverned inflected nickname „Honzy Nováka“ binds Jan Novák via nameLemma + ER member alias" {
            val q = "Jaká je utilizace Honzy Nováka?"
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .setLanguage("cs")
                    .addTokens(token("Jaká", 0, "jaký", "DET", "root"))
                    .addTokens(token("je", 5, "být", "AUX", "cop"))
                    .addTokens(token("utilizace", 8, "utilizace", "NOUN", "nsubj"))
                    .addTokens(token("Honzy", 18, "Honza", "PROPN", "nmod"))
                    .addTokens(token("Nováka", 24, "Novák", "PROPN", "flat"))
                    .addEntities(personEntity("Honzy Nováka", 18))
                    .build()

            val resp = resolveThroughRealMatcher(q, parse)
            val person = resp.resolutionState.valuesList.single { it.span.text == "Honzy Nováka" }
            person.attributionsList.map { it.binding.ref } shouldContainExactly listOf("$empName#Jan Novák")
        }

        "governed dual reading „utilizace Jana Nováka“ (nmod) binds Jan Novák" {
            val q = "Jaká je utilizace Jana Nováka?"
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .setLanguage("cs")
                    .addTokens(token("Jaká", 0, "jaký", "DET", "root", depHead = 0))
                    .addTokens(token("je", 5, "být", "AUX", "cop", depHead = 1))
                    .addTokens(token("utilizace", 8, "utilizace", "NOUN", "nsubj", depHead = 1))
                    .addTokens(token("Jana", 18, "Jan", "PROPN", "nmod", depHead = 3))
                    .addTokens(token("Nováka", 23, "Novák", "PROPN", "flat", depHead = 4))
                    .addEntities(personEntity("Jana Nováka", 18))
                    .build()

            val resp = resolveThroughRealMatcher(q, parse, withAnchorOnUtil = true)
            val person = resp.resolutionState.valuesList.single { it.span.text == "Jana Nováka" }
            person.attributionsList.map { it.binding.ref } shouldContainExactly listOf("$empName#Jan Novák")
        }

        "governed dual reading „utilizace Honzy Nováka“ (nmod) binds Jan Novák via nameLemma + ER alias" {
            val q = "Jaká je utilizace Honzy Nováka?"
            val parse =
                AnalyzeResponse
                    .newBuilder()
                    .setLanguage("cs")
                    .addTokens(token("Jaká", 0, "jaký", "DET", "root", depHead = 0))
                    .addTokens(token("je", 5, "být", "AUX", "cop", depHead = 1))
                    .addTokens(token("utilizace", 8, "utilizace", "NOUN", "nsubj", depHead = 1))
                    .addTokens(token("Honzy", 18, "Honza", "PROPN", "nmod", depHead = 3))
                    .addTokens(token("Nováka", 24, "Novák", "PROPN", "flat", depHead = 4))
                    .addEntities(personEntity("Honzy Nováka", 18))
                    .build()

            val resp = resolveThroughRealMatcher(q, parse, withAnchorOnUtil = true)
            val person = resp.resolutionState.valuesList.single { it.span.text == "Honzy Nováka" }
            person.attributionsList.map { it.binding.ref } shouldContainExactly listOf("$empName#Jan Novák")
        }
    })
