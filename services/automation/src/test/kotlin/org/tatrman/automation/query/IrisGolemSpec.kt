// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.query

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode

/** iris-bff's `/v1/chat/turn` answer (one `IrisStreamEvent`, proto-JSON) → the Golem lane's verdict and frame. */
class IrisGolemSpec :
    StringSpec({
        val golem = IrisGolem("http://iris.invalid")

        fun answer(event: String) = golem.answer(Json.parseToJsonElement(event).jsonObject)

        "a TABLE envelope's contentJson rows become the frame, typed by their values" {
            val a =
                answer(
                    """{"turnId":"t-1","envelope":{"turnId":"t-1","contentJson":"[{\"customer\":\"C-1\",\"revenue\":12,\"share\":0.5},{\"customer\":\"C-2\",\"revenue\":3,\"share\":1}]",
                        "format":{"kind":"TABLE","table":{"headers":[{"name":"customer","title":"Customer"},{"name":"revenue"},{"name":"share"}]}},
                        "currentView":{"totalRows":"2"},"explanation":{"protocolRef":{"traceId":"x","turnId":"t-1"}}}}""",
                )
            a.verdict shouldBe QueryVerdict.ANSWERED
            a.table!!.columns.map { it.name to it.type } shouldBe
                listOf("customer" to "text", "revenue" to "int", "share" to "float")
            a.table!!.rows shouldBe listOf(listOf("C-1", 12L, 0.5), listOf("C-2", 3L, 1.0))
            a.truncated shouldBe false
            a.turnId shouldBe "t-1"
            a.protocolRef.toString() shouldBe """{"traceId":"x","turnId":"t-1"}"""
        }

        "a pending clarification is clarify, an error code or an error event is refused, no table is 422" {
            answer("""{"envelope":{"turnId":"t","pendingClarification":{"kind":"entity_choice"}}}""").verdict shouldBe
                QueryVerdict.CLARIFY
            answer("""{"envelope":{"turnId":"t","errorCode":"REFUSE_NO_PERMISSION","text":"no"}}""").let {
                it.verdict shouldBe QueryVerdict.REFUSED
                it.code shouldBe "REFUSE_NO_PERMISSION"
            }
            answer("""{"turnId":"t","error":{"code":"NO_AGENT_CLIENT","message":"x"}}""").code shouldBe
                "NO_AGENT_CLIENT"
            shouldThrow<AutomationException> {
                answer("""{"envelope":{"turnId":"t","text":"hello","format":{"kind":"PLAINTEXT"}}}""")
            }.code shouldBe ErrorCode.GOLEM_NO_TABLE
        }

        "a table cut by the Golem (totalRows > rows) is truncated" {
            answer(
                """{"envelope":{"contentJson":"[{\"x\":1}]","format":{"kind":"TABLE"},"currentView":{"totalRows":"40"}}}""",
            ).truncated shouldBe true
        }
    })
