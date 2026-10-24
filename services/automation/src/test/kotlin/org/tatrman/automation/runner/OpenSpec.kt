// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.tatrman.automation.api.*
import org.tatrman.automation.bundle.*
import org.tatrman.automation.identity.*
import org.tatrman.automation.ledger.*
import org.tatrman.ttr.parser.model.AutoValue
import org.tatrman.ttr.parser.model.SourceLocation
import org.tatrman.ttr.metadata.model.RunAsRef as ModelRunAs
import java.time.Duration
import java.time.Instant
import java.util.UUID

class OpenSpec :
    StringSpec({
        val original = BundleFixture.load()
        val now = BundleFixture.LOADED_AT
        val caller = Caller("maya-id", "maya", setOf("sales_manager"), TokenKind.PERSON, "verified")
        val recipe = "release_order"
        val deployment = "release_native_event"
        val program = "release_on_placement"

        fun bundle() =
            original.copy(
                deployments =
                    original.deployments +
                        (deployment to original.deployments.getValue(deployment).copy(runAs = ModelRunAs.Caller)),
            )

        fun request(port: JsonObject = buildJsonObject { put("key", "4711") }) = OpenRequest(deployment, port)

        fun refused(
            code: ErrorCode,
            action: () -> Unit,
        ): AutomationException =
            shouldThrow<AutomationException>(action).also {
                it.code shouldBe
                    code
            }

        fun service(
            b: Bundle = bundle(),
            approved: ApprovalReader = ApprovalReader { _, _, _ -> true },
            claim: (RunOpen) -> Claim = { Claim.Claimed(UUID.randomUUID(), startedAt = BundleFixture.clock.instant()) },
            paused: Boolean = false,
            devOff: Boolean = false,
            detailLimit: Int = 200,
        ) = Open(
            {
                b
            },
            approved,
            ServiceTokens(emptyMap(), {
                error("no token exchange at open")
            }),
            claim,
            { "protocol:$it" },
            { paused },
            Duration.ofSeconds(
                60,
            ),
            Duration.ofMinutes(30),
            BundleFixture.clock,
            devOff,
            detailLimit,
        )

        "governed opens refuse action defaults that current fingerprints cannot approve" {
            for (value in listOf<AutoValue>(
                AutoValue.Obj(emptyList(), SourceLocation.UNKNOWN),
                AutoValue.Null(SourceLocation.UNKNOWN),
            )) {
                val b = bundle()
                val r = b.recipes.getValue(recipe)
                val metadata =
                    r.recipe.copy(
                        wiring =
                            listOf(
                                ManifestWiring(listOf("way.release"), "release", mapOf("order" to "order_number")),
                            ),
                    )
                val changed =
                    b.copy(
                        recipes =
                            b.recipes + (
                                recipe to
                                    r.copy(
                                        recipe = metadata,
                                        manifest = r.manifest.copy(recipe = metadata),
                                    )
                            ),
                        actions =
                            b.actions + (
                                "release" to
                                    b.actions.getValue("release").copy(
                                        properties = listOf("writer_defaults" to value),
                                    )
                            ),
                    )
                changed.recipes
                    .getValue(recipe)
                    .recipe.fingerprints shouldBe r.recipe.fingerprints
                changed.programFps shouldBe b.programFps
                changed.deploymentFps shouldBe b.deploymentFps
                refused(ErrorCode.PREVIEW_UNSUPPORTED) {
                    service(changed, claim = { error("must not claim unapproved defaults") })
                        .open(recipe, request(), caller)
                }
                val dev = service(changed, devOff = true).open(recipe, request(), caller)
                dev.httpStatus shouldBe 201
                dev.approvalsBypassed shouldBe true
                val unrelated =
                    changed.copy(
                        recipes =
                            changed.recipes +
                                (recipe to r.copy(recipe = r.recipe.copy(wiring = emptyList()))),
                    )
                service(unrelated).open(recipe, request(), caller).httpStatus shouldBe 201
            }
        }

        "open pins the bundle, model, fingerprints, verified identity, bindings and dry-run before claiming" {
            var recorded: RunOpen? = null
            val id = UUID.randomUUID()
            val result =
                service(claim = {
                    recorded = it
                    Claim.Claimed(id, startedAt = BundleFixture.clock.instant())
                }).open(recipe, request(), caller)
            result.httpStatus shouldBe 201
            result.response.runId shouldBe id.toString()
            result.response.status shouldBe "open"
            result.context!!.keys shouldBe listOf(mapOf("order_number" to "4711"))
            result.context!!.modelFp shouldBe original.modelFp
            result.context!!.identity.principal shouldBe "maya"
            result.context!!
                .bindings
                .getValue("as_of")
                .value shouldBe now
            recorded!!.bundle shouldBe original.id
            recorded!!.fpText shouldBe
                original.recipes
                    .getValue(recipe)
                    .recipe.fingerprints.text
            recorded!!.fpProgram shouldBe original.programFps.getValue(program)
            recorded!!.caller shouldBe "maya"
            recorded!!.callerSubject shouldBe caller.sub
            recorded!!.dryRun shouldBe true // P1 has no writer; false in a request cannot make it wet.
            recorded!!.asOf shouldBe now
        }
        "deployment guard options are pinned at open and recorded in opening policy" {
            val b = bundle()
            val optedIn =
                b.copy(
                    deployments =
                        b.deployments + (
                            deployment to
                                b.deployments.getValue(deployment).copy(allowRestrictedView = true, readCeiling = 17)
                        ),
                )
            var recorded: RunOpen? = null
            val result =
                service(optedIn, claim = {
                    recorded = it
                    Claim.Claimed(UUID.randomUUID(), startedAt = BundleFixture.clock.instant())
                })
                    .open(recipe, request(), caller)
            result.context!!.allowRestrictedView shouldBe true
            result.context!!.readCeiling shouldBe 17
            recorded!!.openingProtocol!!.jsonObject["allowRestrictedView"] shouldBe JsonPrimitive(true)
            recorded!!.openingProtocol!!.jsonObject["readCeiling"] shouldBe JsonPrimitive(17)
            service().open(recipe, request(), caller).context!!.allowRestrictedView shouldBe false
        }

        "detail limit is pinned and journalled before claiming, and invalid limits never claim" {
            var recorded: RunOpen? = null
            val result =
                service(detailLimit = 7, claim = {
                    recorded = it
                    Claim.Claimed(UUID.randomUUID(), startedAt = BundleFixture.clock.instant())
                }).open(recipe, request(), caller)
            result.context!!.detailLimit shouldBe 7
            recorded!!.openingProtocol!!.jsonObject["detailLimit"] shouldBe JsonPrimitive(7)
            service().open(recipe, request(), caller).context!!.detailLimit shouldBe 200
            shouldThrow<IllegalArgumentException> {
                service(detailLimit = 0, claim = { error("must not claim") }).open(recipe, request(), caller)
            }
        }

        "authored action detail limits are pinned before claim and recorded with the opening policy" {
            val b = bundle()
            val loaded = b.recipes.getValue(recipe)
            val action = b.actions.values.first()
            val metadata =
                loaded.recipe.copy(
                    roles =
                        ManifestRoles(
                            routes =
                                listOf(
                                    ManifestRoute("release", detail = listOf("shortfall")),
                                ),
                        ),
                    wiring = listOf(ManifestWiring(listOf("release"), action.qname.name)),
                )
            val limited =
                b.copy(
                    recipes = b.recipes + (recipe to loaded.copy(recipe = metadata)),
                    actions =
                        b.actions + (
                            action.qname.name to
                                action.copy(
                                    properties =
                                        listOf(
                                            "detail_limit" to AutoValue.Num(3.0, SourceLocation.UNKNOWN, "3"),
                                        ),
                                )
                        ),
                )
            var recorded: RunOpen? = null
            val result =
                service(limited, claim = {
                    recorded = it
                    Claim.Claimed(UUID.randomUUID(), startedAt = BundleFixture.clock.instant())
                })
                    .open(recipe, request(), caller)
            result.context!!.detailLimits shouldBe mapOf("release" to 3)
            recorded!!.openingProtocol!!.jsonObject["detailLimits"] shouldBe buildJsonObject { put("release", 3) }
            val invalid =
                limited.copy(
                    actions =
                        limited.actions + (
                            action.qname.name to
                                action.copy(
                                    properties =
                                        listOf(
                                            "detail_limit" to AutoValue.Num(0.0, SourceLocation.UNKNOWN, "0"),
                                        ),
                                )
                        ),
                )
            shouldThrow<IllegalArgumentException> {
                service(invalid, claim = { error("must not claim") }).open(recipe, request(), caller)
            }
        }

        "response matrix: closed replays 200; a live claim returns 202 and no second context" {
            val id = UUID.randomUUID()
            val replay = service(claim = { Claim.Replay(id) }).open(recipe, request(), caller)
            replay.httpStatus shouldBe 200
            replay.response.replayed shouldBe true
            replay.response.protocolRef shouldBe "protocol:$id"
            replay.context shouldBe null
            val running = service(claim = { Claim.Running(id, RunStatus.EVALUATED) }).open(recipe, request(), caller)
            running.httpStatus shouldBe 202
            running.response.status shouldBe "evaluated"
            running.response.location shouldBe "/automation/runs/$id"
            running.context shouldBe null
        }
        "all refusals precede the claim: paused, unknown recipe/deployment, mismatch and disabled" {
            var claims = 0

            fun s(
                b: Bundle = bundle(),
                paused: Boolean = false,
            ) = service(b, claim = {
                claims++
                Claim.Claimed(UUID.randomUUID(), startedAt = BundleFixture.clock.instant())
            }, paused = paused)
            refused(ErrorCode.PAUSED) { s(paused = true).open(recipe, request(), caller) }
            refused(ErrorCode.RECIPE) { s().open("absent", request(), caller) }
            refused(ErrorCode.DEPLOYMENT) { s().open(recipe, request().copy(deployment = "absent"), caller) }
            refused(ErrorCode.PORT) { s().open("decide_return", request(), caller) }
            val b = bundle()
            refused(ErrorCode.DISABLED) {
                s(
                    b.copy(
                        deployments =
                            b.deployments + (deployment to b.deployments.getValue(deployment).copy(enabled = false)),
                    ),
                ).open(recipe, request(), caller)
            }
            claims shouldBe 0
        }
        "identity grants use the deployment world; no grant and unmapped credentials refuse" {
            val noGrant = caller.copy(roles = emptySet())
            refused(ErrorCode.IDENTITY) {
                service(original).open(recipe, request(), noGrant)
            }.detail!!.jsonObject["reason"] shouldBe
                JsonPrimitive("no-grant")
            refused(
                ErrorCode.IDENTITY,
            ) { service(original).open(recipe, request(), caller) }.detail!!.jsonObject["reason"] shouldBe
                JsonPrimitive("unmapped")
        }
        "approvals report each stale class; dev-off still refuses stale exports" {
            for ((kind, code, differs) in listOf(
                Triple("recipe", ErrorCode.RECIPE_STALE, "text"),
                Triple("values", ErrorCode.RECIPE_STALE, "values"),
                Triple("program", ErrorCode.PROGRAM_STALE, "program"),
                Triple("deployment", ErrorCode.DEPLOYMENT_STALE, "deployment"),
            )) {
                val e =
                    refused(code) {
                        service(
                            approved =
                                ApprovalReader {
                                    k,
                                    _,
                                    _,
                                    ->
                                    k != kind
                                },
                        ).open(recipe, request(), caller)
                    }
                e.detail!!.jsonObject["differs"]!!.jsonArray shouldBe JsonArray(listOf(JsonPrimitive(differs)))
            }
            val off = service(approved = ApprovalReader { _, _, _ -> false }, devOff = true)
            off.open(recipe, request(), caller).approvalsBypassed shouldBe true
            refused(ErrorCode.RECIPE_STALE) { off.open(recipe, request().copy(recipeFingerprint = "old"), caller) }
            refused(ErrorCode.PROGRAM_STALE) { off.open(recipe, request().copy(programFingerprint = "old"), caller) }
            refused(
                ErrorCode.DEPLOYMENT_STALE,
            ) { off.open(recipe, request().copy(deploymentFingerprint = "old"), caller) }
        }
        "ports are exact typed shapes: text keys never coerce numbers; empty keys mean no candidates" {
            refused(ErrorCode.PORT) { service().open(recipe, request(buildJsonObject { put("key", 4711) }), caller) }
            refused(ErrorCode.PORT) {
                service().open(
                    recipe,
                    request(
                        buildJsonObject {
                            put("key", "4711")
                            put("none", true)
                        },
                    ),
                    caller,
                )
            }
            refused(
                ErrorCode.PORT,
            ) { service().open(recipe, request(buildJsonObject { put("key", JsonNull) }), caller) }
            val b = bundle()
            val keys = b.copy(programs = b.programs + (program to b.programs.getValue(program).copy(port = "keys")))
            service(keys)
                .open(
                    recipe,
                    request(
                        buildJsonObject {
                            put("keys", JsonArray(emptyList()))
                        },
                    ),
                    caller,
                ).context!!
                .keys shouldBe
                emptyList()
            refused(ErrorCode.PORT) { service().open(recipe, request(buildJsonObject { put("none", true) }), caller) }
        }
        "events validate schema and key types; event time applies only to event programs" {
            val d = "decide_native_event"
            val b =
                bundle().copy(
                    deployments =
                        original.deployments + (d to original.deployments.getValue(d).copy(runAs = ModelRunAs.Caller)),
                )
            val at = "2026-10-20T09:00:00+02:00"
            val req =
                OpenRequest(
                    d,
                    buildJsonObject {
                        putJsonObject("event") { put("line_id", 123) }
                        put("at", at)
                    },
                )
            val opened = service(b).open("decide_return", req, caller)
            opened.context!!.keys shouldBe listOf(mapOf("line_id" to 123L))
            opened.context!!
                .bindings
                .getValue("as_of")
                .value shouldBe Instant.parse("2026-10-20T07:00:00Z")
            refused(ErrorCode.PORT) {
                service(b).open(
                    "decide_return",
                    req.copy(
                        port =
                            buildJsonObject {
                                putJsonObject("event") { put("line_id", "123") }
                            },
                    ),
                    caller,
                )
            }
            refused(ErrorCode.PORT) {
                service(b).open("decide_return", req.copy(port = buildJsonObject { putJsonObject("event") {} }), caller)
            }
            refused(ErrorCode.PORT) {
                service(b).open(
                    "decide_return",
                    req.copy(
                        port =
                            buildJsonObject {
                                putJsonObject("event") {
                                    put("line_id", 123)
                                    put("surprise", 1)
                                }
                            },
                    ),
                    caller,
                )
            }
            val p = "decide_on_request"
            val run = b.copy(programs = b.programs + (p to b.programs.getValue(p).copy(asOf = "run")))
            service(run)
                .open("decide_return", req, caller)
                .context!!
                .bindings
                .getValue("as_of")
                .value shouldBe now
        }
        "literal replay and now are parsed once; malformed or unsupported as-of refuses" {
            val result = service().open(recipe, request().copy(asOf = "2026-10-01T10:00:00+02:00"), caller)
            result.context!!
                .bindings
                .getValue("as_of")
                .value shouldBe Instant.parse("2026-10-01T08:00:00Z")
            service()
                .open(recipe, request().copy(asOf = "now"), caller)
                .context!!
                .bindings
                .getValue("as_of")
                .value shouldBe
                now
            refused(ErrorCode.AS_OF) { service().open(recipe, request().copy(asOf = "yesterday"), caller) }
            refused(ErrorCode.BUNDLE) { service().open(recipe, request().copy(bundle = "preview:x"), caller) }
        }
        "resolved key spellings and event timestamp offsets produce the same derived idempotency key" {
            val recorded = mutableListOf<RunOpen>()
            val s =
                service(claim = {
                    recorded += it
                    Claim.Claimed(UUID.randomUUID(), startedAt = BundleFixture.clock.instant())
                })
            s.open(
                recipe,
                request(
                    buildJsonObject {
                        put("key", "4711")
                        put("at", "2026-10-20T09:00:00+02:00")
                    },
                ),
                caller,
            )
            s.open(
                recipe,
                request(
                    buildJsonObject {
                        putJsonObject("key") { put("order_number", "4711") }
                        put("at", "2026-10-20T07:00:00Z")
                    },
                ),
                caller,
            )
            recorded[0].idempotencyKey shouldBe recorded[1].idempotencyKey
            refused(ErrorCode.AS_OF) { s.open(recipe, request().copy(asOf = "event"), caller) }
            refused(ErrorCode.PORT) { s.open(recipe, request().copy(idempotencyKey = " "), caller) }
        }
        "composite keys require every column and declared scalar types" {
            val b = bundle()
            val r = b.recipes.getValue(recipe)
            val composite =
                b.copy(
                    recipes =
                        b.recipes +
                            (
                                recipe to
                                    r.copy(recipe = r.recipe.copy(key = listOf("order.order_number", "order.customer")))
                            ),
                )
            val valid =
                request(
                    buildJsonObject {
                        putJsonObject("key") {
                            put("order_number", "4711")
                            put("customer", 42)
                        }
                    },
                )
            service(composite).open(recipe, valid, caller).context!!.keys shouldBe
                listOf(mapOf("order_number" to "4711", "customer" to 42L))
            refused(ErrorCode.PORT) { service(composite).open(recipe, request(), caller) }
            refused(ErrorCode.PORT) {
                service(composite).open(
                    recipe,
                    valid.copy(
                        port =
                            buildJsonObject {
                                putJsonObject("key") {
                                    put("order_number", "4711")
                                    put("customer", "42")
                                }
                            },
                    ),
                    caller,
                )
            }
        }
        "process opens use the step port while retaining the selected program identity and fingerprint" {
            val d = "returns_n8n"
            val b =
                original.copy(
                    deployments =
                        original.deployments + (d to original.deployments.getValue(d).copy(runAs = ModelRunAs.Caller)),
                )
            var row: RunOpen? = null
            val result =
                service(b, claim = {
                    row = it
                    Claim.Claimed(UUID.randomUUID(), startedAt = BundleFixture.clock.instant())
                }).open(
                    "decide_return",
                    OpenRequest(d, buildJsonObject { put("key", 123) }),
                    caller,
                )
            result.context!!.keys shouldBe listOf(mapOf("line_id" to 123L))
            row!!.program shouldBe "decide_on_request"
            row!!.fpProgram shouldBe original.programFps.getValue("decide_on_request")
        }
        "the execution start is the committed claim time rather than request time or user asOf" {
            val started = now.plusSeconds(8)
            val result =
                service(claim = { Claim.Claimed(UUID.randomUUID(), startedAt = started) })
                    .open(recipe, request().copy(asOf = now.minusSeconds(86400).toString()), caller)
            result.context!!.startedAt shouldBe started
            result.context!!
                .bindings
                .getValue("as_of")
                .value shouldBe now.minusSeconds(86400)
        }

        "program budgets and overrides resolve before the claim; debug runs use the verified caller" {
            val b = bundle()
            val p = b.programs.getValue(program)
            val configured =
                b.copy(
                    programs =
                        b.programs + (
                            program to
                                p.copy(
                                    budgetSync =
                                        org.tatrman.ttr.metadata.model
                                            .AutomationDuration(0.25, "seconds"),
                                    budgetBatch =
                                        org.tatrman.ttr.metadata.model
                                            .AutomationDuration(2.0, "minutes"),
                                    overrides =
                                        listOf(
                                            "tolerance" to
                                                org.tatrman.ttr.parser.model.AutoValue
                                                    .Num(
                                                        0.04,
                                                        org.tatrman.ttr.parser.model.SourceLocation.UNKNOWN,
                                                        "0.04",
                                                    ),
                                        ),
                                )
                        ),
                )
            var row: RunOpen? = null
            val result =
                service(configured, claim = {
                    row = it
                    Claim.Claimed(UUID.randomUUID(), startedAt = BundleFixture.clock.instant())
                }).open(recipe, request(), caller)
            row!!.budgetSync shouldBe Duration.ofMillis(250)
            row!!.budgetBatch shouldBe Duration.ofMinutes(2)
            result.context!!.syncBudget shouldBe Duration.ofMillis(250)
            row!!.openingProtocol!!.jsonObject["budgetSyncMs"] shouldBe JsonPrimitive(250)
            row!!.openingProtocol!!.jsonObject["budgetBatchMs"] shouldBe JsonPrimitive(120000)
            result.context!!
                .bindings
                .getValue("tolerance")
                .value shouldBe java.math.BigDecimal("0.04")
            val debug =
                original.copy(
                    deployments =
                        original.deployments +
                            (deployment to original.deployments.getValue(deployment).copy(debug = true)),
                )
            service(
                debug,
            ).open(recipe, request(), caller.copy(roles = emptySet())).context!!.identity.principal shouldBe
                "maya"
        }
        "derived keys are scoped and stable; supplied keys survive refresh" {
            val opens = mutableListOf<RunOpen>()
            val s =
                service(claim = {
                    opens += it
                    Claim.Claimed(UUID.randomUUID(), startedAt = BundleFixture.clock.instant())
                })
            s.open(recipe, request(), caller)
            s.open(recipe, request(), caller)
            opens[0].idempotencyKey shouldBe opens[1].idempotencyKey
            s.open(recipe, request(), caller.copy(sub = "other", principal = "other"))
            (opens[0].idempotencyKey == opens[2].idempotencyKey) shouldBe false
            s.open(recipe, request().copy(idempotencyKey = "engine:1"), caller)
            val changed = bundle().copy(modelFp = "new-model")
            service(changed, claim = {
                opens += it
                Claim.Claimed(UUID.randomUUID(), startedAt = BundleFixture.clock.instant())
            }).open(recipe, request().copy(idempotencyKey = "engine:1"), caller)
            opens[3].idempotencyKey shouldBe opens[4].idempotencyKey
        }
    })
