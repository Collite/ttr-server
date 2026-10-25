// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles

import org.tatrman.meta.v1.GetQueryRequest
import org.tatrman.meta.v1.Language
import org.tatrman.meta.v1.ListQueriesRequest
import org.tatrman.meta.v1.PageRequest
import org.tatrman.meta.v1.ParseStatus
import org.tatrman.meta.v1.ParseStatusFilter
import org.tatrman.plan.v1.PlanNode
import org.tatrman.veles.grpc.toProto
import org.tatrman.ttr.metadata.model.QualifiedName
import org.tatrman.ttr.metadata.model.SchemaCode
import org.tatrman.ttr.metadata.graph.ModelGraph
import org.tatrman.veles.grpc.MetadataServiceImpl
import org.tatrman.veles.grpc.PackageIndex
import org.tatrman.ttr.metadata.model.Model
import org.tatrman.ttr.metadata.model.ModelDescriptor
import org.tatrman.ttr.metadata.model.ModelVersion
import org.tatrman.ttr.metadata.model.Query
import org.tatrman.ttr.metadata.model.QueryParameterDef
import org.tatrman.ttr.metadata.registry.MetadataRegistry
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotBeEmpty
import org.tatrman.ttr.metadata.model.ParseStatus as DomainParseStatus
import java.time.Instant

