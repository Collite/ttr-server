// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.mcp

import org.tatrman.meta.v1.ListObjectsResponse
import org.tatrman.meta.v1.ListQueriesResponse
import org.tatrman.meta.v1.ObjectDescriptor
import org.tatrman.meta.v1.QueryDescriptor
import org.tatrman.plan.v1.QualifiedName as ProtoQname
import org.tatrman.plan.v1.SchemaCode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.tatrman.veles.client.MetadataGrpcClient

/**
 * Covers the list-tool callbacks now that every metadata read routes through the gRPC
 * [MetadataGrpcClient] (the metadata service exposes no REST data endpoints). Each test
 * stubs the relevant gRPC list RPC and asserts the structured JSON shape the MCP layer
 * builds — an object with a single named array, never a bare JSON array at the root.
 */
class ToolsTest :
    StringSpec({

        fun descriptor(
            schema: SchemaCode,
            namespace: String,
            name: String,
            kind: String,
        ): ObjectDescriptor =
            ObjectDescriptor
                .newBuilder()
                .setLocalName(name)
                .setKind(kind)
                .setQualifiedName(
                    ProtoQname
                        .newBuilder()
                        .setSchemaCode(schema)
                        .setNamespace(namespace)
                        .setName(name)
                        .build(),
                ).build()

        fun objectsResponse(vararg items: ObjectDescriptor): ListObjectsResponse =
            ListObjectsResponse.newBuilder().addAllItems(items.toList()).build()

        fun queriesResponse(vararg items: ObjectDescriptor): ListQueriesResponse =
            ListQueriesResponse
                .newBuilder()
                .addAllItems(items.map { QueryDescriptor.newBuilder().setObjectDescriptor(it).build() })
                .build()

        val grpc = mockk<MetadataGrpcClient>(relaxed = true)
        val tools = Tools(grpc)
        val request = mockk<CallToolRequest>(relaxed = true)

        "getTablesCallback builds structuredContent with a tables array" {
            coEvery { grpc.listObjects(kind = "table", packageFilter = "") } returns
                objectsResponse(descriptor(SchemaCode.DB, "dbo", "users", "table"))

            val structured = tools.getTablesCallback(request).structuredContent as JsonObject
            structured["tables"].shouldNotBe(null)
            (structured["tables"] as? JsonArray)?.size shouldBe 1
        }

        "getEntitiesCallback builds structuredContent with an entities array" {
            coEvery { grpc.listObjects(kind = "entity", packageFilter = "") } returns
                objectsResponse(descriptor(SchemaCode.ER, "entity", "user", "entity"))

            val structured = tools.getEntitiesCallback(request).structuredContent as JsonObject
            structured["entities"].shouldNotBe(null)
            (structured["entities"] as? JsonArray)?.size shouldBe 1
        }

        "getRelationshipsCallback builds structuredContent with a relationships array" {
            coEvery { grpc.listObjects(kind = "relation", packageFilter = "") } returns
                objectsResponse(descriptor(SchemaCode.ER, "relation", "user_address", "relation"))

            val structured = tools.getRelationshipsCallback(request).structuredContent as JsonObject
            structured["relationships"].shouldNotBe(null)
            (structured["relationships"] as? JsonArray)?.size shouldBe 1
        }

        "getPatternQueriesCallback builds structuredContent with a patternQueries array" {
            coEvery { grpc.listQueries(packageFilter = "") } returns
                queriesResponse(descriptor(SchemaCode.OBJ, "query", "product_search", "query"))

            val structured = tools.getPatternQueriesCallback(request).structuredContent as JsonObject
            structured["patternQueries"].shouldNotBe(null)
            (structured["patternQueries"] as? JsonArray)?.size shouldBe 1
        }

        "getSqlQueriesCallback builds structuredContent with a sqlQueries array" {
            coEvery { grpc.listQueries(packageFilter = "") } returns
                queriesResponse(descriptor(SchemaCode.OBJ, "query", "all_users", "query"))

            val structured = tools.getSqlQueriesCallback(request).structuredContent as JsonObject
            structured["sqlQueries"].shouldNotBe(null)
            (structured["sqlQueries"] as? JsonArray)?.size shouldBe 1
        }

        "getStoredProceduresCallback builds structuredContent with a storedProcedures array" {
            coEvery { grpc.listObjects(kind = "procedure", packageFilter = "") } returns
                objectsResponse(descriptor(SchemaCode.DB, "dbo", "get_user", "procedure"))

            val structured = tools.getStoredProceduresCallback(request).structuredContent as JsonObject
            structured["storedProcedures"].shouldNotBe(null)
            (structured["storedProcedures"] as? JsonArray)?.size shouldBe 1
        }

        "list callbacks return a JsonObject (never a bare JsonArray) at the structuredContent root" {
            coEvery { grpc.listObjects(kind = "table", packageFilter = "") } returns objectsResponse()

            val result = tools.getTablesCallback(request)
            result.structuredContent.shouldNotBe(null)
            result.structuredContent!!::class shouldBe JsonObject::class
            result.structuredContent!!::class shouldNotBe JsonArray::class
            (result.structuredContent as JsonObject).containsKey("tables") shouldBe true
        }
    })
