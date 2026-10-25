// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.mcp.identity.IdentityPolicy
import org.tatrman.resolver.concept.ConceptModels
import org.tatrman.resolver.mcp.ConceptDoor
import org.tatrman.resolver.mcp.ConceptDoorHandler
import org.tatrman.resolver.token.ResumeOption
import org.tatrman.resolver.token.ResumePayload
import org.tatrman.ttr.lexicon.cli.ModelContentId

/**
 * CN-P5 S5.1 T2/T3/T7 — what the cn doors do that is not the module's: the fail-closed gate, the model they answer from
 * and its pin, resume tokens (verified, never minted), and argument errors. The module's own answers are the seeds'
 * ([CnDoorConformanceTest]).
 */
class CnDoorTest :
    StringSpec({
        fun args(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

        val revenue = """{"ask":"ASK_VALUE","measures":[{"qname":"cn.revenue"}],"time":{"allTime":true}}"""

        fun CallToolResult.code(): String = structuredContent!!["errorCode"]!!.jsonPrimitive.content

        "the tool list is the ledger's eight ids, in the families' order" {
            CnDoors.door().tools.map { it.name } shouldBe ConceptDoor.TOOL_NAMES
            ConceptDoor.TOOL_NAMES shouldBe
                listOf(
                    "statement.validate:v1",
                    "statement.lower:v1",
                    "statement.explain:v1",
                    "assertion.validate:v1",
                    "assertion.lower:v1",
                    "assertion.explain:v1",
                    "concept.search:v1",
                    "definition.render:v1",
                )
        }

        "fail-closed (H-2): with identity required, a call with none never reaches the model" {
            // A door over no model at all: the identity refusal must come first, or the model's error would show.
            val handler =
                ConceptDoorHandler(CnDoors.door(modelPath = null), requireIdentity = true, IdentityPolicy.TOKEN_ONLY)
            for (tool in ConceptDoor.TOOL_NAMES) {
                val r =
                    handler.handle(
                        tool,
                        args("""{"statement":$revenue,"text":"VIP","user_id":"mallory"}"""),
                        null,
                        null,
                    )
                r.isError shouldBe true
                (r.code() == ConceptModels.CN_MODEL_UNAVAILABLE) shouldBe false
            }
        }

        "no model configured: the frame tools answer CN_MODEL_UNAVAILABLE, the search still answers" {
            val door = CnDoors.door(modelPath = null)
            val r = door.call("statement.validate:v1", args("""{"statement":$revenue}"""))
            r.isError shouldBe true
            r.code() shouldBe ConceptModels.CN_MODEL_UNAVAILABLE
            val s = door.call("concept.search:v1", args("""{"text":"VIP"}"""))
            s.isError shouldBe false
            s.structuredContent!!["candidates"]!!.jsonArray.size shouldBe 2
        }

        "a model the resolver's vocabulary was not compiled from is refused, never served" {
            val r =
                CnDoors
                    .door(
                        archive = CnEstate.pack(),
                    ).call("statement.validate:v1", args("""{"statement":$revenue}"""))
            r.isError shouldBe true
            r.code() shouldBe ConceptModels.CN_MODEL_SKEW
        }

        "every answer names the resolver's snapshot; a frame from another is a warning, not a refusal (A-CN-34)" {
            val archive = CnEstate.pinnedArchive()
            val snapshot = CnEstate.registry(archive).current().snapshotHash
            val door = CnDoors.door(archive)
            val ok = door.call("statement.validate:v1", args("""{"statement":$revenue}""")).structuredContent!!
            ok["snapshotHash"]!!.jsonPrimitive.content shouldBe snapshot
            (snapshot == ModelContentId.of(CnEstate.root.resolve("model"))) shouldBe false

            val stale = revenue.removeSuffix("}") + ""","snapshotHash":"sha256:old"}"""
            val out = door.call("statement.validate:v1", args("""{"statement":$stale}""")).structuredContent!!
            out["refused"]!!.jsonPrimitive.boolean shouldBe false
            out["diagnostics"]!!.jsonArray.map { it.jsonObject["slug"]!!.jsonPrimitive.content } shouldContain
                "cn/snapshot-mismatch"
        }

        "md first; a statement md carries no single read of is read on E-R — `lane`, `erSql`, no plan (A-CN-101)" {
            val january = """"time":{"periodCode":"2026-01"}"""

            suspend fun lower(
                door: ConceptDoor,
                statement: String,
            ): JsonObject = door.call("statement.lower:v1", args("""{"statement":$statement}""")).structuredContent!!
            // the md hero carries E-0 on one cubelet: md reads it
            val e0 = """{"ask":"ASK_VALUE","measures":[{"qname":"cn.revenue"}],$january}"""
            val md = lower(CnDoors.world("sales"), e0)
            md["lane"]!!.jsonPrimitive.content shouldBe "md"
            md["refused"]!!.jsonPrimitive.boolean shouldBe false
            md.containsKey("plan") shouldBe true
            md.containsKey("erSql") shouldBe false
            // two channel cubelets carry revenue and none has a date: E-R reads it, the day through the calendar key
            val er =
                lower(
                    CnDoors.world("calendar-key"),
                    """{"ask":"ASK_VALUE","measures":[{"qname":"cn.revenue"}],""" +
                        """"filters":[{"phase":{"phase":{"qname":"cn.WebSale"}}}],$january}""",
                )
            er["lane"]!!.jsonPrimitive.content shouldBe "er"
            er["refused"]!!.jsonPrimitive.boolean shouldBe false
            er.containsKey("plan") shouldBe false
            er["erSql"]!!.jsonPrimitive.content shouldBe
                "SELECT SUM(sales.ext_sales_price) AS revenue\n" +
                "FROM sales\n" +
                "JOIN date_dim ON date_dim.sk = sales.sold_date\n" +
                "WHERE sales.channel = 'web' AND date_dim.cal_date >= DATE '2026-01-01' " +
                "AND date_dim.cal_date < DATE '2026-02-01'"
            er["diagnostics"]!!.jsonArray.map { it.jsonObject["slug"]!!.jsonPrimitive.content } shouldContain
                "cn/lowered-on-er"
        }

        // ----- resume tokens (architecture §8, C-Δ3): verified with the resolver's codec, never minted -----

        fun token(
            options: List<ResumeOption>,
            subject: String = "",
        ): String =
            CnDoors.codec.sign(
                ResumePayload(
                    conversationId = "c-1",
                    parseRef = "p-1",
                    options = options,
                    issuedAt = System.currentTimeMillis() / 1000,
                    keyId = CnDoors.codec.activeKeyId,
                    subject = subject,
                ),
            )

        fun ambiguous(token: String): JsonObject =
            args(
                """{"statement":{"ask":"ASK_VALUE","time":{"allTime":true},"measures":[{"candidates":[""" +
                    """{"qname":"cn.revenue","resumeToken":"$token"},{"qname":"cn.Item"}]}]}}""",
            )

        val offered =
            listOf(
                ResumeOption("opt-1", "Tržby", targetRef = "cn.revenue", conceptQname = "cn.revenue"),
                ResumeOption("opt-2", "Položka", targetRef = "cn.Item", conceptQname = "cn.Item"),
            )

        "a candidate's token the resolver signed for this caller passes; the frame is then judged on its own" {
            val r = CnDoors.door().call("statement.validate:v1", ambiguous(token(offered)))
            r.isError shouldBe false
            r.structuredContent!!["refused"]!!.jsonPrimitive.boolean shouldBe true // still two candidates: ambiguous
        }

        "a forged, foreign or mismatched token is RG-RES-002, naming the slot" {
            val door = CnDoors.door()
            val forged = door.call("statement.validate:v1", ambiguous("e30.AAAA"))
            forged.isError shouldBe true
            forged.code() shouldBe ConceptDoor.RESUME_TOKEN_REJECTED
            forged.structuredContent!!["error"]!!.jsonPrimitive.content shouldContain "measures[0].candidates[0]"

            val foreign = door.call("statement.validate:v1", ambiguous(token(offered, subject = "someone-else")))
            foreign.code() shouldBe ConceptDoor.RESUME_TOKEN_REJECTED

            val notOffered = door.call("statement.validate:v1", ambiguous(token(offered.drop(1))))
            notOffered.code() shouldBe ConceptDoor.RESUME_TOKEN_REJECTED
        }

        "a member's token must have offered the value the slot names" {
            val door = CnDoors.world("clinic")
            val pick =
                token(listOf(ResumeOption("opt-1", "P-1001", resolvedId = "1001", entityTypeRef = "er.entity.patient")))

            fun withMember(value: String) =
                args(
                    """{"assertion":{"event":{"qname":"cn.Transfer"},"identity":{"new":true},"participants":[""" +
                        """{"label":"patient","concept":{"qname":"cn.Patient"},"member":{"label":"P","resolvedValue":"$value","resumeToken":"$pick"}}]}}""",
                )
            door.call("assertion.validate:v1", withMember("1001")).isError shouldBe false
            door.call("assertion.validate:v1", withMember("9999")).code() shouldBe ConceptDoor.RESUME_TOKEN_REJECTED
        }

        // ----- arguments -----

        "a malformed call is INVALID_ARGUMENT — an unknown frame field is never silently dropped" {
            val door = CnDoors.door()
            val cases =
                listOf(
                    "statement.lower:v1" to """{}""",
                    "statement.lower:v1" to """{"statement":{"ask":"ASK_VALUE","measurez":[]}}""",
                    "assertion.lower:v1" to """{"assertion":"not an object"}""",
                    "concept.search:v1" to """{"text":"  "}""",
                    "definition.render:v1" to """{"concept":"cn.OverdueInvoice"}""",
                    "definition.render:v1" to """{"concept":"cn.OverdueInvoice","layer":"sql"}""",
                    "definition.render:v1" to """{"concept":"cn.OverdueInvoice","layer":"er","mode":"table"}""",
                    "definition.render:v1" to """{"concept":"cn.OverdueInvoice","layer":"er","as_of":"yesterday"}""",
                )
            for ((tool, raw) in cases) {
                val r = door.call(tool, args(raw))
                r.isError shouldBe true
                r.code() shouldBe ConceptDoor.INVALID_ARGUMENT
            }
        }

        "an answer carries the content line a reader can act on" {
            val r =
                CnDoors
                    .world(
                        "clinic",
                    ).call("definition.render:v1", args("""{"concept":"cn.PostStemi","layer":"er"}"""))
            r.structuredContent.shouldNotBeNull()
            (r.content.single() as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text!! shouldContain
                "SELECT patient.patient_id"
        }
    })
