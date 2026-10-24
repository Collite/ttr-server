// SPDX-License-Identifier: Apache-2.0
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package org.tatrman.automation.apply

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import org.tatrman.automation.api.AutomationException
import org.tatrman.automation.api.ErrorCode
import org.tatrman.automation.bundle.BundleFixture
import org.tatrman.automation.bundle.ManifestColumn
import org.tatrman.automation.runner.BoundValue
import org.tatrman.automation.runner.Table
import org.tatrman.ttr.metadata.model.*
import java.time.Instant

class PreviewWriterSpec :
    StringSpec({
        val original = PreviewMetadata.from(BundleFixture.load())
        val loaded = original.entities.single { it.qname.name == "order" }
        val base = loaded.copy(qname = loaded.qname.copy(`package` = "hartland"))

        fun attribute(
            name: String,
            type: String,
            key: Boolean = false,
            nullable: Boolean = true,
        ) = Attribute(
            name,
            base.qname.copy(name = "order.$name"),
            entity = base.qname,
            type = type,
            isKey = key,
            nullable = nullable,
        )
        val entity =
            base.copy(
                attributes =
                    listOf(
                        attribute("id", "bigint", key = true),
                        attribute("tenant", "text", key = true),
                        attribute("amount", "decimal", nullable = false),
                        attribute("note", "text"),
                    ),
            )
        val columns =
            listOf(
                AutomationColumn("number", "order.id"),
                AutomationColumn("scope", "order.tenant"),
                AutomationColumn("total", "order.amount"),
                AutomationColumn("comment", "order.note", optional = true),
            )
        val action =
            original.actions
                .getValue(
                    "release",
                ).copy(columns = columns, sinks = mapOf("native" to "writer"), transition = null, set = null)
        val arguments =
            mapOf(
                "number" to JsonPrimitive(9007199254740993L),
                "scope" to JsonPrimitive("cz"),
                "total" to JsonUnquotedLiteral("4.560000000000000001"),
            )

        fun writer(
            a: AutomationAction = action,
            entities: List<Entity> = listOf(entity),
        ) = PreviewWriter.resolve(a, entities)

        fun refuses(block: () -> Unit) {
            shouldThrow<AutomationException> { block() }.code shouldBe ErrorCode.PREVIEW_UNSUPPORTED
        }

        "explicit fields produce one ordered insert with declared composite keys and exact values" {
            val row = writer().insert(7, arguments)
            row.seq shouldBe 7
            row.entity shouldBe "er.entity.order"
            row.op shouldBe "insert"
            row.key shouldBe
                buildJsonObject {
                    put("id", 9007199254740993L)
                    put("tenant", "cz")
                }
            row.row shouldBe
                JsonObject(
                    mapOf(
                        "id" to arguments.getValue("number"),
                        "tenant" to arguments.getValue("scope"),
                        "amount" to arguments.getValue("total"),
                    ),
                )
            ApplyRow.restore(row.seq, row.entity, row.op, row.key, row.storedRow(), null) shouldBe row
        }

        "optional omission remains distinct from explicit null" {
            refuses { writer().insert(1, arguments + ("unknown" to JsonPrimitive("secret"))) }
            writer().insert(1, arguments).row!!.containsKey("note") shouldBe false
            writer().insert(1, arguments + ("comment" to JsonNull)).row!!["note"] shouldBe JsonNull
            for (name in listOf("number", "scope", "total")) {
                refuses { writer().insert(1, arguments - name) }
                refuses { writer().insert(1, arguments + (name to JsonNull)) }
            }
        }

        "missing generated keys and required attributes never acquire inferred defaults" {
            refuses { writer(action.copy(columns = columns.filterNot { it.name == "number" })) }
            refuses { writer(action.copy(columns = columns.filterNot { it.name == "total" })) }
            refuses {
                writer(
                    entities = listOf(entity.copy(attributes = entity.attributes.map { it.copy(isKey = false) })),
                )
            }
            refuses {
                writer(
                    entities =
                        listOf(
                            entity.copy(
                                attributes =
                                    entity.attributes + attribute("generated", "int", nullable = false),
                            ),
                        ),
                )
            }
        }

        "ambiguous missing mixed unknown and duplicate target mappings refuse" {
            refuses { writer(entities = emptyList()) }
            refuses { writer(entities = listOf(entity, entity.copy(internalId = "other"))) }
            refuses { writer(action.copy(columns = columns + AutomationColumn("bad", "order.missing"))) }
            refuses { writer(action.copy(columns = columns + AutomationColumn("bad", "text"))) }
            refuses { writer(action.copy(columns = columns + AutomationColumn("bad", "order.amount"))) }
            refuses { writer(action.copy(columns = columns + columns.first())) }
            refuses { writer(action.copy(columns = emptyList())) }
            val other = entity.copy(qname = entity.qname.copy(name = "other"))
            refuses {
                writer(
                    action.copy(columns = columns + AutomationColumn("other", "other.amount")),
                    listOf(entity, other),
                )
            }
        }

        "qualified package and canonical target names resolve without guessing across packages" {
            for (prefix in listOf("hartland.order", "er.entity.order")) {
                writer(
                    action.copy(columns = columns.map { it.copy(type = "$prefix.${it.type.substringAfterLast('.')}") }),
                ).insert(1, arguments)
                    .row shouldBe writer().insert(1, arguments).row
            }
            val other = entity.copy(qname = entity.qname.copy(`package` = "other"))
            refuses { writer(entities = listOf(entity, other)) }
            refuses {
                writer(
                    action.copy(columns = columns.map { it.copy(type = "hartland.${it.type}") }),
                    listOf(entity, other),
                )
            } // The apply envelope cannot distinguish identical canonical names in two packages.
        }

        "aggregated attributes and combined writer effects refuse" {
            refuses {
                writer(
                    entities = listOf(entity.copy(attributes = entity.attributes.map { it.copy(aggregated = true) })),
                )
            }
            refuses { writer(action.copy(set = AutomationSet("order.note", "comment"))) }
            refuses { writer(action.copy(transition = AutomationTransition("order.state", to = "released"))) }
        }

        "published state vocabulary and preserved decimal bounds reject without rounding" {
            val states =
                entity.copy(
                    attributes =
                        entity.attributes.map {
                            if (it.qname.name.endsWith(".note")) it.copy(states = listOf("proposed")) else it
                        },
                )
            writer(
                entities = listOf(states),
            ).insert(1, arguments + ("comment" to JsonPrimitive("proposed"))).row!!["note"] shouldBe
                JsonPrimitive("proposed")
            refuses { writer(entities = listOf(states)).insert(1, arguments + ("comment" to JsonPrimitive("other"))) }
            val bounded =
                entity.copy(
                    attributes =
                        entity.attributes.map {
                            if (it.qname.name.endsWith(".amount")) it.copy(type = "decimal(4,2)") else it
                        },
                )
            for (value in listOf("100.00", "1.001")) {
                refuses {
                    writer(entities = listOf(bounded)).insert(
                        1,
                        arguments + ("total" to JsonUnquotedLiteral(value)),
                    )
                }
            }
            writer(
                entities = listOf(bounded),
            ).insert(1, arguments + ("total" to JsonUnquotedLiteral("99.9900"))).row!!["amount"] shouldBe
                JsonUnquotedLiteral("99.9900")
            val fractional =
                bounded.copy(
                    attributes =
                        bounded.attributes.map {
                            if (it.qname.name.endsWith(".amount")) it.copy(type = "decimal(2,2)") else it
                        },
                )
            writer(entities = listOf(fractional))
                .insert(1, arguments + ("total" to JsonUnquotedLiteral("0.0000")))
                .row!!["amount"] shouldBe JsonUnquotedLiteral("0.0000")
        }

        "preserved text bounds count Unicode codepoints without truncating" {
            val bounded =
                entity.copy(
                    attributes =
                        entity.attributes.map {
                            if (it.qname.name.endsWith(".note")) it.copy(type = "varchar(1)") else it
                        },
                )
            writer(
                entities = listOf(bounded),
            ).insert(1, arguments + ("comment" to JsonPrimitive("😀"))).row!!["note"] shouldBe
                JsonPrimitive("😀")
            refuses { writer(entities = listOf(bounded)).insert(1, arguments + ("comment" to JsonPrimitive("ab"))) }
        }

        "compiler assignment unification allows integer decimal widening without converting through Double" {
            val table = Table(listOf(ManifestColumn("n", "int")), listOf(listOf(9007199254740993L)))
            PreviewExpressions
                .project(
                    mapOf("value" to "n"),
                    table,
                    emptyMap(),
                    mapOf("value" to "decimal"),
                ).rows shouldBe
                table.rows
            refuses {
                PreviewExpressions.project(
                    mapOf("value" to "\"123\""),
                    table,
                    emptyMap(),
                    mapOf("value" to "int"),
                )
            }
            refuses { PreviewExpressions.project(mapOf("value" to "1.5"), table, emptyMap(), mapOf("value" to "int")) }
            refuses { PreviewExpressions.project(mapOf("value" to "n"), table, emptyMap(), mapOf("value" to "float")) }
        }

        "compiler assignment checking works on empty routes and nullable expressions" {
            val empty = Table(emptyList(), emptyList())
            refuses {
                PreviewExpressions.project(
                    mapOf("value" to "\"123\""),
                    empty,
                    emptyMap(),
                    mapOf("value" to "int"),
                )
            }
            PreviewExpressions
                .project(
                    mapOf("value" to "cast(null as int)"),
                    empty,
                    emptyMap(),
                    mapOf("value" to "int"),
                ).rows shouldBe
                emptyList()
            PreviewExpressions
                .project(
                    mapOf("value" to "null"),
                    Table(emptyList(), listOf(emptyList())),
                    emptyMap(),
                    mapOf(
                        "value" to "text",
                    ),
                ).rows shouldBe
                listOf(listOf(null))
        }

        "compiler temporal widening uses pinned date values and rejects implicit text dates" {
            val table = Table(emptyList(), listOf(emptyList()))
            val bindings = mapOf("as_of" to BoundValue("datetime", Instant.parse("2026-10-24T12:00:00Z")))
            PreviewExpressions
                .project(
                    mapOf("value" to "cast(:as_of as date)"),
                    table,
                    bindings,
                    mapOf(
                        "value" to "datetime",
                    ),
                ).rows.size shouldBe
                1
            refuses {
                PreviewExpressions.project(
                    mapOf("value" to "\"2026-10-24\""),
                    table,
                    bindings,
                    mapOf(
                        "value" to "date",
                    ),
                )
            }
        }
    })
