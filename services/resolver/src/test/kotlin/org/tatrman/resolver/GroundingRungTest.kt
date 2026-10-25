// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import org.tatrman.grounding.v1.ClarificationOption
import org.tatrman.grounding.v1.DateTimeInterval
import org.tatrman.grounding.v1.FilterRecipe
import org.tatrman.grounding.v1.GetStatusResponse
import org.tatrman.grounding.v1.GroundRequest
import org.tatrman.grounding.v1.GroundResponse
import org.tatrman.grounding.v1.GroundingResult
import org.tatrman.grounding.v1.Normalized
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.resolver.client.GroundingClient
import org.tatrman.resolver.pipeline.GroundingRung
import org.tatrman.resolver.pipeline.UniversalBinding
import org.tatrman.resolver.v1.UniversalEntityType

/**
 * ✅ **R1** — the grounding rung.
 *
 * The rung's whole value is the ANCHOR COLUMN: without it a grounded interval is still something
 * nothing can be filtered by, which is the state that made the door refuse every dated question.
 * So the tests below are mostly about the shapes in which that column can fail to arrive, because
 * each of them must degrade to "ungrounded" rather than to a confident wrong column.
 */
private fun date(
    text: String,
    start: Int,
    end: Int,
    normalized: String = "",
) = UniversalBinding(
    start = start,
    end = end,
    text = text,
    entityType = UniversalEntityType.DATE,
    rawText = text,
    normalizedValue = normalized,
    sourceEngine = "stanza",
)

private fun okWith(
    start: String,
    end: String,
    anchor: QualifiedName? = null,
): GroundResponse {
    val result =
        GroundingResult
            .newBuilder()
            .setNormalized(
                Normalized
                    .newBuilder()
                    .setInterval(DateTimeInterval.newBuilder().setStart(start).setEnd(end)),
            )
    if (anchor != null) {
        result.setFilter(FilterRecipe.newBuilder().setAnchorColumn(anchor))
    }
    return GroundResponse
        .newBuilder()
        .setStatus(GroundResponse.Status.OK)
        .setResult(result)
        .build()
}

private fun erColumn(
    entity: String,
    attribute: String,
) = QualifiedName
    .newBuilder()
    .setPackage("hartland")
    .setSchemaCode(SchemaCode.ER)
    // ⛑ The wire shape, quoted from a live hartland exchange rather than inferred:
    //     anchor_column { schema_code: ER namespace: "entity" name: "date_dim.cal_date" }
    // The ENTITY is the first half of `name`; the namespace is the bare object class. Two
    // earlier versions of this fake guessed otherwise (`date_dim`, then `entity.date_dim`) and
    // each time the suite passed while hartland refused the question.
    .setNamespace("entity")
    .setName("$entity.$attribute")
    .build()

private fun month(
    id: String,
    label: String,
    start: String,
    end: String,
) = ClarificationOption
    .newBuilder()
    .setId(id)
    .setLabel(label)
    .setNormalized(Normalized.newBuilder().setInterval(DateTimeInterval.newBuilder().setStart(start).setEnd(end)))
    .build()

private class FakeKernel(
    val answer: (GroundRequest) -> GroundResponse,
) : GroundingClient {
    val seen = mutableListOf<GroundRequest>()

    override suspend fun ground(request: GroundRequest): GroundResponse {
        seen += request
        return answer(request)
    }

    override suspend fun getStatus(): GetStatusResponse = GetStatusResponse.getDefaultInstance()
}

