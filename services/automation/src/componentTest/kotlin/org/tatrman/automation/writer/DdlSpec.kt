// SPDX-License-Identifier: Apache-2.0
package org.tatrman.automation.writer

import io.kotest.assertions.throwables.shouldThrow
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
        // Complete the legacy fixture's pre-catalog recipes with typed test declarations.
        java.nio.file.Files.list(root.resolve("automation")).use { paths ->
            paths.forEach { recipe ->
                val directory = recipe.resolve("${recipe.fileName}.bundle")
                val manifest = directory.resolve("manifest.json")
                if (java.nio.file.Files
                        .exists(manifest) &&
                    recipe.fileName.toString() != "release_order"
                ) {
                    val model =
                        RecipeManifest
                            .parse(
                                java.nio.file.Files
                                    .readString(manifest),
                            ).recipe!!
                    val schema = model.verdictSchema!!

                    fun catalog(
                        name: String,
                        columns: List<Pair<String, String>>,
                        keys: Set<String>,
                    ) = "def entity $name { attributes: [" +
                        columns.joinToString(",") { (column, type) ->
                            "def attribute $column { type: $type${if (column in keys) ", isKey: true" else ""} }"
                        } + "] }"
                    val keyNames = model.key.map { it.substringAfterLast('.') }.toSet()
                    val columns =
                        schema.columns.map {
                            it.getValue("name").jsonPrimitive.content to it.getValue("type").jsonPrimitive.content
                        }
                    val entities =
                        listOf(
                            catalog(schema.entity, columns, keyNames + setOf("run", "route")),
                            catalog(schema.current!!, columns, keyNames + "route"),
                            catalog(
                                schema.detail!!,
                                listOf("run" to "text") + columns.filter { it.first in keyNames } +
                                    listOf("route" to "text", "context" to "text", "row_no" to "int"),
                                emptySet(),
                            ),
                        )
                    java.nio.file.Files.writeString(
                        directory.resolve("catalog/writer-test-entities.ttrm"),
                        "package hartland.automation.generated\nmodel er\n" + entities.joinToString("\n"),
                    )
                }
            }
        }
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
            ) = sql(
                "INSERT INTO us.obligation(id,recipe,run,entity,key,kind,due,state,created_at) VALUES ('$id','r','run','order','{\"order\":4711}','inbound',current_date,'$state',now())",
            )
            insert("one", "open")
            shouldThrow<java.sql.SQLException> { insert("two", "open") }
            insert("closed", "fulfilled")
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
                "SELECT DISTINCT privilege_type FROM information_schema.role_table_grants WHERE grantee='au_door_test' AND table_schema='us' ORDER BY privilege_type",
            ) shouldBe
                listOf("DELETE", "INSERT", "SELECT", "UPDATE")
            strings("SELECT has_schema_privilege('au_door_test','us','USAGE')::text") shouldBe listOf("true")
            Ddl(ds, { listOf("cz") }).refresh(bundle)
            strings(
                "SELECT table_name FROM information_schema.role_table_grants WHERE grantee='au_door_test' AND table_schema='cz'",
            ) shouldBe
                emptyList()
        }
    })
