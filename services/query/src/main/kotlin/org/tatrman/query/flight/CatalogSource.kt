// SPDX-License-Identifier: Apache-2.0
package org.tatrman.query.flight

import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.arrow.flight.CallStatus
import org.apache.arrow.flight.sql.FlightSqlColumnMetadata
import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.types.DateUnit
import org.apache.arrow.vector.types.FloatingPointPrecision
import org.apache.arrow.vector.types.TimeUnit
import org.apache.arrow.vector.types.pojo.ArrowType
import org.apache.arrow.vector.types.pojo.Field
import org.apache.arrow.vector.types.pojo.FieldType
import org.apache.arrow.vector.types.pojo.Schema
import org.slf4j.LoggerFactory
import org.tatrman.common.v1.Severity
import org.tatrman.meta.v1.GetModelRequest
import org.tatrman.meta.v1.GetModelResponse
import org.tatrman.meta.v1.GetStatusRequest
import org.tatrman.meta.v1.ListObjectsRequest
import org.tatrman.meta.v1.ListObjectsResponse
import org.tatrman.meta.v1.ModelBundle
import org.tatrman.meta.v1.ObjectDescriptor
import org.tatrman.meta.v1.PageRequest
import org.tatrman.meta.v1.VelesServiceGrpcKt
import org.tatrman.plan.v1.SchemaCode
import org.tatrman.query.v1.RowWindow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit as JTimeUnit

/**
 * One table the edge lists: an entity (`er.entity`), a saved logical query (`obj.query`) or a bound table or view
 * (`db.<schema>`). A logical query's [fields] are empty until probed (the model declares none).
 */
data class CatalogTable(
    val schema: String,
    val name: String,
    val type: Type,
    val fields: List<Field>,
    val primaryKey: List<String> = emptyList(),
    val description: String = "",
    val probed: Boolean = false,
) {
    val arrowSchema: Schema get() = Schema(fields)

    enum class Type(
        val label: String,
    ) {
        TABLE("TABLE"),
        VIEW("VIEW"),
    }
}

data class CatalogPrimaryKey(
    val schema: String,
    val table: String,
    val column: String,
    val keySequence: Int,
)

data class CatalogForeignKey(
    val pkSchema: String,
    val pkTable: String,
    val pkColumn: String,
    val fkSchema: String,
    val fkTable: String,
    val fkColumn: String,
    val keySequence: Int,
)

/** The three Veles reads the catalog needs. */
interface VelesCatalogClient {
    /** The model version Veles currently serves; empty when it has none loaded. */
    suspend fun modelVersion(): String

    suspend fun listObjects(request: ListObjectsRequest): ListObjectsResponse

    suspend fun getModel(request: GetModelRequest): GetModelResponse
}

/** The columns of a statement's result, as the door would return them; null when it cannot say. */
fun interface SchemaProbe {
    suspend fun schemaOf(
        sql: String,
        session: EdgeSession,
    ): Schema?
}

/**
 * BI (contracts C-4) — the Flight SQL catalog, from Veles: two `ListObjects` (entities, tables) name the model's
 * packages and one `GetModel(packages)` carries every entity, query, table and view in them (BI-0 memo §4: 73
 * objects in 0.14 s on hartland). The answer is cached by Veles' model version, which is itself re-read at most
 * once per [ttlSeconds]: within that window a catalog call touches no Veles at all.
 *
 * Schemas are exactly the door's own spellings (D-5h): `er.entity` (entities), `obj.query` (saved logical
 * queries — L-20: readable only as `obj.query.<name>`) and `db.<schema>` (bound tables and views, `db.dbo` on
 * hartland). Foreign keys come from E-R relations whose join pairs the model states; a relation without join
 * pairs yields none (hartland's relations state none, so hartland lists no keys between entities).
 */
