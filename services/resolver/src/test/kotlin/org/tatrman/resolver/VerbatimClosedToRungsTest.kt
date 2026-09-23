// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.tatrman.resolver.model.ResolverEntityType
import org.tatrman.resolver.pipeline.LatticeAssembler
import org.tatrman.resolver.pipeline.LookupRoundConfig
import org.tatrman.resolver.pipeline.ReGate
import org.tatrman.resolver.pipeline.RoundPlanner
import org.tatrman.resolver.v1.GapKind
import org.tatrman.resolver.v1.GateRequest
import org.tatrman.resolver.v1.Hypothesis
import org.tatrman.resolver.v1.ResolutionState
import org.tatrman.resolver.v1.Span
import org.tatrman.resolver.v1.ValueKind

/**
 * LP-P1·S3 — contracts §2.4: **a quoted span is closed to every rung above the core.**
 *
 * The lattice is deliberately honest about literals — it carries the value, its text and, where
 * the question said so, its column — and an LLM rung reading that lattice will have an opinion
 * about `"Pelex"` anyway. It is entitled to: proposing is its job. What it may not do is win, and
 * the three places that decide are the ones tested here — the planner never schedules a lookup for
 * one, the re-gate refuses a hypothesis that touches one, and the rung log says which values are
 * closed BEFORE anyone proposes, so the refusal is not a surprise.
 *
 * All three run against the lattice the real pipeline produced for the hero question.
 */