class GroundingRungTest :
    StringSpec({

        "⚑ a grounded date yields the interval AND the attribute to apply it to" {
            val kernel = FakeKernel { okWith("2025-01-01", "2026-01-01", erColumn("date_dim", "cal_date")) }
            val out =
                runBlocking {
                    GroundingRung.ground(
                        kernel,
                        listOf(date("2025", 36, 40)),
                        "Why did revenue drop in 2025?",
                        "hartland",
                        "",
                    )
                }
            out shouldContainKey (36 to 40)
            val g = out.getValue(36 to 40)
            withClue(
                "the ref grammar is er.entity.<entity>.<attribute> — TransDslRenderer.address parses exactly this",
            ) {
                g.anchorAttributeRef shouldBe "er.entity.date_dim.cal_date"
            }
            g.intervalStart shouldBe "2025-01-01"
            withClue("end is EXCLUSIVE and must survive as such — a closed range here double-counts New Year") {
                g.intervalEndExclusive shouldBe "2026-01-01"
            }
            g.normalizedValue shouldBe "2025-01-01/2026-01-01"
        }

        // The live regression (hartland, 2026-08-31). metadata hands back
        // `namespace = "entity.date_dim"`, and the ref used to be built as
        // "er.entity.${namespace}.${name}" — one `entity.` too many. Golem read the extra
        // segment as a second entity, found no relation to it, and refused a question it had
        // otherwise resolved perfectly. Asserted on the RAW QualifiedName rather than through
        // the `erColumn` helper, so a future edit to that helper cannot quietly restate the bug.
        "the anchor ref is built from the wire's namespace, not by re-prefixing it" {
            val fromMetadata =
                QualifiedName
                    .newBuilder()
                    .setPackage("hartland")
                    .setSchemaCode(SchemaCode.ER)
                    .setNamespace("entity")
                    .setName("date_dim.cal_date")
                    .build()
            val kernel = FakeKernel { okWith("2025-01-01", "2026-01-01", fromMetadata) }
            val g =
                runBlocking {
                    GroundingRung.ground(kernel, listOf(date("2025", 0, 4)), "q", "hartland", "")
                }.getValue(0 to 4)
            g.anchorAttributeRef shouldBe "er.entity.date_dim.cal_date"
            withClue("the doubled segment is the exact regression") {
                g.anchorAttributeRef shouldNotContain "entity.entity"
            }
        }

        // The other half of the guard: an unknown namespace shape degrades to unanchored rather
        // than emitting a ref that names an entity nobody declared. A refusal for want of a
        // column is debuggable; a refusal for want of a relation to a phantom entity is not.
        "a name missing its entity half yields no ref at all" {
            val odd =
                QualifiedName
                    .newBuilder()
                    .setPackage("hartland")
                    .setSchemaCode(SchemaCode.ER)
                    .setNamespace("entity")
                    .setName("cal_date")
                    .build()
            val kernel = FakeKernel { okWith("2025-01-01", "2026-01-01", odd) }
            val g =
                runBlocking {
                    GroundingRung.ground(kernel, listOf(date("2025", 0, 4)), "q", "hartland", "")
                }.getValue(0 to 4)
            g.anchorAttributeRef shouldBe ""
            withClue("the interval still survives — only the column is missing") {
                g.intervalStart shouldBe "2025-01-01"
            }
        }

        "the request carries what the kernel cannot read for itself" {
            val kernel = FakeKernel { okWith("2025-01-01", "2026-01-01", erColumn("date_dim", "cal_date")) }
            runBlocking {
                GroundingRung.ground(
                    kernel,
                    listOf(date("2025", 0, 4)),
                    "q",
                    "hartland",
                    "2026-08-28T00:00:00Z",
                    locale = "en",
                )
            }
            val req = kernel.seen.single()
            req.getPackage() shouldBe "hartland"
            withClue(
                "the kernel is forbidden from reading a clock — an empty reference is a silent relative-date failure",
            ) {
                req.context.referenceDatetime shouldBe "2026-08-28T00:00:00Z"
            }
            req.spanText shouldBe "2025"
            withClue("no conversation was given, so none is invented") {
                req.context.priorQuestionsList shouldBe
                    emptyList()
            }
        }

        "the caller's earlier questions reach the kernel as given, newest first" {
            val kernel = FakeKernel { okWith("2025-10-01", "2025-11-01", erColumn("date_dim", "cal_date")) }
            val earlier = listOf("Which products sold out in October 2025?", "Store sales by city in 2025")
            runBlocking {
                GroundingRung.ground(
                    kernel,
                    listOf(date("October", 21, 28)),
                    "And all of October?",
                    "hartland",
                    "2026-10-12T00:00:00Z",
                    priorQuestions = earlier,
                )
            }
            withClue("the kernel reads a yearless month against these — the rung only carries them") {
                kernel.seen
                    .single()
                    .context.priorQuestionsList shouldBe earlier
            }
        }

        "⚑ a DB-coded anchor is REFUSED as an attribution, but the interval survives" {
            // The trap this guards: `db.dbo.date_dim.d_date` is a physical column, and an
            // attribution is a statement about the MODEL. Mapping one to the other here would be
            // inventing the er2db binding the estate is supposed to declare. Degrading to
            // "grounded but unanchored" keeps the door's refusal — which is the honest outcome.
            val physical =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(SchemaCode.DB)
                    .setNamespace("dbo")
                    .setName("d_date")
                    .build()
            val kernel = FakeKernel { okWith("2025-01-01", "2026-01-01", physical) }
            val g =
                runBlocking {
                    GroundingRung.ground(kernel, listOf(date("2025", 0, 4)), "q", "hartland", "")
                }.getValue(0 to 4)
            g.anchorAttributeRef shouldBe ""
            withClue("the interval still improves the refusal message from `time grain ()` to a named grain") {
                g.normalizedValue shouldBe "2025-01-01/2026-01-01"
            }
        }

        "a kernel that grounds but names no column yields no attribution" {
            // The live hartland state before the model gained `role: event_date`.
            val kernel = FakeKernel { okWith("2025-01-01", "2026-01-01", anchor = null) }
            runBlocking {
                GroundingRung.ground(kernel, listOf(date("2025", 0, 4)), "q", "hartland", "")
            }.getValue(0 to 4).anchorAttributeRef shouldBe ""
        }

        "UNGROUNDABLE contributes nothing" {
            val kernel =
                FakeKernel {
                    GroundResponse.newBuilder().setStatus(GroundResponse.Status.UNGROUNDABLE).build()
                }
            runBlocking {
                GroundingRung.ground(kernel, listOf(date("blursday", 0, 8)), "q", "hartland", "")
            }.shouldBeEmpty()
        }

        "AWAITING_CLARIFICATION grounds nothing — it is ASKED, with the question it was asked (ttr-server#231)" {
            // It used to be dropped as "not grounded", so the caller got a date with no range and
            // nothing to ask. Now the readings come back as a question the resolver asks under its own
            // token, and the exact kernel question rides along so a pick can be put back unchanged.
            val kernel =
                FakeKernel {
                    GroundResponse
                        .newBuilder()
                        .setStatus(GroundResponse.Status.AWAITING_CLARIFICATION)
                        .addOptions(month("202605", "May 2026", "2026-05-01", "2026-06-01"))
                        .addOptions(month("202505", "May 2025", "2025-05-01", "2025-06-01"))
                        .build()
                }
            val out =
                runBlocking {
                    GroundingRung.groundAll(
                        kernel,
                        listOf(date("May", 11, 14)),
                        "Revenue in May",
                        "hartland",
                        "2026-04-10T00:00:00Z",
                        "en",
                        listOf("Revenue in 2025"),
                    )
                }
            out.grounded.shouldBeEmpty()
            val asked = out.asked.getValue(11 to 14)
            asked.options.map { it.id } shouldBe listOf("202605", "202505")
            asked.options.map { it.normalizedValue } shouldBe listOf("2026-05-01/2026-06-01", "2025-05-01/2025-06-01")
            asked.question.spanText shouldBe "May"
            asked.question.questionText shouldBe "Revenue in May"
            asked.question.referenceDatetime shouldBe "2026-04-10T00:00:00Z"
            asked.question.priorQuestions shouldBe listOf("Revenue in 2025")
            asked.question.pkg shouldBe "hartland"
            asked.question.locale shouldBe "en"
            // …and `ground`, the grounded half alone, is what it always was for such a span.
            runBlocking {
                GroundingRung.ground(kernel, listOf(date("May", 11, 14)), "q", "hartland", "")
            }.shouldBeEmpty()
        }

        "an AWAITING with no options is no question: the span stays ungrounded" {
            val kernel =
                FakeKernel {
                    GroundResponse.newBuilder().setStatus(GroundResponse.Status.AWAITING_CLARIFICATION).build()
                }
            val out = runBlocking { GroundingRung.groundAll(kernel, listOf(date("May", 0, 3)), "q", "hartland", "x") }
            out.grounded.shouldBeEmpty()
            out.asked.shouldBeEmpty()
        }

        "⚑ a kernel that THROWS leaves the turn intact — and the other spans still ground" {
            // Fail-open is the whole posture: an optional rung that could make a turn worse than
            // not running it would not be optional. One bad span must not cost the good one.
            val kernel =
                FakeKernel { req ->
                    if (req.spanText ==
                        "boom"
                    ) {
                        error("kernel down")
                    } else {
                        okWith("2025-01-01", "2026-01-01", erColumn("date_dim", "cal_date"))
                    }
                }
            val out =
                runBlocking {
                    GroundingRung.ground(kernel, listOf(date("boom", 0, 4), date("2025", 5, 9)), "q", "hartland", "")
                }
            out.keys shouldBe setOf(5 to 9)
        }

        "no package ⇒ the rung does not run at all" {
            // Asking with an empty scope is not a cheaper question, it is a meaningless one:
            // chrono would have nothing to scan for a period table or an anchor column.
            val kernel = FakeKernel { okWith("2025-01-01", "2026-01-01", erColumn("date_dim", "cal_date")) }
            runBlocking { GroundingRung.ground(kernel, listOf(date("2025", 0, 4)), "q", "", "") }.shouldBeEmpty()
            kernel.seen.size shouldBe 0
        }

        "only DATE universals are offered" {
            val money =
                UniversalBinding(0, 5, "$100", UniversalEntityType.MONEY, "$100", "100", "stanza")
            val person =
                UniversalBinding(6, 9, "Bob", UniversalEntityType.PERSON, "Bob", "", "stanza")
            val kernel = FakeKernel { okWith("2025-01-01", "2026-01-01", erColumn("date_dim", "cal_date")) }
            runBlocking { GroundingRung.ground(kernel, listOf(money, person), "q", "hartland", "") }.shouldBeEmpty()
            withClue("a span the kernel was never asked about cannot cost a round trip") {
                kernel.seen.size shouldBe 0
            }
        }
    })
