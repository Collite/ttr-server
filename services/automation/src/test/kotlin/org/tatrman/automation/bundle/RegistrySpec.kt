// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.bundle

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

/**
 * AU-server S0.2 T3 — the registry grid (D-35) over the hartland world: native runs everything; n8n runs the
 * release and the returns flow with the writes going back through the Server; the Camunda and Python types are
 * not shipped yet, so their deployments are refused naming the type. One rule per refusal, `TTRP-TGT-001…005`.
 */
class RegistrySpec :
    StringSpec({
        val bundle = BundleFixture.load()
        val builtin = ExecutorType.builtin()
        val grid = Registry(builtin).grid(bundle).associateBy { it.deployment }

        "native deployments: ok" {
            for (d in listOf(
                "release_native_morning",
                "release_native_event",
                "decide_native_event",
                "settle_native_batch",
            )) {
                grid.getValue(d).status shouldBe Registry.OK
                grid.getValue(d).targetType shouldBe "native"
            }
            grid.getValue("release_native_morning").mode shouldBe "batch"
        }

        "n8n runs release_order with notes: the transitions and the ask go back through the Server" {
            grid.getValue("release_n8n").let {
                it.status shouldBe Registry.NOTES
                it.targetType shouldBe "n8n_l1"
                it.mode shouldBe "on_event"
                it.notes shouldBe
                    listOf("ask through the Server", "hold through the Server", "release through the Server")
            }
        }

        "a process deployment is judged over every step's program; a sink falls back to the native kind" {
            grid.getValue("returns_n8n").let {
                it.status shouldBe Registry.NOTES
                it.notes shouldBe
                    listOf(
                        "ask through the Server",
                        "expect through the Server",
                        "fulfil through the Server",
                        "set_state through the Server",
                        "task through the Server",
                    )
            }
        }

        "an executor type this Server does not know is refused, named" {
            grid.getValue("release_bpmn").let {
                it.status shouldBe Registry.REFUSED
                it.reason shouldBe "executor type camunda_8 is not known to this Server"
            }
            grid.getValue("release_py").reason shouldBe "executor type python_client is not known to this Server"
        }

        fun withType(change: ExecutorType.() -> ExecutorType) =
            Registry(builtin + ("n8n_l1" to builtin.getValue("n8n_l1").change()))
                .cell(bundle, bundle.deployments.getValue("release_n8n"))

        "TTRP-TGT-001: a mode the type does not run" {
            withType { copy(modes = listOf("batch")) }.reason!! shouldStartWith
                "TTRP-TGT-001: n8n_l1 does not run mode on_event"
        }

        "TTRP-TGT-002: an island kind the type marks `no`" {
            withType { copy(kinds = kinds + ("sql" to "no")) }.reason!! shouldStartWith
                "TTRP-TGT-002: n8n_l1 cannot run a sql island"
        }

        "TTRP-TGT-003: an action whose sink the type cannot perform" {
            withType { copy(family = "zapier") }.reason shouldBe
                "TTRP-TGT-003: n8n_l1 cannot perform action notify (sink deliver; recipe release_order)"
        }

        "TTRP-TGT-004: a granularity the type does not offer" {
            withType { copy(granularity = listOf("recipe")) }.reason shouldBe
                "TTRP-TGT-004: n8n_l1 does not run granularity parts"
        }

        "TTRP-TGT-005: an ungoverned type on a governed deployment" {
            withType { copy(ungoverned = true) }.reason!! shouldStartWith "TTRP-TGT-005"
        }
    })
