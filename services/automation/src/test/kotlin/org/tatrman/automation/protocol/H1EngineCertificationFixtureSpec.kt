// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.protocol

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
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
    })

private inline fun <reified T> certificationFixture(name: String): T {
    val resource = H1EngineCertificationFixtureSpec::class.java.getResource("/certification/p3/$name.json")
    return Json.decodeFromString(checkNotNull(resource).readText())
}
