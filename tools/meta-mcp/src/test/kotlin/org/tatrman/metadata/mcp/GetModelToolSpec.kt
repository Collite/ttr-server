// SPDX-License-Identifier: Apache-2.0
package org.tatrman.veles.mcp

import org.tatrman.meta.v1.EntityDetail
import org.tatrman.meta.v1.ModelBundleEntity
import org.tatrman.meta.v1.GetModelResponse
import org.tatrman.meta.v1.GetRolesForEntityResponse
import org.tatrman.meta.v1.ListObjectsResponse
import org.tatrman.meta.v1.ListQueriesResponse
import org.tatrman.meta.v1.ListRolesResponse
import org.tatrman.meta.v1.ModelBundle
import org.tatrman.meta.v1.ModelBundleTable
import org.tatrman.meta.v1.ObjectDescriptor
import org.tatrman.meta.v1.PackageVersion
import org.tatrman.meta.v1.RelationDetail
import org.tatrman.plan.v1.QualifiedName as ProtoQname
import org.tatrman.plan.v1.SchemaCode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.veles.client.MetadataGrpcClient

/**
 * C1.5: covers the MCP-layer behaviour of `get_model` and the `package` filter on
 * `get_entities` / `get_pattern_queries` / `get_sql_queries`.
 *
 * All metadata reads route through the gRPC [MetadataGrpcClient]; verifies argument
 * forwarding, the empty-packages guard, and the shape of the structured JSON response.
 */
