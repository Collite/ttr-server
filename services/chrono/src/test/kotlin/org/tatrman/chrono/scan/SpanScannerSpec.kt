// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.scan

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.tatrman.grounding.lexicon.GroundingSlice
import org.tatrman.grounding.lexicon.GroundingTerm
import org.tatrman.grounding.lexicon.TriggerMethod
import java.text.Normalizer
import java.time.LocalDate

/**
 * TS-P0 S0.3 T2 (contracts §3, §8) — what `Scan` finds in a question: the spans chrono reads, tight, maximal and
 * non-overlapping, with UTF-16 offsets into the text as sent. Reference 2026-11-01.
 */
class SpanScannerSpec :
    StringSpec({
        val scanner = SpanScanner()
        val ref = LocalDate.of(2026, 11, 1)

        fun spans(
            text: String,
            prior: List<String> = emptyList(),
        ): List<String> = scanner.scan(text, ref, priorQuestions = prior).map { it.text }

        fun at(
            text: String,
            phrase: String,
        ): Pair<Int, Int> = text.indexOf(phrase).let { it to it + phrase.length }

        "the five sentences of the resolver's regex give exactly its spans, with offsets" {
            for ((text, phrase) in listOf(
                "Tržby z tržiště za minulý měsíc pro Brno DC" to "minulý měsíc",
                "Kolik jsme prodali v minulém měsíci?" to "minulém měsíci",
                "Tržby z webu tohoto měsíce" to "tohoto měsíce",
                "Vratky v tomto roce" to "tomto roce",
                "Tržby tento týden" to "tento týden",
            )) {
                val found = scanner.scan(text, ref)
                found.map { it.text } shouldBe listOf(phrase)
                (found.single().start to found.single().end) shouldBe at(text, phrase)
            }
            // the DONE example: one span, "minulý měsíc", UTF-16 [19, 31)
            scanner.scan("Tržby z tržiště za minulý měsíc pro Brno DC", ref).single().let {
                (it.start to it.end) shouldBe (19 to 31)
                it.confidence shouldBe 0.9
                it.trigger shouldBe "relative"
            }
        }

        "an absolute date beside a relative phrase gives both, apart" {
            val text = "Faktury vystavené 15. 3. 2026 splatné tento týden"
            spans(text) shouldBe listOf("15. 3. 2026", "tento týden")
        }

        "a month with its year is one span, not the year alone" {
            spans("Tržby v říjnu 2025 po prodejnách") shouldBe listOf("říjnu 2025")
            spans("Tržby za říjen 2025 a listopad 2025") shouldBe listOf("říjen 2025", "listopad 2025")
        }

        "R230 R3 joined English months are scanned separately" {
            spans("Sales in May and June") shouldBe listOf("May", "June")
            spans("Sales in January and March") shouldBe listOf("January", "March")
            spans("Q2 2025 sales may rise") shouldBe listOf("Q2 2025")
            spans("May we see revenue for March") shouldBe listOf("March")
        }

        "R230 R4 punctuation preserves month parts instead of leaking their year" {
            for (text in listOf(
                "Revenue Jan-Mar 2025", "Revenue January–March 2025", "Tržby leden–březen 2025",
                "Revenue in March/April 2025", "Tržby leden/únor 2025",
            )) { spans(text).shouldBeEmpty() }
            scanner.scan("Revenue mid-March 2025", ref).single().text.contains("March") shouldBe true
            spans("October's revenue") shouldBe listOf("October's")
            spans("Počet zařízení v Q2 2025") shouldBe listOf("Q2 2025")
            spans("Kolik objednal Jan Novák").shouldBeEmpty()
        }

        "a bare period noun is no span" {
            spans("Tržby z tržiště po čtvrtletích").shouldBeEmpty()
            spans("Kolik je dní v měsíci").shouldBeEmpty()
            spans("roční tržby podle prodejny").shouldBeEmpty()
        }

        "a six-digit document number is no span; with the word for a period, or alone, it is one" {
            spans("Doklad 200312 je zaplacen").shouldBeEmpty()
            spans("Tržby za období 202605") shouldBe listOf("období 202605")
            spans("202605") shouldBe listOf("202605")
        }

        "a bare four-digit year is a span: the consumer decides (⚑TS-4)" {
            spans("Tržby v roce 2025 po měsících") shouldBe listOf("roce 2025")
        }

        "a window that names no one period gives no part of it" {
            spans("Tržby za 1. a 2. čtvrtletí").shouldBeEmpty()
            spans("Revenue next Q2 by region").shouldBeEmpty()
            spans("Revenue in 2025 excluding Q4").shouldBeEmpty()
            spans("Tržby za leden až březen 2025").shouldBeEmpty()
            spans("Q2 FY25 revenue").shouldBeEmpty()
        }

        "a breakdown or a target word stays outside the span" {
            spans("Tržby za minulý měsíc po týdnech") shouldBe listOf("minulý měsíc")
            spans("Faktury splatné tento týden") shouldBe listOf("tento týden")
            spans("Revenue last month by week") shouldBe listOf("last month")
        }

        "a span read against the conversation, with nothing to read against, is still a span" {
            spans("A předchozí čtvrtletí?") shouldBe listOf("předchozí čtvrtletí")
        }

        "a yearless period leans on the question's year, as Ground reads it" {
            val found = scanner.scan("Q2 sales last year by region", ref)
            found.map { it.text } shouldBe listOf("Q2 sales last year")
        }

        "a phrase keeps its own words: a period noun after it, a time of its own before it" {
            spans("Tržby za loňského roku po měsících") shouldBe listOf("loňského roku")
            spans("Q2 of the year by region") shouldBe listOf("Q2 of the year")
            spans("Tržby od začátku roku 2025 do dneška") shouldBe listOf("od začátku roku 2025 do dneška")
            spans("Tržby od začátku roku do dneška") shouldBe listOf("od začátku roku do dneška")
            spans("Revenue fiscal year 2025 Q4") shouldBe listOf("fiscal year 2025 Q4")
        }

        "a word with a month inside, a name and a verb are no month" {
            spans("Počet zařízení v Q2 2025") shouldBe listOf("Q2 2025")
            spans("Kolik objednal Jan Novák") shouldBe emptyList()
            spans("May we see revenue for March") shouldBe listOf("March")
            spans("Q2 2025 sales may rise") shouldBe listOf("Q2 2025")
            spans("What were the May sales") shouldBe listOf("May")
            spans("Tržby za květnové období") shouldBe listOf("květnové období")
            spans("Revenue on Jan 15 2025") shouldBe listOf("Jan 15 2025")
        }

        "a phrase that names no one period over more than five words keeps its parts out" {
            spans("Q2 vs the same quarter last year").shouldBeEmpty()
            spans("Tržby od roku 2020 ve 2. čtvrtletí").shouldBeEmpty()
            spans("Q4 of fiscal year next year").shouldBeEmpty()
        }

        "a span naming several periods gives its parts when each is one on its own, else nothing" {
            spans("Revenue Q2 2025 vs Q2 2024") shouldBe listOf("Q2 2025", "Q2 2024")
            spans("Revenue this quarter and last month") shouldBe listOf("this quarter", "last month")
            spans("Tržby letos a loni") shouldBe listOf("letos", "loni")
            spans("Tržby za 1. pololetí letos a loni").shouldBeEmpty()
            spans("Revenue in Q2 this year vs last year").shouldBeEmpty()
            spans("Tržby za minulý a tento měsíc").shouldBeEmpty()
        }

        "a year no word places is a span only as the bare number" {
            spans("Q2 last year for 2000 customers") shouldBe listOf("Q2 last year", "2000")
        }

        "the T8–T10 readings hold through Scan" {
            for (text in listOf(
                "Revenue since Q2",
                "Q2 revenue since 2020",
                "Tržby za 2. čtvrtletí o rok dříve",
                "Q2 in previous years",
                "Revenue for the last week of the month",
                "Tržby ve 2. čtvrtletích",
            )) {
                spans(text).shouldBeEmpty()
            }
        }

        "a quoted literal the caller blanked gives nothing" {
            val blanked = "Kolik objednal zákazník \"    \" v tržišti?"
            spans(blanked).shouldBeEmpty()
        }

        "offsets are UTF-16 into the text as sent: ě, š, ů, a decomposed letter and a surrogate pair" {
            val emoji = "Tržby 📈 za minulý měsíc"
            scanner.scan(emoji, ref).single().let {
                (it.start to it.end) shouldBe at(emoji, "minulý měsíc")
                emoji.substring(it.start, it.end) shouldBe "minulý měsíc"
            }
            val accents = "Vratky šperků v tomto roce, ů"
            scanner.scan(accents, ref).single().let { (it.start to it.end) shouldBe at(accents, "tomto roce") }
            val nfd = Normalizer.normalize("Tržby za minulý měsíc", Normalizer.Form.NFD)
            scanner.scan(nfd, ref).single().let {
                it.text shouldBe Normalizer.normalize("minulý měsíc", Normalizer.Form.NFD)
                it.end shouldBe nfd.length
            }
        }

        "a slice term that fired is the trigger" {
            val slice =
                GroundingSlice(
                    "chrono",
                    listOf(GroundingTerm("fiskalni rok", "fiskální rok", TriggerMethod.Tokens, "cs")),
                    "v1",
                )
            val found = scanner.scan("Tržby za fiskální rok 2026", ref, slice)
            found.map { it.text } shouldBe listOf("fiskální rok 2026")
            found.single().trigger shouldBe "fiskální rok"
        }
    })
