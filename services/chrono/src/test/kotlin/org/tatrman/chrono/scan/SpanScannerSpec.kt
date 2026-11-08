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
                "Revenue Jan-Mar 2025",
                "Revenue January–March 2025",
                "Tržby leden–březen 2025",
                "Revenue in March/April 2025",
                "Tržby leden/únor 2025",
            )) {
                spans(text).shouldBeEmpty()
            }
            scanner
                .scan("Revenue mid-March 2025", ref)
                .single()
                .text
                .contains("March") shouldBe true
            spans("October's revenue") shouldBe listOf("October's")
            spans("Počet zařízení v Q2 2025") shouldBe listOf("Q2 2025")
            spans("Kolik objednal Jan Novák").shouldBeEmpty()
        }

        "R230 S2 joined month abbreviations give no month or year part" {
            for (text in listOf(
                "Revenue Jan.-Mar. 2025",
                "Revenue Jan.–Mar. 2025",
                "Revenue Jan. - Mar. 2025",
                "Revenue Sep.-Dec. 2025 by store",
                "Revenue Jan & Mar 2025",
                "Revenue Jan + Mar 2025",
                "Revenue Jan.:/—Mar. 2025",
            )) {
                spans(text).shouldBeEmpty()
            }
            spans("Revenue in Mar. 2025") shouldBe listOf("Mar. 2025")
            spans("Revenue Jan. 15, 2025 by store") shouldBe listOf("Jan. 15, 2025")
            spans("Revenue in Dec.") shouldBe listOf("Dec")
            spans("Kolik objednal Jan Novák a Petr").shouldBeEmpty()
        }

        "R230 R5 measures attributes verbs and document numbers stay outside spans" {
            val rows =
                listOf(
                    "Revenue this year, costs last month by product" to listOf("this year", "last month"),
                    "Revenue this year costs last month by product" to listOf("this year", "last month"),
                    "Revenue last year by region; margin this month by product" to listOf("last year", "this month"),
                    "Revenue last year with margin this month by product" to listOf("last year", "this month"),
                    "Revenue letos, náklady minulý měsíc by product" to listOf("letos", "minulý měsíc"),
                    "Revenue loni po regionech, marže tento měsíc by product" to listOf("loni", "tento měsíc"),
                    "Revenue this year costs in March by product" to listOf("this year", "March"),
                    "Document 202605 was issued last month" to listOf("last month"),
                    "Invoice 202605 from last month" to listOf("last month"),
                    "Tržby z faktury 202605 za minulý měsíc" to listOf("minulý měsíc"),
                    "Q2 sales last year by region" to listOf("Q2", "last year"),
                )
            for ((text, expected) in rows) spans(text) shouldBe expected
            for (word in listOf("sales", "costs", "margin", "stores", "issued", "green", "202605")) {
                spans("this year $word last month") shouldBe listOf("this year", "last month")
            }
            spans("YTD by end of March") shouldBe listOf("YTD by end of March")
            spans("YTD by the end of March") shouldBe listOf("YTD by the end of March")
        }

        "R230 R6 only linked phrases suppress one another" {
            val rows =
                listOf(
                    "Sales last year and costs this month" to listOf("last year", "this month"),
                    "Sales last year and costs last month" to listOf("last year", "last month"),
                    "How much did we sell last year and how much this month?" to listOf("last year", "this month"),
                    "Tržby loni a náklady tento měsíc" to listOf("loni", "tento měsíc"),
                    "tržby loni a náklady minulý měsíc" to listOf("loni", "minulý měsíc"),
                    "v březnu 2025 a kolik utratili minulý měsíc" to listOf("březnu 2025", "minulý měsíc"),
                    "Revenue last month for products launched next year" to listOf("last month"),
                    "Customers active since Q2 who bought last month" to listOf("last month"),
                    "Revenue in March for stores opened since Q3" to listOf("March"),
                    "Kolik jsme prodali minulý měsíc zákazníkům od 2. čtvrtletí" to listOf("minulý měsíc"),
                    "Sales for 2025 vs 2024 in 3 regions" to listOf("2025", "2024"),
                    "Q2 2025 vs Q2 2024 top 5" to listOf("Q2 2025", "Q2 2024"),
                    "Sales this quarter and last month for 10 stores" to listOf("this quarter", "last month"),
                )
            for ((text, expected) in rows) spans(text) shouldBe expected
            for (text in listOf(
                "Q2 this year and last",
                "1. pololetí letos a loni",
                "Revenue this month and last year",
                "Q2 revenue vs the same quarter last year",
                "revenue for Q2 this year vs last year",
                "Q2 revenue since 2020",
                "next Q2",
                "in 2025 excluding Q4",
            )) {
                spans(text).shouldBeEmpty()
            }
            spans("this quarter and last month") shouldBe listOf("this quarter", "last month")
            for (period in listOf("Q3", "third quarter", "3. čtvrtletí")) {
                for (bound in listOf(
                    "since",
                    "after",
                    "before",
                    "until",
                    "through",
                    "up to",
                    "by",
                    "excluding",
                    "except",
                    "without",
                    "od",
                    "po",
                    "před",
                    "do",
                    "kromě",
                    "bez",
                    "mimo",
                    "vyjma",
                )) {
                    spans("March shops $bound $period") shouldBe listOf("March")
                }
            }
            spans("Q2 revenue vs Q4 last year").shouldBeEmpty()
            for (padding in listOf("", "one two ", "one two three four five six seven eight ")) {
                spans(padding + "v březnu 2025 a kolik utratili minulý měsíc") shouldBe
                    listOf("březnu 2025", "minulý měsíc")
            }
        }

        "R230 R7 a grain before a different unit stays outside the span" {
            for (text in listOf(
                "sales per quarter last year",
                "by quarter last year",
                "each quarter last year",
                "every month last year",
                "sales in the same quarter last year",
            )) {
                spans(text) shouldBe listOf("last year")
            }
            spans("tržby po čtvrtletích loni") shouldBe listOf("loni")
            spans("tržby za každý měsíc loni") shouldBe listOf("loni")
            spans("Revenue fiscal year 2025 Q4") shouldBe listOf("fiscal year 2025 Q4")
            spans("fiscal year Q4") shouldBe listOf("fiscal year Q4")
            spans("fiskálního roku Q4") shouldBe listOf("fiskálního roku Q4")
            spans("Tržby v roce 2025 po měsících") shouldBe listOf("roce 2025")
            spans("Tržby za loňského roku po měsících") shouldBe listOf("loňského roku")
        }

        "R230 R9 months are blanked only where the shared helper finds verbs or names" {
            for ((text, expected) in listOf(
                "May sales by region" to listOf("May"),
                "March revenue by product" to listOf("March"),
                "may revenue by region" to listOf("may"),
                "show march revenue" to listOf("march"),
                "compare march and april revenue" to listOf("march", "april"),
                "Revenue in Jan" to listOf("Jan"),
                "Revenue in Dec." to listOf("Dec"),
                "Show revenue, May vs April" to listOf("May", "April"),
                "What were May sales?" to listOf("May"),
                "Q2 2025 sales may rise" to listOf("Q2 2025"),
                "May we see revenue for March" to listOf("March"),
                "May I see sales for last month?" to listOf("last month"),
                "Kolik objednal Jan Novák" to emptyList(),
            )) {
                spans(text) shouldBe expected
            }
        }

        "R230 S3 an ordinal day beside its month stays inside the date" {
            for (phrase in listOf("March 15th 2025", "December 31st, 2025", "March 2nd 2025", "March 3rd 2025")) {
                val text = "Revenue on $phrase"
                val found = scanner.scan(text, ref)
                found.map { it.text } shouldBe listOf(phrase)
                (found.single().start to found.single().end) shouldBe at(text, phrase)
            }
            spans("Revenue in 1st quarter 2025") shouldBe listOf("1st quarter 2025")
            spans("Revenue in 2nd half 2025") shouldBe listOf("2nd half 2025")
            spans("Revenue on March 15, 2025") shouldBe listOf("March 15, 2025")
            spans("Revenue on the 15th of March 2025") shouldBe listOf("March 2025")
            spans("Revenue the first 10 days of March") shouldBe listOf("March")
        }

        "R230 R12 reading without the question keeps the question slot empty" {
            val found =
                scanner.scan(
                    "Sales last month and the previous quarter",
                    ref,
                    priorQuestions = listOf("Revenue in Q2 2025 and the previous quarter"),
                )
            found.map { it.text } shouldBe listOf("last month", "previous quarter")
            found.last().trigger shouldBe "conversation"
        }

        "R230 S1 short digits beside a quarter keep its parts out" {
            for (text in listOf(
                "Revenue in Q2 10 stores",
                "Q2 25 revenue by region",
                "Revenue in Q2 25 by region",
                "H1 25 revenue",
                "Q3 24 margin",
                "Q2 FY25",
                "Q2 '25",
                "Q2/25",
            )) {
                spans(text).shouldBeEmpty()
            }
            spans("Revenue in Q2 2025") shouldBe listOf("Q2 2025")
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
            found.map { it.text } shouldBe listOf("Q2", "last year")
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
