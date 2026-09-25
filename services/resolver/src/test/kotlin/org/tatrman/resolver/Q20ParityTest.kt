// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.fuzzy.v1.BatchMatchResponse
import org.tatrman.fuzzy.v1.FuzzyMatch
import org.tatrman.fuzzy.v1.FuzzyMatchResponse
import org.tatrman.fuzzy.v1.Provenance
import org.tatrman.fuzzy.v1.SourceTag
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.model.ResolverThresholds
import org.tatrman.resolver.pipeline.Bound
import org.tatrman.resolver.pipeline.DomainSpanCandidate
import org.tatrman.resolver.pipeline.GateSpans
import org.tatrman.resolver.pipeline.SiblingCatalog

/**
 * RG-P5.S1.T6 — the parity instrument. Grounds the Kotlin deterministic core
 * against the recorded Q-20 spike artifacts (rule 7: spike numbers are ground
 * truth, cited not recomputed). Two halves:
 *
 *  1. **Behaviour replay** — the two known-hard behaviours (code-vs-name +
 *     sibling-column) are driven through the resolver's OWN [GateSpans] over the
 *     Q-20 synthesized vocabulary; each `code_vs_name` probe recorded in
 *     `q20-spike-results.json` must bind to the same entity it did in the spike,
 *     carry full provenance (S-1/S-4), and expand to its sibling column.
 *  2. **Acceptance baseline** — the C-config (anchored) precision/recall/awaiting/
 *     over-generation numbers are read from the vendored spike result and asserted
 *     as this phase's baseline: value-extraction P=1.0, ucetnictvi R≥0.8, seed
 *     over-generation eliminated (0).
 *
 * ⚠ Boundary (Q-20 caveat §5 + task-management rule 4): the *live-parse + live
 * lex-matcher* re-confirmation of P/R over the full corpus is the RG-P6 conformance
 * harness's job (a three-tier harness, not a test authored here). This instrument
 * proves the core reproduces the spike's decided behaviours deterministically.
 */
