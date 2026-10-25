// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.apache.arrow.flight.FlightRuntimeException
import org.apache.arrow.flight.FlightStatusCode
import org.apache.arrow.flight.sql.FlightSqlColumnMetadata
import org.apache.arrow.vector.types.DateUnit
import org.apache.arrow.vector.types.TimeUnit
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema
import org.tatrman.meta.v1.AttributeJoinPair
import org.tatrman.meta.v1.RelationDetail
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.plan.v1.SchemaCode
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureTimeMillis

class CatalogSourceSpec :
    StringSpec({
        val session = EdgeSession(userId = "marketa", roles = listOf("kantheon-area-hartland"), world = "hartland_cz")
        val revenueSchema =
            Schema(
                listOf(
                    Field("customer_id", FieldType.nullable(ArrowType.Utf8()), null),
                    Field("revenue", FieldType.nullable(ArrowType.Decimal(38, 2, 128)), null),
                ),
            )

        class Probe : SchemaProbe {
            val calls = AtomicInteger()
            val statements = mutableListOf<Pair<String, EdgeSession>>()

            override suspend fun schemaOf(
                sql: String,
                session: EdgeSession,
            ): Schema? {
                calls.incrementAndGet()
                statements += sql to session
                return revenueSchema
            }
        }

        fun source(
            metadata: MetadataCatalogClient = RecordedMetadata(),
            probe: SchemaProbe = Probe(),
            clock: AtomicLong = AtomicLong(0),
        ) = CatalogSource(metadata, probe, ttlSeconds = 30, nanoClock = { clock.get() })

        // `order` relates to `date_dim` twice: placed and delivered (a star schema's role-playing dates).
        fun roleplayingDates(): org.tatrman.meta.v1.ModelBundle {
            fun qn(name: String) =
                QualifiedName
                    .newBuilder()
                    .setSchemaCode(SchemaCode.ER)
                    .setNamespace("entity")
                    .setName(name)
                    .build()

            fun dated(attribute: String) =
                RelationDetail
                    .newBuilder()
                    .setFromEntity(qn("order"))
                    .setToEntity(qn("date_dim"))
                    .addJoinPairs(
                        AttributeJoinPair
                            .newBuilder()
                            .setFromAttr(qn("order.$attribute"))
                            .setToAttr(qn("date_dim.sk")),
                    ).build()
            return RecordedMetadata
                .hartlandModel()
                .toBuilder()
                .addRelations(dated("placed_date"))
                .addRelations(dated("delivered_date"))
                .build()
        }

        "schemas are exactly the door's three spellings: db.dbo, er.entity, obj.query" {
            runBlocking {
                source().schemas(session, catalog = null, schemaPattern = null) shouldContainExactly
                    listOf("db.dbo", "er.entity", "obj.query")
            }
        }

        "the recorded hartland catalog lists all 73 objects: 36 entities, 1 logical query, 33 tables + 3 views" {
            runBlocking {
                val catalog = source()
                val tables = catalog.tables(session, null, null, null, withFields = false)
                tables shouldHaveSize 73
                tables.count { it.schema == "er.entity" } shouldBe 36
                tables.filter { it.schema == "obj.query" }.map { it.name } shouldContainExactly
                    listOf("revenue_per_customer")
                tables.count { it.schema == "db.dbo" && it.type == CatalogTable.Type.TABLE } shouldBe 33
                tables
                    .filter {
                        it.schema == "db.dbo" && it.type == CatalogTable.Type.VIEW
                    }.map { it.name } shouldContainExactly
                    listOf("channel_sales", "document_flow", "return_request")
            }
        }

        "one load reads ListObjects for entities, queries, tables and views (paged) and GetModel once" {
            runBlocking {
                val metadata = RecordedMetadata()
                source(metadata).tables(session, null, null, null)
                metadata.modelCalls.get() shouldBe 1
                // 36 entities, 1 query, 33 tables and 3 views at 20 per page: 2 + 1 + 2 + 1 pages.
                metadata.listCalls.get() shouldBe 6
                metadata.requestedPackages.single() shouldContainExactly listOf("hartland")
            }
        }

        "entity columns are typed from the model, keys non-null, the Flight SQL column metadata set" {
            runBlocking {
                val warehouse = source().tables(session, null, "er.entity", "warehouse").single()
                warehouse.arrowSchema.fields.map { it.name } shouldContainExactly
                    listOf("sk", "warehouse_id", "warehouse_name", "city", "state")
                val sk = warehouse.arrowSchema.findField("sk")
                sk.type shouldBe ArrowType.Int(32, true)
                sk.isNullable shouldBe false
                warehouse.arrowSchema.findField("warehouse_name").type shouldBe ArrowType.Utf8()
                warehouse.arrowSchema.findField("state").type shouldBe ArrowType.Utf8()
                warehouse.primaryKey shouldContainExactly listOf("sk")
                val meta = FlightSqlColumnMetadata(sk.metadata)
                meta.schemaName shouldBe "er.entity"
                meta.tableName shouldBe "warehouse"
                meta.typeName shouldBe "int"
                meta.isReadOnly shouldBe true
            }
        }

        "bound tables keep their physical columns; composite keys keep their order" {
            runBlocking {
                val catalog = source()
                val sales = catalog.tables(session, null, "db.dbo", "web_sales").single()
                sales.arrowSchema.findField("ws_ext_sales_price").type shouldBe ArrowType.Decimal(38, 18, 128)
                catalog.primaryKeys(session, null, "er.entity", "inventory").map { it.column } shouldContainExactly
                    listOf("as_of_date", "item", "warehouse")
                catalog.primaryKeys(session, null, "er.entity", "inventory").map { it.keySequence } shouldContainExactly
                    listOf(1, 2, 3)
            }
        }

        "a logical query's columns come from a one-row probe as the caller, cached per model version" {
            runBlocking {
                val probe = Probe()
                val metadata = RecordedMetadata()
                val clock = AtomicLong(0)
                val catalog = source(metadata, probe, clock)
                val query = catalog.tables(session, null, "obj.query", null).single()
                query.type shouldBe CatalogTable.Type.VIEW
                query.arrowSchema.fields.map { it.name } shouldContainExactly listOf("customer_id", "revenue")
                probe.statements.single() shouldBe ("SELECT * FROM obj.query.revenue_per_customer" to session)
                catalog.tables(session, null, "obj.query", null)
                probe.calls.get() shouldBe 1
                // A new model version is a new catalog, and the probe runs again.
                metadata.version = "01HARTLAND-2"
                clock.addAndGet(31_000_000_000)
                catalog.tables(session, null, "obj.query", null)
                probe.calls.get() shouldBe 2
            }
        }

        "a table listing without schemas does not probe" {
            runBlocking {
                val probe = Probe()
                source(probe = probe).tables(session, null, "obj.query", null, withFields = false)
                probe.calls.get() shouldBe 0
            }
        }

        "within the TTL a second call touches no Metadata; after it, an unchanged version reloads nothing" {
            runBlocking {
                val metadata = RecordedMetadata()
                val clock = AtomicLong(0)
                val catalog = source(metadata, clock = clock)
                catalog.schemas(session, null, null)
                catalog.tables(session, null, null, null, withFields = false)
                metadata.versionCalls.get() shouldBe 1
                metadata.modelCalls.get() shouldBe 1
                clock.addAndGet(31_000_000_000)
                catalog.schemas(session, null, null)
                metadata.versionCalls.get() shouldBe 2
                metadata.modelCalls.get() shouldBe 1
                catalog.loads shouldBe 1
            }
        }

        "filters: schema and table LIKE patterns, table types, and the connection's world as the only catalog" {
            runBlocking {
                val catalog = source()
                catalog.schemas(session, null, "er.%") shouldContainExactly listOf("er.entity")
                catalog
                    .tables(
                        session,
                        null,
                        "er.entity",
                        "ware_ouse",
                        withFields = false,
                    ).map { it.name } shouldContainExactly
                    listOf("warehouse")
                catalog.tables(session, null, "db.dbo", null, listOf("VIEW"), withFields = false) shouldHaveSize 3
                catalog.tables(session, "hartland_cz", "er.entity", "warehouse", withFields = false) shouldHaveSize 1
                catalog.tables(session, "hartland_us", null, null, withFields = false).shouldBeEmpty()
                catalog.schemas(session, "hartland_us", null).shouldBeEmpty()
                CatalogSource.catalogsOf(session) shouldContainExactly listOf("hartland_cz")
                CatalogSource.catalogsOf(session.copy(world = "")).shouldBeEmpty()
            }
        }

        "hartland's relations state no join pairs, so it lists no foreign keys; a relation with pairs does" {
            runBlocking {
                source().foreignKeys(session).shouldBeEmpty()

                fun qn(name: String) =
                    QualifiedName
                        .newBuilder()
                        .setSchemaCode(SchemaCode.ER)
                        .setNamespace("entity")
                        .setName(name)
                        .build()
                val related =
                    RecordedMetadata
                        .hartlandModel()
                        .toBuilder()
                        .addRelations(
                            RelationDetail
                                .newBuilder()
                                .setFromEntity(qn("inventory"))
                                .setToEntity(qn("warehouse"))
                                .addJoinPairs(
                                    AttributeJoinPair
                                        .newBuilder()
                                        .setFromAttr(
                                            qn("inventory.warehouse"),
                                        ).setToAttr(qn("warehouse.sk")),
                                ),
                        ).build()
                val key = source(RecordedMetadata(model = related)).foreignKeys(session).single()
                key shouldBe
                    CatalogForeignKey(
                        "er.entity",
                        "warehouse",
                        "sk",
                        "er.entity",
                        "inventory",
                        "warehouse",
                        1,
                        "inventory_warehouse_fkey",
                    )
            }
        }

        "R13 — two relations from one entity to another are two keys, each named, each from key_sequence 1" {
            runBlocking {
                val keys = source(RecordedMetadata(model = roleplayingDates())).foreignKeys(session)
                keys.map { Triple(it.name, it.fkColumn, it.keySequence) } shouldContainExactly
                    listOf(
                        Triple("order_placed_date_fkey", "placed_date", 1),
                        Triple("order_delivered_date_fkey", "delivered_date", 1),
                    )
                keys.map { it.pkTable }.distinct() shouldContainExactly listOf("date_dim")
            }
        }

        "R9 — a package holding only a saved query is loaded too" {
            runBlocking {
                val base = RecordedMetadata.hartlandModel()
                val query = base.namedQueriesList.single()
                val elsewhere =
                    query
                        .toBuilder()
                        .setObjectDescriptor(
                            query.objectDescriptor
                                .toBuilder()
                                .setPackageName("reports")
                                .setQualifiedName(
                                    query.objectDescriptor.qualifiedName
                                        .toBuilder()
                                        .setName("monthly"),
                                ),
                        ).build()
                val metadata = RecordedMetadata(model = base.toBuilder().addNamedQueries(elsewhere).build())
                val names =
                    source(
                        metadata,
                    ).tables(session, null, "obj.query", null, withFields = false).map { it.name }
                names shouldContainExactly listOf("monthly", "revenue_per_customer")
                metadata.requestedPackages.single() shouldContainExactly listOf("hartland", "reports")
            }
        }

        "R4 — probed columns are kept per role set: a caller with other roles gets its own probe's answer" {
            runBlocking {
                // The door answers the BI reader's probe, and refuses every other role set's.
                val asked = mutableListOf<List<String>>()
                val probe =
                    SchemaProbe { _, caller ->
                        asked += caller.roles
                        if ("kantheon-bi-reader" in caller.roles) revenueSchema else null
                    }
                val catalog = source(probe = probe)
                val reader = session.copy(roles = listOf("kantheon-area-hartland", "kantheon-bi-reader"))
                val other = session.copy(userId = "petr", roles = listOf("kantheon-scope-dc-5"))
                catalog
                    .tables(reader, null, "obj.query", null)
                    .single()
                    .fields
                    .map { it.name } shouldContainExactly
                    listOf("customer_id", "revenue")
                catalog
                    .tables(other, null, "obj.query", null)
                    .single()
                    .fields
                    .shouldBeEmpty()
                asked shouldContainExactly listOf(reader.roles, other.roles)
                // A refused probe is kept too: once per model version per role set, whoever asks.
                catalog
                    .tables(other.copy(userId = "someone"), null, "obj.query", null)
                    .single()
                    .fields
                    .shouldBeEmpty()
                // The same roles in another order are the same role set.
                catalog.tables(reader.copy(roles = reader.roles.reversed()), null, "obj.query", null)
                asked shouldHaveSize 2
            }
        }

        "R4 — a probe the door could not answer is not kept: the next listing asks again" {
            runBlocking {
                val calls = AtomicInteger()
                val probe =
                    SchemaProbe { _, _ ->
                        if (calls.incrementAndGet() == 1) error("door unavailable") else revenueSchema
                    }
                val catalog = source(probe = probe)
                catalog
                    .tables(session, null, "obj.query", null)
                    .single()
                    .fields
                    .shouldBeEmpty()
                catalog.tables(session, null, "obj.query", null).single().fields shouldHaveSize 2
                calls.get() shouldBe 2
            }
        }

        "R4 — one listing probes its uncached queries four at a time" {
            runBlocking {
                val base = RecordedMetadata.hartlandModel()
                val query = base.namedQueriesList.single()
                val five =
                    base
                        .toBuilder()
                        .addAllNamedQueries(
                            (1..4).map { i ->
                                query
                                    .toBuilder()
                                    .setObjectDescriptor(
                                        query.objectDescriptor
                                            .toBuilder()
                                            .setQualifiedName(
                                                query.objectDescriptor.qualifiedName
                                                    .toBuilder()
                                                    .setName("q$i"),
                                            ),
                                    ).build()
                            },
                        ).build()
                val running = AtomicInteger()
                val most = AtomicInteger()
                val probe =
                    SchemaProbe { _, _ ->
                        most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                        delay(200)
                        running.decrementAndGet()
                        revenueSchema
                    }
                val catalog = source(RecordedMetadata(model = five), probe)
                val took = measureTimeMillis { catalog.tables(session, null, "obj.query", null) shouldHaveSize 5 }
                most.get() shouldBe CatalogSource.PROBE_CONCURRENCY
                // Serially, five probes take 1 000 ms; four at a time, about 400.
                (took < 900) shouldBe true
            }
        }

        "a Metadata with no model loaded is UNAVAILABLE, not an empty catalog" {
            runBlocking {
                val e =
                    shouldThrow<FlightRuntimeException> {
                        source(
                            RecordedMetadata(version = ""),
                        ).schemas(session, null, null)
                    }
                e.status().code() shouldBe FlightStatusCode.UNAVAILABLE
            }
        }

        "model types map to the Arrow types the PostgreSQL worker returns" {
            ModelArrowTypes.arrowTypeOf("int") shouldBe ArrowType.Int(32, true)
            ModelArrowTypes.arrowTypeOf("bigint") shouldBe ArrowType.Int(64, true)
            ModelArrowTypes.arrowTypeOf("decimal(12,2)") shouldBe ArrowType.Decimal(12, 2, 128)
            ModelArrowTypes.arrowTypeOf("numeric") shouldBe ArrowType.Decimal(38, 18, 128)
            ModelArrowTypes.arrowTypeOf("char(16)") shouldBe ArrowType.Utf8()
            ModelArrowTypes.arrowTypeOf("TEXT") shouldBe ArrowType.Utf8()
            ModelArrowTypes.arrowTypeOf("bool") shouldBe ArrowType.Bool()
            ModelArrowTypes.arrowTypeOf("date") shouldBe ArrowType.Date(DateUnit.DAY)
            ModelArrowTypes.arrowTypeOf("timestamp") shouldBe ArrowType.Timestamp(TimeUnit.NANOSECOND, null)
            ModelArrowTypes.arrowTypeOf("something-new") shouldBe ArrowType.Utf8()
        }
    })
