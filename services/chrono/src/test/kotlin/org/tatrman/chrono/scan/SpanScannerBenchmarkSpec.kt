// SPDX-License-Identifier: Apache-2.0
package org.tatrman.chrono.scan

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

/**
 * TS-P0 S0.3 DONE — the unit benchmark: `Scan` over the chrono corpus sentences (each case's question, else its
 * span), after a warm-up of the JIT, each scan cold (a fresh thread, so the recogniser's memo is empty), p95 under
 * 20 ms. One scan reads every window of up to seven tokens, so the cost is the recogniser's times the windows; the p95
 * is printed for the PR.
 */
class SpanScannerBenchmarkSpec :
    StringSpec({
        "Scan p95 on the corpus sentences is under 20 ms" {
            val cases =
                Json
                    .parseToJsonElement(
                        object {}.javaClass.getResource("/corpus/chrono/cases.json")!!.readText(),
                    ).jsonArray
            val sentences =
                cases.map { c ->
                    val o = c.jsonObject
                    (o["question"] ?: o["span"])!!.jsonPrimitive.content
                }
            val scanner = SpanScanner()
            val ref = LocalDate.of(2026, 11, 1)
            repeat(WARM_UP) { sentences.forEach { scanner.scan(it, ref) } }

            // Each scan on a fresh thread: the recogniser's per-thread memo starts empty, as for a new question.
            fun coldMillis(s: String): Double {
                var ms = 0.0
                val thread =
                    Thread {
                        val t = System.nanoTime()
                        scanner.scan(s, ref)
                        ms = (System.nanoTime() - t) / 1_000_000.0
                    }
                thread.start()
                thread.join()
                return ms
            }
            val millis = (1..ROUNDS).flatMap { sentences.map(::coldMillis) }.sorted()
            val p95 = millis[(millis.size * 95) / 100]
            println(
                "Scan benchmark: ${sentences.size} sentences × $ROUNDS, p50 ${millis[millis.size / 2]} ms, p95 $p95 ms",
            )
            p95 shouldBeLessThan 20.0
        }
    }) {
    private companion object {
        const val WARM_UP = 3
        const val ROUNDS = 5
    }
}
