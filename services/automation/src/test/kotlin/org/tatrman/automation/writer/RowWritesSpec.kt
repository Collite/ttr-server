// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.writer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import org.tatrman.automation.apply.ApplyRow
import org.tatrman.automation.apply.PlanSource
import org.tatrman.automation.bundle.BundleFixture
import org.tatrman.automation.bundle.ManifestVerdictSchema
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.identity.RunAsRef
import org.tatrman.automation.identity.RunIdentity
import org.tatrman.automation.identity.ServiceTokens
import org.tatrman.automation.identity.TokenKind
import org.tatrman.automation.runner.Door
import org.tatrman.automation.runner.DoorWrite
import org.tatrman.automation.runner.DoorWriteFailure
import org.tatrman.automation.runner.QueryDoor
import org.tatrman.automation.runner.QueryRun
import org.tatrman.automation.runner.WriteResult
import org.tatrman.common.v1.ResponseMessage
import org.tatrman.common.v1.Severity
import org.tatrman.plan.v1.RowWriteOp
import org.tatrman.query.v1.RunRequest
import org.tatrman.translate.v1.Language
import org.tatrman.worker.v1.ResultBatch
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** S2.2 T3/T4 units: the §4 → row_writes lowering, the writer's own writable check and the door's failure kinds. */
class RowWritesSpec :
    StringSpec({
        val entities = BundleFixture.load().entities
        val key = buildJsonObject { put("order_number", "4711") }

        fun transition(
            to: String,
            from: List<String> = emptyList(),
        ) = ApplyRow(
            2,
            "er.entity.order",
            "transition",
            key,
            attribute = "state",
            from = from,
            to = JsonPrimitive(to),
            history = true,
        )

        "writable: one allowed, one refused — entity globs and entity.attribute entries" {
            val lowering = RowWrites(entities, emptyList())
            val held = ApplyRow(1, "er.entity.order", "update", key, set = buildJsonObject { put("held_for", "Sales") })
            val customer = ApplyRow(3, "er.entity.order", "update", key, set = buildJsonObject { put("customer", 7) })
            lowering.unwritable(listOf(held, customer), listOf("order.state", "order.held_for")) shouldBe
                listOf(customer)
            RowWrites.matches("*_verdict_current", "release_order_verdict_current") shouldBe true
            RowWrites.matches("*_verdict", "release_order_verdict_current") shouldBe false
        }

        "a transition with its set: is one CAS: values, key + IN (from…, to), read_before = attribute + set columns" {
            val lowering = RowWrites(entities, emptyList())
            val unit =
                lowering.update(
                    listOf(
                        ApplyRow(
                            1,
                            "er.entity.order",
                            "update",
                            key,
                            set = buildJsonObject { put("held_for", "Sales") },
                        ),
                    ),
                    transition("held", listOf("placed")),
                )
            unit.kind shouldBe WriteUnit.Kind.CAS
            unit.seqs shouldBe listOf(1, 2)
            unit.row.op shouldBe RowWriteOp.UPDATE
            unit.row.target.name shouldBe "order"
            unit.row.valuesList.map { it.name } shouldBe listOf("state", "held_for")
            unit.row.readBeforeList shouldBe listOf("state", "held_for")
            unit.row.priorKeysList shouldBe listOf("state", "held_for")
            val predicate = unit.row.where.function
            predicate.operation shouldBe "and"
            predicate.operandsList[1].function.operation shouldBe "in"
            val bound = lowering.parameters.associate { it.name to it.value.stringValue }
            predicate.operandsList[1]
                .function.operandsList
                .drop(1)
                .map { bound.getValue(it.parameter.name) } shouldBe listOf("placed", "held")
        }

        "an undeclared transition is bad-transition before any request is built" {
            shouldThrow<RowRefusal> {
                RowWrites(
                    entities,
                    emptyList(),
                ).update(emptyList(), transition("placed"))
            }.code shouldBe
                "bad-transition"
            shouldThrow<RowRefusal> {
                RowWrites(entities, emptyList()).update(emptyList(), transition("held", listOf("released")))
            }.code shouldBe "bad-transition"
        }

        "a key-scoped current delete is one full-PK statement per declared route" {
            val root =
                org.tatrman.automation.bundle.ApprovalFixture.copyTo(
                    kotlin.io.path
                        .createTempDirectory("rw")
                        .resolve("b"),
                )
            val catalog = BundleFixture.load(root).entities
            val units =
                RowWrites(catalog, listOf("hold_sales", "release")).delete(
                    ApplyRow(4, "er.entity.release_order_verdict_current", "delete", key),
                )
            units.size shouldBe 2
            units.forEach { it.row.where.function.operation shouldBe "and" }
        }

        "the writer reports an unknown target as unknown-entity with no door call" {
            var calls = 0
            val door =
                object : Door {
                    override suspend fun read(read: org.tatrman.automation.runner.DoorRead) = error("unused")

                    override suspend fun write(write: DoorWrite): WriteResult {
                        calls++
                        error("must not be called")
                    }
                }
            val recorded = mutableListOf<RowOutcome>()
            val source =
                PlanSource(
                    ManifestVerdictSchema("release_order_verdict"),
                    emptyMap(),
                    "automation_db",
                    writable =
                        mapOf(
                            "automation_db" to listOf("*_verdict"),
                        ),
                )
            Apply(door, Clock.systemUTC()).apply(
                ApplyInput(
                    UUID.randomUUID(),
                    "demo",
                    RunIdentity(Caller("s", "svc", emptySet(), TokenKind.SERVICE, "t"), RunAsRef.Caller, "svc", null),
                    "fp",
                    Clock.systemUTC().instant().plusSeconds(5),
                    listOf(ApplyRow(1, "er.entity.release_order_verdict", "insert", key, row = key)),
                    emptyList(),
                    source,
                    entities,
                    emptyList(),
                ),
                object : ApplyJournal {
                    override fun begin(seqs: List<Int>) = Unit

                    override fun release(seqs: List<Int>) = Unit

                    override fun record(outcomes: List<RowOutcome>) {
                        recorded += outcomes
                    }
                },
            )
            recorded shouldBe listOf(RowOutcome(1, "failed", "unknown-entity"))
            calls shouldBe 0
        }

        "Door.write: write routes only; worker row failure, lost commit and refusals keep their kinds" {
            fun door(answer: (RunRequest) -> ResultBatch) =
                QueryDoor(
                    object : QueryRun {
                        override fun run(request: RunRequest) = flow { emit(answer(request)) }
                    },
                    mapOf("demo/erp_db" to "read-alias"),
                    ServiceTokens(emptyMap(), { error("unused") }),
                    mapOf("demo/erp_db" to "w-erp"),
                )

            fun failing(text: String) =
                ResultBatch
                    .newBuilder()
                    .addMessages(
                        ResponseMessage
                            .newBuilder()
                            .setSeverity(Severity.ERROR)
                            .setCode("c")
                            .setHumanMessage(text),
                    ).build()
            val unit = RowWrites(entities, emptyList()).update(emptyList(), transition("held", listOf("placed")))

            fun write(storage: String = "erp_db") =
                DoorWrite(
                    UUID.randomUUID(),
                    "t",
                    "demo",
                    storage,
                    org.tatrman.plan.v1.RowWrites
                        .newBuilder()
                        .addRows(unit.row)
                        .build(),
                    emptyList(),
                    RunIdentity(Caller("s", "svc", emptySet(), TokenKind.SERVICE, "t"), RunAsRef.Caller, "svc", null),
                    "fp",
                    Duration.ofSeconds(5),
                )
            var seen: RunRequest? = null
            shouldThrow<DoorWriteFailure.RowFailed> {
                door {
                    seen = it
                    failing("row_writes seq 0: duplicate key")
                }.write(write())
            }.seq shouldBe 0
            val sent = requireNotNull(seen)
            sent.world shouldBe "w-erp"
            sent.sourceLanguage shouldBe Language.REL_NODE
            sent.hasAllowedErReads() shouldBe false
            sent.context.userId shouldBe "svc"
            shouldThrow<DoorWriteFailure.Unknown> {
                door {
                    failing(
                        "row_writes commit: connection lost",
                    )
                }.write(write())
            }
            shouldThrow<DoorWriteFailure.Refused> { door { failing("refused") }.write(write()) }
            shouldThrow<DoorWriteFailure.Refused> {
                door {
                    error(
                        "unused",
                    )
                }.write(write("automation_db"))
            }.code shouldBe
                "storage-unmapped"
        }
    })
