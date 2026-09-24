// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * RV-P2.2 DONE — **the gate is the only code path that writes bindings**, asserted by grep rather
 * than by promise.
 *
 * The p2-2 list states the criterion in exactly those terms, and the reason it is worth a test is
 * RV-7: LLM rungs, lookup rounds and the P2.4 re-gate are all *proposers*. The property that keeps
 * them proposers is that none of them can construct a `Binding` — they hand candidates to
 * [org.tatrman.resolver.pipeline.Binder] and take what comes back. A second construction site
 * anywhere would quietly reintroduce the thing RV-7 forbids, and it would look like ordinary code
 * while doing it. Same shape of guard as `NoLlmDependencyTest`, for the same reason: the invariant
 * is structural, so the check should be too.
 *
 * Read as a triangle: the CLASS comes from one place, the DECISION comes from one place, and the
 * lattice `Binding` message is BUILT in one place. Two doors reach the builder and each is
 * documented at its call site — the gate (bindings) and the grounding-trigger annotation
 * (evidence, which cannot bind).
 */
class SingleBinderTest :
    StringSpec({

        val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" }.toList()

        fun filesMatching(pattern: Regex): List<String> =
            sources.filter { pattern.containsMatchIn(it.readText()) }.map { it.name }.sorted()

        "the lattice `Binding` message is constructed in exactly one file" {
            // The lookbehind matters: `EntityBinding.newBuilder()` is the DOOR's legacy binding
            // message (`Resolution.bindings`), a different message with a different life, and it
            // is not what this guard is about.
            // `\s*` because ktlint puts the builder chain on its own line.
            filesMatching(Regex("""(?<![A-Za-z])Binding\s*\.\s*newBuilder\(\)""")) shouldContainExactlyInAnyOrder
                listOf("Bindings.kt")
        }

        "only the gate, the re-gate tool and the trigger annotation may reach that constructor" {
            // `ReGate` was added by RV-P2.4 and this assertion is how it announced itself — the
            // guard failed the moment a third file could build a `Binding`, which is the whole
            // point of writing it down rather than trusting review. It is admitted because it is
            // the p2-4 DONE criterion in person: "the only write-path into bindings from outside
            // the core loop is this tool, and it goes through the P2.2 gate". It does — every
            // binding it emits came out of a `Binder.Bind` verdict, never from a hypothesis'
            // say-so. A FOURTH entry here should be argued, not added.
            filesMatching(Regex("""Bindings\.of\(""")) shouldContainExactlyInAnyOrder
                listOf("GroundingTriggers.kt", "LatticeAssembler.kt", "ReGate.kt")
        }

        "the re-gate tool cannot bind on a hypothesis' authority — it only forwards gate verdicts" {
            val reGate = sources.single { it.name == "ReGate.kt" }.readText()
            // Every `Bindings.of` in the tool takes a winner the binder chose. If this ever reads
            // otherwise, a proposer has become a binder (RV-7) and the p2-4 DONE criterion is void.
            Regex("""Bindings\.of\(([^,)]+)""")
                .findAll(reGate)
                .map { it.groupValues[1].trim() }
                .toList() shouldContainExactlyInAnyOrder listOf("verdict.winner")
        }

        "an evidence class is derived in one place, and asked for in three" {
            // `Binder` classifies what it is about to decide on; `GroundingTriggers` and
            // `PredicateTriggers` classify what they will never decide on — a kernel claim and a
            // comparison word, neither of which binds anything. Nothing else may form an opinion
            // about trust, and a FOURTH entry here should be argued, not added.
            //
            // LP-P2b admitted the third, deliberately: a predicate form that falls below the bind
            // floor must not become a filter, and the only honest way to ask "is this trustworthy
            // enough?" is the one function that answers it for everyone else.
            filesMatching(Regex("""EvidenceClasses\.of\(""")) shouldContainExactlyInAnyOrder
                listOf("Binder.kt", "GroundingTriggers.kt", "PredicateTriggers.kt")
        }

        "the class ORDER is consulted only by the binder — ranking candidates IS deciding" {
            filesMatching(Regex("""EvidenceClasses\.rank\(""")) shouldContainExactlyInAnyOrder listOf("Binder.kt")
        }

        // --- MH (plan risk 6) — a defaulted parameter must not silently skip a producer --------
        //
        // MS-P3 hit this: `owners` was added to `Binder.gate` with a default, one of the three
        // producers was not updated, and the collapse simply did not happen there. MH adds THREE
        // defaulted parameters, so the same shape of guard is written down rather than trusted.

        "every Binder.gate call in main passes the kinds and reach maps" {
            val calls =
                sources
                    .flatMap { file ->
                        Regex("""Binder\.gate\((?:[^()]|\([^()]*\))*\)""", RegexOption.DOT_MATCHES_ALL)
                            .findAll(file.readText())
                            .map { file.name to it.value }
                    }.toList()

            // Three producers gate: the broad pass, the lookup rounds, the re-gate.
            calls.map { it.first }.sorted() shouldContainExactlyInAnyOrder
                listOf("GateSpans.kt", "LookupRounds.kt", "ReGate.kt")
            calls.filterNot { it.second.contains("kinds") && it.second.contains("reach") } shouldBe emptyList()
        }

        "the slot is read from the candidate, and only inside the binder" {
            // Architecture A3: nothing outside `Binder` may consult a candidate's slot, because
            // the slot exists to feed ONE decision. `SlotHints` writes it; the Binder reads it.
            // The trailing `[,)]` keeps this to CODE: `SlotHints`' own KDoc names the expression
            // in prose, and a guard that fired on a comment would teach people to stop writing them.
            filesMatching(Regex("""candidate\.slot[,)]""")) shouldContainExactlyInAnyOrder listOf("Binder.kt")
        }

        "the three registry maps are derived in one place each" {
            // If a producer built its own kinds map by walking `entityTypes` itself, the three
            // gates could disagree about what the registry says — which is the drift the helpers
            // on `ResolverRegistry.kt` exist to prevent.
            // `ResolverRegistry.kt` is where they are DECLARED; the rest are the readers. The
            // pipeline reads kinds (to stamp slots) but never reach — nothing there decides.
            filesMatching(Regex("""\.kindsByRef\(\)""")) shouldContainExactlyInAnyOrder
                listOf("GateSpans.kt", "LookupRounds.kt", "ReGate.kt", "ResolverPipeline.kt", "ResolverRegistry.kt")
            filesMatching(Regex("""\.reachByRef\(\)""")) shouldContainExactlyInAnyOrder
                listOf("GateSpans.kt", "LookupRounds.kt", "ReGate.kt", "ResolverRegistry.kt")
        }
    })