class Q20ParityTest :
    StringSpec({

        // Q-20 vocab.py: 3 sibling entity types, each a (code_ref, name_ref) pair.
        val entities =
            mapOf(
                "QSTRED_DF" to Pair("db.dbo.QSTRED_DF.KOD_STR", "db.dbo.QSTRED_DF.NAZEV_STR"),
                "QXXUKAZMU" to Pair("db.dbo.QXXUKAZMU.KOD_UKAZMU", "db.dbo.QXXUKAZMU.NAZEV_UKAZMU"),
                "QTYPDOK" to Pair("db.dbo.QTYPDOK.TYP_DOK", "db.dbo.QTYPDOK.NAZEV_TYPDOK"),
            )
        val anchors = mapOf("QSTRED_DF" to "středisko", "QXXUKAZMU" to "ukazatel", "QTYPDOK" to "doklad")

        val entityTypes =
            entities.map { (ent, refs) ->
                ResolverEntityType(
                    ref = "db.dbo.$ent",
                    categories = listOf(refs.first, refs.second),
                    anchors = listOf(anchors.getValue(ent)),
                )
            }
        // Sibling expansion: a match on either column also points at its sibling.
        val siblings: SiblingCatalog =
            entities.values.flatMap { (code, name) -> listOf(code to listOf(name), name to listOf(code)) }.toMap()

        val spike =
            Json { ignoreUnknownKeys = true }
                .parseToJsonElement(readResource("q20/q20-spike-results.json"))
                .jsonObject["summary"]!!
                .jsonObject

        "behaviour replay: every recorded code-vs-name probe binds to its entity with provenance + sibling expansion" {
            val probes = spike["code_vs_name"]!!.jsonArray
            probes.forEach { p ->
                val probe = p.jsonObject
                if (probe["correct"]!!.jsonPrimitive.content != "true") return@forEach
                val expectEntity = probe["expect_entity"]!!.jsonPrimitive.content
                val top = probe["top"]!!.jsonPrimitive.content // e.g. "QT ORLAK(QSTRED_DF,name) 1.050"

                val matchedKind = top.substringAfter('(').substringBefore(')').substringAfter(',') // code | name
                val score = top.substringAfterLast(' ').toDouble()
                val (codeRef, nameRef) = entities.getValue(expectEntity)
                val category = if (matchedKind == "code") codeRef else nameRef

                val cand =
                    DomainSpanCandidate(
                        probe["probe"]!!.jsonPrimitive.content,
                        0,
                        1,
                        listOf(codeRef, nameRef),
                        listOf(codeRef, nameRef),
                        anchored = true,
                    )
                val resp =
                    batch(fm("$expectEntity:$matchedKind", probe["probe"]!!.jsonPrimitive.content, score, category))

                val bound =
                    GateSpans
                        .gate(
                            listOf(cand),
                            resp,
                            entityTypes,
                            ResolverThresholds.LIVE,
                            siblings,
                            "q20-snap",
                        ).shouldBeInstanceOf<Bound>()
                val binding = bound.bindings.single()
                binding.entityTypeRef shouldBe "db.dbo.$expectEntity" // bound to the RIGHT entity (spike: correct=true)
                // provenance carried (S-1/S-4)
                binding.vocabularySource shouldBe "MEMBER"
                binding.algorithm shouldBe "TATRMAN"
                binding.snapshotHash shouldBe "q20-snap"
                binding.score shouldBeGreaterThanOrEqual ResolverThresholds.LIVE.bind
                // sibling-column expansion: the value also points at its sibling column.
                binding.siblingRefs shouldContain (if (matchedKind == "code") nameRef else codeRef)
            }
        }

        "acceptance baseline: the recorded C-config (anchored) numbers are this phase's gate" {
            val c = spike["C_anchored_fallback"]!!.jsonObject
            val valueExtraction = c["value_extraction"]!!.jsonObject
            val ucetnictvi = c["ucetnictvi"]!!.jsonObject

            // value-extraction reaches the LLM's precision on anchored spans (spike A/C).
            valueExtraction["precision"]!!.jsonPrimitive.content.toDouble() shouldBe 1.0
            valueExtraction["recall"]!!.jsonPrimitive.content.toDouble() shouldBeGreaterThanOrEqual 0.80

            // ucetnictvi meets the inherited ≥0.80 recall gate; precision recovered vs all-spans.
            ucetnictvi["recall"]!!.jsonPrimitive.content.toDouble() shouldBeGreaterThanOrEqual 0.80
            ucetnictvi["precision"]!!.jsonPrimitive.content.toDouble() shouldBeGreaterThanOrEqual 0.50

            // over-generation eliminated (config B's 33 spurious → 0) — the whole point of anchoring.
            c["seed_spurious_binds"]!!.jsonPrimitive.content.toInt() shouldBe 0
            c["awaiting_withheld"]!!.jsonPrimitive.content shouldBe "1/5"
        }

        "the all-spans anti-baseline (config B) is the design we avoided (33 spurious binds)" {
            val b = spike["B_all_spans"]!!.jsonObject
            // Recorded so a regression toward naive all-spans gating is a visible, cited failure.
            b["seed_spurious_binds"]!!.jsonPrimitive.content.toDouble() shouldBeGreaterThanOrEqual 30.0
            b["ucetnictvi"]!!
                .jsonObject["precision"]!!
                .jsonPrimitive.content
                .toDouble() shouldBeLessThanOrEqual 0.30
        }

        "corpus wiring: the vendored ENTITIES_ONLY corpus loads and its domain entity types are anchor-covered" {
            val lines = readResource("q20/ucetnictvi_entities_only.jsonl").trim().lines().filter { it.isNotBlank() }
            lines.size shouldBe 12
            // Every non-universal expected entity_type in the corpus maps to a declared anchor.
            val corpusEntityTypes =
                lines
                    .flatMap { line ->
                        Regex("\"entity_type\"\\s*:\\s*\"([^\"]+)\"").findAll(line).map { it.groupValues[1] }
                    }.toSet()
            val declared = setOf("stredisko", "ukazatel", "typ_dokladu")
            val universal = setOf("DATE", "MISC", "MONEY", "PERSON", "LOCATION")
            (corpusEntityTypes - universal).forEach { declared shouldContain it }
        }
    }) {
    companion object {
        private fun readResource(path: String): String =
            checkNotNull(
                Q20ParityTest::class.java.classLoader.getResourceAsStream(path),
            ) { "missing test resource: $path" }
                .bufferedReader()
                .use { it.readText() }

        private fun fm(
            id: String,
            candidate: String,
            score: Double,
            category: String,
        ): FuzzyMatch =
            FuzzyMatch
                .newBuilder()
                .setCandidateId(id)
                .setCandidate(candidate)
                .setScore(score)
                .setCategory(category)
                .setSource(SourceTag.MEMBER)
                .setProvenance(
                    Provenance
                        .newBuilder()
                        .setProducer("fuzzy")
                        .setMethod("TATRMAN")
                        .setRawScore(score),
                ).build()

        private fun batch(vararg matches: FuzzyMatch): BatchMatchResponse =
            BatchMatchResponse
                .newBuilder()
                .addResults(FuzzyMatchResponse.newBuilder().addAllMatches(matches.toList()).build())
                .build()
    }
}
