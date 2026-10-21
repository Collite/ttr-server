// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translate.grpc

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.tatrman.translate.grpc.RefusalText.Clean

/**
 * BI (contracts C-11) — the refusals a BI client met in the BI-0 spike, verbatim as the door sent them (the spike's
 * SQL ledger), and what a person reads instead: one line, said once, the position as fields.
 */
class RefusalTextSpec :
    StringSpec({
        "a validator error: the exception class, the position prose and the repeats go; the suggestion stays" {
            RefusalText.of(
                "org.apache.calcite.runtime.CalciteContextException: From line 1, column 15 to line 1, column 26: " +
                    "Object 'warehouse' not found within 'er': From line 1, column 15 to line 1, column 26: Object " +
                    "'warehouse' not found within 'er': Object 'warehouse' not found within 'er' — did you mean: " +
                    "warehouse, warehouse_id?",
            ) shouldBe
                Clean("Object 'warehouse' not found within 'er' — did you mean: warehouse, warehouse_id?", 1, 15)
            RefusalText.of(
                "org.apache.calcite.runtime.CalciteContextException: From line 1, column 15 to line 1, column 30: " +
                    "Object 'entity' not found: From line 1, column 15 to line 1, column 30: Object 'entity' not " +
                    "found: Object 'entity' not found — did you mean: city, quantity?",
            ) shouldBe Clean("Object 'entity' not found — did you mean: city, quantity?", 1, 15)
        }

        "an unknown function keeps its signature, which has a colon-free body of its own" {
            RefusalText.of(
                "org.apache.calcite.runtime.CalciteContextException: From line 1, column 235 to line 1, column 271: " +
                    "No match found for function signature DATE_TRUNC(<CHARACTER>, <TIMESTAMP>): From line 1, column " +
                    "235 to line 1, column 271: No match found for function signature DATE_TRUNC(<CHARACTER>, " +
                    "<TIMESTAMP>): No match found for function signature DATE_TRUNC(<CHARACTER>, <TIMESTAMP>)",
            ) shouldBe Clean("No match found for function signature DATE_TRUNC(<CHARACTER>, <TIMESTAMP>)", 1, 235)
        }

        "a parse error: the expected-token list goes, the position becomes fields" {
            RefusalText.of(
                "Encountered \"= {\" at line 1, column 79.\nWas expecting one of:\n    <EOF> \n    \"ORDER\" ...\n" +
                    "    \"LIMIT\" ...\n    \"OFFSET\" ...\n    \"FETCH\" ...\n    \"GROUP\" ...\n",
            ) shouldBe Clean("Syntax error near \"= {\".", 1, 79)
        }

        "a message with neither shape is kept as it is, on one line" {
            RefusalText.of("SQL references unknown parameter '{city}'; known: [other]") shouldBe
                Clean("SQL references unknown parameter '{city}'; known: [other]")
            RefusalText.of("Literal of SqlTypeName 'INTERVAL_DAY' is not in the v1 wire format") shouldBe
                Clean("Literal of SqlTypeName 'INTERVAL_DAY' is not in the v1 wire format")
            RefusalText.of(
                "Could not determine schema: channel_sales, warehouse exist in multiple schemas (db, er).",
            ) shouldBe
                Clean("Could not determine schema: channel_sales, warehouse exist in multiple schemas (db, er).")
            RefusalText.of("  two\n  lines ") shouldBe Clean("two lines")
        }
    })