class CatalogSource(
    private val veles: VelesCatalogClient,
    private val probe: SchemaProbe,
    private val ttlSeconds: Long = 30,
    private val nanoClock: () -> Long = System::nanoTime,
) {
    private val mutex = Mutex()

    @Volatile private var current: Snapshot? = null

    @Volatile private var checkedAtNanos = 0L

    private val probedFields = ConcurrentHashMap<String, List<Field>>()

    /** Model loads since boot (tests read it: a cached call loads nothing). */
    @Volatile var loads: Int = 0
        private set

    private data class Snapshot(
        val version: String,
        val tables: List<CatalogTable>,
        val foreignKeys: List<CatalogForeignKey>,
    )

    suspend fun schemas(
        session: EdgeSession,
        catalog: String?,
        schemaPattern: String?,
    ): List<String> {
        if (!catalogMatches(session, catalog)) return emptyList()
        val pattern = schemaPattern?.let(::likeRegex)
        return snapshot()
            .tables
            .map { it.schema }
            .distinct()
            .filter { pattern == null || pattern.matches(it) }
            .sorted()
    }

    suspend fun tables(
        session: EdgeSession,
        catalog: String?,
        schemaPattern: String?,
        tablePattern: String?,
        tableTypes: List<String> = emptyList(),
        withFields: Boolean = true,
    ): List<CatalogTable> {
        if (!catalogMatches(session, catalog)) return emptyList()
        val snapshot = snapshot()
        val schemaRegex = schemaPattern?.let(::likeRegex)
        val tableRegex = tablePattern?.let(::likeRegex)
        val types = tableTypes.map { it.uppercase() }.toSet()
        return snapshot.tables
            .filter { schemaRegex == null || schemaRegex.matches(it.schema) }
            .filter { tableRegex == null || tableRegex.matches(it.name) }
            .filter { types.isEmpty() || it.type.label in types }
            .map { if (withFields && it.probed) withProbedFields(snapshot.version, it, session) else it }
            .sortedWith(compareBy({ it.schema }, { it.name }))
    }

    suspend fun primaryKeys(
        session: EdgeSession,
        catalog: String?,
        schema: String?,
        table: String,
    ): List<CatalogPrimaryKey> {
        if (!catalogMatches(session, catalog)) return emptyList()
        return snapshot()
            .tables
            .filter { it.name == table && (schema == null || it.schema == schema) }
            .flatMap { t ->
                t.primaryKey.mapIndexed { i, column -> CatalogPrimaryKey(t.schema, t.name, column, i + 1) }
            }
    }

    suspend fun foreignKeys(session: EdgeSession): List<CatalogForeignKey> = snapshot().foreignKeys

    private suspend fun withProbedFields(
        version: String,
        table: CatalogTable,
        session: EdgeSession,
    ): CatalogTable {
        val key = "$version|${table.schema}.${table.name}"
        val fields =
            probedFields[key] ?: probe
                .schemaOf("SELECT * FROM ${table.schema}.${table.name}", session)
                ?.fields
                ?.map { it.withColumnMetadata(table.schema, table.name, it.type.toString()) }
                ?.also { probedFields[key] = it }
        return if (fields == null) table else table.copy(fields = fields)
    }

    private suspend fun snapshot(): Snapshot =
        mutex.withLock {
            val cached = current
            val now = nanoClock()
            if (cached != null && now - checkedAtNanos < JTimeUnit.SECONDS.toNanos(ttlSeconds)) return cached
            val version = veles.modelVersion()
            if (version.isEmpty()) {
                throw CallStatus.UNAVAILABLE
                    .withDescription("catalog_unavailable: the metadata service has no model loaded yet; retry shortly")
                    .toRuntimeException()
            }
            checkedAtNanos = now
            if (cached != null && cached.version == version) return cached
            load(version).also {
                current = it
                probedFields.keys.removeIf { key -> !key.startsWith("$version|") }
            }
        }

    private suspend fun load(version: String): Snapshot {
        loads++
        val objects = listAll(SchemaCode.ER, "entity") + listAll(SchemaCode.DB, "table")
        val packages =
            objects
                .map { it.packageName.ifEmpty { it.qualifiedName.`package` } }
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
        if (packages.isEmpty()) return Snapshot(version, emptyList(), emptyList())
        val response = veles.getModel(GetModelRequest.newBuilder().addAllPackages(packages).build())
        response.messagesList.firstOrNull { it.severity == Severity.ERROR }?.let {
            throw CallStatus.UNAVAILABLE
                .withDescription("catalog_unavailable: ${it.code}: ${it.humanMessage}")
                .toRuntimeException()
        }
        val tables = tablesOf(response.model)
        log.info(
            "Flight catalog loaded: model {} · packages {} · {} tables in {} schemas",
            version,
            packages,
            tables.size,
            tables.map { it.schema }.distinct().size,
        )
        return Snapshot(version, tables, foreignKeysOf(response.model, tables))
    }

    private suspend fun listAll(
        schema: SchemaCode,
        kind: String,
    ): List<ObjectDescriptor> {
        val out = mutableListOf<ObjectDescriptor>()
        var token = ""
        do {
            val page =
                veles.listObjects(
                    ListObjectsRequest
                        .newBuilder()
                        .setSchema(schema)
                        .setKind(kind)
                        .setPage(PageRequest.newBuilder().setPageSize(PAGE_SIZE).setPageToken(token))
                        .build(),
                )
            out += page.itemsList
            token = page.pageInfo.nextPageToken
        } while (token.isNotEmpty())
        return out
    }

    companion object {
        private val log = LoggerFactory.getLogger(CatalogSource::class.java)
        private const val PAGE_SIZE = 1000

        const val ENTITY_SCHEMA = "er.entity"
        const val QUERY_SCHEMA = "obj.query"

        /** The catalog a connection sees: its world (C-3), or none when the connection names no world. */
        fun catalogOf(session: EdgeSession): String? = session.world.ifEmpty { null }

        fun catalogsOf(session: EdgeSession): List<String> = listOfNotNull(catalogOf(session))

        private fun catalogMatches(
            session: EdgeSession,
            catalog: String?,
        ): Boolean = catalog == null || catalog == catalogOf(session).orEmpty()

        /** A JDBC/Flight SQL search pattern (`%` any run, `_` one character) as a case-insensitive regex. */
        fun likeRegex(pattern: String): Regex =
            Regex(
                pattern
                    .map { c ->
                        when (c) {
                            '%' -> ".*"
                            '_' -> "."
                            else -> Regex.escape(c.toString())
                        }
                    }.joinToString(""),
                RegexOption.IGNORE_CASE,
            )

        internal fun tablesOf(model: ModelBundle): List<CatalogTable> {
            val entities =
                model.entitiesList.map { e ->
                    val qn = e.objectDescriptor.qualifiedName
                    val schema = "er.${qn.namespace.ifEmpty { "entity" }}"
                    val attributes = e.attributesList
                    CatalogTable(
                        schema = schema,
                        name = qn.name,
                        type = CatalogTable.Type.TABLE,
                        fields =
                            attributes.map { a ->
                                val name =
                                    a.objectDescriptor.localName.ifEmpty {
                                        a.objectDescriptor.qualifiedName.name
                                            .substringAfterLast(
                                                '.',
                                            )
                                    }
                                modelField(
                                    name,
                                    a.detail.type,
                                    a.detail.nullable && !a.detail.isKey,
                                    schema,
                                    qn.name,
                                    a.objectDescriptor.description,
                                )
                            },
                        primaryKey =
                            attributes.filter { it.detail.isKey }.map {
                                it.objectDescriptor.localName.ifEmpty {
                                    it.objectDescriptor.qualifiedName.name
                                        .substringAfterLast(
                                            '.',
                                        )
                                }
                            },
                        description = e.objectDescriptor.description,
                    )
                }
            // A saved query with declared parameters cannot be read with a bare SELECT (the edge binds none), so
            // only parameterless logical queries are offered as tables (C-22, D-23).
            val queries =
                (model.namedQueriesList + model.patternQueriesList)
                    .filter { it.objectDescriptor.qualifiedName.schemaCode == SchemaCode.ER && it.parametersCount == 0 }
                    .distinctBy { it.objectDescriptor.qualifiedName.name }
                    .map { q ->
                        CatalogTable(
                            schema = QUERY_SCHEMA,
                            name = q.objectDescriptor.qualifiedName.name,
                            type = CatalogTable.Type.VIEW,
                            fields = emptyList(),
                            description = q.objectDescriptor.description,
                            probed = true,
                        )
                    }
            val tables =
                model.tablesList.map { t ->
                    val qn = t.objectDescriptor.qualifiedName
                    val schema = "db.${qn.namespace}"
                    val pk = t.detail.primaryKeyList
                    CatalogTable(
                        schema = schema,
                        name = qn.name,
                        type = CatalogTable.Type.TABLE,
                        fields =
                            t.detail.columnsList.map {
                                modelField(
                                    it.name,
                                    it.dataType,
                                    it.nullable && it.name !in pk,
                                    schema,
                                    qn.name,
                                    "",
                                )
                            },
                        primaryKey = pk,
                        description = t.objectDescriptor.description,
                    )
                }
            val views =
                model.viewsList.map { v ->
                    val qn = v.objectDescriptor.qualifiedName
                    val schema = "db.${qn.namespace}"
                    CatalogTable(
                        schema = schema,
                        name = qn.name,
                        type = CatalogTable.Type.VIEW,
                        fields =
                            v.detail.columnsList.map {
                                modelField(
                                    it.name,
                                    it.dataType,
                                    it.nullable,
                                    schema,
                                    qn.name,
                                    "",
                                )
                            },
                        description = v.objectDescriptor.description,
                    )
                }
            return entities + queries + tables + views
        }

        /**
         * Foreign keys from E-R relations that state join pairs: the side whose attribute is its entity's key is
         * referenced, the other side refers. A pair where neither side is a key states no key and is skipped.
         */
        internal fun foreignKeysOf(
            model: ModelBundle,
            tables: List<CatalogTable>,
        ): List<CatalogForeignKey> {
            val keys = tables.filter { it.schema == ENTITY_SCHEMA }.associate { it.name to it.primaryKey.toSet() }
            return model.relationsList.flatMap { relation ->
                val from = relation.fromEntity.name
                val to = relation.toEntity.name
                relation.joinPairsList.mapIndexedNotNull { i, pair ->
                    val fromAttr = pair.fromAttr.name.substringAfterLast('.')
                    val toAttr = pair.toAttr.name.substringAfterLast('.')
                    when {
                        toAttr in keys[to].orEmpty() ->
                            CatalogForeignKey(ENTITY_SCHEMA, to, toAttr, ENTITY_SCHEMA, from, fromAttr, i + 1)
                        fromAttr in keys[from].orEmpty() ->
                            CatalogForeignKey(ENTITY_SCHEMA, from, fromAttr, ENTITY_SCHEMA, to, toAttr, i + 1)
                        else -> null
                    }
                }
            }
        }

        private fun modelField(
            name: String,
            modelType: String,
            nullable: Boolean,
            schema: String,
            table: String,
            remarks: String,
        ): Field {
            val type = ModelArrowTypes.arrowTypeOf(modelType)
            return Field(
                name,
                FieldType(nullable, type, null, columnMetadata(schema, table, modelType, type, remarks)),
                null,
            )
        }

        private fun Field.withColumnMetadata(
            schema: String,
            table: String,
            typeName: String,
        ): Field =
            Field(
                name,
                FieldType(isNullable, type, dictionary, columnMetadata(schema, table, typeName, type, "")),
                children,
            )

        private fun columnMetadata(
            schema: String,
            table: String,
            typeName: String,
            type: ArrowType,
            remarks: String,
        ): Map<String, String> =
            FlightSqlColumnMetadata
                .Builder()
                .schemaName(schema)
                .tableName(table)
                .typeName(typeName)
                .isReadOnly(true)
                .apply {
                    if (type is ArrowType.Decimal) {
                        precision(type.precision)
                        scale(type.scale)
                    }
                    if (remarks.isNotEmpty()) remarks(remarks)
                }.build()
                .metadataMap
    }
}

