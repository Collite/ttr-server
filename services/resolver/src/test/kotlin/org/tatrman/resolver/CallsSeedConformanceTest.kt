// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * RG-P6.S2.T2 — the `calls:` core-tier seeds. All fixtures under
 * resources/conformance/calls are well-formed per SCHEMA.md; the drivable seeds run
 * against the REAL pipeline at the door seam ([ConformancePipeline]). `seed_only`
 * fixtures (hero E2E, geo-dark) are shape-validated but not driven — their live run
 * needs nlp/fuzzy/grounding (SV-P4).
 */
class CallsSeedConformanceTest :
    StringSpec({

        val fixtures =
            listOf(
                "refusal-ambiguous-member.json",
                "refusal-below-threshold.json",
                "hero-e2e.json",
                "clarification-roundtrip.json",
                "geo-dark-degrade.json",
                "gate-h1prime-correction.json",
                "verbatim-literal.json",
            )
        val validOutcomes = setOf("clarification", "resolution", "empty", "error")
        val validTools = setOf("resolve.bind:v1", "resolve.gate:v1")
        val json = Json { ignoreUnknownKeys = true }

        fun load(name: String): JsonObject =
            json
                .parseToJsonElement(
                    requireNotNull(javaClass.getResourceAsStream("/conformance/calls/$name")) { "missing $name" }
                        .bufferedReader()
                        .use { it.readText() },
                ).jsonObject

        "every calls: seed is well-formed per SCHEMA.md" {
            for (name in fixtures) {
                val fixture = load(name)
                fixture["id"]!!.jsonPrimitive.content.isNotBlank() shouldBe true
                val turns = fixture["turns"]!!.jsonArray
                turns.isEmpty() shouldBe false
                for (turnEl in turns) {
                    val turn = turnEl.jsonObject
                    val tool = turn["tool"]!!.jsonPrimitive.content
                    validTools shouldContain tool
                    // RV-P2.4 — a `resolve.gate:v1` turn carries `hypotheses` instead of `args`:
                    // it gates a lattice the PRIOR turn produced, so it has no question of its own
                    // and no conversation to name. Everything else about a turn is unchanged, which
                    // is the point of adding a tool rather than a second fixture format.
                    if (tool == "resolve.gate:v1") {
                        turn["hypotheses"]!!.jsonArray.isEmpty() shouldBe false
                    } else {
                        turn["args"]!!
                            .jsonObject["conversation_id"]!!
                            .jsonPrimitive.content
                            .isNotBlank() shouldBe true
                    }
                    val expect = turn["expect"]!!.jsonObject
                    validOutcomes shouldContain expect["outcome"]!!.jsonPrimitive.content
                    // The refusal-over-guess invariant is asserted by every seed.
                    expect["no_binding_below_threshold"]!!.jsonPrimitive.content shouldBe "true"
                }
            }
        }

        "verbatim-literal: the door takes a quoted string as typed and proposes nothing for it" {
            // LP contracts §2, driven at the door seam over the REAL pipeline — the assertion that
            // could not be made anywhere else is that the whole chain agrees: span proposal does
            // not reach into the quotes, the lattice carries the literal as its own kind of value,
            // and the door surfaces it without a binding.
            val turn = load("verbatim-literal.json")["turns"]!!.jsonArray.single().jsonObject
            val expect = turn["expect"]!!.jsonObject
            val handler = ConformancePipeline.doorHandler(turn["scenario"]!!.jsonPrimitive.content)

            val result = runBlocking { handler.handle(turn["args"]!!.jsonObject, null, null) }
            val structured = result.structuredContent.shouldNotBeNull()
            val state = structured["resolutionState"]!!.jsonObject

            // Nothing bound — and nothing was guessed for the span the user explained.
            structured["resolution"]!!.jsonObject["bindings"] shouldBe null

            val expectedVerbatim = expect["verbatim"]!!.jsonArray.single().jsonObject
            val values = state["values"]!!.jsonArray.map { it.jsonObject }
            val verbatim = values.single { it["kind"]?.jsonPrimitive?.content == "VALUE_KIND_VERBATIM" }
            verbatim["verbatimText"]!!.jsonPrimitive.content shouldBe
                expectedVerbatim["text"]!!.jsonPrimitive.content
            val span = verbatim["span"]!!.jsonObject
            val expectedSpan = expectedVerbatim["span"]!!.jsonObject
            span["start"]!!.jsonPrimitive.int shouldBe expectedSpan["start"]!!.jsonPrimitive.int
            span["end"]!!.jsonPrimitive.int shouldBe expectedSpan["end"]!!.jsonPrimitive.int
            // Unattributed on this estate, and no longer for want of a channel: these fixtures
            // drive the door with a STUB registry and no archive on disk, so nothing here states
            // which column carries a store's name. The lattice says G3 rather than picking one —
            // which is the assertion worth having either way (see `conformance/calls/SCHEMA.md`).
            (verbatim["attributions"]?.jsonArray?.size ?: 0) shouldBe
                expectedVerbatim["attributions"]!!.jsonPrimitive.int
            state["gaps"]!!
                .jsonArray
                .map { it.jsonObject }
                .single { it["valueId"]?.jsonPrimitive?.content == verbatim["id"]!!.jsonPrimitive.content }["kind"]!!
                .jsonPrimitive.content shouldBe expectedVerbatim["gap_kind"]!!.jsonPrimitive.content

            // `no_span_inside_literal`: the n-gram floor is the loosest source in the pipeline and
            // it would happily have proposed `Valmy`, `Oil` and `Valmy Oil`. None of them exists.
            expect["no_span_inside_literal"]!!.jsonPrimitive.content shouldBe "true"
            val literalStart = expectedSpan["start"]!!.jsonPrimitive.int
            val literalEnd = expectedSpan["end"]!!.jsonPrimitive.int
            val others =
                (state["mentions"]?.jsonArray.orEmpty() + values.filter { it !== verbatim })
                    .map { it.jsonObject["span"]!!.jsonObject }
            others.none {
                (it["start"]?.jsonPrimitive?.int ?: 0) < literalEnd &&
                    (it["end"]?.jsonPrimitive?.int ?: 0) > literalStart
            } shouldBe true
        }

        "clarification round-trip: the real signed token carries forward and resumes to a pin binding" {
            val fixture = load("clarification-roundtrip.json")
            val turns = fixture["turns"]!!.jsonArray
            // ONE codec across both turns so turn 1 verifies turn 0's real HMAC token.
            val codec = ConformancePipeline.codec()
            var carriedToken: String? = null

            for (turnEl in turns) {
                val turn = turnEl.jsonObject
                val scenario = turn["scenario"]!!.jsonPrimitive.content
                // Substitute ${turn0.resumeToken} with the token the prior turn emitted.
                val args = substituteToken(turn["args"]!!.jsonObject, carriedToken)

                val handler = ConformancePipeline.doorHandler(scenario, codec)
                val result = runBlocking { handler.handle(args, null, null) }
                val structured = result.structuredContent.shouldNotBeNull()

                when (turn["expect"]!!.jsonObject["outcome"]!!.jsonPrimitive.content) {
                    "clarification" -> {
                        val awaiting = structured["awaiting"]!!.jsonObject
                        awaiting["options"]!!.jsonArray.size shouldBe 2
                        carriedToken = awaiting["resumeToken"]!!.jsonPrimitive.content
                    }
                    "resolution" -> {
                        val resolution = structured["resolution"]!!.jsonObject
                        resolution["rationale"]!!.jsonPrimitive.content shouldBe "resumed via signed pin (M:qt-orlak)"
                        // The MEMBER pin reconstructs its Domain — resolved_id AND the
                        // entity_type_ref that review F restored (was empty before).
                        val domain =
                            resolution["bindings"]!!
                                .jsonArray
                                .single()
                                .jsonObject["domain"]!!
                                .jsonObject
                        domain["resolvedId"]!!.jsonPrimitive.content shouldBe "qt-orlak"
                        domain["entityTypeRef"]!!.jsonPrimitive.content shouldBe "er.qstred_df"
                    }
                }
            }
            // The token was actually a real signed HMAC (not a stub literal), long + dotted.
            carriedToken.shouldNotBeNull()
            (carriedToken!!.contains('.') && carriedToken!!.length > 40) shouldBe true
        }
    })

/** Replace a `${turn0.resumeToken}` placeholder in `resume_token` with [token]. */
private fun substituteToken(
    args: JsonObject,
    token: String?,
): JsonObject =
    buildJsonObject {
        for ((key, value) in args) {
            val raw = (value as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            if (key == "resume_token" && raw == "\${turn0.resumeToken}") {
                put(key, requireNotNull(token) { "no prior-turn token to substitute" })
            } else {
                put(key, value)
            }
        }
    }
