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
import org.tatrman.ttr.parser.model.PropertyValue
import java.sql.DriverManager
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

        "dry H-1 journals exactly plan() in one preview batch stable across repeated close" {
            val root = ApprovalFixture.copyTo(tempdir().toPath().resolve("ddl-catalog"))
            val loaded =
                BundleLoader(AuthorMap(emptyMap())).load(
                    Checkout(root, "master", "0123456789abcdef", "po", "po@example.org"),
                    BundleKind.SERVED,
                )
            org.tatrman.automation.writer
                .Ddl(LedgerTestDb.db.getDataSource(), { listOf("dry_fixture") })
                .refresh(loaded.copy(recipes = loaded.recipes.filterKeys { it == "release_order" }))
            val f = DurableRuntimeFixture()
            val runtime = f.start()
            try {
                val opened = runtime.sessions.open("release_order", f.request, f.caller)
                val id = UUID.fromString(opened.response.runId)
                runtime.sessions.evaluate(id, f.caller)
                val planner =
                    org.tatrman.automation.apply
                        .ApplyPlanner(f.clock)
                val expected =
                    runtime.sessions.read(id, f.caller, { Unit }) { runner, _ ->
                        planner.plan(planner.preview(runner))
                    }
                val batch = runtime.status.batch(id, f.caller)
                batch.rows shouldBe expected.rows
                batch.mode shouldBe "preview"
                transaction { Batches.selectAll().where { Batches.run eq id }.count() } shouldBe 1L
                runtime.closer.close(id, f.caller)
                runtime.closer.close(id, f.caller)
                runtime.status.batch(id, f.caller) shouldBe batch
                LedgerTestDb.db.getDataSource().connection.use { connection ->
                    connection.createStatement().use { sql ->
                        for (table in listOf(
                            "release_order_verdict",
                            "release_order_verdict_detail",
                            "release_order_verdict_current",
                            "obligation",
                            "state_history",
                        )) {
                            sql.executeQuery("SELECT count(*) FROM dry_fixture.$table").use { rows ->
                                rows.next() shouldBe true
                                rows.getLong(1) shouldBe 0L
                            }
                        }
                    }
                }
            } finally {
                runtime.shutdown()
                f.close()
                LedgerTestDb.db.getDataSource().connection.use { connection ->
                    connection.autoCommit = true
                    connection.createStatement().use { it.execute("DROP SCHEMA IF EXISTS dry_fixture CASCADE") }
                }
            }
        }

        "an evaluated candidate with no selected route journals only its current delete" {
            val f =
                DurableRuntimeFixture(queryTable = { request ->
                    Table(
                        listOf(ManifestColumn("order_number", "text"), ManifestColumn("verdict_reason", "text")),
                        if ("candidates" in request.source) listOf(listOf("4711", "candidate")) else emptyList(),
                    )
                })
            val runtime = f.start()
            try {
                val id =
                    UUID.fromString(
                        runtime.sessions
                            .open("release_order", f.request, f.caller)
                            .response.runId,
                    )
                requireNotNull(runtime.sessions.evaluate(id, f.caller)).verdict.none shouldBe 1L
                val batch = runtime.status.batch(id, f.caller)
                batch.rows.size shouldBe 1
                batch.rows.single().entity shouldBe "er.entity.release_order_verdict_current"
                batch.rows.single().op shouldBe "delete"
                batch.rows.single().key shouldBe buildJsonObject { put("order_number", "4711") }
            } finally {
                runtime.shutdown()
                f.close()
            }
        }

        fun calendarFixture(
            difference: Boolean = false,
            warnings: Set<String> = emptySet(),
            readsScope: List<String> = listOf("er.entity.date_dim"),
            transform: (Bundle) -> Bundle = { it },
        ): DurableRuntimeFixture {
            val root =
                CalendarFixture.copyTo(
                    tempdir(prefix = "calendar-runtime-").toPath().resolve("arbitrary-branch"),
                    difference = difference,
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
                                        java.time.LocalDate.of(2040, 10, day),
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
                                    listOf("4711", java.time.LocalDate.of(2040, 10, 23), "release", "calendar fixture"),
                                ),
                            )
                        else ->
                            Table(
                                listOf(ManifestColumn("order_number", "text"), ManifestColumn("placed", "date")),
                                listOf(listOf("4711", java.time.LocalDate.of(2040, 10, 23))),
                            )
                    }
                },
            )
        }

        for (difference in listOf(false, true)) {
            val label = if (difference) "bound-date-difference" else "calendar"
            "published $label preview [AU-D3-1; S3.1 engine row]".config(enabled = false) {
                val f = calendarFixture(difference = difference)
                val runtime = f.start()
                try {
                    val opened =
                        runtime.sessions.open(
                            "release_order",
                            f.request.copy(asOf = "2040-10-28T12:00:00Z"),
                            f.caller,
                        )
                    opened.httpStatus shouldBe 201
                    val id = UUID.fromString(opened.response.runId)
                    val result = runtime.sessions.evaluate(id, f.caller)
                    val actions = runtime.status.read(id, f.caller).actions
                    actions.single().arguments["template"] shouldBe JsonPrimitive(if (difference) "5" else "2")
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

        "calendar read refusals with engine row [AU-D3-1; S3.1]".config(enabled = false) {
            for ((warning, scope, expected) in listOf(
                Triple(setOf("security_predicate_applied"), listOf("er.entity.date_dim"), ErrorCode.RESTRICTED_VIEW),
                Triple(setOf("top_n_applied"), listOf("er.entity.date_dim"), ErrorCode.CANDIDATES_TRUNCATED),
                Triple(emptySet<String>(), listOf("er.entity.customer"), ErrorCode.OUT_OF_SCOPE_READ),
            )) {
                val f =
                    calendarFixture(warnings = warning, readsScope = scope, transform = { bundle ->
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
                                    bundle.deployments
                                        .getValue(
                                            "release_native_event",
                                        ).copy(runAs = ModelRunAs.Caller)
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
            missing: Boolean = false,
            oldApproval: Boolean = false,
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
            val approvals =
                Json
                    .parseToJsonElement(
                        Path.of("src/test/resources/approval-evidence/fulfilment/approvals.json").readText(),
                    ).jsonObject
            val approvedText = approvals.getValue(if (oldApproval) "supersededText" else "text").jsonPrimitive.content
            return DurableRuntimeFixture(
                lookupReads = lookupReads,
                approvalReader =
                    ApprovalReader { kind, obj, fp ->
                        when (kind) {
                            "recipe" -> obj == "release_order" && fp == approvedText
                            "values" ->
                                obj == "release_order" &&
                                    fp == approvals.getValue("values").jsonPrimitive.content
                            "program" -> fp == source.programFps[obj]
                            "deployment" -> fp == source.deploymentFps[obj]
                            else -> false
                        }
                    },
                queryWarnings = { request ->
                    if ("_au_obligation_entity_" in request.source) lookupWarnings else emptySet()
                },
                transformBundle = {
                    source.copy(
                        deployments =
                            source.deployments +
                                (
                                    "release_native_event" to
                                        source.deployments
                                            .getValue(
                                                "release_native_event",
                                            ).copy(runAs = ModelRunAs.Caller)
                                ),
                    )
                },
                queryTable = { request ->
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
                                (
                                    if (missing) {
                                        emptyList()
                                    } else if (ambiguous) {
                                        listOf("ob-1", "ob-2")
                                    } else {
                                        listOf("ob-1")
                                    }
                                ).map { id ->
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
                        else ->
                            DriverManager.getConnection("jdbc:duckdb:").use { db ->
                                db.createStatement().use {
                                    it.execute("CREATE TABLE \"order\" (order_number VARCHAR)")
                                    // A second order makes the keyed Open exercise the actual candidate predicate.
                                    it.execute("INSERT INTO \"order\" VALUES ('4711'), ('9999')")
                                }
                                request.source.contains("obligation", ignoreCase = true) shouldBe false
                                val bindings =
                                    request.context.parametersList.associate {
                                        it.name to BoundValue("text", it.value.stringValue)
                                    }
                                // Query's wire placeholders are {name}; the fixture JDBC adapter uses :name.
                                val sql =
                                    bindings.keys.fold(request.source) { text, name ->
                                        text.replace("{$name}", ":$name")
                                    }
                                JdbcTables.query(db, sql, bindings).also { result ->
                                    // Only candidates are wrapped at the door; other keyed outputs are
                                    // filtered by PartRunner before staging (the batch asserts this below).
                                    result.rows.map { it.first() }.toSet() shouldBe
                                        if ("_au_candidates" in
                                            request.source
                                        ) {
                                            setOf("4711")
                                        } else {
                                            setOf("4711", "9999")
                                        }
                                }
                            }
                    }
                },
            )
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

        "superseded fulfilment approval refuses before claim or ERP work" {
            val f = fulfilmentFixture(oldApproval = true)
            val runtime = f.start()
            try {
                shouldThrow<AutomationException> {
                    runtime.sessions.open("release_order", f.request, f.caller)
                }.code shouldBe ErrorCode.RECIPE_STALE
                transaction { Runs.selectAll().count() } shouldBe 0L
                f.reads.get() shouldBe 0
            } finally {
                runtime.shutdown()
                f.close()
            }
        }

        "join-free candidates still reach the scoped lookup when no obligation exists" {
            val f = fulfilmentFixture(missing = true)
            val runtime = f.start()
            try {
                val opened = runtime.sessions.open("release_order", f.request, f.caller)
                val id = UUID.fromString(opened.response.runId)
                // The ERP key remains selected; missing fulfilment targets still refuse as before.
                shouldThrow<AutomationException> {
                    runtime.sessions.evaluate(id, f.caller)
                }.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
                f.reads.get() shouldBe 3
                transaction { RunOutputs.selectAll().count() } shouldBe 3L
                transaction { Batches.selectAll().count() } shouldBe 0L
                runtime.status.read(id, f.caller).actions shouldBe emptyList()
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
                val id = UUID.fromString(opened.response.runId)
                val result = runtime.sessions.evaluate(id, f.caller)
                val batch = runtime.status.batch(id, f.caller)
                batch.rows
                    .filter { it.entity == "er.entity.order" }
                    .map { it.key }
                    .toSet() shouldBe
                    setOf(buildJsonObject { put("order_number", "4711") })
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
                bundle.withWriterStorage("reorder_proposal").copy(
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
                DurableRuntimeFixture(transformBundle = { bundle ->
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
                        ).withWriterStorage("order")
                        .withActions(bundle.actions + ("release" to action))
                }, afterPrepare = { b ->
                    // S2.3 removed the development bypass: stand in for evidence that covers these defaults.
                    b.copy(
                        recipes =
                            b.recipes.mapValues { (_, r) ->
                                r.copy(
                                    coverage =
                                        r.coverage.copy(
                                            refusal = null,
                                            coverage = null,
                                            defaultsWithoutEvidence = false,
                                        ),
                                )
                            },
                    )
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

        // ---- S2.2: the apply through the door, end to end (open → evaluate → close) ----

        fun sqlAuto(text: String) =
            LedgerTestDb.db.getDataSource().connection.use { c ->
                c.autoCommit = true
                c.createStatement().use { it.execute(text) }
            }

        fun query(text: String): List<List<String?>> =
            LedgerTestDb.db.getDataSource().connection.use { c ->
                c.createStatement().use { s ->
                    s.executeQuery(text).use { rs ->
                        buildList { while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getString(it) }) }
                    }
                }
            }

        /**
         * The default H-1 fixture (route `release` → `release`, a transition placed/held → released, with history) plus
         * the generated verdict catalog entities of the approval fixture's `release_order`, which the writer lowers
         * against. ERP = erp_runtime (sales_order), automation_pg = wet_runtime.
         */
        fun wetFixture(
            protocol: String? = null,
            routed: Boolean = true,
            initial: String = "held",
            keys: List<String> = listOf("4711"),
            wire: String = "release",
        ): Triple<DurableRuntimeFixture, org.tatrman.automation.writer.RowWritesQueryFake, Unit> {
            val root = ApprovalFixture.copyTo(tempdir(prefix = "automation-bundles-").toPath().resolve("branch"))
            val catalog =
                BundleLoader(AuthorMap(emptyMap())).load(
                    Checkout(root, "master", "0123456789abcdef", "po", "po@example.org"),
                    BundleKind.SERVED,
                )
            org.tatrman.automation.writer
                .Ddl(LedgerTestDb.db.getDataSource(), { listOf("wet_runtime") })
                .refresh(catalog.copy(recipes = catalog.recipes.filterKeys { it == "release_order" }))
            val verdicts =
                (catalog.model.schemas.getValue("er") as ErSchema).entities.filterValues {
                    it.qname.name.startsWith("release_order_verdict")
                }
            sqlAuto(
                "CREATE SCHEMA IF NOT EXISTS erp_runtime; DROP TABLE IF EXISTS erp_runtime.sales_order; " +
                    "CREATE TABLE erp_runtime.sales_order " +
                    "(order_number text PRIMARY KEY, state text NOT NULL, held_for text); " +
                    "INSERT INTO erp_runtime.sales_order VALUES ('4711', '$initial', 'AR')",
            )
            val fake =
                org.tatrman.automation.writer.RowWritesQueryFake(
                    LedgerTestDb.db.getDataSource(),
                    mapOf("w-erp" to "erp_runtime", "w-auto" to "wet_runtime"),
                    tables = mapOf("order" to "sales_order"),
                )
            val program = "release_on_placement"
            val f =
                DurableRuntimeFixture(
                    transformBundle = { b ->
                        val er = b.model.schemas.getValue("er") as ErSchema
                        val r = b.recipes.getValue("release_order")
                        val wired =
                            r.recipe.copy(
                                wiring =
                                    r.recipe.wiring.map {
                                        it.copy(
                                            action = wire,
                                            columns =
                                                mapOf("order" to "order_number") +
                                                    // release_confirmed also takes the order's value (a literal here).
                                                    if (wire ==
                                                        "release_confirmed"
                                                    ) {
                                                        mapOf("value" to "100000")
                                                    } else {
                                                        emptyMap()
                                                    },
                                        )
                                    },
                            )
                        // The Core 0.22.3 fixture's release_confirmed `dry_run` names `{by}`, not a column, which the
                        // preview refuses (fixed in hartland content by ttr-demo#171); the spec uses a column-only text.
                        val actions =
                            b.actions.mapValues { (name, a) ->
                                if (name == "release_confirmed") {
                                    a.copy(
                                        dryRun =
                                            org.tatrman.ttr.metadata.model.LocalizedText(
                                                mapOf("en" to "would confirm the release of {order} ({value})"),
                                            ),
                                    )
                                } else {
                                    a
                                }
                            }
                        val base = b.withActions(actions)
                        base.copy(
                            recipes =
                                b.recipes +
                                    (
                                        "release_order" to
                                            r.copy(recipe = wired, manifest = r.manifest.copy(recipe = wired))
                                    ),
                            model =
                                base.model.copy(
                                    schemas =
                                        base.model.schemas + ("er" to er.copy(entities = er.entities + verdicts)),
                                ),
                            programs =
                                b.programs + (
                                    program to
                                        b.programs
                                            .getValue(
                                                program,
                                            ).let { p ->
                                                p.copy(
                                                    protocol = protocol,
                                                    port =
                                                        if (keys.size >
                                                            1
                                                        ) {
                                                            "keys"
                                                        } else {
                                                            p.port
                                                        },
                                                )
                                            }
                                ),
                        )
                    },
                    queryTable = { request ->
                        Table(
                            listOf(ManifestColumn("order_number", "text"), ManifestColumn("verdict_reason", "text")),
                            if (routed ||
                                "candidates" in request.source
                            ) {
                                keys.map { listOf(it, "SQL reason") }
                            } else {
                                emptyList()
                            },
                        )
                    },
                    writeRoutes = "demo/erp_db=w-erp,demo/automation_db=w-auto",
                    writes = fake,
                )
            return Triple(f, fake, Unit)
        }

        fun dropWet() = sqlAuto("DROP SCHEMA IF EXISTS wet_runtime CASCADE; DROP SCHEMA IF EXISTS erp_runtime CASCADE")

        "a committed H-1 run applies through the door; a second run is idempotent; a dry run sends no row_writes" {
            val (f, fake, _) = wetFixture()
            val runtime = f.start()
            try {
                val wet = f.request.copy(dryRun = null)

                suspend fun run(request: OpenRequest): UUID {
                    val id =
                        UUID.fromString(
                            runtime.sessions
                                .open("release_order", request, f.caller)
                                .response.runId,
                        )
                    runtime.sessions.evaluate(id, f.caller)
                    runtime.closer.close(id, f.caller).status shouldBe "closed"
                    return id
                }
                val first = run(wet)
                val batch = runtime.status.batch(first, f.caller)
                batch.mode shouldBe "auto"
                batch.statuses
                    .filter {
                        it.status != "applied"
                    }.map { "${batch.rows[it.seq - 1]} ${it.error}" } shouldBe
                    emptyList()
                transaction { Batches.selectAll().where { Batches.run eq first }.single()[Batches.state] } shouldBe
                    "committed"
                query("SELECT run, order_number, route FROM wet_runtime.release_order_verdict") shouldBe
                    listOf(listOf("$first", "4711", "release"))
                query("SELECT run FROM wet_runtime.release_order_verdict_current") shouldBe listOf(listOf("$first"))
                // The held order is transitioned, with its history row.
                query("SELECT state FROM erp_runtime.sales_order") shouldBe listOf(listOf("released"))
                query("SELECT from_state, to_state, run, by FROM wet_runtime.state_history") shouldBe
                    listOf(listOf("held", "released", "$first", "maya"))
                val transition = batch.rows.single { it.op == "transition" }
                batch.statuses.single { it.seq == transition.seq }.prior shouldBe
                    buildJsonObject { put("state", "held") }
                val writes =
                    runtime.protocol
                        .read(
                            first,
                            f.caller,
                        ).sections
                        .getValue("writes")
                        .last()
                        .payload.jsonObject
                writes["state"] shouldBe JsonPrimitive("committed")
                writes["applied"] shouldBe JsonPrimitive(batch.rows.size)
                fake.writes.map { it.world } shouldBe listOf("w-auto", "w-erp", "w-auto")
                fake.writes.forEach { it.context.userId shouldBe "maya" }
                // A second run: released → released is silent (no history), verdicts accumulate, current moves.
                f.base.now = f.base.now.plusSeconds(60)
                val second = run(wet)
                runtime.status
                    .batch(second, f.caller)
                    .statuses
                    .map { it.status }
                    .toSet() shouldBe setOf("applied")
                query("SELECT count(*) FROM wet_runtime.state_history") shouldBe listOf(listOf("1"))
                query("SELECT count(*) FROM wet_runtime.release_order_verdict") shouldBe listOf(listOf("2"))
                query("SELECT run FROM wet_runtime.release_order_verdict_current") shouldBe listOf(listOf("$second"))
                // A dry run plans the same rows and sends zero row_writes requests (R217-L5).
                val sent = f.rowWrites.get()
                f.base.now = f.base.now.plusSeconds(60)
                val dry = run(f.request)
                runtime.status.batch(dry, f.caller).mode shouldBe "preview"
                f.rowWrites.get() shouldBe sent
                query("SELECT count(*) FROM wet_runtime.release_order_verdict") shouldBe listOf(listOf("2"))
            } finally {
                runtime.shutdown()
                f.close()
                dropWet()
            }
        }

        "S2.3 T2: approval-required rows are held per key; a decision applies its group; the last one closes" {
            val (f, fake, _) = wetFixture(keys = listOf("4711", "4712"), wire = "release_confirmed", initial = "placed")
            sqlAuto("INSERT INTO erp_runtime.sales_order VALUES ('4712', 'placed', NULL)")
            val runtime = f.start()
            try {
                val id =
                    UUID.fromString(
                        runtime.sessions
                            .open(
                                "release_order",
                                f.request.copy(
                                    dryRun = null,
                                    port =
                                        buildJsonObject {
                                            put("keys", JsonArray(listOf(JsonPrimitive("4711"), JsonPrimitive("4712"))))
                                        },
                                ),
                                f.caller,
                            ).response.runId,
                    )
                runtime.sessions.evaluate(id, f.caller)
                val pinned = requireNotNull(runtime.sessions.pinned(id))
                val close = runtime.closer.close(id, f.caller)
                close.status shouldBe "waiting"
                close.batch.state shouldBe "held"
                // The verdict rows committed `auto`; the ERP transitions wait.
                query("SELECT order_number FROM wet_runtime.release_order_verdict ORDER BY 1") shouldBe
                    listOf(listOf("4711"), listOf("4712"))
                query("SELECT order_number, state FROM erp_runtime.sales_order ORDER BY 1") shouldBe
                    listOf(listOf("4711", "placed"), listOf("4712", "placed"))
                val batch = runtime.status.batch(id, f.caller)
                batch.mode shouldBe "hold"
                val held = batch.rows.filter { it.group != null }
                held.map { it.op }.toSet() shouldBe setOf("transition")
                held.map { it.group } shouldBe
                    listOf("4711", "4712").map { buildJsonObject { put("order_number", it) } }
                held.forEach { r -> r.group shouldBe r.key }
                batch.statuses
                    .filter { s -> s.seq in held.map { it.seq } }
                    .map { it.status } shouldBe listOf("staged", "staged")
                batch.statuses
                    .filter { s -> s.seq !in held.map { it.seq } }
                    .map { it.status }
                    .toSet() shouldBe setOf("applied")
                val sealed = runtime.protocol.read(id, f.caller).sections
                sealed.getValue("automation").last().payload shouldBe buildJsonObject { put("sealed", true) }
                sealed.getValue("writes").last().payload.jsonObject.let {
                    it["state"] shouldBe JsonPrimitive("held")
                    it["held"] shouldBe JsonPrimitive(2)
                    it["groups"] shouldBe JsonPrimitive(2)
                }
                // Waiting is never abandoned (D-97), however late. Three days on, the run's own deadline (open + its
                // batch budget) has long passed; every decision below is bounded by its own budget instead (D-186 (b)).
                f.base.now = f.base.now.plusSeconds(3 * 86_400)
                f.ledger.abandonExpiredRuns().contains(id) shouldBe false
                f.ledger.findRun(id)!!.status shouldBe RunStatus.WAITING
                // A retried close answers the waiting state again.
                runtime.closer.close(id, f.caller).status shouldBe "waiting"

                val door =
                    QueryDoor(
                        fake,
                        emptyMap(),
                        ServiceTokens(emptyMap(), { error("unused") }),
                        mapOf("demo/erp_db" to "w-erp", "demo/automation_db" to "w-auto"),
                    )

                fun decider(served: Bundle = f.served) =
                    org.tatrman.automation.writer.Held(
                        f.ledger,
                        org.tatrman.automation.writer
                            .Apply(door, f.clock),
                        org.tatrman.automation.writer
                            .BundleDeferredContexts(
                                { null },
                                ServiceTokens(emptyMap(), { error("unused") }),
                            ) { pinned },
                        { served },
                        f.clock,
                    )
                val ops = Caller("sub-olga", "olga", emptySet(), TokenKind.PERSON, "t", groups = setOf("hartland-ops"))
                val k4711 = buildJsonObject { put("order_number", "4711") }
                val k4712 = buildJsonObject { put("order_number", "4712") }
                // role.ops through the world's `def role ops` (hartland-ops); Maya is not in it.
                shouldThrow<AutomationException> { decider().commitHeld(id, k4711, f.caller) }.code shouldBe
                    ErrorCode.IDENTITY
                // D-99: a refresh that moved the recipe's text fp supersedes the held group; nothing is applied.
                val r = f.served.recipes.getValue("release_order")
                val moved = r.recipe.copy(fingerprints = r.recipe.fingerprints.copy(text = "sha256:moved"))
                val refreshed = f.served.copy(recipes = f.served.recipes + ("release_order" to r.copy(recipe = moved)))
                val writesBefore = fake.writes.size
                val superseded = shouldThrow<AutomationException> { decider(refreshed).commitHeld(id, k4711, ops) }
                superseded.code shouldBe ErrorCode.SUPERSEDED
                superseded.detail!!.jsonObject["differs"] shouldBe JsonArray(listOf(JsonPrimitive("text")))
                fake.writes.size shouldBe writesBefore
                query("SELECT state FROM erp_runtime.sales_order WHERE order_number = '4711'") shouldBe
                    listOf(listOf("placed"))
                // commitHeld applies that group only.
                val committed = decider().commitHeld(id, k4711, ops)
                committed.applied shouldBe 1
                committed.runStatus shouldBe "waiting"
                query("SELECT order_number, state FROM erp_runtime.sales_order ORDER BY 1") shouldBe
                    listOf(listOf("4711", "released"), listOf("4712", "placed"))
                query("SELECT from_state, to_state, run FROM wet_runtime.state_history") shouldBe
                    listOf(listOf("placed", "released", "$id"))
                // A second identical commit answers the first outcome and sends nothing.
                val sent = fake.writes.size
                decider().commitHeld(id, k4711, ops).applied shouldBe 1
                fake.writes.size shouldBe sent
                // rejectHeld marks the group's rows skipped and records who; the last decision closes the run.
                val rejected = decider().rejectHeld(id, k4712, ops)
                rejected.discarded shouldBe 1
                rejected.runStatus shouldBe "closed"
                query("SELECT state FROM erp_runtime.sales_order WHERE order_number = '4712'") shouldBe
                    listOf(listOf("placed"))
                val after = f.ledger.readRunBatch(id, f.caller.sub)
                after.mode shouldBe "auto"
                val row4712 = held.single { it.group == k4712 }.seq
                after.statuses.single { it.seq == row4712 }.let {
                    it.status shouldBe "skipped"
                    it.error shouldBe "rejected"
                    it.decided!!["by"] shouldBe JsonPrimitive("olga")
                    it.decided!!["decision"] shouldBe JsonPrimitive("reject")
                }
                f.ledger.findRun(id)!!.status shouldBe RunStatus.CLOSED
                transaction {
                    Batches.selectAll().where { Batches.run eq id }.single().let {
                        it[Batches.state] shouldBe "committed"
                        it[Batches.committedBy] shouldBe "olga"
                    }
                }
                val entries =
                    runtime.protocol
                        .read(id, f.caller)
                        .sections
                        .getValue("writes")
                        .map { it.payload.jsonObject }
                        .filter { it["after_seal"] == JsonPrimitive(true) }
                entries.map { it["event"] } shouldBe listOf(JsonPrimitive("held-commit"), JsonPrimitive("held-reject"))
                entries.map { it["by"] } shouldBe listOf(JsonPrimitive("olga"), JsonPrimitive("olga"))
                entries.last()["last"] shouldBe JsonPrimitive(true)
            } finally {
                runtime.shutdown()
                f.close()
                dropWet()
            }
        }

        "review-258 B3/B4: an interrupted held commit keeps the run waiting; its retry re-reads, never resends" {
            val (f, fake, _) = wetFixture(keys = listOf("4711", "4712"), wire = "release_confirmed", initial = "placed")
            sqlAuto("INSERT INTO erp_runtime.sales_order VALUES ('4712', 'placed', NULL)")
            val runtime = f.start()
            try {
                val id =
                    UUID.fromString(
                        runtime.sessions
                            .open(
                                "release_order",
                                f.request.copy(
                                    dryRun = null,
                                    port =
                                        buildJsonObject {
                                            put("keys", JsonArray(listOf(JsonPrimitive("4711"), JsonPrimitive("4712"))))
                                        },
                                ),
                                f.caller,
                            ).response.runId,
                    )
                runtime.sessions.evaluate(id, f.caller)
                val pinned = requireNotNull(runtime.sessions.pinned(id))
                runtime.closer.close(id, f.caller).status shouldBe "waiting"
                val door =
                    QueryDoor(
                        fake,
                        emptyMap(),
                        ServiceTokens(emptyMap(), { error("unused") }),
                        mapOf("demo/erp_db" to "w-erp", "demo/automation_db" to "w-auto"),
                    )
                val decider =
                    org.tatrman.automation.writer.Held(
                        f.ledger,
                        org.tatrman.automation.writer
                            .Apply(door, f.clock),
                        org.tatrman.automation.writer
                            .BundleDeferredContexts(
                                { null },
                                ServiceTokens(emptyMap(), { error("unused") }),
                            ) { pinned },
                        { f.served },
                        f.clock,
                    )
                val ops = Caller("sub-olga", "olga", emptySet(), TokenKind.PERSON, "t", groups = setOf("hartland-ops"))
                val k4711 = buildJsonObject { put("order_number", "4711") }
                val k4712 = buildJsonObject { put("order_number", "4712") }

                // A's transition commits in the ERP, but the answer is lost: no decision is recorded over it.
                fake.loseCommit = { it.world == "w-erp" }
                val lost = shouldThrow<AutomationException> { decider.commitHeld(id, k4711, ops) }
                fake.loseCommit = { false }
                lost.code shouldBe ErrorCode.CLOSE_NOT_READY
                lost.detail!!.jsonObject["reason"] shouldBe JsonPrimitive("unresolved-outcome")
                query("SELECT state FROM erp_runtime.sales_order WHERE order_number = '4711'") shouldBe
                    listOf(listOf("released"))
                f.ledger.findRun(id)!!.status shouldBe RunStatus.WAITING
                // A commit that already sent rows cannot be rejected.
                shouldThrow<AutomationException> {
                    decider.rejectHeld(
                        id,
                        k4711,
                        ops,
                    )
                }.detail!!.jsonObject["reason"] shouldBe
                    JsonPrimitive("commit-started")

                // B4: deciding B while A has no decision does not close the run.
                decider.rejectHeld(id, k4712, ops).runStatus shouldBe "waiting"
                f.ledger.findRun(id)!!.status shouldBe RunStatus.WAITING
                transaction {
                    Batches.selectAll().where { Batches.run eq id }.single()[Batches.state] shouldBe "held"
                }

                // A's retry re-reads the ERP row (one probe, nothing written), finds the transition there, does not
                // resend it, records A's decision, and that last decision closes the run.
                val erpBefore = fake.writes.filter { it.world == "w-erp" }.map { it.context.correlationId }
                val committed = decider.commitHeld(id, k4711, ops)
                val erpAfter = fake.writes.filter { it.world == "w-erp" }.map { it.context.correlationId }
                (erpAfter - erpBefore.toSet()).map { it.substringAfter('/').substringBefore('/') } shouldBe
                    listOf("reconcile")
                committed.applied shouldBe 1
                committed.runStatus shouldBe "closed"
                query("SELECT state FROM erp_runtime.sales_order WHERE order_number = '4711'") shouldBe
                    listOf(listOf("released"))
                f.ledger.findRun(id)!!.status shouldBe RunStatus.CLOSED
                f.ledger
                    .readRunBatch(id, f.caller.sub)
                    .statuses
                    .filter { it.decided != null }
                    .map { (it.decided!!["decision"] as JsonPrimitive).content }
                    .sorted() shouldBe listOf("commit", "reject")
            } finally {
                runtime.shutdown()
                f.close()
                dropWet()
            }
        }

        "review-258 B3 (D-188): a held retry resends only a transition its CAS still guards; a moved row stays held" {
            val (f, fake, _) = wetFixture(keys = listOf("4711", "4712"), wire = "release_confirmed", initial = "placed")
            sqlAuto("INSERT INTO erp_runtime.sales_order VALUES ('4712', 'placed', NULL)")
            val runtime = f.start()
            try {
                val id =
                    UUID.fromString(
                        runtime.sessions
                            .open(
                                "release_order",
                                f.request.copy(
                                    dryRun = null,
                                    port =
                                        buildJsonObject {
                                            put("keys", JsonArray(listOf(JsonPrimitive("4711"), JsonPrimitive("4712"))))
                                        },
                                ),
                                f.caller,
                            ).response.runId,
                    )
                runtime.sessions.evaluate(id, f.caller)
                val pinned = requireNotNull(runtime.sessions.pinned(id))
                runtime.closer.close(id, f.caller).status shouldBe "waiting"
                val door =
                    QueryDoor(
                        fake,
                        emptyMap(),
                        ServiceTokens(emptyMap(), { error("unused") }),
                        mapOf("demo/erp_db" to "w-erp", "demo/automation_db" to "w-auto"),
                    )
                // The ERP apply request is lost before it reaches the ERP; re-reads pass.
                val dropping =
                    object : Door {
                        override suspend fun read(read: DoorRead) = door.read(read)

                        override suspend fun write(write: DoorWrite): WriteResult {
                            if (write.storage == "erp_db" && write.label.startsWith("apply/")) {
                                throw DoorWriteFailure.Unknown("worker_execution_failed", "connection lost")
                            }
                            return door.write(write)
                        }
                    }

                fun decider(d: Door) =
                    org.tatrman.automation.writer.Held(
                        f.ledger,
                        org.tatrman.automation.writer
                            .Apply(d, f.clock),
                        org.tatrman.automation.writer
                            .BundleDeferredContexts(
                                { null },
                                ServiceTokens(emptyMap(), { error("unused") }),
                            ) { pinned },
                        { f.served },
                        f.clock,
                    )
                val ops = Caller("sub-olga", "olga", emptySet(), TokenKind.PERSON, "t", groups = setOf("hartland-ops"))
                val k4711 = buildJsonObject { put("order_number", "4711") }
                val k4712 = buildJsonObject { put("order_number", "4712") }

                fun erpLabels() =
                    fake.writes.filter { it.world == "w-erp" }.map {
                        it.context.correlationId
                            .substringAfter('/')
                            .substringBefore('/')
                    }

                // 4711: lost before it landed; the order is still placed, a state its compare-and-set accepts → resent.
                shouldThrow<AutomationException> { decider(dropping).commitHeld(id, k4711, ops) }.code shouldBe
                    ErrorCode.CLOSE_NOT_READY
                query("SELECT state FROM erp_runtime.sales_order WHERE order_number = '4711'") shouldBe
                    listOf(listOf("placed"))
                val before = erpLabels().size
                val committed = decider(door).commitHeld(id, k4711, ops)
                erpLabels().drop(before) shouldBe listOf("reconcile", "apply")
                committed.applied shouldBe 1
                committed.runStatus shouldBe "waiting"
                query("SELECT state FROM erp_runtime.sales_order WHERE order_number = '4711'") shouldBe
                    listOf(listOf("released"))
                query("SELECT from_state, to_state FROM wet_runtime.state_history") shouldBe
                    listOf(listOf("placed", "released"))

                // 4712: lost, and another writer has moved the order on: no proof either way, nothing resent, undecided.
                shouldThrow<AutomationException> { decider(dropping).commitHeld(id, k4712, ops) }
                sqlAuto("UPDATE erp_runtime.sales_order SET state = 'cancelled' WHERE order_number = '4712'")
                val sent = erpLabels().size
                val stuck = shouldThrow<AutomationException> { decider(door).commitHeld(id, k4712, ops) }
                stuck.code shouldBe ErrorCode.CLOSE_NOT_READY
                stuck.detail!!.jsonObject["reason"] shouldBe JsonPrimitive("unresolved-outcome")
                erpLabels().drop(sent) shouldBe listOf("reconcile")
                query("SELECT state FROM erp_runtime.sales_order WHERE order_number = '4712'") shouldBe
                    listOf(listOf("cancelled"))
                f.ledger.findRun(id)!!.status shouldBe RunStatus.WAITING
            } finally {
                runtime.shutdown()
                f.close()
                dropWet()
            }
        }

        "an auto close after its executor is gone refuses instead of re-resolving a newer bundle" {
            val (f, fake, _) = wetFixture()
            val runtime = f.start()
            try {
                val id =
                    UUID.fromString(
                        runtime.sessions
                            .open("release_order", f.request.copy(dryRun = null), f.caller)
                            .response.runId,
                    )
                runtime.sessions.evaluate(id, f.caller)
                runtime.shutdown()
                val restarted = f.start()
                try {
                    shouldThrow<AutomationException> { restarted.closer.close(id, f.caller) }.code shouldBe
                        ErrorCode.CLOSE_NOT_READY
                    fake.writes.size shouldBe 0
                } finally {
                    restarted.shutdown()
                }
            } finally {
                runtime.shutdown()
                f.close()
                dropWet()
            }
        }

        "protocol: on_route omits the document when every route is none, and keeps it when a route fired" {
            for (routed in listOf(false, true)) {
                LedgerTestDb.reset()
                val (f, _, _) = wetFixture(protocol = "on_route", routed = routed)
                val runtime = f.start()
                try {
                    val id =
                        UUID.fromString(
                            runtime.sessions
                                .open("release_order", f.request, f.caller)
                                .response.runId,
                        )
                    runtime.sessions.evaluate(id, f.caller)
                    runtime.closer.close(id, f.caller)
                    val document = runtime.protocol.read(id, f.caller)
                    if (routed) {
                        document.sections.filterValues { it.isNotEmpty() }.keys.containsAll(
                            setOf("automation", "decision", "writes"),
                        ) shouldBe
                            true
                    } else {
                        document.sections.filterValues { it.isNotEmpty() }.keys shouldBe setOf("automation")
                        document.sections
                            .getValue("automation")
                            .single()
                            .payload shouldBe
                            buildJsonObject { put("sealed", true) }
                    }
                    runtime.closer.close(id, f.caller).status shouldBe "closed"
                    transaction { Runs.selectAll().where { Runs.id eq id }.single()[Runs.status] } shouldBe "closed"
                } finally {
                    runtime.shutdown()
                    f.close()
                    dropWet()
                }
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
                batch.rows.map { it.op } shouldBe listOf("insert", "transition", "delete", "insert")
                progress.batch!!.rows shouldBe 4
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
    transformBundle: (Bundle) -> Bundle = { it },
    /** Applied after the fixture prepares coverage (`withActions`). */
    afterPrepare: (Bundle) -> Bundle = { it },
    private val queryTable: ((RunRequest) -> Table)? = null,
    private val approvalReader: ApprovalReader? = null,
    private val lookupReads: List<String> = listOf("er.entity.obligation"),
    private val queryWarnings: (RunRequest) -> Set<String> = { emptySet() },
    /** S2.2: AUTOMATION_DOOR_WRITE_ROUTES, and the row_writes seam (the component fake). */
    writeRoutes: String = "",
    private val writes: QueryRun? = null,
) {
    /** Every row_writes request that reached the door — a dry run must leave it at zero (R217-L5). */
    val rowWrites = AtomicInteger()

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
                    automation.door.writeRoutes = "$writeRoutes"
                    automation.auth.jwksUrl = "http://unused/certs"
                    automation.auth.issuer = "unused"
                    automation.clock.skew = 90s
                    automation.budgets.syncDefault = $sync
                    automation.budgets.batchDefault = 10s
                    automation.runner.workers = 1
                    """.trimIndent(),
                ).withFallback(ConfigFactory.parseResources("application.conf"))
                .resolve(),
            emptyMap(),
        )
    val clock = Clock.offset(base, config.clockSkew)
    val ledger = Ledger(config.budgets.batchDefault, clock)
    val stagingRoot = Files.createTempDirectory("au-runtime-pg")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val caller = Caller("sub-maya", "maya", setOf("hartland-sales-managers"), TokenKind.PERSON, "not-stored")

    // The P1/S2.1 runtime specs are previews: S2.2 makes an unflagged open of a wet deployment an auto run (§1.2).
    val request = OpenRequest("release_native_event", buildJsonObject { put("key", "4711") }, dryRun = true)
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
    val served: Bundle get() = bundle

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
        ).let { afterPrepare(it.withActions(it.actions)) }
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

            override fun run(request: RunRequest): kotlinx.coroutines.flow.Flow<ResultBatch> {
                if (request.sourceLanguage == org.tatrman.translate.v1.Language.REL_NODE) {
                    rowWrites.incrementAndGet()
                    return requireNotNull(
                        writes,
                    ) { "a row_writes request reached a door with no write seam" }.run(request)
                }
                return flow {
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
        }

    fun start() =
        RunRuntime(
            config,
            { bundle },
            approvalReader ?: ApprovalReader {
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

/** These synthetic writer targets have no physical table binding; declare their test-only placement. */
private fun Bundle.withWriterStorage(entity: String): Bundle {
    val schema = model.schemas.getValue("world") as WorldSchema
    val worlds =
        schema.worlds.mapValues { (_, world) ->
            world.copy(
                storages =
                    world.storages.map { storage ->
                        if (storage.qname.name != "erp_db") {
                            storage
                        } else {
                            val writable = storage.writable + entity
                            val value =
                                PropertyValue.ListValue(
                                    writable.map { PropertyValue.StringValue(it, SourceLocation.UNKNOWN) },
                                    SourceLocation.UNKNOWN,
                                )
                            storage.copy(writable = writable, manifest = storage.manifest + ("writable" to value))
                        }
                    },
            )
        }
    return copy(model = model.copy(schemas = model.schemas + ("world" to schema.copy(worlds = worlds))))
}
