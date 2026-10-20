// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.bundle

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import org.tatrman.automation.bundle.BundleFixture.edit
import org.tatrman.automation.identity.GrantDecl
import org.tatrman.automation.identity.RunAsRef
import org.tatrman.automation.identity.Who
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * AU-server S0.2 T1/T2 — `/refresh`'s load over the hartland fixture (`17` §1–§2 + the four `17` bundles,
 * prebuilt): what a commit yields, the fingerprints, and every refusal (`422 bundle-invalid`) naming its finding.
 */
class BundleLoaderSpec :
    StringSpec({
        "the hartland fixture loads: four recipes, eight programs, twelve deployments, the identity, four grants" {
            val b = BundleFixture.load()
            b.warnings.shouldBeEmpty()
            b.recipes.keys shouldBe setOf("decide_return", "monitor_inbound", "release_order", "settle")
            b.programs.keys shouldBe
                setOf(
                    "morning_release",
                    "release_on_placement",
                    "decide_on_request",
                    "decide_catch_up",
                    "inbound_on_receipt",
                    "inbound_daily",
                    "settle_on_document",
                    "settle_daily",
                )
            b.deployments.keys shouldHaveSize 12
            b.deployments.keys shouldContain "returns_n8n"
            b.worlds.keys shouldBe setOf("demo")
            b.deploymentWorlds.values.toSet() shouldBe setOf("demo")
            val demo = b.worlds.getValue("demo")
            demo.processes.keys shouldBe setOf("return_line_flow")
            demo.identities.single().let {
                it.name shouldBe "automation_decider"
                it.principal shouldBe "svc-hartland-automation"
                it.credential shouldBe "TTR_CONN_AUTOMATION_DECIDER" // D-132
            }
            demo.executors shouldBe mapOf("n8n" to "n8n_l1", "bpmn" to "camunda_8", "py" to "python_client")
            b.actions.keys shouldContain "expect"
            b.id shouldBe "served:master@0123456789ab"
            b.author shouldBe "po"
            b.loadedAt shouldBe BundleFixture.LOADED_AT
        }

        "a recipe's islands are read into memory, the decision's route islands included" {
            val r = BundleFixture.load().recipes.getValue("release_order")
            r.recipe.mode shouldBe "per-candidate"
            r.recipe.roles!!
                .routes
                .map { it.name } shouldBe
                listOf("hold_sales", "hold_ar", "release_confirmed", "release")
            r.statements.keys shouldContain "islands/overdue.rows.sql"
            r.statements.keys shouldContain "islands/verdict.release.sql"
            r.statements.getValue("islands/overdue.rows.sql") shouldContain "i.due < :as_of"
        }

        "the grants: `who: *` is anyone, `program: *` any program, `as:` the run-as" {
            BundleFixture
                .load()
                .worlds
                .getValue("demo")
                .grants shouldContainExactlyInAnyOrder
                listOf(
                    GrantDecl(Who.Role("ops"), GrantDecl.ANY_PROGRAM, RunAsRef.Named("automation_decider")),
                    GrantDecl(Who.Role("sales_manager"), "release_on_placement", RunAsRef.Named("automation_decider")),
                    GrantDecl(Who.Role("process_owner"), GrantDecl.ANY_PROGRAM, RunAsRef.Named("automation_decider")),
                    GrantDecl(Who.Anyone, "decide_on_request", RunAsRef.Author),
                )
        }

        "fingerprints: sha256, stable across loads and moves, moved by any change" {
            val a = BundleFixture.load()
            a.programFps.values.forEach { it shouldStartWith "sha256:" }
            a.deploymentFps.values.forEach { it shouldStartWith "sha256:" }
            a.modelFp shouldStartWith "sha256:"
            BundleFixture.load().deploymentFps shouldBe a.deploymentFps

            val moved = BundleFixture.copy()
            moved.edit("model/worlds/demo.ttrm") {
                it.replace("    def deployment release_n8n", "\n\n    def deployment release_n8n")
            }
            val m = BundleFixture.load(moved)
            m.deploymentFps shouldBe a.deploymentFps
            m.modelFp shouldNotBe a.modelFp // the model's text did change

            val changed = BundleFixture.copy()
            changed.edit("model/worlds/demo.ttrm") { it.replace("granularity: parts", "granularity: islands") }
            changed.edit(
                "automation/programs.ttrm",
            ) { it.replace("\"06:00 Europe/Prague\" }", "\"06:15 Europe/Prague\" }") }
            val c = BundleFixture.load(changed)
            c.deploymentFps.getValue("release_n8n") shouldNotBe a.deploymentFps.getValue("release_n8n")
            c.deploymentFps.getValue("release_py") shouldBe a.deploymentFps.getValue("release_py")
            c.programFps.getValue("morning_release") shouldNotBe a.programFps.getValue("morning_release")
            c.programFps.getValue("release_on_placement") shouldBe a.programFps.getValue("release_on_placement")
        }

        fun refusal(change: java.nio.file.Path.() -> Unit): List<String> {
            val dir = BundleFixture.copy()
            dir.change()
            return shouldThrow<BundleInvalidException> { BundleFixture.load(dir) }.diagnostics
        }

        "a manifest that is not schemaVersion 3 refuses the commit" {
            refusal {
                edit(
                    "automation/settle/settle.bundle/manifest.json",
                ) { it.replace("\"schemaVersion\": 3", "\"schemaVersion\": 2") }
            } shouldContainExactlyInAnyOrder
                listOf(
                    "bundle settle: manifest schemaVersion 2, this Server reads 3",
                    // the recipe is gone, so its deployments cannot run — the process's step too
                    "deployment settle_native_event: program settle_on_document's recipe settle has no bundle",
                    "deployment settle_native_batch: program settle_daily's recipe settle has no bundle",
                    "deployment returns_n8n: process return_line_flow step settle: program settle_on_document's " +
                        "recipe settle has no bundle",
                )
        }

        "a deployment naming an unknown program refuses the commit" {
            refusal {
                edit("model/worlds/demo.ttrm") { it.replace("program: settle_daily,", "program: settle_weekly,") }
            } shouldContainExactlyInAnyOrder
                listOf(
                    // the automation validator's finding (on the load path) and the loader's own
                    "model/worlds/demo.ttrm:38: ttr/automation-unknown-reference: deployment 'settle_native_batch': " +
                        "program names no program 'settle_weekly'",
                    "deployment settle_native_batch: program settle_weekly is not declared",
                )
        }

        "an island whose statement changed after the build refuses the commit" {
            refusal {
                edit("automation/release_order/release_order.bundle/islands/overdue.rows.sql") { "$it-- edited\n" }
            } shouldBe
                listOf("bundle release_order: island overdue — islands/overdue.rows.sql does not match its sha256")
        }

        "a malformed deployment is refused, never dropped silently" {
            val d =
                refusal {
                    edit(
                        "model/worlds/demo.ttrm",
                    ) { it.replace("granularity: parts", "granularity: parts, dry_run: \"false\"") }
                }
            d.any { it.contains("ttr/automation-deployment-dropped") && it.contains("release_n8n") } shouldBe true
        }

        "a deployment on a target that is no executor of its world refuses the commit" {
            refusal {
                edit("model/worlds/demo.ttrm") { it.replace("target: bpmn,", "target: camunda,") }
            } shouldContain "deployment release_bpmn: target camunda is neither native nor an executor of world demo"
        }

        "every finding is named at once" {
            refusal {
                edit(
                    "automation/settle/settle.bundle/manifest.json",
                ) { it.replace("\"schemaVersion\": 3", "\"schemaVersion\": 2") }
                edit("model/worlds/demo.ttrm") { it.replace("program: inbound_daily,", "program: inbound_hourly,") }
            }.filterNot { it.startsWith("model/") } shouldContainExactlyInAnyOrder
                listOf(
                    "bundle settle: manifest schemaVersion 2, this Server reads 3",
                    "deployment inbound_native_batch: program inbound_hourly is not declared",
                    "deployment settle_native_event: program settle_on_document's recipe settle has no bundle",
                    "deployment settle_native_batch: program settle_daily's recipe settle has no bundle",
                    "deployment returns_n8n: process return_line_flow step settle: program settle_on_document's " +
                        "recipe settle has no bundle",
                )
        }

        "two worlds keep their own identities, grants, processes and executors (review-114: resolved within a world)" {
            val dir = BundleFixture.copy()
            val demo = dir.resolve("model/worlds/demo.ttrm").readText()
            // A second world declaring the same names with other values, and one deployment of its own.
            val other =
                demo
                    .replace("def world demo", "def world other")
                    .replace("\"svc-hartland-automation\"", "\"svc-other-automation\"")
                    .replace("TTR_CONN_AUTOMATION_DECIDER", "TTR_CONN_OTHER_DECIDER")
                    .replace("extends: camunda_8", "extends: n8n_l1")
                    .replace("who: role.sales_manager", "who: user.olga")
                    .lines()
                    .filterNot { it.trimStart().startsWith("def deployment") }
                    .joinToString("\n")
                    .replaceFirst(
                        "    def process return_line_flow",
                        "    def deployment other_bpmn { program: release_on_placement, target: bpmn, " +
                            "run_as: identity.automation_decider }\n\n    def process return_line_flow",
                    )
            dir.resolve("model/worlds/other.ttrm").writeText(other)
            val b = BundleFixture.load(dir)
            b.worlds.keys shouldBe setOf("demo", "other")
            b.deploymentWorlds.getValue("other_bpmn") shouldBe "other"
            b
                .worldOf(b.deployments.getValue("other_bpmn"))!!
                .identities
                .single()
                .credential shouldBe "TTR_CONN_OTHER_DECIDER"
            b
                .worldOf(b.deployments.getValue("release_bpmn"))!!
                .identities
                .single()
                .credential shouldBe "TTR_CONN_AUTOMATION_DECIDER"
            b.targetType(b.deployments.getValue("other_bpmn")) shouldBe "n8n_l1"
            b.targetType(b.deployments.getValue("release_bpmn")) shouldBe "camunda_8"
            b.worlds
                .getValue("demo")
                .grants
                .map { it.who } shouldContain Who.Role("sales_manager")
            b.worlds
                .getValue("other")
                .grants
                .map { it.who } shouldContain Who.User("olga")
            b.worlds
                .getValue("demo")
                .grants
                .map { it.who }
                .contains(Who.User("olga")) shouldBe false
        }

        "a commit without an author refuses the commit: no deployer (D-90)" {
            shouldThrow<BundleInvalidException> {
                BundleFixture.loader().load(
                    BundleFixture.checkout().copy(authorName = "", authorEmail = ""),
                    BundleKind.SERVED,
                )
            }.diagnostics shouldBe listOf("commit 0123456789ab has no author (name or e-mail): no deployer (D-90)")
        }
    })