class GetModelToolSpec :
    StringSpec({

        fun request(args: JsonObject = JsonObject(emptyMap())): CallToolRequest =
            CallToolRequest(CallToolRequestParams(name = "get_model", arguments = args))

        fun stubGrpc(): MetadataGrpcClient {
            val grpc = mockk<MetadataGrpcClient>(relaxed = true)
            coEvery { grpc.listRoles(any()) } returns ListRolesResponse.getDefaultInstance()
            coEvery { grpc.getRolesForEntity(any()) } returns GetRolesForEntityResponse.getDefaultInstance()
            return grpc
        }

        // ----- C1.5: empty packages -----

        "get_model rejects empty packages array with EMPTY_PACKAGES; no upstream call" {
            val grpc = stubGrpc()
            val tools = Tools(grpc)

            val args = buildJsonObject { put("packages", buildJsonArray { }) }
            val result = tools.getModelCallback(request(args))

            result.isError shouldBe true
            (result.structuredContent as JsonObject)["errorCode"]?.jsonPrimitive?.content shouldBe "EMPTY_PACKAGES"
            coVerify(exactly = 0) { grpc.getModel(any(), any(), any(), any(), any()) }
        }

        "get_model rejects missing packages key with EMPTY_PACKAGES" {
            val grpc = stubGrpc()
            val tools = Tools(grpc)

            val result = tools.getModelCallback(request(JsonObject(emptyMap())))

            result.isError shouldBe true
            (result.structuredContent as JsonObject)["errorCode"]?.jsonPrimitive?.content shouldBe "EMPTY_PACKAGES"
        }

        // ----- C1.5: happy path with populated bundle -----

        "get_model with packages=[hartland] returns a structured ModelBundle JSON" {
            val grpc = stubGrpc()
            val bundle =
                ModelBundle
                    .newBuilder()
                    .addEntities(
                        ModelBundleEntity
                            .newBuilder()
                            .setObjectDescriptor(
                                ObjectDescriptor
                                    .newBuilder()
                                    .setLocalName("store")
                                    .setQualifiedName(
                                        ProtoQname
                                            .newBuilder()
                                            .setSchemaCode(SchemaCode.ER)
                                            .setNamespace("entity")
                                            .setName("store")
                                            .build(),
                                    ).build(),
                            ).setDetail(EntityDetail.newBuilder().setLabelPlural("Stores").build())
                            .build(),
                    ).addTables(
                        ModelBundleTable
                            .newBuilder()
                            .setObjectDescriptor(
                                ObjectDescriptor
                                    .newBuilder()
                                    .setLocalName("store_sales")
                                    .setQualifiedName(
                                        ProtoQname
                                            .newBuilder()
                                            .setSchemaCode(SchemaCode.DB)
                                            .setNamespace("dbo")
                                            .setName("store_sales")
                                            .build(),
                                    ).setSourceFile("/hartland/db/facts.ttrm")
                                    .build(),
                            ).build(),
                    ).addPackageVersions(
                        PackageVersion
                            .newBuilder()
                            .setPackageName("hartland")
                            .setContentHash("deadbeef")
                            .setLoadedAt("2026-05-28T00:00:00Z")
                            .build(),
                    ).build()
            coEvery {
                grpc.getModel(listOf("hartland"), "", true, true, true)
            } returns GetModelResponse.newBuilder().setModel(bundle).build()

            val tools = Tools(grpc)
            val args =
                buildJsonObject {
                    put("packages", buildJsonArray { add(JsonPrimitive("hartland")) })
                }
            val result = tools.getModelCallback(request(args))

            (result.isError == true) shouldBe false
            val structured = result.structuredContent as JsonObject
            (structured["entities"] as JsonArray).size shouldBe 1
            (structured["tables"] as JsonArray).size shouldBe 1
            (structured["packageVersions"] as JsonArray).size shouldBe 1
            val pv = (structured["packageVersions"] as JsonArray)[0] as JsonObject
            pv["packageName"]?.jsonPrimitive?.content shouldBe "hartland"
            pv["contentHash"]?.jsonPrimitive?.content shouldBe "deadbeef"
        }

        "get_model forwards include_search_hints / include_roles / include_drill_map / locale to gRPC" {
            val grpc = stubGrpc()
            val packagesSlot = slot<List<String>>()
            val localeSlot = slot<String>()
            val includeSearchHintsSlot = slot<Boolean>()
            val includeRolesSlot = slot<Boolean>()
            val includeDrillMapSlot = slot<Boolean>()
            coEvery {
                grpc.getModel(
                    capture(packagesSlot),
                    capture(localeSlot),
                    capture(includeSearchHintsSlot),
                    capture(includeRolesSlot),
                    capture(includeDrillMapSlot),
                )
            } returns GetModelResponse.newBuilder().setModel(ModelBundle.getDefaultInstance()).build()

            val tools = Tools(grpc)
            val args =
                buildJsonObject {
                    put("packages", buildJsonArray { add(JsonPrimitive("hartland")) })
                    put("locale", JsonPrimitive("cs"))
                    put("include_search_hints", JsonPrimitive(false))
                    put("include_roles", JsonPrimitive(false))
                    put("include_drill_map", JsonPrimitive(false))
                }
            (tools.getModelCallback(request(args)).isError == true) shouldBe false

            packagesSlot.captured shouldBe listOf("hartland")
            localeSlot.captured shouldBe "cs"
            includeSearchHintsSlot.captured shouldBe false
            includeRolesSlot.captured shouldBe false
            includeDrillMapSlot.captured shouldBe false
        }

        // ----- package-led qnames: golem's PackageContext keys + prunes on
        // `<package>.er.entity.x`, but the metadata qname carries no package (it's a
        // sourceFile/path concept). get_model must derive it from sourceFile. -----

        "get_model derives package-led qnames from sourceFile (entities + relation endpoints)" {
            val grpc = stubGrpc()
            val catalogReturn =
                ProtoQname
                    .newBuilder()
                    .setSchemaCode(SchemaCode.ER)
                    .setNamespace("entity")
                    .setName("catalog_return")
                    .build()
            val returnReason =
                ProtoQname
                    .newBuilder()
                    .setSchemaCode(SchemaCode.ER)
                    .setNamespace("entity")
                    .setName("return_reason")
                    .build()

            fun entity(q: ProtoQname): ModelBundleEntity =
                ModelBundleEntity
                    .newBuilder()
                    .setObjectDescriptor(
                        ObjectDescriptor
                            .newBuilder()
                            .setLocalName(q.name)
                            .setQualifiedName(q)
                            .setSourceFile("/tmp/metadata-git/github-model/model/returns/er.ttrm")
                            .build(),
                    ).build()
            val bundle =
                ModelBundle
                    .newBuilder()
                    .addEntities(entity(catalogReturn))
                    .addEntities(entity(returnReason))
                    .addRelations(
                        RelationDetail
                            .newBuilder()
                            .setFromEntity(catalogReturn)
                            .setToEntity(returnReason)
                            .build(),
                    ).build()
            coEvery {
                grpc.getModel(listOf("returns"), "", true, true, true)
            } returns GetModelResponse.newBuilder().setModel(bundle).build()

            val tools = Tools(grpc)
            val args = buildJsonObject { put("packages", buildJsonArray { add(JsonPrimitive("returns")) }) }
            val structured = tools.getModelCallback(request(args)).structuredContent as JsonObject

            val ent0 = (structured["entities"] as JsonArray)[0] as JsonObject
            ent0["id"]?.jsonPrimitive?.content shouldBe "returns.er.entity.catalog_return"
            ent0["qname"]?.jsonPrimitive?.content shouldBe "returns.er.entity.catalog_return"

            val rel0 = (structured["relations"] as JsonArray)[0] as JsonObject
            rel0["fromEntity"]?.jsonPrimitive?.content shouldBe "returns.er.entity.catalog_return"
            rel0["toEntity"]?.jsonPrimitive?.content shouldBe "returns.er.entity.return_reason"
        }

        // ----- C1.3: package filter on get_entities / get_pattern_queries / get_sql_queries -----

        "get_entities forwards `package` to MetadataGrpcClient.listObjects" {
            val grpc = stubGrpc()
            val packageSlot = slot<String>()
            coEvery {
                grpc.listObjects(kind = "entity", packageFilter = capture(packageSlot))
            } returns ListObjectsResponse.getDefaultInstance()

            val tools = Tools(grpc)
            val args = buildJsonObject { put("package", JsonPrimitive("hartland")) }
            tools.getEntitiesCallback(
                CallToolRequest(CallToolRequestParams(name = "get_entities", arguments = args)),
            )

            packageSlot.captured shouldBe "hartland"
            coVerify { grpc.listObjects(kind = "entity", packageFilter = "hartland") }
        }

        "get_pattern_queries forwards `package` to MetadataGrpcClient.listQueries" {
            val grpc = stubGrpc()
            val packageSlot = slot<String>()
            coEvery {
                grpc.listQueries(kind = any(), packageFilter = capture(packageSlot))
            } returns ListQueriesResponse.getDefaultInstance()

            val tools = Tools(grpc)
            val args = buildJsonObject { put("package", JsonPrimitive("hartland")) }
            tools.getPatternQueriesCallback(
                CallToolRequest(CallToolRequestParams(name = "get_pattern_queries", arguments = args)),
            )

            packageSlot.captured shouldBe "hartland"
        }

        "get_sql_queries forwards `package` to MetadataGrpcClient.listQueries" {
            val grpc = stubGrpc()
            val packageSlot = slot<String>()
            coEvery {
                grpc.listQueries(kind = any(), packageFilter = capture(packageSlot))
            } returns ListQueriesResponse.getDefaultInstance()

            val tools = Tools(grpc)
            val args = buildJsonObject { put("package", JsonPrimitive("hartland")) }
            tools.getSqlQueriesCallback(
                CallToolRequest(CallToolRequestParams(name = "get_sql_queries", arguments = args)),
            )

            packageSlot.captured shouldBe "hartland"
        }
    })