/**
 * A model type string — an entity attribute's DSL surface type (`int`, `text`, `decimal`, …) or a table column's
 * SQL type (`varchar`, `char(16)`, `decimal(12,2)`, …) — as the Arrow type the PostgreSQL worker returns for it
 * (`PostgresArrowTypeMapper`). An undeclared decimal precision is the worker's unconstrained `Decimal(38, 18)`;
 * anything unrecognised is listed as text, which is what a JDBC client can always display.
 */
object ModelArrowTypes {
    fun arrowTypeOf(modelType: String): ArrowType {
        val spelled = modelType.trim().lowercase()
        val base = spelled.substringBefore('(').trim()
        val params =
            spelled
                .substringAfter('(', "")
                .substringBefore(')')
                .split(',')
                .mapNotNull { it.trim().toIntOrNull() }
        return when (base) {
            "smallint", "int2", "short" -> ArrowType.Int(16, true)
            "tinyint" -> ArrowType.Int(16, true)
            "int", "integer", "int4" -> ArrowType.Int(32, true)
            "bigint", "int8", "long" -> ArrowType.Int(64, true)
            "bool", "boolean", "bit" -> ArrowType.Bool()
            "real", "float4" -> ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE)
            "float", "float8", "double", "double precision" -> ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE)
            "decimal", "numeric", "money" ->
                if (params.isNotEmpty()) {
                    ArrowType.Decimal(
                        params[0],
                        params.getOrElse(1) {
                            0
                        },
                        128,
                    )
                } else {
                    ArrowType.Decimal(38, 18, 128)
                }
            "date" -> ArrowType.Date(DateUnit.DAY)
            "time" -> ArrowType.Time(TimeUnit.NANOSECOND, 64)
            "timestamp", "datetime", "datetime2" -> ArrowType.Timestamp(TimeUnit.NANOSECOND, null)
            "timestamptz", "datetimeoffset" -> ArrowType.Timestamp(TimeUnit.NANOSECOND, "UTC")
            "bytea", "binary", "varbinary", "blob" -> ArrowType.Binary()
            else -> ArrowType.Utf8()
        }
    }
}

