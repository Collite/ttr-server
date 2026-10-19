// SPDX-License-Identifier: Apache-2.0
package org.tatrman.translate.model

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import org.tatrman.meta.v1.AttributeDetail
import org.tatrman.meta.v1.DbColumnSummary
import org.tatrman.meta.v1.DbTableDetail
import org.tatrman.meta.v1.EntityDetail
import org.tatrman.meta.v1.Er2DbAttributeMappingDetail
import org.tatrman.meta.v1.Er2DbEntityMappingDetail
import org.tatrman.meta.v1.GetSnapshotResponse
import org.tatrman.meta.v1.ModelDescriptor
import org.tatrman.meta.v1.ModelSnapshot
import org.tatrman.meta.v1.ObjectDescriptor
import org.tatrman.meta.v1.ObjectEntry
import org.tatrman.meta.v1.ParseStatus
import org.tatrman.meta.v1.QueryDetail
import org.tatrman.plan.v1.PlanNode
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.translate.grpc.TranslatorServiceImpl
import org.tatrman.translate.snapshot.SnapshotModelHandle
import org.tatrman.translate.v1.DetectSchemaRequest
import org.tatrman.translate.v1.DetectSchemaResponse
import org.tatrman.translate.v1.Language
import org.tatrman.translate.v1.ParseRequest
import org.tatrman.translate.v1.ParseResponse
import org.tatrman.translate.v1.SchemaDecision
import org.tatrman.translate.v1.SqlDialect
import org.tatrman.translate.v1.TranslateRequest
import org.tatrman.translator.framework.HartlandStackModel

/**
 * LQ-T7·S1 (contract L-28) — at run time translate fills in a saved-query reference from its stored plan. The Golem's
 * pattern lane, the lattice and free SQL send a saved query's TEXT through `query.Run`, so a query that reads another
 * one (`FROM obj.dbo.store_revenue`, `FROM obj.query.shopper_revenue`) has to parse here as it did in veles: the handle
 * serves every PARSED saved query under its obj name, and the plan carries the body inlined, so validate secures the
 * tables it really reads. FAILED and PENDING queries carry no stored plan in the snapshot and are not served.
 *
 * The snapshot is built the way veles serves one: the queries' stored plans are what the translator itself produces
 * for their bodies over the same model.
 */
