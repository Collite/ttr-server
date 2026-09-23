// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import com.google.protobuf.util.JsonFormat
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.fuzzy.api.GrpcService
import org.tatrman.fuzzy.config.AppConfig
import org.tatrman.fuzzy.core.Candidate
import org.tatrman.fuzzy.core.FuzzyMatcher
import org.tatrman.fuzzy.core.MatchVersion
import org.tatrman.fuzzy.core.RetrievalMode
import org.tatrman.fuzzy.core.SourceTag
import org.tatrman.fuzzy.core.StringRepository
import org.tatrman.fuzzy.core.TargetClass
import org.tatrman.fuzzy.loader.LoaderSource
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyServiceGrpcKt
import org.tatrman.fuzzy.v1.FuzzyStatusRequest
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.fuzzy.v1.LookupRequest
import org.tatrman.fuzzy.v1.LookupResponse
import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Capability
import org.tatrman.nlp.v1.NlpOp
import org.tatrman.nlp.v1.StatusResponse
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.client.NlpClient
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolveRequest

/**
 * LP-P0·S3 T4 — **the hero verdict diff** (architecture §6 risk 1). The four lattice heroes
 * (`h1-cs`, `h1prime-cs`, `h2-cs`, `h5-cs`) resolved by the REAL pipeline against a REAL
 * lex-matcher (`GrpcService` + `FuzzyMatcher`, in-process gRPC), once with `fuzzy.match.version=v1`
 * and once with `v2`. The verdicts — which mentions bind to which refs, the evidence classes, the
 * gaps, the outcome and the ask's option SET — must be identical; only scores, provenance and ask
 * option ORDER may move.
 *
 * `LatticeGoldenTest` answers from a fake that replays each case's `matcher.byQuery` verbatim,
 * which can never see an engine change. Here that same table becomes an ESTATE instead: each
 * declared row is served under the surface form the case queries it by (what the compiled
 * archive's morphology expansion does — an `EXACT` row answers `nákladů` only if `nákladů` is a
 * row) plus its base form; member rows as stated. Both engines see that one estate.
 */