/** The production [VelesCatalogClient]: Veles' gRPC (7261). */
class GrpcVelesCatalogClient(
    host: String,
    port: Int,
    private val deadlineSeconds: Long = 30,
) : VelesCatalogClient,
    AutoCloseable {
    private val channel: ManagedChannel =
        ManagedChannelBuilder
            .forAddress(host, port)
            .usePlaintext()
            .maxInboundMessageSize(256 * 1024 * 1024)
            .build()
    private val stub = VelesServiceGrpcKt.VelesServiceCoroutineStub(channel)

    override suspend fun modelVersion(): String =
        stub
            .withDeadlineAfter(deadlineSeconds, JTimeUnit.SECONDS)
            .getStatus(GetStatusRequest.getDefaultInstance())
            .let { if (it.modelLoaded) it.modelVersion else "" }

    override suspend fun listObjects(request: ListObjectsRequest): ListObjectsResponse =
        stub.withDeadlineAfter(deadlineSeconds, JTimeUnit.SECONDS).listObjects(request)

    override suspend fun getModel(request: GetModelRequest): GetModelResponse =
        stub.withDeadlineAfter(deadlineSeconds * 2, JTimeUnit.SECONDS).getModel(request)

    override fun close() {
        channel.shutdown().awaitTermination(5, JTimeUnit.SECONDS)
    }
}

/**
 * The production [SchemaProbe]: the statement's first row through the door, as the caller — so the probe is
 * validated like any statement and a caller who may not read the query learns none of its columns.
 */
class RunnerSchemaProbe(
    private val runner: StatementRunner,
    allocator: BufferAllocator,
) : SchemaProbe {
    private val reframer = BatchReframer(allocator)

    override suspend fun schemaOf(
        sql: String,
        session: EdgeSession,
    ): Schema? {
        val request =
            edgeRunRequest(sql, session, UUID.randomUUID().toString(), RowWindow.newBuilder().setLimit(1).build())
        return runCatching {
            runner
                .run(request)
                .first { batch ->
                    batch.messagesList.any { it.severity == Severity.ERROR } ||
                        batch.arrowIpc.size() > 0 ||
                        batch.isLast
                }.takeUnless { batch -> batch.messagesList.any { it.severity == Severity.ERROR } }
                ?.let { reframer.schemaOf(it.arrowIpc.toByteArray()) }
        }.onFailure { log.warn("Flight edge: schema probe failed for '{}': {}", sql, it.message) }
            .getOrNull()
    }

    private companion object {
        private val log = LoggerFactory.getLogger(RunnerSchemaProbe::class.java)
    }
}
