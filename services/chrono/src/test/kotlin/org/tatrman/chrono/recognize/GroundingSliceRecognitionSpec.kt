// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.recognize

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.tatrman.grounding.lexicon.GroundingSlice
import org.tatrman.grounding.lexicon.GroundingTerm
import org.tatrman.grounding.lexicon.TriggerMethod
import org.tatrman.text.Normalization
import java.time.LocalDate

/**
 * RV-P1.6 T4 (RV-42) — the `ground:chrono` slice, at the recognizer.
 *
 * The two claims that matter, and the order they matter in:
 *
 *  1. **Nothing regresses.** With no slice, every recognition is byte-for-byte what it was — which
 *     is why the whole pre-existing `DateRecognizerSpec` runs against the no-slice default and
 *     stays green untouched. The cases here pin the boundary from the other side.
 *  2. **A slice term grounds what the hardcoded list did not.** "fiskální rok" with no year fell
 *     through before: the fiscal rule needs four digits and the relative rule needs a scope word.
 *     Declared as a trigger, it reads as the fiscal year at the reference.
 *
 * The slice never says what a span MEANS — every interval below is still the kernel's rule.
 */
class GroundingSliceRecognitionSpec :
    StringSpec({

        val recognizer = DateRecognizer()
        val reference = LocalDate.of(2026, 5, 12)

        fun slice(vararg terms: Pair<String, TriggerMethod>) =
            GroundingSlice(
                kind = "chrono",
                terms = terms.map { (t, m) -> GroundingTerm(Normalization.fold(t), t, m, "cs") },
                version = "sha256:test",
            )

        val stdlibish =
            slice(
                "rok" to TriggerMethod.Typos(1),
                "měsíc" to TriggerMethod.Typos(1),
                "čtvrtletí" to TriggerMethod.Typos(1),
                "fiskální rok" to TriggerMethod.Tokens,
            )

        // ---- 1. no slice ⇒ the pre-RV recognizer ------------------------------------------------

        "without a slice, a scopeless mention still falls through (the pre-RV behaviour)" {
            recognizer.recognize("fiskální rok", reference) shouldBe null
            recognizer.recognize("rok", reference) shouldBe null
            recognizer.recognize("čtvrtletí", reference) shouldBe null
        }

        "without a slice, every rule that used to fire still fires identically" {
            val scoped = recognizer.recognize("minulý měsíc", reference)
            scoped shouldNotBe null
            scoped!!.kind shouldBe ChronoKind.RELATIVE
            scoped.confidence shouldBe 0.9
            scoped.startInclusive shouldBe LocalDate.of(2026, 4, 1)

            val explicitFiscal = recognizer.recognize("fiskální rok 2026", reference)
            explicitFiscal!!.kind shouldBe ChronoKind.FISCAL_YEAR
            explicitFiscal.confidence shouldBe 0.95
        }

        // ---- 2. a slice term grounds what the list did not --------------------------------------

        "a declared `fiskální rok` grounds as the fiscal year at the reference" {
            val r = recognizer.recognize("fiskální rok", reference, stdlibish)

            r shouldNotBe null
            r!!.kind shouldBe ChronoKind.FISCAL_YEAR
            r.startInclusive shouldBe LocalDate.of(2026, 1, 1)
            r.endExclusive shouldBe LocalDate.of(2027, 1, 1)
            // Inferred scope is weaker evidence than an authored "tento"/"minulý" — but above
            // chrono's 0.6 clarification floor, because the estate declared the word.
            r.confidence shouldBe 0.8
        }

        "a declared bare `měsíc` reads as the current month, with a period code" {
            val r = recognizer.recognize("měsíc", reference, stdlibish)

            r!!.startInclusive shouldBe LocalDate.of(2026, 5, 1)
            r.endExclusive shouldBe LocalDate.of(2026, 6, 1)
            r.periodCode shouldBe "202605"
            r.kind shouldBe ChronoKind.RELATIVE
        }

        "a scope word still wins over the inferred one" {
            val r = recognizer.recognize("minulý rok", reference, stdlibish)

            r!!.startInclusive shouldBe LocalDate.of(2025, 1, 1)
            r.confidence shouldBe 0.9 // authored scope, not inferred
        }

        "an explicit year still wins over the trigger reading" {
            val r = recognizer.recognize("fiskální rok 2024", reference, stdlibish)

            r!!.startInclusive shouldBe LocalDate.of(2024, 1, 1)
            r.confidence shouldBe 0.95
        }

        "a trigger does not make a NON-time span groundable" {
            // The slice answers "is this span mine?", not "what does it mean?". A span that
            // carries a trigger but names no unit the kernel knows still falls through, because
            // there is no rule to read it with.
            recognizer.recognize("tržby podle prodejen", reference, stdlibish) shouldBe null
        }

        "an empty slice is exactly the no-slice case" {
            recognizer.recognize("fiskální rok", reference, GroundingSlice.empty("chrono")) shouldBe null
        }

        // ---- quarter: the third scopeless period, and the one the KDoc promised ----------------

        "a declared bare `čtvrtletí` reads as the current quarter, with a period code" {
            val r = recognizer.recognize("čtvrtletí", reference, stdlibish)

            r shouldNotBe null
            r!!.kind shouldBe ChronoKind.PERIOD
            r.startInclusive shouldBe LocalDate.of(2026, 4, 1) // the reference's own quarter
            r.endExclusive shouldBe LocalDate.of(2026, 7, 1)
            r.periodCode shouldBe "2026Q2"
            r.confidence shouldBe 0.8 // inferred scope, same rung as a scopeless month or year
        }

        "an authored quarter scope still wins, at full confidence" {
            val r = recognizer.recognize("poslední čtvrtletí", reference, stdlibish)

            r!!.periodCode shouldBe "2026Q1"
            r.confidence shouldBe 0.9
        }

        "an AMBIGUOUS quarter scope is still not a match, trigger or no trigger" {
            recognizer.recognize("tento poslední čtvrtletí", reference, stdlibish) shouldBe null
        }

        "a scopeless week is scored like the other inferred periods, not above them" {
            val r = recognizer.recognize("týden", reference, slice("týden" to TriggerMethod.Typos(1)))

            r!!.confidence shouldBe 0.8
        }

        // ---- a span that states its own year is not a bare mention (#213) ----------------------
        // "Marketplace revenue for the year 2025" grounded `the year 2025` to the CURRENT year: the
        // `year` trigger made the span scopeless-but-mine, and the relative rule read that as the
        // reference's year before the calendar-year rule could read the 2025 the span states.

        val withEnglish =
            GroundingSlice(
                kind = "chrono",
                terms =
                    stdlibish.terms +
                        listOf("roku", "roce", "týden").map {
                            GroundingTerm(Normalization.fold(it), it, TriggerMethod.Typos(1), "cs")
                        } +
                        listOf("year", "month", "week", "quarter").map {
                            GroundingTerm(it, it, TriggerMethod.Typos(1), "en")
                        } +
                        GroundingTerm("fiscal year", "fiscal year", TriggerMethod.Tokens, "en"),
                version = "sha256:test",
            )

        "a span that states its year grounds that year, not the reference's (#213)" {
            for (span in listOf(
                "the year 2025",
                "year 2025",
                "for the year 2025",
                "in the year 2025",
                "calendar year 2025",
                "rok 2025",
                "roku 2025",
                "v roce 2025",
                "za rok 2025",
            )) {
                withClue(span) {
                    withEnglish.matches(span) shouldBe true // the trigger fires: the slice is in play
                    val r = recognizer.recognize(span, reference, withEnglish)
                    r shouldNotBe null
                    r!!.startInclusive shouldBe LocalDate.of(2025, 1, 1)
                    r.endExclusive shouldBe LocalDate.of(2026, 1, 1)
                    r.kind shouldBe ChronoKind.CALENDAR_YEAR
                }
            }
        }

        "with a stated year, the slice changes nothing: the span reads as it does without one" {
            for (span in listOf(
                "the year 2025",
                "rok 2025",
                "2025 year to date",
                "year to date 2025",
                "week 12 of 2025",
                "the 12th week of 2025",
                "month 3 of 2025",
                "quarter 2 of 2025",
                "the second quarter of 2025",
            )) {
                withClue(span) {
                    recognizer.recognize(span, reference, withEnglish) shouldBe recognizer.recognize(span, reference)
                }
            }
        }

        "a period of a stated year is ungrounded with the slice too, never the reference's period" {
            // The live shapes: with the slice, each of these was a period around the reference
            // ("the second quarter of 2025" was 2026Q2 here, "the last week of 2025" the week before).
            for (span in listOf(
                "week 12 of 2025",
                "month 3 of 2025",
                "quarter 2 of 2025",
                "quarter in 2025",
                "the second quarter of 2025",
                "the last quarter of 2025",
                "the second week of 2025",
                "the last week of 2025",
                "the first month of 2025",
                "the last month of 2025",
                "the second half of 2025",
                "poslední čtvrtletí 2025",
                "poslední měsíc roku 2025",
            )) {
                withClue(span) { recognizer.recognize(span, reference, withEnglish) shouldBe null }
            }
        }

        "a period with no year of its own still reads at the reference, with the slice" {
            recognizer.recognize("last week", reference, withEnglish)!!.startInclusive shouldBe LocalDate.of(2026, 5, 4)
            recognizer.recognize("this month", reference, withEnglish)!!.periodCode shouldBe "202605"
            recognizer.recognize("minulý měsíc", reference, withEnglish)!!.periodCode shouldBe "202604"
            recognizer.recognize("letos po měsících", reference, withEnglish)!!.periodCode shouldBe "202605"
        }

        "a bare mention with no year of its own still reads at the reference" {
            for ((span, kind) in listOf(
                "fiscal year" to ChronoKind.FISCAL_YEAR,
                "the fiscal year" to ChronoKind.FISCAL_YEAR,
                "fiskální rok" to ChronoKind.FISCAL_YEAR,
                "the year" to ChronoKind.RELATIVE,
                "rok" to ChronoKind.RELATIVE,
            )) {
                withClue(span) {
                    val r = recognizer.recognize(span, reference, withEnglish)
                    r shouldNotBe null
                    r!!.startInclusive shouldBe LocalDate.of(2026, 1, 1)
                    r.endExclusive shouldBe LocalDate.of(2027, 1, 1)
                    r.kind shouldBe kind
                    r.confidence shouldBe 0.8
                }
            }
            recognizer.recognize("week", reference, withEnglish)!!.startInclusive shouldBe LocalDate.of(2026, 5, 11)
            recognizer.recognize("quarter", reference, withEnglish)!!.periodCode shouldBe "2026Q2"
        }

        "a scoped year is unchanged by the slice" {
            for ((span, year) in listOf(
                "this year" to 2026,
                "last year" to 2025,
                "letos" to 2026,
                "loni" to 2025,
                "minulý rok" to 2025,
            )) {
                withClue(span) {
                    val r = recognizer.recognize(span, reference, withEnglish)
                    r!!.startInclusive shouldBe LocalDate.of(year, 1, 1)
                    r.confidence shouldBe 0.9
                }
            }
        }

        "a stated fiscal year is still the fiscal-year rule's" {
            for (span in listOf("fiscal year 2025", "fiskální rok 2025")) {
                withClue(span) {
                    val r = recognizer.recognize(span, reference, withEnglish)
                    r!!.kind shouldBe ChronoKind.FISCAL_YEAR
                    r.startInclusive shouldBe LocalDate.of(2025, 1, 1)
                    r.confidence shouldBe 0.95
                }
            }
        }
    })
