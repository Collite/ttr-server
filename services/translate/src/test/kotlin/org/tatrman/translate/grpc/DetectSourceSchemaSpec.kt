// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translate.grpc

import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.v1.DetectSchemaRequest
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.SchemaDecision
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import org.tatrman.translate.model.StaticModelHandleProvider
import org.tatrman.translator.framework.EntityMapping
import org.tatrman.translator.framework.ModelAttribute
import org.tatrman.translator.framework.ModelColumn
import org.tatrman.translator.framework.ModelEntity
import org.tatrman.translator.framework.ModelForeignKey
import org.tatrman.translator.framework.ModelHandle
import org.tatrman.translator.framework.ModelRelation
import org.tatrman.translator.framework.ModelSavedQuery
import org.tatrman.translator.framework.ModelTable
import org.tatrman.translator.framework.SavedQueryBody
import org.tatrman.translator.framework.SurfaceType

class DetectSourceSchemaSpec :
    StringSpec({

        val dbNamespace = "dbo"
        val erNamespace = "entity"

        val itemCategoryQname =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.DB)
                .setNamespace(dbNamespace)
                .setName("item_category")
                .build()
        val itemQname =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.DB)
                .setNamespace(dbNamespace)
                .setName("item")
                .build()
        val produktQname =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.ER)
                .setNamespace(erNamespace)
                .setName("produkt")
                .build()
        val skupinaQname =
            QualifiedName
                .newBuilder()
                .setSchemaCode(SchemaCode.ER)
                .setNamespace(erNamespace)
                .setName("skupina")
                .build()

        val itemCategoryTable =
            ModelTable(
                qname = itemCategoryQname,
                columns =
                    listOf(
                        org.tatrman.translator.framework
                            .ModelColumn("category_id", SurfaceType.INT),
                        org.tatrman.translator.framework
                            .ModelColumn("category_code", SurfaceType.TEXT),
                        org.tatrman.translator.framework
                            .ModelColumn("name", SurfaceType.TEXT),
                    ),
            )
        val itemTable =
            ModelTable(
                qname = itemQname,
                columns =
                    listOf(
                        org.tatrman.translator.framework
                            .ModelColumn("id", SurfaceType.INT),
                    ),
            )
        val produktEntity =
            ModelEntity(
                qname = produktQname,
                attributes =
                    listOf(
                        org.tatrman.translator.framework
                            .ModelAttribute("id", SurfaceType.INT),
                        org.tatrman.translator.framework
                            .ModelAttribute("name", SurfaceType.TEXT),
                    ),
            )
        val skupinaEntity =
            ModelEntity(
                qname = skupinaQname,
                attributes =
                    listOf(
                        org.tatrman.translator.framework
                            .ModelAttribute("id", SurfaceType.INT),
                    ),
            )

        val handle =
            object : ModelHandle {
                override fun tables(
                    schemaCode: SchemaCode,
                    namespace: String,
                ): Map<QualifiedName, ModelTable> =
                    listOf(itemCategoryTable, itemTable)
                        .filter { it.qname.schemaCode == schemaCode && it.qname.namespace == namespace }
                        .associateBy { it.qname }

                override fun columns(tableQname: QualifiedName): List<ModelColumn> =
                    listOf(itemCategoryTable, itemTable)
                        .find { it.qname == tableQname }
                        ?.columns ?: emptyList()

                override fun foreignKeys(): List<ModelForeignKey> = emptyList()

                override fun entities(
                    schemaCode: SchemaCode,
                    namespace: String,
                ): Map<QualifiedName, ModelEntity> =
                    listOf(produktEntity, skupinaEntity)
                        .filter { it.qname.schemaCode == schemaCode && it.qname.namespace == namespace }
                        .associateBy { it.qname }

                override fun attributes(entityQname: QualifiedName): List<ModelAttribute> =
                    listOf(produktEntity, skupinaEntity)
                        .find { it.qname == entityQname }
                        ?.attributes ?: emptyList()

                override fun relations(): List<ModelRelation> = emptyList()

                override fun entityMapping(entityQname: QualifiedName): EntityMapping? = null

                override fun savedQueries(
                    schemaCode: SchemaCode,
                    namespace: String,
                ): Map<QualifiedName, ModelSavedQuery> = emptyMap()

                override fun savedQueryBody(queryQname: QualifiedName): SavedQueryBody = error("Not implemented")

                override fun currentVersion(): String = "test-v1"

                override fun namespaces(schemaCode: SchemaCode): Set<String> =
                    when (schemaCode) {
                        SchemaCode.DB -> setOf("dbo")
                        SchemaCode.ER -> setOf("entity")
                        else -> emptySet()
                    }
            }
        val service = TranslatorServiceImpl(StaticModelHandleProvider(handle))

        "AUTODETECTED DB when stated=UNSPECIFIED and SQL uses DB table" {
            val resp =
                service.detectSourceSchema(
                    DetectSchemaRequest
                        .newBuilder()
                        .setSource("SELECT category_id, category_code FROM ITEM_CATEGORY WHERE name LIKE 'O%'")
                        .setSourceLanguage(Language.SQL)
                        .setStatedSchema(SchemaCode.SCHEMA_CODE_UNSPECIFIED)
                        .build(),
                )
            resp.decision shouldBe SchemaDecision.AUTODETECTED
            resp.effectiveSchema shouldBe SchemaCode.DB
            resp.messagesList shouldHaveSize 1
            resp.messagesList[0].severity.name shouldBe "INFO"
            resp.messagesList[0].code shouldBe "schema_autodetected"
        }

        "CORRECTED when stated=ER but SQL uses DB table" {
            val resp =
                service.detectSourceSchema(
                    DetectSchemaRequest
                        .newBuilder()
                        .setSource("SELECT id FROM ITEM_CATEGORY")
                        .setSourceLanguage(Language.SQL)
                        .setStatedSchema(SchemaCode.ER)
                        .build(),
                )
            resp.decision shouldBe SchemaDecision.CORRECTED
            resp.effectiveSchema shouldBe SchemaCode.DB
            resp.messagesList shouldHaveSize 1
            resp.messagesList[0].severity.name shouldBe "WARNING"
            resp.messagesList[0].code shouldBe "schema_corrected"
        }

        "UNKNOWN with populated SuggestionGroup" {
            val resp =
                service.detectSourceSchema(
                    DetectSchemaRequest
                        .newBuilder()
                        .setSource("SELECT id FROM ITEM_CATEGORYX")
                        .setSourceLanguage(Language.SQL)
                        .setStatedSchema(SchemaCode.SCHEMA_CODE_UNSPECIFIED)
                        .build(),
                )
            resp.decision shouldBe SchemaDecision.UNKNOWN
            resp.effectiveSchema shouldBe SchemaCode.SCHEMA_CODE_UNSPECIFIED
            resp.messagesList shouldHaveSize 1
            resp.messagesList[0].severity.name shouldBe "ERROR"
            resp.messagesList[0].code shouldBe "schema_object_unknown"
            resp.suggestionsList shouldHaveSize 1
            resp.suggestionsList[0].candidatesList shouldContainExactlyInAnyOrder listOf("item_category")
        }

        "NOT_APPLICABLE when sourceLanguage=DATAFRAME_DSL" {
            val resp =
                service.detectSourceSchema(
                    DetectSchemaRequest
                        .newBuilder()
                        .setSource("SELECT id FROM ITEM_CATEGORY")
                        .setSourceLanguage(Language.DATAFRAME_DSL)
                        .setStatedSchema(SchemaCode.SCHEMA_CODE_UNSPECIFIED)
                        .build(),
                )
            resp.decision shouldBe SchemaDecision.NOT_APPLICABLE
            resp.messagesList shouldHaveSize 0
        }
    })