class MetadataQuerySpec :
    StringSpec({

        fun qn(name: String) =
            QualifiedName(
                schemaCode = SchemaCode.UNSPECIFIED,
                namespace = "q",
                name = name,
            )

        // Three queries: one PARSED, one PENDING, one FAILED — covering the parse-status surface.
        val parsedQuery =
            Query(
                internalId = "q-find-customers",
                qname = qn("findCustomers"),
                description = "list customers",
                tags = listOf("core", "reports"),
                sourceLanguage = "SQL",
                sourceText = "SELECT id, name FROM customers WHERE tenant_id = :tenant",
                parameters = listOf(QueryParameterDef(name = "tenant", type = "int", label = "Tenant")),
                parseStatus = DomainParseStatus.ParseSuccess(PlanNode.getDefaultInstance().toByteArray()),
            )
        val pendingQuery =
            Query(
                internalId = "q-orders-by-customer",
                qname = qn("ordersByCustomer"),
                tags = listOf("reports"),
                sourceLanguage = "TRANSDSL",
                sourceText = "from(orders) | join(customers) | select(*)",
                parseStatus = DomainParseStatus.ParsePending,
            )
        val failedQuery =
            Query(
                internalId = "q-broken",
                qname = qn("brokenQuery"),
                sourceLanguage = "SQL",
                sourceText = "SELEKT * FROM nope",
                parseStatus =
                    DomainParseStatus.ParseFailure(
                        message = "syntax error near 'SELEKT'",
                        location = "line 1:0",
                    ),
            )

        fun service(): MetadataServiceImpl {
            val model =
                Model(
                    descriptor = ModelDescriptor(id = "test", name = "test"),
                    version = ModelVersion(value = "v1", swappedAt = Instant.now()),
                    schemas = emptyMap(),
                    mappings = emptyList(),
                    queries =
                        listOf(parsedQuery, pendingQuery, failedQuery).associateBy { it.qname },
                )
            val registry = MetadataRegistry()
            registry.swap(model, ModelGraph.build(model))
            return MetadataServiceImpl(registry, packageIndex = PackageIndex())
        }

        "ListQueries returns all queries with parse status and parameter counts" {
            val r = service().listQueries(ListQueriesRequest.getDefaultInstance())
            r.itemsList.size shouldBe 3
            r.pageInfo.totalCount shouldBe 3
            val byName = r.itemsList.associateBy { it.objectDescriptor.localName }
            byName.getValue("findCustomers").let {
                it.sourceLanguage shouldBe Language.SQL
                it.parseStatus shouldBe ParseStatus.PARSE_STATUS_PARSED
                it.parameterCount shouldBe 1
            }
            byName.getValue("ordersByCustomer").let {
                it.sourceLanguage shouldBe Language.TRANSFORMATION_DSL
                it.parseStatus shouldBe ParseStatus.PARSE_STATUS_PENDING
                it.parameterCount shouldBe 0
            }
            byName.getValue("brokenQuery").parseStatus shouldBe ParseStatus.PARSE_STATUS_FAILED
        }

        "ListQueries filters by tag" {
            val r =
                service().listQueries(ListQueriesRequest.newBuilder().addTags("core").build())
            r.itemsList.map { it.objectDescriptor.localName } shouldContainExactly listOf("findCustomers")
        }

        "ListQueries filters by language" {
            val r =
                service().listQueries(
                    ListQueriesRequest.newBuilder().setLanguageFilter(Language.TRANSFORMATION_DSL).build(),
                )
            r.itemsList.map { it.objectDescriptor.localName } shouldContainExactly listOf("ordersByCustomer")
        }

        "ListQueries filters by parse status" {
            val r =
                service().listQueries(
                    ListQueriesRequest
                        .newBuilder()
                        .setParseStatusFilter(ParseStatusFilter.PARSE_STATUS_FILTER_FAILED)
                        .build(),
                )
            r.itemsList.map { it.objectDescriptor.localName } shouldContainExactly listOf("brokenQuery")
        }

        "ListQueries paginates via page tokens" {
            val svc = service()
            val first =
                svc.listQueries(
                    ListQueriesRequest.newBuilder().setPage(PageRequest.newBuilder().setPageSize(2)).build(),
                )
            first.itemsList.size shouldBe 2
            first.pageInfo.nextPageToken.shouldNotBeEmpty()
            val second =
                svc.listQueries(
                    ListQueriesRequest
                        .newBuilder()
                        .setPage(PageRequest.newBuilder().setPageSize(2).setPageToken(first.pageInfo.nextPageToken))
                        .build(),
                )
            second.itemsList.size shouldBe 1
            second.pageInfo.nextPageToken.shouldBeEmpty()
        }

        "GetQuery returns the source text, parameters and parse status" {
            val r =
                service().getQuery(GetQueryRequest.newBuilder().setQualifiedName(qn("findCustomers").toProto()).build())
            r.objectDescriptor.localName shouldBe "findCustomers"
            r.sourceLanguage shouldBe Language.SQL
            r.sourceText shouldContain "FROM customers"
            r.parametersList.single().name shouldBe "tenant"
            r.parametersList.single().type shouldBe "int"
            r.parseStatus shouldBe ParseStatus.PARSE_STATUS_PARSED
            r.messagesList.size shouldBe 0
            // canonical form omitted unless requested
            r.hasCanonicalForm() shouldBe false
        }

        "GetQuery includes the canonical form when requested for a parsed query" {
            val r =
                service().getQuery(
                    GetQueryRequest
                        .newBuilder()
                        .setQualifiedName(
                            qn("findCustomers").toProto(),
                        ).setIncludeCanonicalForm(true)
                        .build(),
                )
            r.hasCanonicalForm() shouldBe true
        }

        "GetQuery surfaces the parse error for a failed query" {
            val r =
                service().getQuery(GetQueryRequest.newBuilder().setQualifiedName(qn("brokenQuery").toProto()).build())
            r.parseStatus shouldBe ParseStatus.PARSE_STATUS_FAILED
            r.parseErrorMessage shouldContain "SELEKT"
            r.parseErrorLocation shouldBe "line 1:0"
        }

        "GetQuery reports object_not_found via messages, not a gRPC error" {
            val r =
                service().getQuery(GetQueryRequest.newBuilder().setQualifiedName(qn("noSuchQuery").toProto()).build())
            r.messagesList.single().code shouldBe "object_not_found"
        }

        "ListQueries/GetQuery on an unloaded registry report metadata_not_ready" {
            val empty = MetadataServiceImpl(MetadataRegistry(), packageIndex = PackageIndex())
            empty
                .listQueries(ListQueriesRequest.getDefaultInstance())
                .messagesList
                .single()
                .code shouldBe
                "metadata_not_ready"
            empty
                .getQuery(GetQueryRequest.newBuilder().setQualifiedName(qn("x").toProto()).build())
                .messagesList
                .single()
                .code shouldBe "metadata_not_ready"
        }
    })
