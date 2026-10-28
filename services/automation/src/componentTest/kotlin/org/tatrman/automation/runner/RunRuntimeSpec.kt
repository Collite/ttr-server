// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.runner

import com.google.protobuf.ByteString
import com.typesafe.config.ConfigFactory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempdir
import kotlin.io.path.readText
import kotlin.io.path.writeText
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.tatrman.automation.LedgerTestDb
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.*
import org.tatrman.automation.config.AutomationConfig
import org.tatrman.automation.identity.*
import org.tatrman.automation.ledger.*
import org.tatrman.automation.staging.LocalStagingStore
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.ValuesNode
import org.tatrman.query.v1.CompileResponse
import org.tatrman.query.v1.ErReadScope
import org.tatrman.query.v1.RunRequest
import org.tatrman.worker.v1.ResultBatch
import org.tatrman.ttr.metadata.model.AutomationColumn
import org.tatrman.ttr.metadata.model.ErSchema
import org.tatrman.ttr.metadata.model.WorldSchema
import org.tatrman.ttr.parser.model.AutoValue
import org.tatrman.ttr.parser.model.AutoEntry
import org.tatrman.ttr.parser.model.Reference
import org.tatrman.ttr.parser.model.SourceLocation
import java.time.OffsetDateTime
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.tatrman.ttr.metadata.model.RunAsRef as ModelRunAs