class SavedQueryRunTimeSpec :
    StringSpec({
        val salesQn = qn(SchemaCode.DB, "dbo", "sales")
        val saleQn = qn(SchemaCode.ER, "entity", "sale")
        val model =
            listOf(
                table(salesQn, "s_id" to "int", "s_customer" to "int", "s_amount" to "decimal"),
                entity(saleQn),
                attribute(saleQn, "sale_id", "int"),
                attribute(saleQn, "customer_id", "int"),
                attribute(saleQn, "amount", "decimal"),
                entityMapping(saleQn, salesQn),
                attributeMapping(saleQn, "sale_id", salesQn, "s_id"),
                attributeMapping(saleQn, "customer_id", salesQn, "s_customer"),
                attributeMapping(saleQn, "amount", salesQn, "s_amount"),
            )

        fun parseRequest(
            sql: String,
            schema: SchemaCode,
        ): ParseRequest =
            ParseRequest
                .newBuilder()
                .setSource(sql)
                .setSourceLanguage(Language.SQL)
                .setSourceSchema(schema)
                .setTargetSchema(schema)
                .build()

        fun storedPlan(
            sql: String,
            schema: SchemaCode,
        ): PlanNode {
            val handle = SnapshotModelHandle.from(ModelSnapshot.newBuilder().addAllObjects(model).build())
            val response =
                runBlocking {
                    TranslatorServiceImpl(
                        StaticModelHandleProvider(handle),
                    ).parseToRelNode(parseRequest(sql, schema))
                }
            withClue(response.messagesList) { response.messagesList.shouldBeEmpty() }
            return response.plan
        }

        val storeRevenue = qn(SchemaCode.DB, "dbo", "store_revenue")
        val shopperRevenue = qn(SchemaCode.ER, "query", "shopper_revenue")
        val snapshot =
            ModelSnapshot
                .newBuilder()
                .setModel(ModelDescriptor.newBuilder().setVersion("v1"))
                .addAllObjects(model)
                .addObjects(
                    query(
                        storeRevenue,
                        ParseStatus.PARSE_STATUS_PARSED,
                        storedPlan(
                            "SELECT s_customer, SUM(s_amount) AS revenue FROM sales GROUP BY s_customer",
                            SchemaCode.DB,
                        ),
                    ),
                ).addObjects(
                    query(
                        shopperRevenue,
                        ParseStatus.PARSE_STATUS_PARSED,
                        storedPlan(
                            "SELECT sale.customer_id, SUM(sale.amount) AS revenue FROM sale GROUP BY sale.customer_id",
                            SchemaCode.ER,
                        ),
                    ),
                ).addObjects(query(qn(SchemaCode.DB, "dbo", "broken"), ParseStatus.PARSE_STATUS_FAILED))
                .addObjects(query(qn(SchemaCode.DB, "dbo", "not_yet"), ParseStatus.PARSE_STATUS_PENDING))
                .build()

        // What translate runs on: the handle the provider builds from veles' GetSnapshot.
        val provider =
            MetadataServiceModelHandleProvider(
                getSnapshot = {
                    GetSnapshotResponse
                        .newBuilder()
                        .setEtag("v1")
                        .setSnapshot(snapshot)
                        .build()
                },
            ).also { runBlocking { it.refreshOnce() } }
        val service = TranslatorServiceImpl(provider)

        fun parse(
            sql: String,
            schema: SchemaCode,
        ): ParseResponse = runBlocking { service.parseToRelNode(parseRequest(sql, schema)) }

        "a db text reading a db query parses, with the body inlined: no obj scan, the table it reads" {
            val response =
                parse(
                    "SELECT r.s_customer, r.revenue FROM obj.dbo.store_revenue r WHERE r.revenue > 100",
                    SchemaCode.DB,
                )
            withClue(response.messagesList) { response.messagesList.shouldBeEmpty() }
            scans(response.plan).filter { it.schemaCode == SchemaCode.OBJ }.shouldBeEmpty()
            tableScans(response.plan) shouldContainExactly listOf("sales")
        }

        "an ER text reading a logical query parses, with the body inlined: the entity it reads" {
            val response =
                parse(
                    "SELECT r.customer_id, r.revenue FROM obj.query.shopper_revenue r WHERE r.revenue > 100",
                    SchemaCode.ER,
                )
            withClue(response.messagesList) { response.messagesList.shouldBeEmpty() }
            scans(response.plan).filter { it.schemaCode == SchemaCode.OBJ }.shouldBeEmpty()
            scans(response.plan).map { it.name } shouldContainExactly listOf("sale")
        }

        "the ER reader translates to SQL over the table the body reads" {
            val response =
                runBlocking {
                    service.translate(
                        TranslateRequest
                            .newBuilder()
                            .setSource(
                                "SELECT r.customer_id, r.revenue FROM obj.query.shopper_revenue r WHERE r.revenue > 100",
                            ).setSourceLanguage(Language.SQL)
                            .setTargetLanguage(Language.SQL)
                            .setSourceSchema(SchemaCode.ER)
                            .setTargetSchema(SchemaCode.DB)
                            .setTargetDialect(SqlDialect.MSSQL)
                            .build(),
                    )
                }
            withClue(response.messagesList) { response.messagesList.shouldBeEmpty() }
            response.output shouldContain "sales"
            response.output.lowercase() shouldNotContain "obj"
        }

        "a FAILED or a PENDING query is not served: it has no stored plan" {
            for (name in listOf("broken", "not_yet")) {
                val response = parse("SELECT * FROM obj.dbo.$name", SchemaCode.DB)
                withClue(name) {
                    response.messagesList.shouldNotBeEmpty()
                    response.hasPlan() shouldBe false
                    response.messagesList.joinToString { it.humanMessage } shouldContain name
                }
            }
        }

        "text without obj.* parses byte-identically to a handle that serves no saved query" {
            val plain = TranslatorServiceImpl(StaticModelHandleProvider(SnapshotModelHandle.from(snapshot)))
            for ((sql, schema) in listOf(
                "SELECT s_customer, SUM(s_amount) AS total FROM sales GROUP BY s_customer" to SchemaCode.DB,
                "SELECT sale.customer_id, sale.amount FROM sale WHERE sale.amount > 10" to SchemaCode.ER,
            )) {
                val expected = runBlocking { plain.parseToRelNode(parseRequest(sql, schema)) }
                parse(sql, schema).toByteArray().toList() shouldBe expected.toByteArray().toList()
            }
        }
        // query.Run detects the schema first and refuses UNKNOWN; a reader's text has to get through it to the parse.
        "schema detection leaves a saved-query reference out: the stated schema stands, an entity beside it votes ER" {
            fun detect(
                sql: String,
                stated: SchemaCode,
            ): DetectSchemaResponse =
                runBlocking {
                    service.detectSourceSchema(
                        DetectSchemaRequest
                            .newBuilder()
                            .setSource(sql)
                            .setSourceLanguage(Language.SQL)
                            .setStatedSchema(stated)
                            .build(),
                    )
                }
            for ((sql, stated) in listOf(
                "SELECT r.s_customer, r.revenue FROM obj.dbo.store_revenue r WHERE r.revenue > 100" to SchemaCode.DB,
                "SELECT r.customer_id, r.revenue FROM obj.query.shopper_revenue r WHERE r.revenue > 100" to
                    SchemaCode.ER,
            )) {
                val response = detect(sql, stated)
                withClue(sql) {
                    response.effectiveSchema shouldBe stated
                    response.decision shouldNotBe SchemaDecision.UNKNOWN
                }
            }
            val joined =
                detect(
                    "SELECT r.revenue, sale.amount FROM obj.query.shopper_revenue r " +
                        "JOIN sale ON sale.customer_id = r.customer_id",
                    SchemaCode.SCHEMA_CODE_UNSPECIFIED,
                )
            joined.effectiveSchema shouldBe SchemaCode.ER
        }
    })

