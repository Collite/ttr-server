// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.writer

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * D-202 (F5) + D-201: the one hold rule, a row per code. Only a transient outcome holds a windowed program's watermark;
 * a settled failure, an applied or a skipped row does not.
 */
class RowOutcomeHoldsSpec :
    StringSpec({
        // (status, error) → holds. Every code the writer, the lowering and the door are known to produce.
        val table =
            listOf(
                // Transient: an unknown commit, never resent.
                Triple("staged", RowOutcome.UNKNOWN, true),
                Triple("staged", RowOutcome.IN_FLIGHT, true),
                // Transient: the door's and the transport's faults.
                Triple("failed", "worker_execution_failed", true),
                Triple("failed", "worker_engine_unavailable", true),
                Triple("failed", "worker_stream_failed", true),
                Triple("failed", "worker_resource_exhausted", true),
                Triple("failed", "connection_closed", true),
                Triple("failed", "connection_read_only", true),
                Triple("failed", "deadline-exceeded", true),
                Triple("failed", "door-transport", true),
                Triple("failed", "invalid-result", true),
                Triple("failed", "identity", true),
                Triple("failed", "connector-unavailable", true),
                Triple("failed", null, true),
                // Settled: the verdict stands (D-67, D-201).
                Triple("failed", RowOutcome.STALE_STATE, false),
                // Settled: a policy, placement or validation refusal repeats on every re-read.
                Triple("failed", "write_under_row_policy", false),
                Triple("failed", "not-writable", false),
                Triple("failed", "storage-unmapped", false),
                Triple("failed", "bad-transition", false),
                Triple("failed", "bad-value", false),
                Triple("failed", "unknown-attribute", false),
                Triple("failed", "unknown-entity", false),
                Triple("failed", "row_writes_key", false),
                Triple("failed", "row_writes_values", false),
                Triple("failed", "no-row", false),
                Triple("failed", "rows-affected-0", false),
                Triple("failed", "rows-affected-2", false),
                // Never: applied and skipped rows (a history sub-outcome is not a row status).
                Triple("applied", null, false),
                Triple("applied", RowOutcome.RECONCILED, false),
                Triple("applied", "history-failed: worker_execution_failed", false),
                Triple("skipped", "if_absent", false),
                Triple("skipped", "not-writable", false),
                Triple("skipped", "rolled-back", false),
            )

        "only a transient outcome holds the watermark — one row per code" {
            for ((status, error, holds) in table) {
                withClue("$status · $error") { RowOutcome.holds(status, error) shouldBe holds }
            }
        }

        "the transient families are prefixes, not names: any worker_ or connection_ code holds" {
            RowOutcome.holds("failed", "worker_something_new") shouldBe true
            RowOutcome.holds("failed", "connection_something_new") shouldBe true
            RowOutcome.holds("failed", "a_new_policy_refusal") shouldBe false
        }
    })
