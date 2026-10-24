// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class SqlParametersSpec :
    StringSpec({
        "replace only SQL parameter tokens, preserving casts, strings, identifiers and comments" {
            val sql = "SELECT :n::int, ':n', \":n\", $$:n$$ -- :n\n/* :n */ WHERE x = :n"
            val names = mutableListOf<String>()
            SqlParameters.rewrite(sql) {
                names += it
                "{$it}"
            } shouldBe
                "SELECT {n}::int, ':n', \":n\", $$:n$$ -- :n\n/* :n */ WHERE x = {n}"
            names shouldBe listOf("n", "n")
        }
    })
