// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.StatusResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.client.NlpClient
import org.tatrman.resolver.mcp.ResolveDoor
import org.tatrman.resolver.mcp.ResolveDoorHandler
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.DeclaredValue
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.DeclaredVocabularyEntry
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec

/**
 * A HERMETIC real-pipeline harness for the `calls:` conformance tests (RG-P6.S2 —
 * the seam the S1 stubs promised to replace). It runs the ACTUAL
 * [ResolverPipeline] — `SpanProposal` (n-gram floor, no dep parse needed) →
 * `GateSpans` (the refuse-over-guess heart) → real HMAC [ResumeTokenCodec] — with
 * the upstream nlp/fuzzy replaced by deterministic in-memory fakes fed
 * recorded-shaped data. No network, no live service, no LLM.
 *
 * The point (RG-P6 review G): a genuine resolver defect — binding below the bind
 * threshold, or turning an ambiguous span into a guessed bind — now FAILS these
 * tests, because the assertion runs against the real gate, not a canned response.
 */
object ConformancePipeline {
    /** A fixed 32-byte test HMAC key, so both turns of a round-trip share a codec. */
    private val TEST_KEY = ByteArray(32) { (it + 1).toByte() }

    private val THRESHOLDS = ResolverThresholds(bind = 0.5, ambiguityGap = 0.05, exact = 0.9999, maxOptions = 20)

    /** The declared vocabulary the fuzzy matches below resolve against. */
    private val VOCAB =
        DeclaredVocabulary(
            entries =
                listOf(
                    DeclaredVocabularyEntry(
                        category = "er.qstred_df.member",
                        targetRef = "er.qstred_df",
                        values = listOf(DeclaredValue("df-adnak", "DF ADNAK"), DeclaredValue("df-belus", "DF BELUS")),
                    ),
                ),
        )

    fun codec(): ResumeTokenCodec = ResumeTokenCodec(mapOf("k1" to TEST_KEY), activeKeyId = "k1", maxAgeSeconds = 3600)

    /** The REAL pipeline for [scenario] over hermetic fakes (used by the subject-binding test). */
    fun pipeline(
        scenario: String,
        codec: ResumeTokenCodec = codec(),
    ): ResolverPipeline {
        val registry = SnapshotRegistry(StubRegistrySource(VOCAB, "test-snap"), THRESHOLDS)
        return ResolverPipeline(FakeNlp, FakeFuzzy(scenario), registry, siblings = emptyMap(), tokenCodec = codec)
    }

    /**
     * A door handler backed by a REAL pipeline for [scenario]. `requireIdentity =
     * false` (dev-network) so the token is issued/resumed with an empty subject —
     * the subject-binding path is exercised separately in ResolveDoorTest.
     */
    fun doorHandler(
        scenario: String,
        codec: ResumeTokenCodec = codec(),
    ): ResolveDoorHandler {
        val registry = SnapshotRegistry(StubRegistrySource(VOCAB, "test-snap"), THRESHOLDS)
        val pipeline =
            ResolverPipeline(FakeNlp, FakeFuzzy(scenario), registry, siblings = emptyMap(), tokenCodec = codec)
        return ResolveDoorHandler(ResolveDoor(pipeline::resolve), requireIdentity = false)
    }

    // --- fakes --------------------------------------------------------------

    /** Whitespace tokenizer → parse-less tokens (dep_head = 0) so SpanProposal takes the n-gram floor. */
    private object FakeNlp : NlpClient {
        override suspend fun analyze(request: AnalyzeRequest): AnalyzeResponse {
            val text = request.text
            val builder = AnalyzeResponse.newBuilder().setLanguage(request.language).setTraceId("test-trace")
            var cursor = 0
            for (word in text.split(" ")) {
                if (word.isEmpty()) {
                    cursor += 1
                    continue
                }
                val start = text.indexOf(word, cursor)
                val end = start + word.length
                builder.addTokens(
                    Token
                        .newBuilder()
                        .setText(word)
                        .setLemma(word)
                        .setUpos("X")
                        .setDepHead(0)
                        .setCharStart(start)
                        .setCharEnd(end),
                )
                cursor = end + 1
            }
            return builder.build()
        }

        override suspend fun getStatus(): StatusResponse = StatusResponse.getDefaultInstance()
    }

    /** Returns recorded-shaped fuzzy matches per scenario, positional to the batch spans. */
    private class FakeFuzzy(
        private val scenario: String,
    ) : FuzzyClient {
        override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse {
            val perSpan =
                when (scenario) {
                    // Two distinct MEMBERs tie within the 0.05 gap for the one span ⇒ the
                    // real gate must CLARIFY (never guess). 0.80 vs 0.79.
                    "ambiguous_member" ->
                        listOf(
                            listOf(
                                member("df-adnak", "DF ADNAK", 0.80),
                                member("df-belus", "DF BELUS", 0.79),
                            ),
                        )
                    // Every match is below the 0.5 bind floor ⇒ the real gate must bind
                    // NOTHING (an empty resolution).
                    "below_threshold" ->
                        List(request.spansCount.coerceAtLeast(1)) {
                            listOf(member("noise", "neznámý", 0.30))
                        }
                    // LP §2 — the estate has nothing to say about any span in this question. The
                    // assertion is not about what the matcher answered: it is about what it was
                    // ASKED, and a quoted span must never be among the questions.
                    "verbatim_literal" -> List(request.spansCount.coerceAtLeast(1)) { emptyList() }
                    else -> emptyList()
                }
            val builder = BatchMatchResponse.newBuilder()
            for (matches in perSpan) {
                val r = FuzzyMatchResponse.newBuilder()
                matches.forEach { r.addMatches(it) }
                builder.addResults(r)
            }
            return builder.build()
        }

        override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()

        private fun member(
            id: String,
            label: String,
            score: Double,
        ): FuzzyMatch =
            FuzzyMatch
                .newBuilder()
                .setCandidateId(id)
                .setCandidate(label)
                .setScore(score)
                .setCategory("er.qstred_df.member")
                .setSource(SourceTag.MEMBER)
                .build()
    }
}
