// SPDX-License-Identifier: Apache-2.0
package org.tatrman.resolver

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.tatrman.nlp.v1.AnalyzeResponse
import org.tatrman.nlp.v1.Token
import org.tatrman.resolver.pipeline.QuotedLiteral
import org.tatrman.resolver.pipeline.QuoteScanner

/**
 * LP-P1·S1 — the scanner against the SHARED fixture file (LP contracts §1.5).
 *
 * `src/test/resources/lp/quoted-literals.fixtures.yaml` is a byte copy of
 * `project/server/features/literal-partial-match/fixtures/quoted-literals.fixtures.yaml`, the
 * same file golem's Python scanner is held to. Two implementations of §1.1–§1.4 in two languages
 * agree because they answer to one corpus — so **edit the corpus in `project/` and re-copy**;
 * a case fixed here alone is a fork.
 *
 * The Kotlin-only cases below (normalisation, length, surrogate pairs) are the ones a Python
 * fixture cannot state, because they are about how *this* language indexes a string.
 */
class QuoteScannerTest :
    FunSpec({

        context("shared fixtures (contracts §1)") {
            withData<Case>(nameFn = { "#${it.id} ${it.text}" }, sharedCases()) { case ->
                withClue(case.text) {
                    QuoteScanner.scan(case.text) shouldBe case.literals
                }
            }
        }

        context("what a shared YAML fixture cannot say (Kotlin offsets)") {
            // The scanner reads the text **as received**: it does not normalise, and it does not
            // want to. Two encodings of the same name are two different strings with two different
            // lengths, and the offsets a literal carries have to index the string the caller holds
            // — the one the nlp tokens were produced from. Folding happens downstream, where a
            // value is compared; never here, where it is located.
            test("NFC and NFD input both scan, each with offsets into its own string") {
                val nfc = "z\u00e1kazn\u00edk \u201eV\u00e1len\u00fd\u201c" // á, ý precomposed
                val nfd = "za\u0301kazni\u0301k \u201eVa\u0301leny\u0301\u201c" // combining acutes

                val fromNfc = QuoteScanner.scan(nfc).single()
                val fromNfd = QuoteScanner.scan(nfd).single()

                fromNfc.text shouldBe "V\u00e1len\u00fd"
                nfc.substring(fromNfc.start, fromNfc.end) shouldBe "\u201eV\u00e1len\u00fd\u201c"

                fromNfd.text shouldBe "Va\u0301leny\u0301"
                nfd.substring(fromNfd.start, fromNfd.end) shouldBe "\u201eVa\u0301leny\u0301\u201c"

                // Same name, same number of literals, DIFFERENT offsets — the decomposed form is
                // two code units longer, and the literal starts two later.
                (fromNfd.start - fromNfc.start) shouldBe 2
            }

            test("ten literals in a 2 000-character text, each at its own offsets") {
                val filler = "a".repeat(200)
                val text = (1..10).joinToString(" ") { "$filler \"lit$it\"" }
                text.length shouldBeGreaterThan 2_000

                val found = QuoteScanner.scan(text)

                found.map { it.text } shouldBe (1..10).map { "lit$it" }
                found.forEach { text.substring(it.start, it.end) shouldBe "\"${it.text}\"" }
            }

            test("a surrogate pair inside a literal survives, and end − start counts code units") {
                val emoji = "\uD83D\uDE42" // U+1F642, one code point, TWO Kotlin chars
                val text = "z\u00e1kazn\u00edk \"Valmy $emoji\""

                val literal = QuoteScanner.scan(text).single()

                literal.text shouldBe "Valmy $emoji"
                text.substring(literal.start, literal.end) shouldBe "\"Valmy $emoji\""
                // 6 chars of `Valmy `, 2 code units of emoji, 2 delimiters — code units, not
                // code points (see the scanner's KDoc on the offset base).
                (literal.end - literal.start) shouldBe 10
            }
        }

        context("placing a literal on a tokenisation") {
            // Both shapes are real: MorphoDiTa/Stanza split the quote off as its own PUNCT token,
            // the LLM_EMULATED backend has been seen to hand back `"Pelex"` whole. One rule —
            // overlap with the INNER range — has to land both, or the literal's span depends on
            // which NLP backend the estate happens to run (architecture §6 risk 2).
            val text = "Ukaž dodací místa začínající na \"Pelex\""
            val literal = QuoteScanner.scan(text).single()

            test("delimiters tokenised apart: content tokens and delimiter tokens are separated") {
                val parse =
                    parseOf(
                        token("Ukaž", 0, 4),
                        token("dodací", 5, 11),
                        token("místa", 12, 17),
                        token("začínající", 18, 29),
                        token("na", 30, 32),
                        token("\"", 32, 33, "PUNCT"),
                        token("Pelex", 33, 38),
                        token("\"", 38, 39, "PUNCT"),
                    )

                val span = QuoteScanner.toTokenSpans(listOf(literal), text, parse).single()

                span.tokens shouldBe listOf(6)
                span.delimiterTokens shouldBe listOf(5, 7)
                span.literal.text shouldBe "Pelex"
            }

            test("delimiters glued into one token: the literal covers it, and keeps its own text") {
                val parse =
                    parseOf(
                        token("Ukaž", 0, 4),
                        token("dodací", 5, 11),
                        token("místa", 12, 17),
                        token("začínající", 18, 29),
                        token("na", 30, 32),
                        token("\"Pelex\"", 32, 39),
                    )

                val span = QuoteScanner.toTokenSpans(listOf(literal), text, parse).single()

                span.tokens shouldBe listOf(5)
                span.delimiterTokens.shouldBeEmpty()
                // The token surface carries the quotes; the literal does not. §1.4 text wins —
                // this is the value that goes on the wire.
                span.literal.text shouldBe "Pelex"
                parse.getTokens(5).text shouldBe "\"Pelex\""
            }

            test("a multi-word literal covers every token it overlaps, in order") {
                val long = "zákazník \"Valmy Oil, s.r.o.\""
                val parse =
                    parseOf(
                        token("zákazník", 0, 8),
                        token("\"", 9, 10, "PUNCT"),
                        token("Valmy", 10, 15),
                        token("Oil", 16, 19),
                        token(",", 19, 20, "PUNCT"),
                        token("s.r.o.", 21, 27),
                        token("\"", 27, 28, "PUNCT"),
                    )

                val span = QuoteScanner.toTokenSpans(QuoteScanner.scan(long), long, parse).single()

                span.tokens shouldBe listOf(2, 3, 4, 5)
                span.delimiterTokens shouldBe listOf(1, 6)
            }
        }

        context("no parse at all") {
            // Not "no dep parse" — no tokens. An unanalysable language, or nlp down: the pipeline
            // still answers on the fold+fuzzy floor, and a literal is the one thing on that floor
            // that needs no analysis to be understood.
            test("token spans fall back to a whitespace split of the raw text") {
                val text = "customers starting with \"Sh\""
                val literal = QuoteScanner.scan(text).single()

                val spans = QuoteScanner.toTokenSpans(listOf(literal), text, AnalyzeResponse.getDefaultInstance())

                val tokens = QuoteScanner.tokens(text, AnalyzeResponse.getDefaultInstance())
                tokens.map { it.text } shouldBe listOf("customers", "starting", "with", "\"Sh\"")
                // The whitespace split glues the delimiters on, so it is the glued shape above.
                spans.single().tokens shouldBe listOf(3)
                spans.single().delimiterTokens.shouldBeEmpty()
                spans.single().literal.text shouldBe "Sh"
            }

            test("a non-breaking space splits like a space, because Word and phones type one") {
                val text = "zákazník\u00a0\"Valmy\""

                QuoteScanner.scan(text).single().text shouldBe "Valmy"
                QuoteScanner
                    .tokens(text, AnalyzeResponse.getDefaultInstance())
                    .map { it.text } shouldBe listOf("zákazník", "\"Valmy\"")
            }
        }
    })