/** Real ledger + default pgmq adapter + workers + executor; only Query is a canned governed dependency. */
class RunRuntimeComponentSpec :
    StringSpec({
        LedgerTestDb.ready()
        beforeEach { LedgerTestDb.reset() }

        fun calendarFixture(
            warnings: Set<String> = emptySet(),
            readsScope: List<String> = listOf("er.entity.date_dim"),
            transform: (Bundle) -> Bundle = { it },
        ): DurableRuntimeFixture {
            val root =
                CalendarFixture.copyTo(
                    tempdir(prefix = "calendar-runtime-").toPath().resolve("arbitrary-branch"),
                )
            val loaded =
                BundleLoader(
                    AuthorMap(emptyMap()),
                ).load(Checkout(root, "master", "0123456789abcdef", "po", "po@example.org"), BundleKind.SERVED)
            return DurableRuntimeFixture(
                lookupReads = readsScope,
                queryWarnings = { if ("FROM er.entity.date_dim cal" in it.source) warnings else emptySet() },
                transformBundle = {
                    transform(
                        loaded.copy(
                            deployments =
                                loaded.deployments +
                                    (
                                        "release_native_event" to
                                            loaded.deployments
                                                .getValue(
                                                    "release_native_event",
                                                ).copy(runAs = ModelRunAs.Caller)
                                    ),
                        ),
                    )
                },
                queryTable = { request ->
                    request.world shouldBe "us-erp"
                    request.context.modelVersion shouldBe loaded.modelFp
                    request.context.userId shouldBe "maya"
                    when {
                        "FROM er.entity.date_dim cal" in request.source -> {
                            request.allowedErReads.objectsList.contains("er.entity.date_dim") shouldBe true
                            request.context.parametersCount shouldBe 0
                            Table(
                                listOf(ManifestColumn("cal_date", "date"), ManifestColumn("working_day", "bool")),
                                (23..28).map { day ->
                                    listOf(
                                        java.time.LocalDate.of(2026, 10, day),
                                        day !in setOf(24, 25, 27),
                                    )
                                },
                            )
                        }
                        "verdict_reason" in request.source ->
                            Table(
                                listOf(
                                    ManifestColumn("order_number", "text"),
                                    ManifestColumn("placed", "date"),
                                    ManifestColumn("route", "text"),
                                    ManifestColumn("verdict_reason", "text"),
                                ),
                                listOf(
                                    listOf("4711", java.time.LocalDate.of(2026, 10, 23), "release", "calendar fixture"),
                                ),
                            )
                        else ->
                            Table(
                                listOf(ManifestColumn("order_number", "text"), ManifestColumn("placed", "date")),
                                listOf(listOf("4711", java.time.LocalDate.of(2026, 10, 23))),
                            )
                    }
                },
            )
        }

        "published calendar fixture opens evaluates and journals one immutable snapshot through retry close restart" {
            val f = calendarFixture()
            val runtime = f.start()
            try {
                val opened =
                    runtime.sessions.open(
                        "release_order",
                        f.request.copy(asOf = "2026-10-28T12:00:00Z"),
                        f.caller,
                    )
                opened.httpStatus shouldBe 201
                opened.approvalsBypassed shouldBe false
                val id = UUID.fromString(opened.response.runId)
                val result = runtime.sessions.evaluate(id, f.caller)
                val actions = runtime.status.read(id, f.caller).actions
                actions.single().arguments["template"] shouldBe JsonPrimitive("2")
                f.reads.get() shouldBe 3
                transaction { RunOutputs.selectAll().count() } shouldBe 3L
                runtime.sessions.evaluate(id, f.caller) shouldBe result
                f.reads.get() shouldBe 3
                val protocol = runtime.protocol.read(id, f.caller)
                val openedEvidence =
                    protocol.sections.values
                        .flatten()
                        .map { it.payload }
                        .filterIsInstance<JsonObject>()
                        .single { it["event"] == JsonPrimitive("opened") }
                openedEvidence["calendar"]!!.jsonObject["entity"] shouldBe JsonPrimitive("er.entity.date_dim")
                openedEvidence["calendar"]!!.jsonObject["storage"] shouldBe JsonPrimitive("erp_db")
                val read =
                    protocol.sections.values
                        .flatten()
                        .map { it.payload }
                        .filterIsInstance<JsonObject>()
                        .single { it["statement"] == JsonPrimitive("runtime:calendar-snapshot/v1") }
                read["storage"] shouldBe JsonPrimitive("erp_db")
                read["rows"] shouldBe JsonPrimitive(6)
                runtime.closer.close(id, f.caller)
                val sealed = runtime.protocol.read(id, f.caller)
                runtime.shutdown()
                val restarted = f.start()
                try {
                    restarted.status.read(id, f.caller).actions shouldBe actions
                    restarted.protocol.read(id, f.caller) shouldBe sealed
                    f.reads.get() shouldBe 3
                } finally {
                    restarted.shutdown()
                }
            } finally {
                runtime.shutdown()
                f.close()
            }
        }

        "calendar scope and placement refuse before claim and preserve idempotency" {
            for (missingScope in listOf(true, false)) {
                val f =
                    calendarFixture(transform = { bundle ->
                        if (missingScope) {
                            val loaded = bundle.recipes.getValue("release_order")
                            val metadata =
                                loaded.recipe.copy(
                                    objectsRead = loaded.recipe.objectsRead - "er.entity.date_dim",
                                )
                            bundle.copy(
                                recipes =
                                    bundle.recipes +
                                        (
                                            "release_order" to
                                                loaded.copy(
                                                    recipe = metadata,
                                                    manifest = loaded.manifest.copy(recipe = metadata),
                                                )
                                        ),
                            )
                        } else {
                            val schema = bundle.model.schemas.getValue("world") as WorldSchema
                            val world = schema.worlds.values.single { it.qname.name == "demo" }
                            val changed = world.copy(storages = world.storages.filterNot { it.qname.name == "erp_db" })
                            bundle.copy(
                                model =
                                    bundle.model.copy(
                                        schemas =
                                            bundle.model.schemas +
                                                (
                                                    "world" to
                                                        schema.copy(worlds = schema.worlds + (world.qname to changed))
                                                ),
                                    ),
                            )
                        }
                    })
                val runtime = f.start()
                try {
                    repeat(2) {
                        shouldThrow<AutomationException> {
                            runtime.sessions.open(
                                "release_order",
                                f.request.copy(idempotencyKey = "missing-calendar"),
                                f.caller,
                            )
                        }.code shouldBe if (missingScope) ErrorCode.OUT_OF_SCOPE_READ else ErrorCode.PREVIEW_UNSUPPORTED
                    }
                    transaction { Runs.selectAll().count() } shouldBe 0L
                    f.reads.get() shouldBe 0
                } finally {
                    runtime.shutdown()
                    f.close()
                }
            }
        }

        "calendar narrowed capped and undeclared reads refuse without batch or action completion" {
            for ((warning, scope, expected) in listOf(
                Triple(setOf("security_predicate_applied"), listOf("er.entity.date_dim"), ErrorCode.RESTRICTED_VIEW),
                Triple(setOf("top_n_applied"), listOf("er.entity.date_dim"), ErrorCode.CANDIDATES_TRUNCATED),
                Triple(emptySet<String>(), listOf("er.entity.customer"), ErrorCode.OUT_OF_SCOPE_READ),
            )) {
                val f =
                    calendarFixture(warning, scope, transform = { bundle ->
                        val deployment = bundle.deployments.getValue("release_native_event")
                        bundle.copy(
                            deployments =
                                bundle.deployments +
                                    ("release_native_event" to deployment.copy(allowRestrictedView = true)),
                        )
                    })
                val runtime = f.start()
                try {
                    val opened = runtime.sessions.open("release_order", f.request, f.caller)
                    val id = UUID.fromString(opened.response.runId)
                    shouldThrow<AutomationException> { runtime.sessions.evaluate(id, f.caller) }.code shouldBe expected
                    transaction { Batches.selectAll().count() } shouldBe 0L
                    runtime.status.read(id, f.caller).actions shouldBe emptyList()
                } finally {
                    runtime.shutdown()
                    f.close()
                }
            }
        }

        for (unqualified in listOf(false, true)) {
            "governed ${if (unqualified) "unqualified" else "qualified"} effects evaluate and stale metadata refuses" {
                val root =
                    ApprovalFixture.copyTo(
                        tempdir(prefix = "automation-bundles-").toPath().resolve("branch"),
                        unqualified,
                    )
                val source =
                    BundleLoader(AuthorMap(emptyMap())).load(
                        Checkout(root, "master", "0123456789abcdef", "po", "po@example.org"),
                        BundleKind.SERVED,
                    )

                fun selected(bundle: Bundle) =
                    bundle.copy(
                        deployments =
                            bundle.deployments + (
                                "release_native_event" to
                                    bundle.deployments.getValue("release_native_event").copy(runAs = ModelRunAs.Caller)
                            ),
                    )
                val f =
                    DurableRuntimeFixture(transformBundle = { selected(source) }, queryTable = { request ->
                        if ("approved writer fixture" in request.source) {
                            Table(
                                listOf(
                                    ManifestColumn("order_number", "text"),
                                    ManifestColumn("route", "text"),
                                    ManifestColumn("verdict_reason", "text"),
                                ),
                                listOf(listOf("4711", "release", "approved writer fixture")),
                            )
                        } else {
                            Table(listOf(ManifestColumn("order_number", "text")), listOf(listOf("4711")))
                        }
                    })
                val runtime = f.start()
                try {
                    val opened = runtime.sessions.open("release_order", f.request, f.caller)
                    opened.httpStatus shouldBe 201
                    opened.approvalsBypassed shouldBe false
                    transaction { Runs.selectAll().count() } shouldBe 1L
                    val id = UUID.fromString(opened.response.runId)
                    runtime.sessions.evaluate(id, f.caller)
                    val batch = runtime.status.batch(id, f.caller)
                    val effect = batch.rows.single { it.entity == "er.entity.order" && it.op == "insert" }
                    val transition = batch.rows.single { it.entity == "er.entity.order" && it.op == "transition" }
                    transition.key shouldBe buildJsonObject { put("order_number", "4711") }
                    transition.to shouldBe JsonPrimitive("held")
                    val update = batch.rows.single { it.entity == "er.entity.order" && it.op == "update" }
                    update.key shouldBe transition.key
                    update.set shouldBe buildJsonObject { put("held_for", "Sales") }
                    effect.row!!.getValue("state") shouldBe JsonPrimitive("released")
                    effect.row!!.keys shouldBe setOf("order_number", "state", "customer", "value", "held_for")
                    runtime.status
                        .read(id, f.caller)
                        .actions
                        .single { it.action == "release" }
                        .would shouldBe
                        "would release order 4711 as released"
                    transaction { Batches.selectAll().count() } shouldBe 1L
                    f.reads.get() shouldBe 2
                } finally {
                    runtime.shutdown()
                    f.close()
                }
                val actions = root.resolve("automation/automation.ttrm")
                actions.writeText(actions.readText().replace("'released'", "'held'"))
                val changed =
                    BundleLoader(AuthorMap(emptyMap())).load(
                        Checkout(root, "master", "fedcba9876543210", "po", "po@example.org"),
                        BundleKind.SERVED,
                    )
                changed.recipes
                    .getValue("release_order")
                    .recipe.fingerprints shouldBe
                    source.recipes
                        .getValue("release_order")
                        .recipe.fingerprints
                val stale = DurableRuntimeFixture(transformBundle = { selected(changed) })
                val refused = stale.start()
                try {
                    shouldThrow<AutomationException> {
                        refused.sessions.open("release_order", stale.request, stale.caller)
                    }.code shouldBe ErrorCode.RECIPE_STALE
                    transaction { Runs.selectAll().count() } shouldBe 1L
                    transaction { RunOutputs.selectAll().count() } shouldBe 2L
                    transaction { Batches.selectAll().count() } shouldBe 1L
                    stale.reads.get() shouldBe 0
                } finally {
                    refused.shutdown()
                    stale.close()
                }
            }
        }

        fun obligationFixture(conflict: Boolean = false): DurableRuntimeFixture {
            val root =
                ApprovalFixture.copyTo(
                    tempdir(prefix = "automation-bundles-").toPath().resolve("branch"),
                    obligations = true,
                    obligationConflict = conflict,
                )
            val source =
                BundleLoader(AuthorMap(emptyMap())).load(
                    Checkout(root, "master", "0123456789abcdef", "po", "po@example.org"),
                    BundleKind.SERVED,
                )
            return DurableRuntimeFixture(transformBundle = {
                source.copy(
                    deployments =
                        source.deployments + (
                            "release_native_event" to
                                source.deployments.getValue("release_native_event").copy(runAs = ModelRunAs.Caller)
                        ),
                )
            }, queryTable = { request ->
                if ("approved writer fixture" in request.source) {
                    Table(
                        listOf(
                            ManifestColumn("order_number", "text"),
                            ManifestColumn("route", "text"),
                            ManifestColumn("verdict_reason", "text"),
                        ),
                        listOf(listOf("4711", "release", "approved writer fixture")),
                    )
                } else {
                    Table(listOf(ManifestColumn("order_number", "text")), listOf(listOf("4711")))
                }
            })
        }

        fun fulfilmentFixture(
            ambiguous: Boolean = false,
            lookupReads: List<String> = listOf("er.entity.obligation"),
            lookupWarnings: Set<String> = emptySet(),
            transform: (Bundle) -> Bundle = { it },
        ): DurableRuntimeFixture {
            val root =
                ApprovalFixture.copyTo(
                    tempdir(prefix = "automation-bundles-").toPath().resolve("no-package-in-path"),
                    fulfilment = true,
                )
            val source =
                transform(
                    BundleLoader(
                        AuthorMap(emptyMap()),
                    ).load(Checkout(root, "master", "0123456789abcdef", "po", "po@example.org"), BundleKind.SERVED),
                )
            return DurableRuntimeFixture(lookupReads = lookupReads, queryWarnings = { request ->
                if ("_au_obligation_entity_" in request.source) lookupWarnings else emptySet()
            }, transformBundle = {
                source.copy(
                    deployments =
                        source.deployments +
                            (
                                "release_native_event" to
                                    source.deployments.getValue("release_native_event").copy(runAs = ModelRunAs.Caller)
                            ),
                )
            }, queryTable = { request ->
                when {
                    "_au_obligation_entity_" in request.source -> {
                        request.world shouldBe "us-automation"
                        request.allowedErReads.objectsList.contains("er.entity.obligation") shouldBe true
                        request.context.userId shouldBe "maya"
                        request.context.modelVersion shouldBe source.modelFp
                        request.context.parametersList
                            .map { it.value.stringValue }
                            .toSet() shouldBe
                            setOf("er.entity.return_request_line", "inbound", "{\"line_id\":9007199254740993}")
                        Table(
                            listOf("id", "entity", "key", "kind", "state").map { ManifestColumn(it, "text") },
                            (if (ambiguous) listOf("ob-1", "ob-2") else listOf("ob-1")).map { id ->
                                listOf(
                                    id,
                                    "er.entity.return_request_line",
                                    "{\"line_id\":9007199254740993}",
                                    "inbound",
                                    "overdue",
                                )
                            },
                        )
                    }
                    "FROM \"obligation\"" in request.source ->
                        Table(
                            listOf(
                                "id",
                                "recipe",
                                "run",
                                "entity",
                                "key",
                                "kind",
                                "due",
                                "state",
                                "fulfilled_by",
                                "fulfilled_at",
                                "created_at",
                            ).map { name ->
                                ManifestColumn(
                                    name,
                                    when (name) {
                                        "due" -> "date"
                                        "fulfilled_at", "created_at" -> "timestamp"
                                        else -> "text"
                                    },
                                )
                            },
                            listOf(
                                listOf(
                                    "ob-1",
                                    "past_recipe",
                                    "past_run",
                                    "er.entity.return_request_line",
                                    "{\"line_id\":9007199254740993}",
                                    "inbound",
                                    java.time.LocalDate.parse("2000-01-15"),
                                    "overdue",
                                    null,
                                    null,
                                    java.time.LocalDateTime.parse("2000-01-01T12:00:00"),
                                ),
                            ),
                        )
                    "approved writer fixture" in request.source ->
                        Table(
                            listOf(
                                ManifestColumn("order_number", "text"),
                                ManifestColumn("route", "text"),
                                ManifestColumn("verdict_reason", "text"),
                            ),
                            listOf(listOf("4711", "release", "approved writer fixture")),
                        )
                    else -> Table(listOf(ManifestColumn("order_number", "text")), listOf(listOf("4711")))
                }
            })
        }

        "invalid hosts or missing loader packages refuse governed fulfilment before a run is claimed" {
            for (missingPackages in listOf(false, true)) {
                val f =
                    fulfilmentFixture(transform = { bundle ->
                        if (missingPackages) {
                            bundle.copy(packages = emptyMap())
                        } else {
                            val schema = bundle.model.schemas.getValue("world") as WorldSchema
                            val worlds =
                                schema.worlds.mapValues { (_, world) ->
                                    world.copy(
                                        storages =
                                            world.storages.map {
                                                if (it.qname.name == "erp_db") it.copy(hosts = listOf("hart")) else it
                                            },
                                    )
                                }
                            bundle.copy(
                                model =
                                    bundle.model.copy(
                                        schemas =
                                            bundle.model.schemas +
                                                ("world" to schema.copy(worlds = worlds)),
                                    ),
                            )
                        }
                    })
                val runtime = f.start()
                try {
                    // Missing packages already invalidate approval coverage; invalid hosts reach placement.
                    repeat(2) {
                        shouldThrow<AutomationException> {
                            runtime.sessions.open(
                                "release_order",
                                f.request.copy(idempotencyKey = "invalid-scope"),
                                f.caller,
                            )
                        }.code shouldBe if (missingPackages) ErrorCode.RECIPE_STALE else ErrorCode.PREVIEW_UNSUPPORTED
                    }
                    transaction { Runs.selectAll().count() } shouldBe 0L
                    transaction { RunOutputs.selectAll().count() } shouldBe 0L
                    transaction { Batches.selectAll().count() } shouldBe 0L
                    f.reads.get() shouldBe 0
                } finally {
                    runtime.shutdown()
                    f.close()
                }
            }
        }

        "missing obligation read scope refuses repeated Open before claiming or reading" {
            val f =
                fulfilmentFixture(transform = { bundle ->
                    bundle.copy(
                        recipes =
                            bundle.recipes.mapValues { (_, loaded) ->
                                val recipe =
                                    loaded.recipe.copy(
                                        objectsRead =
                                            loaded.recipe.objectsRead - "er.entity.obligation",
                                    )
                                loaded.copy(recipe = recipe, manifest = loaded.manifest.copy(recipe = recipe))
                            },
                    )
                })
            val runtime = f.start()
            try {
                repeat(2) {
                    shouldThrow<AutomationException> {
                        runtime.sessions.open(
                            "release_order",
                            f.request.copy(idempotencyKey = "missing-obligation-scope"),
                            f.caller,
                        )
                    }.code shouldBe ErrorCode.OUT_OF_SCOPE_READ
                }
                transaction { Runs.selectAll().count() } shouldBe 0L
                transaction { RunOutputs.selectAll().count() } shouldBe 0L
                transaction { Batches.selectAll().count() } shouldBe 0L
                f.reads.get() shouldBe 0
            } finally {
                runtime.shutdown()
                f.close()
            }
        }

        "two governed Opens pin the same cached obligation storage" {
            val f = fulfilmentFixture()
            val runtime = f.start()
            try {
                val storage = mutableListOf<JsonElement?>()
                for (key in listOf("cache-first", "cache-second")) {
                    val opened = runtime.sessions.open("release_order", f.request.copy(idempotencyKey = key), f.caller)
                    opened.httpStatus shouldBe 201
                    val id = UUID.fromString(opened.response.runId)
                    runtime.sessions.evaluate(id, f.caller)
                    val lookup =
                        runtime.protocol
                            .read(id, f.caller)
                            .sections.values
                            .flatten()
                            .map { it.payload }
                            .filterIsInstance<JsonObject>()
                            .single { it["statement"] == JsonPrimitive("runtime:obligation-lookup/v2") }
                    storage += lookup["storage"]
                }
                storage shouldBe listOf(JsonPrimitive("automation_db"), JsonPrimitive("automation_db"))
                transaction { Runs.selectAll().count() } shouldBe 2L
                f.reads.get() shouldBe 6
            } finally {
                runtime.shutdown()
                f.close()
            }
        }

        "governed qualified fulfilment journals a scoped snapshot and persists transition through retry close restart" {
            val f = fulfilmentFixture()
            val runtime = f.start()
            try {
                val opened = runtime.sessions.open("release_order", f.request, f.caller)
                opened.httpStatus shouldBe 201
                opened.approvalsBypassed shouldBe false
                val id = UUID.fromString(opened.response.runId)
                val result = runtime.sessions.evaluate(id, f.caller)
                val batch = runtime.status.batch(id, f.caller)
                val row = batch.rows.single { it.entity == "er.entity.obligation" }
                row.op shouldBe "transition"
                row.key shouldBe buildJsonObject { put("id", "ob-1") }
                row.from shouldBe listOf("overdue")
                row.to shouldBe JsonPrimitive("fulfilled")
                row.history shouldBe true
                val actions = runtime.status.read(id, f.caller).actions
                actions.filter { it.action == "fulfil" }.size shouldBe 2
                actions.filter { it.action == "fulfil" }.forEach { it.batchRows shouldBe listOf(row.seq) }
                f.reads.get() shouldBe 3
                transaction { RunOutputs.selectAll().count() } shouldBe 3L
                runtime.sessions.evaluate(id, f.caller) shouldBe result
                runtime.status.batch(id, f.caller) shouldBe batch
                runtime.closer.close(id, f.caller)
                val protocol = runtime.protocol.read(id, f.caller)
                val lookup =
                    protocol.sections.values
                        .flatten()
                        .map { it.payload }
                        .filterIsInstance<JsonObject>()
                        .single { it["statement"] == JsonPrimitive("runtime:obligation-lookup/v2") }
                lookup["name"] shouldBe JsonPrimitive("_au_obligations")
                lookup["storage"] shouldBe JsonPrimitive("automation_db")
                lookup["rows"] shouldBe JsonPrimitive(1)
                lookup["params"]!!.jsonObject.keys shouldBe
                    setOf("_au_obligation_entity_0", "_au_obligation_kind_0", "_au_obligation_key_0")
                runtime.shutdown()
                val restarted = f.start()
                try {
                    restarted.status.batch(id, f.caller) shouldBe batch
                    restarted.status.read(id, f.caller).actions shouldBe actions
                    restarted.protocol.read(id, f.caller) shouldBe protocol
                    f.reads.get() shouldBe 3
                } finally {
                    restarted.shutdown()
                }
            } finally {
                runtime.shutdown()
                f.close()
            }
        }

        "restricted lookup refuses despite deployment opt in before decision completion" {
            val f =
                fulfilmentFixture(lookupWarnings = setOf("security_predicate_applied"), transform = { bundle ->
                    bundle.copy(
                        deployments =
                            bundle.deployments.mapValues { (_, d) ->
                                d.copy(allowRestrictedView = true)
                            },
                    )
                })
            val runtime = f.start()
            try {
                val opened = runtime.sessions.open("release_order", f.request, f.caller)
                val id = UUID.fromString(opened.response.runId)
                repeat(2) {
                    val error = shouldThrow<AutomationException> { runtime.sessions.evaluate(id, f.caller) }
                    error.code shouldBe ErrorCode.RESTRICTED_VIEW
                    error.message shouldBe "obligation lookup requires a complete snapshot"
                }
                transaction { Batches.selectAll().count() } shouldBe 0L
                runtime.status.read(id, f.caller).actions shouldBe emptyList()
                shouldThrow<AutomationException> { runtime.status.batch(id, f.caller) }.code shouldBe
                    ErrorCode.BATCH_NOT_READY
                runtime.status.read(id, f.caller).verdict shouldBe null
                f.reads.get() shouldBe 3
                // Earlier verified reads remain; the refused lookup never stages narrowed Arrow for matching.
                transaction { RunOutputs.selectAll().count() } shouldBe 2L
                runtime.protocol
                    .read(id, f.caller)
                    .sections.values
                    .flatten()
                    .isNotEmpty() shouldBe true
                runtime.status
                    .read(id, f.caller)
                    .error!!
                    .jsonObject["code"] shouldBe JsonPrimitive("restricted-view")
            } finally {
                runtime.shutdown()
                f.close()
            }
        }

        "ambiguous governed fulfilment retains read evidence without completing any batch or action" {
            val f = fulfilmentFixture(ambiguous = true)
            val runtime = f.start()
            try {
                val opened = runtime.sessions.open("release_order", f.request, f.caller)
                val id = UUID.fromString(opened.response.runId)
                repeat(2) {
                    shouldThrow<AutomationException> { runtime.sessions.evaluate(id, f.caller) }.code shouldBe
                        ErrorCode.PREVIEW_UNSUPPORTED
                }
                transaction { Batches.selectAll().count() } shouldBe 0L
                transaction { RunOutputs.selectAll().count() } shouldBe 3L
                runtime.status.read(id, f.caller).actions shouldBe emptyList()
                runtime.status.read(id, f.caller).status shouldBe "failed"
                f.reads.get() shouldBe 3
            } finally {
                runtime.shutdown()
                f.close()
            }
        }

        "undeclared auxiliary compiler footprint refuses before the lookup executes or a batch commits" {
            val f = fulfilmentFixture(lookupReads = listOf("er.entity.unapproved"))
            val runtime = f.start()
            try {
                val opened = runtime.sessions.open("release_order", f.request, f.caller)
                val id = UUID.fromString(opened.response.runId)
                repeat(2) {
                    shouldThrow<AutomationException> { runtime.sessions.evaluate(id, f.caller) }.code shouldBe
                        ErrorCode.OUT_OF_SCOPE_READ
                }
                f.reads.get() shouldBe 2
                transaction { RunOutputs.selectAll().count() } shouldBe 2L
                transaction { Batches.selectAll().count() } shouldBe 0L
                runtime.status.read(id, f.caller).actions shouldBe emptyList()
                runtime.status.read(id, f.caller).status shouldBe "failed"
            } finally {
                runtime.shutdown()
                f.close()
            }
        }

        "qualified obligation preview persists exact keys and pinned dates through evaluate retry close and restart" {
            val f = obligationFixture()
            f.base.now = Instant.parse("2000-01-01T12:00:00.123456Z")
            val runtime = f.start()
            try {
                val opened = runtime.sessions.open("release_order", f.request, f.caller)
                opened.httpStatus shouldBe 201
                opened.approvalsBypassed shouldBe false
                val id = UUID.fromString(opened.response.runId)
                val startedAt = opened.context!!.startedAt
                f.base.now = f.base.now.plusSeconds(1)
                runtime.sessions.evaluate(id, f.caller)
                val batch = runtime.status.batch(id, f.caller)
                val row = batch.rows.single { it.entity == "er.entity.obligation" }
                val data = row.row!!
                data["due"] shouldBe JsonPrimitive("2000-01-15")
                data["run"] shouldBe JsonPrimitive(id.toString())
                data["state"] shouldBe JsonPrimitive("open")
                java.time.LocalDateTime
                    .parse(
                        data.getValue("created_at").jsonPrimitive.content,
                    ).toInstant(ZoneOffset.UTC) shouldBe
                    startedAt
                Json.parseToJsonElement(data.getValue("key").jsonPrimitive.content) shouldBe
                    buildJsonObject { put("line_id", 9007199254740993L) }
                val actions = runtime.status.read(id, f.caller).actions
                val expectations = actions.filter { it.sink == "obligation" }
                expectations.size shouldBe 2
                expectations.forEach { it.batchRows shouldBe listOf(row.seq) }
                runtime.sessions.evaluate(id, f.caller)
                runtime.status.batch(id, f.caller) shouldBe batch
                f.reads.get() shouldBe 2
                runtime.closer.close(id, f.caller)
                val protocol = runtime.protocol.read(id, f.caller)
                runtime.shutdown()
                val restarted = f.start()
                try {
                    restarted.status.batch(id, f.caller) shouldBe batch
                    restarted.status.read(id, f.caller).actions shouldBe actions
                    restarted.protocol.read(id, f.caller) shouldBe protocol
                    transaction { Batches.selectAll().count() } shouldBe 1L
                    f.reads.get() shouldBe 2
                } finally {
                    restarted.shutdown()
                }
            } finally {
                runtime.shutdown()
                f.close()
            }
        }

        "conflicting obligation due dates leave no durable preview or action evidence" {
            val f = obligationFixture(conflict = true)
            val runtime = f.start()
            try {
                val opened = runtime.sessions.open("release_order", f.request, f.caller)
                val id = UUID.fromString(opened.response.runId)
                repeat(2) {
                    shouldThrow<AutomationException> { runtime.sessions.evaluate(id, f.caller) }.code shouldBe
                        ErrorCode.PREVIEW_UNSUPPORTED
                }
                transaction { Batches.selectAll().count() } shouldBe 0L
                runtime.status.read(id, f.caller).actions shouldBe emptyList()
                runtime.status.read(id, f.caller).status shouldBe "failed"
                f.reads.get() shouldBe 2
            } finally {
                runtime.shutdown()
                f.close()
            }
        }

        "governed generated bigint keys persist through retry close restart and reject stale declarations" {
            val root =
                ApprovalFixture.copyTo(
                    tempdir(prefix = "automation-bundles-").toPath().resolve("generated"),
                    generatedIds = true,
                )

            fun load() =
                BundleLoader(AuthorMap(emptyMap())).load(
                    Checkout(root, "master", "0123456789abcdef", "po", "po@example.org"),
                    BundleKind.SERVED,
                )

            fun selected(bundle: Bundle) =
                bundle.copy(
                    deployments =
                        bundle.deployments + (
                            "release_native_event" to
                                bundle.deployments.getValue("release_native_event").copy(runAs = ModelRunAs.Caller)
                        ),
                )
            val source = load()
            val f =
                DurableRuntimeFixture(transformBundle = { selected(source) }, queryTable = { request ->
                    if ("approved writer fixture" in request.source) {
                        Table(
                            listOf(
                                ManifestColumn("order_number", "text"),
                                ManifestColumn("route", "text"),
                                ManifestColumn("verdict_reason", "text"),
                            ),
                            listOf(listOf("4711", "release", "approved writer fixture")),
                        )
                    } else {
                        Table(listOf(ManifestColumn("order_number", "text")), listOf(listOf("4711")))
                    }
                })
            val runtime = f.start()
            val request = f.request.copy(idempotencyKey = "generated-writer-once")
            try {
                val opened = runtime.sessions.open("release_order", request, f.caller)
                opened.httpStatus shouldBe 201
                opened.approvalsBypassed shouldBe false
                val id = UUID.fromString(opened.response.runId)
                runtime.sessions.evaluate(id, f.caller)
                val batch = runtime.status.batch(id, f.caller)
                val inserts = batch.rows.filter { it.entity == "er.entity.reorder_proposal" }
                inserts.size shouldBe 2
                val ids =
                    inserts.map {
                        it.key!!
                            .getValue("proposal_id")
                            .jsonPrimitive.long
                    }
                ids.distinct().size shouldBe 2
                ids.all { it < 0 } shouldBe true
                val actions = runtime.status.read(id, f.caller).actions
                actions.map {
                    it.arguments
                        .getValue("id")
                        .jsonPrimitive.long
                } shouldBe ids
                actions.map { it.batchRows } shouldBe inserts.map { listOf(it.seq) }
                for (row in inserts) {
                    row.row!!["proposal_id"] shouldBe row.key!!["proposal_id"]
                    row.row!!["run"] shouldBe JsonPrimitive(id.toString())
                    row.row!!["state"] shouldBe JsonPrimitive("proposed")
                    row.row!!["item"] shouldBe JsonPrimitive("4711")
                }
                actions.map { it.would } shouldBe ids.map { "would propose $it for 4711 as proposed" }
                runtime.sessions.evaluate(id, f.caller)
                runtime.sessions
                    .open("release_order", request, f.caller)
                    .response.runId shouldBe id.toString()
                f.reads.get() shouldBe 2
                transaction { Batches.selectAll().count() } shouldBe 1L
                runtime.closer.close(id, f.caller)
                val protocol = runtime.protocol.read(id, f.caller)
                runtime.shutdown()
                val restarted = f.start()
                try {
                    restarted.status.batch(id, f.caller) shouldBe batch
                    restarted.protocol.read(id, f.caller) shouldBe protocol
                    restarted.status.read(id, f.caller).actions shouldBe actions
                    restarted.sessions
                        .open("release_order", request, f.caller)
                        .response.runId shouldBe id.toString()
                    f.reads.get() shouldBe 2
                    transaction { Runs.selectAll().count() } shouldBe 1L
                    transaction { Batches.selectAll().count() } shouldBe 1L
                } finally {
                    restarted.shutdown()
                }
            } finally {
                runtime.shutdown()
                f.close()
            }
            val path = root.resolve("automation/automation.ttrm")
            path.writeText(path.readText().replace("generated: bigint_id_v1", "generated: bigint_id_v2"))
            val changed = load()
            changed.recipes
                .getValue("release_order")
                .coverage.refusal!!
                .contains("stale") shouldBe true
            val stale = DurableRuntimeFixture(transformBundle = { selected(changed) })
            val refused = stale.start()
            try {
                shouldThrow<AutomationException> {
                    refused.sessions.open("release_order", stale.request, stale.caller)
                }.code shouldBe ErrorCode.RECIPE_STALE
                transaction { Runs.selectAll().count() } shouldBe 1L
                transaction { Batches.selectAll().count() } shouldBe 1L
                stale.reads.get() shouldBe 0
            } finally {
                refused.shutdown()
                stale.close()
            }
        }

        "writer defaults persist across clock movement close and restart" {
            val loc = SourceLocation.UNKNOWN

            fun context(name: String) =
                AutoValue.Obj(
                    listOf(AutoEntry("context", AutoValue.Id(Reference(name), listOf(name), loc), loc)),
                    loc,
                )
            val f =
                DurableRuntimeFixture(development = true, transformBundle = { bundle ->
                    val er = bundle.model.schemas.getValue("er") as ErSchema
                    val order = er.entities.values.single { it.qname.name == "order" }
                    val key = order.attributes.single { it.isKey }
                    val target =
                        order.copy(
                            attributes =
                                listOf(
                                    key,
                                    key.copy(qname = key.qname.copy(name = "order.run"), isKey = false, type = "text"),
                                    key.copy(
                                        qname = key.qname.copy(name = "order.opened"),
                                        isKey = false,
                                        type = "datetime",
                                    ),
                                ),
                        )
                    val action =
                        bundle.actions.getValue("release").copy(
                            columns =
                                listOf(
                                    AutomationColumn("order", "order.order_number"),
                                    AutomationColumn("run", "order.run", optional = true),
                                    AutomationColumn("opened", "order.opened", optional = true),
                                ),
                            sinks = mapOf("native" to "writer"),
                            transition = null,
                            set = null,
                            properties =
                                listOf(
                                    "writer_defaults" to
                                        AutoValue.Obj(
                                            listOf(
                                                AutoEntry("run", context("run_id"), loc),
                                                AutoEntry("opened", context("started_at"), loc),
                                            ),
                                            loc,
                                        ),
                                ),
                        )
                    bundle
                        .copy(
                            model =
                                bundle.model.copy(
                                    schemas =
                                        bundle.model.schemas +
                                            ("er" to er.copy(entities = mapOf(target.qname to target))),
                                ),
                        ).withActions(bundle.actions + ("release" to action))
                })
            f.base.now = Instant.parse("2000-01-01T12:00:00.123456Z")
            val runtime = f.start()
            try {
                val opened = runtime.sessions.open("release_order", f.request, f.caller)
                val id = UUID.fromString(opened.response.runId)
                val startedAt = opened.context!!.startedAt
                f.base.now = f.base.now.plusSeconds(1)
                runtime.sessions.evaluate(id, f.caller)
                val batch = runtime.status.batch(id, f.caller)
                val row = batch.rows.single { it.entity == "er.entity.order" }.row!!
                row["run"] shouldBe JsonPrimitive(id.toString())
                OffsetDateTime.parse(row.getValue("opened").jsonPrimitive.content).toInstant() shouldBe startedAt
                val progress = runtime.status.read(id, f.caller)
                progress.actions.single().arguments["run"] shouldBe row["run"]
                progress.actions.single().arguments["opened"] shouldBe row["opened"]
                runtime.closer.close(id, f.caller)
                val protocol = runtime.protocol.read(id, f.caller)
                runtime.shutdown()
                val restarted = f.start()
                try {
                    restarted.status.batch(id, f.caller) shouldBe batch
                    restarted.protocol.read(id, f.caller) shouldBe protocol
                    f.reads.get() shouldBe 2
                    transaction { Batches.selectAll().count() } shouldBe 1L
                } finally {
                    restarted.shutdown()
                }
            } finally {
                runtime.shutdown()
                f.close()
            }
        }

        "runtime maintenance collects abandoned orphans and closed previews release only after grace" {
            val f = DurableRuntimeFixture()
            val abandoned = f.ledger.claimRun(RunOpen("orphan", callerSubject = f.caller.sub)).runId
            f.ledger.markStatus(abandoned, RunStatus.ABANDONED)
            val store = LocalStagingStore(f.stagingRoot)
            store.putIfAbsent("runs/$abandoned/orphan.arrow", byteArrayOf(1))
            val runtime = f.start()
            try {
                eventually { !Files.exists(f.stagingRoot.resolve("runs/$abandoned")) }
                val opened = runtime.sessions.open("release_order", f.request, f.caller)
                val id = UUID.fromString(opened.response.runId)
                runtime.sessions.evaluate(id, f.caller)
                runtime.closer.close(id, f.caller)
                val protocol = runtime.protocol.read(id, f.caller)
                runtime.sweeper.sweep()
                Files.exists(f.stagingRoot.resolve("runs/$id")) shouldBe true
                f.base.now = f.base.now.plusSeconds(3600)
                runtime.sweeper.sweep()
                Files.exists(f.stagingRoot.resolve("runs/$id")) shouldBe false
                transaction {
                    RunOutputs.selectAll().where { RunOutputs.run eq id }.all { it[RunOutputs.releasedAt] != null }
                } shouldBe true
                runtime.protocol.read(id, f.caller) shouldBe protocol
                runtime.status.read(id, f.caller).status shouldBe "closed"
                f.reads.get() shouldBe 2
            } finally {
                runtime.shutdown()
                f.close()
            }
        }

        "the assembled runtime uses the configured clock through durable dispatch, staging and decision commit" {
            val f = DurableRuntimeFixture()
            val runtime = f.start()
            try {
                val opened = runtime.sessions.open("release_order", f.request, f.caller)
                val id = UUID.fromString(opened.response.runId)
                opened.context!!.startedAt shouldBe f.clock.instant()
                runtime.sessions
                    .evaluate(id, f.caller)!!
                    .verdict.routes
                    .getValue("release")
                    .rowCount shouldBe 1L
                f.ledger.findRun(id)!!.status shouldBe RunStatus.EVALUATED
                f.ledger
                    .findRun(id)!!
                    .open.asOf shouldBe f.clock.instant()
                transaction { RunOutputs.selectAll().where { RunOutputs.run eq id }.count() } shouldBe 2L
                transaction {
                    RunProtocol.selectAll().where { RunProtocol.run eq id }.count()
                } shouldBe 6L // Opening + two outputs + decision + action preview + write preview.
                eventually {
                    transaction { RunEvaluations.selectAll().single()[RunEvaluations.archivedAt] != null }
                }
                transaction {
                    val ticket = RunEvaluations.selectAll().single()
                    ticket[RunEvaluations.owner] shouldBe runtime.owner
                    (ticket[RunEvaluations.claimedAt] != null) shouldBe true
                }
                f.reads.get() shouldBe 2
                runtime.sessions.open("release_order", f.request, f.caller).httpStatus shouldBe 202
                runtime.sessions
                    .evaluate(id, f.caller)!!
                    .verdict.none shouldBe 0L
                f.reads.get() shouldBe 2
                val document = runtime.protocol.read(id, f.caller)
                val progress = runtime.status.read(id, f.caller)
                val batch = runtime.status.batch(id, f.caller)
                batch.mode shouldBe "preview"
                batch.rows.map { it.op } shouldBe listOf("insert", "transition")
                progress.batch!!.rows shouldBe 2
                progress.actions.single().would shouldBe "would release order 4711"
                progress.status shouldBe "evaluated"
                progress.verdict!!
                    .routes
                    .getValue("release")
                    .rowCount shouldBe 1L
                progress.bundle shouldBe
                    f.ledger
                        .findRun(id)!!
                        .open.bundle
                document.sections.getValue("islands").size shouldBe 2
                document.sections.getValue("islands").forEach {
                    it.payload.jsonObject["engine"] shouldBe JsonPrimitive("query")
                    it.payload.jsonObject["rows"] shouldBe JsonPrimitive(1)
                    (
                        it.payload.jsonObject["bytes"]!!
                            .jsonPrimitive.long > 0
                    ) shouldBe true
                }
                runtime.shutdown()
                val restarted = f.start()
                try {
                    restarted.protocol.read(id, f.caller) shouldBe document
                    restarted.status.read(id, f.caller) shouldBe progress
                    restarted.status.batch(id, f.caller) shouldBe batch
                    f.reads.get() shouldBe 2
                } finally {
                    restarted.shutdown()
                }
            } finally {
                runtime.shutdown()
                f.close()
            }
        }

        "close releases the pinned runtime and retries after restart without any new Query work" {
            val f = DurableRuntimeFixture()
            val runtime = f.start()
            try {
                val id =
                    UUID.fromString(
                        runtime.sessions
                            .open("release_order", f.request, f.caller)
                            .response.runId,
                    )
                shouldThrow<AutomationException> { runtime.closer.close(id, f.caller) }.code shouldBe
                    ErrorCode.CLOSE_NOT_READY
                runtime.sessions.evaluate(id, f.caller)
                val batch = runtime.status.batch(id, f.caller)
                val closed = runtime.closer.close(id, f.caller)
                closed.batch.state shouldBe "preview"
                runtime.status.batch(id, f.caller) shouldBe batch
                runtime.status.read(id, f.caller).status shouldBe "closed"
                shouldThrow<AutomationException> { runtime.sessions.evaluate(id, f.caller) }.code shouldBe
                    ErrorCode.RUN_CLOSED
                shouldThrow<AutomationException> {
                    runtime.sessions.read(
                        id,
                        f.caller,
                        { Unit },
                    ) { _, _ -> Unit }
                }.code shouldBe
                    ErrorCode.RUN_CLOSED
                runtime.sessions.open("release_order", f.request, f.caller).httpStatus shouldBe 200
                val protocol = runtime.protocol.read(id, f.caller)
                runtime.shutdown()
                val restarted = f.start()
                try {
                    restarted.closer.close(id, f.caller) shouldBe closed
                    restarted.protocol.read(id, f.caller) shouldBe protocol
                    restarted.status.batch(id, f.caller) shouldBe batch
                    shouldThrow<AutomationException> { restarted.sessions.evaluate(id, f.caller) }.code shouldBe
                        ErrorCode.RUN_CLOSED
                    f.reads.get() shouldBe 2
                } finally {
                    restarted.shutdown()
                }
            } finally {
                runtime.shutdown()
                f.close()
            }
        }

        "the assembled deadline cancels actual queued execution and persists the typed public refusal" {
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val f =
                DurableRuntimeFixture(sync = "20ms", beforeRead = {
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled.complete(Unit)
                    }
                })
            val runtime = f.start()
            try {
                val id =
                    UUID.fromString(
                        runtime.sessions
                            .open("release_order", f.request, f.caller)
                            .response.runId,
                    )
                runtime.sessions.evaluate(id, f.caller) shouldBe null
                withTimeout(5000) { entered.await() }
                f.base.now = f.base.now.plusSeconds(10)
                withTimeout(5000) { cancelled.await() }
                eventually { f.ledger.findRun(id)!!.status == RunStatus.FAILED }
                runtime.status.read(id, f.caller).status shouldBe "failed"
                runtime.status.read(id, f.caller).error shouldBe buildJsonObject { put("code", "budget") }
                shouldThrow<AutomationException> { runtime.sessions.progress(id, f.caller) }.code shouldBe
                    ErrorCode.BUDGET
                transaction {
                    Runs.selectAll().single()[Runs.error] shouldBe buildJsonObject { put("code", "budget") }
                    RunOutputs.selectAll().count() shouldBe 0L
                    RunProtocol.selectAll().count() shouldBe 1L
                }
                f.reads.get() shouldBe 1
            } finally {
                runtime.shutdown()
                f.close()
            }
        }

        "startup maintenance abandons and archives a lost owner's trigger without executing its context" {
            val f = DurableRuntimeFixture()
            val lost = UUID.randomUUID()
            val id =
                f.ledger
                    .claimRun(
                        RunOpen("lost", budgetBatch = f.config.budgets.batchDefault, callerSubject = f.caller.sub),
                    ).runId
            RunQueue(clock = f.clock, defaultBudget = f.config.budgets.batchDefault).enqueue(id, lost)
            f.base.now = f.base.now.plusSeconds(131) // Strict budget + two minute recovery grace.
            val runtime = f.start()
            try {
                eventually { f.ledger.findRun(id)!!.status == RunStatus.ABANDONED }
                eventually {
                    transaction { RunEvaluations.selectAll().single()[RunEvaluations.archivedAt] != null }
                }
                transaction {
                    val ticket = RunEvaluations.selectAll().single()
                    ticket[RunEvaluations.owner] shouldBe lost
                    ticket[RunEvaluations.claimedAt] shouldBe null
                }
                shouldThrow<AutomationException> { runtime.sessions.evaluate(id, f.caller) }.code shouldBe
                    ErrorCode.RUN_ABANDONED
                f.reads.get() shouldBe 0
            } finally {
                runtime.shutdown()
                f.close()
            }
        }
    })

