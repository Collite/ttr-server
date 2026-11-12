// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.ledger

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.tatrman.automation.LedgerTestDb
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.ApprovalDependencies
import org.tatrman.automation.bundle.ApprovalFixture
import org.tatrman.automation.bundle.ApprovalFps
import org.tatrman.automation.bundle.Approver
import org.tatrman.automation.bundle.Bundle
import org.tatrman.automation.bundle.BundleKind
import org.tatrman.automation.bundle.BundleLoader
import org.tatrman.automation.bundle.Checkout
import org.tatrman.automation.bundle.Staleness
import org.tatrman.automation.bundle.completeWriterCatalogs
import org.tatrman.automation.identity.AuthorMap
import org.tatrman.automation.identity.Caller
import org.tatrman.automation.identity.ServiceTokens
import org.tatrman.automation.identity.TokenKind
import org.tatrman.automation.runner.Open
import org.tatrman.automation.runner.OpenRequest
import org.tatrman.automation.writer.Ddl
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.tatrman.ttr.metadata.model.RunAsRef as ModelRunAs

/**
 * S2.3 T1 — the approvals writes on a real ledger (D-60, D-13, D-89). No bypass: a fingerprint without an `approved`
 * row refuses the open, the deployment detail's staleness reads the same rows, and the `approvals` view in each world
 * schema of `automation_pg` shows them.
 */
