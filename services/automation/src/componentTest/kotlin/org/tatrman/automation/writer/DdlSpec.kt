// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.writer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import kotlinx.serialization.json.*
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.tatrman.automation.LedgerTestDb
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.*
import org.tatrman.ttr.metadata.model.ErSchema

class DdlSpec :
    StringSpec({
        LedgerTestDb.ready()
        LedgerTestDb.asSuperuser { sql ->
            sql.execute(
                "DO $$ BEGIN CREATE ROLE au_writer_owner LOGIN PASSWORD 'writer-test'; " +
                    "EXCEPTION WHEN duplicate_object THEN NULL; END $$",
            )
            sql.execute("GRANT CREATE ON DATABASE automation TO au_writer_owner")
        }
        val ds = LedgerTestDb.dataSource("automation", "au_writer_owner", "writer-test")

        fun adminSql(text: String) =
            LedgerTestDb.db.getDataSource().connection.use { connection ->
                connection.autoCommit = true
                connection.createStatement().use { it.execute(text) }
            }
        val root = ApprovalFixture.copyTo(tempdir().toPath().resolve("bundle"))
        completeWriterCatalogs(root)
        val loaded =
            BundleLoader(
                org.tatrman.automation.identity
                    .AuthorMap(emptyMap()),
            ).load(Checkout(root, "master", "0123456789abcdef", "po", "po@example.org"), BundleKind.SERVED)
        val bundle = loaded

        fun sql(text: String) =
            ds.connection.use { c ->
                c.autoCommit = true
                c.createStatement().use { it.execute(text) }
            }

        fun strings(text: String) =
            ds.connection.use { c ->
                c.createStatement().use { s ->
                    s.executeQuery(text).use { r ->
                        buildList {
                            while (r.next()) add(r.getString(1))
                        }
                    }
                }
            }
        beforeEach {
            adminSql("DROP SCHEMA IF EXISTS us CASCADE")
            adminSql("DROP SCHEMA IF EXISTS cz CASCADE")
        }
        val expected =
            (
                bundle.recipes.values.flatMap {
                    listOfNotNull(
                        it.recipe.verdictSchema?.entity,
                        it.recipe.verdictSchema?.detail,
                        it.recipe.verdictSchema?.current,
                    )
                } + listOf("obligation", "state_history")
            ).sorted()

        "refresh DDL creates every catalog and stdlib table in each world schema idempotently" {
            val unmapped = shouldThrow<AutomationException> { Ddl.configured(ds, emptyMap(), null).refresh(bundle) }
            unmapped.code shouldBe ErrorCode.BUNDLE_INVALID
            unmapped.message!! shouldContain "demo"
            val ddl = Ddl(ds, { listOf("us", "cz") })
            val book =
                object : BundleBook {
                    override fun record(bundle: Bundle) = Unit

                    override fun touch(
                        id: String,
                        at: java.time.Instant,
                    ) = Unit
                }
            val registry =
                Bundles(
                    "master",
                    org.tatrman.automation.config
                        .PreviewsConfig(emptyList(), 24),
                    BundleSource { Checkout(root, "master", "0123456789abcdef", "po", "po@example.org") },
                    BundleLoader(
                        org.tatrman.automation.identity
                            .AuthorMap(emptyMap()),
                    ),
                    book,
                    prepare = ddl::refresh,
                )
            registry.refresh().changed shouldBe true
            val before = strings("SELECT oid::text FROM pg_class WHERE relnamespace = 'us'::regnamespace ORDER BY oid")
            registry.refresh().changed shouldBe false
            strings("SELECT oid::text FROM pg_class WHERE relnamespace = 'us'::regnamespace ORDER BY oid") shouldBe
                before
            for (schema in listOf("us", "cz")) {
                strings(
                    "SELECT table_name FROM information_schema.tables WHERE table_schema = '$schema' ORDER BY table_name",
                ) shouldBe
                    expected
            }
        }

        "additive attributes apply but a destructive type change is bundle-invalid naming its column" {
            val ddl = Ddl(ds, { listOf("us") })
            ddl.refresh(bundle)
            val er = bundle.model.schemas.getValue("er") as ErSchema
            val verdict = er.entities.values.single { it.qname.name == "release_order_verdict" }

            fun changed(
                type: String,
                extra: Boolean = false,
            ): Bundle {
                val fields =
                    verdict.attributes.map {
                        if (it.qname.name.endsWith(
                                ".order_number",
                            )
                        ) {
                            it.copy(type = type)
                        } else {
                            it
                        }
                    }
                val updated =
                    verdict.copy(
                        attributes =
                            fields +
                                if (extra) {
                                    listOf(
                                        fields.first().copy(
                                            internalId = "new-note",
                                            qname = fields.first().qname.copy(name = "release_order_verdict.note"),
                                            type = "text",
                                            nullable = true,
                                            isKey = false,
                                        ),
                                    )
                                } else {
                                    emptyList()
                                },
                    )
                return bundle.copy(
                    model =
                        bundle.model.copy(
                            schemas =
                                bundle.model.schemas +
                                    ("er" to er.copy(entities = er.entities + (verdict.qname to updated))),
                        ),
                    entities =
                        bundle.entities.map {
                            if (it.qname ==
                                verdict.qname
                            ) {
                                updated
                            } else {
                                it
                            }
                        },
                )
            }
            ddl.refresh(changed("text", extra = true))
            strings(
                "SELECT column_name FROM information_schema.columns WHERE table_schema='us' AND column_name='note'",
            ) shouldBe
                listOf("note")
            val error = shouldThrow<AutomationException> { ddl.refresh(changed("bigint", extra = true)) }
            error.code shouldBe ErrorCode.BUNDLE_INVALID
            error.message!! shouldContain "order_number"
            val changedKey =
                verdict.copy(
                    attributes =
                        verdict.attributes.map {
                            if (it.qname.name.endsWith(".order_number")) it.copy(isKey = false) else it
                        },
                )
            val keyBundle =
                bundle.copy(
                    model =
                        bundle.model.copy(
                            schemas =
                                bundle.model.schemas +
                                    ("er" to er.copy(entities = er.entities + (verdict.qname to changedKey))),
                        ),
                )
            shouldThrow<AutomationException> { ddl.refresh(keyBundle) }.message!! shouldContain "primary key"
        }

        "verdict updates are forbidden and only one open obligation may exist for an identity" {
            Ddl(ds, { listOf("us") }).refresh(bundle)
            sql(
                "INSERT INTO us.release_order_verdict VALUES ('00000000-0000-0000-0000-000000000001',now(),'4711','release','ok','fp','caller',now())",
            )
            shouldThrow<java.sql.SQLException> { sql("UPDATE us.release_order_verdict SET route='hold'") }

            fun insert(
                id: String,
                state: String,
                identity: String = "4711",
            ) = sql(
                "INSERT INTO us.obligation(id,recipe,run,entity,key,kind,due,state,created_at) VALUES ('$id','r','run','order','{\"order\":$identity}','inbound',current_date,'$state',now())",
            )
            insert("one", "open")
            shouldThrow<java.sql.SQLException> { insert("two", "open") }
            insert("closed", "fulfilled")
            for (state in listOf("overdue", "reopened", "fulfilled", "cancelled")) {
                val identity =
                    (
                        5000 +
                            listOf(
                                "overdue",
                                "reopened",
                                "fulfilled",
                                "cancelled",
                            ).indexOf(state)
                    ).toString()
                insert("$state-old", state, identity)
                if (state in setOf("overdue", "reopened")) {
                    shouldThrow<java.sql.SQLException> { insert("$state-new", "open", identity) }.sqlState shouldBe
                        "23505"
                } else {
                    insert("$state-new", "open", identity)
                }
            }
        }

        "an explicit door role receives only schema usage and table DML grants; unset grants none" {
            LedgerTestDb.asSuperuser {
                it.execute(
                    "DO $$ BEGIN CREATE ROLE au_door_test; " +
                        "EXCEPTION WHEN duplicate_object THEN NULL; END $$",
                )
            }
            Ddl(ds, { listOf("us") }, "au_door_test").refresh(bundle)
            strings(
                "SELECT table_name || ':' || privilege_type FROM information_schema.role_table_grants " +
                    "WHERE grantee='au_door_test' AND table_schema='us' ORDER BY table_name, privilege_type",
            ) shouldBe expected.flatMap { table -> listOf("DELETE", "INSERT", "SELECT", "UPDATE").map { "$table:$it" } }
            strings("SELECT has_schema_privilege('au_door_test','us','USAGE')::text") shouldBe listOf("true")
            Ddl(ds, { listOf("cz") }).refresh(bundle)
            strings(
                "SELECT table_name FROM information_schema.role_table_grants WHERE grantee='au_door_test' AND table_schema='cz'",
            ) shouldBe
                emptyList()
        }

        "non-null additive column on populated table is bundle-invalid and rolls back the refresh" {
            Ddl(ds, { listOf("us") }).refresh(bundle)
            sql("INSERT INTO us.release_order_verdict VALUES ('run',now(),'4711','release','ok','fp','caller',now())")
            val er = bundle.model.schemas.getValue("er") as ErSchema
            val verdict = er.entities.values.single { it.qname.name == "release_order_verdict" }
            val changed =
                verdict.copy(
                    attributes =
                        verdict.attributes +
                            verdict.attributes.first().copy(
                                internalId = "required-note",
                                qname = verdict.qname.copy(name = "release_order_verdict.required_note"),
                                type = "text",
                                nullable = false,
                                isKey = false,
                            ),
                )
            val next =
                bundle.copy(
                    model =
                        bundle.model.copy(
                            schemas =
                                bundle.model.schemas +
                                    ("er" to er.copy(entities = er.entities + (verdict.qname to changed))),
                        ),
                )
            val error = shouldThrow<AutomationException> { Ddl(ds, { listOf("us") }).refresh(next) }
            error.code shouldBe ErrorCode.BUNDLE_INVALID
            error.message!! shouldContain
                "non-null column added to populated table us.release_order_verdict.required_note"
            strings(
                "SELECT column_name FROM information_schema.columns WHERE table_schema='us' AND column_name='required_note'",
            ) shouldBe
                emptyList()
            strings("SELECT count(*)::text FROM us.release_order_verdict") shouldBe listOf("1")
        }

        "every planned fixture detail field conforms to the real PG catalog including the Core 382 marker" {
            // Use the full H-1 output schema fixture, plus the legacy H-2 detail-bearing recipes.
            val full = ApprovalFixture.copyTo(tempdir().toPath().resolve("conformance"))
            val h1 = full.resolve("automation/release_order/release_order.bundle")
            h1.toFile().deleteRecursively()
            copyFixtureTree(
                java.nio.file.Path
                    .of("src/test/resources/runner/hartland/release_order.bundle"),
                h1,
            )
            completeWriterCatalogs(full)
            val loaded =
                BundleLoader(
                    org.tatrman.automation.identity
                        .AuthorMap(emptyMap()),
                ).load(
                    Checkout(full, "master", "conformance", "po", "po@example.org"),
                    BundleKind.SERVED,
                )
            Ddl(ds, { listOf("us") }).refresh(loaded)
            var checked = 0
            for (recipe in loaded.recipes.values) {
                val model = recipe.recipe
                val contexts =
                    model.roles
                        ?.routes
                        .orEmpty()
                        .flatMap { it.detail }
                        .distinct()
                if (contexts.isEmpty()) continue
                val schema = requireNotNull(model.verdictSchema)
                val keys = model.key.map { it.substringAfterLast('.') }.toSet()
                val key = JsonObject(keys.associateWith { JsonPrimitive("4711") })
                val verdict =
                    JsonObject(
                        schema.columns.associate {
                            it
                                .getValue(
                                    "name",
                                ).jsonPrimitive.content to JsonPrimitive("value")
                        } +
                            key +
                            ("route" to JsonPrimitive("selected")),
                    )
                val rows =
                    mutableListOf(
                        org.tatrman.automation.apply.ApplyRow(
                            1,
                            "er.entity.${schema.entity}",
                            "insert",
                            key,
                            row = verdict,
                        ),
                    )
                for (context in contexts) {
                    val data =
                        JsonObject(
                            writerDetailColumns(
                                recipe.manifest,
                            ).associate { it.first to JsonPrimitive("value") } + key +
                                mapOf(
                                    "route" to JsonPrimitive("selected"),
                                    "context" to JsonPrimitive(context),
                                    "row_no" to JsonPrimitive(1),
                                    "truncated" to JsonPrimitive(false),
                                ),
                        )
                    rows +=
                        org.tatrman.automation.apply.ApplyRow(
                            rows.size + 1,
                            "er.entity.${schema.detail}",
                            "insert",
                            key,
                            row = data,
                        )
                    rows +=
                        org.tatrman.automation.apply.ApplyRow(
                            rows.size + 1,
                            "er.entity.${schema.detail}",
                            "insert",
                            key,
                            row =
                                JsonObject(
                                    data + ("truncated" to JsonPrimitive(true)),
                                ),
                        )
                }
                val preview =
                    org.tatrman.automation.apply.PreviewPlan(
                        "batch",
                        "run",
                        rows,
                        emptyList(),
                        source =
                            org.tatrman.automation.apply
                                .PlanSource(schema, emptyMap(), "automation_db"),
                        candidateKeys = listOf(key),
                    )
                val plan =
                    org.tatrman.automation.apply
                        .ApplyPlanner(java.time.Clock.systemUTC())
                        .plan(preview)
                for (row in plan.rows) {
                    val table = row.entity.removePrefix("er.entity.")
                    val columns =
                        strings(
                            "SELECT column_name FROM information_schema.columns WHERE table_schema='us' AND table_name='$table'",
                        ).toSet()
                    for (field in row.row.orEmpty().keys + row.set.orEmpty().keys + row.key.orEmpty().keys) {
                        withClue("${model.name}: $table.$field must be a catalog column") {
                            (field in columns) shouldBe
                                true
                        }
                    }
                }
                checked++
            }
            checked shouldBe 3 // release_order, monitor_inbound, settle; decide_return attaches no detail context.
        }

        "catalog time and monetary types retain the compiler precision and scale" {
            val er = bundle.model.schemas.getValue("er") as ErSchema
            val verdict = er.entities.values.single { it.qname.name == "release_order_verdict" }
            val changed =
                verdict.copy(
                    attributes =
                        verdict.attributes +
                            listOf("time", "money", "smallmoney").map { type ->
                                verdict.attributes.first().copy(
                                    internalId = "type-$type",
                                    qname = verdict.qname.copy(name = "release_order_verdict.$type"),
                                    type = type,
                                    nullable = true,
                                    isKey = false,
                                )
                            },
                )
            val next =
                bundle.copy(
                    model =
                        bundle.model.copy(
                            schemas =
                                bundle.model.schemas +
                                    ("er" to er.copy(entities = er.entities + (verdict.qname to changed))),
                        ),
                )
            val ddl = Ddl(ds, { listOf("us") })
            ddl.refresh(next)
            ddl.refresh(next)
            strings(
                "SELECT format_type(a.atttypid,a.atttypmod) FROM pg_attribute a WHERE a.attrelid='us.release_order_verdict'::regclass AND a.attname IN ('time','money','smallmoney') ORDER BY a.attname",
            ) shouldBe
                listOf("numeric(19,4)", "numeric(10,4)", "time without time zone")
        }
    })
