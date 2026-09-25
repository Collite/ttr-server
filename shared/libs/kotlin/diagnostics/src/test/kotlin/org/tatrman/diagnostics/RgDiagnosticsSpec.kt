// SPDX-License-Identifier: Apache-2.0
package org.tatrman.diagnostics

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.string.shouldNotContain

/**
 * RG-P0.S3.T6 — the fixture IS contracts §8. Every `RG-*` id must be registered
 * with the exact severity from the table, a non-blank message template, and a
 * non-blank suggestion. The table below is the source of truth; the registry is
 * checked against it (both directions — no missing ids, no extras).
 */
class RgDiagnosticsSpec :
    FunSpec({

        // contracts §8 — (id -> severity)
        val expected =
            mapOf(
                "RG-NLP-001" to Severity.ERROR,
                "RG-NLP-002" to Severity.WARNING,
                "RG-NLP-003" to Severity.ERROR,
                "RG-NLP-010" to Severity.INFO,
                "RG-FUZ-001" to Severity.WARNING,
                "RG-FUZ-002" to Severity.ERROR,
                // MV-T1 (member-vocabulary contracts §8, renumbered: 002 was already the leak guard).
                "RG-FUZ-003" to Severity.WARNING,
                // MV-T2 — the loader side: ListMemberVocabularies unavailable, previous cache kept.
                "RG-FUZ-004" to Severity.WARNING,
                "RG-GND-001" to Severity.WARNING,
                "RG-GND-002" to Severity.ERROR,
                "RG-RES-001" to Severity.INFO,
                "RG-RES-002" to Severity.ERROR,
            )

        test("registry has exactly the contracts §8 id set") {
            RgDiagnostics
                .all()
                .map { it.id }
                .shouldContainExactlyInAnyOrder(expected.keys.toList())
        }

        context("each id: registered, correct severity, non-blank template + suggestion") {
            expected.forEach { (id, severity) ->
                test("$id is $severity with a template + suggestion") {
                    val d = RgDiagnostics[id]
                    d.id shouldBe id
                    d.severity shouldBe severity
                    d.messageTemplate.shouldNotBeBlank()
                    d.suggestion.shouldNotBeBlank()
                }
            }
        }

        test("get() on an unknown id fails loudly") {
            io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
                RgDiagnostics["RG-NOPE-999"]
            }
        }

        test("render substitutes every placeholder in the template") {
            // RG-NLP-010 template = "Unsupported ({language}, {op}) — degrade floor …"
            val rendered = RgDiagnostics.render("RG-NLP-010", "language" to "cs", "op" to "DEP_PARSE")
            rendered shouldContain "cs"
            rendered shouldContain "DEP_PARSE"
            // no placeholder left behind (every {name} in the template was supplied)
            rendered shouldNotContain "{"
        }

        test("an RgDiagnosticException carries the diagnostic and names its id in the message") {
            val ex = RgDiagnosticException(RgDiagnostics["RG-FUZ-002"], "category=widgets")
            ex.diagnostic.id shouldBe "RG-FUZ-002"
            ex.diagnostic.severity shouldBe Severity.ERROR
            val message = ex.message!!
            message.shouldNotBeBlank()
            message shouldContain "RG-FUZ-002"
            message shouldContain "category=widgets"
        }
    })
