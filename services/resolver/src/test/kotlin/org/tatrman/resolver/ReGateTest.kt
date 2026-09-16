// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import com.google.protobuf.util.JsonFormat
import io.grpc.Status
import io.grpc.StatusException
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.fuzzy.v1.BatchMatchRequest
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.FuzzyStatusResponse
import org.tatrman.fuzzy.v1.LookupRequest
import org.tatrman.fuzzy.v1.LookupResponse
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.fuzzy.v1.TargetClass
import org.tatrman.nlp.v1.AnalyzeRequest
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Capability
import org.tatrman.nlp.v1.NlpOp
import org.tatrman.nlp.v1.StatusResponse
import org.tatrman.resolver.client.FuzzyClient
import org.tatrman.resolver.client.NlpClient
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.LookupRoundConfig
import org.tatrman.resolver.pipeline.LookupRounds
import org.tatrman.resolver.pipeline.ReGate
import org.tatrman.resolver.pipeline.ResolverPipeline
import org.tatrman.resolver.registry.DeclaredVocabulary
import org.tatrman.resolver.registry.DeclaredVocabularyEntry
import org.tatrman.resolver.registry.SnapshotRegistry
import org.tatrman.resolver.registry.StubRegistrySource
import org.tatrman.resolver.token.ResumeTokenCodec
import org.tatrman.resolver.v1.Disposition
import org.tatrman.resolver.v1.EvidenceClass
import org.tatrman.resolver.v1.FreshQuestion
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.GapRecord
import org.tatrman.resolver.v1.GateRequest
import org.tatrman.resolver.v1.GateResponse
import org.tatrman.resolver.v1.Hypothesis
import org.tatrman.resolver.v1.Registry
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.ResolveRequest
import org.tatrman.resolver.v1.Span

/**
 * RV-P2.4.T1/T4 — **`resolve.gate:v1`**, the re-gate sibling (Q-13 RULED = B).
 *
 * The tool exists so an LLM rung can be *wrong safely*. A rung reads a lattice, forms a hypothesis
 * — *"`5010O1` is a typo for `501001`"* — and hands it back; the core turns it into a lexicon
 * question and puts the answer through the **same gate** as any other candidate. RV-7's
 * proposer-not-binder, structurally: there is no path in this tool from "a rung said so" to "it is
 * bound".
 *
 * Every case here gates a **real lattice** — one the pipeline actually produced from the h1′/h2
 * fixtures — rather than a hand-built one, because the interesting part of the contract is what
 * happens to the *gaps* afterwards, and a hand-built lattice would let the test choose those.
 */
