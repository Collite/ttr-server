// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.LocalDate

/**
 * #214 — a relative period word is read by its KIND, for years, months and quarters:
 *  - **deictic** words read against the reference date ("last year", „loni“, "last month", "this quarter");
 *  - **anaphoric** words read against the period of that unit the conversation is about ("the previous year",
 *    „předchozí měsíc“, "the quarter before"), and against the reference date when it names none;
 *  - the **ambiguous** „minulý …“ has both readings, and asks only when they differ.
 *
 * The reference is pinned to 2026-10-14 (Q4, October), the date of the ruling, so the examples read as it
 * wrote them. `context` is what the service passes: the question the span is in, then the earlier
 * questions, newest first.
 */
class PeriodKindsSpec :
    StringSpec({
        val rec = DateRecognizer()
        val ref = LocalDate.of(2026, 10, 14)

        fun year(
            span: String,
            vararg context: String,
        ): Int {
            val r = rec.recognize(span, ref, context = context.toList()).shouldNotBeNull()
            r.startInclusive.dayOfYear shouldBe 1
            r.endExclusive shouldBe r.startInclusive.plusYears(1)
            return r.startInclusive.year
        }

        fun code(
            span: String,
            vararg context: String,
        ): String? = rec.recognize(span, ref, context = context.toList()).shouldNotBeNull().periodCode

        fun asks(
            span: String,
            vararg context: String,
        ): List<String> {
            val r = rec.recognize(span, ref, context = context.toList()).shouldNotBeNull()
            return (listOf(r) + r.alternatives).map { c -> c.periodCode ?: c.startInclusive.year.toString() }
        }

        fun unanchored(
            span: String,
            vararg context: String,
        ): String {
            val reading = rec.read(span, ref, context = context.toList()).shouldNotBeNull()
            withClue("an anaphoric word with no anchor is never read against the reference date") {
                reading.shouldBeInstanceOf<ChronoReading.Unanchored>()
            }
            rec.recognize(span, ref, context = context.toList()).shouldBeNull()
            return (reading as ChronoReading.Unanchored).reason
        }

        // ---- the ruling's examples, word for word ----

        val y2025 = "Marketplace revenue in 2025"
        val cs2025 = "Tržby z tržiště v roce 2025"

        "years: \"the previous year\" after 2025 is 2024; \"last year\" is 2025; „minulý rok“ asks 2025 or 2024" {
            year("the previous year", "And the previous year?", y2025) shouldBe 2024
            year("last year", "And last year?", y2025) shouldBe 2025
            asks("minulý rok", "A minulý rok?", cs2025) shouldBe listOf("2025", "2024")
        }

        "months: \"the month before\" after March 2025 is February 2025; \"last month\" is September 2026" {
            val march = "Marketplace revenue in March 2025"
            code("the month before", "And the month before?", march) shouldBe "202502"
            code("last month", "And last month?", march) shouldBe "202609"
            asks("minulý měsíc", "A minulý měsíc?", "Tržby z tržiště v březnu 2025") shouldBe listOf("202609", "202502")
        }

        "quarters: \"the previous quarter\" after Q2 2025 is Q1 2025; „minulé čtvrtletí“ asks Q3 2026 or Q1 2025" {
            code("the previous quarter", "And the previous quarter?", "Marketplace revenue in Q2 2025") shouldBe
                "2025Q1"
            asks("minulé čtvrtletí", "A minulé čtvrtletí?", "Tržby z tržiště ve 2. čtvrtletí 2025") shouldBe
                listOf("2026Q3", "2025Q1")
        }

        // ---- deictic: the reference date, whatever the conversation says ----

        "every deictic year word reads against the reference date" {
            for ((span, expected) in listOf(
                "this year" to 2026,
                "letos" to 2026,
                "letošní" to 2026,
                "tohoroční" to 2026,
                "last year" to 2025,
                "loni" to 2025,
                "vloni" to 2025,
                "loňský rok" to 2025,
                "minuloroční" to 2025,
                "the year before last" to 2024,
                "předloni" to 2024,
                "předloňský rok" to 2024,
            )) {
                withClue(span) { year(span, "And $span?", "Marketplace revenue in 2022") shouldBe expected }
            }
        }
        "every deictic month and quarter word reads against the reference date" {
            val march = "Marketplace revenue in March 2025"
            val q2 = "Marketplace revenue in Q2 2025"
            code("this month", "And this month?", march) shouldBe "202610"
            code("tento měsíc", "A tento měsíc?", march) shouldBe "202610"
            code("last month", "And last month?", march) shouldBe "202609"
            code("this quarter", "And this quarter?", q2) shouldBe "2026Q4"
            code("toto čtvrtletí", "A toto čtvrtletí?", q2) shouldBe "2026Q4"
            code("last quarter", "And last quarter?", q2) shouldBe "2026Q3"
            code("past quarter", "And the past quarter?", q2) shouldBe "2026Q3"
            code("poslední čtvrtletí", "A poslední čtvrtletí?", q2) shouldBe "2026Q3"
        }
        "two back is deictic too: the month and the quarter before last" {
            code("the month before last", "And the month before last?", "Marketplace revenue in March 2025") shouldBe
                "202608"
            code("the quarter before last", "And the quarter before last?", "Marketplace revenue in Q2 2025") shouldBe
                "2026Q2"
        }

        // ---- anaphoric: the conversation's period of that unit ----

        "every anaphoric year word reads against the year the conversation is about" {
            for (span in listOf("the previous year", "the prior year", "the year before", "the previous fiscal year")) {
                withClue(span) { year(span, "And $span?", y2025) shouldBe 2024 }
            }
            for (span in listOf("předchozí rok", "předešlý rok", "předchozího roku", "v předešlém roce")) {
                withClue(span) { year(span, "A $span?", cs2025) shouldBe 2024 }
            }
        }
        "every anaphoric month word reads against the month the conversation is about" {
            for (span in listOf("the previous month", "the prior month", "the month before", "the month prior")) {
                withClue(span) { code(span, "And $span?", "Marketplace revenue in March 2025") shouldBe "202502" }
            }
            for (span in listOf("předchozí měsíc", "předešlý měsíc", "v předchozím měsíci", "předešlého měsíce")) {
                withClue(span) { code(span, "A $span?", "Tržby z tržiště v březnu 2025") shouldBe "202502" }
            }
        }
        "every anaphoric quarter word reads against the quarter the conversation is about" {
            for (span in listOf("the previous quarter", "the prior quarter", "the quarter before")) {
                withClue(span) { code(span, "And $span?", "Marketplace revenue in Q2 2025") shouldBe "2025Q1" }
            }
            for (span in listOf("předchozí čtvrtletí", "předešlé čtvrtletí", "v předchozím čtvrtletí")) {
                withClue(span) { code(span, "A $span?", "Tržby z tržiště ve 2. čtvrtletí 2025") shouldBe "2025Q1" }
            }
        }
        "a quarter is named in each of the ways a question writes one" {
            for (q in listOf(
                "Marketplace revenue in Q2 2025",
                "Marketplace revenue 2025 Q2",
                "Marketplace revenue in 2025Q2",
                "Marketplace revenue in the second quarter of 2025",
                "Marketplace revenue in the 2nd quarter of 2025",
                "Tržby z tržiště ve 2. čtvrtletí 2025",
                "Tržby z tržiště ve druhém čtvrtletí roku 2025",
            )) {
                withClue(q) { code("the previous quarter", "And the previous quarter?", q) shouldBe "2025Q1" }
            }
        }
        "with no period of that unit in the conversation, an anaphoric word reads against the reference date" {
            year("the previous year", "And the previous year?") shouldBe 2025
            code("the previous month", "And the previous month?", "How many stores do we have?") shouldBe "202609"
            code("předchozí čtvrtletí", "A předchozí čtvrtletí?") shouldBe "2026Q3"
        }
        "the anchor is the conversation's period of the word's OWN unit" {
            withClue("a month with its year names the year") {
                year("the previous year", "And the previous year?", "Marketplace revenue in March 2025") shouldBe 2024
            }
            withClue("a quarter with its year names the year") {
                year("the previous year", "And the previous year?", "Marketplace revenue in Q2 2025") shouldBe 2024
            }
            withClue("a year names no month: the reference's month before") {
                code("the previous month", "And the previous month?", y2025) shouldBe "202609"
            }
        }
        "the nearest question that names a period of the unit decides; questions naming none are passed over" {
            year(
                "the previous year",
                "And the previous year?",
                "By state?",
                y2025,
                "Marketplace revenue in 2023",
            ) shouldBe
                2024
            year("the previous year", "And the previous year?", "Marketplace revenue in 2023", y2025) shouldBe 2022
        }
        "the word is never its own anchor, however the question spaces it" {
            year("the previous  year", "And the previous year?", y2025) shouldBe 2024
            year("the previous year", "And the   previous\tyear?", y2025) shouldBe 2024
        }
        "the question the word is in comes first" {
            year(
                "the previous year",
                "Marketplace revenue in 2025 against the previous year",
                "Revenue in 2020",
            ) shouldBe
                2024
        }
        "a yearless month in the conversation leans on the year before it, the way chrono read it" {
            code(
                "the previous month",
                "And the previous month?",
                "And in April?",
                "Marketplace revenue in March 2025",
            ) shouldBe "202503"
        }
        "the anchor crosses a year boundary" {
            code("the previous month", "And the previous month?", "Marketplace revenue in January 2025") shouldBe
                "202412"
            code("the previous quarter", "And the previous quarter?", "Marketplace revenue in Q1 2025") shouldBe
                "2024Q4"
        }

        // ---- the chain: an earlier question's anaphoric word is read against the questions before it ----

        "an anaphoric word chains" {
            year("the previous year", "And the previous year?", "And the previous year?", y2025) shouldBe 2023
            year(
                "the previous year",
                "And the previous year?",
                "And the previous year?",
                "And the previous year?",
                y2025,
            ) shouldBe 2022
            year("předchozí rok", "A předchozí rok?", "A předchozí rok?", cs2025) shouldBe 2023
            code(
                "the month before",
                "And the month before?",
                "And the month before?",
                "Marketplace revenue in March 2025",
            ) shouldBe "202501"
            code(
                "the previous quarter",
                "And the previous quarter?",
                "And the quarter before?",
                "Revenue in Q2 2025",
            ) shouldBe
                "2024Q4"
        }
        "a deictic word in the chain reads against the reference, and the chain goes on from it" {
            year(
                "the previous year",
                "And the previous year?",
                "And last year?",
                "Marketplace revenue in 2022",
            ) shouldBe
                2024
        }
        "a yearless date leans on a year the chain settled" {
            rec
                .recognize(
                    "v březnu",
                    ref,
                    context = listOf("A v březnu?", "A předchozí rok?", "Tržby z tržiště v roce 2023"),
                ).shouldNotBeNull()
                .periodCode shouldBe "202203"
        }

        // ---- no anchor: the nearest question names several periods, or one it does not say is a year ----

        "several periods in the nearest question: no anchor, and never the reference date" {
            unanchored("the previous year", "And the previous year?", "Marketplace revenue in 2024 and in 2025")
                .let {
                    it shouldContain "2024"
                    it shouldContain "2025"
                }
            unanchored("předchozí rok", "A předchozí rok?", "Porovnej tržby v roce 2024 a v roce 2025")
            unanchored("the month before", "And the month before?", "Revenue in March 2025 vs April 2025")
            unanchored("the previous quarter", "And the previous quarter?", "Revenue in Q1 2025 vs Q2 2025")
        }
        "a four-digit number the question does not say is a year leaves no anchor" {
            // "2024 vs 2025" names two years — but so might "2025" alone, and an amount looks the same. The
            // anaphoric word is left ungrounded rather than read against the reference date.
            unanchored("the previous year", "And the previous year?", "Marketplace revenue 2024 vs 2025")
            unanchored("the previous year", "And the previous year?", "Marketplace revenue 2025")
        }
        "an amount is not a year, so it is no anchor and no obstacle" {
            year("the previous year", "And the previous year?", "Items over 2000 dollars") shouldBe 2025
            year("předchozí rok", "A předchozí rok?", "Položky nad 2000 Kč", cs2025) shouldBe 2024
        }
        "an earlier „minulý rok“ that asked leaves the next anaphoric word with no anchor" {
            // „A minulý rok?“ after 2023 asked 2025 or 2022; the answer is not in the conversation.
            unanchored("předchozí rok", "A předchozí rok?", "A minulý rok?", "Tržby z tržiště v roce 2023")
        }
        "an earlier „minulý rok“ whose readings agreed is the year it was read as" {
            year("předchozí rok", "A předchozí rok?", "Tržby z tržiště minulý rok") shouldBe 2024
        }
        "an anaphoric chain broken by a question with no anchor stays broken" {
            unanchored(
                "the previous year",
                "And the previous year?",
                "And the previous year?",
                "Marketplace revenue in 2024 and in 2025",
            )
        }

        // ---- ambiguous: ask only when the two readings differ ----

        "„minulý rok“ asks in every case form" {
            for (span in listOf("minulý rok", "minulého roku", "v minulém roce", "minulým rokem")) {
                withClue(span) { asks(span, "A $span?", cs2025) shouldBe listOf("2025", "2024") }
            }
        }
        "„minulý rok“ does not ask when the conversation names no year" {
            val r =
                rec
                    .recognize(
                        "minulý rok",
                        ref,
                        context = listOf("A minulý rok?", "Kolik máme prodejen?"),
                    ).shouldNotBeNull()
            r.startInclusive.year shouldBe 2025
            r.alternatives.shouldBeEmpty()
        }
        "„minulý …“ does not ask when the conversation's period is the current one" {
            asks("minulý rok", "A minulý rok?", "Tržby z tržiště v roce 2026") shouldBe listOf("2025")
            asks("minulý rok", "A minulý rok?", "Tržby z tržiště letos") shouldBe listOf("2025")
            asks("minulý měsíc", "A minulý měsíc?", "Tržby z tržiště v říjnu 2026") shouldBe listOf("202609")
            asks("minulé čtvrtletí", "A minulé čtvrtletí?", "Tržby z tržiště za toto čtvrtletí") shouldBe
                listOf("2026Q3")
        }
        "„minulý měsíc“ after a question naming only a year does not ask" {
            asks("minulý měsíc", "A minulý měsíc?", cs2025) shouldBe listOf("202609")
        }
        "„minulý …“ with several periods in the conversation has no anaphoric reading, so it is not grounded" {
            unanchored("minulý rok", "A minulý rok?", "Porovnej tržby v roce 2024 a v roce 2025")
        }
        "a month in an ambiguous year asks for the year" {
            asks("v březnu minulého roku", "A v březnu minulého roku?", "Tržby z tržiště v roce 2023") shouldBe
                listOf("202503", "202203")
            code("v březnu předchozího roku", "A v březnu předchozího roku?", "Tržby z tržiště v roce 2023") shouldBe
                "202203"
            code("loni v březnu", "A loni v březnu?", "Tržby z tržiště v roce 2023") shouldBe "202503"
        }

        // ---- what the kinds leave alone ----

        "an adjective between the scope word and its noun keeps the kind" {
            code("last full month", "And last full month?", "Marketplace revenue in March 2025") shouldBe "202609"
            code("the previous calendar month", "And the previous calendar month?", "Revenue in March 2025") shouldBe
                "202502"
            code("the last complete quarter", "And the last complete quarter?", "Revenue in Q2 2025") shouldBe "2026Q3"
            asks("minulý celý rok", "A minulý celý rok?", cs2025) shouldBe listOf("2025", "2024")
            year("předchozí fiskální rok", "A předchozí fiskální rok?", cs2025) shouldBe 2024
        }

        "a week keeps reading against the reference date" {
            rec
                .recognize("the previous week", ref, context = listOf("And the previous week?", y2025))
                .shouldNotBeNull()
                .startInclusive shouldBe LocalDate.of(2026, 10, 5)
        }
        "a rolling window is not a period word" {
            val r =
                rec
                    .recognize(
                        "posledních 3 měsíců",
                        ref,
                        context = listOf("Tržby z tržiště v březnu 2025"),
                    ).shouldNotBeNull()
            r.startInclusive shouldBe LocalDate.of(2026, 7, 14)
            r.alternatives.shouldBeEmpty()
        }
        "a stated year still wins over a scope word beside it" {
            year("previous year 2023", "And previous year 2023?", y2025) shouldBe 2023
        }
    })
