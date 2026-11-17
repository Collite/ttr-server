// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.writer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.apply.ApplyRow
import org.tatrman.automation.apply.BatchRowStatus
import org.tatrman.automation.apply.DryRunAction
import org.tatrman.automation.apply.PlanSource
import org.tatrman.automation.bundle.BundleFixture
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.identity.RunAsRef
import org.tatrman.automation.identity.RunIdentity
import org.tatrman.automation.identity.TokenKind
import org.tatrman.automation.ledger.HeldDecision
import org.tatrman.automation.ledger.HeldGroup
import org.tatrman.automation.ledger.Ledger
import org.tatrman.automation.ledger.RunNames
import org.tatrman.automation.ledger.RunStatus
import org.tatrman.automation.runner.RunFingerprints
import java.time.Duration
import java.util.UUID

/**
 * S3.5 T1 (k)–(l): `Held.commitKey` — the answer run's decision (D-230, ⚑AU-S35-1). The hold group is the candidate key
 * (⚑20-2), so an answer commits exactly its key's held rows: one [ApplyInput] over them, the action's set + transition
 * together (one compare-and-set), the decision recorded by the verified person and naming the answer run. `role.ops`
 * is not asked of the engine client (contract 5); D-99 is.
 */
class HeldKeySpec :
    StringSpec({
        val bundle = BundleFixture.load()
        val recipe = bundle.recipes.getValue("release_order").recipe
        val run = UUID.randomUUID()
        val batch = UUID.randomUUID()
        val answer = UUID.randomUUID()
        val k4711 = buildJsonObject { put("order_number", "4711") }
        val k4712 = buildJsonObject { put("order_number", "4712") }
        val engine = Caller("sub-engine", "process-engine", emptySet(), TokenKind.SERVICE, "engine-token")
        val names =
            RunNames(
                "release_order",
                "release_on_placement",
                "release_native_event",
                bundle.id,
                "svc-hartland-automation",
            )
        val fingerprints =
            RunFingerprints(
                recipe.fingerprints.text,
                recipe.fingerprints.values,
                bundle.programFps.getValue("release_on_placement"),
                bundle.deploymentFps.getValue("release_native_event"),
                bundle.modelFp,
            )

        fun transition(
            seq: Int,
            key: kotlinx.serialization.json.JsonObject,
        ) = ApplyRow(
            seq,
            "er.entity.sales_order",
            "transition",
            key,
            attribute = "state",
            from = listOf("placed"),
            to = JsonPrimitive("released"),
            history = true,
            group = key,
        )

        // seq 1 = the verdict (auto), 2 + 3 = 4711's action (a `set:` and its transition: one CAS), 4 = 4712's.
        val rows =
            listOf(
                ApplyRow(1, "er.entity.release_order_verdict", "insert", row = k4711),
                ApplyRow(
                    2,
                    "er.entity.sales_order",
                    "update",
                    k4711,
                    set = buildJsonObject { put("held_for", "AR") },
                    group = k4711,
                ),
                transition(3, k4711),
                transition(4, k4712),
            )
        val actions =
            listOf(
                DryRunAction(k4711, "release", "release_confirmed", "transition", buildJsonObject {}, listOf(2, 3)),
                DryRunAction(k4712, "release", "release_confirmed", "transition", buildJsonObject {}, listOf(4)),
            )

        fun group(
            key: kotlinx.serialization.json.JsonObject,
            decided: Boolean = false,
            status: RunStatus = RunStatus.WAITING,
        ): HeldGroup {
            val mine = rows.filter { it.group == key }
            return HeldGroup(
                run,
                batch,
                status,
                "held",
                names,
                fingerprints,
                mine,
                mine.map {
                    BatchRowStatus(
                        it.seq,
                        if (decided) "applied" else "staged",
                        decided =
                            if (decided) {
                                buildJsonObject {
                                    put("decision", "commit")
                                    put("by", "alice")
                                }
                            } else {
                                null
                            },
                    )
                },
                actions,
                Duration.ofMinutes(30),
            )
        }

        val context =
            ApplyContext(
                "demo",
                RunIdentity(
                    engine,
                    RunAsRef.Named("automation_decider"),
                    "svc-hartland-automation",
                    "AUTOMATION_DECIDER",
                ),
                bundle.modelFp,
                PlanSource(null, emptyMap(), "automation_db"),
                emptyList(),
                listOf("release"),
            )

        fun decision(applied: Int) = HeldDecision(batch.toString(), k4711, "commit", applied, 0, "waiting")

        "(k)(l) only the answered key's rows go, in one ApplyInput with their partners; the decision names the answer" {
            val ledger = mockk<Ledger>()
            every { ledger.heldGroup(run, k4711) } returns group(k4711)
            every { ledger.decideHeld(run, k4711, "commit", "alice", answer) } returns decision(2)
            val apply = mockk<Apply>()
            val sent = slot<ApplyInput>()
            coEvery { apply.reconcile(any(), any(), any(), true) } returns emptyMap()
            coEvery { apply.apply(capture(sent), any()) } answers {
                sent.captured.rows.associate { it.seq to RowOutcome(it.seq, "applied") }
            }
            val held = Held(ledger, apply, { _, _, _ -> context }, { bundle }, BundleFixture.clock)

            val decided = held.commitKey(run, k4711, engine, "alice", answer)

            decided.applied shouldBe 2
            decided.replayed shouldBe false
            // (k) The key's seqs only — never 4712's row, nor the auto verdict row.
            sent.captured.rows.map { it.seq } shouldBe listOf(2, 3)
            // (l) The set + transition of 4711's action go to the writer together: its action evidence pairs them.
            sent.captured.actions
                .single { it.key == k4711 }
                .batchRows shouldBe listOf(2, 3)
            verify(exactly = 1) { ledger.decideHeld(run, k4711, "commit", "alice", answer) }
            verify(exactly = 0) { ledger.heldGroup(run, k4712) }
        }

        "contract 5: the engine client needs no role.ops for an answer; the dev route's commitHeld still does" {
            val ledger = mockk<Ledger>()
            every { ledger.heldGroup(run, k4711) } returns group(k4711)
            val held = Held(ledger, mockk(), { _, _, _ -> context }, { bundle }, BundleFixture.clock)
            shouldThrow<AutomationException> { held.commitHeld(run, k4711, engine) }.code shouldBe ErrorCode.IDENTITY
        }

        "contract 2: a key already decided replays its recorded decision; nothing is applied again" {
            val ledger = mockk<Ledger>()
            every { ledger.heldGroup(run, k4711) } returns group(k4711, decided = true)
            every { ledger.decideHeld(run, k4711, "commit", "-") } returns decision(2)
            val apply = mockk<Apply>()
            val held =
                Held(ledger, apply, { _, _, _ -> error("no context for a replay") }, { bundle }, BundleFixture.clock)

            val replayed = held.commitKey(run, k4711, engine, "alice", answer)

            replayed.replayed shouldBe true
            replayed.applied shouldBe 2
            coVerify(exactly = 0) { apply.apply(any(), any()) }
        }

        "D-99 first: a moved fingerprint refuses the answer before any apply (409 superseded)" {
            val ledger = mockk<Ledger>()
            every { ledger.heldGroup(run, k4711) } returns group(k4711)
            val apply = mockk<Apply>()
            val moved = bundle.copy(deploymentFps = bundle.deploymentFps + ("release_native_event" to "sha256:moved"))
            val held = Held(ledger, apply, { _, _, _ -> context }, { moved }, BundleFixture.clock)
            val refused = shouldThrow<AutomationException> { held.commitKey(run, k4711, engine, "alice", answer) }
            refused.code shouldBe ErrorCode.SUPERSEDED
            coVerify(exactly = 0) { apply.apply(any(), any()) }
        }
    })