class ApprovalsSpec :
    StringSpec({
        LedgerTestDb.ready()
        val ds = LedgerTestDb.db.getDataSource()
        val recipe = "release_order"
        val program = "release_on_placement"
        val deployment = "release_native_event"
        val caller = Caller("maya-id", "maya", setOf("hartland-sales-managers"), TokenKind.PERSON, "verified")
        val now = Instant.parse("2026-11-12T09:00:00Z")
        val book = LedgerBundles()
        lateinit var bare: Bundle
        lateinit var golden: Bundle

        fun sql(text: String) =
            ds.connection.use { c ->
                c.autoCommit = true
                c.createStatement().use { it.execute(text) }
            }

        fun strings(query: String): List<String> =
            ds.connection.use { c ->
                c.createStatement().use { s ->
                    s.executeQuery(query).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
                }
            }

        beforeSpec {
            val root = ApprovalFixture.copyTo(tempdir().toPath().resolve("approvals"))
            completeWriterCatalogs(root)
            val loaded =
                ApprovalDependencies.prepare(
                    BundleLoader(AuthorMap(emptyMap())).load(
                        Checkout(root, "master", "0123456789abcdef", "po", "po@example.org"),
                        BundleKind.SERVED,
                    ),
                )
            bare =
                loaded.copy(
                    deployments =
                        loaded.deployments +
                            (deployment to loaded.deployments.getValue(deployment).copy(runAs = ModelRunAs.Caller)),
                )
            val r = bare.recipes.getValue(recipe)
            val withTests = r.recipe.copy(tests = listOf("tests/hold_sales.yaml"))
            golden =
                bare.copy(
                    recipes =
                        bare.recipes +
                            (recipe to r.copy(recipe = withTests, manifest = r.manifest.copy(recipe = withTests))),
                )
            Ddl(
                ds,
                { listOf("appr_fixture") },
            ).refresh(golden.copy(recipes = golden.recipes.filterKeys { it == recipe }))
        }
        afterSpec { sql("DROP SCHEMA appr_fixture CASCADE") }
        beforeEach { LedgerTestDb.reset() }

        fun approver(bundle: () -> Bundle) = Approver(bundle, book, { now })

        fun fps() =
            golden.recipes
                .getValue(recipe)
                .recipe.fingerprints

        fun approveAll(
            except: String? = null,
            bundle: Bundle = golden,
        ) {
            val a = approver { bundle }
            val f =
                bundle.recipes
                    .getValue(recipe)
                    .recipe.fingerprints
            if (except != "recipe") a.approve("recipe", recipe, ApprovalFps(f.text, f.values), "recipe", "po")
            if (except != "values") a.approve("recipe", recipe, ApprovalFps(f.text, f.values), "values", "po")
            if (except != "program") {
                a.approve("program", program, ApprovalFps(fp = bundle.programFps.getValue(program)), "program", "po")
            }
            if (except != "deployment") {
                a.approve(
                    "deployment",
                    deployment,
                    ApprovalFps(fp = bundle.deploymentFps.getValue(deployment)),
                    "deployment",
                    "po",
                )
            }
        }

        fun open(bundle: Bundle) =
            Open(
                { bundle },
                book,
                ServiceTokens(emptyMap(), { error("no token exchange") }),
                {
                    org.tatrman.automation.ledger.Claim
                        .Claimed(UUID.randomUUID(), startedAt = now)
                },
                { null },
                { false },
                Duration.ofSeconds(60),
                Duration.ofMinutes(30),
                java.time.Clock.fixed(now, java.time.ZoneOffset.UTC),
            ).open(recipe, OpenRequest(deployment, buildJsonObject { put("key", "4711") }), caller)

        "no approved row for the text fp refuses 409 recipe-stale {differs:[text]}; the deployment detail agrees" {
            approveAll(except = "recipe")
            val e = shouldThrow<AutomationException> { open(golden) }
            e.code shouldBe ErrorCode.RECIPE_STALE
            e.detail!!
                .jsonObject
                .getValue("differs")
                .jsonArray shouldBe JsonArray(listOf(JsonPrimitive("text")))
            e.detail!!
                .jsonObject
                .getValue("approved")
                .jsonObject["text"] shouldBe kotlinx.serialization.json.JsonNull
            val stale = Staleness.of(golden, deployment, book)
            stale.stale shouldBe true
            stale.unapproved shouldContainExactly listOf("recipe $recipe")
            approver { golden }.approve("recipe", recipe, ApprovalFps(fps().text, fps().values), "recipe", "po")
            open(golden).httpStatus shouldBe 201
            Staleness.of(golden, deployment, book).stale shouldBe false
        }

        "the same for values: no approved values row refuses with differs:[values]" {
            approveAll(except = "values")
            val e = shouldThrow<AutomationException> { open(golden) }
            e.code shouldBe ErrorCode.RECIPE_STALE
            e.detail!!
                .jsonObject
                .getValue("differs")
                .jsonArray shouldBe JsonArray(listOf(JsonPrimitive("values")))
            Staleness.of(golden, deployment, book).unapproved shouldContainExactly listOf("values $recipe")
        }

        "a recipe approval without tests/ is 422 golden-required and writes nothing; values needs no golden" {
            val f =
                bare.recipes
                    .getValue(recipe)
                    .recipe.fingerprints
            val e =
                shouldThrow<AutomationException> {
                    approver { bare }.approve("recipe", recipe, ApprovalFps(f.text, f.values), "recipe", "po")
                }
            e.code shouldBe ErrorCode.GOLDEN_REQUIRED
            e.code.status.value shouldBe 422
            strings("SELECT count(*) FROM automation.approvals") shouldBe listOf("0")
            approver { bare }.approve("recipe", recipe, ApprovalFps(f.text, f.values), "values", "po").created shouldBe
                true
            strings("SELECT kind || ':' || status FROM automation.approvals") shouldBe listOf("values:approved")
        }

        "a supplied fp that is not the served one is refused and writes nothing" {
            val e =
                shouldThrow<AutomationException> {
                    approver { golden }.approve(
                        "recipe",
                        recipe,
                        ApprovalFps("sha256:old", fps().values),
                        "recipe",
                        "po",
                    )
                }
            e.code shouldBe ErrorCode.RECIPE_STALE
            strings("SELECT count(*) FROM automation.approvals") shouldBe listOf("0")
        }

        "re-approving is idempotent; a new text supersedes the old row of that kind" {
            val first =
                approver { golden }.approve(
                    "recipe",
                    recipe,
                    ApprovalFps(fps().text, fps().values),
                    "recipe",
                    "po",
                )
            approver { golden }
                .approve("recipe", recipe, ApprovalFps(fps().text, fps().values), "recipe", "bora")
                .let {
                    it.created shouldBe false
                    it.id shouldBe first.id
                    it.approver shouldBe "po"
                }
            val r = golden.recipes.getValue(recipe)
            val moved = r.recipe.copy(fingerprints = r.recipe.fingerprints.copy(text = "sha256:moved"))
            val next = golden.copy(recipes = golden.recipes + (recipe to r.copy(recipe = moved)))
            approver { next }.approve("recipe", recipe, ApprovalFps("sha256:moved", fps().values), "recipe", "po")
            strings(
                "SELECT fp_text || ':' || status FROM automation.approvals ORDER BY status DESC",
            ) shouldContainExactly
                listOf("${fps().text}:superseded", "sha256:moved:approved")
            book.approved("recipe", recipe, fps().text) shouldBe false
            book.approved("recipe", recipe, "sha256:moved") shouldBe true
        }

        "the approvals view in automation_pg shows the row in the stdlib entity's shape" {
            approveAll()
            strings(
                "SELECT object_kind || '|' || object_name || '|' || kind || '|' || fp_text || '|' || " +
                    "approver || '|' || status || '|' || pg_typeof(at)::text FROM appr_fixture.approvals ORDER BY kind",
            ) shouldContainExactly
                listOf(
                    "deployment|$deployment|deployment|${golden.deploymentFps.getValue(deployment)}|po|approved|" +
                        "timestamp without time zone",
                    "program|$program|program|${golden.programFps.getValue(program)}|po|approved|" +
                        "timestamp without time zone",
                    "recipe|$recipe|recipe|${fps().text}|po|approved|timestamp without time zone",
                    "recipe|$recipe|values|${fps().text}|po|approved|timestamp without time zone",
                )
            strings("SELECT fp_values FROM appr_fixture.approvals WHERE kind = 'values'") shouldBe listOf(fps().values)
        }
    })
