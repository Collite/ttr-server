// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.writer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.serialization.json.*
import org.tatrman.automation.apply.ApplyPlanner
import org.tatrman.automation.apply.ApplyRow
import org.tatrman.automation.apply.PlanSource
import org.tatrman.automation.apply.PreviewPlan
import org.tatrman.automation.apply.SettleKey
import org.tatrman.automation.bundle.ApprovalFixture
import org.tatrman.automation.bundle.BundleFixture
import org.tatrman.automation.bundle.ManifestVerdictSchema
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.identity.RunAsRef
import org.tatrman.automation.identity.RunIdentity
import org.tatrman.automation.identity.TokenKind
import org.tatrman.automation.runner.Door
import org.tatrman.automation.runner.DoorRead
import org.tatrman.automation.runner.DoorWrite
import org.tatrman.automation.runner.DoorWriteFailure
import org.tatrman.automation.runner.WriteResult
import java.time.Clock
import java.util.UUID

/** review-303 F2 (a current pair settles as a unit) and F4 (the digest keeps a number apart from a string). */
class RowIdentitySpec :
    StringSpec({
        val key = buildJsonObject { put("order_number", "4711") }
        val current = "er.entity.release_order_verdict_current"

        "review-303 F4: the digest tells the number 100 from the string \"100\"; numbers compare by value" {
            fun digest(text: String) = RowIdentity.digest(Json.parseToJsonElement(text))
            digest("""{"a":100}""") shouldNotBe digest("""{"a":"100"}""")
            digest("""{"a":100}""") shouldBe digest("""{"a":1.00E2}""")
            digest("""{"a":100}""") shouldBe digest("""{"a":100.0}""")
            digest("""{"a":true}""") shouldNotBe digest("""{"a":"true"}""")
        }

        "review-303 F2: a change of one route's current row changes the identity of the delete and of every insert" {
            val planner = ApplyPlanner(Clock.systemUTC())
            val source =
                PlanSource(
                    ManifestVerdictSchema(
                        "release_order_verdict",
                        detail = "release_order_verdict_detail",
                        current = "release_order_verdict_current",
                    ),
                    emptyMap(),
                    "automation_db",
                )

            fun plan(holdReason: String): List<ApplyRow> {
                fun verdict(
                    seq: Int,
                    route: String,
                    reason: String,
                ) = ApplyRow(
                    seq,
                    "er.entity.release_order_verdict",
                    "insert",
                    key,
                    row =
                        JsonObject(
                            key +
                                mapOf(
                                    "run" to JsonPrimitive(UUID.randomUUID().toString()),
                                    "route" to JsonPrimitive(route),
                                    "verdict_reason" to JsonPrimitive(reason),
                                ),
                        ),
                )
                return planner
                    .plan(
                        PreviewPlan(
                            "b",
                            "r",
                            listOf(verdict(1, "hold_sales", holdReason), verdict(2, "release", "R")),
                            emptyList(),
                            source = source,
                            candidateKeys = listOf(key),
                        ),
                    ).rows
                    .filter { it.entity == current }
            }
            val before = plan("A")
            val after = plan("A2")
            before.map { it.op } shouldBe listOf("delete", "insert", "insert")
            // The run's own `run` is not counted: the same plan twice has the same identities.
            plan("A").map(RowIdentity::of) shouldBe before.map(RowIdentity::of)
            // The release row did not change, yet its identity did — it is resent with the delete that clears it.
            before.zip(after).forEach { (b, a) -> RowIdentity.of(a) shouldNotBe RowIdentity.of(b) }
            // Two inserts of one key keep apart by route.
            before.drop(1).map { it.settle!!.key["automation:route"] } shouldBe
                listOf(JsonPrimitive("hold_sales"), JsonPrimitive("release"))
        }

        "review-303 F2 guard: an insert of a key whose delete is sent is never settled, whatever the settled set says" {
            val root =
                ApprovalFixture.copyTo(
                    kotlin.io.path
                        .createTempDirectory("rid")
                        .resolve("b"),
                )
            val entities = BundleFixture.load(root).entities
            val delete = ApplyRow(1, current, "delete", key, settle = SettleKey(key, "sha256:group-2"))
            val insert =
                ApplyRow(
                    2,
                    current,
                    "insert",
                    key,
                    row =
                        JsonObject(
                            key + mapOf("route" to JsonPrimitive("release"), "verdict_reason" to JsonPrimitive("R")),
                        ),
                )
            var sent = 0
            val door =
                object : Door {
                    override suspend fun read(read: DoorRead) = error("unused")

                    override suspend fun write(write: DoorWrite): WriteResult {
                        sent += write.rows.rowsCount
                        throw DoorWriteFailure.Refused("storage-unmapped", "recorded, not written")
                    }
                }
            val recorded = mutableListOf<RowOutcome>()
            Apply(door, Clock.systemUTC()).apply(
                ApplyInput(
                    UUID.randomUUID(),
                    "demo",
                    RunIdentity(Caller("s", "svc", emptySet(), TokenKind.SERVICE, "t"), RunAsRef.Caller, "svc", null),
                    "fp",
                    Clock.systemUTC().instant().plusSeconds(5),
                    listOf(delete, insert),
                    emptyList(),
                    PlanSource(
                        ManifestVerdictSchema(
                            "release_order_verdict",
                            current = "release_order_verdict_current",
                        ),
                        emptyMap(),
                        "automation_db",
                        writable = mapOf("automation_db" to listOf("*_verdict_current")),
                    ),
                    entities,
                    listOf("release"),
                    // The insert's identity is in the settled set (a hand-built input: no planner group digest); the
                    // delete's is not.
                    settled = mapOf(requireNotNull(RowIdentity.of(insert)) to JsonObject(emptyMap())),
                ),
                object : ApplyJournal {
                    override fun begin(
                        seqs: List<Int>,
                        request: String,
                    ) = Unit

                    override fun release(seqs: List<Int>) = Unit

                    override fun record(outcomes: List<RowOutcome>) {
                        recorded += outcomes
                    }
                },
            )
            recorded.none { it.error == RowOutcome.SETTLED } shouldBe true
            recorded.single { it.seq == 2 }.status shouldBe "failed"
            sent shouldBe 2
        }
    })
