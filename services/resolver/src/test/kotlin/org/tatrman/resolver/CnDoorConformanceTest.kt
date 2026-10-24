// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.resolver.mcp.ConceptDoor
import org.tatrman.resolver.mcp.DEFINITION_RENDER_TOOL_NAME

/**
 * CN-P5 S5.1 T1 — the cn doors' `calls:` seeds (`resources/conformance/calls/cn-*.json`, the cn section of SCHEMA.md),
 * driven through the door over the REAL ttr-concept: one happy call and one refusal-over-guess per family, and the
 * determinism promise — every turn is called twice and the two answers must be byte-equal.
 *
 * Like the conversation tier's runner, this one **rejects a fixture that states a key it does not assert**: a seed that
 * says more than the runner checks is worse than a smaller one, because the next reader believes it.
 */
class CnDoorConformanceTest :
    StringSpec({
        val seeds =
            listOf(
                "cn-statement-validate.json",
                "cn-statement-lower.json",
                "cn-statement-explain.json",
                "cn-assertion-validate.json",
                "cn-assertion-lower.json",
                "cn-assertion-explain.json",
                "cn-concept-search.json",
                "cn-definition-render.json",
            )
        val outcomes = setOf("result", "refused", "error")
        val estates = setOf("cn-estate", "sales", "clinic")
        val assertedKeys =
            setOf(
                "outcome",
                "refusal_over_guess",
                "deterministic",
                "slugs",
                "error_code",
                "dot_path",
                "rows",
                "text",
                "text_contains",
                "parameters_read",
                "concepts",
                "concepts_only",
                "sources",
            )
        val json = Json { ignoreUnknownKeys = false }

        fun load(name: String): JsonObject =
            json
                .parseToJsonElement(
                    requireNotNull(javaClass.getResourceAsStream("/conformance/calls/$name")) { "missing $name" }
                        .bufferedReader()
                        .use { it.readText() },
                ).jsonObject

        val doors = HashMap<String, ConceptDoor>()

        fun doorFor(estate: String): ConceptDoor =
            doors.getOrPut(estate) { if (estate == "cn-estate") CnDoors.door() else CnDoors.world(estate) }

        fun CallToolResult.text(): String = (content.single() as TextContent).text.orEmpty()

        fun JsonObject.strings(key: String): List<String> = this[key]!!.jsonArray.map { it.jsonPrimitive.content }

        "every cn seed is well-formed, and states only what the runner asserts" {
            for (name in seeds) {
                val seed = load(name)
                withClue(name) {
                    seed["id"]!!.jsonPrimitive.content shouldBe name.removeSuffix(".json")
                    seed["surface"]!!.jsonPrimitive.content shouldBe "cn"
                    estates shouldContain seed["estate"]!!.jsonPrimitive.content
                    for (turn in seed["turns"]!!.jsonArray.map { it.jsonObject }) {
                        ConceptDoor.TOOL_NAMES shouldContain turn["tool"]!!.jsonPrimitive.content
                        val expect = turn["expect"]!!.jsonObject
                        (expect.keys - assertedKeys).shouldBeEmpty()
                        outcomes shouldContain expect["outcome"]!!.jsonPrimitive.content
                        // Both invariants are stated on every turn, so a second shell inherits the words.
                        expect["refusal_over_guess"]!!.jsonPrimitive.boolean shouldBe true
                        expect["deterministic"]!!.jsonPrimitive.boolean shouldBe true
                    }
                }
            }
        }

        "the seeds cover all eight tools, with a refusal for every frame family" {
            val turns = seeds.flatMap { load(it)["turns"]!!.jsonArray.map { t -> t.jsonObject } }
            turns.map { it["tool"]!!.jsonPrimitive.content }.toSet() shouldBe ConceptDoor.TOOL_NAMES.toSet()
            val refused =
                turns
                    .filter { it["expect"]!!.jsonObject["outcome"]!!.jsonPrimitive.content == "refused" }
                    .map { it["tool"]!!.jsonPrimitive.content.substringBefore('.') }
                    .toSet()
            refused shouldContainAll setOf("statement", "assertion", DEFINITION_RENDER_TOOL_NAME.substringBefore('.'))
        }

        for (name in seeds) {
            "seed $name" {
                val seed = load(name)
                val door = doorFor(seed["estate"]!!.jsonPrimitive.content)
                for ((i, turn) in seed["turns"]!!.jsonArray.map { it.jsonObject }.withIndex()) {
                    val tool = turn["tool"]!!.jsonPrimitive.content
                    val args = turn["args"]!!.jsonObject
                    val expect = turn["expect"]!!.jsonObject
                    withClue("$name turn $i ($tool)") {
                        val first = door.call(tool, args)
                        val second = door.call(tool, args)
                        // The determinism promise (cn contracts §4: identical to the resolve door's).
                        second.structuredContent shouldBe first.structuredContent
                        second.text() shouldBe first.text()

                        val out = first.structuredContent.shouldNotBeNull()
                        val errors =
                            (out["diagnostics"] as? JsonArray)
                                .orEmpty()
                                .map { it.jsonObject }
                                .filter { it["severity"]!!.jsonPrimitive.content == "ERROR" }
                                .map { it["slug"]!!.jsonPrimitive.content }
                        when (expect["outcome"]!!.jsonPrimitive.content) {
                            "error" -> {
                                first.isError shouldBe true
                                out["errorCode"]!!.jsonPrimitive.content shouldBe
                                    expect["error_code"]!!.jsonPrimitive.content
                            }
                            "refused" -> {
                                first.isError shouldBe false
                                out["refused"]!!.jsonPrimitive.boolean shouldBe true
                                errors shouldContainAll expect.strings("slugs")
                                // Refusal over guess: a refused frame yields nothing a consumer could run.
                                out["plan"] shouldBe null
                                (out["rows"] as? JsonArray).orEmpty().shouldBeEmpty()
                                (out["text"] as? JsonPrimitive)?.content.orEmpty() shouldBe ""
                            }
                            "result" -> {
                                first.isError shouldBe false
                                ((out["refused"] as? JsonPrimitive)?.boolean ?: false) shouldBe false
                                if (!tool.endsWith(".explain:v1")) errors.shouldBeEmpty()
                            }
                        }
                        expect["dot_path"]?.let {
                            out["dotPath"]!!.jsonPrimitive.content shouldBe
                                it.jsonPrimitive.content
                        }
                        expect["text"]?.let { out["text"]!!.jsonPrimitive.content shouldBe it.jsonPrimitive.content }
                        expect["text_contains"]?.let { want ->
                            want.jsonArray.forEach {
                                out["text"]!!.jsonPrimitive.content shouldContain
                                    it.jsonPrimitive.content
                            }
                        }
                        expect["rows"]?.let { out["rows"] shouldBe it }
                        expect["parameters_read"]?.let { want ->
                            out["parametersRead"]!!.jsonArray.map {
                                it.jsonObject["name"]!!.jsonPrimitive.content
                            } shouldBe
                                want.jsonArray.map { it.jsonPrimitive.content }
                        }
                        val candidates = (out["candidates"] as? JsonArray).orEmpty().map { it.jsonObject }
                        expect["concepts"]?.let { want ->
                            candidates.map { it["qname"]!!.jsonPrimitive.content } shouldBe
                                want.jsonArray.map { it.jsonPrimitive.content }
                        }
                        expect["concepts_only"]?.let {
                            // A concept, by its ref and its stereotype; a member or an element never appears.
                            for (c in candidates) {
                                c["qname"]!!.jsonPrimitive.content.startsWith("cn.") shouldBe true
                                c["stereotype"]!!.jsonPrimitive.content.isNotBlank() shouldBe true
                            }
                        }
                        expect["sources"]?.let { want ->
                            for ((qname, sources) in want.jsonObject) {
                                candidates.single { it["qname"]!!.jsonPrimitive.content == qname }.strings(
                                    "sources",
                                ) shouldBe
                                    sources.jsonArray.map { it.jsonPrimitive.content }
                            }
                        }
                    }
                }
            }
        }
    })