class ReGateTest :
    StringSpec({

        "(a) H1′ — a correct correction survives the gate and arrives with its provenance" {
            val fuzzy = GateFuzzy(answers = mapOf("501001" to listOf(member("501001", ACCOUNT_CODE))))
            val response =
                gate(
                    fuzzy,
                    lattice = h1primeLattice(),
                    correction("5010O1", 20, 26, to = "501001", ref = ACCOUNT_CODE, rung = "local"),
                )

            val binding = response.gatedBindingsList.single()
            binding.ref shouldBe "$ACCOUNT_CODE#501001"
            // `gate_evidence` is the evidence class, and it is EXACT for the same reason any other
            // member that matched itself is: the hypothesis bought no trust, the lookup did.
            binding.evidenceClass shouldBe EvidenceClass.EVIDENCE_CLASS_EXACT
            binding.producer.proposingRung shouldBe "local"

            response.outcomesList
                .single()
                .accepted
                .shouldBeTrue()
            // (e) the gaps are recomputed, not patched: the G4 this correction closed is gone.
            response.updatedGapsList shouldBe emptyList()
            response.rungLogEntry.rung shouldBe "local"
            response.rungLogEntry.action shouldBe ReGate.ACTION
            response.rungLogEntry.bindingsAdded shouldBe 1

            // the corrected surface is what was looked up, scoped to the ref the rung proposed
            fuzzy.lookups.single().term shouldBe "501001"
            fuzzy.lookups.single().categoriesList shouldContainExactly listOf(ACCOUNT_CODE)
        }

        "(b) a wrong-but-plausible hypothesis binds NOTHING, and the gap it aimed at is untouched" {
            // The ref exists; the term simply is not in it. A rung guessing `501002` gets the same
            // answer as a rung guessing nothing.
            val fuzzy = GateFuzzy(answers = emptyMap())
            val response =
                gate(
                    fuzzy,
                    lattice = h1primeLattice(),
                    correction("5010O1", 20, 26, to = "501002", ref = ACCOUNT_CODE, rung = "local"),
                )

            response.gatedBindingsList shouldBe emptyList()
            val outcome = response.outcomesList.single()
            outcome.accepted.shouldBeFalse()
            outcome.reason shouldBe ReGate.Reason.NO_CANDIDATE
            // unchanged, and still typed — the ladder is exactly where it was, and knows it
            response.updatedGapsList.single().kind shouldBe GapKind.GAP_KIND_G4_METHOD_MISS
        }

        "a candidate that binds something OTHER than the proposed ref is a contradiction, not a confirmation" {
            val fuzzy = GateFuzzy(answers = mapOf("5010O1" to listOf(declared("md.measure.cost"))))
            val response =
                gate(
                    fuzzy,
                    lattice = h1primeLattice(),
                    hypothesis("5010O1", 20, 26, ref = ACCOUNT_CODE, rung = "capable"),
                )
            response.gatedBindingsList shouldBe emptyList()
            response.outcomesList.single().reason shouldBe ReGate.Reason.REF_MISMATCH
        }

        "a candidate the class floor refuses is reported as WEAK, not quietly dropped" {
            val fuzzy = GateFuzzy(answers = mapOf("501001" to listOf(member("5AU 5001", ACCOUNT_CODE, 0.62))))
            val response =
                gate(
                    fuzzy,
                    lattice = h1primeLattice(),
                    correction("5010O1", 20, 26, to = "501001", ref = ACCOUNT_CODE, rung = "local"),
                )
            response.gatedBindingsList shouldBe emptyList()
            response.outcomesList.single().reason shouldBe ReGate.Reason.WEAK
        }

        "(c) H2 pre-learning — vocabulary that does not exist yet binds nothing, and that is correct" {
            val fuzzy = GateFuzzy(answers = emptyMap())
            val h2 = h2Lattice()
            val stanic = h2.mentionsList.single { it.span.text == "čerpacích stanic" }
            val response =
                gate(
                    fuzzy,
                    lattice = h2,
                    hypothesis(
                        stanic.span.text,
                        stanic.span.start,
                        stanic.span.end,
                        ref = "er.fuel_station",
                        rung = "capable",
                    ),
                )

            response.gatedBindingsList shouldBe emptyList()
            response.outcomesList.single().reason shouldBe ReGate.Reason.NO_CANDIDATE
            // The G1 stays. This is the ask/learning path's cue, and forcing a binding here is
            // precisely the issues.md §2 failure the whole effort is aimed at.
            response.updatedGapsList.any { it.kind == GapKind.GAP_KIND_G1_UNBOUND }.shouldBeTrue()
        }

        "(d) a mixed batch partially succeeds, and every hypothesis gets its own verdict" {
            val fuzzy = GateFuzzy(answers = mapOf("501001" to listOf(member("501001", ACCOUNT_CODE))))
            val response =
                gate(
                    fuzzy,
                    lattice = h1primeLattice(),
                    correction("5010O1", 20, 26, to = "501001", ref = ACCOUNT_CODE, rung = "local"),
                    correction("5010O1", 20, 26, to = "999999", ref = ACCOUNT_CODE, rung = "local"),
                    // a span this lattice does not have: the caller is gating against a stale one
                    hypothesis("Brno", 400, 404, ref = "er.branch", rung = "local"),
                )

            response.gatedBindingsList.size shouldBe 1
            response.outcomesList.map { it.accepted } shouldContainExactly listOf(true, false, false)
            response.outcomesList[1].reason shouldBe ReGate.Reason.NO_CANDIDATE
            response.outcomesList[2].reason shouldBe ReGate.Reason.NO_SPAN
            // one entry per escalate→gate PAIR (RV-11), carrying everything that was proposed
            response.rungLogEntry.hypothesesCount shouldBe 3
            response.rungLogEntry.bindingsAdded shouldBe 1
        }

        "(T4) gating the same batch twice is the same call twice — stateless, and idempotent" {
            val hypotheses =
                arrayOf(
                    correction("5010O1", 20, 26, to = "501001", ref = ACCOUNT_CODE, rung = "local"),
                    hypothesis("Brno", 400, 404, ref = "er.branch", rung = "local"),
                )
            val lattice = h1primeLattice()
            val first = gate(GateFuzzy(mapOf("501001" to listOf(member("501001", ACCOUNT_CODE)))), lattice, *hypotheses)
            val second =
                gate(GateFuzzy(mapOf("501001" to listOf(member("501001", ACCOUNT_CODE)))), lattice, *hypotheses)

            // Byte-identical. The service holds nothing between calls, so a retry after a dropped
            // connection cannot double-count a binding or advance a round — which is what the
            // Golem loop's retry semantics rest on.
            first shouldBe second
        }

        "MH: a re-gated homonym still ASKS — no parse, no slot, no rule" {
            // Structural, not incidental: `anchorCandidate` synthesises a candidate with no slot
            // because a re-gate has no parse. If a slot ever leaked in here, an LLM rung would be
            // able to move an object decision by choosing when to re-gate — the shape RV-7
            // forbids. Two EXACT declared candidates for one span ⇒ AMBIGUOUS, as before MH.
            // The span has to be one the lattice actually carries (otherwise the answer is
            // NO_SPAN and nothing is being tested); the hypothesis' TERM is what the fake matcher
            // is keyed by, so the homonym rides `5010O1`'s span.
            val fuzzy =
                GateFuzzy(
                    answers =
                        mapOf(
                            "prodejna" to
                                listOf(
                                    declared("er.entity.store"),
                                    declared("er.entity.store_sales"),
                                ),
                        ),
                )
            val response =
                gate(
                    fuzzy,
                    lattice = h1primeLattice(),
                    correction("5010O1", 20, 26, to = "prodejna", ref = "", rung = "local"),
                )

            response.gatedBindingsList shouldBe emptyList()
            response.outcomesList.single().reason shouldBe "AMBIGUOUS"
        }

        "(review) a matcher that could not be ASKED is LOOKUP_FAILED, never an empty vocabulary" {
            // The failure mode this replaces: an unreachable matcher answered every hypothesis with
            // `NO_CANDIDATE`, whose own contract is "the vocabulary has no such term". A rung that
            // believes that stops proposing something that was right, and nothing in the response
            // let it tell the two apart.
            val fuzzy = GateFuzzy(answers = emptyMap(), failLookups = true)
            val response =
                gate(
                    fuzzy,
                    lattice = h1primeLattice(),
                    correction("5010O1", 20, 26, to = "501001", ref = ACCOUNT_CODE, rung = "local"),
                )

            response.gatedBindingsList shouldBe emptyList()
            response.outcomesList.single().reason shouldBe ReGate.Reason.LOOKUP_FAILED
            // the gap it aimed at is exactly where it was, and still typed
            response.updatedGapsList.single().kind shouldBe GapKind.GAP_KIND_G4_METHOD_MISS
        }

        "(review) a large batch cannot open one matcher call per hypothesis — the fan-out is bounded" {
            // `Gate` is a public rpc and `hypotheses` is unbounded on the wire, so the bound has to
            // be this service's rather than the caller's good manners. Every hypothesis is still
            // ASKED — the cap is on how many are in flight at once.
            val fuzzy = GateFuzzy(answers = emptyMap(), trackConcurrency = true)
            val batch =
                (0 until 64)
                    .map { correction("5010O1", 20, 26, to = "50100$it", ref = ACCOUNT_CODE, rung = "local") }
                    .toTypedArray()

            val response = gate(fuzzy, h1primeLattice(), *batch)

            fuzzy.lookups.size shouldBe 64
            response.outcomesList.size shouldBe 64
            fuzzy.peakConcurrency shouldBeLessThanOrEqual LookupRoundConfig.DEFAULT.maxQueriesPerRound
        }

        "(review) a degraded-floor banner survives the re-gate — G5 is carried, not re-derived" {
            // G5 is minted from the RESOLVE's capability assessment (RS-25), which no lattice
            // carries, so re-deriving it here could only ever answer `false`. Since `updated_gaps`
            // is the caller's whole next gap list and not a delta, that silently retired RG-RES-001
            // across one gate call: a question answered on the fold+fuzzy floor came back looking
            // undegraded. The G5 is appended to a real lattice here for exactly the reason the tool
            // has to carry it — nothing in the lattice could have told it apart.
            val lattice =
                h1primeLattice()
                    .toBuilder()
                    .addGaps(
                        GapRecord
                            .newBuilder()
                            .setKind(GapKind.GAP_KIND_G5_NLP_DARK)
                            .setDisposition(Disposition.DISPOSITION_DEGRADED)
                            .setSpan(Span.newBuilder().setStart(0).setEnd(40)),
                    ).build()

            val response =
                gate(
                    GateFuzzy(mapOf("501001" to listOf(member("501001", ACCOUNT_CODE)))),
                    lattice,
                    correction("5010O1", 20, 26, to = "501001", ref = ACCOUNT_CODE, rung = "local"),
                )

            // the G4 the correction closed is gone; the honesty banner is not
            response.updatedGapsList.map { it.kind } shouldContainExactly listOf(GapKind.GAP_KIND_G5_NLP_DARK)
        }

        "⛑ a GROUNDING_TRIGGER answer may bind, but it does NOT attribute — the value's gap stays open" {
            // hartland, 2026-09-16. The rung log for the failing turn reads
            // `round: 1 rung: "lookup" action: "lookup" value_ids: "v1" bindings_added: 1` — this
            // path, on the value *roce* — and what it added was `attribute_ref: "ground:chrono"`.
            // golem's query door then refused the entire turn on a ref it cannot address, while the
            // chrono kernel had grounded the year beside it onto a real column.
            //
            // `GateResponse` carries no updated values, so the observable here is the GAP: an
            // attributed value closes its own gap, and this one must not — a trigger names which
            // KERNEL owns a span, never an attribute the literal could be a value of.
            val response =
                gate(
                    GateFuzzy(mapOf("5010O1" to listOf(trigger("ground:chrono")))),
                    h1primeLattice(),
                    hypothesis("5010O1", 20, 26, ref = "", rung = "local"),
                )

            // The G4 the correction in the test above CLOSED is still open here, because nothing
            // attributed the value — which is the whole difference the filter makes.
            response.updatedGapsList.map { it.kind } shouldContainExactly listOf(GapKind.GAP_KIND_G4_METHOD_MISS)
        }

        "(review) a hypothesis naming a MEMBER is not confirmed by binding the column it lives in" {
            // `bound ⊂ proposed` confirms (a member arrives as `<ref>#<id>`); the REVERSE does not.
            // This hypothesis asked about a value and was answered about a column — and accepting it
            // would have handed the proposer the column as its binding while saying "yes".
            val fuzzy = GateFuzzy(answers = mapOf("5010O1" to listOf(declared(ACCOUNT_CODE))))
            val response =
                gate(
                    fuzzy,
                    lattice = h1primeLattice(),
                    hypothesis("5010O1", 20, 26, ref = "$ACCOUNT_CODE#501001", rung = "capable"),
                )

            response.gatedBindingsList shouldBe emptyList()
            response.outcomesList.single().reason shouldBe ReGate.Reason.REF_MISMATCH
        }

        "(review) the rung-log entry names a round, and never claims round 0" {
            // Round 0 is reserved for the core's own deterministic pass, so an unset field was a
            // gate entry impersonating one. Derived from the caller's own log, so the response
            // stays a pure function of the request — the T4 idempotency case below still passes.
            val lattice = h1primeLattice()
            val response =
                gate(
                    GateFuzzy(mapOf("501001" to listOf(member("501001", ACCOUNT_CODE)))),
                    lattice,
                    correction("5010O1", 20, 26, to = "501001", ref = ACCOUNT_CODE, rung = "local"),
                )

            response.rungLogEntry.round shouldBe (lattice.rungLogList.maxOfOrNull { it.round } ?: 0) + 1
            response.rungLogEntry.round shouldNotBe 0
            // what it touched: the h1′ value span the correction aimed at
            response.rungLogEntry.valueIdsCount shouldBe 1
            // and NOT the clock — `elapsed_ms` stays unset so the response is byte-reproducible
            response.rungLogEntry.elapsedMs shouldBe 0L
        }

        "the caller's lattice is never mutated — `updated_gaps` describes a world it can choose to adopt" {
            val lattice = h1primeLattice()
            val before = lattice.toByteArray().toList()
            gate(
                GateFuzzy(mapOf("501001" to listOf(member("501001", ACCOUNT_CODE)))),
                lattice,
                correction("5010O1", 20, 26, to = "501001", ref = ACCOUNT_CODE, rung = "local"),
            )
            lattice.toByteArray().toList() shouldBe before
        }
    }) {
    private companion object {
        private const val ACCOUNT_CODE = "md.dimension.Account.code"
        private val json = Json { ignoreUnknownKeys = true }
        private val parser: JsonFormat.Parser = JsonFormat.parser().ignoringUnknownFields()

        private fun gate(
            fuzzy: FuzzyClient,
            lattice: ResolutionState,
            vararg hypotheses: Hypothesis,
        ): GateResponse =
            runBlocking {
                pipelineFor(fuzzy, "h1-cs").gate(
                    GateRequest
                        .newBuilder()
                        .setLattice(lattice)
                        .addAllHypotheses(hypotheses.toList())
                        .build(),
                )
            }

        /** A real lattice, produced by the real pipeline from the fixture. */
        private fun h1primeLattice(): ResolutionState = resolveFixture("h1prime-cs")

        private fun h2Lattice(): ResolutionState = resolveFixture("h2-cs")

        private fun resolveFixture(id: String): ResolutionState {
            val case = loadJson("/lattice/$id.case.json")
            val fuzzy = FixtureFuzzy(case)
            return runBlocking {
                pipelineFor(fuzzy, id).resolve(
                    ResolveRequest
                        .newBuilder()
                        .setConversationId(id)
                        .setFresh(
                            FreshQuestion
                                .newBuilder()
                                .setText(case["text"]!!.jsonPrimitive.content)
                                .setLocale(case["locale"]!!.jsonPrimitive.content),
                        ).setRegistry(merge(case["registry"]!!.jsonObject, Registry.newBuilder()).build())
                        .build(),
                )
            }.resolutionState
        }

        /**
         * The gate reads its vocabulary from the SNAPSHOT registry, not from a per-request override
         * (see `ResolverPipeline.gate`), so the account entity is declared here — this is the seam
         * the ⚑ on that method is about.
         */
        private fun pipelineFor(
            fuzzy: FuzzyClient,
            fixture: String,
        ): ResolverPipeline =
            ResolverPipeline(
                FakeNlp(
                    merge(
                        loadJson(
                            "/lattice/${loadJson("/lattice/$fixture.case.json")["parseFile"]!!.jsonPrimitive.content}",
                        ),
                        AnalyzeResponse.newBuilder(),
                    ).build(),
                ),
                fuzzy,
                SnapshotRegistry(
                    StubRegistrySource(
                        DeclaredVocabulary(
                            entries =
                                listOf(
                                    DeclaredVocabularyEntry(
                                        category = ACCOUNT_CODE,
                                        targetRef = "md.dimension.Account",
                                        values = emptyList(),
                                    ),
                                ),
                        ),
                        "snap-gate",
                    ),
                    ResolverThresholds.LIVE,
                ),
                emptyMap(),
                ResumeTokenCodec(mapOf("k1" to ByteArray(32) { it.toByte() }), activeKeyId = "k1"),
                lookupRounds = LookupRounds(fuzzy, LookupRoundConfig.DISABLED),
            )

        private fun hypothesis(
            text: String,
            start: Int,
            end: Int,
            ref: String,
            rung: String,
        ): Hypothesis =
            Hypothesis
                .newBuilder()
                .setSpan(
                    Span
                        .newBuilder()
                        .setStart(start)
                        .setEnd(end)
                        .setText(text),
                ).setRef(ref)
                .setProposingRung(rung)
                .build()

        private fun correction(
            text: String,
            start: Int,
            end: Int,
            to: String,
            ref: String,
            rung: String,
        ): Hypothesis = hypothesis(text, start, end, ref, rung).toBuilder().setCorrection(to).build()

        /**
         * A DECLARED row that POSITIVELY declares the grounding-trigger class — what the `ground:`
         * slice of the compiled lexicon returns, quoted off hartland's live wire
         * (`candidate_id: "lex:ground:chrono:cs:roce" … target_class: TARGET_CLASS_GROUNDING_TRIGGER`).
         */
        private fun trigger(targetRef: String): FuzzyMatch =
            FuzzyMatch
                .newBuilder()
                .setCandidateId("lex:$targetRef")
                .setCandidate(targetRef)
                .setScore(1.0)
                .setCategory(targetRef)
                .setSource(SourceTag.DECLARED)
                .setTargetRef(targetRef)
                .setTargetClass(TargetClass.TARGET_CLASS_GROUNDING_TRIGGER)
                .setMatchMethod("EXACT")
                .build()

        private fun member(
            id: String,
            category: String,
            score: Double = 1.0,
        ): FuzzyMatch =
            FuzzyMatch
                .newBuilder()
                .setCandidateId(id)
                .setCandidate(id)
                .setScore(score)
                .setCategory(category)
                .setSource(SourceTag.MEMBER)
                .build()

        private fun declared(targetRef: String): FuzzyMatch =
            FuzzyMatch
                .newBuilder()
                .setCandidateId("lex:$targetRef")
                .setCandidate(targetRef)
                .setScore(1.0)
                .setCategory(targetRef)
                .setSource(SourceTag.DECLARED)
                .setTargetRef(targetRef)
                .setMatchMethod("EXACT")
                .build()

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
                    ReGateTest::class.java.getResource(resource)?.readText()
                        ?: error("missing test resource $resource"),
                ).jsonObject
    }

    private class FakeNlp(
        private val parse: AnalyzeResponse,
    ) : NlpClient {
        override suspend fun analyze(request: AnalyzeRequest): AnalyzeResponse = parse

        override suspend fun getStatus(): StatusResponse =
            StatusResponse
                .newBuilder()
                .setReady(true)
                .addCapabilities(
                    Capability
                        .newBuilder()
                        .setOp(NlpOp.NER)
                        .setLanguage("cs")
                        .setEngine("nametag3"),
                ).addCapabilities(
                    Capability
                        .newBuilder()
                        .setOp(NlpOp.DEP_PARSE)
                        .setLanguage("cs")
                        .setEngine("stanza"),
                ).build()
    }

    /** Replays a lattice fixture's vocabulary, so the gated lattices are the real ones. */
    private class FixtureFuzzy(
        case: JsonObject,
    ) : FuzzyClient {
        private val byQuery: Map<String, List<FuzzyMatch>> =
            case["matcher"]!!
                .jsonObject["byQuery"]!!
                .jsonObject
                .mapValues { (_, matches) ->
                    matches.jsonArray.map { merge(it.jsonObject, FuzzyMatch.newBuilder()).build() }
                }

        override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse {
            val builder = BatchMatchResponse.newBuilder()
            for (span in request.spansList) {
                val scoped = span.categoriesList.toSet()
                builder.addResults(
                    FuzzyMatchResponse.newBuilder().addAllMatches(
                        byQuery[span.query].orEmpty().filter { scoped.isEmpty() || it.category in scoped },
                    ),
                )
            }
            return builder.build()
        }

        override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()
    }

    /** Answers `Lookup` by term and records every request — the gate's only upstream. */
    private class GateFuzzy(
        private val answers: Map<String, List<FuzzyMatch>>,
        /** The matcher is unreachable: every question throws, as a live one would. */
        private val failLookups: Boolean = false,
        /** Suspend inside each call so overlap is observable — otherwise the cap check is vacuous. */
        private val trackConcurrency: Boolean = false,
    ) : FuzzyClient {
        val lookups: MutableList<LookupRequest> = mutableListOf()

        /** The most calls ever in flight at once. Single-threaded `runBlocking`, so no lock needed. */
        var peakConcurrency: Int = 0
            private set

        private var inFlight = 0

        override suspend fun batchMatch(request: BatchMatchRequest): BatchMatchResponse =
            BatchMatchResponse.getDefaultInstance()

        override suspend fun lookup(request: LookupRequest): LookupResponse {
            lookups += request
            inFlight++
            peakConcurrency = maxOf(peakConcurrency, inFlight)
            try {
                if (trackConcurrency) delay(1)
                if (failLookups) throw StatusException(Status.UNAVAILABLE.withDescription("lex-matcher is down"))
                return LookupResponse.newBuilder().addAllCandidates(answers[request.term].orEmpty()).build()
            } finally {
                inFlight--
            }
        }

        override suspend fun getStatus(): FuzzyStatusResponse = FuzzyStatusResponse.getDefaultInstance()
    }
}