private fun scans(plan: PlanNode): List<QualifiedName> =
    HartlandStackModel.nodes(plan).filter { it.hasScan() }.map { it.scan.`object` }

private fun tableScans(plan: PlanNode): List<String> =
    HartlandStackModel.nodes(plan).filter { it.hasTableScan() }.map { it.tableScan.table.name }

private fun qn(
    schema: SchemaCode,
    ns: String,
    name: String,
): QualifiedName =
    QualifiedName
        .newBuilder()
        .setSchemaCode(schema)
        .setNamespace(ns)
        .setName(name)
        .build()

private fun descriptor(
    qn: QualifiedName,
    kind: String,
): ObjectDescriptor =
    ObjectDescriptor
        .newBuilder()
        .setQualifiedName(qn)
        .setLocalName(qn.name)
        .setSchemaCode(qn.schemaCode)
        .setKind(kind)
        .build()

private fun query(
    qn: QualifiedName,
    status: ParseStatus,
    storedPlan: PlanNode? = null,
): ObjectEntry =
    ObjectEntry
        .newBuilder()
        .setObjectDescriptor(descriptor(qn, "query"))
        .setQuery(
            QueryDetail
                .newBuilder()
                .setSourceLanguage(org.tatrman.meta.v1.Language.SQL)
                .setParseStatus(status)
                .apply { storedPlan?.let { canonicalForm = it } },
        ).build()

private fun entity(entityQn: QualifiedName): ObjectEntry =
    ObjectEntry
        .newBuilder()
        .setObjectDescriptor(descriptor(entityQn, "entity"))
        .setEntity(EntityDetail.newBuilder().build())
        .build()

// Attribute qnames are entity-prefixed, as the metadata service writes them (`sale.amount`).
private fun attribute(
    entityQn: QualifiedName,
    name: String,
    type: String,
): ObjectEntry =
    ObjectEntry
        .newBuilder()
        .setObjectDescriptor(descriptor(qn(SchemaCode.ER, "entity", "${entityQn.name}.$name"), "er.attribute"))
        .setAttribute(
            AttributeDetail
                .newBuilder()
                .setEntity(entityQn)
                .setType(type)
                .setNullable(true)
                .build(),
        ).build()

private fun table(
    tableQn: QualifiedName,
    vararg columns: Pair<String, String>,
): ObjectEntry =
    ObjectEntry
        .newBuilder()
        .setObjectDescriptor(descriptor(tableQn, "db.table"))
        .setTable(
            DbTableDetail
                .newBuilder()
                .apply {
                    columns.forEach { (name, type) ->
                        addColumns(
                            DbColumnSummary
                                .newBuilder()
                                .setName(name)
                                .setDataType(type)
                                .setNullable(true),
                        )
                    }
                }.build(),
        ).build()

private fun entityMapping(
    entityQn: QualifiedName,
    tableQn: QualifiedName,
): ObjectEntry =
    ObjectEntry
        .newBuilder()
        .setObjectDescriptor(
            descriptor(qn(SchemaCode.SCHEMA_CODE_UNSPECIFIED, "er2db_entity", entityQn.name), "map.er2db_entity"),
        ).setEr2DbEntityMapping(
            Er2DbEntityMappingDetail
                .newBuilder()
                .setEntity(entityQn)
                .setTable(tableQn)
                .build(),
        ).build()

private fun attributeMapping(
    entityQn: QualifiedName,
    attr: String,
    tableQn: QualifiedName,
    column: String,
): ObjectEntry =
    ObjectEntry
        .newBuilder()
        .setObjectDescriptor(
            descriptor(
                qn(SchemaCode.SCHEMA_CODE_UNSPECIFIED, "er2db_attribute", "${entityQn.name}.$attr"),
                "map.er2db_attribute",
            ),
        ).setEr2DbAttributeMapping(
            Er2DbAttributeMappingDetail
                .newBuilder()
                .setAttribute(qn(SchemaCode.ER, "entity", "${entityQn.name}.$attr"))
                .setColumn(qn(SchemaCode.DB, tableQn.namespace, "${tableQn.name}.$column"))
                .build(),
        ).build()