class VerbatimClosedToRungsTest :
    StringSpec({

        val config = LookupRoundConfig()

        /** The hero lattice: `"Pelex"` attributed to the store's name column. */
        fun attributed(): ResolutionState = VerbatimHero.resolve().second.resolutionState

        /** The same question on an estate that declared no name column: the literal is a G3. */
        fun headless(): ResolutionState =
            VerbatimHero
                .resolve(
                    VerbatimHero.registryOf(
                        VerbatimHero.STORE
                            .toBuilder()
                            .clearNameAttributeRef()
                            .build(),
                    ),
                ).second.resolutionState

        val storeType =
            ResolverEntityType(
                ref = "er.entity.store",
                categories = listOf("er.entity.store"),
                anchors = listOf("dodací místo"),
            )

        "round 0 names the literals, so a rung knows what is closed before it proposes" {
            val log = attributed().rungLogList.single { it.action == LatticeAssembler.VERBATIM_ACTION }

            log.round shouldBe 0
            log.rung shouldBe LatticeAssembler.CORE_RUNG
            // The count IS the `verbatim: n literal(s)` of contracts §2.4, carried as the value
            // ids rather than as a formatted string: `RungLogEntry` has no free-text field, and
            // ids are what a rung would have to look up anyway.
            log.valueIdsList shouldContainExactly listOf("v1")
        }

        "a question without quotes gets no verbatim entry at all" {
            // The common lattice stays byte-identical to what RV-P2.1 emitted. An entry saying
            // "zero literals" would be noise on every question this estate ever answers.
            val plain =
                VerbatimHero
                    .resolve(
                        questionText = "Ukaž dodací místa",
                        parse =
                            org.tatrman.nlp.v1.AnalyzeResponse
                                .newBuilder()
                                .setLanguage("cs")
                                .addTokens(VerbatimHero.token("Ukaž", 0, 4, "ukázat", "VERB", 0, "root"))
                                .addTokens(VerbatimHero.token("dodací", 5, 11, "dodací", "ADJ", 3, "amod"))
                                .addTokens(VerbatimHero.token("místa", 12, 17, "místo", "NOUN", 1, "obj"))
                                .build(),
                    ).second.resolutionState

            plain.valuesList.none { it.kind == ValueKind.VALUE_KIND_VERBATIM } shouldBe true
            plain.rungLogList.none { it.action == LatticeAssembler.VERBATIM_ACTION } shouldBe true
        }

        "the planner schedules no lookup for a headless literal, though it IS a G3" {
            val lattice = headless()

            // The gap is real and stays open — this is not a claim that the literal resolved.
            lattice.gapsList
                .single { it.valueId.isNotBlank() }
                .kind shouldBe GapKind.GAP_KIND_G3_UNATTRIBUTED

            // And the BROAD tier, whose whole job is to widen an unscoped G3, declines this one.
            RoundPlanner
                .plan(lattice, listOf(storeType), asked = emptySet(), config = config)
                .shouldBeEmpty()
        }

        "a rung that proposes a ref for the literal is refused with E_VERBATIM_SPAN" {
            val fuzzy = VerbatimHero.FakeFuzzy(mapOf("Pelex" to listOf(VerbatimHero.member("p-1", "er.entity.store"))))
            val response =
                runBlocking {
                    VerbatimHero.pipeline(fuzzy).gate(
                        GateRequest
                            .newBuilder()
                            .setLattice(attributed())
                            .addHypotheses(
                                hypothesis(
                                    "Pelex",
                                    VerbatimHero.LITERAL_START,
                                    VerbatimHero.LITERAL_END,
                                    ref = "er.entity.store#p-1",
                                ),
                            ).build(),
                    )
                }

            response.gatedBindingsList.shouldBeEmpty()
            response.outcomesList.single().reason shouldBe ReGate.Reason.VERBATIM_SPAN
            // Refused WITHOUT asking. The distinction matters to the rung that proposed it: told
            // NO_CANDIDATE it would learn the estate has no such store, which is a lie about the
            // data and the kind of lie that stops it proposing something true later.
            fuzzy.lookups.shouldBeEmpty()
            // The round still reports what it touched, so the audit trail shows the refusal
            // happened on v1 rather than nowhere.
            response.rungLogEntry.valueIdsList shouldContainExactly listOf("v1")
        }

        "a hypothesis on the CONTENT of a literal is refused too — the rule is overlap" {
            // The shape a real rung produces: it reads `verbatim_text` and proposes about `Pelex`
            // (33–38), not about `"Pelex"` (32–39). Exact-offset keying would answer NO_SPAN —
            // the right refusal for the wrong reason, and a reason a rung is entitled to act on
            // by proposing again with adjusted offsets.
            val fuzzy = VerbatimHero.FakeFuzzy(mapOf("Pelex" to listOf(VerbatimHero.member("p-1", "er.entity.store"))))
            val response =
                runBlocking {
                    VerbatimHero.pipeline(fuzzy).gate(
                        GateRequest
                            .newBuilder()
                            .setLattice(attributed())
                            .addHypotheses(hypothesis("Pelex", 33, 38, ref = "er.entity.store#p-1"))
                            .build(),
                    )
                }

            response.outcomesList.single().reason shouldBe ReGate.Reason.VERBATIM_SPAN
            fuzzy.lookups.shouldBeEmpty()
        }

        "the refusal is about the literal, not about the question: other spans still gate" {
            val fuzzy =
                VerbatimHero.FakeFuzzy(
                    mapOf(
                        "Pelex" to listOf(VerbatimHero.member("p-1", "er.entity.store")),
                        "dodací místa" to listOf(VerbatimHero.declared("er.entity.store")),
                    ),
                )
            val lattice = attributed()
            val mention = lattice.mentionsList.first()
            val response =
                runBlocking {
                    VerbatimHero.pipeline(fuzzy).gate(
                        GateRequest
                            .newBuilder()
                            .setLattice(lattice)
                            .addHypotheses(
                                hypothesis(
                                    "Pelex",
                                    VerbatimHero.LITERAL_START,
                                    VerbatimHero.LITERAL_END,
                                    ref = "er.entity.store#p-1",
                                ),
                            ).addHypotheses(
                                hypothesis(
                                    mention.span.text,
                                    mention.span.start,
                                    mention.span.end,
                                    ref = "er.entity.store",
                                ),
                            ).build(),
                    )
                }

            response.outcomesList.map { it.reason } shouldContainExactly
                listOf(ReGate.Reason.VERBATIM_SPAN, "")
            // The mention was asked about; the literal was not.
            fuzzy.lookups shouldContainExactly listOf("dodací místa")
        }
    }) {
    companion object {
        private fun hypothesis(
            text: String,
            start: Int,
            end: Int,
            ref: String,
            rung: String = "capable",
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
    }
}