private fun token(
    text: String,
    start: Int,
    end: Int,
    upos: String = "NOUN",
): Token =
    Token
        .newBuilder()
        .setText(text)
        .setCharStart(start)
        .setCharEnd(end)
        .setUpos(upos)
        .build()

private fun parseOf(vararg tokens: Token): AnalyzeResponse =
    AnalyzeResponse
        .newBuilder()
        .setLanguage("cs")
        .addAllTokens(tokens.toList())
        .build()

data class Case(
    val id: Int,
    val text: String,
    val literals: List<QuotedLiteral>,
)

private fun sharedCases(): List<Case> {
    val mapper = ObjectMapper(YAMLFactory())
    val stream =
        QuoteScannerTest::class.java.getResourceAsStream("/lp/quoted-literals.fixtures.yaml")
            ?: error("fixture file missing from the test resources")
    val root = stream.use { mapper.readValue(it, Map::class.java) }

    @Suppress("UNCHECKED_CAST")
    val cases = root["cases"] as List<Map<String, Any?>>
    return cases.map { case ->
        @Suppress("UNCHECKED_CAST")
        val literals = (case["literals"] as List<Map<String, Any?>>? ?: emptyList())
        Case(
            id = case["id"] as Int,
            text = case["text"] as String,
            literals =
                literals.map {
                    QuotedLiteral(
                        text = it["text"].toString(),
                        start = it["start"] as Int,
                        end = it["end"] as Int,
                    )
                },
        )
    }
}
