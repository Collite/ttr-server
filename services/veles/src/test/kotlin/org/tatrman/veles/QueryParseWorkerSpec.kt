// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles

import org.tatrman.meta.v1.GetQueryRequest
import org.tatrman.meta.v1.ListQueriesRequest
import org.tatrman.meta.v1.ParseStatus as ProtoParseStatus
import org.tatrman.meta.v1.ParseStatusFilter
import org.tatrman.veles.grpc.toProto
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.metadata.model.parseSchemaCode
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.veles.grpc.MetadataServiceImpl
import org.tatrman.ttr.metadata.model.DbColumn
import org.tatrman.ttr.metadata.model.DbSchema
import org.tatrman.ttr.metadata.model.DbTable
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.ModelVersion
import org.tatrman.ttr.metadata.model.ParseStatus
import org.tatrman.ttr.metadata.model.Query
import org.tatrman.ttr.metadata.model.QueryParameterDef
import org.tatrman.veles.parse.MetadataModelHandle
import org.tatrman.veles.parse.QueryParseState
import org.tatrman.veles.parse.QueryParseWorker
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

class QueryParseWorkerSpec :
    StringSpec({

        fun qn(
            schema: String,
            ns: String,
            name: String,
        ) = QualifiedName(
            schemaCode = parseSchemaCode(schema) ?: SchemaCode.UNSPECIFIED,
            namespace = ns,
            name = name,
        )

        val customersQn = qn("db", "dbo", "customers")
        // db.dbo.customers(id INT PK, name TEXT)
        val dbSchema =
            DbSchema(
                namespace = "dbo",
                tables =
                    mapOf(
                        customersQn to
                            DbTable(
                                internalId = "t-customers",
                                qname = customersQn,
                                columns =
                                    listOf(
                                        DbColumn(
                                            internalId = "c-id",
                                            qname = qn("db", "dbo", "id"),
                                            table = customersQn,
                                            dataType = "int",
                                            nullable = false,
                                            isPrimaryKey = true,
                                        ),
                                        DbColumn(
                                            internalId = "c-name",
                                            qname = qn("db", "dbo", "name"),
                                            table = customersQn,
                                            dataType = "text",
                                        ),
                                    ),
                                primaryKey = listOf("id"),
                            ),
                    ),
            )

        val okQuery =
            Query(
                internalId = "q-ok",
                qname = qn("obj", "q", "okQuery"),
                sourceLanguage = "SQL",
                sourceText = "SELECT id, name FROM customers",
                parseStatus = ParseStatus.ParsePending,
            )
        val badSqlQuery =
            Query(
                internalId = "q-bad-sql",
                qname = qn("obj", "q", "badSqlQuery"),
                sourceLanguage = "SQL",
                sourceText = "SELEKT * FROM customers",
                parseStatus = ParseStatus.ParsePending,
            )
        // Parametrized query: `{name_filter}` must be de-braced to a Calcite `?` via the
        // ParameterBridge before parsing. Without the declared parameters threaded through,
        // Calcite fails on `{` — the bug this fixes. (Infix-LIKE `'%' || {p} || '%'` form: a bare
        // `?` inside `||` is untypeable, so this also exercises the CAST(? AS text) typed retry.)
        val paramQuery =
            Query(
                internalId = "q-param",
                qname = qn("obj", "q", "paramQuery"),
                sourceLanguage = "SQL",
                sourceText = "SELECT id, name FROM customers WHERE name LIKE '%' || {name_filter} || '%'",
                parameters = listOf(QueryParameterDef(name = "name_filter", type = "text")),
                parseStatus = ParseStatus.ParsePending,
            )
        val unknownTableQuery =
            Query(
                internalId = "q-unknown-table",
                qname = qn("obj", "q", "unknownTableQuery"),
                sourceLanguage = "SQL",
                sourceText = "SELECT id FROM no_such_table",
                parseStatus = ParseStatus.ParsePending,
            )
        val unsupportedLangQuery =
            Query(
                internalId = "q-unsupported",
                qname = qn("obj", "q", "unsupportedLangQuery"),
                sourceLanguage = "PYTHON",
                sourceText = "print('hi')",
                parseStatus = ParseStatus.ParsePending,
            )

        fun model() =
            Model(
                descriptor = ModelDescriptor(id = "test", name = "test"),
                version = ModelVersion(value = "v1", swappedAt = Instant.now()),
                schemas = mapOf("db" to dbSchema),
                mappings = emptyList(),
                queries =
                    listOf(okQuery, badSqlQuery, unknownTableQuery, unsupportedLangQuery, paramQuery)
                        .associateBy { it.qname },
            )

        "MetadataModelHandle exposes db tables and columns from the model" {
            val handle = MetadataModelHandle(model())
            // MetadataModelHandle implements the proto-typed query-translator ModelHandle
            // interface, so its schema/qname args + returned keys are proto (converted here).
            val tables = handle.tables(SchemaCode.DB.toProto(), "dbo")
            tables.keys shouldContainExactlyInAnyOrder listOf(customersQn.toProto())
            handle.columns(customersQn.toProto()).map { it.name } shouldContainExactlyInAnyOrder listOf("id", "name")
            handle.tables(SchemaCode.ER.toProto(), "entity") shouldBe emptyMap()
            handle.currentVersion() shouldBe "v1"
        }

        "QueryParseWorker parses each query against the model and records PARSED / FAILED" {
            val state = QueryParseState()
            val m = model()
            state.reset(m.version.value, m.queries.keys)
            val worker = QueryParseWorker()
            worker.parseAll(m, state).join()

            state.get(m.version.value, okQuery.qname).shouldBeInstanceOf<ParseStatus.ParseSuccess>()
            // The parametrized query parses only because its `{name_filter}` placeholder is
            // de-braced to `?` via the ParameterBridge (declared params threaded through).
            state.get(m.version.value, paramQuery.qname).shouldBeInstanceOf<ParseStatus.ParseSuccess>()
            state.get(m.version.value, badSqlQuery.qname).shouldBeInstanceOf<ParseStatus.ParseFailure>()
            state.get(m.version.value, unknownTableQuery.qname).shouldBeInstanceOf<ParseStatus.ParseFailure>()
            val unsupported = state.get(m.version.value, unsupportedLangQuery.qname)
            unsupported.shouldBeInstanceOf<ParseStatus.ParseFailure>()
            unsupported.message shouldContain "PYTHON"

            val counts = checkNotNull(state.counts(m.version.value))
            counts.parsed shouldBe 2
            counts.failed shouldBe 3
            counts.pending shouldBe 0

            // the parsed query's canonical form round-trips as a PlanNode
            val ok = state.get(m.version.value, okQuery.qname)
            ok.shouldBeInstanceOf<ParseStatus.ParseSuccess>()
            org.tatrman.plan.v1.PlanNode
                .parseFrom(ok.canonicalFormProtoBytes) // must not throw
            worker.close()
        }

        "MetadataServiceImpl reflects the live parse state in ListQueries / GetQuery / GetStatus" {
            val m = model()
            val registry = MetadataRegistry()
            registry.swap(m, ModelGraph.build(m))
            val state = QueryParseState()
            state.reset(m.version.value, m.queries.keys)
            val service = MetadataServiceImpl(registry = registry, parseState = state)

            // before the worker runs: everything PENDING
            service
                .getStatus(
                    org.tatrman.meta.v1.GetStatusRequest
                        .getDefaultInstance(),
                ).let {
                    it.queriesTotal shouldBe 5
                    it.queriesPending shouldBe 5
                    it.queriesParsed shouldBe 0
                }

            QueryParseWorker().also { it.parseAll(m, state).join() }.close()

            service
                .getStatus(
                    org.tatrman.meta.v1.GetStatusRequest
                        .getDefaultInstance(),
                ).let {
                    it.queriesParsed shouldBe 2
                    it.queriesFailed shouldBe 3
                    it.queriesPending shouldBe 0
                }
            service
                .getQuery(
                    GetQueryRequest.newBuilder().setQualifiedName(okQuery.qname.toProto()).build(),
                ).parseStatus shouldBe
                ProtoParseStatus.PARSE_STATUS_PARSED
            service.getQuery(GetQueryRequest.newBuilder().setQualifiedName(badSqlQuery.qname.toProto()).build()).let {
                it.parseStatus shouldBe ProtoParseStatus.PARSE_STATUS_FAILED
                it.parseErrorMessage.isNotEmpty() shouldBe true
            }
            // ListQueries filter by parse status now sees the live result
            service
                .listQueries(
                    ListQueriesRequest
                        .newBuilder()
                        .setParseStatusFilter(
                            ParseStatusFilter.PARSE_STATUS_FILTER_PARSED,
                        ).build(),
                ).itemsList
                .map { it.objectDescriptor.localName } shouldContainExactlyInAnyOrder listOf("okQuery", "paramQuery")
        }
    })