private suspend fun eventually(predicate: () -> Boolean) {
    withTimeout(5000) { while (!withContext(Dispatchers.IO) { predicate() }) delay(10) }
}

private class RuntimeClock(
    @Volatile var now: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this

    override fun instant(): Instant = now
}

private class DurableRuntimeFixture(
    sync: String = "60s",
    private val beforeRead: suspend () -> Unit = {},
    development: Boolean = false,
    transformBundle: (Bundle) -> Bundle = { it },
    private val queryTable: ((RunRequest) -> Table)? = null,
    private val lookupReads: List<String> = listOf("er.entity.obligation"),
    private val queryWarnings: (RunRequest) -> Set<String> = { emptySet() },
) {
    // Far from the system clock: a queue/session accidentally using wall time would immediately refuse.
    val base = RuntimeClock(Instant.parse("2000-01-01T12:00:00Z"))
    val config =
        AutomationConfig.load(
            ConfigFactory
                .parseString(
                    """
                    automation.db.host = "unused"
                    automation.door.queryUrl = "unused"
                    automation.door.metadataUrl = "unused"
                    automation.door.routes = "demo/erp_db=us-erp,demo/automation_db=us-automation"
                    automation.auth.jwksUrl = "http://unused/certs"
                    automation.auth.issuer = "unused"
                    automation.clock.skew = 90s
                    automation.budgets.syncDefault = $sync
                    automation.budgets.batchDefault = 10s
                    automation.runner.workers = 1
                    automation.development = $development
                    automation.approvals = ${if (development) "off" else "required"}
                    """.trimIndent(),
                ).withFallback(ConfigFactory.parseResources("application.conf"))
                .resolve(),
            emptyMap(),
        )
    val clock = Clock.offset(base, config.clockSkew)
    val ledger = Ledger(config.budgets.batchDefault, clock)
    val stagingRoot = Files.createTempDirectory("au-runtime-pg")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val caller = Caller("sub-maya", "maya", setOf("sales_manager"), TokenKind.PERSON, "not-stored")
    val request = OpenRequest("release_native_event", buildJsonObject { put("key", "4711") })
    val reads = AtomicInteger()
    private val columns = listOf(ManifestColumn("order_number", "text"), ManifestColumn("verdict_reason", "text"))
    private val source =
        BundleLoader(AuthorMap(emptyMap()), clock).load(
            Checkout(
                Path.of("src/test/resources/bundles/hartland"),
                "master",
                "0123456789abcdef0123456789abcdef01234567",
                "po",
                "po@example.org",
            ),
            BundleKind.SERVED,
        )
    private val recipe =
        source.recipes.getValue("release_order").let { r ->
            val metadata =
                r.recipe.copy(
                    parts = emptyList(),
                    wiring = listOf(ManifestWiring(listOf("way.release"), "release", mapOf("order" to "order_number"))),
                    roles =
                        ManifestRoles(
                            "placed",
                            emptyList(),
                            "way",
                            routes = listOf(ManifestRoute("release")),
                        ),
                )
            r.copy(
                recipe = metadata,
                manifest =
                    r.manifest.copy(
                        recipe = metadata,
                        waves = listOf(listOf("placed"), listOf("way")),
                        islands =
                            listOf(
                                ManifestIsland(
                                    "placed",
                                    outputs =
                                        listOf(
                                            ManifestOutput(
                                                "orders",
                                                "c.sql",
                                                columns = columns,
                                                keyed = true,
                                                storage = "erp_db",
                                            ),
                                        ),
                                ),
                                ManifestIsland(
                                    "way",
                                    outputs =
                                        listOf(
                                            ManifestOutput(
                                                "release",
                                                "r.sql",
                                                columns = columns,
                                                keyed = true,
                                                storage = "erp_db",
                                            ),
                                        ),
                                ),
                            ),
                    ),
                statements = mapOf("c.sql" to "candidates", "r.sql" to "route"),
            )
        }
    private val bundle =
        transformBundle(
            source.copy(
                recipes = source.recipes + ("release_order" to recipe),
                deployments =
                    source.deployments +
                        (
                            "release_native_event" to
                                source.deployments.getValue("release_native_event").copy(runAs = ModelRunAs.Caller)
                        ),
            ),
        ).let { it.withActions(it.actions) }
    private val query =
        object : QueryRun {
            override suspend fun compile(request: RunRequest) =
                CompileResponse
                    .newBuilder()
                    .setPlan(PlanNode.newBuilder().setValues(ValuesNode.getDefaultInstance()))
                    .setErReads(
                        ErReadScope.newBuilder().addAllObjects(
                            if ("_au_obligation_entity_" in
                                request.source ||
                                "FROM er.entity.date_dim cal" in request.source
                            ) {
                                lookupReads
                            } else {
                                emptyList()
                            },
                        ),
                    ).build()

            override fun run(request: RunRequest) =
                flow {
                    request.world shouldBe
                        if ("_au_obligation_entity_" in request.source ||
                            "FROM \"obligation\"" in request.source
                        ) {
                            "us-automation"
                        } else {
                            "us-erp"
                        }
                    reads.incrementAndGet()
                    beforeRead()
                    val table = queryTable?.invoke(request) ?: Table(columns, listOf(listOf("4711", "SQL reason")))
                    emit(
                        ResultBatch
                            .newBuilder()
                            .setContext(
                                request.context.toBuilder().addAllWarnings(
                                    queryWarnings(request).map {
                                        org.tatrman.plan.v1.Warning
                                            .newBuilder()
                                            .setCode(it)
                                            .build()
                                    },
                                ),
                            ).setBatchRowCount(
                                table.rows.size.toLong(),
                            ).setArrowIpc(ByteString.copyFrom(ArrowTables.encode(table)))
                            .build(),
                    )
                }
        }

    fun start() =
        RunRuntime(
            config,
            { bundle },
            ApprovalReader {
                _,
                _,
                _,
                ->
                true
            },
            ServiceTokens(emptyMap(), { error("unused") }),
            ledger,
            scope,
            clock,
            query,
            LocalStagingStore(stagingRoot),
        )

    fun close() {
        scope.cancel()
    }
}
