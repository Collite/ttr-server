// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.protocol

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.tatrman.automation.runner.RunProgress

/** Sanitized responses from the shared Server S3.3 and process-engine S1.1 walk on bp-dsk. */
class H1EngineCertificationFixtureSpec :
    StringSpec({
        "the on-event Sales hold closes with the same engine and decider identity in its protocol" {
            val run = certificationFixture<RunProgress>("t2-run")
            val protocol = certificationFixture<ProtocolDocument>("t2-protocol")
            protocol.assertSealedFixture()
            protocol.run shouldBe run.runId
            run.status shouldBe "closed"
            run.batch?.state shouldBe "committed"
            run.runAs shouldBe "svc-hartland-automation"
            run.verdict
                ?.routes
                ?.get("hold_sales")
                ?.rowCount shouldBe 1L
            run.actions.map { it.action } shouldBe listOf("hold", "notify")
            run.actions.forEach { action ->
                action.key.getValue("order_number") shouldBe JsonPrimitive(200117047)
            }

            val opened =
                protocol.sections
                    .getValue("automation")
                    .first()
                    .payload.jsonObject
            opened["caller"] shouldBe JsonPrimitive("process-engine")
            opened["runAs"] shouldBe JsonPrimitive(run.runAs)
            val engine = opened.getValue("engine").jsonObject
            engine["instanceId"] shouldBe JsonPrimitive("cc32160c-e4f9-11f1-942b-7e60a2870a34")
            engine["businessKey"] shouldBe JsonPrimitive("200117047")
        }

        "the approval commits the original numeric key after verifying the task assignee" {
            val original = certificationFixture<RunProgress>("t3-original-run")
            val originalProtocol = certificationFixture<ProtocolDocument>("t3-original-protocol")
            val answer = certificationFixture<RunProgress>("t3-answer-run")
            val answerProtocol = certificationFixture<ProtocolDocument>("t3-answer-protocol")
            originalProtocol.assertSealedFixture()
            originalProtocol.run shouldBe original.runId
            original.status shouldBe "waiting"
            val key = original.actions.single().key
            key.getValue("order_number") shouldBe JsonPrimitive(200117739)

            val decision = checkNotNull(answer.answer)
            answer.kind shouldBe "answer"
            answer.status shouldBe "closed"
            decision.original shouldBe original.runId
            decision.batch shouldBe original.batch?.id
            decision.key shouldBe key
            decision.decision shouldBe "commit"
            decision.applied shouldBe 1
            decision.by shouldBe "approver"

            answerProtocol.schema shouldBe originalProtocol.schema
            answerProtocol.run shouldBe answer.runId
            answerProtocol.sections.keys.toList() shouldBe originalProtocol.sections.keys.toList()
            val opened =
                answerProtocol.sections
                    .getValue("automation")
                    .single()
                    .payload.jsonObject
            opened.getValue("port").jsonObject["key"] shouldBe key
            val engine = opened.getValue("engine").jsonObject
            engine["instanceId"] shouldBe JsonPrimitive("16ce6cf7-e4fa-11f1-942b-7e60a2870a34")
            engine["businessKey"] shouldBe JsonPrimitive("200117739")
            engine["answeredBy"] shouldBe JsonPrimitive(decision.by)
            engine["personVerified"] shouldBe JsonPrimitive(true)

            val identity =
                answerProtocol.sections
                    .getValue("people")
                    .single()
                    .payload.jsonObject
            identity["source"] shouldBe JsonPrimitive("engine:history/task")
            identity["taskId"] shouldBe engine.getValue("taskId")
            identity["instanceId"] shouldBe engine.getValue("instanceId")
            identity["verified"] shouldBe JsonPrimitive(true)
            identity.getValue("history").jsonObject["assignee"] shouldBe JsonPrimitive(decision.by)
        }

        "the timeout expires the approval as a refusal and its correlated typed hold holds the order for Sales" {
            val original = certificationFixture<RunProgress>("t4-original-run")
            val originalProtocol = certificationFixture<ProtocolDocument>("t4-original-protocol")
            val perform = certificationFixture<RunProgress>("t4-perform-run")
            val performProtocol = certificationFixture<ProtocolDocument>("t4-perform-protocol")
            originalProtocol.assertSealedFixture()
            performProtocol.assertSealedFixture()
            originalProtocol.run shouldBe original.runId
            performProtocol.run shouldBe perform.runId

            // The original run: the approval was never answered; the due timer decided it as a refusal (expire).
            original.status shouldBe "closed"
            val approval = original.actions.single { it.action == "release_confirmed" }
            val order = approval.key.getValue("order_number")
            order shouldBe JsonPrimitive(219990303)
            val decided = checkNotNull(approval.decided)
            decided["decision"] shouldBe JsonPrimitive("expire")
            decided["by"] shouldBe null
            decided["holdRun"] shouldBe JsonPrimitive(perform.runId)
            val originalOpen =
                originalProtocol.sections
                    .getValue("automation")
                    .first()
                    .payload.jsonObject
            val engine = originalOpen.getValue("engine").jsonObject
            engine["instanceId"] shouldBe JsonPrimitive("4904951f-e82f-11f1-86d7-c23630ba32e0")
            engine["businessKey"] shouldBe JsonPrimitive("219990303")
            val expired =
                originalProtocol.sections
                    .getValue("writes")
                    .map { it.payload.jsonObject }
                    .single { it["event"] == JsonPrimitive("held-expire") }
            expired["holdRun"] shouldBe JsonPrimitive(perform.runId)
            expired["after_seal"] shouldBe JsonPrimitive(true)
            expired["group"] shouldBe approval.key
            val evidence = expired.getValue("expiry").jsonObject
            evidence["source"] shouldBe JsonPrimitive("engine:history")
            evidence["originalRunId"] shouldBe JsonPrimitive(original.runId)
            evidence["action"] shouldBe JsonPrimitive("release_confirmed")
            evidence["key"] shouldBe approval.key
            evidence["instanceId"] shouldBe engine["instanceId"]
            evidence["timerActivityId"] shouldBe JsonPrimitive("s41_release_confirmed_lapsed")

            // The typed hold: opened by the engine with the same verified evidence, applied for Sales.
            perform.kind shouldBe "perform"
            perform.status shouldBe "closed"
            val hold =
                performProtocol.sections
                    .getValue("automation")
                    .first()
                    .payload.jsonObject
            hold["action"] shouldBe JsonPrimitive("hold")
            hold["caller"] shouldBe JsonPrimitive("process-engine")
            hold.getValue("key").jsonObject["order"] shouldBe order
            hold.getValue("params").jsonObject["department"] shouldBe JsonPrimitive("Sales")
            hold["expiry"] shouldBe evidence
            hold.getValue("engine").jsonObject["taskId"] shouldBe evidence["taskId"]
            val outcome =
                performProtocol.sections
                    .getValue("writes")
                    .last()
                    .payload.jsonObject
            outcome["event"] shouldBe JsonPrimitive("performed")
            outcome["outcome"] shouldBe JsonPrimitive("applied")
            outcome.getValue("rows").jsonArray.forEach { row ->
                row.jsonObject["status"] shouldBe JsonPrimitive("applied")
            }
        }
    })

private inline fun <reified T> certificationFixture(name: String): T {
    val resource = H1EngineCertificationFixtureSpec::class.java.getResource("/certification/p3/$name.json")
    return Json.decodeFromString(checkNotNull(resource).readText())
}