class LpHeroVerdictDiffTest :
    StringSpec({
        val heroes = listOf("h1-cs", "h1prime-cs", "h2-cs", "h5-cs")

        heroes.forEach { id ->
            "$id: v1 and v2 reach identical Binder verdicts on a live lex-matcher" {
                val case = loadJson("/lattice/$id.case.json")
                val v1 = runBlocking { resolveWith(case, id, MatchVersion.V1) }
                val v2 = runBlocking { resolveWith(case, id, MatchVersion.V2) }

                // Not vacuous: the live estate really does bind something under v1.
                bindingCount(v1) shouldBeGreaterThan 0

                val p1 = verdict(v1)
                val p2 = verdict(v2)
                if (p1 != p2) {
                    println("LP-P0·S3 T4 $id VERDICT DIFF\n v1: $p1\n v2: $p2")
                }
                p2 shouldBe p1
                println("LP-P0·S3 T4 $id raw paths that moved: ${diffPaths(v1, v2)}")
                println(
                    "LP-P0·S3 T4 $id: verdict identical (${bindingCount(v1)} binding(s)); " +
                        "ask option order identical: ${optionOrder(v1) == optionOrder(v2)}",
                )
            }
        }
    }) {
    private class FixtureNlp(
        private val parse: AnalyzeResponse,
    ) : NlpClient {
        override suspend fun analyze(request: AnalyzeRequest): AnalyzeResponse = parse

        override suspend fun getStatus(): StatusResponse =
            StatusResponse
                .newBuilder()
                .setReady(true)
                .addCapabilities(capability(NlpOp.NER, "nametag3", "cnec2.0"))
                .addCapabilities(capability(NlpOp.DEP_PARSE, "stanza", "1.13.0"))
                .build()

        private fun capability(
            op: NlpOp,
            engine: String,
            version: String,
        ): Capability =
            Capability
                .newBuilder()
                .setLanguage("cs")
                .setOp(op)
                .setEngine(engine)
                .setModelVersion(version)
                .build()
    }

    /** The resolver's [FuzzyClient] over an in-process channel to a real lex-matcher. */
    private class InProcessFuzzy(
        private val stub: FuzzyServiceGrpcKt.FuzzyServiceCoroutineStub,
    ) : FuzzyClient {
        override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse = stub.batchMatch(request)

        override suspend fun lookup(request: LookupRequest): LookupResponse = stub.lookup(request)

        override suspend fun getStatus(): FuzzyStatusResponse = stub.getStatus(FuzzyStatusRequest.getDefaultInstance())
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val printer: JsonFormat.Printer = JsonFormat.printer().omittingInsignificantWhitespace()
        private val parser: JsonFormat.Parser = JsonFormat.parser().ignoringUnknownFields()

        /** Keys whose values are allowed to move between engines — numbers and provenance, not verdicts. */
        private val MOVABLE =
            setOf(
                "score",
                "rawScore",
                "inClassScore",
                "uniquenessMargin",
                "coverage",
                "tokenHits",
                "provenance",
                "algorithm",
                "elapsedMs",
                "parse",
                "resumeToken",
                // Derived from the winning scores (Binder rationale), so v2's ε·C moves it — by
                // ≤ ε. A number about the verdict, not the verdict; the raw diff below prints it.
                "confidence",
            )

        private suspend fun resolveWith(
            case: JsonObject,
            id: String,
            version: MatchVersion,
        ): JsonObject {
            val repo =
                StringRepository(
                    AppConfig(serverPort = 0, grpcPort = 0, grpcReflectionEnabled = false, refreshIntervalSeconds = 0),
                    estateOf(case),
                )
            repo.forceRefresh()
            val service =
                GrpcService(FuzzyMatcher(repo, retrievalMode = RetrievalMode.INDEX_FIRST, matchVersion = version), repo)
            val name = "lp-hero-$id-${version.wire}-${System.nanoTime()}"
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
                        FixtureNlp(
                            merge(loadJson("/lattice/${case.str("parseFile")}"), AnalyzeResponse.newBuilder()).build(),
                        ),
                        InProcessFuzzy(FuzzyServiceGrpcKt.FuzzyServiceCoroutineStub(channel)),
                        SnapshotRegistry(StubRegistrySource(DeclaredVocabulary(), ""), ResolverThresholds.LIVE),
                        emptyMap(),
                        ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1"),
                    )
                val request =
                    ResolveRequest
                        .newBuilder()
                        .setConversationId("$id-${version.wire}")
                        .setFresh(FreshQuestion.newBuilder().setText(case.str("text")).setLocale(case.str("locale")))
                        .setRegistry(merge(case["registry"]!!.jsonObject, Registry.newBuilder()).build())
                        .build()
                return json.parseToJsonElement(printer.print(pipeline.resolve(request))).jsonObject
            } finally {
                channel.shutdownNow()
                server.shutdownNow()
                repo.close()
            }
        }

        /**
         * The case's `matcher.byQuery` table as an estate: category → candidates. A declared row is
         * served under the queried surface form AND its base form (deduplicated by id); a member row
         * as stated.
         */
        private fun estateOf(case: JsonObject): LoaderSource {
            val byCategory = LinkedHashMap<String, LinkedHashMap<String, Candidate>>()
            for ((query, rows) in case["matcher"]!!.jsonObject["byQuery"]!!.jsonObject) {
                for (row in rows.jsonArray.map { it.jsonObject }) {
                    val category = row.str("category")
                    val bucket = byCategory.getOrPut(category) { LinkedHashMap() }
                    val source = SourceTag.valueOf(row.str("source").removePrefix("SOURCE_TAG_"))
                    val baseId = row.str("candidateId")
                    if (source == SourceTag.MEMBER) {
                        bucket.getOrPut(baseId) { Candidate.fromValues(baseId, row.str("candidate")) }
                        continue
                    }
                    val targetClass =
                        row["targetClass"]?.jsonPrimitive?.content?.let {
                            TargetClass.valueOf(it.removePrefix("TARGET_CLASS_"))
                        }
                    for (form in listOf(query, row.str("candidate")).distinct()) {
                        val formId = if (form == row.str("candidate")) baseId else "$baseId@$form"
                        bucket.getOrPut(formId) {
                            Candidate.vocabulary(
                                id = formId,
                                value = form,
                                targetRef = row["targetRef"]?.jsonPrimitive?.content ?: category,
                                source = source,
                                matchMethod = row["matchMethod"]?.jsonPrimitive?.content,
                                targetClass = targetClass,
                            )
                        }
                    }
                }
            }
            val snapshot = byCategory.mapValues { it.value.values.toList() }
            return object : LoaderSource {
                override suspend fun loadNextCache() = snapshot
            }
        }

        /** The verdict projection: everything except [MOVABLE] keys, with ask options as a SET. */
        private fun verdict(response: JsonObject): JsonElement = project(response)

        private fun project(
            e: JsonElement,
            key: String? = null,
        ): JsonElement =
            when (e) {
                is JsonObject -> JsonObject(e.filterKeys { it !in MOVABLE }.mapValues { (k, v) -> project(v, k) })
                is JsonArray -> {
                    val items = e.map { project(it) }
                    if (key == "options") JsonArray(items.sortedBy { it.toString() }) else JsonArray(items)
                }
                else -> e
            }

        /** Every leaf path whose value differs between the two raw responses (for the report). */
        private fun diffPaths(
            a: JsonElement?,
            b: JsonElement?,
            path: String = "",
        ): List<String> =
            when {
                a is JsonObject && b is JsonObject ->
                    (a.keys + b.keys).flatMap { k -> diffPaths(a[k], b[k], "$path.$k") }
                a is JsonArray && b is JsonArray && a.size == b.size ->
                    a.indices.flatMap { i -> diffPaths(a[i], b[i], "$path[$i]") }
                a == b -> emptyList()
                else -> listOf("$path: $a → $b")
            }

        private fun optionOrder(response: JsonObject): List<String> =
            response["awaiting"]
                ?.jsonObject
                ?.get("options")
                ?.jsonArray
                .orEmpty()
                .map { project(it).toString() }

        private fun bindingCount(response: JsonObject): Int =
            response["resolutionState"]
                ?.jsonObject
                ?.get("mentions")
                ?.jsonArray
                .orEmpty()
                .sumOf { it.jsonObject["bindings"]?.jsonArray?.size ?: 0 }

        private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

        private fun <T : com.google.protobuf.Message.Builder> merge(
            source: JsonObject,
            builder: T,
        ): T {
            parser.merge(source.toString(), builder)
            return builder
        }

        private fun loadJson(resource: String): JsonObject =
            json
                .parseToJsonElement(
                    LpHeroVerdictDiffTest::class.java.getResource(resource)?.readText()
                        ?: error("missing test resource $resource"),
                ).jsonObject
    }
}
